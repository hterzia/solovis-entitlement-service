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

- **Meeting a throughput target.** There is no longer one to meet: spec §7's decisions-per-second and latency rubric was withdrawn on 2026-08-10 with the client base settled at 300. Even when it existed it never reached this service — under spec §11, decisions resolve inside SDK replicas embedded in consuming products, and the management service only distributes the model. A single small instance is the correct size for a management plane, not a compromise made for cost.
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

### The durability assumption — tested 2026-08-10, and it is false

This design originally rested on a claim the Litestream `-exec` reference does not make: that on SIGTERM, Litestream performs a final sync before exiting. That claim was marked as needing an experiment rather than assumption. **The experiment has now been run, and the assumption does not hold.**

Method: a container replicating to a *file* replica, so no GCS or network timing is involved (flush-on-signal semantics are replica-agnostic). Write a capability through the API, stop the container, then restore into an empty data directory from the replica alone and check whether the row came back.

| Case | Result |
|---|---|
| Write, then SIGKILL immediately | **LOST** |
| Write, then SIGTERM (10s grace) | **LOST** |
| Write, then SIGTERM (30s grace) | **LOST** |
| Write, then SIGTERM with `shutdown-sync-timeout`/`shutdown-sync-interval` set | **LOST** |
| *Control:* write, wait 15s, then SIGTERM | **SURVIVED** |

The control is what makes the negative result trustworthy — the harness can restore, and the only difference is time for the periodic sync. In the surviving case the replica held one more segment file than in the losing ones.

Note that the Litestream documentation states the opposite: *"When Litestream receives a shutdown signal, it attempts a final sync of each database to its replica before exiting."* Either that does not apply under `-exec` supervision, or a clean SQLite close checkpoints and truncates the WAL out from under the sync. The mechanism was not isolated; the observable was reproduced four ways and is what this design has to live with.

**What actually protects data, therefore, is the periodic `sync-interval` during normal running — nothing else.** Two consequences follow:

- **Redeploys are safe, and this was confirmed against the live service.** A marker capability was written, a redeploy dispatched, and the marker was still present on the new revision. That works because a deploy takes minutes, during which the write replicates — not because anything flushes at shutdown.
- **An abrupt restart loses writes since the last sync.** With request-based billing the CPU is throttled between requests, so the 1-second timer may not fire promptly after a response; the practical window is "until the next request or the next CPU-allocated moment."

`sync-interval: 1s` is now stated explicitly in `deploy/litestream.yml` — not as tuning, but because it is the sole mechanism, and a future reader changing it should know what they are changing. If losing the final write before an unplanned restart ever becomes unacceptable, the fix is `--no-cpu-throttling` so replication runs continuously (~$47/month, §9), or a GCE VM with a persistent disk.

### Seed data

Seed data will exist and populate the demo with accounts, plans and capabilities. Because seeding runs on every boot and a restored database arrives already populated, **the `seed/` package must skip when the database is not empty.** Without that guard, every restart overwrites whatever the reviewer did with pristine demo data, defeating the point of Litestream entirely.

**Audit history is seed data too.** This is a full demo, and spec §8's change history is one of the things being demonstrated — an empty history screen shows a reviewer nothing, and the filters by account, by plan and by actor cannot be exercised against no rows. Seeding it imposes four requirements:

- **Enough variety to exercise the filters.** Several actors, several accounts, more than one plan, and a mix of event kinds: plan edits, plan assignments, override creations and removals, capability changes. Enough rows to make pagination visible rather than theoretical.
- **Timestamps backdated relative to the injected `Clock`, never hard-coded dates.** A demo seeded with fixed 2026 dates looks abandoned six months later. Deriving each event from `clock.instant()` minus an offset means the history always reads as recent, whenever the container happens to boot. This also keeps the rule in `CLAUDE.md` — timestamps computed in Java and passed in, never `datetime('now')` in SQL.
- **The history must agree with current state.** The seeded trail should tell the story that ends at the state the demo actually starts in. An audit record saying an override was removed, next to that override sitting live in the UI, is the kind of contradiction a sharp reviewer finds in the first five minutes.
- **Reasons on every override event.** They are mandatory per spec §8, and they are what makes the explanation screens worth looking at.

Seeding audit rows is compatible with the append-only enforcement: the schema triggers block `UPDATE` and `DELETE`, not `INSERT`.

These are requirements on a package that is not yet written, and belong in its plan as well as this one.

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

**`seed/` must skip a populated database, and must seed audit history.** Covered in §4.

**SPA client-side routing fallback.** Unknown non-API paths need forwarding to `index.html`, or a deep link into the operator UI returns 404 when opened directly — the exact thing that happens when a reviewer is sent a link to a specific screen. (This is the router's history mode, unrelated to the audit history above.) Not currently in `frontend-plan.md` and should be added there.

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
3. **Data survives a redeploy.** ✅ Confirmed 2026-08-10 against the live service: a marker capability was written, a redeploy dispatched, and the marker was present on the new revision. Note *why* it survives — the deploy takes minutes and the periodic sync replicates the write — and not because of any shutdown flush (§4).
4. **A cold restore works.** Delete the service, redeploy from scratch, and confirm the data returns from GCS rather than being re-seeded.
5. **Seed does not clobber.** Confirm a restart against a populated database leaves reviewer-made changes intact.
6. **History is populated and filterable.** The audit screen shows seeded events on first load, the account, plan and actor filters each return results, and the timestamps read as recent rather than fixed to the date the seed was written.
7. **The single-writer guarantee holds.** Confirm the deployed service reports `max-instances=1`.

---

## 11. Known risks

| Risk | Severity | Disposition |
|---|---|---|
| Litestream does **not** flush on SIGTERM | Medium | **Confirmed false by experiment, 2026-08-10** (§4). Durability rests entirely on `sync-interval: 1s`. Redeploys are safe; an abrupt restart loses writes since the last sync. Accepted for a demo. |
| Redeploy overlap loses writes near switchover | Medium | Accepted; demo data, deliberate deploys. |
| Public URL with no authentication | Medium | Accepted knowingly (§8), with three binding conditions. |
| Ungraceful kill (OOM/crash) loses the last writes | Low | Accepted; the CPU-throttled model cannot prevent it. |
| SQLite file consumes instance memory | Low | Demo-sized data; raise to 2 GiB if it OOMs. |
| Litestream v0.5 is a recent rewrite | Low | Pin the exact image tag; do not track `latest`. |

## 12. Deployment pipeline (as built)

Deployment is automated: push to `main` runs the reactor tests, builds the image, and deploys. No manual `gcloud` step exists.

**Source:** `github.com/hterzia/solovis-entitlement-service`, public, so reviewers can read the code alongside the running demo.

### Authentication: Workload Identity Federation, not a key

`iam.disableServiceAccountKeyCreation` blocks the path every GitHub-Actions-to-GCP tutorial takes — create a service account, download the JSON key, paste it into a repo secret. That key cannot be created, so federation is the only route. It is also the better one: GitHub mints a short-lived OIDC token that GCP exchanges for temporary credentials, and no long-lived secret exists to leak from a public repository.

| Component | Value |
|---|---|
| Pool | `github` (global) |
| Provider | `projects/773463992355/locations/global/workloadIdentityPools/github/providers/github` |
| Attribute condition | `assertion.repository_owner == 'hterzia'` |
| Impersonation binding | `principalSet://…/attribute.repository/hterzia/solovis-entitlement-service` |

Two independent constraints: the provider only accepts tokens from that owner, and only that one repository may impersonate the deploy account.

**The workflow triggers on `push` to `main` and `workflow_dispatch` only — never `pull_request`.** On a public repository this matters more than it looks: a `pull_request` trigger with `id-token: write` would let anyone open a fork PR whose workflow mints GCP credentials.

### Service accounts

| Account | Holds | Scope |
|---|---|---|
| `entitlement-run@` | `roles/storage.objectAdmin` | the Litestream bucket **only** |
| `entitlement-deploy@` | `roles/artifactregistry.writer` | the `entitlement` repository **only** |
| `entitlement-deploy@` | `roles/run.admin` | project |
| `entitlement-deploy@` | `roles/iam.serviceAccountUser` | on `entitlement-run@` only |

**`run.admin` is wider than this needs and is a deliberate trade-off.** `run.developer` cannot set an IAM policy, and `--allow-unauthenticated` *is* a `setIamPolicy` call, so a narrower role would make the pipeline fail on first deploy and require a manual grant afterwards. The project holds nothing but this service, and the account is reachable only from one repository through federation. If authentication ever lands and the service stops being public, this should drop back to `run.developer`.

### Verified locally before first push

The image builds through all three stages; the container boots in **~4 seconds** with health `UP`; and `GET /` serves the SPA from inside the jar with hashed assets — confirming the one-deployable, no-CORS arrangement works end to end. The 4-second figure is a local measurement and will be somewhat slower on Cloud Run, but it is far better than the 8–15 seconds assumed when weighing cold starts in §3.

### Secrets

The Gemini key for the natural-language checker is held in Secret Manager as `gemini-api-key` and mounted with `--set-secrets`, so the revision stores a reference and resolves the value at container start.

**Not `--set-env-vars`.** Cloud Run environment variables are configuration, not secrets: they are stored as plaintext in revision metadata, readable by anyone holding `run.viewer`, and they persist in every revision ever deployed. Routing a GitHub Actions secret into `--set-env-vars` fails the same way — masked in CI logs, plaintext in GCP.

`roles/secretmanager.secretAccessor` is held by `entitlement-run@` alone. The deploy account that mounts the secret cannot read it, so compromising the pipeline does not disclose the key.

**Secret Manager protects the key, not the spending.** The service is public and unauthenticated, so once the ask feature reaches `main`, an endpoint that calls a paid model per request is exposed to the internet — a routinely scanned-for target. Confidentiality of the credential and control of its use are different problems, and only the first is solved here. Before that feature merges: a project budget alert, and quota caps on the key in Google AI Studio. Neither requires code. Per-route rate limiting or gating that one route behind IAP are the next steps if the demo runs long.

### Known gap

The public repository carries **committed history only**, by explicit decision. `README.md`, `DECISIONS.md` and the prompt transcripts have since been committed and pushed, but `spec.md`, `CLAUDE.md`, specs 002 and 003 and `frontend-plan.md` remain uncommitted and therefore absent, while the superseded `init-spec.md` and `homepage.html` are still present in the pushed history.

So a reviewer cloning today finds a README describing the work, and next to it the *wrong* specification — `init-spec.md` where `spec.md` should be. Recorded so this is not later mistaken for the repository's intended state.

---

## 13. If this ever needs to be real

Recorded so the demo's shortcuts are not mistaken for architecture:

- **Authentication first.** Nothing else matters until sign-in lands. IAP is the cheapest interim step.
- **Replace SQLite with Cloud SQL for PostgreSQL.** This removes `max-instances=1`, the redeploy-overlap window, Litestream, and the memory-bound database in one move, and brings managed backups. The store layer is hand-written SQL through `JdbcClient`, so the change is contained — though `sql-error-codes.xml` and the SQLite-specific triggers enforcing the immutability invariants would need revisiting.
- **Then, and only then, horizontal scaling becomes meaningful** — each instance holding its own snapshot, polling for version changes within the 60-second visibility budget of spec §7.
