# Mahoraga Memory MVP — Demo Transcript

Narration transcript for the demo video, derived from the approved
[`demo-script.md`](demo-script.md). Timecodes follow the script's timing budget
(target 6:30). Before acceptance, this transcript and
[`demo-captions.vtt`](demo-captions.vtt) must be reconciled against the actual
recorded narration — they never override what was actually spoken or displayed.

> Opening disclosure (visible on screen and spoken): **Synthetic MVP — no
> customer data.**

---

**[0:00–0:35] Blank-notebook problem and synthetic scope**

An automated offensive-security swarm tests a customer, writes down what it finds,
and then forgets all of it when the engagement ends. Next quarter it starts from a
blank notebook — it can't tell whether a past weakness is still open, was actually
fixed, or quietly came back. Everything you're about to see is a deterministic
synthetic MVP: one Java application, one PostgreSQL database, and checked-in
fixtures. It is not wired into Armadin production.

**[0:35–1:05] Minimal runtime and E1 boundary**

Preflight is read-only — it checks Java, Docker, the built artifact, and the
demo's safety guards, and changes nothing. Then one command runs the whole
demonstration. Engagement 1 is ingested first: every fact is a tenant-scoped
immutable row, and the engagement is finalized behind an explicit knowledge
boundary so later queries see exactly the finalized positions and nothing after
them.

**[1:05–2:25] Leakage-free plans and executed 3 → 1**

The planner runs at the Engagement 1 boundary, before any Engagement 2 result
exists. It sees opaque candidate IDs only — no scenario labels, no outcomes, no
eventual classification. With memory off, it orders candidates plainly: T-A, T-B,
T-C. With memory on, it uses one persisted fact — that one target was previously
verified resolved and is worth rechecking first — and returns T-C, T-A, T-B. Both
plans then execute the same frozen outcomes in separate, identical copies of the
finalized Engagement 1 state. The executed histories measured regression discovery
at action three without memory versus action one with memory. "Zero E2 events at
planning: true" confirms nothing leaked.

**[2:25–3:20] Deployment identity and ambiguous match**

Between engagements the Pod UID, name, and IP all changed, but the canonical
Kubernetes Deployment identity stayed the same — so the finding attached to it
remains the same finding. And when a weak signal collides — a reused DNS name with
no authoritative Deployment UID — the system holds it as AMBIGUOUS and lets it
change posture zero times. Weak evidence never silently merges two assets.

**[3:20–4:55] Same E2 facts: stateless versus memory report**

These are the exact same Engagement 2 facts, rendered through two knowledge
boundaries — no second code path. A stateless point-in-time scan sees three
detections, one not-detected, one partial, and it literally cannot represent the
finding that was never retested. Put Engagement 1 memory back in scope and the
same facts gain lifecycle meaning: one new, one still open, one verified resolved,
one regressed, one not retested, one inconclusive. A completed compatible negative
verifies resolution; absence does not. Only the memory view can say a fix came
back, distinguish verified resolution from a first negative, and flag a known
weakness that wasn't retested.

**[4:55–5:45] Retry, gap, rollback, and shuffle confidence**

Ingestion is deterministic and safe: an identical retry is a no-op, a conflicting
duplicate is rejected, a report is blocked while a completion sequence is missing,
shuffled arrival order produces the same report hash, and a forced transaction
failure leaves no partial state. Same facts, same answer, regardless of order or
retries.

**[5:45–6:35] Armadin value, honest limits, and roadmap**

For Armadin this turns a point-in-time scan into continuous, longitudinal security
— regression detection, verified-resolution tracking, and coverage honesty — which
is what justifies a recurring engagement over a one-off. Being honest about limits:
this MVP is not production-scale, HA, or DR; it is not integrated with Armadin, and
it makes no zero-change-integration claim. It records no authorization, validates
no privacy or cross-tenant isolation, and includes no tradecraft or cross-tenant
learning. The internal event contract reduces future adapter work but does not
remove it. Real integration and hardening are roadmap.

**[6:35–7:00] Closing proof summary**

Everything here — the three-to-one planner result, stable identity, the six
classifications, and the correctness checks — was computed from executed
application behavior over persisted facts, not printed from expected values, and
it reproduces byte-for-byte across clean runs. Synthetic MVP, but a real,
deterministic result.

---

## Proof block displayed on screen

The `Normalized stakeholder proof` phase prints this (byte-stable; normalized
transcript SHA-256 `f81d12c3c33f7a616b1e80b8ca9062d584a8a827bfa5dac7673a56499513ddd6`):

```
MAHORAGA MEMORY MVP
Synthetic local evidence

Steering
Candidate tests: [T-A, T-B, T-C]
Memory disabled: [T-A, T-B, T-C]
Memory enabled: [T-C, T-A, T-B]
Actions before regression detection: 3 -> 1
Zero E2 events at planning: true

Stable identity
Pod UID/name/IP changed: true
Canonical Deployment unchanged: true
Weak-signal collision: AMBIGUOUS
Posture changes from ambiguous observation: 0

Stateless E2 view
Detected: 3
Not detected: 1
Partial: 1
Findings with no E2 fact: unrepresentable
Longitudinal classifications: unavailable

Memory-aware E1 + E2 view
NEW: 1
STILL_OPEN: 1
VERIFIED_RESOLVED: 1
REGRESSED: 1
NOT_RETESTED: 1
INCONCLUSIVE: 1

Correctness
Duplicate retry: NO_OP
Conflicting duplicate: EVENT_CONTENT_REJECTED
Missing completion sequence: UNFINALIZED_REPORT_BLOCKED
Shuffled ingestion report hash equal: true
Transaction failure leaves partial state: false

Scope
Synthetic product-value demonstration; not a production privacy or security validation.
```
