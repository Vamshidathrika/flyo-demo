#!/usr/bin/env bash
# =================================================================
# FLYO PRODUCTION DATABASE BACKUP & RESTORE UTILITY
# =================================================================
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/tmp/flyo_backups}"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-flyodb}"
DB_USER="${DB_USER:-flyo_admin}"

mkdir -p "$BACKUP_DIR"

usage() {
    echo "Usage: $0 [backup|restore <backup_file>|verify <backup_file>]"
    exit 1
}

backup() {
    local backup_file="$BACKUP_DIR/flyodb_backup_${TIMESTAMP}.dump"
    echo "==> Starting PostgreSQL backup of '$DB_NAME' to $backup_file..."
    
    pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -F c -b -v -f "$backup_file" "$DB_NAME"
    
    # Generate SHA-256 Checksum for tamper evidence
    shasum -a 256 "$backup_file" > "${backup_file}.sha256"
    
    echo "==> Backup complete: $backup_file ($(du -h "$backup_file" | cut -f1))"
    echo "==> SHA-256 Checksum generated: $(cat "${backup_file}.sha256")"
}

restore() {
    local target_file="${1:-}"
    if [ -z "$target_file" ] || [ ! -f "$target_file" ]; then
        echo "Error: Backup file '$target_file' not found."
        exit 1
    fi
    
    echo "==> Verifying SHA-256 checksum before restoring..."
    if [ -f "${target_file}.sha256" ]; then
        shasum -a 256 -c "${target_file}.sha256"
        echo "==> Checksum verified successfully."
    else
        echo "WARNING: No checksum file found for $target_file."
    fi
    
    echo "==> Restoring database '$DB_NAME' from $target_file..."
    pg_restore -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" --clean --if-exists -v "$target_file"
    echo "==> Database restore completed successfully."
}

verify() {
    local target_file="${1:-}"
    if [ -z "$target_file" ] || [ ! -f "$target_file" ]; then
        echo "Error: Backup file '$target_file' not found."
        exit 1
    fi
    
    echo "==> Inspecting backup contents without altering database..."
    pg_restore -l "$target_file" | head -n 30
    echo "==> Backup format and catalog verified valid."
}

case "${1:-}" in
    backup)
        backup
        ;;
    restore)
        restore "${2:-}"
        ;;
    verify)
        verify "${2:-}"
        ;;
    *)
        usage
        ;;
esac
