# Mahoraga — Demo Recording Script

Read this straight down. **▶ RUN** lines are where you actually type a command.
Everything else is you just talking. Don't read it word-for-word or it'll sound
stiff — know the beat and the numbers, then say it like you.

- **Length:** aim ~7:35, hard stop before 8:00. Running long? Cut words, not runs.
- **On screen the whole time:** the line `Synthetic MVP — no customer data`.
- **You only type two commands** (preflight, then present). `present` finishes in
  a few seconds and prints the proof block — it stays on screen, so everything
  after that is you talking over it and pointing at lines.
- **Don't spoil it:** before the plans show up, don't say how the candidates turn
  out. Saying memory knows one "got fixed last time" is fine — that's the past,
  which it's allowed to use.

---

## 0 · Intro  (~0:35)

"Hey — I'm Varun. [← one line about you: what you do / your background.] I built
this thing called Mahoraga and I want to walk you through it.

Short version: it's a memory engine for offensive security. I built it as one
small service — Java, Postgres, a real transactional ingestion path — plus a set
of deterministic synthetic fixtures that play out two engagements against the same
target. There's no LLM anywhere in the core; it's all plain, reproducible logic,
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

## 2 · What this command is actually doing  (~0:55)

"That preflight I just ran was just me being careful — it checks Java, Docker, the
build, the safety guards, and it changes nothing.

Now this next command is the whole trick, so let me tell you what it's actually
doing. Think of it as standing in for the swarm — the thing that would normally go
run the attacks. Real engagements are messy and random, so I've frozen the whole
thing into a fixture: a fixed set of checks with fixed results. That's on purpose —
it means the only thing that can change between runs is Mahoraga's memory.

And it runs the entire second engagement twice — once with memory off, once with
memory on — each against its own clean database, from the exact same starting point.
So if anything's different at the end, you know it came from the memory and nothing
else. It's a controlled experiment.

It loads Engagement 1 first, and draws that hard line — everything we knew, as of
right now. Keep that in mind, it matters in a sec."

**▶ RUN:**
```
scripts/demo.sh present
```
*(it streams `== Preflight ==`, `== Memory disabled ==`, `== Memory enabled ==`,
`== Comparing Java evidence ==`, then prints the proof block. Keep talking over it;
the proof block stays on screen for the rest of the demo.)*

---

## 3 · What T-A / T-B / T-C are, and the 3 → 1  (~1:20)

*(point at: `Candidate tests: [T-A, T-B, T-C]`, `Memory disabled: [T-A, T-B, T-C]`,
`Memory enabled: [T-C, T-A, T-B]`, `Actions before regression detection: 3 -> 1`)*

"See these three up top — T-A, T-B, T-C? Those are just three checks the swarm could
run in the second engagement — basically 'go re-test this, go re-test that.' And to
the planner they're completely opaque. It's three IDs; it has no idea what any of
them will find. That part matters.

So the planner's only job here is: what order do we run these in? With memory off it's
got nothing to go on, so it just runs them in order — A, B, C. Turn memory on, and it
looks back at Engagement 1 and notices one of these is pointing at something we'd
already confirmed fixed. And something you fixed coming back is exactly what you want
to check first — so it pulls that one to the front: C, A, B.

Then both plans actually run, same frozen results baked in — and here's the payoff:
the bug that came back, the memory version catches it on the very first action instead
of the third. Three down to one.

And that's how you know it's the memory and not luck — both runs started from the
identical state, with the identical outcomes. The one and only difference was whether
the planner could see history. Same everything, better result. That's the memory
engine doing its job. And that 'zero events at planning' line is just me proving the
planner never got to peek at the second engagement's answers."

---

## 4 · Same thing after everything changed  (~0:55)

*(point at: `Canonical Deployment unchanged: true`, `Weak-signal collision: AMBIGUOUS`)*

"None of this works, though, if you can't tell it's the same thing across time.
Between the two engagements the pod got a new ID, a new name, a new IP — basically
everything you'd normally key on changed. But the actual Deployment underneath is
the same, so we keep it as the same asset, and the finding stays stuck to it. That's
what lets you talk about a bug over time.

And that's Mahoraga doing exactly what it's supposed to — tracking a finding across
engagements even when the asset's name and IP change out from under it. In real
Kubernetes that happens constantly: pods get rescheduled, IPs get recycled, names
churn. The Deployment is the thing that's actually stable, so that's what we key on.

And then the opposite case: when something's genuinely fuzzy — a reused DNS name with
nothing solid behind it — it doesn't guess. It marks it ambiguous and flat-out
refuses to let it move any numbers. It'd rather say 'I don't know' than smash two
things together that might not be the same thing."

---

## 5 · Same facts, two lenses  (~1:15)

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

## 6 · The boring stuff that has to be right  (~0:40)

*(point at the `Correctness` block)*

"Real quick on the unglamorous stuff, because it just has to work. Send the same
event twice — no-op, nothing happens. Send a conflicting one — rejected. Ask for a
report before the data's actually complete — it blocks you. Shuffle the order
everything shows up in — you get the exact same report. Kill a transaction halfway
through — nothing's left behind, no half-written mess. Same facts, same answer, every
time. That's the part you have to be able to trust before any of the rest matters."

---

## 7 · Why I built this, and where it goes  (~1:05)

*(the `Scope` line is on screen)*

"So — why did I actually build this. Honestly, mostly for fun. I'm pretty sure the
folks at Armadin are already thinking about this, or already have something like it.
But I kept coming back to this idea that there's a ton of untapped power in giving
security systems real memory, and I just wanted to explore it myself.

And there's more I'd love to do with it. Integration — I found this open-source
project called T3MP3ST, a multi-agent offensive-security framework that runs the whole
kill chain, recon to exploit to reporting. Really cool, but it has no memory — every
run is a blank slate. Bolting Mahoraga onto something like that would be a natural
fit, and it'd let me show the whole loop end to end against a real target instead of
fixtures.

Architecture-wise, I built this as one app to keep it simple, but the memory flow
really wants to be split into pieces — a writer that owns ingestion, a reader that
serves memory back out, a separate report job — so each part can scale and fail on its
own. And then a longer list I find genuinely interesting: tracking how the environment
drifts over time, tradecraft memory for which techniques work against which stacks,
real tamper-evident provenance, and eventually the hard one — safely sharing learnings
across customers without leaking anyone's data. But the core — the memory engine, the
part that has to be correct — that's what I've got working right here."

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
