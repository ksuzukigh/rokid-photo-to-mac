#!/bin/bash

set -e
set -o pipefail
export PATH="/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK="$SCRIPT_DIR/Photo-to-Mac.apk"
TOKEN_FILE="$HOME/Library/Application Support/Rokid Photo Bridge/token.txt"
PACKAGE="io.github.ksuzukigh.phototomac"

pause_and_exit() {
    echo
    read -r -p "Enterキーで処理を終了します..."
    exit 1
}

if [ ! -f "$APK" ]; then
    echo "Photo-to-Mac.apkが見つかりません。ダウンロードしたフォルダを確認してください。"
    pause_and_exit
fi

if [ ! -f "$TOKEN_FILE" ]; then
    echo "MacとRokidを安全に組み合わせる合言葉がまだありません。"
    echo "先に『Mac受信機を設定.command』を実行してください。"
    pause_and_exit
fi

TOKEN="$(tr -d '\r\n' < "$TOKEN_FILE")"
if ! printf '%s' "$TOKEN" | grep -Eq '^[0-9a-f]{32}$'; then
    echo "Mac側の合言葉を確認できませんでした。"
    echo "『Mac受信機を設定.command』をもう一度実行してください。"
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
INSTALL_LOG="$(mktemp)"
if ! adb -s "$SERIAL" install -r "$APK" 2>&1 | tee "$INSTALL_LOG"; then
    if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" "$INSTALL_LOG"; then
        echo "以前の開発版と署名が異なるため、いったん削除して正式版を入れ直します。"
        adb -s "$SERIAL" uninstall "$PACKAGE" >/dev/null 2>&1 || true
        adb -s "$SERIAL" install "$APK"
    else
        rm -f "$INSTALL_LOG"
        echo "インストールに失敗しました。表示された内容をご確認ください。"
        pause_and_exit
    fi
fi
rm -f "$INSTALL_LOG"

adb -s "$SERIAL" shell am force-stop "$PACKAGE" >/dev/null
adb -s "$SERIAL" shell am start \
    -n "$PACKAGE/.MainActivity" \
    --es setup_token "$TOKEN" >/dev/null
echo "MacとRokidの合言葉を設定しました。"

echo
echo "インストールが完了しました。"
echo "Rokidのアプリ一覧から『Photo to Mac』を開いてください。"
read -r -p "Enterキーで処理を終了します..."
