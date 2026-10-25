#!/usr/bin/env bash
# Pakai: ./push.sh nama-repo
set -e
git init -q 2>/dev/null || true
git add -A && git commit -qm "VinzPay" || true
git branch -M main
gh repo create "${1:-vinzpay}" --public --source=. --remote=origin --push
echo "Buka tab Actions di GitHub, tunggu build selesai, download artifact VinzPay-debug-apk"
