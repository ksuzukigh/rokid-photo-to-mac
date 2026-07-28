#!/bin/bash

set -Ee
export PATH="/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:$PATH"

show_error() {
    local exit_code="$?"
    trap - ERR
    echo
    echo "設定の途中で問題が発生しました。"
    echo "この画面をそのまま残して、作者へお知らせください。"
    read -r -p "Enterキーで処理を終了します..." || true
    exit "$exit_code"
}
trap show_error ERR

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNTIME_DIR="$HOME/Library/Application Support/Rokid Photo Bridge"
LAUNCH_AGENTS_DIR="$HOME/Library/LaunchAgents"
PLIST="$LAUNCH_AGENTS_DIR/io.github.ksuzukigh.photo-to-mac.plist"
LOG_OUT="$HOME/Library/Logs/rokid-photo-bridge.log"
LOG_ERR="$HOME/Library/Logs/rokid-photo-bridge.error.log"
USER_ID="$(id -u)"
PYTHON_PATH=""

find_working_python() {
    local candidate
    for candidate in \
        /opt/homebrew/bin/python3 \
        /usr/local/bin/python3 \
        "$(command -v python3 || true)"
    do
        if [ -z "$candidate" ]; then
            continue
        fi
        if [ "$candidate" = "/usr/bin/python3" ] \
            && ! xcode-select -p >/dev/null 2>&1; then
            continue
        fi
        if "$candidate" -c "import plistlib" >/dev/null 2>&1; then
            PYTHON_PATH="$candidate"
            return 0
        fi
    done
    return 1
}

if ! find_working_python; then
    if command -v brew >/dev/null 2>&1; then
        echo "Macの写真受信に必要なソフトを準備しています..."
        brew install python
        find_working_python || true
    fi

    if [ -z "$PYTHON_PATH" ]; then
        echo "写真受信に使えるPythonが見つかりませんでした。"
        echo "https://brew.sh/ja/ を開いてHomebrewをインストールしてください。"
        read -r -p "Enterキーで処理を終了します..." || true
        exit 1
    fi
fi

if [ ! -f "$SCRIPT_DIR/mac_receiver.py" ]; then
    echo "mac_receiver.pyが見つかりません。"
    echo "ダウンロードしたフォルダ内のファイルを移動せず、もう一度実行してください。"
    read -r -p "Enterキーで処理を終了します..." || true
    exit 1
fi

mkdir -p "$RUNTIME_DIR" "$LAUNCH_AGENTS_DIR" "$HOME/Pictures/Rokid Inbox"
TOKEN_FILE="$RUNTIME_DIR/token.txt"
if [ ! -f "$TOKEN_FILE" ]; then
    (umask 077; openssl rand -hex 16 > "$TOKEN_FILE")
fi
chmod 600 "$TOKEN_FILE"
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
echo "使わないときは『Mac受信機を停止.command』で停止できます。"
read -r -p "Enterキーで処理を終了します..." || true
