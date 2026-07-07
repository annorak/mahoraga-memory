# Mahoraga Memory MVP — Demo Script

A 6–7 minute walkthrough. The goal is to sound like a real person who built this
and cares about it — technical, but explaining things plainly, the way you'd talk
a junior engineer through it. Not a pitch. Keep it honest and a little informal.

- **Target runtime:** 6:30. **Hard maximum:** 7:59.
- **Audience:** Armadin engineering and product.
- **What you actually type:** two commands — `scripts/demo.sh preflight` and
  `scripts/demo.sh present`. Everything else is you talking over the output, and
  then walking through the proof block it prints at the end.
- **Setup, recording hygiene, and recovery:** see
  [`demo-runbook.md`](demo-runbook.md).

> Say the honest-scope bit out loud early and mean it: this is all fake data, one
> app and one database, and it is **not** plugged into Armadin's real systems.
> Being straight about that is part of why the technical claims land.

## How `present` behaves

`scripts/demo.sh present` runs the whole thing as one guarded workflow and prints
these headings as it goes:

```
== Preflight ==
== Memory disabled ==
== Memory enabled ==
== Comparing Java evidence ==
== Normalized stakeholder proof ==
```

The two runs each spin up their own clean database, so it takes a couple of
minutes while you talk. The last phase prints the full proof block and leaves it
on screen — that's what you read from in segments 5–8.

## Timing budget

| Time | Segment | Max | Story beats |
|---|---|---:|---|
| 0:00–0:35 | The blank-notebook problem, and "this is fake data" | 0:35 | 1, 2 |
| 0:35–1:05 | One command, and the Engagement 1 line | 0:30 | 3 |
| 1:05–2:25 | The plans can't cheat, and the 3 → 1 | 1:20 | 4, 5, 6 |
| 2:25–3:20 | Same thing after everything changed, and "I don't know" | 0:55 | 7, 8 |
| 3:20–4:55 | Same facts, two lenses | 1:35 | 9 |
| 4:55–5:45 | The boring stuff that has to be right | 0:50 | 10 |
| 5:45–6:35 | Why Armadin cares, and what this isn't yet | 0:50 | 11 |
| 6:35–7:00 | Wrap up | 0:25 | — |

If a rehearsal runs long, cut words — don't add anything new to fill the buffer.

---

## Segment 1 — The blank-notebook problem, and "this is fake data" (0:00–0:35, max 0:35)

- **On-screen action:** terminal up; the "Synthetic MVP — no customer data" line
  is visible.
- **Expected output cue:** clean terminal, nothing run yet.
- **Narration:** "Okay, real quick before anything — everything here runs on fake
  data. It's a synthetic demo, it is not hooked up to Armadin's actual systems. I
  just want to be upfront about that. So, the problem I care about: you've got a
  swarm that attacks a customer, finds a bunch of stuff, writes it all down — and
  then the engagement ends and it basically throws the notebook away. Next quarter
  it shows up and it's starting from zero. It can't tell you if the bug it found
  last time is still there, or got fixed, or got fixed and then came right back.
  That's the whole thing I'm trying to fix here. It's the memory."
- **Product meaning:** name the gap in plain terms; set honest scope first.
- **Transition:** "Let me just run it."

## Segment 2 — One command, and the Engagement 1 line (0:35–1:05, max 0:30)

- **On-screen action:** type `scripts/demo.sh preflight`, then `scripts/demo.sh present`.
- **Expected output cue:** `Preflight passed.`, then `== Preflight ==` and
  `== Memory disabled ==` start streaming.
- **Narration:** "One command does the whole thing. This first part, preflight,
  is just me being careful — it checks Java, Docker, that the build's there, that
  the safety guards are good — and it doesn't change anything. Then it loads up
  Engagement 1. Every fact it learns is its own row, locked down, scoped to the
  tenant. And when the engagement's done, we draw a hard line that says 'this is
  everything we knew, as of right now.' Hang on to that line, it matters in a
  second."
- **Product meaning:** durable, boundary-controlled memory is the foundation.
- **Transition:** "Because here's the part I actually care about."

## Segment 3 — The plans can't cheat, and the 3 → 1 (1:05–2:25, max 1:20)

- **On-screen action:** talk while `== Memory disabled ==` and
  `== Memory enabled ==` run.
- **Expected output cue (from the final transcript):**
  ```
  Candidate tests: [T-A, T-B, T-C]
  Memory disabled: [T-A, T-B, T-C]
  Memory enabled: [T-C, T-A, T-B]
  Actions before regression detection: 3 -> 1
  Zero E2 events at planning: true
  ```
- **Narration:** "Storing stuff is fine, whatever — the real question is whether
  the memory changes what you do next. So there's a planner, and it runs at that
  Engagement 1 line, before we know anything about the second engagement. And this
  is the part people get wrong: it is so easy to accidentally let the future leak
  in — to let the planner peek at answers it shouldn't have yet. So the planner
  only ever sees opaque IDs. No labels, no results, nothing about how it turns out.
  With memory off, it just goes in order: A, B, C. Turn memory on, and it knows one
  of these got fixed last time and is worth double-checking, so it pulls that one
  to the front: C, A, B. Then both plans actually run, against identical copies of
  the same starting point, same outcomes baked in. And the bug that came back —
  memory catches it on the very first move instead of the third. Three to one. And
  that 'zero events at planning' line is just me proving nothing leaked."
- **Product meaning:** memory re-prioritizes the next engagement, and it's measured
  from what actually ran, not asserted.
- **Transition:** "None of this works, though, if you can't tell it's the same thing."

## Segment 4 — Same thing after everything changed, and "I don't know" (2:25–3:20, max 0:55)

- **On-screen action:** talk; point at the identity block once it prints.
- **Expected output cue:**
  ```
  Pod UID/name/IP changed: true
  Canonical Deployment unchanged: true
  Weak-signal collision: AMBIGUOUS
  Posture changes from ambiguous observation: 0
  ```
- **Narration:** "Between the two engagements the pod got a new ID, a new name, a
  new IP — basically everything you'd normally key on changed. But the actual
  Deployment underneath is the same, so we keep it as the same asset, and the
  finding stays stuck to it. That's what lets you talk about a bug across time. And
  then the opposite case: when something's genuinely fuzzy — a reused DNS name with
  nothing solid behind it — it doesn't guess. It marks it ambiguous and flat-out
  refuses to let it move any numbers. It'd rather say 'I don't know' than smash two
  things together that might not be the same thing."
- **Product meaning:** stable identity under churn is what makes the memory
  trustworthy instead of noisy.
- **Transition:** "Alright, this next one's the one I'd actually show a customer."

## Segment 5 — Same facts, two lenses (3:20–4:55, max 1:35)

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
- **Narration:** "Same exact Engagement 2 facts — I'm not touching the data — I'm
  just changing how much history it's allowed to look at. With no memory, which is
  what a normal point-in-time scan is, you see three detections, one not-detected,
  one partial. And the finding nobody retested this time? It just isn't there. The
  scan literally can't see it, because it has no idea it ever existed. Now turn the
  history back on, same facts, and suddenly they mean something. One's new, one's
  still open, one is actually verified fixed, one regressed, one nobody retested,
  one's inconclusive. And that 'verified fixed' is the one I care about — it only
  counts if we actually reran the same check and it came back clean. Not seeing a
  bug is not the same as the bug being gone. Only the memory version can tell you a
  fix came back, or that you straight-up forgot to retest something."
- **Product meaning:** this is the retainer case — continuous security instead of
  repeated one-off scans.
- **Transition:** "Real quick on the boring stuff, because it has to be right."

## Segment 6 — The boring stuff that has to be right (4:55–5:45, max 0:50)

- **On-screen action:** read the correctness block.
- **Expected output cue:**
  ```
  Duplicate retry: NO_OP
  Conflicting duplicate: EVENT_CONTENT_REJECTED
  Missing completion sequence: UNFINALIZED_REPORT_BLOCKED
  Shuffled ingestion report hash equal: true
  Transaction failure leaves partial state: false
  ```
- **Narration:** "This is the unglamorous stuff that just has to work. Send the
  same event twice — no-op, nothing happens. Send a conflicting one — rejected. Ask
  for a report before the data's actually complete — it blocks you. Shuffle the
  order everything shows up in — you get the exact same report. Kill a transaction
  halfway through — nothing's left behind, no half-written mess. Same facts, same
  answer, every time. That's the part you have to be able to trust before any of
  the rest matters."
- **Product meaning:** the evidence behind the story holds up to real messiness.
- **Transition:** "So — why does Armadin actually care."

## Segment 7 — Why Armadin cares, and what this isn't yet (5:45–6:35, max 0:50)

- **On-screen action:** transcript's `Scope` line visible.
- **Expected output cue:**
  ```
  Scope
  Synthetic product-value demonstration; not a production privacy or security validation.
  ```
- **Narration:** "So why does Armadin care. This is the thing that turns a one-off
  scan into something you'd actually pay for every quarter — you can prove a fix
  regressed, you can prove something got verified instead of just not-seen, you can
  catch the stuff nobody retested. And let me be straight about what this isn't:
  it's not production-scale, it's not wired into Armadin, it doesn't do
  authorization or any of the privacy or cross-tenant stuff — that's all on purpose,
  it's later. The way this actually becomes real is you point a real swarm at a real
  target — Armadin's, or honestly any of these agent frameworks — and you write one
  adapter that turns its output into the same event contract you just watched go by.
  That adapter is real work, it's not a one-liner. But the memory engine underneath
  it — that's the part that's done, and that's what I just showed you."
- **Product meaning:** credible business case with a defensible, honest boundary.
- **Transition:** "Okay, wrapping up."

## Segment 8 — Wrap up (6:35–7:00, max 0:25)

- **On-screen action:** the full proof block is on screen.
- **Expected output cue:** the complete printed proof, ending at the `Scope` line.
- **Narration:** "And that's it. Everything you just saw — the three-to-one, the
  identity stuff, the six categories, all the correctness checks — none of it is
  hardcoded. It's all computed from what actually happened in the run, and it comes
  out byte-for-byte identical every single time I run it. Fake data, yeah. But a
  real result. Thanks for watching."
- **Product meaning:** the proof is reproducible, not staged.
- **Transition:** end. Don't tack on extra claims to fill time.

---

## Leakage and claims guardrails

- Do **not** reveal how the candidates turn out (T-A detected, T-B not detected,
  T-C detected) before the plans are shown in Segment 3. Saying memory knows one
  was "fixed last time" is fine — that's the prior-engagement fact the planner is
  allowed to use; do not hint at the Engagement 2 outcome.
- Keep it honest. Fine to say: it's a synthetic MVP; the executed run measured
  regression discovery at three versus one; a fix only counts when we reran the
  same check; the event contract cuts down future adapter work but doesn't remove
  it.
- Never claim: production-ready/scale/HA/DR/SLOs, that it's integrated with
  Armadin or zero-change to integrate, authorization enforcement or recording,
  privacy/DLP or cross-tenant isolation, cross-customer network effects, a gate,
  tradecraft, embeddings, tamper-evident provenance, live Kubernetes, remediation
  or MTTR, or that the synthetic 3 → 1 is some general benchmark.
