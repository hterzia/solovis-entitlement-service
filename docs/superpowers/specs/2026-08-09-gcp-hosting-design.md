# Design: Hosting the Entitlement Service on GCP

**Date:** 2026-08-09
**Status:** Approved for planning
**Target:** the finished v1 application — REST APIs, admin API, snapshot feed, operator SPA and seed data, deployed as one unit

---

## 1. What this is for

A **shareable demo environment**: a live HTTPS URL that can be sent to a reviewer or stakeholder, who clicks through the operator UI and exercises the APIs without installing anything.

That framing decides the rest of this document. It is not a production deployment, and several choices below would be wrong for one. Section 12 records what changes if that ever becomes the goal.

### Decisions taken during design

| Decision | Choice |
|---|---|
| Purpose | Shareable demo, disposable data |
| Access | Fully public, unauthenticated (see §8) |
| Scope | The finished application, not a walking skeleton |
| Platform | Cloud Run, single instance, always warm |
| Persistence | SQLite on the instance, replicated to GCS by Litestream |
| Region | `us-central1` (tier‑1 pricing) |
| Project | `entitlement-505004` (org `223887378499`, billing active) |

## 2. Non-goals

- **Meeting spec §7's throughput targets.** 5,000 decisions/sec never reaches this service. Under spec §11, decisions resolve inside SDK replicas embedded in consuming products; the management service only distributes the model. A single small instance is the correct size for a management plane, not a compromise made for cost.
- **High availability.** SQLite permits one writer host, so the service does not horizontally scale. This is a property of the architecture, not of the hosting.
- **Disaster recovery.** Litestream is here so a redeploy does not wipe the demo, not so data can be recovered after a catastrophe.
- **Authentication.** v1 has none by decision. §8 addresses the consequence.

---

## 3. Target architecture

One Cloud Run service, `entitlement-service`, running one container that holds the SPA, the backend and Litestream. This preserves the repository's existing "one deployable, no CORS story" commitment — the SPA is built into the jar's `static/` directory and served by the same process that serves the APIs.

```
Reviewer ──HTTPS──▶ entitlement-service.run.app
                     │
                     └─ Cloud Run (us-central1, min=1, max=1)
                         └─ container
                             ├─ litestream (supervisor)  ──▶ gs://…-litestream
                             └─ java -jar app.jar
                                 ├─ REST APIs + snapshot feed
                                 ├─ SPA static assets
                                 └─ SQLite @ /data/entitlement.db
```

### Service configuration

```
--region=us-central1
--min-instances=1 --max-instances=1
--cpu=1 --memory=1Gi
--port=8081
--allow-unauthenticated
--service-account=entitlement-run@entitlement-505004.iam.gserviceaccount.com
--set-env-vars=ENTITLEMENT_DB_PATH=/data/entitlement.db,LITESTREAM_BUCKET=entitlement-505004-litestream
```

Billing stays on the default request-based model, so CPU is throttled between requests and charged at the idle rate.

**`--max-instances=1` is the load-bearing flag.** It turns "SQLite permits one writer host" from a note in `CLAUDE.md` into an infrastructure guarantee. It must never be raised without first replacing SQLite.

**`--port=8081`** matches the existing `application.yaml` and avoids editing it. Cloud Run's injected `$PORT` is ignored. Changing `server.port` to `${PORT:8081}` would also work and is marginally more idiomatic; it is not worth a code change for a demo.

---

## 4. Persistence and durability

SQLite lives at `/data/entitlement.db` on the container's writable filesystem, which on Cloud Run is backed by memory. Litestream continuously replicates it to a GCS bucket and restores it on boot, so the database survives redeploys and instance recycling.

WAL mode is Litestream's hard prerequisite and is **already satisfied** — `EntitlementDatabaseProperties.java:22` builds the JDBC URL with `journal_mode=WAL`. No change needed.

### Litestream configuration

Litestream **v0.5.x** (current release v0.5.16). The v0.5 config format differs from the widely-circulated 0.3.x examples: the replica type is `gs`, and `replica:` is singular rather than a `replicas:` list.

```yaml
# /etc/litestream.yml
dbs:
  - path: /data/entitlement.db
    replica:
      type:   gs
      bucket: $LITESTREAM_BUCKET
      path:   entitlement
```

Credentials come from the instance metadata server. The Litestream GCS guide states this explicitly for Cloud Run, which matters here: the `iam.disableServiceAccountKeyCreation` org policy makes the key-file path impossible, so ADC is not merely preferred but required.

### Process model

Litestream is PID 1 and supervises the JVM, so it controls the shutdown sequence:

```sh
exec litestream replicate \
  -config /etc/litestream.yml \
  -restore-if-db-not-exists \
  -exec "java -XX:MaxRAMPercentage=55 -jar /app/app.jar"
```

`-restore-if-db-not-exists` handles first boot and every cold start in one flag, replacing the separate `litestream restore` step that 0.3.x required.

### The durability assumption, stated plainly

This design's durability rests on a claim the Litestream documentation **does not** make: that on SIGTERM, Litestream performs a final sync before exiting. The `-exec` reference documents only that "Litestream will exit when the child process exits" — nothing about signal forwarding or shutdown flushing.

Cloud Run sends SIGTERM with a 10-second CPU-allocated grace period before stopping an instance. If Litestream flushes in that window, redeploys and scale-down are non-destructive. If it does not, every redeploy silently loses recent writes.

**This must be verified by experiment, not assumed** — see §10. If the assumption fails, the fallbacks in order of preference are: reduce Litestream's sync interval and accept a small loss window; switch to `--no-cpu-throttling` so background replication runs continuously (~$47/month, §9); or abandon Cloud Run for a GCE VM with a persistent disk.

### Seed data

Seed data will exist and populate the demo with accounts, plans and capabilities. Because seeding runs on every boot and a restored database arrives already populated, **the `seed/` package must skip when the database is not empty.** Without that guard, every restart overwrites whatever the reviewer did with pristine demo data, defeating the point of Litestream entirely.

This is a requirement on a package that is not yet written, and belongs in its plan as well as this one.

### Accepted weakness: redeploy overlap

With `max-instances=1`, Cloud Run still briefly runs the old and new revisions together during a deploy while traffic migrates. For that window there are two Litestream writers against one replica path. Litestream resolves the conflict by starting a new generation, which can discard writes made in the seconds around the switchover.

Accepted: deploys are intentional and infrequent, and the data is demo data. For anything real, this is a reason to move off SQLite rather than to work around it.

---

## 5. Container image

A three-stage build, at the repository root because it needs both the frontend and backend trees.

| Stage | Base | Work |
|---|---|---|
| `ui` | `node:22-alpine` | `npm ci && npm run build` in `management/frontend/management-ui` → `dist/` |
| `build` | `maven:3.9-eclipse-temurin-21` | copy `dist/` into `entitlement-service/src/main/resources/static/`, then `./mvnw -pl entitlement-service -am package -DskipTests` |
| runtime | `eclipse-temurin:21-jre` | jar, Litestream binary (`COPY --from=litestream/litestream:0.5.16`), config, entrypoint |

The **`-am` is mandatory** in the Maven stage. Without it the build fails with "Could not find artifact entitlement-core", as `CLAUDE.md` already records.

Tests are skipped in the image build. They run in the reactor, not as a side effect of packaging; a deploy of untested code is a CI concern, and this is a demo deployed deliberately.

### Memory budget

1 GiB total, shared between the JVM heap (`MaxRAMPercentage=55` ≈ 560 MB), JVM non-heap (~200 MB), Litestream (~20 MB), and the SQLite file plus WAL — which, on Cloud Run's memory-backed filesystem, **consumes instance memory**. Demo-sized data leaves comfortable headroom.

The consequence worth knowing: database size is bounded by the memory limit. If the instance OOMs, raise memory to 2 GiB at roughly +$6.57/month rather than tuning the heap down further.

---

## 6. GCP resources and prerequisites

### APIs to enable

`run.googleapis.com`, `artifactregistry.googleapis.com`, `cloudbuild.googleapis.com`. Storage, logging and monitoring are already enabled on the project.

### Resources to create

| Resource | Value |
|---|---|
| Artifact Registry repo | `us-central1-docker.pkg.dev/entitlement-505004/entitlement` (Docker format) |
| GCS bucket | `gs://entitlement-505004-litestream`, `us-central1`, uniform bucket-level access |
| Bucket lifecycle | **none** — see the warning below |
| Runtime service account | `entitlement-run@entitlement-505004.iam.gserviceaccount.com` |

> **Do not add a GCS lifecycle rule to the Litestream bucket.** An age-based deletion rule looks like tidy housekeeping and is a silent way to destroy recoverability: Litestream's replica is a base snapshot plus the WAL segments layered on it, and a rule that deletes "objects older than N days" will happily delete a base snapshot that current segments still depend on. The restore then fails at exactly the moment it is needed. Retention belongs to Litestream's own `retention` setting, which understands which objects are still reachable. Storage for a demo-sized database costs cents.

### Org policies that constrain this design

The project sits under organization `223887378499`, whose policies are not the permissive defaults most GCP guides assume. Each of these was checked against the live project:

| Policy | State | Consequence |
|---|---|---|
| `iam.allowedPolicyMemberDomains` | `allValues: ALLOW` | `allUsers` is permitted, so `--allow-unauthenticated` will work. This was not a given and the design depends on it. |
| `iam.disableServiceAccountKeyCreation` | SET | No service-account JSON keys anywhere. Litestream must use metadata-server credentials. |
| `iam.automaticIamGrantsForDefaultServiceAccounts` | SET | Default service accounts receive **no** automatic Editor role. Every grant below must be made explicitly. |
| `storage.uniformBucketLevelAccess` | SET | The bucket cannot use ACLs. Uniform access is mandatory, not a preference. |

### IAM grants — all explicit

Because automatic grants are disabled, a plan that assumes the default Editor role will fail partway through.

| Principal | Role | Scope |
|---|---|---|
| `entitlement-run@…` | `roles/storage.objectAdmin` | the Litestream bucket **only**, not the project |
| Cloud Build service account | `roles/artifactregistry.writer` | project |
| Cloud Build service account | `roles/run.developer` | project |
| Cloud Build service account | `roles/iam.serviceAccountUser` | on `entitlement-run@…` |

Scoping the runtime account's storage role to the single bucket keeps a fully public, unauthenticated service from carrying project-wide storage rights.

### Conditional: Gemini-backed features

The repository's git-ignored `.env` holds `GOOGLE_AI_GEMINI_API_KEY`, for the natural-language checker specified in `.specs/003-natural-language-procesing/`. That feature is not part of v1. **If any Gemini-backed feature ships into this deployment**, the key belongs in Secret Manager and mounted as a secret — never `--set-env-vars`, which writes it into service metadata readable by anyone with viewer access. That adds `secretmanager.googleapis.com` and `roles/secretmanager.secretAccessor` for the runtime account.

---

## 7. Repository changes required

Infrastructure alone is not sufficient; four changes are needed in the repo.

**`Dockerfile` and `.gcloudignore` at the repository root.** The `.gcloudignore` matters more than it appears: `refs/` is a vendored LangChain4j reference checkout, and `.claude/worktrees/` contains a **complete second copy of the repository**. Both must be excluded, along with `target/` and `node_modules/`, or every build uploads hundreds of megabytes of irrelevant source.

**Graceful shutdown.** Add `server.shutdown: graceful` and `spring.lifecycle.timeout-per-shutdown-phase: 8s` — inside Cloud Run's 10-second SIGTERM window — so in-flight requests finish and SQLite closes cleanly before Litestream's final sync. Without this, §4's durability story does not hold even if Litestream behaves as hoped.

**`seed/` must skip a populated database.** Covered in §4.

**SPA history-mode fallback.** Client-side routes need unknown non-API paths forwarded to `index.html`, or a deep link into the operator UI returns 404 when opened directly — the exact thing that happens when a reviewer is sent a link to a specific screen. This is not currently in `frontend-plan.md` and should be added there.

---

## 8. Access control, and a deviation on record

**The service will be public and unauthenticated.** Anyone with the URL can view and mutate every plan, account and override, including lifting compliance HOLDs.

This is a deliberate deviation from a decision already recorded in `DECISIONS.md` and `CLAUDE.md`:

> "with sign-in deferred, anyone who can reach the service can add or lift any hold, compliance holds included. Accepted while this is a single-operator MVP **on a trusted network**."

Publishing to `*.run.app` removes the trusted network, which was the entire basis on which that risk was accepted. The concern was raised during design and the deviation was chosen knowingly, for demo reach. It is recorded rather than absorbed silently, and carries three conditions:

1. **Synthetic data only.** No real customer, account or contract data reaches this deployment, ever.
2. **`robots.txt` with `noindex`**, so the demo is not crawled and indexed.
3. **A named teardown trigger.** The service is deleted when the assessment concludes or when sign-in lands — whichever comes first. A demo that outlives its purpose is how this becomes a real exposure.

Identity-Aware Proxy remains available later at modest effort and no application code, and is the obvious first move if this URL ever needs to live longer than the demo.

---

## 9. Observability and cost

Cloud Run ships container stdout and stderr to Cloud Logging with no configuration. A **startup probe on `/actuator/health`** — already exposed by `application.yaml` — with a generous failure threshold covers JVM startup plus the Litestream restore, which on a cold start runs before the JVM begins.

### Cost

At `min-instances=1` with CPU throttled, the instance is billed at the idle rate for essentially the whole month (2,628,000 s), with demo traffic adding a negligible amount of active time.

| Line | Calculation | Monthly |
|---|---|---|
| Idle CPU | 2,628,000 s × $0.0000025 | $6.57 |
| Idle memory (1 GiB) | 2,628,000 s × $0.0000025 | $6.57 |
| Free tier | 180,000 vCPU-s + 360,000 GiB-s | −$5.22 |
| GCS + Artifact Registry | small objects, one image | ~$0.20 |
| **Total** | | **~$8–13** |

Rates are us-central1 tier‑1 list prices for request-based billing, read from the Cloud Run pricing page on 2026-08-09.

For comparison, `--no-cpu-throttling` bills CPU at the always-allocated rate of $0.000018/vCPU-s — about **$47/month**, more than three `e2-small` VMs. That option is held in reserve for the §4 fallback and is not the default.

---

## 10. Verification

The deployment is not done until these pass. The third is the one that matters most, because it tests the assumption the whole persistence design rests on.

1. **The service answers.** `/actuator/health` returns `UP` over HTTPS at the `*.run.app` URL.
2. **The SPA loads and deep-links.** The operator UI renders, and a direct link to an inner route loads instead of 404ing.
3. **Data survives a redeploy.** Save a change through the UI, redeploy the service, and confirm the change is still there. **If this fails, §4's fallbacks apply** — this is the experiment that decides whether B-warm is viable at all.
4. **A cold restore works.** Delete the service, redeploy from scratch, and confirm the data returns from GCS rather than being re-seeded.
5. **Seed does not clobber.** Confirm a restart against a populated database leaves reviewer-made changes intact.
6. **The single-writer guarantee holds.** Confirm the deployed service reports `max-instances=1`.

---

## 11. Known risks

| Risk | Severity | Disposition |
|---|---|---|
| Litestream may not flush on SIGTERM | High | Undocumented; verified by test 3. Fallbacks in §4. |
| Redeploy overlap loses writes near switchover | Medium | Accepted; demo data, deliberate deploys. |
| Public URL with no authentication | Medium | Accepted knowingly (§8), with three binding conditions. |
| Ungraceful kill (OOM/crash) loses the last writes | Low | Accepted; the CPU-throttled model cannot prevent it. |
| SQLite file consumes instance memory | Low | Demo-sized data; raise to 2 GiB if it OOMs. |
| Litestream v0.5 is a recent rewrite | Low | Pin the exact image tag; do not track `latest`. |

## 12. If this ever needs to be real

Recorded so the demo's shortcuts are not mistaken for architecture:

- **Authentication first.** Nothing else matters until sign-in lands. IAP is the cheapest interim step.
- **Replace SQLite with Cloud SQL for PostgreSQL.** This removes `max-instances=1`, the redeploy-overlap window, Litestream, and the memory-bound database in one move, and brings managed backups. The store layer is hand-written SQL through `JdbcClient`, so the change is contained — though `sql-error-codes.xml` and the SQLite-specific triggers enforcing the immutability invariants would need revisiting.
- **Then, and only then, horizontal scaling becomes meaningful** — each instance holding its own snapshot, polling for version changes within the 60-second visibility budget of spec §7.
