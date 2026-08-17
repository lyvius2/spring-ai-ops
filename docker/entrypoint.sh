#!/bin/sh
set -e

redis6-server \
    --daemonize yes \
    --bind 127.0.0.1 \
    --port 6379 \
    --save "" \
    --appendonly no \
    --dir /tmp

for _ in $(seq 1 20); do
    if redis6-cli -p 6379 ping 2>/dev/null | grep -q PONG; then
        break
    fi
    sleep 0.5
done

exec java ${JAVA_OPTS:-} -jar /app/app.jar