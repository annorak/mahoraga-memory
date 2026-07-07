# Mahoraga Memory MVP — Demo Runbook

Operational companion to [`demo-script.md`](demo-script.md). It covers setup,
recording hygiene, the exact commands, expected evidence, safe recovery, and
post-run checks. The presenter runs only the two commands below and follows the
script; nothing here is improvised.

## Supported environment

- macOS or Linux on the supported baseline: **Java 21**, the bundled **Maven
  Wrapper** (`./mvnw`), and **Docker** with a running daemon.
- The pinned image present locally (the demo runs with `--pull=never`):
  `docker pull postgres:18.4-alpine`.
- Local TCP port `127.0.0.1:55432` free for the guarded demo database.
- Roughly 1 GB free disk for the image and build output.

## Recording setup

- Resolution **1920×1080**, terminal font **16–18 point minimum**, high-contrast
  theme, window sized so no line wraps.
- Disable all notifications (system, chat, mail, calendar). Enter a
  do-not-disturb/focus mode.
- Close unrelated windows, browser tabs, and any window showing credentials or
  another customer/project.
- Clear or scroll away sensitive terminal history before capture starts.
- Confirm the shell prompt shows no absolute home path or secret. Use a working
  directory that does not expose a private path on screen.
- Ensure the environment holds no real credentials; the demo generates an
  ephemeral database password per run and never prints it.

## Clean build and preflight

Run these before recording (not on camera):

```bash
./mvnw -q verify
scripts/demo.sh preflight
```

- `verify` must pass (exit 0).
- `preflight` must print `Preflight passed.` and change no state.

If a fresh JAR is needed, `./mvnw -q verify` (or `./mvnw -q package`) produces
`target/mahoraga-memory-0.1.0-SNAPSHOT.jar`; preflight requires it to exist.

## Presentation command

On camera, run exactly:

```bash
scripts/demo.sh preflight
scripts/demo.sh present
```

`present` runs the full guarded workflow once and streams these phases:

```
== Preflight ==
== Memory disabled ==
== Memory enabled ==
== Comparing Java evidence ==
== Normalized stakeholder proof ==
```

The final phase prints the normalized proof block and leaves it on screen. Do
not run `present` more than once per take, and do not type any other command
during recording.

## Expected evidence

After a clean run, the normalized transcript is byte-identical to the accepted
TASK-016B / TASK-017 evidence:

```bash
shasum -a 256 target/demo/transcript.txt
# f81d12c3c33f7a616b1e80b8ca9062d584a8a827bfa5dac7673a56499513ddd6
```

`target/demo/evidence.json` carries the same values in machine form and also
embeds the packaged-JAR build fingerprint, so its digest is build-specific while
the transcript stays build-independent. The transcript hash is the one that must
match across runs.

High-level output checkpoints (all in the printed transcript):

- `Memory disabled: [T-A, T-B, T-C]` and `Memory enabled: [T-C, T-A, T-B]`
- `Actions before regression detection: 3 -> 1`
- `Canonical Deployment unchanged: true`, `Weak-signal collision: AMBIGUOUS`
- Stateless `Detected: 3 / Not detected: 1 / Partial: 1`
- Memory-aware one of each: `NEW/STILL_OPEN/VERIFIED_RESOLVED/REGRESSED/NOT_RETESTED/INCONCLUSIVE`
- Correctness: `NO_OP`, `EVENT_CONTENT_REJECTED`, `UNFINALIZED_REPORT_BLOCKED`,
  shuffle equal `true`, partial state `false`

## Safe retry

Retry only through the guarded demo script — never by hand.

```bash
scripts/demo.sh preflight
scripts/demo.sh present
```

The script owns the entire container lifecycle. On a clean environment,
`present` starts, uses, and stops only a container that matches every guarded
property (name `mahoraga-memory-demo`, label `dev.mahoraga.memory.synthetic=true`,
image `postgres:18.4-alpine`, database `mahoraga_demo`, binding exactly
`127.0.0.1:55432`, ephemeral `tmpfs` only, restart `no`, default bridge network,
image-default entrypoint/command). Because storage is `tmpfs`, stopping the
container discards demo data — there is nothing to clean up by hand.

## Failure decision tree

1. **Stop.** Do not continue narrating past a failed command or a broken
   invariant, and do not improvise a fix on camera.
2. **Preserve diagnostics.** Read the printed error; a guard refusal prints
   `Manual recovery required for container ...` and stops without touching a
   non-matching container.
3. **Clean only through the validated guard.** Re-run `scripts/demo.sh preflight`.
   It safely recovers an orphaned container only when every guard still matches;
   otherwise it refuses and you inspect the container yourself.
4. **Re-run preflight** until it prints `Preflight passed.`.
5. **Restart from the beginning** of the script for a clean take. Never splice a
   prior successful run into a new take.

### Known environment note

On some Docker builds, `docker image inspect` can intermittently report the
pinned image as missing even though `docker image ls postgres` shows it. Preflight
correctly fails closed in that moment. Recovery is read-only and non-destructive:
confirm the image with `docker image ls postgres`, then re-run
`scripts/demo.sh preflight`. Do not re-pull or prune on camera.

## What not to improvise

- Do not run any wildcard Docker command, `docker system prune`, `docker volume`
  operation, or `docker rm -f` against an unguarded container.
- Do not delete a volume, drop a database, or hand-edit `target/demo/` files.
- Do not expose a credential, token, JDBC URL, or absolute private path.
- Do not run `present` twice in one take, reorder the script, or reveal the
  runner-only outcome map before the plans are shown.
- Do not continue after any failed invariant or unexpected output.

## Post-run checks

```bash
shasum -a 256 target/demo/transcript.txt   # equals the accepted hash above
docker container inspect mahoraga-memory-demo --format '{{.Id}}'  # expect: no such container
lsof -nP -iTCP:55432 -sTCP:LISTEN || echo "port free"            # expect: port free
```

Confirm the container was removed and the port is free, and that the recording
shows no notification, credential, unrelated window, or absolute private path.
