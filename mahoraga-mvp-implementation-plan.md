# Mahoraga MVP Implementation Plan

Status: Approved  
Implementation language: Java  
Authoritative product specification: `mahoraga-mvp.md`  
Production-direction reference: `mahoraga-design.md`

---

## 1. Purpose

This plan divides the Mahoraga MVP into small, dependency-ordered tasks. Each task is intended to fit within one fresh Codex or Claude Code session without relying on context compaction.

The decomposition optimizes for:

- Small, reviewable diffs.
- One primary responsibility per task.
- Explicit prerequisites and handoffs.
- Production-relevant correctness where the MVP makes a correctness claim.
- No implementation of deferred production features.
- Tests delivered with the behavior they protect.
- A deterministic, repeatable demonstration rather than a manually staged prototype.

The master plan is the source of truth for task ordering and task status. Individual files under `tasks/` will contain the complete instructions for one implementation session.

## 2. Business outcome

Armadin currently performs engagements that are individually useful but do not create durable longitudinal knowledge for the next engagement.

The MVP will prove that Mahoraga can:

1. Remember findings and test coverage across engagements.
2. Recognize the same Kubernetes Deployment after Pod name, UID, and IP churn.
3. Distinguish a stable asset from an ambiguous weak-signal match.
4. Distinguish:
   - New findings.
   - Still-open findings.
   - Verified-resolved findings.
   - Regressed findings.
   - Findings that were not retested.
   - Inconclusive attempts.
5. Avoid treating absence as proof of remediation.
6. Use prior memory to move the relevant regression check from action three to action one.
7. Render the same Engagement 2 facts as:
   - A stateless point-in-time view.
   - A longitudinal memory-aware view.
8. Demonstrate that only the memory-aware view can identify regression, verified resolution, and missing retest coverage.

Together, these capabilities demonstrate the product value of continuous longitudinal security instead of repeated independent scans. This supports a recurring-engagement or retainer model by making every future engagement more informed and more efficient than the previous one.

## 3. Final MVP proof

The completed MVP must produce this deterministic evidence:

```text
Candidate tests: [T-A, T-B, T-C]
Memory disabled: [T-A, T-B, T-C]
Memory enabled:  [T-C, T-A, T-B]
Actions before regression detection: 3 -> 1

Stable identity:
  Pod UID/name/IP changed: true
  Canonical Deployment unchanged: true
  Weak-signal collision: AMBIGUOUS
  Posture changes from ambiguous observation: 0

Stateless E2 view:
  Detected: 3
  Not detected: 1
  Partial: 1
  Findings with no E2 fact: unrepresentable
  Longitudinal classifications: unavailable

Memory-aware E1 + E2 view:
  NEW: 1
  STILL_OPEN: 1
  VERIFIED_RESOLVED: 1
  REGRESSED: 1
  NOT_RETESTED: 1
  INCONCLUSIVE: 1

Correctness:
  Duplicate retry: NO_OP
  Conflicting duplicate: REJECTED
  Missing completion sequence: REPORT_BLOCKED
  Shuffled ingestion report hash equal: true
  Transaction failure leaves partial state: false
```

All values must be generated from executed application behavior and persisted facts. They must not be printed from hard-coded expected-output code.

## 4. Scope

### Included

- One Java application artifact.
- One PostgreSQL database per isolated execution.
- Typed internal `SourceEvent` models.
- Versioned JSON fixture files.
- Server-computed canonical event hashes.
- Seven-table MVP schema.
- Transactional synchronous ingestion.
- Tenant and engagement stream binding.
- Canonical Deployment identity.
- Finding matching across engagements.
- Test-attempt coverage compatibility.
- Knowledge boundaries and completion checks.
- Deterministic posture folding and classification.
- Stateless and memory-aware reports.
- Pre-engagement memory query.
- Deterministic planner.
- Executed memory-off and memory-on experiment.
- Replay, reordering, rollback, and reproducibility tests.
- Local CLI demo automation.
- Demo script, rehearsal guide, and demo video.

### Explicitly excluded

- Customer-facing or domain REST API.
- Web UI.
- Hosted deployment.
- Spanner, Dataflow, or Pub/Sub integration.
- Python/Java hybrid implementation.
- `maho-gate`.
- Cross-tenant learning.
- Tradecraft.
- Embeddings or vector search.
- Temporal topology.
- JPA or Hibernate.
- Production RLS role matrix.
- Evidence object storage.
- Hash-chained provenance.
- HA, DR, load testing, and production SLOs.
- MCP.

An excluded feature requires a separate approved task and must not be added opportunistically.

## 5. Language and stack decision

### Decision: Java

Java is the better choice for this MVP because:

- The primary implementation risks are transaction correctness, deterministic ordering, identity, and lifecycle state—not Python integration.
- Java records and enums make the event and lifecycle contracts explicit.
- The user can review Java changes faster and more confidently.
- PostgreSQL transaction behavior can be tested directly with mature Java tooling.
- Armadin's future Python integration is isolated behind the language-neutral JSON `SourceEvent` contract.
- A later Python adapter can consume the same checked-in contract fixtures without changing the memory core.

No Python service or duplicate Python model will be introduced in the core MVP.

### Minimal stack

| Concern | Decision |
|---|---|
| Java | Java 21 LTS |
| Build | Single Maven module with Maven Wrapper |
| Application | Dropwizard 5.0.x |
| Dependency injection | Plain Guice 7 with explicit modules and bindings |
| Configuration | Native Dropwizard YAML configuration and validation |
| SQL access | Dropwizard JDBI3 with concrete database collaborators |
| Transactions | Explicit JDBI transactions around one application operation |
| Database | PostgreSQL only |
| Migrations | Flyway SQL migrations |
| JSON | Dropwizard-managed Jackson with typed Java records |
| Hashing | JDK SHA-256 over one canonical JSON implementation |
| Logging | SLF4J and Logback through Dropwizard |
| Unit tests | JUnit 5; Mockito only for genuine interaction boundaries |
| Application tests | Dropwizard Testing |
| Database tests | Testcontainers PostgreSQL |
| CLI | Built-in Dropwizard commands plus only the custom commands required by the MVP |
| Packaging | Maven Shade producing one executable JAR |
| Demo database | Guarded `docker run` automation; no Docker Compose dependency |

Task 001 will pin Dropwizard, Guice, Maven plugin, and test versions. TASK-003A
will pin Flyway, PostgreSQL JDBC, Testcontainers, and the PostgreSQL test-image
version. TASK-003B will add the Dropwizard JDBI3 module at the version managed
by the Dropwizard 5.0.1 dependency BOM. Version changes after those tasks
require an explicit reviewed diff.

### Application conventions

- `MahoragaApplication` extends Dropwizard `Application<MahoragaConfiguration>`.
- Configuration uses standard Dropwizard YAML and Jakarta validation.
- The application creates one Guice injector from one explicit application module.
- Guice classpath scanning and automatic binding discovery are not used.
- `binder().requireExplicitBindings()` is enabled.
- Constructor injection is the default.
- `@Provides` methods are used only when construction requires configuration/lifecycle handling or the dependency has multiple consumers.
- Dropwizard resources, health checks, managed objects, and commands are registered explicitly.
- Application-owned Jersey resources are obtained from Guice and registered as instances.
- JDBI is created through Dropwizard's managed data-source integration once database support is introduced.
- Concrete database collaborators own SQL for their capability.
- Flyway is the single migration owner and runs before application SQL uses a schema.
- Long-lived resources are registered with the Dropwizard lifecycle.
- Logging uses parameterized SLF4J calls.
- Custom Dropwizard commands are added only for concrete MVP workflows.

### Deliberately not used

- No ORM.
- No H2.
- No Lombok.
- No MapStruct.
- No generic DAO or repository framework.
- No event-sourcing framework.
- No internal event bus.
- No multi-module Maven project.
- No Spring or Spring Boot.
- No Guicey.
- No Guice classpath scanning.
- No application-owned HK2 bindings.
- No customer-facing Jersey resource before a task requires one.
- No Liquibase.
- No shared raw JDBC connection.
- No reactive database access.
- No mocks for PostgreSQL correctness.
- No abstraction with only one implementation unless it represents an actual external boundary.

## 6. Initial repository facts

At planning time:

- `mahoraga-memory` is not a Git repository.
- Java 21 is installed.
- Maven 3.9 is installed.
- Docker is installed.
- Docker Compose is not installed.
- No application code or build files exist.
- The only authoritative inputs are `mahoraga-mvp.md` and `mahoraga-design.md`.

Git initialization and remote creation are not automatic. Task 001 must ask for explicit approval before initializing Git or configuring a remote.

## 7. Target repository shape

The target is one Maven module organized by capability:

```text
mahoraga-memory/
├── AGENTS.md
├── README.md
├── mahoraga-design.md
├── mahoraga-mvp.md
├── mahoraga-mvp-implementation-plan.md
├── mahoraga-mvp-junior-guide.md
├── pom.xml
├── mvnw, mvnw.cmd
├── .mvn/
├── config/
│   └── mahoraga.yml
├── scripts/
│   └── demo.sh
├── src/
│   ├── main/
│   │   ├── java/dev/mahoraga/memory/
│   │   │   ├── MahoragaApplication.java
│   │   │   ├── config/
│   │   │   ├── contract/
│   │   │   ├── database/
│   │   │   ├── ingest/
│   │   │   ├── identity/
│   │   │   ├── finding/
│   │   │   ├── coverage/
│   │   │   ├── boundary/
│   │   │   ├── posture/
│   │   │   ├── planning/
│   │   │   ├── reporting/
│   │   │   ├── fixture/
│   │   │   ├── demo/
│   │   │   └── commands/
│   │   └── resources/
│   │       ├── db/migration/
│   │       └── fixtures/v1/
│   └── test/
│       ├── java/dev/mahoraga/memory/
│       └── resources/
├── docs/
│   └── demo/
└── tasks/
```

This is a package structure inside one application, not a set of deployable services or Maven modules.

The neutral base package `dev.mahoraga.memory` is the default because use of an Armadin-owned Java domain has not been authorized.

## 8. Single-session task rules

Every task must satisfy these execution rules:

1. Read:
   - The assigned task file.
   - Relevant sections of `mahoraga-mvp.md`.
   - Relevant sections of `mahoraga-design.md`.
   - This master plan.
2. Inspect the actual repository state rather than trusting only prior completion notes.
3. Verify prerequisite behavior before proposing changes.
4. Show the complete proposed diff and wait for approval.
5. Implement only the approved scope.
6. Add or update tests in the same task as the behavior.
7. Run targeted tests and the full currently available suite.
8. Record exact commands and outcomes in the task's completion record.
9. Do not commit or push without separate explicit approval.
10. Stop if the task requires changing an authoritative contract.

### Session-size limits

A task should normally introduce:

- One primary behavior or architectural concept.
- No more than one database migration.
- No more than approximately eight production files.
- No more than approximately eight test files.
- No unrelated refactoring.

If the implementation cannot remain within that boundary, the session must stop and propose splitting the task.

## 9. Dependency chain

The core path is intentionally mostly linear to keep review and handoff simple:

```text
001 Foundation
  └─▶ 002 Event contracts
       └─▶ 003A PostgreSQL schema
            └─▶ 003B Database runtime wiring
                 └─▶ 004 Source inbox and transactions
                      └─▶ 005 Asset identity
                           └─▶ 006 Finding identity
                                └─▶ 007 Coverage attempts
                                     └─▶ 008A Ingestion completion
                                          └─▶ 008B Knowledge boundaries
                                               └─▶ 009 Posture fold
                                                    └─▶ 010A Fixture contracts and loader
                                                         └─▶ 010B Fixture datasets
                                                              ├─▶ 011 Reports ─┐
                                                              └─▶ 012 Planner ─┴─▶ 013 Steering experiment
                                                                                    └─▶ 014 Atomicity hardening
                                                                                         └─▶ 015 Replay
                                                                                              └─▶ 016A Demo evidence
                                                                                                   └─▶ 016B Demo harness
                                                                                                        └─▶ 017 Release
                                                                                                             └─▶ 018 Script
                                                                                                                  └─▶ 019 Video
```

Tasks 011 and 012 both require Task 010B. They may be implemented in either
order, but both must complete before Task 013.

## 10. Task index

| ID | Task | Depends on | Primary value |
|---|---|---|---|
| 001 | Project foundation and deterministic toolchain | None | Repeatable, reviewable development baseline |
| 002 | Typed SourceEvent contract and canonical hashing | 001 | Trustworthy, language-neutral ingestion boundary |
| 003A | PostgreSQL schema and constraint-test harness | 002 | Durable invariant-enforcing storage |
| 003B | Dropwizard database lifecycle and JDBI wiring | 003A | Safe migration-first application startup |
| 004 | Source-event inbox, stream binding, and transaction shell | 003B | Safe retry and engagement isolation |
| 005 | Canonical Deployment identity and asset observations | 004 | Continuity across Pod/IP churn |
| 006 | Finding identity and occurrences | 005 | The same weakness remains recognizable across engagements |
| 007 | Test attempts and coverage compatibility | 006 | Absence cannot be misreported as remediation |
| 008A | Ingestion composition and engagement completion | 007 | Explicit routing and durable complete-stream boundaries |
| 008B | Canonical knowledge boundaries and fact selection | 008A | Reproducible as-of state without future leakage |
| 009 | Posture fold and six classifications | 008B | Longitudinal security meaning |
| 010A | Fixture contracts, loader, and planner-safe projection | 009 | Strict separation of source, runner, and planner data |
| 010B | Synthetic fixture datasets and scenario proof | 010A | Deterministic proof data |
| 011 | Deterministic reports and stateless/memory contrast | 010B | Customer-visible longitudinal value |
| 012 | Pre-engagement memory query and planner | 010B | Prior memory changes the next plan |
| 013 | Executed steering experiment | 011, 012 | Causal proof of `3 -> 1` improvement |
| 014 | Atomicity and rollback hardening | 013 | Partial failures cannot corrupt memory |
| 015 | Replay, shuffle, and report reproducibility | 014 | Durable deterministic behavior |
| 016A | Java demo command and normalized evidence | 015 | Business proof derived from persisted execution |
| 016B | Guarded demo orchestration and transcript | 016A | Repeatable stakeholder demonstration |
| 017 | Clean-room release verification and reviewer handoff | 016B | Another engineer can reproduce the MVP |
| 018 | Demo script, runbook, and rehearsals | 017 | Accurate and concise product narrative |
| 019 | Record and package the demo video | 018 | Shareable proof for Armadin stakeholders |

## 11. Detailed task plan

### TASK-001 — Project foundation and deterministic toolchain

Outcome:

- A minimal Java 21 Maven application can build, test, package, and display CLI help without a database.

Scope:

- Maven Wrapper.
- Single-module `pom.xml`.
- Java 21 compiler and test configuration.
- Dropwizard application/configuration baseline.
- One explicit Guice application module.
- Maven Shade executable-JAR configuration.
- Neutral package and application entry point.
- `.gitignore`.
- Minimal configuration and test skeleton.
- Dependency/version policy.

Success criteria:

- `./mvnw test` passes.
- `./mvnw verify` passes.
- The packaged JAR starts and displays usage/help without requiring PostgreSQL.
- A Dropwizard server smoke test starts on ephemeral ports and shuts down cleanly.
- No customer-facing resource is registered.
- Dependency tree contains no Spring, JPA, Hibernate, H2, Guicey, JDBI, or Flyway.

Tests:

- Dropwizard application bootstrap test.
- Guice explicit-binding test.
- Maven Enforcer check for Java and Maven requirements.
- Packaged-JAR smoke command.
- Dependency-tree review.

Non-goals:

- No database.
- No domain models.
- No ingestion behavior.
- No domain REST resource.
- No Git initialization without separate approval.

### TASK-002 — Typed SourceEvent contract and canonical hashing

Outcome:

- Versioned JSON fixture events parse into typed Java models and receive a deterministic server-computed SHA-256 hash.

Scope:

- `SourceEvent`.
- `EventType`.
- Typed payload records for:
  - Asset observation.
  - Finding observation.
  - Test attempt.
  - Engagement completion.
- One shared typed relevant-context value used by findings and attempts.
- Trusted fixture context.
- Input validation.
- PostgreSQL-compatible microsecond timestamp validation.
- Canonical JSON serializer.
- Canonical hash service.
- Schema-version ownership.

Success criteria:

- Equivalent typed values produce identical canonical bytes and hashes.
- Field-order differences do not affect the hash.
- Changes to `occurred_at`, `schema_version`, sequence, event type, or payload affect the hash.
- Finding and attempt payloads carry the same validated check-context inputs.
- Timestamps finer than PostgreSQL's microsecond precision are rejected rather than truncated.
- Unknown event types and unsupported schema versions fail clearly.
- Fixture-provided hashes are ignored or rejected.

Tests:

- JSON parsing tests for every event type.
- Canonical ordering tests.
- Hash stability golden tests.
- Hash-difference tests for every semantic field.
- Required-field and malformed-input tests.
- Shared relevant-context and address-bound validation tests.
- Timestamp-precision boundary tests.
- Canonicalization-version tests.

Non-goals:

- No database persistence.
- No identity resolution.
- No generic plugin architecture for event versions.

### TASK-003A — PostgreSQL schema and constraint-test harness

Outcome:

- The seven-table MVP schema is created by Flyway and its invariants are tested against real PostgreSQL.

Scope:

- Add PostgreSQL JDBC, Flyway, and Testcontainers dependencies.
- One initial Flyway migration for:
  - `source_events`.
  - `engagements`.
  - `assets`.
  - `asset_observations`.
  - `findings`.
  - `finding_occurrences`.
  - `test_attempts`.
- Tenant-qualified primary and unique keys.
- Foreign keys.
- `timestamptz(6)` storage for source-event chronology.
- Immutable finding verification-baseline columns for check version, relevant-context hash, and compatibility-policy version.
- Only the `RESOLVED` and `AMBIGUOUS` asset-resolution states implemented by the MVP.
- Legal status/result checks.
- Shared PostgreSQL integration-test support.
- JDBC-based schema tests only; runtime JDBI wiring belongs to TASK-003B.

Success criteria:

- A new PostgreSQL database migrates from empty to current.
- All seven tables and required constraints exist.
- No optional projection or eighth table is introduced.
- Source timestamps round-trip without precision loss.
- The database can persist both sides of the exact coverage predicate.
- Undefined provisional-asset states cannot be persisted accidentally.
- Illegal status/result combinations fail at the database boundary.
- Tests use PostgreSQL rather than H2.
- A second Flyway migration run applies zero migrations and validates successfully.

Tests:

- Empty-database migration test.
- Constraint tests for tenant keys, source identity, asset identity, finding matching, and test-result legality.
- Foreign-key tests.
- Migration rerun/idempotence test.
- Microsecond timestamp round-trip test.

Non-goals:

- No Dropwizard database configuration.
- No JDBI.
- No Guice database binding.
- No application-startup migration wiring.
- No repositories or business behavior.
- No production RLS.
- No report-version table.

### TASK-003B — Dropwizard database lifecycle and JDBI wiring

Outcome:

- The application owns one Dropwizard-managed PostgreSQL pool, runs Flyway
  synchronously before database use, and exposes one explicitly bound `Jdbi`
  instance to later capabilities.

Scope:

- Add the Dropwizard JDBI3 module.
- Add a validated Dropwizard `DataSourceFactory`.
- Load database URL, user, and password through environment substitution.
- Build one `ManagedDataSource`.
- Run Flyway against that data source before constructing database consumers.
- Pass the same data source to `JdbiFactory`.
- Bind the resulting `Jdbi` instance explicitly in Guice.
- Preserve database-free packaged-JAR help behavior.
- Update application smoke tests to use PostgreSQL.

Success criteria:

- Server startup on an empty database applies V1 before any application SQL.
- Flyway and JDBI do not create separate long-lived pools.
- Migration validation failure aborts startup.
- An unavailable database fails startup within configured timeouts.
- `Jdbi` is injectable, while the managed data source remains the lifecycle-owned resource.
- Shutdown closes the database pool.
- Packaged-JAR `--help` requires no database.

Tests:

- Empty-database application startup.
- Migration-validation failure abort.
- Unavailable-database bounded failure.
- Explicit Guice binding.
- JDBI transaction rollback and retry.
- Connection return after failed and successful handles.
- Application shutdown and pool closure.
- Existing TASK-001 and TASK-002 regressions.

Non-goals:

- No schema changes beyond TASK-003A's migration.
- No repositories or domain SQL.
- No ingestion behavior.
- No custom migration command.
- No second data-source abstraction or Guice provider hierarchy.

### TASK-004 — Source-event inbox, stream binding, and transaction shell

Outcome:

- Source events are accepted exactly once, conflicting retries are rejected, and streams cannot cross tenant or engagement boundaries.

Scope:

- Concrete JDBI source-event collaborator.
- Engagement stream registration.
- Explicit JDBI transaction around one ingestion operation.
- Canonical hash persistence.
- Duplicate/no-op result.
- Conflict result.
- Direct validation errors.
- A narrow public `DatabaseWork` callback receiving the active JDBI `Handle`.
- Deterministic database-only downstream work inside the source transaction.
- A separate test-only failure-injection seam.

Success criteria:

- Same tenant/event ID and same hash returns `NO_OP`.
- Same tenant/event ID and different content is rejected.
- Same tenant/stream/sequence with another ID or content is rejected.
- A stream cannot be reused for another tenant or engagement.
- Event insertion and downstream work share one transaction.
- Callback failure rolls back source and derived rows.
- External side effects are prohibited inside transactional work.

Tests:

- First insert.
- Exact duplicate.
- Content conflict.
- Stream-position conflict.
- Cross-tenant stream reuse.
- Cross-engagement stream reuse.
- Rollback immediately after inbox insertion.
- Retry after rollback.

Non-goals:

- No assets, findings, or test attempts yet.
- No asynchronous queue.
- No quarantine or DLQ.

### TASK-005 — Canonical Deployment identity and asset observations

Outcome:

- Mahoraga recognizes one Deployment across changing Pod observations and safely withholds ambiguous weak matches.

Scope:

- Canonical asset value objects.
- Authoritative Deployment-key resolution.
- Atomic asset insert-or-read.
- Asset-observation persistence.
- `RESOLVED` and `AMBIGUOUS` paths used by the MVP.
- Resolution basis and policy version.
- Weak-signal collision behavior.

Success criteria:

- First authoritative Deployment observation creates one confirmed asset.
- Later observations with the same cluster/kind/resource UID reuse it.
- Pod UID, name, and IP changes do not create another asset.
- A weak match without authoritative UID becomes `AMBIGUOUS`.
- An ambiguous observation does not create posture facts.

Tests:

- First authoritative creation.
- Repeat authoritative resolution.
- Pod churn.
- Same weak DNS/label across distinct authoritative assets.
- Missing UID weak collision.
- Authoritative UID wins over weak ambiguity.
- Tenant isolation.
- Transaction rollback and retry.

Non-goals:

- No merge/split history.
- No review UI.
- No Kubernetes kinds beyond Deployment.

### TASK-006 — Finding identity and occurrences

Outcome:

- Engagement 2 finding observations correlate to the Engagement 1 finding without carrying an internal finding ID.

Scope:

- Finding match-policy v1.
- Normalized location signature.
- Atomic finding insert-or-read.
- Finding occurrence persistence.
- Immutable finding verification baseline:
  - Verification key.
  - Check version.
  - Compatibility-policy version.
  - Server-computed relevant-context hash.
- Relevant-context canonicalization and SHA-256 fingerprint policy v1.
- Conflict handling when an existing finding identity is observed with a different verification baseline.
- No `F-*` labels in ingested events.

Success criteria:

- The same stable asset, vulnerability class, normalized location, and policy version produce one finding.
- A second engagement maps to the recorded finding ID without receiving that ID as input.
- Different match components produce different findings.
- Every detection remains an immutable occurrence.
- Equivalent relevant contexts produce the same fingerprint.
- An unbound target address does not affect the fingerprint.
- An address-bound target address does affect the fingerprint.
- Reobserving the same finding identity with a different verification key, check version, context fingerprint, or policy version is rejected rather than silently mutating the finding.
- Fixture-only labels never cross the ingestion boundary.

Tests:

- First finding creation.
- Same-finding correlation across engagements.
- One-dimension mismatch cases.
- Relevant-context canonicalization and golden SHA-256 tests.
- Bound-address and unbound-address fingerprint tests.
- Verification-baseline conflict tests.
- Tenant isolation.
- Fresh-database sequential shuffle.
- Rollback and retry.
- Search proving no fixture labels appear in source-event payloads.

Non-goals:

- No fuzzy matching.
- No embeddings.
- No finding merge or split.
- No evolving verification baseline for one finding identity; that requires an explicit versioned model after the MVP.
- No validity/treatment commands.

### TASK-007 — Test attempts and coverage compatibility

Outcome:

- Mahoraga records what was actually tested and resolves a finding only from a compatible completed negative.

Scope:

- Test-attempt input and persistence.
- Independent authoritative target resolution.
- Execution status and result enums.
- Reuse of the relevant-context fingerprint policy introduced by TASK-006.
- Server-computed attempt fingerprint persisted with the attempt.
- Coverage compatibility policy v1.
- Joining attempts to findings by asset and verification key.

Success criteria:

- A test attempt may arrive before its finding.
- Pod/IP churn remains compatible for Deployment-level checks.
- Changed asset, check key, version, port, route, parameters, or policy is incompatible.
- Only `COMPLETED + NOT_DETECTED + compatible` verifies resolution.
- Failed, blocked, partial, skipped, absent, or incompatible attempts never resolve.

Tests:

- Full compatibility positive case.
- One-dimension mismatch matrix.
- Attempt fingerprint equality with the corresponding finding baseline.
- Changed ephemeral IP remains compatible.
- Changed port/route/parameter is incompatible.
- Every execution-status/result legal combination.
- Every forbidden combination.
- Attempt-before-finding ordering.

Non-goals:

- No remediation claims.
- No MTTR.
- No address-independent rule for explicitly address-bound checks.

### TASK-008A — Ingestion composition and engagement completion

Outcome:

- The four source-event types route through one explicit transaction path, and
  an engagement becomes finalized only after its complete stream is present.

Scope:

- One concrete `SourceEventIngestor` with an exhaustive four-case switch.
- Same-handle composition through TASK-004's `DatabaseWork`.
- Engagement completion event handling.
- Contiguous `1..N` verification.
- Completion marker at `N+1`.
- Pending completion markers and transactional gap-fill reevaluation.
- Write-once `engagements.last_data_sequence`.
- Rejection of data beyond a pending marker or any event after finalization.

Success criteria:

- Every event routes to exactly one concrete handler in the source transaction.
- A missing earlier sequence blocks finalization.
- Supplying the missing event later finalizes without marker replay.
- A rejected event beyond a pending marker writes nothing.
- Restart recovers pending completion from durable rows.
- Overlapping sequence numbers in different streams remain isolated.

Tests:

- All four event-type routes.
- Complete stream.
- Missing first, middle, and final data sequence.
- Marker with incorrect `last_data_sequence`.
- Position beyond a pending marker.
- Event after finalization.
- Restart between marker and gap fill.
- Handler and finalization rollback.
- Two streams with overlapping sequence numbers.

Non-goals:

- No knowledge-boundary query.
- No partition-vector production boundary.
- No generic dispatcher, event bus, queue, or background gap scanner.

### TASK-008B — Canonical knowledge boundaries and fact selection

Outcome:

- Planner and report queries see exactly the finalized source positions
  requested and cannot leak future facts.

Scope:

- Immutable sorted `(source_stream_id, last_data_sequence)` boundary value.
- Exact canonical boundary JSON and SHA-256 digest.
- Tenant-qualified, set-based boundary fact selection.
- Typed finding-occurrence and test-attempt facts for folding/reporting.

Success criteria:

- Planner boundary includes E1 and zero E2 outcomes.
- E2 memory boundary can include E1 and E2.
- E2-only stateless boundary excludes E1.
- A late backdated event beyond a selected position remains invisible.
- Caller order does not affect canonical bytes, equality, or digest.

Tests:

- Exact canonical JSON and reviewed golden digest.
- Empty, duplicate, invalid, and unfinalized boundary rejection.
- Cross-tenant isolation.
- Overlapping sequence numbers in different streams.
- Late backdated fact exclusion.
- E2-only attempts remain available without synthesizing findings.

Non-goals:

- No completion writes, fold, report, planner, or boundary table.
- No per-stream N+1 query or production partition-vector abstraction.

### TASK-009 — Pure posture fold and six classifications

Outcome:

- Ordered facts deterministically produce lifecycle state and the six mutually exclusive report classifications.

Scope:

- Pure fold input and output models.
- Domain ordering.
- Last verified exposure.
- Current-engagement assessment and episode flags.
- Classification precedence.
- No projection table.

Success criteria:

- All six scenarios classify exactly as specified.
- `NOT_RETESTED` preserves prior open exposure.
- A failed or partial attempt cannot resolve.
- Prior verified resolution followed by detection becomes `REGRESSED`.
- Prior open followed by compatible negative becomes `VERIFIED_RESOLVED`.
- Episode flags are raised only by facts from the explicit current engagement.
- With no current fact, the episode is `NOT_RETESTED` regardless of carried
  historical exposure.
- A strict E2-only view does not invoke the longitudinal fold.
- Arrival order does not affect the fold result.

Tests:

- One focused unit test per classification.
- Precedence collision tests.
- Ordering/tie-break tests.
- Detection then negative.
- Negative then later detection.
- Incomplete-only attempts.
- No-attempt history.
- Historical regression/closure followed by a no-fact current engagement.
- Detected attempt without its required finding occurrence.
- Property-style permutation test over small fact sets.

Non-goals:

- No database projection.
- No recurring classification.
- No composite-history demo scenario.

### TASK-010A — Fixture contracts, loader, and planner-safe projection

Outcome:

- Strict fixture and runner contracts prevent labels and future outcomes from
  crossing into ingestion or planner inputs.

Scope:

- Typed event-dataset wrapper using the production source codec.
- Runner-only manifest.
- A distinct planner-safe candidate projection.
- Typed fixture loader.
- Structural and cross-reference validation.

Success criteria:

- Candidate IDs are only `T-A`, `T-B`, and `T-C`.
- `F-*` and frozen outcomes remain runner-only.
- Planner projection contains only trusted tenant, candidate ID, authoritative
  Deployment target, verification key, and budget.
- Production ingestion and planner signatures cannot accept the manifest.

Tests:

- Strict parse and negative schema cases.
- Event ID and stream-position uniqueness.
- Manifest references valid events.
- Source-payload tree search for `F-*`/`T-*` leakage.
- Reflection/serialization proof of the safe planner projection.

Non-goals:

- No full E1/E2 scenario dataset or database ingestion proof.
- No planner, report, demo command, or generic resource-loader framework.

### TASK-010B — Synthetic fixture datasets and scenario proof

Outcome:

- Checked-in synthetic E1/E2 fixtures prove all six scenarios, Pod churn,
  ambiguity, completion, and leakage-free planner inputs.

Scope:

- E1, E2 planner-driven, E2 background, and completion event datasets.
- Frozen runner outcomes and reviewed semantic digest.
- Full ingestion through production code.
- Six-scenario integration proof.

Success criteria:

- E1 leaves the future regression in verified-resolved state.
- No E2 fact exists at the planner boundary.
- E2 produces exactly one of each six longitudinal classifications.
- `T-A` and `T-C` each execute a finding observation and a completed/detected
  attempt; detected attempts never synthesize occurrences.
- `F-UNTESTED` has no E2 occurrence or attempt.
- Pod UID/name/IP change while authoritative Deployment identity remains stable.
- The weak collision is ambiguous and changes no posture-fact count.

Tests:

- Strict parse, reference, contiguity, completion, and leakage checks.
- Golden semantic fixture digest.
- Planner projection contains no labels, outcomes, references, or E2 facts.
- Full PostgreSQL ingestion and exact six-classification proof.
- Canonical Deployment continuity and ambiguity isolation.

Non-goals:

- No live Kubernetes cluster, real data, planner algorithm, report, or demo.
- No fixture-specific branch in production services.

### TASK-011 — Deterministic reports and stateless/memory contrast

Outcome:

- The same finalized E2 facts render a stateless report and a longitudinal report through knowledge-boundary selection.

Scope:

- Reuse TASK-008B's boundary-aware fact query for both views.
- Canonical current-E2 semantic fact set and digest.
- Canonical report DTO.
- Human-readable report renderer.
- Canonical JSON renderer.
- Stable semantic report digest.
- Stateless E2-only view.
- Memory-aware E1+E2 view.

Success criteria:

- Memory report contains exactly one of each six classification.
- Stateless E2 report contains exactly `3 detected`, `1 not detected`, and
  `1 partial`.
- `F-UNTESTED` is absent/unrepresentable in the stateless report.
- Stateless reporting cannot emit longitudinal classifications or use an E1
  subject roster.
- Both reports use the same E2 fact digest.
- Internal random UUIDs do not affect canonical semantic comparison.
- Generated timestamps and formatting do not affect report hashes.

Tests:

- Exact six-scenario report.
- Stateless expected counts.
- Same-E2-facts assertion.
- Boundary isolation.
- Canonical JSON golden test.
- Semantic digest repeatability.
- Internal-ID substitution test.

Non-goals:

- No persisted `report_versions`.
- No PDF.
- No HTML UI.

### TASK-012 — Pre-engagement memory query and deterministic planner

Outcome:

- A planner can use only pre-E2 memory to order candidate checks deterministically.

Scope:

- Trusted tenant and explicit pre-engagement knowledge boundary.
- TASK-010A's planner-safe candidates with authoritative Deployment targets.
- Boundary-selected history folded through TASK-009.
- Memory feature model.
- Memory-off ordering.
- Memory-on ordering.
- Deterministic tie-break.
- Action budget.

Success criteria:

- Memory-off order is `[T-A, T-B, T-C]`.
- Memory-on order is `[T-C, T-A, T-B]`.
- The only behavioral difference is supplied memory features.
- No E2 result, runner label, or outcome map can enter planner input.
- `has_prior_verified_resolution` is true only when the boundary-folded last
  exposure remains `VERIFIED_RESOLVED`, not merely because any historical
  negative exists.
- A matching verification key on another asset supplies no memory feature.
- Planner output is stable across runs.

Tests:

- Exact baseline order.
- Exact memory order.
- Empty-memory behavior.
- Historical negative followed by reopening yields false.
- Same key on another asset and another tenant yields false.
- Equal-score tie-break.
- Budget enforcement.
- Boundary excludes all E2 outcomes.
- Reflection/serialization test proving planner input has no forbidden fields.

Non-goals:

- No LLM.
- No scoring configuration framework.
- No severity/recency/cost model until required by another scenario.

### TASK-013 — Executed steering experiment

Outcome:

- Both plans execute against isolated copies of the same finalized E1 state and prove regression discovery improves from action three to action one.

Scope:

- One-arm runner against a caller-supplied clean database.
- Pure comparator over normalized control and memory evidence.
- Sequential control and memory invocation by later orchestration.
- Replay and finalize E1 independently for each arm.
- Execute the same frozen outcomes in returned order.
- Ingest planner-driven and background E2 events.
- Derive actions-before-regression from persisted causative source-event
  linkage after all E2 facts commit.

Success criteria:

- Both executions begin from semantically identical E1 state.
- Both receive the same candidates, budget, and frozen outcomes.
- Both finish with the same semantic E2 outcome set.
- One process owns only one database pool at a time.
- Regression is detected at step three without memory.
- Regression is detected at step one with memory.
- The metric is not inferred from fixture labels, report text, or a partial run.

Tests:

- Isolated-state verification.
- E1 digest equality.
- Candidate/outcome equality.
- Exact executed order.
- Exact `3 -> 1` metric.
- Metric derivation after full E2 persistence.
- Equal final E2 semantic facts.
- Failure if outcome map is exposed before planning.

Non-goals:

- No physical database snapshot technology if replay provides an exact isolated state.
- No concurrent execution requirement.

### TASK-014 — Atomicity and rollback hardening

Outcome:

- Every multi-step ingestion operation either commits completely or leaves no partial state.

Scope:

- Test-only failure injection at critical transaction stages.
- Rollback verification after:
  - Source-event insert.
  - Canonical-asset insert/read.
  - Asset-observation insert.
  - Finding insert/read.
  - Finding-occurrence insert.
  - Test-attempt insert.
  - Engagement finalization.
  - Final pre-return stage.
- Retry after rollback.
- A normal-server no-op hook; only TASK-016A may construct a guarded one-shot
  synthetic hook.

Success criteria:

- Every injected failure leaves the database semantically unchanged.
- A retry after rollback succeeds exactly once.
- No failed event becomes a false duplicate.
- Source event and derived rows commit together.
- Failure injection cannot be activated by YAML, environment, or server mode.

Tests:

- One integration test for every failure point.
- Row-count and semantic-state verification.
- Retry tests.
- Constraint failure rollback.
- Connection/resource cleanup.

Non-goals:

- No Pub/Sub crash matrix.
- No outbox.
- No background retries.

### TASK-015 — Replay, shuffle, and reproducibility

Outcome:

- Recorded facts can be replayed and shuffled without changing semantic results.

Scope:

- Fresh-database sequential shuffle test.
- Duplicate replay.
- Fact-derived rebuild path.
- Canonical semantic comparison.
- Stable IDs when rebuilding the same recorded database.
- Stable semantic keys across separate fresh databases.

Success criteria:

- Shuffled source arrival produces the same canonical report.
- Exact duplicate replay is a no-op.
- Rebuild reruns no fuzzy identity matching.
- Same-database rebuild preserves recorded IDs.
- Fresh-database runs may have different internal UUIDs but equal semantic report hashes.

Tests:

- Multiple deterministic shuffle seeds.
- Duplicate-every-event replay.
- Rebuild from recorded facts.
- Cross-database semantic comparison.
- E1/E2 overlapping sequence test.
- Report digest repeatability.

Non-goals:

- No concurrent-ingestion claim.
- No production projector framework.

### TASK-016A — Java demo command and normalized evidence

Outcome:

- A guarded Java command executes one experiment arm and emits normalized,
  machine-verifiable evidence derived from persisted behavior.

Scope:

- A configured Dropwizard `demo` command.
- One-arm execution against a supplied clean synthetic database.
- Persisted-order, report, identity, ambiguity, replay, and rollback evidence.
- Pure control/memory evidence comparison.
- Atomic evidence-file publication.
- An independently enforced direct-command safety guard.

Success criteria:

- Control and memory evidence come from separate clean invocations.
- Pure comparison proves equal controlled inputs/facts and exact `3 -> 1`.
- The rollback probe changes none of the seven table states.
- Direct invocation rejects remote, incorrectly named, or unguarded databases.
- Demo orchestration calls application services and duplicates no business rules.

Tests:

- Separate control/memory arm outputs.
- Persisted orders and causative metric derivation.
- Evidence comparison rejection matrix.
- Rollback one-shot and normal-server no-op behavior.
- Direct-command safety-negative tests.
- Golden normalized evidence JSON.

Non-goals:

- No shell/container lifecycle, transcript, video, REST, or UI.
- No second pool in one process or production fault flag.

### TASK-016B — Guarded demo orchestration and transcript

Outcome:

- One guarded local script sequentially runs both demo arms and produces
  deterministic human and machine evidence.

Scope:

- `scripts/demo.sh preflight`, `rehearse`, and `present`.
- Sequential fresh control and memory database lifecycles using `docker run`.
- Full container safety guard, signal handling, and exact orphan recovery.
- Java command invocation, transcript rendering, and repeat-run comparison.

Success criteria:

- Only one application process, pool, and database exists at a time.
- Reset/cleanup requires exact container name, synthetic label, image, database,
  loopback port binding, command, network/restart policy, and no mounts/volumes.
- Unsafe or partially matching containers are refused.
- Two clean rehearsals produce byte-equal normalized evidence and transcript.
- Shell code renders Java evidence but contains no domain rules.

Tests:

- Preflight success/failure paths.
- Every one-field safety-guard mismatch.
- Mount, volume, non-loopback, restart, network, and command mismatch.
- INT/TERM/HUP cleanup and unsafe-orphan refusal.
- Arm, compare, and build-fingerprint failures.
- Golden uncolored transcript and byte-equal second run.

Non-goals:

- No video recording, REST/UI, Compose, remote database, package installation,
  broad cleanup, or semantic calculation in shell.

### TASK-017 — Clean-room release verification and reviewer handoff

Outcome:

- Another engineer can build, test, run, and understand the MVP without prior session context.

Scope:

- Complete `README.md`.
- Prerequisites.
- Architecture summary.
- Build/test/demo commands.
- Database startup and cleanup.
- Troubleshooting.
- Synthetic-data and MVP disclaimers.
- Clean-directory reproduction.
- `git archive` for a clean reviewed revision, or an explicit positive allowlist
  when the tree is dirty or no Git repository exists.
- Final acceptance evidence.

Success criteria:

- Every documented command is copy/paste tested.
- A clean-directory run passes `verify`, preflight, and rehearse.
- README distinguishes MVP claims from production roadmap.
- No secret, machine-specific absolute path, or customer data is required.
- Every MVP acceptance area maps to passing tests.

Tests:

- Clean-directory build.
- Packaged-JAR run.
- Fresh database migration.
- Full Maven verification.
- Demo preflight and rehearsal.
- Documentation command audit.

If a product defect appears, this task opens a focused corrective task rather than silently expanding its scope.

### TASK-018 — Demo script, runbook, and rehearsals

Outcome:

- A reviewer-approved 6–7 minute script accurately explains the product value and executed evidence.

Scope:

- `docs/demo/demo-script.md`.
- `docs/demo/demo-runbook.md`.
- Story beats, commands, expected output, narration, and timing.
- Recording setup.
- Recovery/troubleshooting.
- Claims and claims-to-avoid.
- Three clean rehearsals.

Required story:

1. Blank-notebook problem.
2. Honest synthetic MVP scope.
3. E1 ingestion and final boundary.
4. No E2 leakage at planner time.
5. Memory-off vs memory-on plans.
6. Executed `3 -> 1` regression result.
7. Stable identity under Pod/IP churn.
8. Ambiguous weak match withheld.
9. Same E2 facts, stateless vs memory report.
10. Idempotency and shuffled-order confidence.
11. Armadin business value and honest roadmap.

Success criteria:

- Total expected duration is under eight minutes.
- Commands match the current application revision.
- Three rehearsals complete successfully.
- Rehearsal output hashes match.
- No unsupported production, authorization, privacy, or integration claim appears.
- The script never reveals the frozen outcome map before planning.

Tests:

- Execute every command in the runbook.
- Validate expected output snippets.
- Record rehearsal durations.
- Compare evidence hashes.
- Review narrative against `mahoraga-mvp.md` §15.

### TASK-019 — Record and package the demo video

Outcome:

- A shareable, reviewed video demonstrates the complete MVP without exposing secrets or overstating scope.

Scope:

- Preflight the exact source revision.
- Run the guided presentation.
- Human-assisted narration and screen capture.
- Export MP4.
- Generate captions and transcript.
- Record checksum, source revision, and artifact location.
- Full playback review.

Default format:

- 1920×1080.
- 30 fps.
- H.264 MP4.
- 16–18 point terminal font.
- User narration with synchronized captions.
- Under eight minutes.
- Visible “synthetic data only” disclosure.

Success criteria:

- Text is legible.
- Audio is intelligible and synchronized.
- Captions match narration.
- No notifications, credentials, customer data, or unrelated windows appear.
- Commands and results match the recorded source revision.
- The entire exported video is watched before acceptance.
- SHA-256 and artifact location are recorded.

The MP4 should not be committed to ordinary Git history. The default is an ignored local `artifacts/demo/` directory or external artifact storage. Only the script, transcript, captions, checksum, revision, and artifact link belong in normal source control.

## 12. Acceptance coverage map

| MVP acceptance area | Primary tasks |
|---|---|
| Source-event identity | 002, 004, 014 |
| Atomicity | 004, 014 |
| Ordering | 008A, 008B, 009, 015 |
| Asset/finding identity | 005, 006, 015 |
| Coverage | 007, 009 |
| Steering | 012, 013 |
| Completeness | 008A |
| Rebuild | 015 |
| Stateless vs memory value contrast | 011 |
| Report reproducibility | 011, 015 |
| End-to-end demonstration | 016A, 016B, 017–019 |

No MVP acceptance criterion is considered complete solely because a unit test exists. Database-sensitive behavior requires real PostgreSQL integration tests, and business claims require end-to-end executed evidence.

## 13. Individual task-file standard

Each `tasks/NNN-*.md` file must contain:

1. Task metadata:
   - ID.
   - Title.
   - Status.
   - Dependencies.
   - Expected session size.
2. One-sentence outcome.
3. Business value.
4. Required reading with exact document sections.
5. Expected repository state before starting.
6. Commands that verify prerequisites.
7. In-scope work.
8. Explicit non-goals.
9. Contracts and invariants owned by the task.
10. Fixed decisions.
11. Decisions that require user approval.
12. Expected files or capabilities touched.
13. Incremental implementation sequence.
14. Partial-failure, retry, and restart considerations.
15. Detailed testing matrix.
16. Exact validation commands.
17. Binary acceptance checklist.
18. Suggested diff-review order.
19. Completion record to fill in at task end:
    - Files changed.
    - Tests executed and results.
    - Approved deviations.
    - Remaining risks.
    - Artifacts produced.
20. Next-task handoff.
21. Fresh-session kickoff prompt.

The task file must provide enough bounded context for a fresh coding session while linking to authoritative documents instead of copying the entire architecture.

## 14. Task completion states

Each task uses one status:

- `NOT_STARTED`
- `IN_PROGRESS`
- `BLOCKED`
- `READY_FOR_REVIEW`
- `COMPLETE`

A task becomes `COMPLETE` only when:

- Its approved implementation is applied.
- Its tests pass.
- The full existing suite passes.
- The user has reviewed the diff.
- Its completion record is filled in.
- No unresolved correctness issue is hidden in a later task.

## 15. Final MVP release gate

The MVP is complete only when:

- Every task ID in the task index, including the A/B splits, is complete.
- `./mvnw verify` passes from a clean directory.
- The demo preflight and rehearsal pass from a clean synthetic database.
- The exact six memory classifications are produced.
- The exact stateless contrast is produced from the same E2 facts.
- The executed planner result is `3 -> 1`.
- Duplicate, rollback, gap, replay, and shuffle checks pass.
- The README and demo runbook commands are verified.
- The video, captions, transcript, checksum, revision, and artifact location are reviewed.
- No deferred feature is represented as implemented.

## 16. Confirmed defaults

The following defaults were approved with this plan:

1. The MVP remains a local terminal demonstration; no REST or hosting.
2. The Java package is `dev.mahoraga.memory`.
3. The project is a standalone Maven repository.
4. Git initialization will be separately approved during Task 001.
5. The demo targets a mixed Armadin engineering/product audience.
6. The user narrates the video; captions and transcript are also produced.
7. The MP4 is stored outside ordinary Git history.

Changing any of these defaults may alter Tasks 001, 016A, 016B, 018, or 019
but does not change the core memory architecture.
