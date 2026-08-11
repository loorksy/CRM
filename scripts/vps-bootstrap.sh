#!/usr/bin/env bash
# Bootstrap a fresh Ubuntu 24.04 VPS for LorkERP (erp.lork.cloud)
# Run as root on the VPS.
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
APP_DIR="${APP_DIR:-/root/ERPSYSTEM-}"
APP_USER="${APP_USER:-root}"
DOMAIN="${DOMAIN:-erp.lork.cloud}"
NODE_MAJOR="${NODE_MAJOR:-22}"

echo "==> Updating apt packages"
apt-get update -qq
apt-get install -y -qq \
  curl ca-certificates gnupg git ufw \
  nginx certbot python3-certbot-nginx \
  postgresql postgresql-contrib \
  build-essential python3

echo "==> Installing Node.js ${NODE_MAJOR}.x"
if ! command -v node >/dev/null 2>&1 || [[ "$(node -v | sed 's/v//;s/\..*//')" != "$NODE_MAJOR" ]]; then
  curl -fsSL "https://deb.nodesource.com/setup_${NODE_MAJOR}.x" | bash -
  apt-get install -y -qq nodejs
fi
node -v
npm -v

echo "==> Installing PM2 globally"
npm install -g pm2

echo "==> Ensuring PostgreSQL is running"
systemctl enable --now postgresql

echo "==> Firewall (SSH/HTTP/HTTPS)"
ufw allow OpenSSH >/dev/null 2>&1 || true
ufw allow 80/tcp >/dev/null 2>&1 || true
ufw allow 443/tcp >/dev/null 2>&1 || true
# Non-interactive enable if not already active
ufw --force enable >/dev/null 2>&1 || true

echo "==> Bootstrap complete"
echo "Node: $(node -v)  npm: $(npm -v)  pm2: $(pm2 -v)"
echo "Postgres: $(systemctl is-active postgresql)"
echo "Nginx: $(systemctl is-active nginx)"
