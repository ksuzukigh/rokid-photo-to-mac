#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNTIME_DIR="$HOME/Library/Application Support/Rokid Photo Bridge"
LAUNCH_AGENTS_DIR="$HOME/Library/LaunchAgents"
PLIST="$LAUNCH_AGENTS_DIR/io.github.ksuzukigh.photo-to-mac.plist"
LOG_OUT="$HOME/Library/Logs/rokid-photo-bridge.log"
LOG_ERR="$HOME/Library/Logs/rokid-photo-bridge.error.log"
USER_ID="$(id -u)"
PYTHON_PATH="$(command -v python3 || true)"

if [ -z "$PYTHON_PATH" ]; then
    if command -v brew >/dev/null 2>&1; then
        echo "Macの写真受信に必要なソフトを準備しています..."
        brew install python
        PYTHON_PATH="$(command -v python3 || true)"
    fi

    if [ -z "$PYTHON_PATH" ]; then
        echo "準備に必要なHomebrewが見つかりません。"
        echo "https://brew.sh/ja/ を開いてHomebrewをインストールしてください。"
        read -r -p "Enterキーで処理を終了します..."
        exit 1
    fi
fi

mkdir -p "$RUNTIME_DIR" "$LAUNCH_AGENTS_DIR" "$HOME/Pictures/Rokid Inbox"
cp "$SCRIPT_DIR/mac_receiver.py" "$RUNTIME_DIR/mac_receiver.py"

"$PYTHON_PATH" - "$PLIST" "$PYTHON_PATH" "$RUNTIME_DIR/mac_receiver.py" "$RUNTIME_DIR" "$LOG_OUT" "$LOG_ERR" <<'PY'
import plistlib
import sys

plist_path, python_path, receiver, working_dir, log_out, log_err = sys.argv[1:]
payload = {
    "Label": "io.github.ksuzukigh.photo-to-mac",
    "ProgramArguments": [python_path, receiver],
    "WorkingDirectory": working_dir,
    "RunAtLoad": True,
    "KeepAlive": True,
    "ProcessType": "Background",
    "EnvironmentVariables": {"PYTHONUNBUFFERED": "1"},
    "StandardOutPath": log_out,
    "StandardErrorPath": log_err,
    "ThrottleInterval": 5,
}
with open(plist_path, "wb") as output:
    plistlib.dump(payload, output)
PY

launchctl bootout "gui/$USER_ID" "$PLIST" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$USER_ID" "$PLIST"

echo "Macの写真受信機を設定しました。"
echo "受信した写真は『ピクチャ/Rokid Inbox』へ保存されます。"
read -r -p "Enterキーで処理を終了します..."
