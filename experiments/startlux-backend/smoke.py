"""Real model API smoke test only; does not connect to or operate a phone."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import sys
import time
import httpx
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'pi-phone-use'))
from local_providers import StartLuxClient
from providers import _jev_payload, validate_choice

async def run(url, output):
    output.mkdir(parents=True, exist_ok=True)
    records=[]
    async with httpx.AsyncClient(trust_env=False, timeout=90, limits=httpx.Limits(max_connections=1,max_keepalive_connections=1,keepalive_expiry=None)) as client:
        health=(await client.get(url+'/health')).json()
        assert health['status']=='ok' and health['model']=='startlux-4b-instant'
        models=(await client.get(url+'/v1/models')).json()
        for route in ('/v1/systemone','/v1/decisions','/api/alpha/decisions'):
            payload={'model':'startlux-4b-instant','state':{'task':'Calculate 2 + 3.'},'questions':{'sum':{'type':'choice','instructions':'Select the correct sum.','criteria':{'five':'5','nine':'9'}}},'return_details':True}
            events=[];t=time.perf_counter()
            async def trace(name,info):events.append({'event':name,'offset_ms':(time.perf_counter()-t)*1000})
            response=await client.post(url+route,json=payload,extensions={'trace':trace});response.raise_for_status();body=response.json();choice=validate_choice(body['answers']['sum'],payload['questions']['sum']['criteria']);assert choice=='five'
            records.append({'route':route,'request':payload,'response':body,'elapsed_ms':(time.perf_counter()-t)*1000,'network_events':events})
        options={'OPEN_APP:settings':'Open the Settings application','OPEN_APP:clock':'Open the Clock application','WAIT':'Wait for loading','BLOCKED':'Stop because the requested application is unavailable'}
        state={'goal':'Open the Settings application.','screen':{'app':'launcher','visible_apps':['Settings','Clock']},'history':[]}
        payload=_jev_payload(state, options, True, 'heads');payload['model']='startlux-4b-instant';payload['return_details']=True
        t=time.perf_counter();response=await client.post(url+'/api/alpha/decisions',json=payload);response.raise_for_status();body=response.json();assert set(body['answers'])==set(payload['questions'])
        for name,question in payload['questions'].items():validate_choice(body['answers'][name],question['criteria'])
        assert body['answers']['operation']['choice']=='OPEN_APP' and body['answers']['open_app_target']['choice']=='OPEN_APP:settings'
        records.append({'route':'/api/alpha/decisions','style':'project_jev_heads','request':payload,'response':body,'elapsed_ms':(time.perf_counter()-t)*1000})
        invalid=await client.post(url+'/v1/systemone',json={'model':'not-loaded'})
        assert invalid.status_code==422
    os.environ['PHONE_BENCH_STARTLUX_ALLOW_LAN']='1';os.environ['PHONE_BENCH_STARTLUX_INSTANT_URL']=url+'/v1/systemone'
    project=StartLuxClient('startlux-instant')
    project_results=[]
    try:
        for _ in range(2):
            answer=await project.decide('startlux-instant',state,options)
            assert answer['choice']=='OPEN_APP:settings'
            assert answer['servedModel']=='startlux-4b-instant'
            project_results.append(answer)
    finally:await project.close()
    result={'passed':True,'phone_actions_executed':False,'url':url,'health':health,'models':models,'api_records':records,'project_client_results':project_results,'invalid_model_status':invalid.status_code,'scope':'Calls from this Mac through its LAN interface address; other-device connectivity not yet tested.'}
    (output/'smoke-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps({'passed':True,'url':url,'api_elapsed_ms':[round(r['elapsed_ms'],2) for r in records],'project_elapsed_ms':[round(r['latencyMs'],2) for r in project_results],'project_choices':[r['choice'] for r in project_results]},ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--url',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();asyncio.run(run(a.url.rstrip('/'),a.output))
