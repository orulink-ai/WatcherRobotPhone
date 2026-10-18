#!/bin/bash
set -euo pipefail
backend_dir="$(cd "$(dirname "$0")" && pwd)"
startlux_root="${STARTLUX_ROOT:-/Users/studio/Documents/Codex/2026-10-17/new-chat/work/startlux}"
model_dir="${STARTLUX_MODEL:-/Users/studio/Documents/Codex/2026-10-17/new-chat/work/models/StartLux-Decision-v1.1-4B-Instant-MLX-8bit}"
runtime_dir="${STARTLUX_RUNTIME:-/Users/studio/Documents/Codex/2026-10-17/new-chat/outputs/startlux-lan}"
exec "$startlux_root/.venv/bin/python" "$backend_dir/manage.py" start --runtime-dir "$runtime_dir" --startlux-root "$startlux_root" --model "$model_dir" "$@"
