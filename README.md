# Mahoraga Memory — MVP

Mahoraga is a durable memory engine for repeated, authorized offensive-security
engagements. Without memory, every engagement is an isolated point-in-time scan
that cannot say whether a past weakness persists, was verified fixed, regressed,
or was simply never retested. This MVP proves the core differentiator with the
smallest thing that can prove it: **one Java application, one PostgreSQL
database, and checked-in synthetic fixtures** that demonstrate longitudinal
posture, stable identity across infrastructure churn, coverage-honest
resolution, six mutually exclusive finding classifications, a leakage-free
memory-aware planner, and reproducible reports.

> **Synthetic MVP demonstration.** All data is checked-in synthetic fixtures.
> This is a product-value demonstration, not a production, privacy, or
> security-boundary validation, and it does not integrate with any live system.
> See [Limitations and roadmap](#limitations-and-roadmap).

## What it demonstrates

Every claim below is produced by executed application behavior over persisted
facts — not printed from hard-coded expected values:

- **Stable Deployment identity** across Pod UID, name, and IP churn, with a
  weak-signal collision correctly withheld as `AMBIGUOUS`.
- **Exact source-event retry/conflict handling** — an identical retry is a
  no-op; a changed retry or reused stream position is rejected.
- **Coverage-aware verified resolution** — only a compatible, completed,
  not-detected test resolves a finding; absence never proves a fix.
- **Six longitudinal classifications** — `NEW`, `STILL_OPEN`,
  `VERIFIED_RESOLVED`, `REGRESSED`, `NOT_RETESTED`, `INCONCLUSIVE`.
- **Leakage-free deterministic planning** — the planner sees no Engagement 2
  outcomes, runner labels, or the eventual classification.
- **Executed `3 -> 1` regression-discovery improvement** — memory moves the
  regression check from action three to action one.
- **Reproducible reports and shuffled-ingestion convergence** — the same facts
  produce the same semantic report regardless of arrival order.

## Architecture

One Java 21 / Dropwizard application organized by capability, backed by one
PostgreSQL database. Checked-in synthetic `SourceEvent` fixtures stand in for a
future ingestion adapter. There are no microservices: the boxes below are
packages under `dev.mahoraga.memory`, not deployable services.

```
Versioned JSON fixtures ─▶ One Mahoraga application ─▶ PostgreSQL
   contract → ingest → identity/finding/coverage → boundary
            → posture fold → planning · reporting
```

Fold pipeline: immutable tenant-qualified facts are selected against an explicit
finalized **knowledge boundary**, ordered by domain chronology, then folded into
longitudinal posture and a deterministic next plan.

### Seven-table schema

The single Flyway migration
(`src/main/resources/db/migration/V1__create_mvp_schema.sql`) creates seven
tenant-qualified tables:

| Table | Owns |
|---|---|
| `engagements` | Stream-to-tenant/engagement binding and the finalized `last_data_sequence`. |
| `source_events` | Immutable source log and the inbox/dedup ledger with the server-computed canonical hash. |
| `assets` | Stable canonical Kubernetes Deployment identities. |
| `asset_observations` | Changing Pod/network evidence plus `RESOLVED`/`AMBIGUOUS` resolution outcome. |
| `findings` | Stable weakness identity and its coverage verification baseline. |
| `finding_occurrences` | Positive detection facts for a stable finding. |
| `test_attempts` | What was actually tested, including negative/partial/failed results. |

## Prerequisites

- **Java 21** (the build enforces `[21,22)`).
- **Maven** is not required system-wide; use the bundled **Maven Wrapper**
  (`./mvnw`). It does require Maven `>= 3.9` if you use your own.
- **Docker** with a running daemon, for the demo and for Testcontainers-backed
  integration tests.
- The pinned PostgreSQL image present locally. The demo starts containers with
  `--pull=never`, so pull it once first:

  ```bash
  docker pull postgres:18.4-alpine
  ```

- Roughly 1 GB free disk for the image and build output, and local TCP port
  `127.0.0.1:55432` free for the guarded demo database.

## Build, test, and run

```bash
# Full verification: unit + real-PostgreSQL (Testcontainers) integration tests.
./mvnw -q verify

# A focused test class, e.g. the six-scenario end-to-end proof.
./mvnw -q -Dtest=SixScenarioIntegrationTest test

# Produce the executable JAR at target/mahoraga-memory-0.1.0-SNAPSHOT.jar.
./mvnw -q package

# Help works with no database.
java -jar target/mahoraga-memory-0.1.0-SNAPSHOT.jar --help
```

`--help` lists the available commands (`server`, `check`, `demo`). The `demo`
command is guarded and is intended to be driven by `scripts/demo.sh`, which owns
the database lifecycle; do not invoke it directly.

### Run the demonstration

```bash
# Read-only environment and safety checks; changes no state.
scripts/demo.sh preflight

# Full run: two clean database lifecycles, evidence comparison, transcript.
scripts/demo.sh rehearse

# Same work with recording-friendly headings and pauses.
scripts/demo.sh present
```

Each run executes the memory-off and memory-on arms in separate clean database
lifecycles, compares Java-generated evidence, and writes:

```
target/demo/control-evidence.json
target/demo/memory-evidence.json
target/demo/evidence.json
target/demo/transcript.txt
```

The shell script only renders Java-produced evidence; it computes no
classifications, hashes, planner metrics, or report counts.

## Expected deterministic proof

A clean rehearsal prints `target/demo/transcript.txt`. The normalized transcript
(which excludes ports, container IDs, paths, timestamps, and credentials) is
byte-stable across runs:

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

`target/demo/evidence.json` carries the same values in machine-readable form and
additionally embeds the packaged-JAR build fingerprint, so its digest tracks the
exact build while the normalized transcript stays build-independent.

## Database and container safety

The demo never touches arbitrary Docker state. It only starts, reuses, or stops
a container that matches every one of these properties, and refuses (printing
manual-recovery guidance) on any mismatch:

- name `mahoraga-memory-demo`
- label `dev.mahoraga.memory.synthetic=true`
- image `postgres:18.4-alpine`
- database `mahoraga_demo`, host binding exactly `127.0.0.1:55432`
- ephemeral `tmpfs` storage only — no bind mounts, volumes, or inherited volumes
- restart policy `no`, default bridge network, image-default entrypoint/command

Signals (`INT`, `TERM`, `HUP`) and normal exit revalidate and stop the guarded
container, preserving a non-zero status on failure. A later `preflight` recovers
an orphaned container only when every guard still matches; otherwise it refuses.
Because storage is `tmpfs`, stopping the container discards all demo data — there
is nothing to clean up by hand in the normal path.

## Project layout

```
mahoraga-memory/
├── pom.xml                     # single Maven module
├── mvnw, mvnw.cmd, .mvn/       # Maven Wrapper
├── config/mahoraga.yml         # Dropwizard config; DB via env substitution
├── scripts/demo.sh             # guarded demo orchestration
├── src/main/java/dev/mahoraga/memory/
│   ├── MahoragaApplication.java # Dropwizard entry point and lifecycle
│   ├── config/                  # typed configuration and explicit Guice bindings
│   ├── contract/               # SourceEvent types, validation, canonical hashing
│   ├── database/               # Dropwizard/JDBI runtime and Flyway startup
│   ├── ingest/                 # inbox, stream binding, transaction, routing
│   ├── identity/               # canonical Deployment identity
│   ├── finding/                # stable finding identity and occurrences
│   ├── coverage/               # test attempts and compatibility policy v1
│   ├── boundary/               # engagement completion and knowledge boundaries
│   ├── posture/                # pure longitudinal fold and classifications
│   ├── planning/               # pre-engagement memory query and planner
│   ├── reporting/              # stateless/memory reports and digests
│   ├── fixture/                # synthetic dataset and runner-only manifest types
│   ├── demo/                   # executed proof collection and semantic evidence
│   └── commands/               # the guarded demo command
├── src/main/resources/
│   ├── db/migration/           # V1 seven-table Flyway schema
│   └── fixtures/v1/            # checked-in synthetic E1/E2 event datasets
└── src/test/                   # unit, PostgreSQL integration, and E2E tests
```

## How the pieces fit together

- **Identity** separates the stable Kubernetes Deployment (`cluster_id +
  resource_kind + resource_uid`) from changing Pod observations, so churn does
  not create a new asset. Findings key off the stable asset, so the same
  weakness stays recognizable across engagements.
- **Coverage** compares a test attempt to a finding's verification baseline
  (tenant, asset, verification key, exact check version and context, policy
  version). Only a compatible **completed, not-detected** attempt verifies
  resolution; ephemeral Pod IP changes stay compatible, but a changed port,
  route, or parameter does not.
- **Knowledge boundaries** are explicit finalized `(source_stream_id,
  last_data_sequence)` sets. Facts are filtered to the boundary *before*
  chronological ordering, which is what prevents future-data leakage into the
  planner and lets the same Engagement 2 facts render both a stateless
  (E2-only) and a memory-aware (E1 + E2) report with no second code path.
- **Reports** fold boundary-selected facts by domain chronology into the six
  classifications; the memory view adds regression detection, verified
  resolution, and honest missing-retest coverage that a stateless scan
  structurally cannot produce.

Deeper explanations live in [`mahoraga-mvp-junior-guide.md`](mahoraga-mvp-junior-guide.md).

## Acceptance coverage

Every MVP acceptance area is backed by named passing tests, exercised by
`./mvnw verify`:

| Acceptance area | Representative tests |
|---|---|
| Source-event identity | `contract/CanonicalSourceHashTest`, `contract/SourceEventValidatorTest`, `ingest/SourceEventIngestorTest` |
| Atomicity | `ingest/IngestionAtomicityTest`, `ingest/IngestionTransactionTest` |
| Ordering | `boundary/KnowledgeBoundaryTest`, `boundary/BoundaryFactQueryTest`, `posture/PostureOrderTest` |
| Asset/finding identity | `identity/AssetIdentityServiceTest`, `finding/FindingIdentityServiceTest`, `finding/RelevantContextFingerprintTest` |
| Coverage | `coverage/CoverageCompatibilityPolicyV1Test`, `coverage/TestAttemptServiceTest`, `coverage/TestAttemptCoverageQueryTest` |
| Steering | `planning/DeterministicPlannerTest`, `planning/PreEngagementMemoryQueryTest`, `planning/SteeringExperimentTest` |
| Completeness | `boundary/EngagementCompletionHandlerTest` |
| Rebuild / replay | `replay/ReplayReproducibilityTest` |
| Stateless vs memory contrast | `reporting/ReportServiceTest` |
| Report reproducibility | `reporting/ReportCanonicalizationTest` |
| End-to-end demonstration | `fixture/SixScenarioIntegrationTest`, `demo/DemoRunnerTest`, `commands/DemoCommandTest`, `demo/DemoScriptTest` |

## Troubleshooting

| Symptom | Likely cause | Non-destructive recovery |
|---|---|---|
| `the pinned PostgreSQL image is not available locally` | Image not pulled, or a transient daemon hiccup. | `docker pull postgres:18.4-alpine`; re-run `preflight`. |
| `the guarded local demo port is occupied` | Something is listening on `127.0.0.1:55432`. | Stop that process, or re-run once the port frees; the demo never rebinds. |
| `the Docker daemon is unavailable` | Docker not running. | Start Docker and re-run `preflight`. |
| `Java 21 is required` | Wrong JDK on `PATH`. | Select a Java 21 JDK and re-run. |
| Preflight reports a recoverable guarded orphan | A prior run left the exact guarded container. | Re-run; it is removed only after every guard matches. |
| `Manual recovery required for container ...` | A container holds the guarded name but does not match every safety property. | Inspect it yourself; the demo will not stop or delete a non-matching container. |
| Integration tests fail to start containers | Docker unavailable to Testcontainers. | Ensure the daemon is running; these tests do not silently skip. |

## Security and privacy

- All fixtures are **synthetic**. No customer data, credentials, or real
  infrastructure identifiers are present or required.
- Database credentials are supplied only through environment substitution
  (`MAHORAGA_DB_URL`, `MAHORAGA_DB_USER`, `MAHORAGA_DB_PASSWORD`); the demo
  generates an ephemeral password per run and never prints it.
- No secrets, tokens, or absolute machine paths are committed.

## Limitations and roadmap

This MVP intentionally does **not** provide, claim, or validate:

- Production scale, high availability, disaster recovery, SLOs, or
  rolling-deploy safety.
- Any live integration with an external system, or "zero-change" integration.
- Authorization recording or enforcement.
- Production privacy or security-boundary validation.
- Cross-tenant learning, tradecraft, or a cross-tenant gate.
- Storage-enforced RLS, tamper-evident/hash-chained provenance, MCP,
  embeddings/vector search, or a live Kubernetes cluster.
- Any testing against customer data.

The production direction for these — real source discovery and adapters, change
streams, evidence lifecycle, multi-tenant hardening, and gated cross-tenant
learning — is described in the design document's roadmap, not built here.

## Reviewer path

Read in this order:

1. [`mahoraga-mvp.md`](mahoraga-mvp.md) — authoritative product behavior and
   acceptance tests.
2. [`mahoraga-mvp-implementation-plan.md`](mahoraga-mvp-implementation-plan.md) —
   build decisions, task decomposition, and the final release gate.
3. [`mahoraga-design.md`](mahoraga-design.md) — the larger production direction
   and deferred-to-production register.
4. [`mahoraga-mvp-junior-guide.md`](mahoraga-mvp-junior-guide.md) — a
   first-principles walkthrough of every concept above.

Per-task scope and completion records live under `tasks/` (excluded from version
control by owner decision).
