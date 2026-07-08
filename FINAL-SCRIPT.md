# Mahoraga — Demo Recording Script

Read this straight down. **▶ RUN** lines are where you actually type a command.
Everything else is you just talking. Don't read it word-for-word or it'll sound
stiff — know the beat and the numbers, then say it like you.

- **Length:** aim ~7:15, hard stop before 8:00. Running long? Cut words, not runs.
- **On screen the whole time:** the line `Synthetic MVP — no customer data`.
- **You only type two commands** (preflight, then present). `present` finishes in
  a few seconds and prints the proof block — it stays on screen, so everything
  after that is you talking over it and pointing at lines.
- **Don't spoil it:** before the plans show up, don't say how the candidates turn
  out. Saying memory knows one "got fixed last time" is fine — that's the past,
  which it's allowed to use.

---

## 0 · Intro  (~0:40)

"Hey — I'm Varun. [← one line about you: what you do / your background.] I built
this thing called Mahoraga and I want to walk you through it.

Short version: it's a memory engine for offensive security. I built it as one
small service — Java, Postgres, a real transactional ingestion path — plus a set
of deterministic synthetic fixtures that play out two engagements against the same
target. There's no LLM anywhere in the core. It's all plain, reproducible logic,
so everything you're about to see comes out identical every single time I run it.

Let me start with the problem, then just run it."

---

## 1 · The blank-notebook problem  (~0:30)

"First — and I'll keep saying this — everything here is fake data. It's a synthetic
demo, it is not wired into Armadin's real systems.

Okay. The problem I care about: you've got a swarm that goes and attacks a customer,
finds a bunch of stuff, writes it all down — and then the engagement ends and it
basically throws the notebook away. Next quarter it shows up and it's starting from
zero. It can't tell you if the bug it found last time is still there, or got fixed,
or got fixed and then came right back. That gap — that's the whole thing I'm trying
to fix. It's the memory."

**▶ RUN:**
```
scripts/demo.sh preflight
```
*(wait for: `Preflight passed.`)*

---

## 2 · One command  (~0:25)

"One command does the whole thing. This next command runs everything — but that
preflight I just ran is just me being careful: it checks Java, Docker, that the
build's there, that the safety guards are good, and it changes nothing.

When I run this, it loads up Engagement 1. Every fact it learns is its own row,
locked down, scoped to the tenant. And when the engagement's done, it draws a hard
line that says 'this is everything we knew, as of right now.' Hang on to that line
— it matters in a second."

**▶ RUN:**
```
scripts/demo.sh present
```
*(it streams `== Preflight ==`, `== Memory disabled ==`, `== Memory enabled ==`,
`== Comparing Java evidence ==`, then prints the proof block. Keep talking over it;
the proof block stays on screen for the rest of the demo.)*

---

## 3 · The plans can't cheat, and the 3 → 1  (~1:15)

*(point at: `Memory disabled: [T-A, T-B, T-C]`, `Memory enabled: [T-C, T-A, T-B]`,
`Actions before regression detection: 3 -> 1`)*

"Storing stuff is fine, whatever — the real question is whether the memory changes
what you do next. So there's a planner, and it runs at that Engagement 1 line,
before we know anything about the second engagement.

And this is the part people get wrong: it is so easy to accidentally let the future
leak in — to let the planner peek at answers it shouldn't have yet. So the planner
only ever sees opaque IDs. No labels, no results, nothing about how it turns out.

With memory off, it just goes in order: A, B, C. Turn memory on, and it knows one of
these got fixed last time and is worth double-checking, so it pulls that one to the
front: C, A, B.

Then both plans actually run, against identical copies of the same starting point,
same outcomes baked in. And the bug that came back — memory catches it on the very
first move instead of the third. Three to one. And that 'zero events at planning'
line is just me proving nothing leaked."

---

## 4 · Same thing after everything changed  (~0:50)

*(point at: `Canonical Deployment unchanged: true`, `Weak-signal collision: AMBIGUOUS`)*

"None of this works, though, if you can't tell it's the same thing across time.
Between the two engagements the pod got a new ID, a new name, a new IP — basically
everything you'd normally key on changed. But the actual Deployment underneath is
the same, so we keep it as the same asset, and the finding stays stuck to it. That's
what lets you talk about a bug over time.

And then the opposite case: when something's genuinely fuzzy — a reused DNS name with
nothing solid behind it — it doesn't guess. It marks it ambiguous and flat-out
refuses to let it move any numbers. It'd rather say 'I don't know' than smash two
things together that might not be the same thing."

---

## 5 · Same facts, two lenses  (~1:25)

*(point at the `Stateless E2 view` block, then the `Memory-aware E1 + E2 view` block)*

"This next one's the one I'd actually show a customer. Same exact Engagement 2 facts
— I'm not touching the data — I'm just changing how much history it's allowed to look
at.

With no memory, which is what a normal point-in-time scan is, you see three
detections, one not-detected, one partial. And the finding nobody retested this time?
It just isn't there. The scan literally can't see it, because it has no idea it ever
existed.

Now turn the history back on, same facts, and suddenly they mean something. One's
new, one's still open, one is actually verified fixed, one regressed, one nobody
retested, one's inconclusive. And that 'verified fixed' is the one I care about — it
only counts if we actually reran the same check and it came back clean. Not seeing a
bug is not the same as the bug being gone. Only the memory version can tell you a fix
came back, or that you straight-up forgot to retest something."

---

## 6 · The boring stuff that has to be right  (~0:45)

*(point at the `Correctness` block)*

"Real quick on the unglamorous stuff, because it just has to work. Send the same
event twice — no-op, nothing happens. Send a conflicting one — rejected. Ask for a
report before the data's actually complete — it blocks you. Shuffle the order
everything shows up in — you get the exact same report. Kill a transaction halfway
through — nothing's left behind, no half-written mess. Same facts, same answer, every
time. That's the part you have to be able to trust before any of the rest matters."

---

## 7 · Why I built this, and where it goes  (~1:15)

*(the `Scope` line is on screen)*

"So — why did I actually build this. Honestly, mostly for fun. I'm pretty sure the
folks at Armadin are already thinking about this, or already have something like it.
But I kept coming back to this idea that there's a ton of untapped power in giving
security systems real memory, and I just wanted to explore it myself. It was a
genuinely interesting problem to sit with.

And there's a bunch more I'd love to do with it. A few things I'm excited about:

Integration. I found this open-source project called T3MP3ST — it's a multi-agent
offensive-security framework that runs the whole kill chain, recon, exploit, all the
way to reporting. Really cool project. But it has no memory — every run is a blank
slate. Bolting Mahoraga onto something like that would be a super natural fit, and it
would let me show the whole loop end to end against a real target instead of fixtures.

Then, architecture. I built this as one app to keep it simple, but the memory flow
really wants to be split into separate pieces — a writer that owns ingestion and the
transaction, a reader that just serves memory back out, a separate report job, a
shared core library. Pull those apart and each part can scale and fail on its own.
That's where this goes as it grows up.

And then a longer list of stuff I find genuinely interesting: tracking how the
environment itself drifts over time, not just the findings. Tradecraft memory —
which techniques actually worked against which kinds of stacks. Real tamper-evident
provenance with a hash chain. And eventually the hard, fascinating one — safely
sharing learnings across customers without leaking anyone's data. Each of those is
its own project.

But the core — the memory engine, the part that has to be correct — that's what I've
got working and proven right here."

---

## 8 · Wrap up  (~0:20)

"And that's it. Everything you just saw — the three-to-one, the identity stuff, the
six categories, all the correctness checks — none of it is hardcoded. It's all
computed from what actually happened in the run, and it comes out byte-for-byte
identical every single time I run it. Fake data, yeah. But a real result. Thanks for
watching."

---

## After you stop recording

- Confirm the run was clean: the proof block should match, container removed, port
  free (`scripts/demo.sh` handles cleanup).
- Reconcile [`docs/demo/demo-transcript.md`](docs/demo/demo-transcript.md) and
  [`docs/demo/demo-captions.vtt`](docs/demo/demo-captions.vtt) to what you actually
  said, then finish the checklist in
  [`docs/demo/demo-artifact-manifest.md`](docs/demo/demo-artifact-manifest.md).
