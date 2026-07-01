#!/usr/bin/env bash
# One-shot bring-up for the cljot-crud bot on a fresh server (no CapRover).
# Idempotent: safe to re-run. Requires Docker + Docker Compose v2.
# Run from the deploy/ directory:  ./setup.sh
set -euo pipefail
cd "$(dirname "$0")"

DC="docker compose"

# ---- 0. checks ----
if [ ! -f .env ]; then
  echo "ERROR: .env not found. Copy .env.example -> .env and fill it in." >&2
  exit 1
fi
if [ ! -f data/dumps/cljot_default.sql ]; then
  echo "ERROR: data/dumps/cljot_default.sql not found." >&2
  echo "       Transfer the extracted data into deploy/data/ first (see README)." >&2
  exit 1
fi

# load env (simple KEY=VALUE file)
set -a; . ./.env; set +a

: "${MINIO_USER:?set in .env}"; : "${MINIO_PASSWORD:?set in .env}"
: "${MINIO_BUCKET:?set in .env}"; : "${DB_USER:?}"; : "${DB_NAME:?}"

# ---- 1. render SeaweedFS S3 identities from .env ----
# NB: keep MINIO_USER / MINIO_PASSWORD free of quotes and backslashes.
echo "==> Rendering seaweed/s3.json"
mkdir -p seaweed
cat > seaweed/s3.json <<EOF
{
  "identities": [
    {
      "name": "cljot",
      "credentials": [
        { "accessKey": "${MINIO_USER}", "secretKey": "${MINIO_PASSWORD}" }
      ],
      "actions": ["Admin", "Read", "Write", "List", "Tagging"]
    }
  ]
}
EOF

# ---- 2. start infra ----
echo "==> Starting postgres + seaweedfs"
$DC up -d postgres seaweedfs

echo "==> Waiting for postgres to be healthy"
until [ "$($DC ps -q postgres | xargs -r docker inspect -f '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; do sleep 2; done
echo "==> Waiting for seaweedfs to be healthy"
until [ "$($DC ps -q seaweedfs | xargs -r docker inspect -f '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; do sleep 2; done

# ---- 3. restore DB (only if empty) ----
count="$($DC exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc 'select count(*) from file' 2>/dev/null | tr -d '[:space:]' || true)"
if [ -z "$count" ] || [ "$count" = "0" ]; then
  echo "==> Restoring database from data/dumps/cljot_default.sql"
  $DC run --rm db-restore
else
  echo "==> DB already has $count file rows — skipping restore"
fi

# ---- 4. upload video objects to SeaweedFS (idempotent) ----
echo "==> Uploading video objects to bucket '$MINIO_BUCKET'"
$DC run --rm s3-upload

# ---- 5. build & start the bot ----
echo "==> Building and starting the bot"
$DC up -d --build bot

echo
echo "==> Done. Status:"
$DC ps
echo
echo "Follow bot logs:   $DC logs -f bot"
