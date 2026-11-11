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
if [ ! -s data/secrets.env ]; then
  echo "DB_PASSWORD=$(openssl rand -hex 24)" > data/secrets.env
fi

# --- 3. Dominio público (IMDSv2) ------------------------------------------------------
imds() {
  local token
  token=$(curl -fsS -X PUT "http://169.254.169.254/latest/api/token" \
    -H "X-aws-ec2-metadata-token-ttl-seconds: 300")
  curl -fsS -H "X-aws-ec2-metadata-token: $token" "http://169.254.169.254/latest/meta-data/$1"
}
PUBLIC_IP=$(imds public-ipv4)
DOMAIN="${PUBLIC_IP//./-}.sslip.io"
echo "Dominio: https://$DOMAIN"

# --- 4. Usuario de métricas (contraseña en bcrypt, nunca en texto plano en Caddy) -----
# shellcheck disable=SC1091
source deploy.env   # APP_IMAGE y METRICS_PASSWORD, escritos por el UserData
HASH=$(docker run --rm "$CADDY_IMAGE" caddy hash-password --plaintext "$METRICS_PASSWORD")
printf 'basic_auth {\n\tmetrics %s\n}\n' "$HASH" > metrics_auth.caddy

# --- 5. Variables para Compose ----------------------------------------------------------
{
  echo "APP_IMAGE=$APP_IMAGE"
  cat data/secrets.env
  echo "DOMAIN=$DOMAIN"
} > .env

# --- 6. Levantar (siempre con la última imagen) -----------------------------------------
docker compose pull --quiet
docker compose up -d --remove-orphans
docker image prune -f >/dev/null
echo "=== listo: https://$DOMAIN ==="
