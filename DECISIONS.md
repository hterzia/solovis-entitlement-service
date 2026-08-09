this service doesnt own the usage tracker just the limit. 
a question that would be forwarded to business, when multiple overrides disagree, which one should we go with (current implementation goes with deny always wins )

duration to show changes, instantatnation adds stress to load calls, batches might cause extra support tickets, so in between, within a known time limit ("Changes will be active in 60s).

## 2026-08-09 — decisions recorded during spec review
- Change visibility: 60 seconds or less, end to end; the UI states the promise wherever a change is saved. (init-spec §7, criteria 28 & 41)
- Override conflicts: signed off — a restriction always defeats a concession; strictest HOLD caps the result, most generous GRANT wins among grants. (init-spec §4)
- Outage posture: products keep using the last answer they saw — an outage neither takes away nor grants. Mechanism left to engineering. (init-spec §11)
- Plan changes & grandfathering: settled — a plan edit applies to every account on the plan ("everyone follows"), matching Anthropic/OpenAI practice for entitlements. Contractual customers are protected by GRANTs; wholesale grandfathering is a deliberate successor-plan act. The Stigg/Chargebee per-change audience choice is noted as the future path in future-spec item 6. (init-spec §3.2, §11)

## 2026-08-09 — clarifications from plan review
- Capability default and off-value edits ship ungated in v1 — accepted as a known limitation (init-spec §12); an affected-account warning is future work (future-spec item 17).
- Upstream-system (billing) writes are deferred until sign-in lands. v1 writes come from the operator UI alone; a system-sourced plan change is demonstrated with a simulated caller.
- Overrides are never edited: immutable from creation to removal. Correcting one is remove + recreate, both audited with reasons.
- Explanation tie-break: when overrides tie on value, the newest is named the winner in the explanation. The effective value is unaffected either way.
- The account "closed" state stays unused in v1 — every customer is active; closing is a future offboarding concern.
- MVP posture, acknowledged: with sign-in deferred, anyone who can reach the service can add or lift any hold, compliance holds included. Accepted while this is a single-operator MVP on a trusted network.

