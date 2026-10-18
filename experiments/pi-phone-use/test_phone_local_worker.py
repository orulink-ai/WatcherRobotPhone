"""Phone-only backend integration with fake model/device and real policy/lock."""
import asyncio
import fcntl
import io
import json
import os
from pathlib import Path

import pytest

import phone_local_worker as entry
import worker
from own_mcp import McpError
from providers import ProviderError


class PhoneDouble:
    def __init__(self):
        self.index = 0
        self.snapshot = None
        self.operations, self.phases, self.model_calls, self.actions = [], [], [], []
        self.error = None
        self.closed = False

    def observe(self):
        self.index += 1
        self.snapshot = {'state': {'generation': self.index, 'observation': {'kind': 'screen'}},
                         'options': {f'CLICK:node-{self.index}': 'current safe choice'},
                         'done': len(self.actions) == 2}
        return self.snapshot

    def act(self, choice):
        self.actions.append(choice)
        return self.observe()

    def record_model(self, value):
        self.model_calls.append(value)
        self.phases.append({'kind':'model','name':'fixture','ms':value['latencyMs']})

    def finish(self, extra=None):
        self.closed = True
        return {'status':'success' if len(self.actions)==2 else 'failed',**(extra or {})}


class ClientDouble:
    def __init__(self):
        self.mode, self.decisionStyle = 'local','flat'
        self.calls = []
        self.closed = False

    async def decide(self, backend, state, options):
        self.calls.append((backend,state['generation']))
        if len(self.calls)==1:
            raise ProviderError('private error text',network={},latency_ms=1,
                                exception_type='ReadTimeout',stage='read_response')
        return {'choice':next(iter(options)),'latencyMs':1,'usage':{},'network':{}}

    def summary(self):
        primary=[call for call in self.calls if call[0]=='jev']
        fallback=[call for call in self.calls if call[0]=='deepseek']
        return {'primaryRequests':len(primary),'primarySuccesses':max(0,len(primary)-1),
                'primaryFailures':int(bool(primary)),'fallbackRequests':len(fallback),
                'fallbackSuccesses':len(fallback),'primaryServedModels':['local-fixture']}

    async def close(self):
        self.closed = True


@pytest.fixture
def dependencies(monkeypatch,tmp_path):
    phone, client = PhoneDouble(),ClientDouble()
    captured={}
    def runtime_factory(trial,task,**kwargs):
        captured.update(task=task,**kwargs)
        return phone
    def client_factory(key,**kwargs):
        assert key==''
        return client
    monkeypatch.setattr(entry,'_load_dependencies',lambda:(worker.serve,runtime_factory,client_factory))
    monkeypatch.setenv('PHONE_CONTROL_LOCK',str(tmp_path/'control.lock'))
    monkeypatch.setenv('PHONE_BENCH_OUTPUT_DIR',str(tmp_path/'output'))
    monkeypatch.setenv('PHONE_TERMINAL_GOAL','在小红书搜索“普通新词”，打开第一篇帖子，只点赞。')
    monkeypatch.setenv('PHONE_TERMINAL_TASK','{"kind":"must-not-rewrite"}')
    monkeypatch.delenv('OPENROUTER_API_KEY',raising=False)
    return phone,client,captured


def drive(commands, provider='jev-auto'):
    output=io.StringIO()
    asyncio.run(entry.main(['--provider',provider,'--trial-id','fixture'],
        input_stream=io.StringIO('\n'.join(json.dumps({'id':i,**c}) for i,c in enumerate(commands))),
        output_stream=output))
    return [json.loads(line) for line in output.getvalue().splitlines()]


def test_primary_refresh_cloud_fallback_then_next_primary_without_openrouter_key(dependencies):
    phone,client,captured=dependencies
    replies=drive([{'command':'observe'},{'command':'decide'},{'command':'observe'},
        {'command':'decide'},{'command':'act','choice':'CLICK:node-2'},
        {'command':'decide'},{'command':'act','choice':'CLICK:node-3'},{'command':'finish'}])
    assert replies[1]['result']['kind']=='refresh_required'
    assert replies[3]['result']['selectedBackend']=='deepseek'
    assert replies[5]['result']['selectedBackend']=='jev'
    assert client.calls==[('jev',1),('deepseek',2),('jev',3)]
    assert captured['task']['query']=='普通新词'
    assert captured['task']['desired']=={'liked':True}
    assert captured['task']['restoreAfter'] is False
    assert captured['exit_requested'] is False
    result=replies[-1]['result']
    assert result['status']=='success'
    assert result['comparisonStats']==client.summary()
    assert result['primaryRequests']==2 and result['fallbackSuccesses']==1
    assert result['terminalTask']==captured['task']
    assert phone.closed and client.closed


@pytest.mark.parametrize('bad_command',[{'command':'act','choice':'CLICK:node-1'}, {'command':'decide'}])
def test_fallback_refresh_barrier_prevents_unauthorized_decision_or_action(dependencies,bad_command):
    phone,client,_captured=dependencies
    replies=drive([{'command':'observe'},{'command':'decide'},bad_command,{'command':'finish'}])
    assert 'error' in replies[2]
    assert phone.actions==[] and client.calls==[('jev',1)]
    assert replies[-1]['result']['status']=='failed'


def test_existing_standard_lock_blocks_backend_before_device_or_models(dependencies):
    phone,client,_captured=dependencies
    with open(os.environ['PHONE_CONTROL_LOCK'],'a+') as lease:
        fcntl.flock(lease,fcntl.LOCK_EX|fcntl.LOCK_NB)
        with pytest.raises(entry.PhoneControlBusy):
            drive([{'command':'observe'}])
    assert phone.index==0 and client.calls==[]


def test_inherited_lock_fd_keeps_parent_lease_locked(tmp_path):
    path=tmp_path/'control.lock'
    with open(path,'a+') as parent:
        fcntl.flock(parent,fcntl.LOCK_EX|fcntl.LOCK_NB)
        with entry.ControlLock(path,inherited_fd=parent.fileno()):
            pass
        with open(path,'a+') as competitor:
            with pytest.raises(BlockingIOError):
                fcntl.flock(competitor,fcntl.LOCK_EX|fcntl.LOCK_NB)


def test_inherited_fd_must_reference_the_standard_lock_path(tmp_path):
    with open(tmp_path/'wrong.lock','a+') as wrong:
        with pytest.raises(ValueError):
            with entry.ControlLock(tmp_path/'control.lock',inherited_fd=wrong.fileno()):
                pass


def test_local_wrapper_uses_startlux_and_same_cloud_fallback_without_key_reads(monkeypatch):
    class Backend:
        def __init__(self):
            self.calls = []
        async def decide(self,provider,state,options):
            self.calls.append(provider)
            return {'choice':'WAIT','latencyMs':1}
        async def close(self):
            pass
    primary,fallback=Backend(),Backend()
    monkeypatch.setattr(entry,'read_cloud_key',lambda:pytest.fail('Injected fallback must not read credentials'))
    client=entry.LocalPhoneDecisionClient('',primary_client=primary,fallback_client=fallback,experience=None)
    async def run():
        await client.decide('jev',{'goal':'fixture'},{'WAIT':'wait'})
        await client.decide('deepseek',{'goal':'fixture'},{'WAIT':'wait'})
        await client.close()
    asyncio.run(run())
    assert primary.calls==['startlux-instant'] and fallback.calls==['deepseek']
    assert client.summary()['primaryBackend']=='lan-startlux'


def test_terminal_auto_cli_alias_still_runs_logical_jev_auto(dependencies):
    _phone, client, _captured = dependencies
    replies = drive([{'command': 'observe'}, {'command': 'decide'}, {'command': 'finish'}],
                    provider='terminal-auto')
    assert replies[1]['result']['kind'] == 'refresh_required'
    assert client.calls == [('jev', 1)]
    assert replies[-1]['result']['provider'] == 'jev-auto'


def test_canonical_cloud_key_path_precedes_legacy_alias(monkeypatch, tmp_path):
    canonical = tmp_path / 'canonical.key'
    canonical.write_text('fixture-canonical\n')
    legacy = tmp_path / 'legacy.key'
    legacy.write_text('fixture-legacy\n')
    monkeypatch.setenv('PHONE_CLOUD_DS_API_KEY_FILE', str(canonical))
    monkeypatch.setenv('PHONE_CLOUD_DEEPSEEK_KEY_FILE', str(legacy))
    assert entry.read_cloud_key() == 'fixture-canonical'


def test_default_cloud_key_loader_is_reused_without_reading_real_credentials(monkeypatch):
    monkeypatch.delenv('PHONE_CLOUD_DS_API_KEY_FILE', raising=False)
    monkeypatch.delenv('PHONE_CLOUD_DEEPSEEK_KEY_FILE', raising=False)
    monkeypatch.setattr(entry, 'cloud_deepseek_key', lambda: 'fixture-production-key')
    assert entry.read_cloud_key() == 'fixture-production-key'


def test_production_setup_failure_keeps_task_and_trusted_zero_request_stats(dependencies, monkeypatch):
    _phone, client, _captured = dependencies

    def broken_factory(trial_id, task, **options):
        (Path(options['base']) / trial_id).mkdir(parents=True)
        raise McpError('private-initialization-failure', stage='initialize', exception_type='ToolError')

    monkeypatch.setattr(entry, '_load_dependencies', lambda: (
        worker.serve, broken_factory, lambda *_args, **_options: client))
    replies = drive([{'command': 'observe'}, {'command': 'finish', 'piElapsedMs': 82}])
    result = json.loads((Path(os.environ['PHONE_BENCH_OUTPUT_DIR']) / 'fixture/result.json').read_text())
    assert replies[-1]['result'] == result
    assert result['status'] == 'failed' and result['setupFailed'] is True
    assert result['stopCode'] == 'MCP_SETUP_FAILED'
    assert result['terminalTask']['query'] == '普通新词'
    assert result['terminalTask']['desired'] == {'liked': True}
    assert result['comparisonStats'] == client.summary()
    assert result['primaryRequests'] == result['fallbackRequests'] == 0
    assert result['piElapsedMs'] == 82 and result['noInteractionsDispatched'] is True
    assert result['errorDetails'] == {'exceptionType': 'ToolError', 'stage': 'initialize'}
    assert 'private-initialization-failure' not in json.dumps(result)
    assert client.closed
