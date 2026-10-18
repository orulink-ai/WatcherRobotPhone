import asyncio
from pathlib import Path
import sys
import httpx
import pytest
from aiohttp import web
from serve import create_app
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'pi-phone-use'))
from local_providers import local_settings,StartLuxClient

@pytest.mark.parametrize('url', ['https://example.com','http://192.168.50.1:8091','http://a:b@127.0.0.1:8091','http://127.0.0.1:8091/x'])
def test_upstream_must_remain_loopback(url):
    with pytest.raises(ValueError):create_app(url)

@pytest.mark.parametrize('url,allowed', [('http://192.168.50.26:8094/v1/systemone',True),('http://8.8.8.8:8094/v1/systemone',False),('http://example.com/v1/systemone',False),('http://169.254.1.2/v1/systemone',False)])
def test_project_lan_opt_in(url,allowed,monkeypatch):
    monkeypatch.setenv('PHONE_BENCH_STARTLUX_INSTANT_URL',url)
    with pytest.raises(ValueError):local_settings('startlux-instant')
    monkeypatch.setenv('PHONE_BENCH_STARTLUX_ALLOW_LAN','1')
    if allowed:assert local_settings('startlux-instant')[0]==url
    else:
        with pytest.raises(ValueError):local_settings('startlux-instant')

def test_no_key_needed_and_gateway_preserves_contract():
    async def run():
        seen=[]; entered=asyncio.Event(); release=asyncio.Event()
        async def model(request):
            assert 'Authorization' not in request.headers
            if request.path=='/health':return web.json_response({'model':'startlux-4b-instant','kind':'instant'})
            payload=await request.json();seen.append(payload)
            if payload.get('state',{}).get('hold'):
                entered.set();await release.wait()
            assert payload['think_mode']=='never' and payload['none_check'] is False
            return web.json_response({'model':'startlux-4b-instant','answers':{'action':{'type':'choice','choice':'WAIT','confidence':1,'probabilities':{'WAIT':1}}}},headers={'x-request-id':'fixed-id'})
        async def start(app):
            runner=web.AppRunner(app);await runner.setup();site=web.TCPSite(runner,'127.0.0.1',0);await site.start();return runner,site._server.sockets[0].getsockname()[1]
        worker=web.Application();worker.router.add_get('/health',model);worker.router.add_post('/v1/systemone',model);worker.router.add_post('/v1/decisions',model)
        wr,port=await start(worker);gr=None
        try:
            gr,gp=await start(create_app(f'http://127.0.0.1:{port}'))
            async with httpx.AsyncClient(trust_env=False) as c:
                url=f'http://127.0.0.1:{gp}'
                assert (await c.get(url+'/health')).status_code==200
                r=await c.post(url+'/v1/systemone',json={'model':'startlux-4b-instant','state':{},'questions':{'action':{'type':'choice','criteria':{'WAIT':'wait'}}},'think_mode':'always'})
                assert r.status_code==200 and r.headers['x-request-id']=='fixed-id'
                assert r.json()['answers']['action']['choice']=='WAIT'
                assert (await c.post(url+'/v1/systemone',json={'model':'not-loaded'})).status_code==422
                assert (await c.post(url+'/v1/systemone',content='[]')).status_code==422
                assert (await c.get(url+'/not-supported')).status_code==404
                assert len(seen)==1
                assert (await c.post(url+'/api/alpha/decisions',json={'state':{},'questions':{}})).status_code==200
                held=asyncio.create_task(c.post(url+'/v1/systemone',json={'state':{'hold':True}}))
                await asyncio.wait_for(entered.wait(),2)
                rejected=await c.post(url+'/v1/decisions',json={'state':{}})
                assert rejected.status_code==429 and rejected.headers['retry-after']=='1'
                release.set();assert (await held).status_code==200
                assert len(seen)==3
        finally:
            if gr:await gr.cleanup()
            await wr.cleanup()
    asyncio.run(run())
