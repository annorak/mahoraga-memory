# Mahoraga Memory MVP — Demo Script

A reviewer-approved 6–7 minute presentation. It connects the executed synthetic
evidence to Armadin's business value without overstating integration, scale,
privacy, or production maturity.

- **Target runtime:** 6:30. **Hard maximum:** 7:59.
- **Audience:** mixed Armadin engineering and product.
- **What the presenter types:** two commands only — `scripts/demo.sh preflight`
  and `scripts/demo.sh present`. Everything else is narration over the output
  that `present` streams, ending with the normalized proof block it prints.
- **Setup, recording hygiene, and recovery:** see
  [`demo-runbook.md`](demo-runbook.md).

> Say this near the start and keep it true throughout: **this is a deterministic
> synthetic MVP — one Java application, one PostgreSQL database, and checked-in
> fixtures. It is not integrated with Armadin production.**

## How `present` behaves

`scripts/demo.sh present` runs the entire demonstration as one guarded workflow
and streams these phase headings as it goes:

```
== Preflight ==
== Memory disabled ==
== Memory enabled ==
== Comparing Java evidence ==
== Normalized stakeholder proof ==
```

The two arms run in separate clean databases, but the guarded workflow normally
finishes well before the narrated presentation. Its headings may stream before
the related narration is complete. The final `Normalized stakeholder proof`
phase prints the full transcript and leaves it on screen; the proof walk-through
(segments 5–8) reads directly from it.

## Timing budget

| Time | Segment | Max | Story beats |
|---|---|---:|---|
| 0:00–0:35 | Blank-notebook problem and synthetic scope | 0:35 | 1, 2 |
| 0:35–1:05 | Minimal runtime and E1 boundary | 0:30 | 3 |
| 1:05–2:25 | Leakage-free plans and executed `3 -> 1` | 1:20 | 4, 5, 6 |
| 2:25–3:20 | Deployment identity and ambiguous match | 0:55 | 7, 8 |
| 3:20–4:55 | Same E2 facts: stateless versus memory report | 1:35 | 9 |
| 4:55–5:45 | Retry, gap, rollback, and shuffle confidence | 0:50 | 10 |
| 5:45–6:35 | Armadin value, honest limits, and roadmap | 0:50 | 11 |
| 6:35–7:00 | Closing proof summary and buffer | 0:25 | — |

Do not fill the buffer with new features. If a rehearsal runs long, tighten
narration; do not add material.

---

## Segment 1 — Blank-notebook problem and synthetic scope (0:00–0:35, max 0:35)

- **On-screen action:** title/terminal visible; the "Synthetic MVP — no customer
  data" disclosure is on screen.
- **Expected output cue:** clean terminal, no commands run yet.
- **Narration:** "An automated offensive-security swarm tests a customer, writes
  down what it finds, and then forgets all of it when the engagement ends. Next
  quarter it starts from a blank notebook — it can't tell whether a past weakness
  is still open, was actually fixed, or quietly came back. Everything you're about
  to see is a deterministic synthetic MVP: one Java application, one PostgreSQL
  database, and checked-in fixtures. It is not wired into Armadin production."
- **Product meaning:** frames the gap memory closes, and sets honest scope first.
- **Transition:** "Here's the whole moving system."

## Segment 2 — Minimal runtime and E1 boundary (0:35–1:05, max 0:30)

- **On-screen action:** type `scripts/demo.sh preflight`, then `scripts/demo.sh present`.
- **Expected output cue:** `Preflight passed.`, then `== Preflight ==` and
  `== Memory disabled ==` begin streaming.
- **Narration:** "Preflight is read-only — it checks Java, Docker, the built
  artifact, and the demo's safety guards, and changes nothing. Then one command
  runs the whole demonstration. Engagement 1 is ingested first: every fact is a
  tenant-scoped immutable row, and the engagement is finalized behind an explicit
  knowledge boundary so later queries see exactly the finalized positions and
  nothing after them."
- **Product meaning:** durable, boundary-controlled memory is the substrate for
  everything else.
- **Transition:** "The sharp part is what memory does to the next plan."

## Segment 3 — Leakage-free plans and executed `3 -> 1` (1:05–2:25, max 1:20)

- **On-screen action:** keep the terminal visible. If the arms are still running,
  narrate as their headings stream; otherwise point to the `Steering` block in
  the final transcript.
- **Expected output cue (from the final transcript):**
  ```
  Candidate tests: [T-A, T-B, T-C]
  Memory disabled: [T-A, T-B, T-C]
  Memory enabled: [T-C, T-A, T-B]
  Actions before regression detection: 3 -> 1
  Zero E2 events at planning: true
  ```
- **Narration:** "The planner runs at the Engagement 1 boundary, before any
  Engagement 2 result exists. It sees opaque candidate IDs only — no scenario
  labels, no outcomes, no eventual classification. With memory off, it orders
  candidates plainly: T-A, T-B, T-C. With memory on, it uses one persisted fact —
  that one target was previously verified resolved and is worth rechecking first —
  and returns T-C, T-A, T-B. Both plans then execute the same frozen outcomes in
  separate, identical copies of the finalized Engagement 1 state. The executed
  histories measured regression discovery at action three without memory versus
  action one with memory. `Zero E2 events at planning: true` confirms nothing
  leaked."
- **Product meaning:** memory doesn't just store; it re-prioritizes the next
  engagement, and the improvement is measured from executed behavior, not asserted.
- **Transition:** "That only works if we recognize the same target across change."

## Segment 4 — Deployment identity and ambiguous match (2:25–3:20, max 0:55)

- **On-screen action:** narrate; point to the identity block when the transcript
  prints.
- **Expected output cue:**
  ```
  Pod UID/name/IP changed: true
  Canonical Deployment unchanged: true
  Weak-signal collision: AMBIGUOUS
  Posture changes from ambiguous observation: 0
  ```
- **Narration:** "Between engagements the Pod UID, name, and IP all changed, but
  the canonical Kubernetes Deployment identity stayed the same — so the finding
  attached to it remains the same finding. And when a weak signal collides — a
  reused DNS name with no authoritative Deployment UID — the system holds it as
  `AMBIGUOUS` and lets it change posture zero times. Weak evidence never silently
  merges two assets."
- **Product meaning:** stable identity under churn is what makes longitudinal
  memory trustworthy rather than noisy.
- **Transition:** "Now the customer-facing payoff — same facts, two lenses."

## Segment 5 — Same E2 facts: stateless versus memory report (3:20–4:55, max 1:35)

- **On-screen action:** walk through the two report blocks in the printed transcript.
- **Expected output cue:**
  ```
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
  ```
- **Narration:** "These are the exact same Engagement 2 facts, rendered through
  two knowledge boundaries — no second code path. A stateless point-in-time scan
  sees three detections, one not-detected, one partial, and it literally cannot
  represent the finding that was never retested. Put Engagement 1 memory back in
  scope and the same facts gain lifecycle meaning: one new, one still open, one
  verified resolved, one regressed, one not retested, one inconclusive. A completed
  compatible negative verifies resolution; absence does not. Only the memory view
  can say a fix came back, distinguish verified resolution from a first negative,
  and flag a known weakness that wasn't retested."
- **Product meaning:** this is the retainer case — continuous longitudinal
  security instead of repeated independent scans.
- **Transition:** "Underneath the story, the memory has to be trustworthy."

## Segment 6 — Retry, gap, rollback, and shuffle confidence (4:55–5:45, max 0:50)

- **On-screen action:** read the correctness block.
- **Expected output cue:**
  ```
  Duplicate retry: NO_OP
  Conflicting duplicate: EVENT_CONTENT_REJECTED
  Missing completion sequence: UNFINALIZED_REPORT_BLOCKED
  Shuffled ingestion report hash equal: true
  Transaction failure leaves partial state: false
  ```
- **Narration:** "Ingestion is deterministic and safe: an identical retry is a
  no-op, a conflicting duplicate is rejected, a report is blocked while a
  completion sequence is missing, shuffled arrival order produces the same report
  hash, and a forced transaction failure leaves no partial state. Same facts, same
  answer, regardless of order or retries."
- **Product meaning:** the evidence behind the story holds up to real operational
  messiness.
- **Transition:** "So what does this mean for Armadin?"

## Segment 7 — Armadin value, honest limits, and roadmap (5:45–6:35, max 0:50)

- **On-screen action:** transcript's `Scope` line visible.
- **Expected output cue:**
  ```
  Scope
  Synthetic product-value demonstration; not a production privacy or security validation.
  ```
- **Narration:** "For Armadin this turns a point-in-time scan into continuous,
  longitudinal security — regression detection, verified-resolution tracking, and
  coverage honesty — which is what justifies a recurring engagement over a one-off.
  Being honest about limits: this MVP is not production-scale, HA, or DR; it is not
  integrated with Armadin, and it makes no zero-change-integration claim. It records
  no authorization, validates no privacy or cross-tenant isolation, and includes no
  tradecraft or cross-tenant learning. The internal event contract reduces future
  adapter work but does not remove it. Real integration and hardening are roadmap."
- **Product meaning:** credible business case with a defensible boundary.
- **Transition:** "To close, the one-screen proof."

## Segment 8 — Closing proof summary and buffer (6:35–7:00, max 0:25)

- **On-screen action:** the full normalized transcript is on screen.
- **Expected output cue:** the complete printed proof block, ending at the `Scope`
  line.
- **Narration:** "Everything here — the three-to-one planner result, stable
  identity, the six classifications, and the correctness checks — was computed from
  executed application behavior over persisted facts, not printed from expected
  values, and it reproduces byte-for-byte across clean runs. Synthetic MVP, but a
  real, deterministic result."
- **Product meaning:** the proof is reproducible, not staged.
- **Transition:** end. Do not improvise additional claims into the buffer.

---

## Leakage and claims guardrails

- Do **not** reveal the runner-only outcome map or the expected candidate outcomes
  (T-A detected, T-B not detected, T-C detected) before the plans are shown in
  Segment 3. The candidates are opaque until then.
- Use only the approved claims: "In this deterministic synthetic MVP…", "the
  executed history measured regression discovery at action three versus one", "the
  same E2 facts gain lifecycle meaning when E1 memory is in scope", "a completed
  compatible negative verifies resolution; absence does not", "the internal event
  contract reduces future adapter work but does not remove it".
- Never claim production readiness/scale/HA/DR/SLOs, Armadin integration or
  zero-change integration, authorization enforcement or recording, privacy/DLP or
  cross-tenant isolation validation, cross-customer network effects, a gate,
  tradecraft, embeddings, tamper-evident provenance, live Kubernetes, remediation
  or MTTR, or that the synthetic `3 -> 1` is a general performance benchmark.
