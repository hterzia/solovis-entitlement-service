# Notes

Photographs of the paper the design started on, before anything was written into `.specs/`.
Kept because the specification reads as though it arrived finished, and it did not.

## What is here

[`assignment-notes.jpg`](./assignment-notes.jpg) — the brief as handed over, highlighted while
reading it. The colours are the first pass at vocabulary: *customer*, *plan*, *feature*, *limit*,
*override*, *exception* — each one marked in its own colour wherever it recurs. Several of those
words did not survive; see the note on drift below.

[`db-design-notes.jpg`](./db-design-notes.jpg) — the first storage sketch. Five boxes
(`PLANS`, `PLAN_ENTITLEMENT`, `FEATURES`, `CUSTOMERS`, `OVERRIDES`) with crow's-feet between them,
and two unresolved questions written to one side: `? Feature Value unit` and `? PLAN_OVERRIDES`.
The first became the three-variant value encoding (`SWITCH` / `QUANTITY` / `TIER`, with `unlimited`
as a distinct variant rather than a large number). The second was answered *no* — overrides attach
to an account, never to a plan.

[`edge-cases-notes.jpg`](./edge-cases-notes.jpg) — four "collision cases", each written as a
sequence of the number a customer actually ends up with:

- `20 → 50 → 20` — a grant of +20 seats, then a temporary +50 for everyone that expires in 30 days.
- `20 → 10 → 20` — the same grant, capped to 10 for a month while servers are down.
- `20 → 0 → 50` — reports per month, legal disables exports until the customer is compliant, sales
  has already promised 50 for when they are.
- `0 → 10 → 50` — a free customer given 10 reports to try, who then upgrades to Enterprise.

Working those four through by hand is what produced the combining rule the whole service is now
built around: baseline, raised by the most generous grant, then capped by the most restrictive hold
— a restriction always defeats a concession. Cases 1 and 2 are also the entire argument for
`.specs/002-time-bound-override/`; on paper the expiry is an aside, and in the model it is a
second axis.

## Terminology drift

The paper says **customer** and **feature**. The shipped model says **account** and **capability**.
Read the sketches with that substitution in mind — `CUSTOMERS.ID` is `account_key`, `FEATURES` is
the capability registry, and `PLAN_ENTITLEMENT` kept its name. The relational shape survived the
rename largely intact; what changed underneath it is that this is no longer the read path
(see `CLAUDE.md` — decisions resolve against an in-memory snapshot, never SQLite).

Originals were HEIC from a phone camera; committed here as JPEG so they render in the GitHub UI.
