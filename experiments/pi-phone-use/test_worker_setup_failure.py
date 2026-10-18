"""Explicit custom-runtime setup failures retain safe, durable diagnostics."""
import asyncio
import io
import json

import pytest

import worker
from own_mcp import McpError


class UnusedClient:
    mode = 'local'
    decisionStyle = 'flat'

    def __init__(self):
        self.closed = False

    async def decide(self, *_arguments):
        pytest.fail('A failed runtime setup cannot request a model decision')

    async def close(self):
        self.closed = True


@pytest.mark.parametrize('send_finish', [False, True])
def test_injected_runtime_setup_mcp_error_persists_safe_failure(tmp_path, send_finish):
    client = UnusedClient()
    calls = []
    counts = {'primaryRequests': 0, 'primarySuccesses': 0, 'primaryFailures': 0,
              'fallbackRequests': 0, 'fallbackSuccesses': 0, 'primaryServedModels': []}
    metadata = {'terminalTask': {'kind': 'read', 'goal': '查看屏幕'},
                'terminalSummary': {'kind': 'read', 'backend': 'jev-auto'},
                'comparisonStats': dict(counts), **counts}

    def broken_factory(trial_id):
        calls.append(trial_id)
        (tmp_path / trial_id).mkdir()
        raise McpError('private-error-with-key-must-not-leak',
                       stage='initialize', exception_type='ToolError')

    requests = [{'id': 1, 'command': 'observe'}, {'id': 2, 'command': 'observe'}]
    if send_finish:
        requests.append({'id': 3, 'command': 'finish', 'piElapsedMs': 321})
    output = io.StringIO()
    asyncio.run(worker.serve(
        'jev-auto', 'custom-runtime-setup', runtime_factory=broken_factory,
        client_factory=lambda *_args, **_kwargs: client, key_reader=lambda: '',
        input_stream=io.StringIO('\n'.join(json.dumps(request) for request in requests)),
        output_stream=output, base=tmp_path, setup_failure_metadata=lambda: metadata))
    replies = [json.loads(line) for line in output.getvalue().splitlines()]
    assert 'error' in replies[0] and 'error' in replies[1]
    result = json.loads((tmp_path / 'custom-runtime-setup/result.json').read_text())
    assert result['status'] == 'failed'
    assert result['setupFailed'] is True and result['noInteractionsDispatched'] is True
    assert result['stopCode'] == 'MCP_SETUP_FAILED'
    assert result['finalState'] is None
    assert result['modelCalls'] == result['operations'] == []
    assert result['errorDetails'] == {'exceptionType': 'ToolError', 'stage': 'initialize'}
    assert result['terminalTask'] == metadata['terminalTask']
    assert result['comparisonStats'] == counts
    assert result['primaryRequests'] == result['fallbackRequests'] == 0
    assert calls == ['custom-runtime-setup'] and client.closed
    assert 'private-error-with-key' not in output.getvalue()
    assert 'private-error-with-key' not in json.dumps(result)
    if send_finish:
        assert replies[-1]['result'] == result
        assert result['piElapsedMs'] == 321


def test_setup_metadata_cannot_override_failed_status_or_safe_error(tmp_path):
    client = UnusedClient()

    def broken_factory(_trial_id):
        raise RuntimeError('private-original-error')

    output = io.StringIO()
    asyncio.run(worker.serve(
        'jev-auto', 'safe-failure', runtime_factory=broken_factory,
        client_factory=lambda *_args, **_kwargs: client, key_reader=lambda: '',
        input_stream=io.StringIO('{"id":1,"command":"observe"}\n{"id":2,"command":"finish"}'),
        output_stream=output, base=tmp_path,
        setup_failure_metadata={'status': 'success', 'error': 'private-metadata-error'}))
    result = json.loads((tmp_path / 'safe-failure/result.json').read_text())
    assert result['status'] == 'failed'
    assert 'private-' not in json.dumps(result)
    assert result['errorDetails'] == {'exceptionType': 'InternalError', 'stage': 'observe'}


def test_custom_runtime_without_opt_in_keeps_original_behavior(tmp_path):
    client = UnusedClient()

    def broken_factory(_trial_id):
        raise RuntimeError('private-original-error')

    output = io.StringIO()
    asyncio.run(worker.serve(
        'jev-auto', 'legacy-custom', runtime_factory=broken_factory,
        client_factory=lambda *_args, **_kwargs: client, key_reader=lambda: '',
        input_stream=io.StringIO('{"id":1,"command":"observe"}'),
        output_stream=output, base=tmp_path))
    assert 'error' in json.loads(output.getvalue())
    assert not (tmp_path / 'legacy-custom/result.json').exists()
    assert client.closed
