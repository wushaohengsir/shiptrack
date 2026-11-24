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

# Two separate accounts are what keeps DDL away from the API at runtime. With a
# shared user the second CREATE USER IF NOT EXISTS is a no-op and the DML grant
# leaves the DDL privileges in place, so refuse before touching the database.
if [[ "$DB_MIGRATION_USER" == "$DB_APP_USER" ]]; then
    echo "mysql-init: DB_MIGRATION_USER and DB_APP_USER must be different accounts" >&2
    exit 1
fi
if [[ "$DB_MIGRATION_PASSWORD" == "$DB_APP_PASSWORD" ]]; then
    echo "mysql-init: DB_MIGRATION_PASSWORD and DB_APP_PASSWORD must be different" >&2
    exit 1
fi

# Identifiers are interpolated into SQL, so only accept a safe alphabet.
for identifier in "$MYSQL_DATABASE" "$DB_MIGRATION_USER" "$DB_APP_USER"; do
    if [[ ! "$identifier" =~ ^[A-Za-z0-9_]+$ ]]; then
        echo "mysql-init: refusing unsafe identifier '$identifier' (allowed: [A-Za-z0-9_])" >&2
        exit 1
    fi
done

# In a database-level GRANT, `_` remains a wildcard even inside backticks, so
# `ship_track` would also match `shipXtrack`. Escape it to keep the grant on
# this one schema. (`%` cannot appear: the identifier check above rejects it.)
GRANT_DB=${MYSQL_DATABASE//_/\\_}

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
    ON \`${GRANT_DB}\`.* TO '${DB_MIGRATION_USER}'@'%';

GRANT SELECT, INSERT, UPDATE, DELETE
    ON \`${GRANT_DB}\`.* TO '${DB_APP_USER}'@'%';

FLUSH PRIVILEGES;
SQL
