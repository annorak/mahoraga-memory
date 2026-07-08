# Mahoraga Memory MVP — Rehearsal Log

Records clean rehearsals of `scripts/demo.sh present` used to validate that the
demonstration is deterministic, reproduces the accepted evidence, and cleans up
after itself. No absolute paths, credentials, container IDs, or secrets are
recorded here.

## Environment and identity

- **Date:** 2026-07-07 (UTC)
- **Application source revision:** `0e643f3` (merged `master`; this task changes
  only `docs/`, which does not affect the build)
- **Build fingerprint (packaged JAR SHA-256, embedded in `evidence.json`):**
  `b2fc787769e6320d56426ba31dc24cbb8a4baf03c689ff7238de7b8f6dd739e1`
- **Accepted normalized transcript SHA-256 (TASK-016B / TASK-017):**
  `f81d12c3c33f7a616b1e80b8ca9062d584a8a827bfa5dac7673a56499513ddd6`
- Prerequisites confirmed before rehearsing: `./mvnw -q verify` passed;
  `scripts/demo.sh preflight` printed `Preflight passed.`

## Rehearsals

Each run executed `scripts/demo.sh present` against clean guarded databases
(each arm in its own fresh container; no reuse of a prior transcript). Duration
is the raw `present` command wall-clock; see the note below on narrated timing.

| Run | Build fingerprint | Evidence (`evidence.json`) SHA-256 | Normalized transcript SHA-256 | Duration | Result |
|---|---|---|---|---:|---|
| 1 | `b2fc7877…d739e1` | `3627913d…8aaf24b` | `f81d12c3…9513ddd6` | 0:21 | PASS |
| 2 | `b2fc7877…d739e1` | `3627913d…8aaf24b` | `f81d12c3…9513ddd6` | 0:19 | PASS |
| 3 | `b2fc7877…d739e1` | `3627913d…8aaf24b` | `f81d12c3…9513ddd6` | 0:19 | PASS |

- All three normalized transcript hashes match each other **and** the accepted
  TASK-016B / TASK-017 hash.
- All three `evidence.json` hashes match each other (identical build; the JSON
  embeds the build fingerprint, so this digest is build-specific while the
  transcript is build-independent).
- After every run the guarded container was removed and port `127.0.0.1:55432`
  was free.

## Notes requiring script-only correction

- None. Story order, commands, output cues, and claims matched the accepted
  implementation. No application, schema, fixture, or evidence change was needed.
- Reworded one narration line to avoid the ambiguous word "authorized" (it could
  read as an authorization-enforcement claim, which the MVP does not make). This
  is a documentation-only wording change; evidence is unaffected.

## Timing caveat (honest scope of these rehearsals)

These are mechanical rehearsals: they drive `scripts/demo.sh present`
unnarrated to prove determinism, hash-stability, and clean teardown. The raw
command runtime (~0:20) is far below the eight-minute ceiling. The
[`demo-script.md`](demo-script.md) ~7:35 target is the **narrated** presentation
length — the presenter reading each segment at normal pace while `present`
streams its phases and prints the final proof block. That narrated timing is
validated by a presenter read-through during recording preparation (TASK-019);
it cannot be measured by an unnarrated command run.

## Transient environment observation

On one preflight during this session, `docker image inspect` briefly reported
the pinned `postgres:18.4-alpine` image as missing while `docker image ls`
showed it present; preflight correctly failed closed and a re-run succeeded. This
is a local Docker-daemon flake, not a demo defect. The recovery is read-only and
is documented in [`demo-runbook.md`](demo-runbook.md).
