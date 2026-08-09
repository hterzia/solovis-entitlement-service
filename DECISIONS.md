this service doesnt own the usage tracker just the limit. 
a question that would be forwarded to business, when multiple overrides disagree, which one should we go with (current implementation goes with deny always wins )

duration to show changes, instantatnation adds stress to load calls, batches might cause extra support tickets, so in between, within a known time limit ("Changes will be active in 60s).

## 2026-08-09 — decisions recorded during spec review
- Change visibility: 60 seconds or less, end to end; the UI states the promise wherever a change is saved. (init-spec §7, criteria 28 & 41)
- Override conflicts: signed off — a restriction always defeats a concession; strictest HOLD caps the result, most generous GRANT wins among grants. (init-spec §4)
- Outage posture: products keep using the last answer they saw — an outage neither takes away nor grants. Mechanism left to engineering. (init-spec §11)
- Plan changes & grandfathering: settled — a plan edit applies to every account on the plan ("everyone follows"), matching Anthropic/OpenAI practice for entitlements. Contractual customers are protected by GRANTs; wholesale grandfathering is a deliberate successor-plan act. The Stigg/Chargebee per-change audience choice is noted as the future path in future-spec item 6. (init-spec §3.2, §11)

