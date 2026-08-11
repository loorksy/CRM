#!/usr/bin/env bash
# Deploy / update LorkERP on the VPS.
# Expects: Node, PM2, Postgres, nginx already installed (see vps-bootstrap.sh)
# Env (optional): APP_DIR, REPO_URL, GIT_REF, DOMAIN, DB_NAME, DB_USER, DB_PASSWORD,
#                 ADMIN_USERNAME, ADMIN_PASSWORD, SESSION_SECRET, EVOLUTION_EMBEDDED
set -euo pipefail

APP_DIR="${APP_DIR:-/root/ERPSYSTEM-}"
REPO_URL="${REPO_URL:-https://github.com/loorksy/ERPSYSTEM-.git}"
GIT_REF="${GIT_REF:-main}"
DOMAIN="${DOMAIN:-erp.lork.cloud}"
DB_NAME="${DB_NAME:-lorkerp}"
DB_USER="${DB_USER:-lork}"
PORT="${PORT:-3020}"
EVOLUTION_EMBEDDED="${EVOLUTION_EMBEDDED:-1}"
EVOLUTION_MOUNT_PATH="${EVOLUTION_MOUNT_PATH:-/whatsapp-api}"

rand_hex() { openssl rand -hex "${1:-24}"; }
rand_alnum() { openssl rand -base64 32 | tr -dc 'A-Za-z0-9' | head -c "${1:-32}"; }

if [[ -z "${DB_PASSWORD:-}" ]]; then
  if [[ -f "${APP_DIR}/.env" ]] && grep -q '^DATABASE_URL=' "${APP_DIR}/.env"; then
    DB_PASSWORD="$(python3 - <<'PY' "${APP_DIR}/.env"
import sys, urllib.parse
from pathlib import Path
for line in Path(sys.argv[1]).read_text().splitlines():
    if line.startswith("DATABASE_URL="):
        u = urllib.parse.urlparse(line.split("=",1)[1].strip())
        print(urllib.parse.unquote(u.password or ""))
        break
PY
)"
  fi
fi
DB_PASSWORD="${DB_PASSWORD:-$(rand_alnum 28)}"
SESSION_SECRET="${SESSION_SECRET:-$(rand_hex 32)}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-$(rand_alnum 16)}"
EVOLUTION_API_KEY="${EVOLUTION_API_KEY:-$(rand_hex 24)}"
WHATSAPP_WEBHOOK_SECRET="${WHATSAPP_WEBHOOK_SECRET:-$(rand_hex 24)}"
AGENT_DB_USER="${AGENT_DB_USER:-lorkerp_agent}"
AGENT_DB_PASSWORD="${AGENT_DB_PASSWORD:-$(rand_alnum 28)}"

echo "==> Ensuring PostgreSQL role/database"
export DEPLOY_DB_NAME="$DB_NAME" DEPLOY_DB_USER="$DB_USER" DEPLOY_DB_PASSWORD="$DB_PASSWORD"
sudo -u postgres -E python3 - <<'PY'
import os, subprocess, shlex
name = os.environ["DEPLOY_DB_NAME"]
user = os.environ["DEPLOY_DB_USER"]
password = os.environ["DEPLOY_DB_PASSWORD"]
# Escape single quotes for SQL string literals
pw = password.replace("'", "''")
sql = f"""
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '{user}') THEN
    CREATE ROLE {user} LOGIN PASSWORD '{pw}';
  ELSE
    ALTER ROLE {user} WITH LOGIN PASSWORD '{pw}';
  END IF;
END
$$;
"""
subprocess.run(["psql", "-v", "ON_ERROR_STOP=1", "-c", sql], check=True)
exists = subprocess.run(
    ["psql", "-tAc", f"SELECT 1 FROM pg_database WHERE datname='{name}'"],
    check=True, capture_output=True, text=True,
).stdout.strip()
if exists != "1":
    subprocess.run(["psql", "-v", "ON_ERROR_STOP=1", "-c", f"CREATE DATABASE {name} OWNER {user}"], check=True)
subprocess.run(["psql", "-v", "ON_ERROR_STOP=1", "-c", f"GRANT ALL PRIVILEGES ON DATABASE {name} TO {user}"], check=True)
print("postgres role/db ready")
PY

# Allow password auth for local app connections if needed
PG_HBA="$(ls /etc/postgresql/*/main/pg_hba.conf 2>/dev/null | head -1 || true)"
if [[ -n "${PG_HBA}" ]] && ! grep -qE "^local\s+all\s+${DB_USER}\s+md5" "${PG_HBA}"; then
  sed -i "1i local   all             ${DB_USER}                                scram-sha-256" "${PG_HBA}"
  sed -i "1i host    all             ${DB_USER}        127.0.0.1/32            scram-sha-256" "${PG_HBA}"
  systemctl reload postgresql || systemctl restart postgresql
fi

echo "==> Syncing application source to ${APP_DIR}"
mkdir -p "${APP_DIR}"
if [[ -d "${APP_DIR}/.git" ]]; then
  git -C "${APP_DIR}" fetch --all --prune
  git -C "${APP_DIR}" checkout "${GIT_REF}"
  git -C "${APP_DIR}" pull --ff-only origin "${GIT_REF}" || git -C "${APP_DIR}" reset --hard "origin/${GIT_REF}"
else
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    git clone --branch "${GIT_REF}" "https://x-access-token:${GITHUB_TOKEN}@github.com/loorksy/ERPSYSTEM-.git" "${APP_DIR}"
    git -C "${APP_DIR}" remote set-url origin "${REPO_URL}"
  else
    echo "APP_DIR has no git checkout and GITHUB_TOKEN is unset." >&2
    echo "Either clone manually into ${APP_DIR} or export GITHUB_TOKEN." >&2
    exit 1
  fi
fi

cd "${APP_DIR}"

echo "==> Writing .env"
umask 077
DB_PASSWORD_URLENC="$(python3 - <<'PY' "$DB_PASSWORD"
import sys, urllib.parse
print(urllib.parse.quote(sys.argv[1], safe=""))
PY
)"
cat > .env <<EOF
PORT=${PORT}
NODE_ENV=production
SESSION_SECRET=${SESSION_SECRET}
DATABASE_URL=postgresql://${DB_USER}:${DB_PASSWORD_URLENC}@localhost:5432/${DB_NAME}
ADMIN_USERNAME=${ADMIN_USERNAME}
ADMIN_PASSWORD=${ADMIN_PASSWORD}
PUBLIC_BASE_URL=https://${DOMAIN}

EVOLUTION_EMBEDDED=${EVOLUTION_EMBEDDED}
EVOLUTION_MOUNT_PATH=${EVOLUTION_MOUNT_PATH}
EVOLUTION_API_KEY=${EVOLUTION_API_KEY}
EVOLUTION_API_URL=
WHATSAPP_WEBHOOK_SECRET=${WHATSAPP_WEBHOOK_SECRET}

AGENT_DB_USER=${AGENT_DB_USER}
AGENT_DB_PASSWORD=${AGENT_DB_PASSWORD}
AGENT_QUERY_MAX_ROWS=200
AGENT_QUERY_TIMEOUT_MS=8000
AGENT_HARDEN_SET_CONFIG=1

WHATSAPP_HUMANIZE=1
WHATSAPP_STT_ENABLED=1
WHATSAPP_MEMORY_ENABLED=1
EOF
chmod 600 .env

if [[ "${EVOLUTION_EMBEDDED}" == "1" ]]; then
  mkdir -p integrations/evolution-api
  cat > integrations/evolution-api/.env <<EOF
SERVER_TYPE=http
SERVER_PORT=${PORT}
AUTHENTICATION_API_KEY=${EVOLUTION_API_KEY}
DATABASE_ENABLED=true
DATABASE_PROVIDER=postgresql
DATABASE_CONNECTION_URI=postgresql://${DB_USER}:${DB_PASSWORD_URLENC}@localhost:5432/${DB_NAME}?schema=evolution_api
DATABASE_CONNECTION_CLIENT_NAME=lorkerp_evolution
CACHE_REDIS_ENABLED=false
CACHE_LOCAL_ENABLED=true
CORS_ORIGIN=*
CORS_METHODS=GET,POST,PUT,DELETE
CORS_CREDENTIALS=true
LOG_LEVEL=ERROR,WARN
DEL_INSTANCE=false
LANGUAGE=en
EOF
  chmod 600 integrations/evolution-api/.env
fi

echo "==> npm install"
npm install --omit=dev

if [[ "${EVOLUTION_EMBEDDED}" == "1" ]]; then
  echo "==> Evolution setup"
  npm run evolution:setup
fi

echo "==> Building CSS"
npm run css:build

echo "==> Installing nginx site"
if [[ -f deploy/nginx/erp.lork.cloud.conf ]]; then
  # HTTP-only bootstrap until certs exist
  if [[ ! -f /etc/letsencrypt/live/${DOMAIN}/fullchain.pem ]]; then
    cat > /etc/nginx/sites-available/${DOMAIN} <<NGINX
server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN} www.${DOMAIN};
    location /.well-known/acme-challenge/ { root /var/www/html; }
    location / {
        proxy_pass http://127.0.0.1:${PORT};
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
NGINX
  else
    cp deploy/nginx/erp.lork.cloud.conf /etc/nginx/sites-available/${DOMAIN}
  fi
  ln -sfn /etc/nginx/sites-available/${DOMAIN} /etc/nginx/sites-enabled/${DOMAIN}
  rm -f /etc/nginx/sites-enabled/default
  nginx -t
  systemctl reload nginx
fi

echo "==> Starting / restarting PM2 app"
if pm2 describe lorkerp >/dev/null 2>&1; then
  pm2 restart ecosystem.config.cjs --update-env
else
  pm2 start ecosystem.config.cjs
fi
pm2 save
pm2 startup systemd -u root --hp /root >/dev/null 2>&1 || true

sleep 3
pm2 list
curl -sS -o /dev/null -w "local_http=%{http_code}\n" "http://127.0.0.1:${PORT}/" || true

echo "==> Deploy finished"
echo "URL: https://${DOMAIN}"
echo "Admin user: ${ADMIN_USERNAME}"
echo "Admin password: ${ADMIN_PASSWORD}"
echo "(credentials also in ${APP_DIR}/.env — chmod 600)"
