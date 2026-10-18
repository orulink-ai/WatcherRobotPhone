"""Goal-preserving phone runtimes with optional, model-selected HOME completion.

ExperienceRuntime owns arbitrary supported post targets and requested state
changes. This module adds exit only when the current user explicitly requests
it; prior demonstrations never supply a query, title, state change, or HOME.
"""
from __future__ import annotations

import copy
import os
import re
import time

EXIT_ACTIONS = {
    'EXIT:HOME': {'method': 'home', 'role': 'exit', 'label': '退出应用，回到桌面并验证'},
    'WAIT': {'method': 'wait', 'role': 'wait', 'label': '等待后继续选择退出'},
    'BLOCKED': {'method': 'blocked', 'role': 'blocked', 'label': '无法继续，停止'},
}
_EXIT_SUFFIX = re.compile(
    r'(?:然后|接着|随后|最后|完成后|再|并)?'
    r'(?:退出(?:小红书|当前应用|应用)(?:(?:并|然后|，)?(?:回到|返回)(?:手机)?桌面)?'
    r'|退出(?:回到?|返回)(?:手机)?桌面'
    r'|(?:回到|返回)(?:手机)?桌面)[。！!\s]*$'
)


class PhoneExitError(RuntimeError):
    def __init__(self, code):
        self.code = code
        super().__init__('Phone completion verification stopped')


def _intent_separators(text):
    # Arrows are sequencing syntax only outside quoted query/title data.
    closers = {'“': '”', '「': '」', '"': '"', "'": "'"}
    closer = None
    result = []
    for character in text:
        if closer is not None:
            result.append(character)
            if character == closer:
                closer = None
        elif character in closers:
            closer = closers[character]
            result.append(character)
        else:
            result.append('，' if character in {'→', '➜', '➝'} else character)
    return ''.join(result).rstrip('，,。；; \n')


def resolve_phone_task(goal):
    """Separate explicit exit from the existing bounded, parameterized task."""
    from terminal_tasks import resolve_task, TerminalTaskError

    if not isinstance(goal, str) or not 1 <= len(goal.strip()) <= 4000:
        raise TerminalTaskError('A bounded user phone goal is required')
    original = goal.strip()
    match = _EXIT_SUFFIX.search(original)
    exit_requested = False
    body = original
    if match is not None:
        prefix = original[:match.start()].rstrip('，,。；; \n')
        # A negative reference to exiting grants no HOME authority.
        if not re.search(r'(?:不要|不用|无需|不必|别|不)$', prefix):
            exit_requested = True
            body = prefix
    if not body and exit_requested:
        task = {'kind': 'read', 'goal': original}
    else:
        body = _intent_separators(body)
        sequence = re.search(
            r'(?<![\w\u4e00-\u9fff])点赞[，,、；;\s]+收藏[，,、；;\s]+'
            r'取消点赞[，,、；;\s]+取消收藏[。！!\s]*$', body)
        if sequence is not None:
            # A complete explicit ordered list already specifies before/after;
            # make that order explicit to the existing strict task parser.
            body = body[:sequence.start()] + '先' + body[sequence.start():]
        task = resolve_task(body)
    if task['kind'] == 'recorded':
        raise TerminalTaskError('Name the current search target and requested state changes explicitly')
    task['goal'] = original
    return task, exit_requested


def make_exit_runtime_class(runtime_base=None, *, read_screen=None, focus_reader=None,
                            launcher_verifier=None, lock_verifier=None, sleep=time.sleep):
    """Wrap an injected runtime; production create_runtime selects the adapter."""
    if runtime_base is None:
        from experience_runtime import ExperienceRuntime
        runtime_base = ExperienceRuntime
    if focus_reader is None or launcher_verifier is None:
        from window_state import focused_package, verified_launcher
        focus_reader = focus_reader or focused_package
        launcher_verifier = launcher_verifier or verified_launcher
    if read_screen is None:
        def read_screen(runtime):
            from own_mcp import content_text
            return content_text(runtime.call('android_get_screen_state', {'include_screenshot': False}))
    if lock_verifier is None:
        def lock_verifier(raw):
            from own_mcp import own_nodes
            from terminal_runtime import screen_locked
            return screen_locked(own_nodes(raw))

    class PhoneExitRuntime(runtime_base):
        def __init__(self, trial_id, task, *, exit_requested=False, base=None):
            if type(exit_requested) is not bool:
                raise ValueError('Exit authority must be an explicit boolean')
            super().__init__(trial_id, copy.deepcopy(task),
                             base=base or os.environ.get('PHONE_BENCH_OUTPUT_DIR'))
            self.exit_requested = exit_requested
            self.task_verified = False
            self.exit_verified = False
            self.final_task_state = None
            self._exit_focus_package = None
            self._base_act_depth = self._exit_waits = 0
            self._home_attempted = False
            self.stopped = getattr(self, 'stopped', False)

        def _persist_exit(self):
            save = getattr(self, '_save', None)
            if save is not None:
                save()
            else:
                import json
                from controller import private_write
                private_write(self.directory / f'state-{self.index}.json',
                              json.dumps(self.snapshot, ensure_ascii=False, indent=2))

        def _stop_exit(self, code):
            if self.error is None:
                self.error = 'Phone completion verification stopped (' + code + ')'
            self.stopped = True
            self.progress.phase = 'failed'
            self.actions = {}
            if self.snapshot is not None:
                self.snapshot['done'] = False
                self.snapshot['options'] = {}
                self.snapshot['state'].update(stage='failed', stopCode=code, actionDescriptors={})
                self._persist_exit()
            self.operations.append({'name': 'exit_stop', 'code': code})
            raise PhoneExitError(code)

        def _complete_task(self, snapshot):
            if not snapshot.get('done'):
                return snapshot
            observed = snapshot.get('state', {}).get('observation', {})
            if self.task['kind'] == 'experience':
                desired = self.task['desired']
                final = {key: False for key in desired} if self.task.get('restoreAfter') else desired
                if not (observed.get('kind') == 'detail' and observed.get('targetVerified') is True
                        and all(observed.get(field) is expected for field, expected in final.items())):
                    self._stop_exit('TASK_UNVERIFIED')
            self.task_verified = True
            self.final_task_state = copy.deepcopy(observed)
            if not self.exit_requested:
                return snapshot
            self._exit_focus_package = (snapshot['state'].get('focusedPackage')
                                        or observed.get('focusedPackage'))
            self.progress.phase = 'exit'
            self.actions = copy.deepcopy(EXIT_ACTIONS)
            self.snapshot = copy.deepcopy(snapshot)
            self.snapshot['done'] = False
            self.snapshot['options'] = {key: action['label'] for key, action in self.actions.items()}
            self.snapshot['state'].update(stage='exit', nextGoal='当前请求已验证。请选择 EXIT:HOME 回到桌面并验证。',
                                           actionDescriptors=copy.deepcopy(self.actions))
            self._persist_exit()
            return self.snapshot

        def observe(self):
            if self.stopped:
                raise PhoneExitError('STOPPED')
            if self.task_verified and self.exit_requested:
                if self.exit_verified:
                    return self.snapshot
                try:
                    raw = read_screen(self)
                    package, locked = focus_reader(raw), lock_verifier(raw)
                except Exception:
                    self._stop_exit('EXIT_READ_FAILED')
                self.index += 1
                if locked or not package or package != self._exit_focus_package:
                    self._stop_exit('EXIT_FOCUS_CHANGED')
                self.snapshot = copy.deepcopy(self.snapshot)
                self.snapshot['state']['observation'] = {
                    'kind': 'screen', 'focusedPackage': package, 'locked': False, 'exitRefresh': True}
                self.snapshot['state']['focusedPackage'] = package
                self.operations.append({'name': 'exit_refresh', 'readOnly': True,
                                        'observation': self.index, 'focusedPackage': package})
                self._persist_exit()
                return self.snapshot
            observed = super().observe()
            return observed if self._base_act_depth else self._complete_task(observed)

        def _home(self):
            self._home_attempted = True
            try:
                self.call('android_press_key', {'key': 'HOME'})
            except Exception:
                self._stop_exit('HOME_DELIVERY_UNCERTAIN')
            for attempt in range(4):
                try:
                    raw = read_screen(self)
                    package, launcher = focus_reader(raw), launcher_verifier(raw)
                    locked = lock_verifier(raw)
                except Exception:
                    self._stop_exit('HOME_READ_FAILED')
                self.index += 1
                confirmed = bool(package and package == launcher and not locked)
                self.snapshot = {
                    'state': {'goal': self.task['goal'], 'stage': 'done' if confirmed else 'exit',
                              'focusedPackage': package,
                              'observation': {'kind': 'launcher' if confirmed else 'screen',
                                              'focusedPackage': package, 'exitVerified': confirmed},
                              'actionDescriptors': {}}, 'options': {}, 'done': confirmed}
                self._persist_exit()
                self.operations.append({'name': 'exit_verification', 'attempt': attempt + 1,
                                        'readOnly': True, 'focusedPackage': package, 'verified': confirmed})
                if confirmed:
                    self.exit_verified = True
                    self.progress.phase = 'done'
                    self.actions = {}
                    self.progress.milestones.append({'stage': 'exit', 'next': 'done',
                                                     'observation': self.index, 'focusedPackage': package})
                    return self.snapshot
                if attempt < 3:
                    start = time.perf_counter()
                    sleep(.25)
                    self.phases.append({'kind': 'wait', 'name': '退出后桌面只读核对',
                                        'ms': (time.perf_counter() - start) * 1000})
            self._stop_exit('HOME_NOT_VERIFIED')

        def act(self, choice):
            if self.stopped or self.exit_verified:
                raise PhoneExitError('STOPPED')
            if not (self.task_verified and self.exit_requested):
                self._base_act_depth += 1
                try:
                    observed = super().act(choice)
                finally:
                    self._base_act_depth -= 1
                return self._complete_task(observed)
            if choice not in self.actions:
                self._stop_exit('UNKNOWN_EXIT_CHOICE')
            action = self.actions[choice]
            self.operations.append({'name': 'selected_action', 'choice': choice, 'stage': 'exit', **action})
            if choice == 'BLOCKED':
                self._stop_exit('BLOCKED')
            if choice == 'WAIT':
                self._exit_waits += 1
                if self._exit_waits > 3:
                    self._stop_exit('EXIT_WAIT_BUDGET')
                start = time.perf_counter()
                sleep(.25)
                self.phases.append({'kind': 'wait', 'name': '退出前等待',
                                    'ms': (time.perf_counter() - start) * 1000})
                return self.observe()
            if self._home_attempted:
                self._stop_exit('HOME_ALREADY_ATTEMPTED')
            return self._home()

        def finish(self, extra=None):
            success = ((extra or {}).get('status') != 'failed' and self.error is None and not self.stopped
                       and self.task_verified and (not self.exit_requested or self.exit_verified)
                       and self.progress.phase == 'done')
            toggles = [copy.deepcopy(event) for event in self.operations
                       if event.get('name') == 'toggle_verification']
            possible_interaction = bool(toggles or getattr(self, 'pending_toggle', None)) or any(
                event.get('name') == 'selected_action'
                and (event.get('role') in {'like', 'collect'} or event.get('stage') in {'desired', 'cancel'})
                for event in self.operations)
            return super().finish({**(extra or {}), 'piAgentLoop': True,
                                   'taskVerified': self.task_verified, 'interactionVerified': self.task_verified,
                                   'exitRequested': self.exit_requested, 'exitVerified': self.exit_verified,
                                   'finalTargetState': self.final_task_state, 'toggleEvidence': toggles,
                                   'stopCode': (self.snapshot or {}).get('state', {}).get('stopCode'),
                                   'noInteractionsDispatched': not possible_interaction,
                                   'status': 'success' if success else 'failed'})

    return PhoneExitRuntime


def create_runtime(trial_id, task, *, exit_requested=False, base=None):
    if task['kind'] == 'experience':
        from experience_runtime import ExperienceRuntime
        runtime_base = ExperienceRuntime
    elif task['kind'] in {'read', 'open', 'search'}:
        from terminal_runtime import GenericTerminalRuntime
        runtime_base = GenericTerminalRuntime
    else:
        raise ValueError('Unsupported phone task adapter')
    return make_exit_runtime_class(runtime_base)(trial_id, task, exit_requested=exit_requested, base=base)
