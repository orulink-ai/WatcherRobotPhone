"""Local decision wire format, fail-closed validation, and key-free worker wiring."""

import asyncio
import json

import httpx
import pytest

from local_decision_client import StartLuxClient, local_settings
from providers import ProviderError


@pytest.mark.parametrize(
    "provider,port,thinking,override",
    [
        ("startlux-instant", 8091, "never", None),
        ("startlux-autothink", 8092, "auto", None),
        ("startlux-autothink", 8092, "never", "never"),
    ],
)
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
        return httpx.Response(
            200,
            json={
                "model": provider,
                "answers": {
                    "action": {
                        "type": "choice",
                        "choice": "WAIT",
                        "confidence": 1,
                        "probabilities": {"WAIT": 1},
                    }
                },
                "usage": {"input_tokens": 10, "output_tokens": 0},
            },
        )

    async def run():
        client = StartLuxClient(provider, transport=httpx.MockTransport(respond))
        try:
            result = await client.decide(provider, {"goal": "wait"}, {"WAIT": "wait"})
            assert result["choice"] == "WAIT" and result["servedModel"] == provider
        finally:
            await client.close()

    asyncio.run(run())
    assert len(seen) == 1


@pytest.mark.parametrize(
    "url",
    [
        "https://example.com/v1/systemone",
        "http://192.168.1.2/v1/systemone",
        "http://secret@127.0.0.1:8091/v1/systemone",
        "http://127.0.0.1:8091/other",
    ],
)
def test_local_endpoint_rejects_non_loopback_or_credentials(url, monkeypatch):
    monkeypatch.setenv("PHONE_BENCH_STARTLUX_INSTANT_URL", url)
    with pytest.raises(ValueError, match="PHONE_BENCH_STARTLUX_INSTANT_URL"):
        local_settings("startlux-instant")


@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(302, headers={"location": "https://example.com"}),
        httpx.Response(500, text="private-server-error"),
        httpx.Response(
            200,
            json={
                "answers": {
                    "action": {
                        "type": "choice",
                        "choice": "UNKNOWN",
                        "confidence": 1,
                        "probabilities": {"UNKNOWN": 1},
                    }
                }
            },
        ),
    ],
)
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
