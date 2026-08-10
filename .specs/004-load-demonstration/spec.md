# The Load Demonstration — Business Specification

**Status:** Draft for review
**Date:** 2026-08-10
**Companion documents:** [`001-entitlement-service/spec.md`](../001-entitlement-service/spec.md) — the v1 specification whose speed promises this evidences · [`future-spec.md`](../future-spec.md) — deferred scope

---

## 1. Purpose

The v1 specification makes seven promises about speed, freshness and consistency, and then says how they must be settled: *"criteria 25–31 are evidenced by a demonstration at the stated volumes, run against data that is changing during the demonstration."*

Everything else in v1 can be shown to a person in a browser. These seven cannot. They are claims about behaviour at a hundred thousand accounts and five thousand decisions a second, and no amount of reading the design settles whether they hold.

This feature is that demonstration. Its only output is **evidence**: a repeatable run, at the stated volumes, that either shows the promises being kept or shows exactly which one is not.

**Why it is a feature of its own rather than a task inside v1.** The demonstration is not a test of the code; it is a measurement of a deployment. It needs a service that is running, a database with a hundred thousand real accounts in it, and someone changing that data while the measurement is taken. That is a different kind of work with a different definition of done, and folding it into v1 hid it — for as long as it sat on v1's task list it stayed unbuilt while everything around it was finished, and v1 read as complete when seven of its criteria had never been evidenced at all.

---

## 2. Scope

### In scope

- A body of data at the stated size: a hundred thousand accounts, several hundred capabilities, a realistic number of exceptions
- A sustained measurement of single-capability decisions at the stated rate
- A separate measurement of whole-account requests
- Changes being made to plans and exceptions *while* those measurements run
- A measurement of how long a saved change takes to become visible, both at the service and inside a consuming product's own copy
- A written result: what was run, at what size, and what was observed

### Out of scope

| Not in scope | Why |
|---|---|
| **Changing the service to go faster** | If the demonstration shows a promise is not kept, that is a finding. Acting on it is separate work, and mixing the two would let the measurement be tuned until it agreed with the claim. |
| **Continuous performance testing** | This answers "are the v1 promises kept". Watching for slow drift over months is a different job with different tooling, and inventing it here would delay the answer. |
| **Measuring the operator screens** | The speed promises are about decisions. Nobody made a promise about how fast a plan editor paints, and §7 is careful not to. |
| **Proving the service survives failure** | The outage posture is a v1 concern already settled by design and covered by its own tests. This is about speed under load, not behaviour under breakage. |

---

## 3. What must be demonstrated

Each item restates a v1 acceptance criterion. The numbering is v1's, deliberately, so the evidence and the promise cannot drift apart.

| v1 criterion | The promise |
|---|---|
| 25 | With a hundred thousand accounts and five thousand single-capability decisions a second sustained, 99 of every 100 are answered within 10 milliseconds |
| 26 | Whole-account requests are measured **separately**: 99 of every 100 within 50 milliseconds |
| 27 | Both hold **while plans and exceptions are being changed**, not only at rest |
| 28 | A saved change is reflected in decisions within 60 seconds, end to end |
| 29 | The 10-second limit on how long a caller may reuse an answer is documented as a condition of 28, and callers are held to it |
| 30 | An operator re-checking immediately after saving sees their own change |
| 31 | One evaluation reflects one coherent moment and never mixes new and stale state |

### The conditions the run must meet

- **Real volumes, not scaled-down ones.** A run at ten thousand accounts demonstrates nothing about a hundred thousand, because the interesting failures are the ones that only appear at size.
- **Changing data throughout.** A measurement taken against a still database is the measurement §7 explicitly refuses to accept: *"a system that is fast only when nothing is happening has not met this requirement."*
- **Measured from outside.** The demonstration exercises the service the way a product would, over its published interfaces. A measurement taken from inside the code would be measuring the wrong thing.
- **Repeatable by someone else.** A number nobody can reproduce is an anecdote. The run must be a command, not a procedure.
- **Freshness measured end to end**, including inside a consuming product's own copy of the model — that is where the 60-second promise actually lands, and measuring only the service would flatter it.

---

## 4. What "done" means

Done is a **result**, not a passing test.

The demonstration is complete when it has been run at the stated volumes with data changing throughout, and its output states, for each of the seven promises, either that it was kept or by how much it was missed.

A run that shows a promise is **not** kept is a complete demonstration. It is not a failure of this feature; it is this feature working. The finding then belongs to whoever decides what to do about it — which may be to fix the service, or to revise the promise, and those are different decisions with different owners.

The result must be legible to someone who was not there: what was run, how much data, what was changing underneath, what was observed, and on what hardware. A p99 with no account of the machine it came from is not evidence.

---

## 5. Acceptance criteria

1. A body of data at the stated size can be created from nothing by a single command, and creating it is not itself so slow as to be impractical.
2. The demonstration runs single-capability decisions at the stated sustained rate for long enough that the result is not a warm-up artefact.
3. Whole-account requests are measured in their own right, separately from single-capability decisions, and reported separately.
4. Plans and exceptions are being changed throughout every measurement, and the run states what that rate of change was.
5. The freshness measurement observes a specific saved change and reports when it became visible, both at the service and in a consuming product's own copy.
6. The run reports the observed 99th-percentile timings against the promised ones, and states plainly which promises were kept.
7. The result records the size of the data, the rate of change, the duration, and the hardware.
8. The whole run is reproducible from a single documented command by someone who did not write it.

---

## 6. Known limitations of any such demonstration

Stated so nobody mistakes the evidence for more than it is.

| Limitation | Consequence |
|---|---|
| One machine, one shape of hardware | The result holds for hardware like the one it ran on, and says nothing certain about anything else. |
| Synthetic accounts and synthetic exceptions | Real customers are distributed less evenly than generated ones. The demonstration can be made pessimistic on purpose, but it is not real traffic. |
| A moment in time | It evidences the promises for the build it ran against. It is not a guarantee about future builds, which is why the run must stay cheap enough to repeat. |
