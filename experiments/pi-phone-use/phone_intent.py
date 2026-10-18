"""Classify a current PI input without importing or connecting any phone client."""

import json
import re
import sys

PHONE_CUE = re.compile(r"手机|小红书|屏幕|界面|页面|微信|抖音|支付宝|相册|相机|计算器|设置")
QUESTION = re.compile(
    r"^(?:请)?(?:解释|介绍|讲解|教我|写|编写|帮我写)|如何|怎么|是什么|(?:你|PI|pi).*(?:能|会|可以).*(?:控制|操作|使用)手机.*(?:吗|么|？|\?)"
)
COMMAND = re.compile(r"打开|启动|读取|查看|看看|获取|搜索|点赞|收藏|点击|输入|滑动|退出|返回|回到")


def classify(text):
    if not isinstance(text, str) or not 1 <= len(text.strip()) <= 4000:
        return {"kind": "chat"}
    text = text.strip()
    outer = re.sub(r'“[^”]*”|"[^"]*"|「[^」]*」|\'[^\']*\'', "", text)
    if (
        not PHONE_CUE.search(outer)
        or QUESTION.search(outer)
        or not COMMAND.search(outer)
        or re.match(r"^(?:请)?(?:不要|别|不需要|不用|无需|不必)", outer)
    ):
        return {"kind": "chat"}
    from phone_exit_runtime import resolve_phone_task

    try:
        task, exit_requested = resolve_phone_task(text)
    except ValueError:
        return {
            "kind": "unsupported",
            "message": "这条手机请求超出当前支持范围，未操作手机。请明确应用、搜索词、单个目标和操作。",
        }
    return {"kind": "phone", "task": task, "exitRequested": exit_requested}


def main():
    try:
        request = json.loads(sys.stdin.read(20000))
        result = classify(request.get("text")) if isinstance(request, dict) else {"kind": "chat"}
    except (ValueError, TypeError):
        result = {"kind": "unsupported", "message": "手机请求格式无效，未操作手机。"}
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
