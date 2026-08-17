#!/bin/sh
set -e

if [ -z "${CRYPTO_SECRET_KEY:-}" ]; then
    echo "ERROR: CRYPTO_SECRET_KEY is required." >&2
    exit 1
fi

redis6-server \
    --daemonize yes \
    --bind 127.0.0.1 \
    --port 6379 \
    --save "" \
    --appendonly no \
    --dir /tmp

RETRIES=20
COUNT=0
until redis6-cli -p 6379 ping 2>/dev/null | grep -q PONG; do
    COUNT=$((COUNT + 1))
    if [ "$COUNT" -ge "$RETRIES" ]; then
        echo "ERROR: Redis failed to start within $((RETRIES / 2)) seconds." >&2
        exit 1
    fi
    sleep 0.5
done
echo "Redis is ready."

exec java ${JAVA_OPTS:-} -jar /app/app.jar
