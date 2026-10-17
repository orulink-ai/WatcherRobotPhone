"""Dedicated PI worker for consuming a bounded Android-recorded intent plan.

This is an explicit companion-only entry, leaving the ordinary terminal worker
unchanged. Plans constrain every task mutation; preparation and settling are
recorded separately and can never count as consumed Android events.
"""

from __future__ import annotations

import argparse
import asyncio
import copy
import json
import os
from functools import partial
from pathlib import Path

from controller import Progress, private_write
from terminal_runtime import BACK, TerminalRecordedRuntime
from workflow import SELECTOR_KEYS
from workflow_intents import (
    KINDS,
    TASK,
    digest,
    intent_completed,
    matches_intent,
    validation_binding,
)
from worker_io import cancellation_scope
from providers import DecisionClient
import worker


class IntentVerificationError(RuntimeError):
    pass


def load_plan(path):
    value = json.loads(Path(path).read_text())
    checked = {key: item for key, item in value.items() if key != "planSha256"}
    if (
        value.get("schemaVersion") != 1
        or value.get("task") != TASK
        or value.get("planSha256") != digest(checked)
        or [step.get("kind") for step in value.get("intents", [])] != list(KINDS)
        or [step.get("eventSequence") for step in value["intents"]] != list(range(1, 9))
    ):
        raise IntentVerificationError("Invalid verified-scope intent plan")
    return value


class DraftIntentRuntime(TerminalRecordedRuntime):
    def __init__(self, trial_id, plan):
        self.plan = plan
        self.cursor = 0
        self.pending_intent = None
        self.settle_waits = self.prepare_backs = 0
        self.recorded_steps = []
        self.preparation_steps = []
        self.offered = {}
        super().__init__(
            trial_id,
            {
                "kind": "recorded",
                "goal": "按手机录制的完整有界意图逐步配对验证，不执行录制之外的任务",
            },
            entry_strategy="prepare-feed-v1",
        )

    def observe(self):
        snapshot = super().observe()
        if self.cursor == 0 and self.pending_intent is None:
            # A retained previous results page is preparation, not evidence that
            # the newly recorded search intent was actually performed.
            self.progress = Progress()
            snapshot["state"]["stage"], snapshot["done"] = "search", False
            if (
                snapshot["state"]["focusedPackage"] == "com.xingin.xhs"
                and snapshot["state"]["observation"]["kind"] != "feed"
            ):
                self.actions[BACK] = {
                    "method": "back",
                    "role": "navigation",
                    "label": "返回录制任务的搜索入口",
                }
        self.offered = copy.deepcopy(self.actions)
        return self._filter_options(snapshot)

    def _filter_options(self, snapshot):
        offered = self.offered
        allowed = {key: action for key, action in offered.items() if key in {"WAIT", "BLOCKED"}}
        if self.pending_intent is None and self.cursor < len(self.plan["intents"]):
            kind = self.plan["intents"][self.cursor]["kind"]
            matches = {
                key: action
                for key, action in offered.items()
                if matches_intent(kind, snapshot, action)
            }
            if len(matches) > 1:
                raise IntentVerificationError("Recorded intent has ambiguous live targets")
            allowed.update(matches)
            if self.cursor == 0:
                allowed.update(
                    {
                        key: action
                        for key, action in offered.items()
                        if key == "OPEN_APP" or key == BACK
                    }
                )
        self.actions = allowed
        snapshot["options"] = {key: action["label"] for key, action in allowed.items()}
        snapshot["state"]["actionDescriptors"] = {
            key: {name: action.get(name) for name in SELECTOR_KEYS}
            for key, action in allowed.items()
        }
        snapshot["state"]["recordedIntent"] = (
            self.plan["intents"][self.cursor] if self.cursor < 8 else None
        )
        snapshot["state"]["androidRecordingSha256"] = self.plan["rawSha256"]
        private_write(
            self.directory / f"state-{self.index}.json", json.dumps(snapshot, ensure_ascii=False)
        )
        self.snapshot = snapshot
        return snapshot

    def act(self, choice):
        if choice not in self.actions:
            raise IntentVerificationError("Action is outside the current recorded intent")
        before, action = copy.deepcopy(self.snapshot), copy.deepcopy(self.actions[choice])
        kind = self.plan["intents"][self.cursor]["kind"] if self.cursor < 8 else None
        recorded = (
            self.pending_intent is None
            and kind is not None
            and matches_intent(kind, before, action)
        )
        if not recorded and choice not in {"WAIT", "BLOCKED", "OPEN_APP", BACK}:
            raise IntentVerificationError("Unrecorded task mutation is forbidden")
        if choice in {"OPEN_APP", BACK}:
            if self.cursor != 0 or self.pending_intent is not None:
                raise IntentVerificationError("Preparation cannot replace a recorded action")
            if choice == BACK:
                self.prepare_backs += 1
                if self.prepare_backs > 8:
                    raise IntentVerificationError("Preparation budget exceeded")
        if recorded:
            self.pending_intent = self.cursor
        after = super().act(choice)
        marker = after.get("execution", {"status": "applied"})
        delivery = marker.get("status") if isinstance(marker, dict) else None
        if delivery not in {"applied", "not_dispatched"} or (
            delivery == "applied" and set(marker) != {"status"}
        ):
            raise IntentVerificationError("Uncertain delivery cannot become demonstration evidence")
        step = {
            "before": before,
            "action": {key: action.get(key) for key in SELECTOR_KEYS},
            "after": copy.deepcopy(after),
            "delivery": delivery,
        }
        if recorded and delivery == "applied":
            step.update(
                rawEventSequence=self.plan["intents"][self.cursor]["eventSequence"], intentKind=kind
            )
        elif recorded:
            self.pending_intent = None
        if recorded or self.cursor > 0 or self.pending_intent is not None:
            self.recorded_steps.append(step)
        else:
            self.preparation_steps.append(step)
        if self.pending_intent is not None:
            if intent_completed(kind, after):
                self.cursor += 1
                self.pending_intent = None
                self.settle_waits = 0
            elif choice == "WAIT":
                self.settle_waits += 1
                if self.settle_waits >= 8:
                    raise IntentVerificationError("Recorded postcondition did not verify")
        # The parent observed while the intent was pending, exposing only WAIT.
        # Rebind candidates from that same snapshot without another phone read.
        if self.pending_intent is None:
            self._refresh_options(after)
        return self.snapshot

    def _refresh_options(self, snapshot):
        # Refilter only the controls already captured by the paired readback.
        # Advancing the intent never performs a hidden phone operation.
        self._filter_options(snapshot)

    def finish(self, extra=None):
        binding = validation_binding(self.plan)
        complete = (
            self.cursor == 8
            and self.pending_intent is None
            and self.progress.phase == "done"
            and intent_completed("uncollect", self.snapshot)
            and [item.get("stage") for item in self.progress.milestones]
            == ["search", "open_first", "like", "collect", "unlike", "uncollect"]
        )
        values = {
            **(extra or {}),
            "recordingValidation": binding if complete else {"allConsumed": False},
        }
        if not complete:
            values["status"] = "failed"
        result = super().finish(values)
        recording = {
            "schemaVersion": 1,
            "source": "pi-agent-demonstration",
            "status": result["status"],
            "task": TASK,
            "steps": self.recorded_steps,
            "preparationSteps": self.preparation_steps,
            "recordingValidation": result["recordingValidation"],
        }
        private_write(
            self.directory / "recording.json", json.dumps(recording, ensure_ascii=False, indent=2)
        )
        return result


async def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--provider", required=True)
    parser.add_argument("--trial-id", required=True)
    args = parser.parse_args(argv)
    plan = load_plan(os.environ["PHONE_WORKFLOW_PLAN"])
    try:
        async with cancellation_scope():
            await worker.serve(
                "jev-auto",
                args.trial_id,
                runtime_factory=lambda trial: DraftIntentRuntime(trial, plan),
                client_factory=partial(DecisionClient, providerFallbacks=True),
            )
    except asyncio.CancelledError:
        return 143
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
