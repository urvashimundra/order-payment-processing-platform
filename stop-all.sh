#!/usr/bin/env bash
# Stops all services (by port) and the Docker containers (data is kept).
cd "$(dirname "$0")" || exit 1
for p in 8081 9010 9020 9030 9040 8888 8761; do
  pid=$(lsof -ti tcp:$p 2>/dev/null)
  [ -n "$pid" ] && { echo "stopping port $p (pid $pid)"; kill $pid; }
done
docker compose down
