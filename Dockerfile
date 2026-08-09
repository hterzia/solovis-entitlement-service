# syntax=docker/dockerfile:1
#
# One deployable: the operator SPA is built into the jar's static/ directory and
# served by the same process that serves the APIs — no CORS story, one artifact.
# Litestream supervises the JVM so it owns the shutdown sequence and can flush
# the SQLite replica to GCS before the container stops.

# ---------------------------------------------------------------------------
# Stage 1 — build the operator SPA
# ---------------------------------------------------------------------------
FROM node:22-alpine AS ui
WORKDIR /ui

# Copy manifests first so `npm ci` is cached independently of source changes.
COPY management/frontend/management-ui/package.json management/frontend/management-ui/package-lock.json ./
RUN npm ci

COPY management/frontend/management-ui/ ./
RUN npm run build

# ---------------------------------------------------------------------------
# Stage 2 — build the Spring Boot jar with the SPA inside it
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

COPY management/backend/ ./
RUN chmod +x mvnw

# The SPA lands in static/ before packaging, so it ships inside the jar.
COPY --from=ui /ui/dist/ ./entitlement-service/src/main/resources/static/

# -am is mandatory: entitlement-service depends on entitlement-core, and without
# it the build fails with "Could not find artifact entitlement-core".
# Tests run as their own CI job, not as a side effect of packaging.
RUN ./mvnw -B -pl entitlement-service -am package -DskipTests

# ---------------------------------------------------------------------------
# Stage 3 — runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

COPY --from=litestream/litestream:0.5.16 /usr/local/bin/litestream /usr/local/bin/litestream
COPY --from=build /src/entitlement-service/target/entitlement-service-*.jar /app/app.jar
COPY deploy/litestream.yml /etc/litestream.yml
COPY deploy/entrypoint.sh /entrypoint.sh

RUN chmod +x /entrypoint.sh && mkdir -p /data

# On Cloud Run this filesystem is memory-backed, so the database counts against
# the instance memory limit. MaxRAMPercentage is set accordingly in entrypoint.sh.
ENV ENTITLEMENT_DB_PATH=/data/entitlement.db

EXPOSE 8081
ENTRYPOINT ["/entrypoint.sh"]
