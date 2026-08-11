#!/usr/bin/env bash
# Deploy WhatsAppDesktopBot on the same VPS as LorkERP (port 3030 → bot.lork.cloud)
set -euo pipefail

BOT_DIR="${BOT_DIR:-/root/WhatsAppDesktopBot}"
BOT_REPO="${BOT_REPO:-https://github.com/loorksy/WhatsAppDesktopBot.git}"
BOT_PORT="${BOT_PORT:-3030}"
NGINX_SRC="${NGINX_SRC:-}"
JWT_VAL="${JWT_SECRET:-}"

echo "==> Ensure Chromium runtime libs"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq \
  ca-certificates curl git \
  libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 \
  libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 \
  libgbm1 libasound2t64 libpango-1.0-0 libcairo2 libx11-xcb1 \
  libxcb-dri3-0 fonts-liberation 2>/dev/null \
  || apt-get install -y -qq \
  ca-certificates curl git \
  libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 \
  libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 \
  libgbm1 libasound2 libpango-1.0-0 libcairo2 libx11-xcb1 \
  libxcb-dri3-0 fonts-liberation

if [[ ! -d "$BOT_DIR/.git" ]]; then
  echo "==> Clone $BOT_REPO → $BOT_DIR"
  git clone --depth 1 "$BOT_REPO" "$BOT_DIR"
else
  echo "==> Pull latest in $BOT_DIR"
  git -C "$BOT_DIR" fetch --depth 1 origin || true
  git -C "$BOT_DIR" reset --hard FETCH_HEAD 2>/dev/null \
    || git -C "$BOT_DIR" pull --ff-only || true
fi

cd "$BOT_DIR"
echo "==> npm install"
npm install --omit=dev

mkdir -p releases public/downloads
if [[ ! -f releases/bulk-sender.apk ]]; then
  echo "WARNING: releases/bulk-sender.apk missing"
fi

if [[ -z "$JWT_VAL" ]]; then
  JWT_VAL="$(openssl rand -hex 24)"
fi

cat > /root/whatsappbot.ecosystem.cjs <<EOF
module.exports = {
  apps: [{
    name: 'whatsappbot',
    cwd: '${BOT_DIR}',
    script: 'index.js',
    env: {
      PORT: '${BOT_PORT}',
      NODE_ENV: 'production',
      JWT_SECRET: '${JWT_VAL}',
    },
  }],
};
EOF

echo "==> PM2 start/restart whatsappbot"
pm2 delete whatsappbot 2>/dev/null || true
pm2 start /root/whatsappbot.ecosystem.cjs
pm2 save

mkdir -p /var/www/html

# HTTP bootstrap (certbot) if cert missing
if [[ ! -d /etc/letsencrypt/live/bot.lork.cloud ]]; then
  echo "==> Temporary HTTP nginx for bot.lork.cloud + certbot"
  cat > /etc/nginx/sites-available/bot.lork.cloud <<NGX
server {
    listen 80;
    listen [::]:80;
    server_name bot.lork.cloud;
    location /.well-known/acme-challenge/ { root /var/www/html; }
    location / {
        proxy_pass http://127.0.0.1:${BOT_PORT};
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
NGX
  ln -sfn /etc/nginx/sites-available/bot.lork.cloud /etc/nginx/sites-enabled/bot.lork.cloud
  nginx -t && systemctl reload nginx
  certbot --nginx -d bot.lork.cloud --non-interactive --agree-tos -m loorksy@gmail.com --redirect || true
fi

if [[ -n "$NGINX_SRC" && -f "$NGINX_SRC" && -d /etc/letsencrypt/live/bot.lork.cloud ]]; then
  echo "==> Install SSL nginx config from $NGINX_SRC"
  cp "$NGINX_SRC" /etc/nginx/sites-available/bot.lork.cloud
  ln -sfn /etc/nginx/sites-available/bot.lork.cloud /etc/nginx/sites-enabled/bot.lork.cloud
  nginx -t && systemctl reload nginx
elif [[ -d /etc/letsencrypt/live/bot.lork.cloud ]]; then
  echo "==> Cert present; keeping current nginx site"
  nginx -t && systemctl reload nginx || true
fi

echo "==> Health"
sleep 2
curl -sS -o /dev/null -w "local_bot=%{http_code}\n" "http://127.0.0.1:${BOT_PORT}/" || true
pm2 status whatsappbot
echo "Done."
