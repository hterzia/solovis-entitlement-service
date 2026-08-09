#!/bin/sh
#
# Litestream runs as PID 1 and supervises the JVM, which is what lets it control
# the shutdown sequence: Cloud Run sends SIGTERM with a 10-second CPU-allocated
# grace period, and the replica must be flushed inside that window or a redeploy
# silently loses recent writes.
#
# -restore-if-db-not-exists pulls the database back from GCS on a cold start and
# is a no-op when the file is already present.
set -eu

: "${LITESTREAM_BUCKET:?LITESTREAM_BUCKET must be set}"
: "${ENTITLEMENT_DB_PATH:=/data/entitlement.db}"

# MaxRAMPercentage leaves headroom for JVM non-heap, Litestream, and the SQLite
# file plus WAL — which live on a memory-backed filesystem and therefore count
# against the container's memory limit.
exec litestream replicate \
  -config /etc/litestream.yml \
  -restore-if-db-not-exists \
  -exec "java -XX:MaxRAMPercentage=55 -jar /app/app.jar"
