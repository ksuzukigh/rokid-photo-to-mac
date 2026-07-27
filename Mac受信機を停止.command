#!/bin/bash

PLIST="$HOME/Library/LaunchAgents/io.github.ksuzukigh.photo-to-mac.plist"
USER_ID="$(id -u)"

if [ ! -f "$PLIST" ]; then
    echo "写真の受信機は設定されていません。"
    read -r -p "Enterキーで処理を終了します..."
    exit 0
fi

launchctl bootout "gui/$USER_ID" "$PLIST" >/dev/null 2>&1 || true
rm -f "$PLIST"

echo "Macの写真受信機を停止しました。"
echo "もう一度使うときは『Mac受信機を設定.command』を実行してください。"
echo "『ピクチャ/Rokid Inbox』に保存済みの写真はそのまま残ります。"
read -r -p "Enterキーで処理を終了します..."
