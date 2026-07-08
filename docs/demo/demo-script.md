# Mahoraga Memory MVP — Demo Script

A roughly 7-minute walkthrough. The goal is to sound like a real person who built this
and cares about it — technical, but explaining things plainly, the way you'd talk
a junior engineer through it. Not a pitch. Keep it honest and a little informal.

- **Target runtime:** ~7:35. **Hard maximum:** 7:59.
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
| 0:00–0:35 | Intro — who I am, what this is, how I built it | 0:35 | — |
| 0:35–1:05 | The blank-notebook problem, and "this is fake data" | 0:30 | 1, 2 |
| 1:05–2:00 | What present is doing — stubs the swarm, runs it twice | 0:55 | 3 |
| 2:00–3:20 | What T-A/T-B/T-C are, and the 3 → 1 | 1:20 | 4, 5, 6 |
| 3:20–4:15 | Same thing after everything changed (real K8s churn) | 0:55 | 7, 8 |
| 4:15–5:30 | Same facts, two lenses | 1:15 | 9 |
| 5:30–6:10 | The boring stuff that has to be right | 0:40 | 10 |
| 6:10–7:15 | Why I built this, and where it goes | 1:05 | 11 |
| 7:15–7:35 | Wrap up | 0:20 | — |

If a rehearsal runs long, cut words — don't add anything new to fill the buffer.

---

## Segment 0 — Intro: who I am, what this is, how I built it (0:00–0:35, max 0:35)

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

## Segment 1 — The blank-notebook problem, and "this is fake data" (0:35–1:05, max 0:30)

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

## Segment 2 — What present is doing: it stubs the swarm and runs twice (1:05–2:00, max 0:55)

- **On-screen action:** type `scripts/demo.sh preflight`, then `scripts/demo.sh present`.
- **Expected output cue:** `Preflight passed.`, then `== Preflight ==`,
  `== Memory disabled ==`, `== Memory enabled ==` stream.
- **Narration:** "That preflight I just ran was just me being careful — it checks
  Java, Docker, the build, the safety guards, and it changes nothing. Now this next
  command is the whole trick, so let me tell you what it's actually doing. Think of it
  as standing in for the swarm — the thing that would normally go run the attacks.
  Real engagements are messy and random, so I've frozen the whole thing into a
  fixture: a fixed set of checks with fixed results. That's on purpose — it means the
  only thing that can change between runs is Mahoraga's memory. And it runs the entire
  second engagement twice — once with memory off, once with memory on — each against
  its own clean database, from the exact same starting point. So if anything's
  different at the end, you know it came from the memory and nothing else. It's a
  controlled experiment. It loads Engagement 1 first, and draws that hard line —
  everything we knew, as of right now. Keep that in mind, it matters in a sec."
- **Product meaning:** the fixture stubs the agent's actions with frozen outcomes so
  memory is the only variable; the two arms are a controlled experiment.
- **Transition:** "Okay, so what actually comes out of that."

## Segment 3 — What T-A/T-B/T-C are, and the 3 → 1 (2:00–3:20, max 1:20)

- **On-screen action:** point at the candidate/plan/metric lines in the printed proof.
- **Expected output cue (from the final transcript):**
  ```
  Candidate tests: [T-A, T-B, T-C]
  Memory disabled: [T-A, T-B, T-C]
  Memory enabled: [T-C, T-A, T-B]
  Actions before regression detection: 3 -> 1
  Zero E2 events at planning: true
  ```
- **Narration:** "See these three up top — T-A, T-B, T-C? Those are just three checks
  the swarm could run in the second engagement — basically 'go re-test this, go
  re-test that.' And to the planner they're completely opaque; it's three IDs, it has
  no idea what any of them will find. That part matters. So the planner's only job
  here is: what order do we run these in? With memory off it's got nothing to go on,
  so it just runs them in order — A, B, C. Turn memory on, and it looks back at
  Engagement 1 and notices one of these is pointing at something we'd already confirmed
  fixed. And something you fixed coming back is exactly what you want to check first —
  so it pulls that one to the front: C, A, B. Then both plans actually run, same frozen
  results baked in — and here's the payoff: the bug that came back, the memory version
  catches it on the very first action instead of the third. Three down to one. And
  that's how you know it's the memory and not luck — both runs started from the
  identical state, with the identical outcomes. The one and only difference was whether
  the planner could see history. Same everything, better result — that's the memory
  engine doing its job. And 'zero events at planning' is just me proving the planner
  never got to peek at the second engagement's answers."
- **Product meaning:** opaque candidates + a held-constant experiment are what make the
  3 → 1 a causal proof of memory, not a coincidence.
- **Transition:** "None of this works, though, if you can't tell it's the same thing."

## Segment 4 — Same thing after everything changed, and "I don't know" (3:20–4:15, max 0:55)

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
  finding stays stuck to it. That's what lets you talk about a bug over time. And
  that's Mahoraga doing exactly what it's supposed to — tracking a finding across
  engagements even when the asset's name and IP change out from under it. In real
  Kubernetes that happens constantly: pods get rescheduled, IPs get recycled, names
  churn. The Deployment is the thing that's actually stable, so that's what we key on.
  And then the opposite case: when something's genuinely fuzzy — a reused DNS name with
  nothing solid behind it — it doesn't guess. It marks it ambiguous and flat-out
  refuses to let it move any numbers. It'd rather say 'I don't know' than smash two
  things together that might not be the same thing."
- **Product meaning:** stable identity under real production churn is what makes the
  memory trustworthy instead of noisy.
- **Transition:** "Alright, this next one's the one I'd actually show a customer."

## Segment 5 — Same facts, two lenses (4:15–5:30, max 1:15)

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

## Segment 6 — The boring stuff that has to be right (5:30–6:10, max 0:40)

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

## Segment 7 — Why I built this, and where it goes (6:10–7:15, max 1:05)

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

## Segment 8 — Wrap up (7:15–7:35, max 0:20)

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
