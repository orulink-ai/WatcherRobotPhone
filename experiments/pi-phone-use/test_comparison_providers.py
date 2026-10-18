"""Device-free contracts for the two-primary, shared-cloud-fallback comparison."""

import asyncio
import json
import unittest
from unittest.mock import AsyncMock

from comparison_providers import ComparisonDecisionClient, CloudDeepSeekClient
from decision_policy import DecisionPolicy
from providers import ProviderError
import httpx


class RouterTests(unittest.IsolatedAsyncioTestCase):
    async def test_primary_routing_and_same_cloud_fallback(self):
        requests = []

        class Fake:
            async def decide(self, provider, state, options):
                requests.append((provider, state, options))
                return {"choice": "WAIT", "servedModel": provider, "latencyMs": 1}

            async def close(self):
                pass

        for arm, primary in [("cloud", "jev"), ("local", "startlux-instant")]:
            client = ComparisonDecisionClient(
                "not-a-real-key",
                arm=arm,
                primary_client=Fake(),
                fallback_client=Fake(),
                experience=None,
            )
            self.assertEqual(client.decisionStyle, "flat")
            await client.decide(
                "jev", {"visibleControls": [{"id": "stale"}], "goal": "wait"}, {"WAIT": "wait"}
            )
            await client.decide("deepseek", {"goal": "wait"}, {"WAIT": "wait"})
            self.assertEqual(requests[-2][0], primary)
            self.assertNotIn("visibleControls", requests[-2][1])
            self.assertEqual(requests[-1][0], "deepseek")
            summary = client.summary()
            self.assertEqual(summary["primaryRequests"], 1)
            self.assertEqual(summary["fallbackRequests"], 1)
            self.assertEqual(summary["fallbackBackend"], "cloud-deepseek")
            self.assertEqual(
                summary["primaryBackend"], "cloud-jev" if arm == "cloud" else "lan-startlux"
            )
            await client.close()

    async def test_primary_error_requires_new_observation_then_returns_to_primary(self):
        primary = AsyncMock()
        fallback = AsyncMock()
        primary.decide.side_effect = [
            ProviderError(
                "failed",
                network={},
                latency_ms=1,
                exception_type="HTTPStatusError",
                stage="http_status",
                http_status=429,
            ),
            {"choice": "WAIT", "servedModel": "primary", "latencyMs": 1},
        ]
        fallback.decide.return_value = {"choice": "WAIT", "servedModel": "cloud-ds", "latencyMs": 1}
        client = ComparisonDecisionClient(
            "test", arm="local", primary_client=primary, fallback_client=fallback, experience=None
        )
        policy = DecisionPolicy(client, "jev-auto")
        state = {"state": {"goal": "wait"}, "options": {"WAIT": "wait"}}
        response = await policy.decide(state, 1)
        self.assertEqual(response["kind"], "refresh_required")
        with self.assertRaises(Exception):
            await policy.decide(state, 1)
        self.assertEqual(fallback.decide.await_count, 0)
        await policy.decide(state, 2)
        await policy.decide(state, 3)
        self.assertEqual(primary.decide.await_count, 2)
        self.assertEqual(fallback.decide.await_count, 1)
        self.assertEqual(client.summary()["primaryFailures"], 1)
        self.assertEqual(client.summary()["fallbackSuccesses"], 1)
        await client.close()

    async def test_cancelled_request_is_not_a_success(self):
        primary = AsyncMock()
        primary.decide.side_effect = asyncio.CancelledError()
        client = ComparisonDecisionClient(
            "test",
            arm="cloud",
            primary_client=primary,
            fallback_client=AsyncMock(),
            experience=None,
        )
        with self.assertRaises(asyncio.CancelledError):
            await client.decide("jev", {}, {"WAIT": "wait"})
        self.assertEqual(client.summary()["primaryFailures"], 1)
        self.assertEqual(client.summary()["primarySuccesses"], 0)
        await client.close()

    async def test_cloud_ds_always_uses_public_cloud_endpoint(self):
        seen = []

        async def respond(request):
            seen.append(request)
            return httpx.Response(
                200,
                json={
                    "choices": [
                        {"finish_reason": "stop", "message": {"content": '{"choice":"EXIT:HOME"}'}}
                    ],
                    "model": "deepseek-flash",
                },
            )

        client = CloudDeepSeekClient("test", transport=httpx.MockTransport(respond))
        result = await client.decide("deepseek", {}, {"EXIT:HOME": "Exit"})
        self.assertEqual(result["choice"], "EXIT:HOME")
        self.assertEqual(str(seen[0].url), "https://api.deepseek.com/chat/completions")
        payload = json.loads(seen[0].content)
        self.assertNotIn("store", payload)
        self.assertEqual(payload["model"], "deepseek-flash")
        await client.close()


if __name__ == "__main__":
    unittest.main()


def test_cloud_key_uses_explicit_private_file_reference(tmp_path, monkeypatch):
    from comparison_providers import cloud_deepseek_key
    from pathlib import Path

    monkeypatch.setattr(Path, "home", lambda: tmp_path)
    legacy = tmp_path / ".config/orulink-phone/benchmark-deepseek-cloud.key"
    legacy.parent.mkdir(parents=True)
    legacy.write_text("synthetic-legacy")
    key = tmp_path / "cloud.key"
    key.write_text("synthetic-cloud-key\n")
    monkeypatch.setenv("PHONE_CLOUD_DS_API_KEY_FILE", str(key))
    assert cloud_deepseek_key() == "synthetic-cloud-key"
