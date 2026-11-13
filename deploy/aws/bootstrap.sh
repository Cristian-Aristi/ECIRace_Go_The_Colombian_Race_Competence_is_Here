#!/bin/bash
# =====================================================================================
# ECIRace GO - arranque de la instancia EC2 (Amazon Linux 2023)
# cloud-init lo ejecuta en CADA arranque (ver UserData en aws/ecirace-ec2.yaml), así que
# todo aquí es idempotente: instala solo lo que falta y vuelve a levantar los contenedores
# con la última imagen publicada. Por eso "detener + encender" la instancia = desplegar.
# =====================================================================================
set -euo pipefail
exec > >(tee -a /var/log/ecirace-bootstrap.log) 2>&1
echo "=== $(date -Is) bootstrap ECIRace GO ==="

APP_DIR=/opt/ecirace
COMPOSE_VERSION=v2.29.7
CADDY_IMAGE=caddy:2.10-alpine
cd "$APP_DIR"

# --- 1. Docker y el plugin de Compose ------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
  dnf install -y docker
fi
systemctl enable --now docker

PLUGIN=/usr/local/lib/docker/cli-plugins/docker-compose
if ! docker compose version 2>/dev/null | grep -q "${COMPOSE_VERSION#v}"; then
  base="https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-x86_64"
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/docker-compose-linux-x86_64" "$base"
  curl -fsSL -o "$tmp/docker-compose-linux-x86_64.sha256" "$base.sha256"
  (cd "$tmp" && sha256sum -c docker-compose-linux-x86_64.sha256)   # integridad de la descarga
  install -D -m 755 "$tmp/docker-compose-linux-x86_64" "$PLUGIN"
  rm -rf "$tmp"
fi

# --- 2. Secretos persistentes: se generan una sola vez y nunca salen de la instancia ---
umask 077
install -d -m 700 data data/postgres data/caddy
touch data/secrets.env
grep -q '^DB_PASSWORD=' data/secrets.env || echo "DB_PASSWORD=$(openssl rand -hex 24)" >> data/secrets.env
# Secreto de los tokens de sesión: si cambia, todos los pilotos deben volver a iniciar sesión
grep -q '^ECIRACE_TOKEN_SECRET=' data/secrets.env || echo "ECIRACE_TOKEN_SECRET=$(openssl rand -hex 32)" >> data/secrets.env

# --- 3. Dominio público (IMDSv2) ------------------------------------------------------
imds() {
  local token
  token=$(curl -fsS -X PUT "http://169.254.169.254/latest/api/token" \
    -H "X-aws-ec2-metadata-token-ttl-seconds: 300")
  curl -fsS -H "X-aws-ec2-metadata-token: $token" "http://169.254.169.254/latest/meta-data/$1"
}
# shellcheck disable=SC1091
source deploy.env   # APP_IMAGE, METRICS_HASH_B64, CUSTOM_DOMAIN, GOOGLE_CLIENT_ID, RATE_LIMIT_ENABLED y DEMO_TOOLS (UserData)
PUBLIC_IP=$(imds public-ipv4)
SSLIP_DOMAIN="${PUBLIC_IP//./-}.sslip.io"
# Caddy atiende (y certifica) el dominio propio, si existe, y siempre el de sslip.io como respaldo
if [ -n "${CUSTOM_DOMAIN:-}" ]; then
  DOMAIN="$CUSTOM_DOMAIN $SSLIP_DOMAIN"
  MAIN_DOMAIN="$CUSTOM_DOMAIN"
  ALLOWED_ORIGINS="https://$CUSTOM_DOMAIN,https://$SSLIP_DOMAIN"
else
  DOMAIN="$SSLIP_DOMAIN"
  MAIN_DOMAIN="$SSLIP_DOMAIN"
  ALLOWED_ORIGINS="https://$SSLIP_DOMAIN"
fi
echo "Dominios: $DOMAIN"

# --- 4. Usuario de métricas -------------------------------------------------------------
# La contraseña nunca llega a la instancia: scripts/aws.py envía solo su hash bcrypt (en base64),
# así que leer el UserData de la EC2 no revela la clave.
HASH=$(printf '%s' "$METRICS_HASH_B64" | base64 -d)
case "$HASH" in
  '$2a$'*|'$2b$'*|'$2y$'*) ;;
  *) echo "METRICS_HASH_B64 no es un hash bcrypt válido" >&2; exit 1 ;;
esac
printf 'basic_auth {\n\tmetrics %s\n}\n' "$HASH" > metrics_auth.caddy

# --- 5. Variables para Compose ----------------------------------------------------------
{
  echo "APP_IMAGE=$APP_IMAGE"
  cat data/secrets.env
  echo "DOMAIN=\"$DOMAIN\""
  echo "ALLOWED_ORIGINS=$ALLOWED_ORIGINS"
  echo "GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID:-}"
  echo "RATE_LIMIT_ENABLED=${RATE_LIMIT_ENABLED:-true}"
  echo "DEMO_TOOLS=${DEMO_TOOLS:-false}"
} > .env

# --- 6. Levantar (siempre con la última imagen) -----------------------------------------
docker compose pull --quiet
docker compose up -d --remove-orphans
docker image prune -f >/dev/null
echo "=== listo: https://$MAIN_DOMAIN ==="
