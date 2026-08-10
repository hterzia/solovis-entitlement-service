# syntax=docker/dockerfile:1
#
# One deployable: the operator SPA is embedded in the jar's static/ directory and served
# by the same process that serves the APIs — no CORS story, one artifact.
#
# The SPA build is NOT duplicated here. entitlement-service's pom drives it through
# frontend-maven-plugin (commit e8dd8ad), which installs its own pinned Node and runs
# `npm ci && npm run build` against management/frontend/management-ui. A separate node
# stage in this file would be a second implementation of the same step, free to drift
# from the one CI and local builds use — and it did: it silently stopped matching once
# the pom took over, and the image build failed outright.
#
# Consequence: the whole `management/` tree is the build context, not just `backend/`,
# because the plugin resolves the front end at ${project.basedir}/../../frontend.

# ---------------------------------------------------------------------------
# Stage 1 — build the jar (Maven builds the SPA into it)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

COPY management/ ./
WORKDIR /src/backend
RUN chmod +x mvnw

# -am is mandatory: entitlement-service depends on entitlement-core, and without it the
# build fails with "Could not find artifact entitlement-core".
# Tests run as their own CI job, not as a side effect of packaging.
RUN ./mvnw -B -pl entitlement-service -am package -DskipTests

# ---------------------------------------------------------------------------
# Stage 2 — runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

COPY --from=litestream/litestream:0.5.16 /usr/local/bin/litestream /usr/local/bin/litestream
COPY --from=build /src/backend/entitlement-service/target/entitlement-service-*.jar /app/app.jar
COPY deploy/litestream.yml /etc/litestream.yml
COPY deploy/entrypoint.sh /entrypoint.sh

RUN chmod +x /entrypoint.sh && mkdir -p /data

# On Cloud Run this filesystem is memory-backed, so the database counts against the
# instance memory limit. MaxRAMPercentage is set accordingly in entrypoint.sh.
ENV ENTITLEMENT_DB_PATH=/data/entitlement.db

EXPOSE 8081
ENTRYPOINT ["/entrypoint.sh"]
