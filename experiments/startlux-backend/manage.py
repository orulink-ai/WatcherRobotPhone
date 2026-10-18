"""Start/stop the LAN gateway, reusing an existing Instant model worker."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import urllib.request

HERE = Path(__file__).resolve().parent

def health(url):
    try:
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(url+'/health', timeout=3) as response:
            return json.load(response)
    except Exception:
        return None

def owned(pid, fragment):
    result=subprocess.run(['ps','-p',str(pid),'-o','command='],capture_output=True,text=True)
    return result.returncode==0 and fragment in result.stdout

def start_process(args, log):
    with log.open('ab') as f:
        return subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=f, stderr=subprocess.STDOUT, start_new_session=True).pid

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('command',choices=['start','stop','status'])
    parser.add_argument('--runtime-dir',type=Path,required=True)
    parser.add_argument('--startlux-root',type=Path)
    parser.add_argument('--model',type=Path)
    parser.add_argument('--host')
    parser.add_argument('--port',type=int,default=8094)
    args=parser.parse_args();runtime=args.runtime_dir.expanduser().resolve();runtime.mkdir(parents=True,exist_ok=True);statefile=runtime/'service.json'
    state=json.loads(statefile.read_text()) if statefile.exists() else {}
    if args.command=='stop':
        pid=state.get('gateway_pid')
        if pid and owned(pid,str(HERE/'serve.py')):
            os.kill(pid,signal.SIGTERM)
            for _ in range(40):
                if not owned(pid,str(HERE/'serve.py')):break
                time.sleep(.1)
        print('LAN gateway stopped; loopback worker retained');return
    if args.command=='status':
        print(json.dumps({**state,'health':health(state.get('url','http://127.0.0.1:8094'))},indent=2));return
    if state.get('gateway_pid') and owned(state['gateway_pid'],str(HERE/'serve.py')):
        if not health(state['url']):raise SystemExit('Gateway exists but health check failed; inspect logs')
        print('Already running: '+state['url']);return
    host=args.host
    if not host:
        host=subprocess.check_output(['ipconfig','getifaddr','en0'],text=True).strip()
    ip=ipaddress.ip_address(host)
    if not ip.is_loopback and not any(ip in ipaddress.ip_network(n) for n in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16')):
        raise SystemExit('Select a private LAN address')
    upstream='http://127.0.0.1:8091';ready=health(upstream);worker_pid=None
    if not ready:
        if args.startlux_root is None or args.model is None:
            raise SystemExit('Supply --startlux-root and --model to start the model worker')
        python=args.startlux_root.resolve()/'.venv/bin/python';entry=args.startlux_root.resolve()/'.venv/bin/startlux-decide'
        if not python.exists() or not args.model.is_dir():raise SystemExit('Model or StartLux runtime missing')
        env=os.environ.copy();env.update(HF_HUB_OFFLINE='1',TRANSFORMERS_OFFLINE='1')
        with (runtime/'worker.log').open('ab') as f:
            worker_pid=subprocess.Popen([str(entry),'serve','--model',str(args.model.resolve()),'--backend','mlx','--host','127.0.0.1','--port','8091','--name','startlux-4b-instant','--max-inflight','1','--think-mode','never','--no-none-check'],stdin=subprocess.DEVNULL,stdout=f,stderr=subprocess.STDOUT,start_new_session=True,env=env).pid
        for _ in range(120):
            ready=health(upstream)
            if ready:break
            if not owned(worker_pid,str(entry)):raise SystemExit('Model worker exited; inspect worker.log')
            time.sleep(1)
    if not ready or ready.get('model')!='startlux-4b-instant' or ready.get('kind')!='instant':
        raise SystemExit('Port8091 must serve startlux-4b-instant / instant')
    pid=start_process([sys.executable,str(HERE/'serve.py'),'--host',host,'--port',str(args.port),'--upstream',upstream,'--access-log',str(runtime/'access.jsonl')],runtime/'gateway.log');url=f'http://{host}:{args.port}'
    for _ in range(50):
        if health(url):break
        if not owned(pid,str(HERE/'serve.py')):raise SystemExit('Gateway exited; inspect gateway.log')
        time.sleep(.2)
    else:
        os.kill(pid,signal.SIGTERM);raise SystemExit('Gateway readiness timed out')
    state={'url':url,'gateway_pid':pid,'worker_pid_started_by_manager':worker_pid,'upstream':upstream,'model':'startlux-4b-instant','authentication':'none','transport':'HTTP/1.1 persistent connections; no TLS on LAN','runtime_dir':str(runtime)}
    statefile.write_text(json.dumps(state,indent=2));print(json.dumps(state,indent=2))
if __name__=='__main__':main()
