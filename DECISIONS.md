# Decisions

## 1. The shape of the system

I fixed the outage posture as a business rule before choosing any mechanism: **an outage must neither
take away what a customer had nor grant what they lacked.** Products carry on with the last answer
they saw — never failing open, never failing closed. That, plus decisions that stay correct *while
plans and overrides are changing*, rules out "every product calls the service on every check".

So: **the resolution rule is a small pure library, and the model it resolves against is replicated
into each consuming product.** A decision is a local map lookup, and a replica that loses contact
keeps answering from what it last saw — which *is* the posture, rather than a document asking five
teams to implement it consistently. That forces the module split:

| Module | What it is | Why the boundary is there |
|---|---|---|
| `entitlement-core` | Pure Java 21. No Spring, no I/O, no dependencies. Model, total order, resolver, trace. | A decision must be answerable inside a product while the service is down. That is only honest if it is *literally the same code* in both places. |
| `entitlement-service` | Spring Boot 4. SQLite, mutation, audit, three REST surfaces, hosts the SPA. | Everything needing a database or a network sits on one side of the line. |
| `entitlement-client` | The SDK products embed. JDK `HttpClient` + Jackson only, deliberately no Spring. | A library that drags a framework into five products is a library nobody adopts. |

| Rejected | Why |
|---|---|
| One materialised entitlement document per account in a KV store | Closest contender — resolution happens once, centrally, and drift becomes impossible. But the outage posture then depends on a second piece of infrastructure staying up, and a plan edit re-sends every account's document. |
| Every product calls the service per decision | Puts this in every product's request path, and SQLite's single writer leaves nothing to fail over to. |
| Replicate the SQLite file (Litestream / WAL shipping) | Callers need the domain model, not the database file — smaller, simpler, language-agnostic. |
| Per-key cache with a 10 s TTL | Meets the freshness bound, not the outage posture: a key never seen before has no last answer to fall back on. |

**The trade I accepted:** the rule now runs inside every consumer — the scattered-logic problem this
service exists to delete, reappearing one layer down. It is guarded structurally rather than by
discipline: the replication feed carries a `resolverContract` version and a set of conformance vectors
that every replica evaluates against its own engine at startup, **refusing to serve on any mismatch**.
With no traces in replicas, a disagreement cannot be diagnosed after the fact, so detection has to
happen before the first wrong answer rather than after.

## 2. Products get answers; only the operator UI gets explanations

`resolve()` returns `(allowed, value)` and allocates nothing; `explain()` runs the *identical*
arithmetic and layers a trace on top, so the two cannot disagree — and a test asserts they agree
wherever a value is asserted. The feed carries only what `resolve()` needs: overrides travel as
`(account, capability, kind, value)` with reason, author and timestamp stripped. Commercially
sensitive text ("suspended pending investigation") therefore never reaches a consuming process, and
exactly one component can produce a trace because exactly one holds the record a trace is made of.

**The corollary, made explicit on 2026-08-10: the operator UI computes nothing.** This came to a head
over the remove-override confirmation, which must state what the value returns to *before* the
operator commits. Answering that in TypeScript would have put a second implementation of the combining
rule in the least defensible place, so it became a read-only route through the same
`Resolver.explain()`. **If the UI would have to reason about entitlement values to answer it, it is
the service's question.**

## 3. Two override kinds instead of a taxonomy of exceptions

The exceptions the business creates are varied — suspensions, goodwill, pilots, trial extensions,
compliance blocks, negotiated reductions. The obvious model gives each its own type and its own rule.
I took the opposite decision: **there are exactly two kinds, GRANT and HOLD, and one rule combines
them.** Baseline (plan value, else the capability's default) → raised by the most generous GRANT →
capped by the most restrictive HOLD. Deny-wins is my documented default, not a discovered truth. What
I insisted on is that the answer depend only on **what is in force at the moment of the decision,
never on creation order.** Time-bounded overrides later narrowed what "in force" means without
touching the rule itself — which is the test that the rule was the right shape.

The payoff is that the awkward cases stop being cases:

| The situation | How it falls out — with no special rule |
|---|---|
| Compliance suspends an account that already has a sales concession | The HOLD caps the GRANT. Lift the hold and the concession is intact underneath — no cleanup, no re-grant, nobody to remember. |
| A customer upgrades past an old, smaller concession | The plan wins, because the baseline is *the most generous of the plan and its GRANTs*. No sweep job to retire stale grants. |
| Sales and compliance act on the same account at once | Neither record touches the other, and order-independence means the result does not depend on who saved first. |
| A capability the plan does not mention at all | A GRANT grants it; the baseline is the capability's default and the trace says so. |
| Extending a trial | A second overlapping GRANT. Both agree while both are in force and the later carries on alone — which is why time-bounded overrides need no new rule for "extend". |
| Suspending a quantity to nothing | A HOLD of `0`, distinguishable in the trace from a plan value of `0` and from a defaulted `0`. |
| A per-region or per-format exception | Sets are one switch per member, so it is an ordinary GRANT on `residency.eu` — no fourth value type. |

The cost I accepted is real: **a negotiated lower limit and a fraud suspension are the same record
type.** The arithmetic is right for both, but they cannot be reported on separately and no rule can
treat them differently. That is override categories, deferred with its trigger.

"Most generous" needs a total order, which decides the value model: a sealed `EntitlementValue` with
three variants — `Switch`, `Quantity`, `Tier` — exhaustively checked by the compiler, so a fourth
cannot be added without every comparison site being pointed at. Hence **`unlimited` is a distinct
variant, never a large number** (the `Long.MAX_VALUE` shortcut leaks into the API the moment a value
is serialised) and **no unordered "choice" type** — every candidate I examined is really a set.

## 4. Other places one primitive absorbed a class of problems

§3 is the clearest instance of a pattern I used deliberately: rather than adding a rule per awkward
case, find the primitive that makes the cases stop existing. Four more worth discussing.

**The resolver takes a view, not a snapshot.** `resolve()` and `explain()` accept an
`EntitlementView` — an interface of lookup methods — never a concrete `Snapshot`. That one seam is why
five features are the same code rather than five implementations: the plan-edit preview (a view with
pending values applied), the remove-override confirmation (that override filtered out), the
whole-account response (every capability against one view instance, so the page is one coherent
moment), point-in-time answers (a view assembled from the audit trail at a past date), and
the service's own read path (a view over one WAL read transaction). Each would otherwise have been a
place to re-derive a value. **"What would the answer be if…" is always a different view, never
different arithmetic.**

**Records are marked, never destroyed.** Capabilities retire; overrides soft-delete; the audit trail
refuses `UPDATE` and `DELETE` at the trigger. One rule, and a list of problems never arise: no
override references a vanished capability, no history silently rewrites itself, "who removed this and
when" is always answerable. It paid a debt forward too: point-in-time answers need the past to still
be there, and when that feature was built the past already was.

**One append-only log is the only history mechanism.** No `plan_version` table, no periodic state
snapshots. Every write records before, after, actor, source and timestamp, and that one structure
serves the history screen, plan reconstruction, the material a future rollback would be built from,
point-in-time — and, through its autoincrement `seq`, the logical clock that makes "the state as of
sequence N" well-defined. The alternative was a version table per mutable thing, each with its own
retention and its own drift.

**"We don't know" is not "no".** Unknown account, unknown capability and retired capability each get
their own status and error type, never `allowed: false`. Had they been denials, every consuming
product would do the right thing for the wrong reason until a typo'd capability key started reading as
a legitimate denial. The companion definition does the same for values: **`allowed` means the
effective value differs from the declared off-value**, covering all three types with one rule and
making a limit of `0` a legitimate *allowed* answer the caller enforces.

Two smaller ones: **plans are partial**, so an unmentioned capability falls to the default *and that
fallback is a visible step in the trace* — adding a capability never requires editing every plan, and
"the plan set 0" stays distinguishable from "nothing was set". And **`capability.area` is derived from
the key prefix** rather than a second taxonomy, so grouping cannot drift from the keys it describes.

## 5. Storage: SQLite as the record, and what the schema enforces

SQLite is defensible precisely *because* it is never on a decision path in a consuming product. Its
single-writer limit means the management service does not horizontally scale — acceptable, because
decision availability comes from replicas, not service redundancy. WAL mode; one dedicated write
connection (pool size 1, removing `SQLITE_BUSY` as a class of bug rather than retrying around it) plus
a small read pool. **No JPA:** no first-party Hibernate dialect for SQLite, no object graph to lazily
load since the hot path bypasses the database, and hand-written SQL through `JdbcClient` keeps visible
the thing that matters — every write is paired with an audit record in the same transaction.

- **Typed value columns, not one polymorphic `rank`.** Defaults, plan entitlements and overrides store
  `bool_value` / `qty_value` / `qty_unlimited` / `tier_value` with `CHECK` constraints enforcing that
  exactly the right one is populated. A numeric rank would collapse "quantity 1" and "switch on" into
  one storage and force unlimited to be a magic number — §3's mistake, one layer down.
- **Tier values are foreign keys** into `capability_tier`, so a plan cannot hold a tier the capability
  does not declare. Ordinals are appendable above the maximum only: inserting between existing tiers
  renumbers them and silently rewrites stored values.
- **Append-only enforced by triggers**, so it holds against an errant migration and a hand-typed
  statement, not merely against well-behaved application code.
- **Overrides soft-delete** with `removed_at`/`removed_by` set together by a `CHECK`, and every
  live-row index is partial. `reason` is `NOT NULL` and non-blank, because an exception with no stated
  reason becomes unremovable in practice.
- **Exactly one default plan**, enforced by a partial unique index rather than application code.

A guarantee that holds only while everyone behaves is not a guarantee.

## 6. Java 21, Spring Boot 4, platform threads

Java 21 LTS; Spring Boot 4 on MVC and embedded Tomcat; Maven's reactor, because the SDK must publish
clean minimal POM metadata for other teams to depend on. **Virtual threads deliberately left off** —
the decision path does zero blocking I/O, so they have nothing to unblock, and Boot's own
documentation warns pinning can *reduce* throughput on 21. *Rejected: WebFlux*, which buys nothing
with no I/O to overlap and makes tail latency harder to reason about. REST with RFC 9457 problems, and
the wire vocabulary defined once and reused by every surface, so callers branch on `type` and never on
message text. The operator UI is React 19 + TanStack built into the service's static assets — one
deployable, no CORS story. *Rejected: server-rendered pages*, which would have given read-your-writes
and one toolchain free, but not the interaction density the plan editor and capability tree need.


## 7. Change visibility: 60 seconds, and the UI says so

Instant propagation adds load to the decision path; batching produces "why hasn't it applied yet"
tickets. The middle is a bounded, promised window, and every save confirmation states it — reading the
number from the API, never hard-coding 60, because a working-as-designed delay nobody warned you about
is indistinguishable from a fault. The operator sees their own change immediately regardless, since it
is committed before the response returns. Across services — billing reassigns a plan, then calls a
product whose replica is 5 s behind — mutating responses return their `snapshotVersion` and reads
accept a `minSnapshotVersion`, answering at or above it or returning `409`. The Zanzibar *zookie* in
its cheapest form: a monotonic integer suffices, because there is one writer and therefore one order.

## 8. What I deliberately left out

| Cut | Consequence I accepted |
|---|---|
| **Authentication and the three operator roles** | The big one. Anyone who can reach the service can add *or lift* any hold, compliance holds included; criterion 37 is not demonstrable. An `ActorResolver` seam keeps audit records complete and makes sign-in a bean replacement rather than a retrofit — but §9 records what changed when this went public. |
| **Usage counting** | This service owns the *limit*, not the counter. A service that says "limit 50, you count" must also say "limit 0, you handle it", or it applies two contradictory rules. |
| **Relative grants ("plan + 20")** | Stored as an absolute, a contractual promise evaporates on upgrade. Deferred because "most generous" needs redefining once a grant's value depends on the plan — a design question, not a detail. |
| **Plan inheritance** | Enterprise restates Pro's values. Inheritance makes explanations recursive, and the explanation is the product. |
| **Ungated capability default / off-value edits** | The one inconsistency I knowingly shipped: the plan editor refuses to save without showing its blast radius; the capability editor waves the same operator through on an edit reaching *more* accounts. |

Bulk overrides, capability dependencies, upgrade-path hints, override categories, an approval workflow
and a customer-facing view are each in `future-spec.md` with a trigger. Five further items were
**withdrawn** rather than left pending, so their absence reads as a decision: stale-override review,
existing-override warnings, account hierarchy, unordered choice values, usage-aware decisions.

**Time-bounded overrides** (002) are **built and merged**, and turn on one finding: windows are
evaluated by the service and never inside replicas — so the resolver is untouched, `resolverContract`
stays at 1, and the coordinated release across every consuming product this feature was predicted to
need does not happen. A window became a SQL predicate rather than a scheduler, so the service is
correct at every instant by construction and the midnight job exists only to tell replicas. The price
is that a cut-off replica keeps honouring an override that has ended — which the outage posture
requires, which a test now demonstrates by cutting a live replica off, and which will look like a bug
to anyone who has not read this sentence. Still unmerged: **a plain-English checker** (003),
interpreter-only, where the model
receives the question, the capability catalogue and today's date — never a value, decision, trace,
reason, or the account roster. An LLM near an entitlement decision is a bad idea; an LLM parsing a
search box is not.

## 9. Hosting, and one deviation on the record

Cloud Run, one always-warm instance, SQLite replicated to GCS by Litestream. `--max-instances=1` is
load-bearing: it turns "SQLite permits one writer host" from a note in a file into an infrastructure
guarantee. One assumption I tested rather than trusted — Litestream's documented final sync on SIGTERM
**does not happen** under `-exec` supervision, measured four ways — so the periodic sync interval is
the entire durability mechanism, and redeploys survive because they take minutes, not because anything
flushes at shutdown.

Publishing removed the trusted network on which the authentication deferral was accepted. Chosen
knowingly for demo reach, with three binding conditions: synthetic data only, `noindex`, and a named
teardown trigger — deleted when the assessment concludes or when sign-in lands. A demo that outlives
its purpose is how this becomes a real exposure.

## 10. What I would do differently with more time

- **Authentication first.** Two deferred items are blocked on it outright and a third triggers on it;
  the §8 risk is the only one I would not accept twice.
- **Settle the scale question before designing to it.** A throughput rubric nobody had asked for drove
  three acceptance criteria, a load-test module and a whole spec folder before the supported client
  base was pinned at 300 — at which point all of it was withdrawn.
- **Push instead of polling.** A 5 s poll was right for v1, but SSE fits better before the consumer
  count grows.
- **Override categories earlier.** Overrides cannot be edited, so everything created before that
  feature lands stays uncategorised for ever. It gets strictly worse the longer it waits.
- **Balance.** I front-loaded the budget onto the specification and the pure core, where the
  irreversible decisions live. The cost is that the API and UI layers came last and are the least
  settled part of the repository. Whoever inherits this gets an unusually well-documented system with
  an unfinished edge — which I would take over the reverse.
