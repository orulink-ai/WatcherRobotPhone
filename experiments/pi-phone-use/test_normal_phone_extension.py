"""Ordinary PI routing contracts; only fake models/workers are exercised."""

import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

import pytest

HERE = Path(__file__).resolve().parent


def classify(text):
    spec = importlib.util.spec_from_file_location("phone_intent", HERE / "phone_intent.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.classify(text)


@pytest.mark.parametrize(
    "text",
    [
        "你好",
        "你能控制手机吗",
        "小红书是什么",
        "帮我写一个打开文件的Python函数",
        "解释一下手机无障碍树如何工作",
        "不要读取手机屏幕",
    ],
)
def test_chat_and_capability_questions_do_not_route_to_phone(text):
    assert classify(text) == {"kind": "chat"}


def test_explicit_phone_request_preserves_authorized_target_and_exit():
    text = (
        "在小红书搜索“小狮日记”，打开第一篇帖子，先点赞、收藏，然后取消点赞、取消收藏，返回桌面。"
    )
    result = classify(text)
    assert result["kind"] == "phone" and result["exitRequested"] is True
    assert result["task"]["query"] == "小狮日记"
    assert result["task"]["target"] == {"kind": "first"}
    assert result["task"]["restoreAfter"] is True
    assert result["task"]["goal"] == text


def test_explicit_unsupported_phone_mutations_fail_closed():
    assert classify("在小红书搜索“猫”，打开第一篇并评论“你好”。")["kind"] == "unsupported"


def test_question_words_inside_a_query_remain_data():
    result = classify("在小红书搜索“如何学习”，打开第一篇帖子，只点赞。")
    assert result["kind"] == "phone" and result["task"]["query"] == "如何学习"


def run_pi(tmp_path, prompt):
    if shutil.which("pi") is None:
        pytest.skip("PI not installed")
    worker = tmp_path / "fake_worker.py"
    worker.write_text("""import json,os,sys
from pathlib import Path
for line in sys.stdin:
 r=json.loads(line);c=r['command']
 with Path(os.environ['FAKE_PHONE_LOG']).open('a') as out:out.write(c+'\\n')
 value={'state':{},'options':{},'done':True} if c=='observe' else {'status':'success','terminalTask':{'kind':'read'},'finalState':{}}
 print(json.dumps({'id':r['id'],'result':value}),flush=True)
 if c=='finish':break
""")
    wrong_worker = tmp_path / "wrong_worker.py"
    wrong_worker.write_text(
        "raise SystemExit('Provider environment did not select the fixture worker')\n"
    )
    profile = tmp_path / "profile.py"
    profile.write_text(
        "import json\nprint(json.dumps("
        + repr(
            {
                "env": {
                    "PHONE_BENCH_PYTHON": sys.executable,
                    "PHONE_BENCH_WORKER": str(worker),
                    "FAKE_PHONE_LOG": str(tmp_path / "phone.log"),
                }
            }
        )
        + "))\n"
    )
    fixture = tmp_path / "chat_fixture.ts"
    fixture.write_text("""import {writeFileSync,appendFileSync} from 'node:fs';
import {createAssistantMessageEventStream} from '@earendil-works/pi-ai';
export default function(pi){
 pi.registerProvider('chat-fixture',{baseUrl:'stdio://chat',apiKey:'fake',api:'chat-fixture-api',models:[{id:'fixture',name:'fixture',reasoning:false,input:['text'],contextWindow:8192,maxTokens:100,cost:{input:0,output:0,cacheRead:0,cacheWrite:0}}],streamSimple(model){
  appendFileSync(process.env.FAKE_CHAT_LOG,'chat\\n');const stream=createAssistantMessageEventStream();
  const message={role:'assistant',content:[{type:'text',text:'CHAT'}],api:model.api,provider:model.provider,model:model.id,timestamp:Date.now(),stopReason:'stop',usage:{input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}}};
  queueMicrotask(()=>{stream.push({type:'start',partial:message});stream.push({type:'done',reason:'stop',message});stream.end()});return stream;
 }});
 pi.on('session_shutdown',(_e,ctx)=>writeFileSync(process.env.FAKE_RESTORE_LOG,JSON.stringify({model:ctx.model?.provider,tools:pi.getActiveTools(),thinking:pi.getThinkingLevel()})));
}
""")
    result = subprocess.run(
        [
            "pi",
            "--no-extensions",
            "-e",
            str(HERE / "normal-phone-extension.ts"),
            "-e",
            str(fixture),
            "--no-mcp",
            "--no-skills",
            "--no-context-files",
            "--no-prompt-templates",
            "--no-themes",
            "--offline",
            "--no-builtin-tools",
            "--provider",
            "chat-fixture",
            "--model",
            "fixture",
            "--thinking",
            "off",
            "--mode",
            "json",
            "--print",
            prompt,
        ],
        cwd=tmp_path,
        stdin=subprocess.DEVNULL,
        capture_output=True,
        text=True,
        timeout=30,
        env={
            **os.environ,
            "PHONE_BENCH_PYTHON": sys.executable,
            "PHONE_PROFILE_SCRIPT": str(profile),
            "PHONE_BENCH_WORKER": str(wrong_worker),
            "FAKE_CHAT_LOG": str(tmp_path / "chat.log"),
            "FAKE_RESTORE_LOG": str(tmp_path / "restore.json"),
            "PI_OFFLINE": "1",
            "PI_TELEMETRY": "0",
        },
    )
    assert result.returncode == 0, result.stderr
    assert (tmp_path / "restore.json").exists(), result.stderr
    return result, json.loads((tmp_path / "restore.json").read_text())


def test_real_pi_print_phone_input_temporarily_routes_and_restores_chat(tmp_path):
    result, restored = run_pi(tmp_path, "读取当前手机屏幕")
    assert (tmp_path / "phone.log").read_text().splitlines() == ["observe", "finish"]
    assert not (tmp_path / "chat.log").exists()
    assert restored["model"] == "chat-fixture"
    assert restored["thinking"] == "off"
    assert "phone_observe" not in restored["tools"] and "phone_action" not in restored["tools"]
    assert "phone-normal-route" in result.stdout


def test_real_pi_print_capability_question_uses_chat_without_phone(tmp_path):
    _result, restored = run_pi(tmp_path, "你能控制手机吗")
    assert (tmp_path / "chat.log").read_text().splitlines() == ["chat"]
    assert not (tmp_path / "phone.log").exists()
    assert restored["model"] == "chat-fixture"


def test_pending_input_stop_shutdown_and_failed_switch_restore_offline(tmp_path):
    if shutil.which("pi") is None:
        pytest.skip("PI not installed")
    audit = tmp_path / "transitions.json"
    probe = tmp_path / "probe.ts"
    probe.write_text(
        """import {writeFileSync} from 'node:fs';
import {registerNormalPhoneRouting} from """
        + json.dumps(str(HERE / "normal-phone-extension.ts"))
        + """;
export default async function(realPi){
 function fixture(helper,failSwitch=false){
  const handlers=new Map(),entries=[];let model={provider:'chat',id:'original'},tools=['read','bash'],thinking='high',aborts=0,switches=[];
  const phone={provider:'phone-control',id:'auto'};
  const api={on(n,h){if(!handlers.has(n))handlers.set(n,[]);handlers.get(n).push(h)},registerProvider(){},registerTool(){},registerCommand(){},appendEntry(n,v){entries.push({n,v})},getActiveTools(){return tools},setActiveTools(v){tools=[...v]},getThinkingLevel(){return thinking},setThinkingLevel(v){thinking=v},async setModel(v){switches.push(v.provider);if(failSwitch&&v.provider==='phone-control')return false;model=v;return true}};
  const ctx={get model(){return model},modelRegistry:{find(){return phone}},isIdle(){return true},hasUI:false,ui:{},abort(){aborts++}};
  const emit=async(n,e={})=>{let result;for(const h of handlers.get(n)||[])result=await h(e,ctx);return result};
  registerNormalPhoneRouting(api,{jsonProcess:helper});
  return {emit,state:()=>({model:model.provider,tools,thinking,aborts,switches,entries})};
 }
 let release;const delayed=new Promise(r=>release=r);
 const first=fixture(async(script)=>script.endsWith('phone_intent.py')?delayed:{env:{PHONE_BENCH_PYTHON:'fake',PHONE_BENCH_WORKER:'fake'}});
 await first.emit('session_start');
 const pending=first.emit('input',{text:'读取手机屏幕'});
 await Promise.resolve();
 const concurrent=await first.emit('input',{text:'你好'});
 release({kind:'phone',task:{kind:'read'}});await pending;
 await first.emit('agent_start');
 await first.emit('input',{text:'停止当前手机任务'});
 await first.emit('agent_end');
 let lateRelease;const latePromise=new Promise(r=>lateRelease=r);
 const late=fixture(async()=>latePromise);
 const lateInput=late.emit('input',{text:'读取手机屏幕'});await Promise.resolve();
 await late.emit('session_shutdown');lateRelease({kind:'phone',task:{kind:'read'}});await lateInput;
 const unavailable=fixture(async(script)=>script.endsWith('phone_intent.py')?{kind:'phone',task:{kind:'read'}}:{env:{}},true);
 const denied=await unavailable.emit('input',{text:'读取手机屏幕'});
 writeFileSync("""
        + json.dumps(str(audit))
        + """,JSON.stringify({first:first.state(),concurrent,late:late.state(),unavailable:unavailable.state(),denied}));
 realPi.registerCommand('probe',{description:'offline state probe',handler:async()=>{}});
}
"""
    )
    result = subprocess.run(
        [
            "pi",
            "--no-extensions",
            "-e",
            str(probe),
            "--no-mcp",
            "--no-skills",
            "--no-context-files",
            "--offline",
            "--mode",
            "json",
            "--print",
            "/probe",
        ],
        cwd=tmp_path,
        capture_output=True,
        text=True,
        timeout=25,
        env={**os.environ, "PI_OFFLINE": "1", "PI_TELEMETRY": "0"},
    )
    assert result.returncode == 0, result.stderr
    data = json.loads(audit.read_text())
    assert data["concurrent"]["action"] == "handled"
    assert data["first"]["aborts"] == 1
    assert data["first"]["model"] == "chat" and data["first"]["thinking"] == "high"
    assert data["first"]["tools"] == ["read", "bash"]
    assert data["late"]["switches"] == [] and data["late"]["model"] == "chat"
    assert data["denied"]["action"] == "handled"
    assert data["unavailable"]["model"] == "chat" and data["unavailable"]["thinking"] == "high"
