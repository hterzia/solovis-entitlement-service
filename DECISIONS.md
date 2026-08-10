# Decisions

The choices below are mine. Every alternative weighed sits in
`.specs/001-entitlement-service/research.md` (21 topics) and `plan.md`; everything deferred, each
with its trigger, is in `.specs/future-spec.md`. The specification itself is deliberately a
*business* document — where a decision looks technical but has a business kernel (the outage posture,
grandfathering), the posture is stated there and only the mechanism deferred to engineering, so it
stays obvious who owns which decision. Its 41 acceptance criteria are cited from code as `(cNN)`.

## 1. The decision everything else follows from

I fixed the outage posture as a business rule before choosing any mechanism: **an outage must neither
take away what a customer had nor grant what they lacked.** Products carry on with the last answer
they saw — never failing open, never failing closed. That, plus decisions that stay correct *while
plans and overrides are changing*, rules out "every product calls the service on every check".

So: **the resolution rule is a small pure library over an immutable in-memory snapshot, and that
snapshot is replicated into each consuming product.** SQLite is the system of record and is never on
a decision path. Writes commit and atomically swap a new snapshot; consumers embed the same library
via an SDK holding their own replica. A decision is a local map lookup, and a replica that loses
contact keeps answering from what it last saw — which *is* the posture, rather than a document asking
five teams to implement it consistently.

| Rejected | Why |
|---|---|
| One materialised entitlement document per account in a KV store | Closest contender: resolution happens once, centrally, and drift becomes impossible. But denormalising ~10 plans across 100k accounts turns a ~5 MB model into ~2 GB, and the outage posture then depends on a second piece of infrastructure staying up. |
| Every product calls the service per decision | Puts this in every product's request path, and SQLite's single writer leaves nothing to fail over to. |
| Replicate the SQLite file (Litestream / WAL shipping) | Callers need the domain snapshot, not the database file — smaller, simpler, language-agnostic. |
| Per-key cache with a 10 s TTL | Meets the freshness bound, not the outage posture: a key never seen before has no last answer to fall back on. |

**The trade I accepted:** the rule now runs inside every consumer — the scattered-logic problem this
service exists to delete, reappearing one layer down. Guarded structurally, not by discipline: the
feed carries a `resolverContract` version and conformance vectors each replica evaluates at startup,
refusing to serve on mismatch.

## 2. Products get answers; only the operator UI gets explanations

`resolve()` returns `(allowed, value)` and allocates nothing; `explain()` runs the *identical*
arithmetic and layers a trace on top, so the two cannot disagree. The feed carries only what
`resolve()` needs. Sensitive reason text ("suspended pending investigation") never leaves the
management layer, and "the UI renders the trace the evaluation returned, never a copy" becomes
structural — one component produces traces because one component holds the record a trace is made of.
The cost: two disagreeing replicas can't be diagnosed by diffing explanations, since replicas have
none. Which is why the startup gate isn't optional.

## 3. The combining rule

Baseline (plan value, else the capability's default) → raised by the **most generous GRANT** → capped
by the **most restrictive HOLD**. A restriction always defeats a concession.

I flagged this as genuinely a business question — *when exceptions disagree, who wins?* — and
deny-wins is my documented default, not a discovered truth. What I insisted on is that the answer
depend only on **what exists at the moment of the decision, never on creation order**. So two authors
can't clobber each other, lifting a hold restores the grant underneath it with no cleanup, and the
explanation is never more than four lines. Order-independence is property-tested, not assumed. Where
overrides tie, the trace marks the newest as winner: the value is identical either way, but without a
deterministic tie-break two evaluations of identical state could render different explanations.

Two corollaries. **No unordered "choice" value type** — every candidate I examined (residency, export
formats, integrations, IdPs, currencies) is really a *set*, and a set is one switch per member; a
fourth type would need its own rule and forfeit the total order "most generous" depends on. And
**`unlimited` is a distinct variant, never a large number** — the `Long.MAX_VALUE` shortcut leaks
into the API the moment a value is serialised.

## 4. SQLite, hand-written SQL, invariants in the schema

SQLite is defensible precisely *because* it is never on a decision path. Its single-writer limit
means the management service doesn't horizontally scale — fine, since decision availability comes
from replicas, not service redundancy. No JPA: no first-party dialect, no object graph to lazily
load, and explicit SQL keeps visible the thing that matters — every write is paired with an audit
record in the same transaction.

The immutability rules live in schema and triggers, not code review: audit append-only, capabilities
retired never deleted, overrides never edited (remove + recreate, both with reasons), tiers
appendable above the maximum but never insertable between existing ones (renumbering silently
rewrites stored values), exactly one default plan. A guarantee that holds only while everyone behaves
is not a guarantee.

The operator tool is a React SPA over REST, not server-rendered pages: server rendering would have
given read-your-writes and one toolchain free, but not the interaction density the plan editor and
capability tree need. It builds into the service's static assets — one deployable, no CORS story.

## 5. Change visibility: 60 seconds, and the UI says so

Instant propagation adds load to the decision path; batching produces "why hasn't it applied yet"
tickets. The middle is a bounded, promised window, and every save confirmation states it — reading
the number from the API, never hard-coding 60. A working-as-designed delay nobody warned you about is
indistinguishable from a fault. The operator sees their own change immediately regardless, because
the snapshot swap happens inside the write's commit path.

## 6. Plan edits apply to everyone on the plan

Rejected: the per-change audience choice dedicated platforms (Stigg, Chargebee) offer at publish
time. Contractual customers are protected by an explicit GRANT created *before* the plan moves;
grandfathering is a deliberate act — leave the old plan alone, create a successor. Nothing keeps old
terms by accident.

Hence **no plan rollback**. "Revert this override" deletes a record; "revert this plan edit" is not
the analogous operation, because accounts moved between plans meanwhile and replaying an old version
grants capabilities to accounts never meant to have them. Plan history is reconstructed from the
audit trail, so no version table exists solely to serve a button the spec forbids.

## 7. What I deliberately left out

| Cut | Consequence I accepted |
|---|---|
| **Authentication and the three operator roles** | The big one. Anyone who can reach the service can add *or lift* any hold, compliance holds included; criterion 37 is not demonstrable. Accepted for a single-operator MVP — §9 records what changed when it went public. |
| **Usage counting** | This service owns the *limit*, not the counter. A service that says "limit 50, you count" must also say "limit 0, you handle it", or it applies two contradictory rules. |
| **Override expiry** | Every temporary promise is permanent until removed by hand. Specified as feature 002 — §8. |
| **Relative grants ("plan + 20")** | Stored as an absolute, a contractual promise evaporates on upgrade. Deferred because "most generous" needs redefining once a grant's value depends on the plan — a design question, not a detail. |
| **Plan inheritance** | Enterprise restates Pro's values. Inheritance makes explanations recursive, and the explanation is the product. |
| **Bulk/segment overrides, capability dependencies, upgrade-path hints, override categories, approval workflow, customer-facing view** | Each in `future-spec.md` with a trigger and dependencies. |
| **Ungated capability default / off-value edits** | The one inconsistency I knowingly shipped: the plan editor refuses to save without showing its blast radius; the capability editor waves the same operator through on an edit reaching *more* accounts. |
| **Upstream (billing) writes** | Deferred until sign-in lands; v1 writes come from the operator UI alone. |

Five further items were **withdrawn** from the deferred list rather than left pending, so their
absence reads as a decision: stale-override review, existing-override warnings, account hierarchy,
unordered choice values, usage-aware decisions.

## 8. The stretch goal

Specified in `.specs/002-time-bound-override/`, turning on one finding: **time windows are evaluated
only when the management service assembles a snapshot, never inside replicas.** A start publishes as
an ordinary "override created" delta, an end as "override removed" — so the resolver is untouched,
the contract version stays at 1, and no coordinated release across products is needed. The
consequence I accepted and wrote down: a cut-off replica keeps honouring an override that has since
ended, which the outage posture requires and which will look like a bug to anyone who hasn't read
this sentence. Also settled: dates not timestamps, one timezone application-wide, extension is a
second overlapping override rather than an edit, no back-dating at all, point-in-time answers for
operators only, and retention raised from 24 months to 7 years to match the commercial records
disputes turn on.

"Two independent instances resolve identically" is *shown, not asserted*: the spec's worked examples
are transcribed literally as executable conformance vectors, property tests shuffle the override set
and assert an identical decision, and every replica runs the vectors at startup and refuses to serve
if its engine disagrees.

A third spec (`.specs/003-natural-language-procesing/`) asks the checker a question in plain English,
deliberately **interpreter-only**: a model turns the sentence into an account and a capability, then
the service verifies both locally and runs the existing checker. The model never sees a value,
decision, trace, reason or the account roster. An LLM near an entitlement decision is a bad idea; an
LLM parsing a search box is not.

## 9. Hosting, and one deviation on the record

Cloud Run, one always-warm instance, SQLite replicated to GCS by Litestream. `--max-instances=1` is
load-bearing: it turns "SQLite permits one writer host" from a note in a file into an infrastructure
guarantee.

Publishing removes the trusted network on which the authentication deferral was accepted. Chosen
knowingly for demo reach, with three binding conditions: synthetic data only, `noindex`, and a named
teardown trigger — deleted when the assessment concludes or when sign-in lands, whichever comes
first. A demo that outlives its purpose is how this becomes a real exposure. The demo seeds change
history as well as data, and the seeded trail must tell the story that ends at the state the demo
starts in.

## 10. What I would do differently with more time

- **Authentication first.** Two deferred items are blocked on it outright and a third triggers on it;
  the §7 risk is the only one I wouldn't accept twice.
- **Ship the load harness.** The performance criteria are designed for but not yet evidenced.
  Indexed SQLite reads inside a read-only transaction (see §11) make the targets easy in principle,
  and easy in principle is not evidence — the widest gap between what the design claims and what it
  has proved.
- **Push instead of polling.** A 5 s poll of one integer lands ten times inside the freshness
  requirement, so it was right for v1, but SSE fits better before the consumer count grows.
- **Override categories earlier.** Overrides can't be edited, so everything created before that
  feature lands stays uncategorised forever. It gets strictly worse the longer it waits.
- **Balance.** I front-loaded the budget onto the specification and the pure core, where the
  irreversible decisions live. The cost is that the API and UI layers came last and are the least
  settled part of the repository. Whoever inherits this gets an unusually well-documented system with
  an unfinished edge — which I'd take over the reverse.

## 11. Deleting the in-memory snapshot holder

I moved the service's own read path off the in-memory `SnapshotHolder` and onto SQLite directly: a
decision now runs inside one read-only transaction against the read connection pool, and WAL mode
gives that transaction the "one coherent moment" guarantee (c31) the holder used to give by being
swapped atomically. The rejected alternative was to keep the holder as the service's own read path and
build 002 on top of it anyway.

The holder's original justification was criteria 25–27 — 5,000 decisions/s at p99 ≤ 10 ms, holding
while writes happen — and that justification was never evidenced; the load harness never ran (§10).
Meanwhile 002 has three concrete needs that fight a held snapshot: explaining overrides that are *not*
in force without keeping them in heap for seven years (c19–c21), point-in-time answers that want to be
the same view-building code with a different `asOf`, and window standing that has to be exact at the
moment of asking. A held snapshot answers all three by growing new special cases; direct reads answer
them by construction.

The holder was also carrying invariants nobody had named: a version counter kept correct only by
convention between two places, a publish shape that was safe only because the write pool is size 1,
and a one-publish-per-transaction rule enforced by nothing. Deleting it deletes those along with it,
and makes the data-governance boundary structural rather than disciplined — the object that leaves the
process (the feed) can't leak what the process never assembles into memory in the first place.

**§10's second bullet, fixed in the same commit**: "in-memory lookups behind an atomic reference make
the targets easy in principle" described the deleted mechanism. It's now indexed SQLite reads inside a
read-only transaction that make them easy in principle — the point of that bullet (easy in principle
is not evidence, the load harness still hasn't run) is untouched, only the mechanism it pointed at.
