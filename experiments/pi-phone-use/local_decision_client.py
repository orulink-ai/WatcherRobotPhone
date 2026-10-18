"""StartLux client from colleague commit 05029cf; explicit LAN opt-in."""

import asyncio
import math
import ipaddress
import os
import time
from urllib.parse import urlsplit

import httpx

from providers import NetworkTrace, ProviderError, SYSTEM_INSTRUCTIONS, _decode, validate_choice

LOCAL_MODELS = ("startlux-instant", "startlux-autothink")
LOCAL_BACKENDS = (*LOCAL_MODELS, "deepseek-local")


def local_settings(provider):
    if provider not in LOCAL_MODELS:
        raise ValueError("Unsupported local model")
    variable, port = (
        ("PHONE_BENCH_STARTLUX_INSTANT_URL", 8091)
        if provider == "startlux-instant"
        else ("PHONE_BENCH_STARTLUX_AUTOTHINK_URL", 8092)
    )
    url = os.environ.get(variable, f"http://127.0.0.1:{port}/v1/systemone")
    parsed = urlsplit(url)
    loopback = parsed.hostname in {"127.0.0.1", "localhost", "::1"}
    lan = False
    if not loopback and os.environ.get("PHONE_BENCH_STARTLUX_ALLOW_LAN") == "1":
        try:
            ip = ipaddress.ip_address(parsed.hostname or "")
            lan = any(ip in ipaddress.ip_network(n) for n in (
                "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"
            ))
        except ValueError:
            pass
    if (
        parsed.scheme != "http"
        or not (loopback or lan)
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path != "/v1/systemone"
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError(variable)
    try:
        if parsed.port is not None and not 1 <= parsed.port <= 65535:
            raise ValueError
    except ValueError:
        raise ValueError(variable) from None
    try:
        timeout = float(os.environ.get("PHONE_BENCH_STARTLUX_TIMEOUT", "90"))
        if not math.isfinite(timeout) or not 0 < timeout <= 90:
            raise ValueError
    except ValueError:
        raise ValueError("PHONE_BENCH_STARTLUX_TIMEOUT") from None
    try:
        budget = int(os.environ.get("PHONE_BENCH_STARTLUX_THINK_BUDGET", "512"))
        if not 1 <= budget <= 4096:
            raise ValueError
    except ValueError:
        raise ValueError("PHONE_BENCH_STARTLUX_THINK_BUDGET") from None
    local_thinking(provider)
    return url, timeout, budget


def local_thinking(provider):
    mode = os.environ.get("PHONE_BENCH_STARTLUX_THINK_MODE", "auto")
    if mode not in {"auto", "never"}:
        raise ValueError("PHONE_BENCH_STARTLUX_THINK_MODE")
    return "never" if provider == "startlux-instant" else mode


class StartLuxClient:
    mode = "local"
    decisionStyle = "flat"

    def __init__(self, provider, *, transport=None):
        self.provider = provider
        self.url, self.timeout, self.budget = local_settings(provider)
        self.think_mode = local_thinking(provider)
        self._client = httpx.AsyncClient(
            timeout=self.timeout, trust_env=False, follow_redirects=False, transport=transport,
            limits=httpx.Limits(max_connections=1, max_keepalive_connections=1, keepalive_expiry=None)
        )
        self._closed = False
        self._lock = asyncio.Lock()

    async def close(self):
        async with self._lock:
            if not self._closed:
                self._closed = True
                await self._client.aclose()

    async def decide(self, provider, state, options):
        if provider != self.provider or not isinstance(state, dict):
            raise ValueError("Invalid local decision request")
        if not isinstance(options, dict) or not 1 <= len(options) <= 255:
            raise ValueError("Expected 1..255 choices")
        if any(not isinstance(k, str) or not k or not isinstance(v, str) for k, v in options.items()):
            raise ValueError("Invalid choice mapping")
        options = dict(options)
        # Serialize before awaiting, preserving the observation and its offered choices.
        payload = {
            "state": state,
            "questions": {"action": {"type": "choice", "instructions": SYSTEM_INSTRUCTIONS,
                                      "criteria": options}},
            "think_mode": self.think_mode,
            "think_budget": self.budget,
            "none_check": False,
            "allow_none": False,
        }
        trace = NetworkTrace()
        request = self._client.build_request(
            "POST", self.url, json=payload, extensions={"trace": trace}
        )
        async with self._lock:
            if self._closed:
                raise ValueError("Local decision client is closed")
            started = time.perf_counter()
            stage = "send_request"
            response = None
            version = None
            try:
                response = await self._client.send(request)
                version = response.http_version
                stage = "http_status"
                response.raise_for_status()
                stage = "validate_response"
                body = _decode(response.content)
                answers = body.get("answers")
                if not isinstance(answers, dict) or set(answers) != {"action"}:
                    raise ValueError("Expected one action answer")
                choice = validate_choice(answers["action"], options)
                elapsed = (time.perf_counter() - started) * 1000
                return {
                    "choice": choice,
                    "latencyMs": elapsed,
                    "servedModel": body.get("model") if isinstance(body.get("model"), str) else None,
                    "usage": body.get("usage") if isinstance(body.get("usage"), dict) else {},
                    "network": trace.metrics(total_ms=elapsed, http_version=version),
                    "mode": self.mode,
                    "decisionStyle": self.decisionStyle,
                    "thinkMode": self.think_mode,
                }
            except asyncio.CancelledError:
                raise
            except Exception as error:
                elapsed = (time.perf_counter() - started) * 1000
                raise ProviderError(
                    "Local decision failed",
                    network=trace.metrics(total_ms=elapsed, http_version=version),
                    latency_ms=elapsed, exception_type=type(error).__name__, stage=stage,
                ) from None
            finally:
                if response is not None:
                    await response.aclose()
