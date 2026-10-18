"""Production local phone backend: StartLux first, direct cloud DeepSeek fallback.

The original worker/DecisionPolicy owns observe/decide/act authorization and the
fresh-observation barrier. This entry changes routing and task selection only.
It never changes the user's ordinary PI chat model or reads an OpenRouter key.
"""
from __future__ import annotations

import argparse
import asyncio
import copy
import fcntl
import os
from pathlib import Path
import stat
import sys

from comparison_providers import ComparisonDecisionClient, CloudDeepSeekClient, cloud_deepseek_key
from phone_exit_runtime import resolve_phone_task

METRIC_FIELDS = ('primaryRequests', 'primarySuccesses', 'primaryFailures',
                 'fallbackRequests', 'fallbackSuccesses', 'primaryServedModels')
DEFAULT_CONTROL_LOCK = '~/.local/share/orulink-phone/codex/control.lock'
DEFAULT_STARTLUX_URL = 'http://192.168.50.26:8094/v1/systemone'


class PhoneControlBusy(RuntimeError):
    """Another session already holds the standard shared phone lease."""


class ControlLock:
    """Hold the existing cross-entry lease, or reuse a verified inherited fd.

    The fd form supports the existing MCP manager, which holds the lease while
    spawning its child. Closing a duplicate does not unlock the parent's lease.
    An environment boolean can never disable locking.
    """
    def __init__(self, path, *, inherited_fd=None):
        self.path = Path(path).expanduser()
        self.inherited_fd = inherited_fd
        self.fd = None

    def __enter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        opened = os.open(self.path, os.O_RDWR | os.O_CREAT, 0o600)
        try:
            expected = os.fstat(opened)
            if not stat.S_ISREG(expected.st_mode):
                raise ValueError('Phone lease must be a regular file')
            if self.inherited_fd is not None:
                actual = os.fstat(self.inherited_fd)
                if (actual.st_dev, actual.st_ino) != (expected.st_dev, expected.st_ino):
                    raise ValueError('Inherited phone lease does not match the shared lock')
                self.fd = os.dup(self.inherited_fd)
                os.close(opened)
                opened = None
            else:
                self.fd, opened = opened, None
            fcntl.flock(self.fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            os.fchmod(self.fd, 0o600)
            return self
        except BaseException as error:
            if self.fd is not None:
                os.close(self.fd)
                self.fd = None
            if opened is not None:
                os.close(opened)
            if isinstance(error, BlockingIOError):
                raise PhoneControlBusy('Another phone session is active; no phone action started') from None
            raise

    def __exit__(self, *_error):
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None


def read_cloud_key():
    try:
        legacy = os.environ.get('PHONE_CLOUD_DEEPSEEK_KEY_FILE')
        if legacy and not os.environ.get('PHONE_CLOUD_DS_API_KEY_FILE'):
            key = Path(legacy).expanduser().read_text().strip()
        else:
            key = cloud_deepseek_key().strip()
    except OSError:
        raise ValueError('Cloud phone fallback credentials are unavailable') from None
    if not key:
        raise ValueError('Cloud phone fallback credentials are unavailable')
    return key


class LocalPhoneDecisionClient(ComparisonDecisionClient):
    """Pin the logical primary to StartLux; DeepSeek always remains cloud-only."""
    def __init__(self, key='', *, mode='warm', compact=True, decisionStyle='flat', **options):
        options.pop('arm', None)
        if options.get('fallback_client') is None:
            options['fallback_client'] = CloudDeepSeekClient(
                read_cloud_key(), mode=mode, compact=True, decisionStyle='flat')
        if os.environ.get('PHONE_EXPERIENCE_ENABLED', '1') == '0' and 'experience' not in options:
            options['experience'] = None
        super().__init__('', mode=mode, compact=True, decisionStyle='flat', arm='local', **options)


def _load_dependencies():
    from worker import serve
    from phone_exit_runtime import create_runtime
    return serve, create_runtime, LocalPhoneDecisionClient


async def main(argv=None, **serve_kwargs):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--provider', choices=['jev-auto', 'terminal-auto'], required=True)
    parser.add_argument('--trial-id', required=True)
    parser.add_argument('--goal')
    args = parser.parse_args(argv)
    goal = args.goal if args.goal is not None else os.environ.get('PHONE_TERMINAL_GOAL')
    task, exit_requested = resolve_phone_task(goal)
    # A caller's derived task cannot replace the current user's goal, nor can
    # inherited terminal env rewrite the original serve() logical backend.
    os.environ.pop('PHONE_TERMINAL_TASK', None)
    os.environ.pop('PHONE_TERMINAL_GOAL', None)
    os.environ.setdefault('PHONE_BENCH_STARTLUX_INSTANT_URL', DEFAULT_STARTLUX_URL)
    os.environ.setdefault('PHONE_BENCH_STARTLUX_ALLOW_LAN', '1')
    lock_path = os.environ.get('PHONE_CONTROL_LOCK', DEFAULT_CONTROL_LOCK)
    inherited = os.environ.get('PHONE_CONTROL_LOCK_FD')
    inherited = int(inherited) if inherited is not None else None
    base = os.environ.get('PHONE_BENCH_OUTPUT_DIR')
    serve, runtime_factory, client_factory = _load_dependencies()
    client_holder = {}

    def local_client(_unused_key, **options):
        client = client_factory('', **options)
        client_holder['client'] = client
        return client

    def public_result_metadata():
        summary = copy.deepcopy(client_holder['client'].summary())
        metadata = {'comparisonStats': summary, 'terminalTask': task,
                    'terminalSummary': {'kind': task['kind'], 'backend': 'jev-auto',
                                        'exitRequested': exit_requested}}
        metadata.update({name: summary[name] for name in METRIC_FIELDS})
        return metadata

    def phone_runtime(trial_id):
        runtime = runtime_factory(trial_id, task, exit_requested=exit_requested, base=base)
        original_finish = runtime.finish

        def finish(extra=None):
            return original_finish({**(extra or {}), **public_result_metadata()})

        runtime.finish = finish
        return runtime

    options = {'runtime_factory': phone_runtime, 'client_factory': local_client,
               'key_reader': lambda: '', 'base': base,
               'setup_failure_metadata': public_result_metadata}
    options.update(serve_kwargs)
    with ControlLock(lock_path, inherited_fd=inherited):
        await serve('jev-auto', args.trial_id, **options)
    return 0


async def _cli():
    from worker_io import cancellation_scope
    try:
        async with cancellation_scope():
            return await main()
    except asyncio.CancelledError:
        return 143


if __name__ == '__main__':
    try:
        raise SystemExit(asyncio.run(_cli()))
    except PhoneControlBusy:
        print('Phone control is busy; no phone action started', file=sys.stderr)
        raise SystemExit(2)
    except (OSError, ValueError, KeyError):
        print('Phone task or private configuration is unavailable; execution stopped', file=sys.stderr)
        raise SystemExit(2)
