"""Offline scope/exit checks for ordinary PI phone tasks."""
from copy import deepcopy
from types import SimpleNamespace

import pytest

from phone_exit_runtime import make_exit_runtime_class, resolve_phone_task


def observation(task, *, done=True):
    kind = 'detail' if task['kind'] == 'experience' else 'app'
    return {'state': {'stage': 'done' if done else 'desired', 'focusedPackage': 'com.xingin.xhs',
                     'observation': {'kind': kind, 'targetVerified': kind == 'detail',
                                     'liked': True, 'collected': False}, 'actionDescriptors': {}},
            'options': {}, 'done': done}


class FakeRuntime:
    def __init__(self, trial_id, task, *, base=None):
        self.task = deepcopy(task)
        self.progress = SimpleNamespace(phase='search', milestones=[])
        self.operations, self.phases = [], []
        self.actions, self.snapshot, self.error = {}, None, None
        self.stopped = False
        self.index = 0
        self.calls, self.saved = [], []
        self.initial_state = None

    def observe(self):
        self.index += 1
        self.snapshot = observation(self.task)
        self.progress.phase = 'done'
        return self.snapshot

    def act(self, choice):
        return self.observe()

    def call(self, name, arguments):
        self.calls.append((name, arguments))

    def _save(self):
        self.saved.append(deepcopy(self.snapshot))

    def finish(self, extra=None):
        return {'status': 'success' if self.progress.phase == 'done' else 'failed',
                'finalState': self.snapshot['state']['observation'], **(extra or {})}


def make_runtime(task, *, exit_requested=False, screens=None):
    screens = iter(screens or ['launcher'])
    cls = make_exit_runtime_class(FakeRuntime, read_screen=lambda runtime: next(screens),
        focus_reader=lambda raw: 'com.miui.home' if raw == 'launcher' else 'com.xingin.xhs',
        launcher_verifier=lambda raw: 'com.miui.home' if raw == 'launcher' else None,
        lock_verifier=lambda raw: raw == 'locked', sleep=lambda duration: None)
    return cls('trial', task, exit_requested=exit_requested)


def test_arbitrary_query_and_exact_title_are_preserved():
    task, exit_requested = resolve_phone_task('在小红书搜索“周末咖啡”，打开标题为“今天随便走走”的帖子，只点赞。')
    assert task['query'] == '周末咖啡'
    assert task['target'] == {'kind': 'title', 'title': '今天随便走走'}
    assert task['desired'] == {'liked': True}
    assert task['restoreAfter'] is False
    assert exit_requested is False


def test_only_like_neither_cancels_nor_exits_automatically():
    task, exit_requested = resolve_phone_task('在小红书搜索“雨天散步”，打开第一篇帖子，只点赞。')
    runtime = make_runtime(task, exit_requested=exit_requested)
    observed = runtime.observe()
    assert observed['done'] is True
    assert runtime.calls == []
    assert task['desired'] == {'liked': True} and task['restoreAfter'] is False
    assert runtime.finish()['status'] == 'success'


def test_explicit_exit_requires_model_home_and_does_not_cancel_like():
    task, exit_requested = resolve_phone_task('在小红书搜索“手冲咖啡”，打开第一篇帖子，点赞，然后退出小红书回到桌面。')
    assert exit_requested is True and task['desired'] == {'liked': True}
    runtime = make_runtime(task, exit_requested=exit_requested)
    observed = runtime.observe()
    assert not observed['done'] and 'EXIT:HOME' in observed['options']
    assert runtime.calls == []
    assert runtime.act('EXIT:HOME')['done'] is True
    assert runtime.calls == [('android_press_key', {'key': 'HOME'})]
    result = runtime.finish()
    assert result['status'] == 'success' and result['exitVerified']
    assert result['finalTargetState']['liked'] is True
    assert result['finalState']['kind'] == 'launcher'


def test_exit_refresh_is_new_read_with_no_stale_icon_claim():
    task, _ = resolve_phone_task('在小红书搜索“咖啡”，打开第一篇帖子，点赞。')
    runtime = make_runtime(task, exit_requested=True, screens=['same-app', 'launcher'])
    runtime.observe()
    index = runtime.index
    fresh = runtime.observe()
    assert runtime.index > index
    assert fresh['state']['observation']['kind'] == 'screen'
    assert 'liked' not in fresh['state']['observation']
    assert runtime.act('EXIT:HOME')['done']


def test_negative_exit_phrase_does_not_authorize_home():
    task, exit_requested = resolve_phone_task('在小红书搜索“咖啡”，打开第一篇帖子，点赞，不要退出小红书。')
    assert exit_requested is False
    assert make_runtime(task).observe()['done']


@pytest.mark.parametrize('goal,kind', [('看看手机屏幕','read'), ('打开微信','open'), ('在小红书搜索“独立书店”','search')])
def test_generic_read_open_search_remain_generic(goal, kind):
    task, exit_requested = resolve_phone_task(goal)
    assert task['kind'] == kind and not exit_requested
    runtime = make_runtime(task)
    assert runtime.observe()['done'] and runtime.calls == []


def test_missing_exit_or_worker_failed_never_overridden():
    task, _ = resolve_phone_task('在小红书搜索“咖啡”，打开第一篇帖子，点赞。')
    runtime = make_runtime(task, exit_requested=True)
    runtime.observe()
    assert runtime.finish({'status':'success'})['status'] == 'failed'
    runtime.act('EXIT:HOME')
    assert runtime.finish({'status':'failed'})['status'] == 'failed'


@pytest.mark.parametrize('goal', [
    '打开小红书，搜索小狮日记，打开第一篇帖子，点赞，收藏，取消点赞，取消收藏，退出回桌面。',
    '打开小红书→搜索小狮日记→第一篇→点赞→收藏→取消点赞→取消收藏→退出回桌面',
])
def test_explicit_ordered_natural_list_preserves_user_task(goal):
    task, exit_requested = resolve_phone_task(goal)
    assert task['kind'] == 'experience'
    assert task['query'] == '小狮日记'
    assert task['target'] == {'kind': 'first'}
    assert task['desired'] == {'liked': True, 'collected': True}
    assert task['restoreAfter'] is True and exit_requested is True
    assert task['goal'] == goal
