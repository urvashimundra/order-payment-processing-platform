#!/usr/bin/env bash
# One-command start for macOS / Linux / Git Bash / WSL.
# Starts Docker infra, builds common-lib, then every service in the right order.
# Logs: logs/<service>.log     Stop everything: ./stop-all.sh
cd "$(dirname "$0")" || exit 1
# Java in India reports the old zone name "Asia/Calcutta", which the Postgres Docker image rejects.
# Change this if you are in another time zone (e.g. UTC).
export JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Kolkata"
chmod +x */mvnw 2>/dev/null
mkdir -p logs

port_open() { (echo > /dev/tcp/localhost/"$1") >/dev/null 2>&1; }

wait_port() {  # name port
  echo -n "   waiting for $1 on :$2 "
  for _ in $(seq 1 150); do
    if port_open "$2"; then echo " up"; return 0; fi
    echo -n "."; sleep 2
  done
  echo " TIMEOUT - see logs/$1.log"; return 1
}

start_service() {  # name extra-maven-args
  echo ">> starting $1"
  ( cd "$1" && nohup ./mvnw spring-boot:run $2 > "../logs/$1.log" 2>&1 & )
}

echo ">> starting Docker infrastructure"
docker compose up -d || { echo "Docker failed. Is Docker Desktop running?"; exit 1; }

echo -n ">> waiting for Postgres databases "
until docker exec pay-postgres psql -h 127.0.0.1 -U postgres -tAc \
  "select 1 from pg_database where datname='pay-vault-db'" 2>/dev/null | grep -q 1; do
  echo -n "."; sleep 2
done
echo " ready"

echo ">> building common-lib (first run takes a few minutes)"
( cd common-lib && ./mvnw -q clean install -DskipTests ) || { echo "common-lib build failed (JDK 25 installed?)"; exit 1; }

start_service discovery-service;  wait_port discovery-service 8761
start_service config-service "-Dspring-boot.run.profiles=native"; wait_port config-service 8888

start_service merchant-service
start_service payment-service
start_service operations-service
start_service vault-service
wait_port merchant-service 9010
wait_port payment-service 9020
wait_port operations-service 9030
wait_port vault-service 9040

start_service api-gateway-service; wait_port api-gateway-service 8081

echo
echo "All services are up. API gateway: http://localhost:8081"
echo "Eureka: http://localhost:8761   Zipkin: http://localhost:9411"
echo "Verify:   powershell -File verify-all.ps1   (see README.md)"
