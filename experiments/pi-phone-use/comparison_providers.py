"""Two interchangeable primary decision services with one shared cloud fallback.

Logical `jev` means the selected primary; logical `deepseek` is ALWAYS the cloud
service. DecisionPolicy owns the refresh barrier and deadlines. These clients
cannot observe or operate a phone, retry requests, or select a fallback by itself.
"""
from __future__ import annotations

import asyncio
import copy
import json
import os
from pathlib import Path
import time

from experience_skill import load_experience_skill
from local_decision_client import StartLuxClient
from providers import DecisionClient, NetworkTrace, ProviderError, SYSTEM_INSTRUCTIONS, _context, _decode

CLOUD_DS_ENDPOINT = 'https://api.deepseek.com/chat/completions'
CLOUD_DS_MODEL = 'deepseek-flash'
_UNSET = object()


def cloud_deepseek_key():
    supplied = os.environ.get('PHONE_CLOUD_DS_API_KEY_FILE')
    if supplied:
        path = Path(supplied).expanduser()
    else:
        directory = Path.home()/'.config/orulink-phone'
        path = directory/'cloud-deepseek.key'
        if not path.exists():
            path = directory/'benchmark-deepseek-cloud.key'
    return path.read_text().strip()


class CloudDeepSeekClient(DecisionClient):
    """Direct cloud chat API; independent of all LOCAL_DS_* configuration."""
    def __init__(self, key, **options):
        super().__init__(key, **options)
        self.decisionStyle = 'flat'

    async def decide(self, provider, state, options):
        if provider != 'deepseek' or not isinstance(options, dict) or not options:
            raise ValueError('Invalid cloud fallback request')
        payload = {
            'model': CLOUD_DS_MODEL, 'temperature': 0, 'max_tokens': 128,
            'stream': False, 'thinking': {'type': 'disabled'},
            'response_format': {'type': 'json_object'},
            'messages': [
                {'role': 'system', 'content': SYSTEM_INSTRUCTIONS +
                 ' Return only JSON with exactly one field: {"choice":"one supplied action key"}.'},
                {'role': 'user', 'content': json.dumps({'state': state, 'options': options}, ensure_ascii=False)},
            ],
        }
        async with self._lock:
            if self._closed:
                raise ValueError('Decision client is closed')
            started = time.perf_counter()
            trace = NetworkTrace()
            response = None
            stage = 'create_client'
            try:
                if self._client is None:
                    self._client = self._new_client()
                stage = 'create_request'
                request = self._client.build_request('POST', CLOUD_DS_ENDPOINT,
                    headers={'Authorization': 'Bearer ' + self.key}, json=payload,
                    extensions={'trace': trace})
                stage = 'send_request'
                response = await self._client.send(request)
                stage = 'http_status'
                response.raise_for_status()
                stage = 'validate_response'
                body = _decode(response.content)
                completions = body.get('choices')
                if not isinstance(completions, list) or len(completions) != 1:
                    raise ValueError('Expected one completion')
                completion = completions[0]
                if completion.get('finish_reason') != 'stop' or completion.get('message', {}).get('refusal'):
                    raise ValueError('Incomplete decision')
                answer = _decode(completion.get('message', {}).get('content'))
                if set(answer) != {'choice'} or not isinstance(answer['choice'], str) or answer['choice'] not in options:
                    raise ValueError('Unavailable action')
                elapsed = (time.perf_counter() - started) * 1000
                return {'choice': answer['choice'], 'latencyMs': elapsed,
                    'servedModel': body.get('model'), 'usage': body.get('usage', {}),
                    'network': trace.metrics(total_ms=elapsed, http_version=response.http_version),
                    'mode': self.mode, 'compact': True, 'decisionStyle': 'flat'}
            except Exception as error:
                elapsed = (time.perf_counter() - started) * 1000
                raise ProviderError('Cloud DeepSeek request failed; no action selected',
                    network=trace.metrics(total_ms=elapsed,
                        http_version=response.http_version if response is not None else None),
                    latency_ms=elapsed, exception_type=type(error).__name__, stage=stage,
                    http_status=response.status_code if response is not None else None) from None


class ComparisonDecisionClient:
    """Translate policy roles to actual backends, retaining per-request evidence."""
    decisionStyle = 'flat'

    def __init__(self, key, mode='warm', compact=True, *, decisionStyle='flat',
                 arm=None, primary_client=None, fallback_client=None, experience=_UNSET):
        self.arm = arm or os.environ['PHONE_COMPARE_ARM']
        if self.arm not in {'cloud', 'local'}:
            raise ValueError('Unknown primary comparison arm')
        self.mode, self.compact = mode, compact
        self.primary_backend = 'cloud-jev' if self.arm == 'cloud' else 'lan-startlux'
        self.primary = primary_client if primary_client is not None else (
            DecisionClient(key, mode=mode, compact=True, decisionStyle='flat')
            if self.arm == 'cloud' else StartLuxClient('startlux-instant'))
        self.fallback = fallback_client if fallback_client is not None else CloudDeepSeekClient(
            cloud_deepseek_key(), mode=mode, compact=True, decisionStyle='flat')
        source = os.environ.get('PHONE_BENCH_RECIPE')
        self.experience = (load_experience_skill(source) if source else None) if experience is _UNSET else experience
        self.audit = []

    async def close(self):
        await self.primary.close()
        await self.fallback.close()

    async def decide(self, provider, state, options):
        if provider not in {'jev', 'deepseek'}:
            raise ValueError('Unknown logical decision role')
        state = _context(state, options, True)
        state.pop('visibleControls', None)
        if self.experience is not None:
            state['skillExperience'] = self.experience.context(state)
        is_primary = provider == 'jev'
        backend = self.primary_backend if is_primary else 'cloud-deepseek'
        target = self.primary if is_primary else self.fallback
        actual_provider = ('jev' if self.arm == 'cloud' else 'startlux-instant') if is_primary else 'deepseek'
        audit = {'requestIndex': len(self.audit) + 1, 'logicalBackend': provider,
            'actualBackend': backend, 'role': 'primary' if is_primary else 'fallback', 'status': 'started'}
        self.audit.append(audit)
        started = time.perf_counter()
        try:
            result = await target.decide(actual_provider, state, dict(options))
            if not isinstance(result, dict) or not isinstance(result.get('choice'), str) or result['choice'] not in options:
                raise ValueError('Unavailable model choice')
            audit.update(status='success', servedModel=result.get('servedModel'), choice=result['choice'])
            return {**result, 'actualBackend': backend, 'primaryBackend': self.primary_backend}
        except asyncio.CancelledError:
            audit.update(status='cancelled')
            raise
        except Exception as error:
            audit.update(status='failed', exceptionType=type(error).__name__)
            if isinstance(error, ProviderError):
                audit.update(stage=error.stage, httpStatus=error.httpStatus)
                raise
            raise ProviderError('Decision adapter failed; no action selected', network={},
                latency_ms=(time.perf_counter()-started)*1000,
                exception_type=type(error).__name__, stage='validate_response') from None
        finally:
            audit['latencyMs'] = (time.perf_counter() - started) * 1000

    def summary(self):
        primary = [a for a in self.audit if a['role'] == 'primary']
        fallback = [a for a in self.audit if a['role'] == 'fallback']
        primary_success = sum(a['status'] == 'success' for a in primary)
        return {'arm': self.arm, 'experienceLoaded': self.experience is not None,
            'primaryBackend': self.primary_backend,
            'fallbackBackend': 'cloud-deepseek', 'fallbackEndpoint': CLOUD_DS_ENDPOINT,
            'primaryRequests': len(primary), 'primarySuccesses': primary_success,
            'primaryFailures': len(primary)-primary_success,
            'fallbackRequests': len(fallback),
            'fallbackSuccesses': sum(a['status'] == 'success' for a in fallback),
            'primaryServedModels': sorted({a['servedModel'] for a in primary
                if isinstance(a.get('servedModel'), str)}),
            'routingAudit': copy.deepcopy(self.audit)}
