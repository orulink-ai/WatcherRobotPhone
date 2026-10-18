"""Local decision wire format, fail-closed validation, and key-free worker wiring."""

import asyncio
import io
import json

import httpx
import pytest

from local_providers import LOCAL_MODELS, StartLuxClient, local_settings
from providers import ProviderError
import run_pi_trial
from test_worker_policy import PhoneDouble
import worker


@pytest.mark.parametrize("provider,port,thinking,override", [
    ("startlux-instant", 8091, "never", None), ("startlux-autothink", 8092, "auto", None),
    ("startlux-autothink", 8092, "never", "never"),
])
def test_local_wire_format_never_sends_cloud_key(provider, port, thinking, override, monkeypatch):
    monkeypatch.delenv("PHONE_BENCH_STARTLUX_THINK_MODE", raising=False)
    if override:
        monkeypatch.setenv("PHONE_BENCH_STARTLUX_THINK_MODE", override)
    monkeypatch.setenv("OPENROUTER_API_KEY", "must-never-be-sent")
    monkeypatch.setenv("HTTP_PROXY", "http://invalid.example:1")
    seen = []

    def respond(request):
        seen.append(request)
        body = json.loads(request.content)
        assert request.url.host == "127.0.0.1" and request.url.port == port
        assert "authorization" not in request.headers
        assert body["think_mode"] == thinking
        assert body["none_check"] is False and body["allow_none"] is False
        assert body["questions"]["action"]["criteria"] == {"WAIT": "wait"}
        return httpx.Response(200, json={"model": provider, "answers": {"action": {
            "type": "choice", "choice": "WAIT", "confidence": 1, "probabilities": {"WAIT": 1},
        }}, "usage": {"input_tokens": 10, "output_tokens": 0}})

    async def run():
        client = StartLuxClient(provider, transport=httpx.MockTransport(respond))
        try:
            result = await client.decide(provider, {"goal": "wait"}, {"WAIT": "wait"})
            assert result["choice"] == "WAIT" and result["servedModel"] == provider
        finally:
            await client.close()

    asyncio.run(run())
    assert len(seen) == 1


@pytest.mark.parametrize("url", [
    "https://example.com/v1/systemone", "http://192.168.1.2/v1/systemone",
    "http://secret@127.0.0.1:8091/v1/systemone", "http://127.0.0.1:8091/other",
])
def test_local_endpoint_rejects_non_loopback_or_credentials(url, monkeypatch):
    monkeypatch.setenv("PHONE_BENCH_STARTLUX_INSTANT_URL", url)
    with pytest.raises(ValueError, match="PHONE_BENCH_STARTLUX_INSTANT_URL"):
        local_settings("startlux-instant")


@pytest.mark.parametrize("response", [
    httpx.Response(302, headers={"location": "https://example.com"}),
    httpx.Response(500, text="private-server-error"),
    httpx.Response(200, json={"answers": {"action": {"type": "choice", "choice": "UNKNOWN",
                        "confidence": 1, "probabilities": {"UNKNOWN": 1}}}}),
])
def test_failure_is_single_attempt_and_never_selects_action(response):
    calls = []

    def respond(request):
        calls.append(request)
        return response

    async def run():
        client = StartLuxClient("startlux-instant", transport=httpx.MockTransport(respond))
        try:
            with pytest.raises(ProviderError) as caught:
                await client.decide("startlux-instant", {}, {"WAIT": "wait"})
            assert "private-server-error" not in str(caught.value)
        finally:
            await client.close()

    asyncio.run(run())
    assert len(calls) == 1


@pytest.mark.parametrize("provider", LOCAL_MODELS)
def test_local_worker_uses_no_cloud_key(provider, tmp_path, monkeypatch):
    class Client:
        mode, decisionStyle = "local", "flat"

        def __init__(self, selected):
            assert selected == provider

        async def decide(self, selected, state, options):
            assert selected == provider
            return {"choice": next(iter(options)), "latencyMs": 1, "servedModel": selected}

        async def close(self):
            pass

    def forbidden():
        raise AssertionError("Local mode attempted to read a cloud key")

    PhoneDouble.fail_action = PhoneDouble.fail_readback = PhoneDouble.parse_after_action = False
    PhoneDouble.instances = []
    monkeypatch.setattr(worker, "StartLuxClient", Client)
    monkeypatch.setenv("OPENROUTER_API_KEY_FILE", str(tmp_path / "does-not-exist"))
    commands = [
        {"id": "1", "command": "observe"}, {"id": "2", "command": "decide"},
        {"id": "3", "command": "act", "choice": "CLICK:node-1"},
        {"id": "4", "command": "finish"},
    ]
    out = io.StringIO()
    asyncio.run(worker.serve(provider, "local-test", input_stream=io.StringIO(
        "\n".join(json.dumps(c) for c in commands) + "\n"), output_stream=out,
        runtime_factory=PhoneDouble, key_reader=forbidden, base=tmp_path))
    replies = [json.loads(line) for line in out.getvalue().splitlines()]
    assert all("error" not in reply for reply in replies)
    assert PhoneDouble.instances[0].actions == ["CLICK:node-1"]
    assert run_pi_trial.validate_configuration(provider) is None
