# Mahoraga Memory MVP — Demo Transcript

Narration transcript for the demo video, matching the conversational narration in
[`demo-script.md`](demo-script.md). Timecodes follow the script's ~7:35 budget.
Before acceptance, this transcript and [`demo-captions.vtt`](demo-captions.vtt)
get reconciled against what you actually say on the recording — they never
override the real narration.

> On-screen and spoken at the start: **Synthetic MVP — no customer data.**

---

**[0:00–0:35] Intro**

Hey — I'm Varun. [one line about you: what you do / your background.] I built this
thing called Mahoraga and I want to walk you through it. Short version: it's a
memory engine for offensive security. I built it as one small service — Java,
Postgres, a real transactional ingestion path — plus a set of deterministic
synthetic fixtures that play out two engagements against the same target. There's
no LLM anywhere in the core; it's all plain, reproducible logic, so everything
you're about to see comes out identical every single time I run it. Let me start
with the problem, then just run it.

**[0:35–1:05] The blank-notebook problem, and "this is fake data"**

First — and I'll keep saying this — everything here is fake data. It's a synthetic
demo, it is not wired into Armadin's real systems. Okay. The problem I care about:
you've got a swarm that attacks a customer, finds a bunch of stuff, writes it all
down — and then the engagement ends and it basically throws the notebook away. Next
quarter it shows up and it's starting from zero. It can't tell you if the bug it
found last time is still there, or got fixed, or got fixed and then came right back.
That gap — that's the whole thing I'm trying to fix. It's the memory.

**[1:05–2:00] What present is doing: it stubs the swarm and runs twice**

That preflight I just ran was just me being careful — it checks Java, Docker, the
build, the safety guards, and it changes nothing. Now this next command is the whole
trick, so let me tell you what it's actually doing. Think of it as standing in for the
swarm — the thing that would normally go run the attacks. Real engagements are messy
and random, so I've frozen the whole thing into a fixture: a fixed set of checks with
fixed results. That's on purpose — it means the only thing that can change between runs
is Mahoraga's memory. And it runs the entire second engagement twice — once with memory
off, once with memory on — each against its own clean database, from the exact same
starting point. So if anything's different at the end, you know it came from the memory
and nothing else. It's a controlled experiment. It loads Engagement 1 first, and draws
that hard line — everything we knew, as of right now. Keep that in mind, it matters in
a sec.

**[2:00–3:20] What T-A/T-B/T-C are, and the 3 → 1**

See these three up top — T-A, T-B, T-C? Those are just three checks the swarm could run
in the second engagement — basically "go re-test this, go re-test that." And to the
planner they're completely opaque; it's three IDs, it has no idea what any of them will
find. That part matters. So the planner's only job here is: what order do we run these
in? With memory off it's got nothing to go on, so it just runs them in order — A, B, C.
Turn memory on, and it looks back at Engagement 1 and notices one of these is pointing
at something we'd already confirmed fixed. And something you fixed coming back is exactly
what you want to check first — so it pulls that one to the front: C, A, B. Then both
plans actually run, same frozen results baked in — and here's the payoff: the bug that
came back, the memory version catches it on the very first action instead of the third.
Three down to one. And that's how you know it's the memory and not luck — both runs
started from the identical state, with the identical outcomes. The one and only
difference was whether the planner could see history. Same everything, better result —
that's the memory engine doing its job. And "zero events at planning" is just me proving
the planner never got to peek at the second engagement's answers.

**[3:20–4:15] Same thing after everything changed, and "I don't know"**

Between the two engagements the pod got a new ID, a new name, a new IP — basically
everything you'd normally key on changed. But the actual Deployment underneath is
the same, so we keep it as the same asset, and the finding stays stuck to it.
That's what lets you talk about a bug over time. And that's Mahoraga doing exactly
what it's supposed to — tracking a finding across engagements even when the asset's
name and IP change out from under it. In real Kubernetes that happens constantly: pods
get rescheduled, IPs get recycled, names churn. The Deployment is the thing that's
actually stable, so that's what we key on. And then the opposite case: when something's
genuinely fuzzy — a reused DNS name with nothing solid behind it — it doesn't guess. It
marks it ambiguous and flat-out refuses to let it move any numbers. It'd rather say "I
don't know" than smash two things together that might not be the same thing.

**[4:15–5:30] Same facts, two lenses**

Same exact Engagement 2 facts — I'm not touching the data — I'm just changing how
much history it's allowed to look at. With no memory, which is what a normal
point-in-time scan is, you see three detections, one not-detected, one partial. And
the finding nobody retested this time? It just isn't there. The scan literally
can't see it, because it has no idea it ever existed. Now turn the history back on,
same facts, and suddenly they mean something. One's new, one's still open, one is
actually verified fixed, one regressed, one nobody retested, one's inconclusive.
And that "verified fixed" is the one I care about — it only counts if we actually
reran the same check and it came back clean. Not seeing a bug is not the same as
the bug being gone. Only the memory version can tell you a fix came back, or that
you straight-up forgot to retest something.

**[5:30–6:10] The boring stuff that has to be right**

This is the unglamorous stuff that just has to work. Send the same event twice —
no-op, nothing happens. Send a conflicting one — rejected. Ask for a report before
the data's actually complete — it blocks you. Shuffle the order everything shows up
in — you get the exact same report. Kill a transaction halfway through — nothing's
left behind, no half-written mess. Same facts, same answer, every time. That's the
part you have to be able to trust before any of the rest matters.

**[6:10–7:15] Why I built this, and where it goes**

So — why did I actually build this. Honestly, mostly for fun. I'm pretty sure the
folks at Armadin are already thinking about this, or already have something like
it. But I kept coming back to this idea that there's a ton of untapped power in
giving security systems real memory, and I just wanted to explore it myself. It was
a genuinely interesting problem to sit with. And there's a bunch more I'd love to do
with it. A few things I'm excited about. Integration — I found this open-source
project called T3MP3ST, a multi-agent offensive-security framework that runs the
whole kill chain, recon, exploit, all the way to reporting. Really cool project, but
it has no memory — every run is a blank slate. Bolting Mahoraga onto something like
that would be a super natural fit, and it'd let me show the whole loop end to end
against a real target instead of fixtures. Then architecture — I built this as one
app to keep it simple, but the memory flow really wants to be split into separate
pieces: a writer that owns ingestion and the transaction, a reader that just serves
memory back out, a separate report job, a shared core. Pull those apart and each
part can scale and fail on its own. And then a longer list I find genuinely
interesting: tracking how the environment itself drifts over time, not just the
findings; tradecraft memory, which techniques actually worked against which kinds of
stacks; real tamper-evident provenance with a hash chain; and eventually the hard,
fascinating one — safely sharing learnings across customers without leaking anyone's
data. Each of those is its own project. But the core — the memory engine, the part
that has to be correct — that's what I've got working and proven right here.

**[7:15–7:35] Wrap up**

And that's it. Everything you just saw — the three-to-one, the identity stuff, the
six categories, all the correctness checks — none of it is hardcoded. It's all
computed from what actually happened in the run, and it comes out byte-for-byte
identical every single time I run it. Fake data, yeah. But a real result. Thanks
for watching.

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
