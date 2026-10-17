"""Conservative fixed-scope intent extraction from unverified Android events."""

from __future__ import annotations

import hashlib
import json
import re

from controller import PACKAGE, QUERY, TITLE

KINDS = (
    "search_entry",
    "query",
    "search_submit",
    "open_target",
    "like",
    "collect",
    "unlike",
    "uncollect",
)
TASK = {"query": QUERY, "expectedFirstTitle": TITLE}


class IntentError(ValueError):
    pass


def digest(value):
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True).encode()
    ).hexdigest()


def _labels(selector):
    return [
        value.strip()
        for key in ("text", "contentDescription")
        if isinstance(value := selector.get(key), str) and value.strip()
    ]


def _box(evidence, key):
    box = evidence[key]
    if set(box) != {"left", "top", "right", "bottom"} or any(
        type(v) is not int for v in box.values()
    ):
        raise ValueError
    width, height = evidence["screenWidth"], evidence["screenHeight"]
    if type(width) is not int or type(height) is not int or not 0 < width < height <= 20000:
        raise ValueError
    if not (0 <= box["left"] < box["right"] <= width and 0 <= box["top"] < box["bottom"] <= height):
        raise ValueError
    return box


def _toolbar(event, expected_role):
    selector, evidence = event["selector"], event.get("evidence", {})
    if (
        selector.get("semanticRole") != expected_role
        or selector.get("contextTitle") != TITLE
        or evidence.get("source") != "xhs-toolbar-candidate"
        or evidence.get("contextAuthor") != "小狮日记"
    ):
        return False
    source, anchor = _box(evidence, "sourceBounds"), _box(evidence, "favoriteAnchorBounds")
    labels = evidence.get("favoriteAnchorLabels", [])
    if (
        not isinstance(labels, list)
        or not 1 <= len(labels) <= 12
        or not all(isinstance(label, str) and len(label) <= 2000 for label in labels)
        or not any(label.startswith(("收藏", "已收藏")) for label in labels)
        or min(source["top"], anchor["top"]) < evidence["screenHeight"] * 0.8
    ):
        return False
    if expected_role == "collect_toggle":
        return source == anchor
    height = min(source["bottom"] - source["top"], anchor["bottom"] - anchor["top"])
    overlap = min(source["bottom"], anchor["bottom"]) - max(source["top"], anchor["top"])
    gap = anchor["left"] - source["right"]
    return overlap >= height * 0.5 and 0 <= gap <= height * 3


def derive_plan(raw):
    try:
        events = raw["events"]
        if (
            raw.get("schemaVersion") != 1
            or raw.get("source") != "android-accessibility-events"
            or raw.get("targetPackage") != PACKAGE
            or raw.get("needsReview") is not True
            or raw.get("hasPairedVerification") is not False
            or len(events) != len(KINDS)
        ):
            raise ValueError
        previous = -1
        intents = []
        for index, (event, kind) in enumerate(zip(events, KINDS), 1):
            selector = event["selector"]
            if (
                event.get("sequence") != index
                or type(event.get("elapsedMs")) is not int
                or event["elapsedMs"] < previous
                or selector.get("packageName") != PACKAGE
            ):
                raise ValueError
            previous = event["elapsedMs"]
            labels = _labels(selector)
            if kind == "query":
                valid = (
                    event["type"] == "text"
                    and isinstance(event.get("value"), str)
                    and re.sub(r"^搜索[,，]\s*", "", event["value"].strip()).strip() == QUERY
                )
            elif event["type"] != "click":
                valid = False
            elif kind in {"search_entry", "search_submit"}:
                valid = labels == ["搜索"] or (
                    bool(labels) and all(label == "搜索" for label in labels)
                )
            elif kind == "open_target":
                valid = any(TITLE in label for label in labels)
                if not valid:
                    evidence = event.get("evidence", {})
                    valid = (
                        selector.get("semanticRole") == "open_target"
                        and selector.get("contextTitle") == TITLE
                        and evidence.get("source") in {"source-label-v1", "source-descendants-v1"}
                        and any(
                            TITLE in label
                            for label in evidence.get("sourceLabels", [])
                            if isinstance(label, str)
                        )
                    )
            else:
                role = "like_toggle" if kind in {"like", "unlike"} else "collect_toggle"
                if selector.get("semanticRole") is not None:
                    valid = _toolbar(event, role)
                else:
                    allowed = {
                        "like": {"点赞"},
                        "collect": {"收藏"},
                        "unlike": {"取消点赞"},
                        "uncollect": {"取消收藏", "已收藏"},
                    }[kind]
                    valid = bool(labels) and all(label in allowed for label in labels)
            if not valid:
                raise ValueError
            intents.append({"kind": kind, "eventSequence": index, "eventSha256": digest(event)})
        plan = {"schemaVersion": 1, "task": TASK, "rawSha256": digest(raw), "intents": intents}
        plan["planSha256"] = digest(plan)
        return plan
    except (KeyError, TypeError, ValueError, AttributeError):
        raise IntentError(
            "Recorded events do not establish the complete supported intent sequence"
        ) from None


def validation_binding(plan):
    return {
        "androidRecordingSha256": plan["rawSha256"],
        "intentPlanSha256": plan["planSha256"],
        "consumedEventSequences": [step["eventSequence"] for step in plan["intents"]],
        "intentKinds": [step["kind"] for step in plan["intents"]],
        "allConsumed": True,
    }


def matches_intent(kind, snapshot, action):
    state, observation = snapshot["state"], snapshot["state"]["observation"]
    if any(state.get(key) != value for key, value in TASK.items()):
        return False
    method, role, label = (action.get(key) for key in ("method", "role", "label"))
    if kind == "search_entry":
        return (
            method == "click"
            and observation["kind"] == "feed"
            and (label == "搜索" or role == "search_field")
        )
    if kind == "query":
        return method == "type" and role == "search_field" and state["stage"] == "search"
    if kind == "search_submit":
        return method == "click" and label == "搜索" and observation.get("queryMatches") is True
    if kind == "open_target":
        return method == "click" and TITLE in label and state["stage"] == "open_first"
    flags = {
        "like": ("like", False, False),
        "collect": ("collect", True, False),
        "unlike": ("like", True, True),
        "uncollect": ("collect", False, True),
    }
    expected_role, liked, collected = flags[kind]
    return (
        method in {"click", "tap"}
        and role == expected_role
        and state["stage"] == kind
        and observation.get("kind") == "detail"
        and observation.get("firstTitleVisible") is True
        and observation.get("liked") is liked
        and observation.get("collected") is collected
    )


def intent_completed(kind, snapshot):
    state, observed = snapshot["state"], snapshot["state"]["observation"]
    if any(state.get(key) != value for key, value in TASK.items()):
        return False
    if kind == "search_entry":
        return observed["kind"] == "search_input"
    if kind == "query":
        return observed.get("queryMatches") is True and state["stage"] == "search"
    if kind == "search_submit":
        return (
            observed["kind"] == "results"
            and observed.get("queryMatches") is True
            and state["stage"] == "open_first"
        )
    stages = {
        "open_target": ("like", False, False),
        "like": ("collect", True, False),
        "collect": ("unlike", True, True),
        "unlike": ("uncollect", False, True),
        "uncollect": ("done", False, False),
    }
    stage, liked, collected = stages[kind]
    return (
        state["stage"] == stage
        and observed["kind"] == "detail"
        and observed.get("firstTitleVisible") is True
        and observed.get("liked") is liked
        and observed.get("collected") is collected
    )


def evidence_matches_plan(evidence, plan):
    """Require real matched actions and verified transitions for every raw intent."""
    try:
        steps = evidence["steps"]
        annotated = [
            (index, step)
            for index, step in enumerate(steps)
            if step.get("rawEventSequence") is not None
        ]
        if evidence.get("recordingValidation") != validation_binding(plan) or len(annotated) != len(
            plan["intents"]
        ):
            return False
        for position, ((index, step), intent) in enumerate(zip(annotated, plan["intents"])):
            end = annotated[position + 1][0] if position + 1 < len(annotated) else len(steps)
            if (
                step.get("rawEventSequence") != intent["eventSequence"]
                or step.get("intentKind") != intent["kind"]
                or step.get("delivery", "applied") != "applied"
                or not matches_intent(intent["kind"], step["before"], step["action"])
                or not any(
                    intent_completed(intent["kind"], item["after"]) for item in steps[index:end]
                )
            ):
                return False
            if any(item["action"].get("method") != "wait" for item in steps[index + 1 : end]):
                return False
        return annotated[0][0] == 0
    except (KeyError, TypeError, ValueError, IndexError):
        return False
