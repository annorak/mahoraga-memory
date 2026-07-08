# Mahoraga Memory MVP — Demo Script

A roughly 7-minute walkthrough. The goal is to sound like a real person who built this
and cares about it — technical, but explaining things plainly, the way you'd talk
a junior engineer through it. Not a pitch. Keep it honest and a little informal.

- **Target runtime:** ~7:15. **Hard maximum:** 7:59.
- **Audience:** Armadin engineering and product.
- **Read-off version:** [`FINAL-SCRIPT.md`](../../FINAL-SCRIPT.md) is the plain
  teleprompter with `▶ RUN` markers; this file is the annotated planning version.
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
| 0:00–0:40 | Intro — who I am, what this is, how I built it | 0:40 | — |
| 0:40–1:10 | The blank-notebook problem, and "this is fake data" | 0:30 | 1, 2 |
| 1:10–1:35 | One command, and the Engagement 1 line | 0:25 | 3 |
| 1:35–2:50 | The plans can't cheat, and the 3 → 1 | 1:15 | 4, 5, 6 |
| 2:50–3:40 | Same thing after everything changed, and "I don't know" | 0:50 | 7, 8 |
| 3:40–5:05 | Same facts, two lenses | 1:25 | 9 |
| 5:05–5:50 | The boring stuff that has to be right | 0:45 | 10 |
| 5:50–7:05 | Why I built this, and where it goes | 1:15 | 11 |
| 7:05–7:25 | Wrap up | 0:20 | — |

If a rehearsal runs long, cut words — don't add anything new to fill the buffer.

---

## Segment 0 — Intro: who I am, what this is, how I built it (0:00–0:40, max 0:40)

- **On-screen action:** terminal up; the "Synthetic MVP — no customer data" line
  is visible from the start.
- **Expected output cue:** clean terminal, nothing run yet.
- **Narration:** "Hey — I'm Varun. [one line about you: what you do / your
  background.] I built this thing called Mahoraga and I want to walk you through it.
  Short version: it's a memory engine for offensive security. I built it as one
  small service — Java, Postgres, a real transactional ingestion path — plus a set
  of deterministic synthetic fixtures that play out two engagements against the same
  target. There's no LLM anywhere in the core; it's all plain, reproducible logic,
  so everything you're about to see comes out identical every single time I run it.
  Let me start with the problem, then just run it."
- **Product meaning:** establish the person, the thing, and how it was built before
  any jargon. Keep it warm and short.
- **Transition:** "So, first the problem."

## Segment 1 — The blank-notebook problem, and "this is fake data" (0:40–1:10, max 0:30)

- **On-screen action:** terminal up; the "Synthetic MVP — no customer data" line
  is visible.
- **Expected output cue:** clean terminal, nothing run yet.
- **Narration:** "First — and I'll keep saying this — everything here is fake data.
  It's a synthetic demo, it is not wired into Armadin's real systems. Okay. The
  problem I care about: you've got a swarm that attacks a customer, finds a bunch of
  stuff, writes it all down — and then the engagement ends and it basically throws
  the notebook away. Next quarter it shows up and it's starting from zero. It can't
  tell you if the bug it found last time is still there, or got fixed, or got fixed
  and then came right back. That gap — that's the whole thing I'm trying to fix. It's
  the memory."
- **Product meaning:** name the gap in plain terms; keep the honest scope loud.
- **Transition:** "Let me just run it."

## Segment 2 — One command, and the Engagement 1 line (1:10–1:35, max 0:25)

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

## Segment 3 — The plans can't cheat, and the 3 → 1 (1:35–2:50, max 1:15)

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

## Segment 4 — Same thing after everything changed, and "I don't know" (2:50–3:40, max 0:50)

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

## Segment 5 — Same facts, two lenses (3:40–5:05, max 1:25)

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

## Segment 6 — The boring stuff that has to be right (5:05–5:50, max 0:45)

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

## Segment 7 — Why I built this, and where it goes (5:50–7:05, max 1:15)

- **On-screen action:** transcript's `Scope` line visible.
- **Expected output cue:**
  ```
  Scope
  Synthetic product-value demonstration; not a production privacy or security validation.
  ```
- **Narration:** "So — why did I actually build this. Honestly, mostly for fun. I'm
  pretty sure the folks at Armadin are already thinking about this, or already have
  something like it. But I kept coming back to this idea that there's a ton of
  untapped power in giving security systems real memory, and I just wanted to explore
  it myself. It was a genuinely interesting problem to sit with. And there's a bunch
  more I'd love to do with it. A few things I'm excited about. Integration — I found
  this open-source project called T3MP3ST, a multi-agent offensive-security framework
  that runs the whole kill chain, recon, exploit, all the way to reporting. Really
  cool project, but it has no memory — every run is a blank slate. Bolting Mahoraga
  onto something like that would be a super natural fit, and it'd let me show the
  whole loop end to end against a real target instead of fixtures. Then architecture —
  I built this as one app to keep it simple, but the memory flow really wants to be
  split into separate pieces: a writer that owns ingestion and the transaction, a
  reader that just serves memory back out, a separate report job, a shared core.
  Pull those apart and each part can scale and fail on its own. And then a longer list
  I find genuinely interesting: tracking how the environment itself drifts over time,
  not just the findings; tradecraft memory, which techniques actually worked against
  which kinds of stacks; real tamper-evident provenance with a hash chain; and
  eventually the hard, fascinating one — safely sharing learnings across customers
  without leaking anyone's data. Each of those is its own project. But the core — the
  memory engine, the part that has to be correct — that's what I've got working and
  proven right here."
- **Product meaning:** honest, personal framing; shows depth through what you'd build
  next, not through overclaiming what exists.
- **Transition:** "Okay, wrapping up."

## Segment 8 — Wrap up (7:05–7:25, max 0:20)

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
