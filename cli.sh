#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")/server"
set -a; [ -f .env ] && . ./.env; set +a
node cli.js "$@"
