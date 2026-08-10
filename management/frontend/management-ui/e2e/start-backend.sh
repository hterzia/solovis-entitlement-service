#!/usr/bin/env bash
#
# Starts a throwaway entitlement-service for the end-to-end run.
#
# Two steps, not one. `spring-boot:run` invoked as a bare goal with `-am` runs the goal against
# *every* module the reactor pulls in, including the parent POM, which fails with "Unable to find a
# suitable main class". So dependencies are installed first, and the run goal is then aimed at the
# one module that has a main class.
#
# Installing only core and client (never the service) also keeps the frontend-maven-plugin out of
# this: it is bound to the service's prepare-package phase, and building the SPA here would be
# circular — Playwright is already serving it from Vite.
set -euo pipefail

cd "$(dirname "$0")/../../../backend"

PORT="${E2E_API_PORT:-8099}"
# Absolute: spring-boot:run executes with the *module* directory as its working directory, not the
# reactor root, so a relative path here resolves somewhere nobody intended.
DB_PATH="${E2E_DB_PATH:-$(pwd)/entitlement-service/target/e2e-entitlement.db}"
mkdir -p "$(dirname "${DB_PATH}")"

rm -f "${DB_PATH}" "${DB_PATH}-wal" "${DB_PATH}-shm"

./mvnw -q install -DskipTests -pl entitlement-core,entitlement-client

exec ./mvnw -q spring-boot:run -pl entitlement-service \
  -Dspring-boot.run.jvmArguments="-Dserver.port=${PORT} -Dentitlement.database.path=${DB_PATH} -Dentitlement.seed.enabled=true"
