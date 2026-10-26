#!/bin/sh
set -e

if [ -z "${CRYPTO_SECRET_KEY:-}" ]; then
    echo "ERROR: CRYPTO_SECRET_KEY is required." >&2
    exit 1
fi

exec java ${JAVA_OPTS:-} -jar /app/app.jar
