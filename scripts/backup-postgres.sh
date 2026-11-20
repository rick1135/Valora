#!/usr/bin/env bash
set -euo pipefail
umask 077

BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
BACKUP_FILE="${BACKUP_DIR}/valora_$(date +%Y%m%d_%H%M%S).sql.gz"
TMP_FILE="${BACKUP_FILE}.tmp.$$"

mkdir -p "$BACKUP_DIR"

cleanup() {
  rm -f "$TMP_FILE"
}
trap cleanup EXIT INT TERM

if [[ -n "${PGPASSWORD:-}" ]]; then
  : "${PGHOST:?PGHOST is required when using PGPASSWORD}"
  : "${PGUSER:?PGUSER is required when using PGPASSWORD}"
  : "${PGDATABASE:?PGDATABASE is required when using PGPASSWORD}"
  pg_dump | gzip > "$TMP_FILE"
else
  docker compose -f docker-compose.prod.yml exec -T postgres \
    sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' | gzip > "$TMP_FILE"
fi

if [[ ! -s "$TMP_FILE" ]] || ! gzip -t "$TMP_FILE"; then
  echo "Backup is empty or corrupted: $TMP_FILE" >&2
  exit 1
fi

mv "$TMP_FILE" "$BACKUP_FILE"
find "$BACKUP_DIR" -type f -name 'valora_*.sql.gz' -mtime "+${RETENTION_DAYS}" -delete
printf 'Backup created: %s\n' "$BACKUP_FILE"
