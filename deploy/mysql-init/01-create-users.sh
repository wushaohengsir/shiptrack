#!/bin/bash
# Runs once, while the MySQL data directory is still empty.
#
# The official image would create a single account with full rights on the
# schema. The project instead separates the accounts (技术栈使用说明 §3.7):
#   - the migration account (DDL) is only used by the one-shot `migrate` service;
#   - the API runtime account has DML rights only.
set -euo pipefail

: "${MYSQL_DATABASE:?MYSQL_DATABASE is required}"
: "${DB_MIGRATION_USER:?DB_MIGRATION_USER is required}"
: "${DB_MIGRATION_PASSWORD:?DB_MIGRATION_PASSWORD is required}"
: "${DB_APP_USER:?DB_APP_USER is required}"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD is required}"

# Identifiers are interpolated into SQL, so only accept a safe alphabet.
for identifier in "$MYSQL_DATABASE" "$DB_MIGRATION_USER" "$DB_APP_USER"; do
    if [[ ! "$identifier" =~ ^[A-Za-z0-9_]+$ ]]; then
        echo "mysql-init: refusing unsafe identifier '$identifier' (allowed: [A-Za-z0-9_])" >&2
        exit 1
    fi
done

# MySQL string literal: backslash is an escape character, single quotes double up.
sql_escape() {
    local value=${1//\\/\\\\}
    printf '%s' "${value//\'/\'\'}"
}

MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot <<SQL
CREATE USER IF NOT EXISTS '${DB_MIGRATION_USER}'@'%'
    IDENTIFIED BY '$(sql_escape "$DB_MIGRATION_PASSWORD")';
CREATE USER IF NOT EXISTS '${DB_APP_USER}'@'%'
    IDENTIFIED BY '$(sql_escape "$DB_APP_PASSWORD")';

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
    ON \`${MYSQL_DATABASE}\`.* TO '${DB_MIGRATION_USER}'@'%';

GRANT SELECT, INSERT, UPDATE, DELETE
    ON \`${MYSQL_DATABASE}\`.* TO '${DB_APP_USER}'@'%';

FLUSH PRIVILEGES;
SQL
