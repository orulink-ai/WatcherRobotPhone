"""Non-secret phone runtime configuration for the ordinary PI extension."""

from __future__ import annotations
import argparse
import json
import os
from pathlib import Path

DIRECTORY = Path(__file__).resolve().parent


def launch_environment(profile_path=None):
    path = Path(
        profile_path or os.environ.get("PI_PHONE_PROFILE", "~/.config/orulink-phone/terminal.json")
    ).expanduser()
    profile = json.loads(path.read_text())
    python = Path(profile["python"]).expanduser()
    if not python.is_absolute():
        raise ValueError("Absolute phone Python runtime required")
    config = Path(profile["mcpConfig"]).expanduser().resolve()
    server = profile["mcpServer"]
    settings = json.loads(config.read_text())["mcpServers"][server]
    url = settings["url"]
    if not isinstance(url, str) or not url.startswith(("http://", "https://")):
        raise ValueError("HTTP Android MCP required")
    env = {
        "PHONE_BENCH_PYTHON": str(python),
        "PHONE_BENCH_WORKER": str(DIRECTORY / "phone_local_worker.py"),
        "PHONE_BENCH_OUTPUT_DIR": str(
            Path(profile.get("output", "~/.local/share/orulink-phone/terminal"))
            .expanduser()
            .resolve()
        ),
        "ANDROID_MCP_CONFIG": str(config),
        "ANDROID_MCP_SERVER": server,
        "ANDROID_MCP_URL": url,
        "PHONE_BENCH_STARTLUX_ALLOW_LAN": "1",
        "PHONE_BENCH_STARTLUX_INSTANT_URL": profile.get(
            "localPrimaryUrl", "http://192.168.50.26:8094/v1/systemone"
        ),
        "PHONE_BENCH_STARTLUX_TIMEOUT": "5",
        "PHONE_BENCH_JEV_TIMEOUT": "5",
        "PHONE_BENCH_DEEPSEEK_TIMEOUT": "15",
        "PHONE_CLOUD_DS_API_KEY_FILE": str(
            Path(
                profile.get("cloudDeepSeekKeyFile", "~/.config/orulink-phone/cloud-deepseek.key")
            ).expanduser()
        ),
        "PHONE_EXPERIENCE_ENABLED": "1" if profile.get("experienceEnabled", True) else "0",
    }
    if profile.get("recipe") and profile.get("experienceEnabled", True):
        env["PHONE_BENCH_RECIPE"] = str(Path(profile["recipe"]).expanduser().resolve())
    else:
        env["PHONE_BENCH_RECIPE"] = ""
    return env


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--launch-env", action="store_true", required=True)
    parser.add_argument("--profile")
    args = parser.parse_args()
    try:
        print(json.dumps({"env": launch_environment(args.profile)}, ensure_ascii=False))
    except (OSError, ValueError, KeyError, TypeError):
        print(
            json.dumps(
                {"error": "Phone configuration could not be loaded; no phone operation performed."}
            )
        )
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
