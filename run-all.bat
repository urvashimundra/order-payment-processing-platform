@echo off
REM One-command start for Windows. Opens one window per service.
REM Stop: close the service windows, then run "docker compose down".
setlocal
cd /d "%~dp0"

REM Windows/Java in India report the old time-zone name "Asia/Calcutta", which the Postgres
REM Docker image rejects ("invalid value for parameter TimeZone"). Use the current name.
REM Change this line if you are in another time zone (e.g. UTC).
set "JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Kolkata"

echo ^>^> starting Docker infrastructure
docker compose up -d
if errorlevel 1 (
  echo Docker failed. Is Docker Desktop running?
  pause
  exit /b 1
)

echo ^>^> waiting for Postgres databases
:waitpg
docker exec pay-postgres psql -h 127.0.0.1 -U postgres -tAc "select 1 from pg_database where datname='pay-vault-db'" 2>nul | findstr 1 >nul
if errorlevel 1 (
  timeout /t 2 >nul
  goto waitpg
)

echo ^>^> building common-lib (first run takes a few minutes)
pushd common-lib
call mvnw.cmd -q clean install -DskipTests
if errorlevel 1 (
  echo common-lib build failed. Is JDK 25 installed?
  popd
  pause
  exit /b 1
)
popd

start "discovery-service" /d discovery-service cmd /k mvnw.cmd spring-boot:run
call :waitport 8761
start "config-service" /d config-service cmd /k mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=native
call :waitport 8888

start "merchant-service" /d merchant-service cmd /k mvnw.cmd spring-boot:run
start "payment-service" /d payment-service cmd /k mvnw.cmd spring-boot:run
start "operations-service" /d operations-service cmd /k mvnw.cmd spring-boot:run
start "vault-service" /d vault-service cmd /k mvnw.cmd spring-boot:run
call :waitport 9010
call :waitport 9020
call :waitport 9030
call :waitport 9040

start "api-gateway-service" /d api-gateway-service cmd /k mvnw.cmd spring-boot:run
call :waitport 8081

echo.
echo All services are up. API gateway: http://localhost:8081
echo Eureka: http://localhost:8761   Zipkin: http://localhost:9411
pause
goto :eof

:waitport
echo    waiting for port %1 ...
powershell -NoProfile -Command "while(-not (Test-NetConnection localhost -Port %1 -InformationLevel Quiet -WarningAction SilentlyContinue)){Start-Sleep 3}"
goto :eof
