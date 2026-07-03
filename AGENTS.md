# Mahoraga Coding Agent Instructions

## Purpose

These instructions apply to the entire Mahoraga repository.

Act as a principal software engineer. Prefer simple, direct, contract-driven
solutions over enterprise abstractions. Keep changes small, reviewable, and
owned by the component responsible for the behavior.

Approval, security, and Git controls in this file always apply. For functional
implementation details, follow this precedence:

1. The user's explicit instructions and approved decisions.
2. The current task file and prerequisite completion records.
3. `mahoraga-mvp-implementation-plan.md`.
4. `mahoraga-mvp.md`.
5. `mahoraga-design.md`.
6. General style guidance in this file.

If authoritative documents conflict, stop, explain the conflict, and request a
decision. Do not silently choose one interpretation.

## Required Workflow

Use this sequence for every task:

```text
UNDERSTAND -> PLAN -> CONFIRM -> IMPLEMENT -> VALIDATE
```

Before coding:

1. Read the complete current task file.
2. Read every prerequisite completion record and referenced specification.
3. Inspect the actual repository, nearby production code, and nearby tests.
4. Verify that planning-time assumptions still match the repository.
5. Identify the component contract and critical invariant being changed.
6. Propose the smallest implementation plan.
7. Show the complete proposed diff and wait for explicit approval.

During implementation:

- Apply only the approved changes.
- Work incrementally and keep the diff focused.
- Explain non-obvious decisions and preserved invariants.
- Stop if repository state, requirements, or required scope differs materially
  from the approved plan.

After implementation:

- Run the current task's exact validation commands.
- Run the applicable formatter and lint checks.
- Run focused tests and the full existing `./mvnw verify` suite.
- Fill in the task completion record.
- Report changed files, commands, results, deviations, artifacts, and risks.

## File Modification Approval

- Never modify, create, rename, or delete repository files before showing the
  complete proposed diff and receiving explicit approval.
- Approval for one diff does not authorize unrelated changes.
- If implementation requires a materially different diff, stop and show the
  revised proposal.
- Prefer editing existing files over creating new ones.
- Do not perform unrelated cleanup, refactoring, reformatting, or renaming.

## Git Controls

- Never initialize Git without explicit approval.
- Never configure or change a remote without explicit approval.
- Never commit without explicit approval.
- Never push without explicit approval.
- Never use destructive Git operations unless the user explicitly requests and
  approves the exact operation.

Before a commit, show:

```text
Files to commit:
| Status | File | Change Summary |
|--------|------|----------------|
| M/A/D  | path | Brief description |

Commit message:
type(scope): description

Proceed? (yes/no)
```

Commit messages use:

```text
type(scope): description
```

Allowed types:

```text
feat | fix | docs | style | refactor | test | chore
```

## Terminal Commands

- Show commands before executing them.
- Explain non-trivial commands and expected side effects.
- Do not run destructive commands without explicit confirmation.
- Do not install packages or introduce dependencies without approval.
- Use the Maven Wrapper once it exists.
- Prefer non-interactive commands.
- Use `rg` and `rg --files` for repository searches.
- Never expose secrets, credentials, tokens, or full sensitive connection
  strings in command output.

## Task Scope

- Implement only the current task.
- Do not introduce behavior, configuration, abstractions, dependencies, or
  files owned by later tasks.
- Do not refactor unrelated code.
- Preserve previously passing behavior unless the current task explicitly
  changes a contract.
- Add tests in the same task as the behavior they verify.
- Do not hide unresolved correctness problems in a later task.
- If the task cannot fit within one focused coding session, stop and propose a
  smaller split.

## Approved Technology Baseline

Use the versions pinned by the repository and current task:

- Java 21.
- One Maven module with Maven Wrapper.
- Dropwizard.
- Guice with explicit bindings.
- PostgreSQL.
- JDBI3.
- Flyway.
- Jackson managed through Dropwizard.
- Jakarta Validation.
- JUnit 5.
- Testcontainers for real PostgreSQL integration tests.
- SLF4J and Logback through Dropwizard.

Do not:

- Add another application framework or dependency-injection framework.
- Create additional Maven modules.
- Add a Python/Java hybrid to the core MVP.
- Add a generic repository abstraction for one concrete implementation.
- Add a dependency for behavior available clearly in the JDK or existing stack.
- Copy package names, configuration conventions, or domain types from another
  repository.

The base Java package is:

```text
dev.mahoraga.memory
```

Do not use an Armadin-owned package namespace without explicit authorization.

## Simplicity and Design

Apply these principles in order:

1. Correctness and explicit contracts.
2. KISS: choose the simplest solution that works.
3. YAGNI: do not build unrequested flexibility.
4. DRY: keep one source of truth for each piece of knowledge.
5. SOLID where it makes the implementation clearer rather than larger.

In practice:

- Prefer one concrete implementation until a second real implementation exists.
- Prefer direct construction for a simple single-use dependency.
- Prefer explicit branching over reflective registries or plugin systems.
- Prefer boundary validation over speculative internal fallback behavior.
- Prefer durable state over file presence, cache presence, or in-memory progress.
- Prefer strong invariants over heuristics.
- Preserve caller intent; reject invalid input rather than silently changing it.
- Do not add optional parameters for values that are actually required.
- Do not add defensive branches for states excluded by validated types.
- Name intentional sentinels and test their behavior.
- Avoid magic values; use named constants for domain rules.
- Avoid configuration until a concrete requirement needs configurability.
- Avoid premature caching, batching, concurrency, or optimization.

Use design patterns as vocabulary, not ceremony:

- Use an Adapter only for a real external boundary.
- Use a Factory or Guice provider only when construction or lifecycle warrants it.
- Use Strategy only when multiple concrete algorithms currently exist.
- Use a Facade only when it creates a genuinely smaller subsystem boundary.
- Do not introduce a pattern merely because it may be useful later.

## Component Ownership

Organize by capability rather than by technical layer alone.

- Source contracts own parsing, validation, canonicalization, and hashing.
- Ingestion owns source-event deduplication, routing, transactions, completion,
  and retry behavior.
- Identity owns canonical assets, findings, and their matching policies.
- Posture owns the pure longitudinal fold and classification rules.
- Boundary queries own finalized-position visibility and fact selection.
- Planning owns memory feature construction and deterministic action ordering.
- Reporting owns semantic report models, rendering, and report digests.
- Fixture and demo code owns synthetic scenario labels and frozen outcomes.

Do not leak:

- Runner labels or frozen outcomes into ingestion, planning, posture, or reports.
- SQL or Dropwizard lifecycle behavior into pure posture logic.
- Report rendering rules into persistence.
- Fixture-specific branches into production domain services.
- Service-level lifecycle into small domain value types.

## Package and File Organization

- Organize code by capability under `dev.mahoraga.memory`.
- Do not create broad `utils`, `common`, `misc`, or dumping-ground packages.
- Add a shared primitive only after at least two capabilities genuinely need it.
- Keep tightly related small records together when that improves readability.
- Use domain-specific names; avoid meaningless suffixes such as `Dto`.
- Keep real runtime contracts, implementations, commands, configuration, and
  task-required runtime fixtures under `src/main`.
- Keep test-only fakes, helpers, data builders, and fixtures under `src/test`.
- Follow the current task when it explicitly requires runtime fixtures under
  `src/main/resources`.
- Check in E2E Java behavior as source; scripts must invoke checked-in code
  rather than generate Java source dynamically.
- Keep shell scripts orchestration-only; do not duplicate domain rules in shell.

Code structure limits:

| Element | Limit |
|---|---:|
| Method length | 30 lines |
| Java file length | 300 lines |
| Nesting depth | 3 levels |
| Distinct concepts per method | 7 |

If a cohesive implementation cannot satisfy these limits, stop and explain why
rather than splitting it into arbitrary indirection. Generated files, migration
DDL, fixtures, and documentation may exceed code limits when the format requires
it and the result remains reviewable.

## Java Style

- Use Java 21 language features where they make contracts clearer.
- Prefer immutable records and enums for genuine value contracts.
- Prefer final fields and constructor injection.
- Name classes as nouns and methods as verbs.
- Name booleans with forms such as `isReady`, `hasHistory`, `canRetry`, or
  `shouldPersist`.
- Use descriptive domain names rather than generic `Manager`, `Helper`, or
  `Processor` names.
- Keep methods direct and skimmable.
- Avoid deeply chained transformations when straightforward control flow is
  clearer.
- Do not return null where the contract has a clearer required result.
- Do not put null values into immutable collection factories.

Use Google Java Style as the baseline. The repository-local formatter,
Checkstyle configuration, and suppressions are authoritative once present.
Do not create or replace formatting/lint infrastructure unless the current task
explicitly authorizes it. Do not reformat unrelated files.

Comments should be sparse and explanatory:

- Explain why an invariant, ordering rule, or unusual choice exists.
- Do not restate a method name or obvious code.
- Do not put task numbers or review conversation into production comments.
- Use ordinary professional prose rather than forced lowercase comments.

## Configuration

- Use native Dropwizard YAML configuration and Jakarta Validation.
- Use the approved `config/mahoraga.yml` structure and task-specific test YAML.
- Do not introduce HOCON `.conf`, `base.conf`, or `test.conf` conventions.
- Add only configuration required by the current task.
- Parse and validate configuration once at startup.
- Pass the smallest typed nested configuration to the component that owns it.
- Read configuration once per method and assign meaningful local variables.
- Prefer explicit values when they affect security, persistence, networking, or
  durability.
- Use environment substitution for secrets or deployment-specific values.
- Never commit credentials, API keys, or real customer connection information.
- Fail startup on missing, empty, or malformed required configuration.
- Do not introduce a feature flag without an active use case and tests for both
  enabled and disabled behavior.

## Guice, Construction, and Lifecycle

- Require explicit Guice bindings.
- Use `@Provides` when a dependency has multiple consumers or requires explicit
  lifecycle, configuration, or scoping.
- Construct a simple single-use dependency locally when a provider adds only
  indirection.
- Centralize feature guarding where the object is introduced.
- Do not construct external clients, SDK clients, schedulers, watchers, or
  background resources unless the active path uses them.
- Disabled paths must have zero background-resource footprint.
- Register process-lifetime resources with Dropwizard lifecycle management.
- Start resources only after their dependencies are ready.
- Stop owned resources in reverse ownership order.
- Close handles, streams, iterators, executors, and clients at the layer that
  opened them.
- Do not add custom lifecycle abstractions when Dropwizard already owns the
  lifecycle.

For database support:

- Use one Dropwizard-managed data source and connection pool.
- Use that same data source for Flyway and JDBI.
- Run Flyway before any database-using component starts.
- Abort startup if migration fails.
- Keep packaged `--help` and other database-free commands database-free.

## Critical Mahoraga Invariants

Do not weaken these contracts:

- Every tenant-owned key and query is tenant-qualified.
- Source-event identity and stream-position identity are distinct constraints.
- Exact duplicate source events are no-ops.
- Conflicting duplicate IDs or stream positions are rejected.
- Source-event persistence and all derived writes share one transaction.
- Transactional callbacks perform deterministic database work only; no external
  side effects occur inside the transaction.
- Facts retain their source-event linkage.
- Domain ordering uses validated effective time and deterministic source
  tie-breakers, never `recorded_at`.
- Knowledge boundaries use finalized stream positions and cannot leak future
  events.
- A missing observation is not evidence of remediation.
- Only a compatible completed negative attempt can verify resolution.
- Ambiguous identity has no posture effect.
- Rebuild and replay use recorded facts and identities rather than rerunning
  fuzzy matching.
- Canonical hashes and semantic digests exclude operationally random data.

The authoritative task and design documents define the complete versions of
these invariants.

## Critical-Path Reasoning

Before changing ingestion, identity, completion, persistence, replay, planning,
or report semantics, explicitly answer:

- What is the component's critical operation?
- What durable or system-level contract does it participate in?
- What happens if only part of the operation succeeds?
- What happens on retry, restart, deploy, rollback, and mixed-version execution?
- What changes for callers, downstream consumers, operators, and recovery?
- Does the change preserve ordering, idempotency, durability, startup behavior,
  tenant isolation, and observability?

Before calling the change complete, ask:

- Does this address the root cause or only one symptom?
- What new failure modes does it introduce?
- Does it increase normal-case cost to address a rare edge case?
- Is this the smallest change that closes the correctness gap?
- Can the invariant be proved through a durable boundary and a test?

## Error Handling and Security

- Validate all external input at the boundary.
- Use parameterized SQL exclusively.
- Enforce tenant isolation server-side and in every database query.
- Fail loudly on impossible internal states.
- Never silently swallow exceptions.
- Handle errors at the boundary with enough context to classify them.
- Include stable identifiers in errors without including full payloads.
- Never log secrets, credentials, tokens, full JDBC URLs, or customer data.
- Bound input sizes, nesting, retries, queues, loops, and timeouts.
- Fail closed when security-sensitive configuration or identity is ambiguous.
- Do not weaken validation to make a test or fixture pass.

## Logging, Metrics, and Operability

- Use SLF4J parameterized messages.
- Keep startup logs concise and explicit.
- Include stable identifiers useful for triage, such as tenant-safe event,
  stream, engagement, or source IDs.
- Do not log complete source payloads.
- Add logs, metrics, tracing, or health behavior only when the current task
  introduces an actionable operational path.
- Measure meaningful work units and failures, not arbitrary method calls.
- Avoid noisy logging in loops.
- Health checks should represent real dependency/progress health rather than
  process existence alone.

## Performance and Resource Safety

- Do not optimize without a measured or task-defined need.
- Avoid N+1 database queries.
- Prefer set-based SQL for bounded collections and knowledge boundaries.
- Use batching only when required by the task and preserve ordering semantics.
- Do not create unbounded queues, retry loops, thread pools, or collections.
- Set explicit connection and external-operation timeouts.
- Keep retries bounded unless durable progress explicitly cannot advance until
  the operation succeeds.
- Ensure retries are idempotent.
- Ensure every resource is closed on success and failure.
- Do not claim concurrent safety when only sequential behavior has been tested.

## Testing Standards

- Add or update tests with every behavior change.
- Test the owning class or capability directly.
- Use unit tests for pure domain logic.
- Use real PostgreSQL through Testcontainers for database semantics.
- Do not mock transaction, constraint, timestamp-precision, or PostgreSQL
  behavior that the test is intended to prove.
- Do not silently skip required integration tests when Docker is unavailable.
- Test the invariant, not only the happy path.
- Include negative, edge, retry, restart, rollback, and partial-failure cases
  where applicable.
- Test enabled and disabled paths for a real feature flag.
- Test exact ordering and deterministic tie-break behavior.
- Use golden vectors only for stable canonical contracts.
- Preserve all previously passing unit, integration, contract, and E2E behavior
  unless an approved task changes it.
- Test migrations from a fresh database and safe replay through Flyway.
- Keep test-only helpers and fault injection out of normal runtime paths.

Validation order:

1. Repository-configured formatting.
2. Repository-configured lint and Checkstyle.
3. Focused unit tests.
4. Focused PostgreSQL integration tests.
5. Current task validation commands.
6. Full `./mvnw verify`.
7. Relevant packaged-JAR, replay, or demo verification when it exists.

If a required check cannot run, report the exact command, failure, and reason.
Do not claim the task is complete.

## Review Checklist

Before presenting implementation as done, verify:

- [ ] The change belongs to the current task and owning capability.
- [ ] The implementation has one clear responsibility.
- [ ] It is the simplest solution that satisfies the contract.
- [ ] No future behavior or speculative abstraction was introduced.
- [ ] No knowledge is duplicated across Java, SQL, configuration, or scripts.
- [ ] Public/wire/schema changes are compatible or explicitly versioned.
- [ ] Partial failure and restart behavior preserve durable state.
- [ ] Ordering, idempotency, replay, and rollback behavior are preserved.
- [ ] Tenant isolation and parameterized SQL are enforced.
- [ ] Secrets and sensitive payloads are not logged.
- [ ] External clients and background resources start only when required.
- [ ] Resources are bounded and closed.
- [ ] Focused tests prove the important invariant.
- [ ] The full existing suite passes.
- [ ] No unrelated formatting or refactoring appears in the diff.
- [ ] The task completion record is accurate and complete.

## Completion Record

At the end of every task, record:

- Files changed.
- Commands executed.
- Tests and results.
- Formatting and lint results.
- Approved deviations.
- Remaining risks.
- Artifacts or digests produced.
- Next-task handoff information.

A task is ready for review only when its approved implementation is applied,
all required validation passes, and its completion record is filled in.

Do not commit or push as part of task completion without separate approval.
