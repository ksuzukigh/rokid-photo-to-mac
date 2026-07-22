#!/bin/bash

set -e
export PATH="/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK="$SCRIPT_DIR/Photo-to-Mac.apk"

pause_and_exit() {
    echo
    read -r -p "Enterキーで処理を終了します..."
    exit 1
}

if [ ! -f "$APK" ]; then
    echo "Photo-to-Mac.apkが見つかりません。ダウンロードしたフォルダを確認してください。"
    pause_and_exit
fi

if ! command -v adb >/dev/null 2>&1; then
    if ! command -v brew >/dev/null 2>&1; then
        echo "アプリを入れる準備が必要です。先にHomebrewをインストールしてください。"
        echo "https://brew.sh/ja/"
        pause_and_exit
    fi
    echo "Rokidへアプリを入れるためのソフトを準備しています..."
    brew install android-platform-tools
fi

echo "Rokidを開発用5ピンケーブルでMacへつないでください。"
echo "接続を最大60秒待ちます..."

SERIAL=""
for attempt in $(seq 1 60); do
    SERIAL="$(adb devices | awk 'NR > 1 && $2 == "device" && $1 !~ /:/ { print $1; exit }')"
    if [ -n "$SERIAL" ]; then
        break
    fi

    STATE="$(adb devices | awk 'NR > 1 && $1 !~ /:/ { print $2; exit }')"
    if [ "$STATE" = "unauthorized" ]; then
        echo "Rokidに確認画面が出たら、USB接続を許可してください。"
    fi
    sleep 1
done

if [ -z "$SERIAL" ]; then
    echo "Rokidを確認できませんでした。ケーブルを抜き差しして、もう一度お試しください。"
    pause_and_exit
fi

echo "Photo to MacをRokidへ入れています..."
adb -s "$SERIAL" install -r "$APK"

echo
echo "インストールが完了しました。"
echo "Rokidのアプリ一覧から『Photo to Mac』を開いてください。"
read -r -p "Enterキーで処理を終了します..."
