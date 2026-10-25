#!/data/data/com.termux/files/usr/bin/bash
# pkg install nodejs -y  (sekali saja)
cd "$(dirname "$0")/server"
set -a; [ -f .env ] && . ./.env; set +a
node server.js
