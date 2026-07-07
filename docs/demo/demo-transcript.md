# Mahoraga Memory MVP — Demo Transcript

Narration transcript for the demo video, matching the conversational narration in
[`demo-script.md`](demo-script.md). Timecodes follow the script's timing budget
(target 6:30). Before acceptance, this transcript and
[`demo-captions.vtt`](demo-captions.vtt) get reconciled against what you actually
say on the recording — they never override the real narration.

> On-screen and spoken at the start: **Synthetic MVP — no customer data.**

---

**[0:00–0:35] The blank-notebook problem, and "this is fake data"**

Okay, real quick before anything — everything here runs on fake data. It's a
synthetic demo, it is not hooked up to Armadin's actual systems. I just want to be
upfront about that. So, the problem I care about: you've got a swarm that attacks
a customer, finds a bunch of stuff, writes it all down — and then the engagement
ends and it basically throws the notebook away. Next quarter it shows up and it's
starting from zero. It can't tell you if the bug it found last time is still there,
or got fixed, or got fixed and then came right back. That's the whole thing I'm
trying to fix here. It's the memory.

**[0:35–1:05] One command, and the Engagement 1 line**

One command does the whole thing. This first part, preflight, is just me being
careful — it checks Java, Docker, that the build's there, that the safety guards
are good — and it doesn't change anything. Then it loads up Engagement 1. Every
fact it learns is its own row, locked down, scoped to the tenant. And when the
engagement's done, we draw a hard line that says "this is everything we knew, as of
right now." Hang on to that line, it matters in a second.

**[1:05–2:25] The plans can't cheat, and the 3 → 1**

Storing stuff is fine, whatever — the real question is whether the memory changes
what you do next. So there's a planner, and it runs at that Engagement 1 line,
before we know anything about the second engagement. And this is the part people
get wrong: it is so easy to accidentally let the future leak in — to let the
planner peek at answers it shouldn't have yet. So the planner only ever sees opaque
IDs. No labels, no results, nothing about how it turns out. With memory off, it
just goes in order: A, B, C. Turn memory on, and it knows one of these got fixed
last time and is worth double-checking, so it pulls that one to the front: C, A, B.
Then both plans actually run, against identical copies of the same starting point,
same outcomes baked in. And the bug that came back — memory catches it on the very
first move instead of the third. Three to one. And that "zero events at planning"
line is just me proving nothing leaked.

**[2:25–3:20] Same thing after everything changed, and "I don't know"**

Between the two engagements the pod got a new ID, a new name, a new IP — basically
everything you'd normally key on changed. But the actual Deployment underneath is
the same, so we keep it as the same asset, and the finding stays stuck to it.
That's what lets you talk about a bug across time. And then the opposite case: when
something's genuinely fuzzy — a reused DNS name with nothing solid behind it — it
doesn't guess. It marks it ambiguous and flat-out refuses to let it move any
numbers. It'd rather say "I don't know" than smash two things together that might
not be the same thing.

**[3:20–4:55] Same facts, two lenses**

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

**[4:55–5:45] The boring stuff that has to be right**

This is the unglamorous stuff that just has to work. Send the same event twice —
no-op, nothing happens. Send a conflicting one — rejected. Ask for a report before
the data's actually complete — it blocks you. Shuffle the order everything shows up
in — you get the exact same report. Kill a transaction halfway through — nothing's
left behind, no half-written mess. Same facts, same answer, every time. That's the
part you have to be able to trust before any of the rest matters.

**[5:45–6:35] Why Armadin cares, and what this isn't yet**

So why does Armadin care. This is the thing that turns a one-off scan into
something you'd actually pay for every quarter — you can prove a fix regressed, you
can prove something got verified instead of just not-seen, you can catch the stuff
nobody retested. And let me be straight about what this isn't: it's not
production-scale, it's not wired into Armadin, it doesn't do authorization or any
of the privacy or cross-tenant stuff — that's all on purpose, it's later. The way
this actually becomes real is you point a real swarm at a real target — Armadin's,
or honestly any of these agent frameworks — and you write one adapter that turns
its output into the same event contract you just watched go by. That adapter is
real work, it's not a one-liner. But the memory engine underneath it — that's the
part that's done, and that's what I just showed you.

**[6:35–7:00] Wrap up**

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
