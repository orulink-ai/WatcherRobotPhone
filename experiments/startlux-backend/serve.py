"""LAN gateway (no authentication, explicitly requested) for an existing loopback StartLux worker."""
import argparse
import asyncio
import ipaddress
import json
from pathlib import Path
import time
from urllib.parse import urlsplit
from aiohttp import web, ClientSession, ClientTimeout, TCPConnector, ClientError

LAN_NETWORKS = tuple(ipaddress.ip_network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))
ALIASES = {'/v1/systemone': '/v1/systemone', '/v1/decisions': '/v1/decisions', '/api/alpha/decisions': '/v1/decisions', '/api/v1/decisions': '/v1/decisions', '/api/v1/systemone': '/v1/systemone'}
ROUTES = {*ALIASES, '/v1/models'}
CLIENT = web.AppKey('client', ClientSession)


def create_app(upstream, access_log=None):
    parsed = urlsplit(upstream)
    if parsed.scheme != 'http' or parsed.hostname not in {'127.0.0.1', 'localhost', '::1'} or parsed.path not in ('', '/') or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('upstream must be a loopback HTTP origin')
    upstream = upstream.rstrip('/')
    busy = False
    @web.middleware
    async def guard(request, handler):
        nonlocal busy
        started = time.perf_counter()
        decision = request.method == 'POST' and request.path in ALIASES
        acquired = False
        try:
            if decision and busy:
                response = web.json_response({'error': 'model busy; request not queued or executed'}, status=429, headers={'Retry-After': '1'})
            else:
                if decision:
                    busy = True
                    acquired = True
                response = await handler(request)
        except web.HTTPException as exc:
            response = web.json_response({'error': exc.reason}, status=exc.status)
        finally:
            if acquired:
                busy = False
        if access_log:
            record = {'time': time.time(), 'route': request.path if request.path in ROUTES or request.path == '/health' else 'unknown', 'status': response.status, 'elapsed_ms': round((time.perf_counter()-started)*1000, 3)}
            with Path(access_log).open('a') as f:
                f.write(json.dumps(record)+'\n')
        return response
    app = web.Application(client_max_size=8*1024*1024, middlewares=[guard])
    async def lifetime(app):
        async with ClientSession(timeout=ClientTimeout(total=90), connector=TCPConnector(limit=1, keepalive_timeout=300), trust_env=False) as client:
            app[CLIENT] = client
            yield
    app.cleanup_ctx.append(lifetime)
    async def health(request):
        try:
            async with app[CLIENT].get(upstream+'/health', timeout=ClientTimeout(total=3)) as r:
                body = await r.json()
                ok = r.status == 200 and body.get('kind') == 'instant' and body.get('model') == 'startlux-4b-instant'
                return web.json_response({'status': 'ok' if ok else 'unavailable', 'model': 'startlux-4b-instant', 'backend': 'mlx', 'upstream_ready': ok}, status=200 if ok else 503)
        except (ClientError, asyncio.TimeoutError, ValueError):
            return web.json_response({'status': 'unavailable', 'model': 'startlux-4b-instant'}, status=503)
    async def proxy(request):
        if request.query_string:
            return web.json_response({'error': 'query parameters are not supported'}, status=400)
        data = await request.read() if request.method == 'POST' else None
        if data is not None:
            try:
                body = json.loads(data)
                if not isinstance(body, dict):
                    raise ValueError
            except (ValueError, UnicodeError):
                return web.json_response({'error': 'expected a JSON object'}, status=422)
            if body.get('model') not in (None, 'startlux-4b-instant', 'startlux-instant', 'StartLux-Decision-v1.1-4B-Instant-MLX-8bit'):
                return web.json_response({'error': 'model not served; use startlux-4b-instant'}, status=422)
            body.update(think_mode='never', none_check=False, allow_none=False)
            data = json.dumps(body).encode()
        try:
            async with app[CLIENT].request(request.method, upstream+ALIASES.get(request.path,request.path), data=data, headers={'Content-Type': 'application/json'} if data is not None else {}, allow_redirects=False) as r:
                headers = {k: v for k,v in r.headers.items() if k.lower() in {'content-type', 'x-request-id', 'x-typesafe-request-id', 'retry-after'}}
                return web.Response(body=await r.read(), status=r.status, headers=headers)
        except asyncio.TimeoutError:
            return web.json_response({'error': 'model request timed out; not retried'}, status=504)
        except ClientError:
            return web.json_response({'error': 'model worker unavailable'}, status=502)
    app.router.add_get('/health', health)
    app.router.add_get('/v1/models', proxy)
    for route in ALIASES:
        app.router.add_post(route, proxy)
    return app

if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--host', required=True)
    parser.add_argument('--port', type=int, default=8094)
    parser.add_argument('--upstream', default='http://127.0.0.1:8091')
    parser.add_argument('--access-log', type=Path)
    args=parser.parse_args()
    address=ipaddress.ip_address(args.host)
    if not address.is_loopback and not any(address in n for n in LAN_NETWORKS):
        parser.error('host must be a private LAN IPv4 address or loopback')
    web.run_app(create_app(args.upstream,args.access_log),host=args.host,port=args.port,keepalive_timeout=300,access_log=None)
