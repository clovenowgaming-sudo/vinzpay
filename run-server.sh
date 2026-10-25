#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")/server"
set -a; [ -f .env ] && . ./.env; set +a
echo "Server jalan. Di app isi URL: http://127.0.0.1:3000"
termux-wake-lock 2>/dev/null
node server.js
