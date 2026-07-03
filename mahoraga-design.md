# Mahoraga — Design Document

**A memory engine for AI-driven offensive security.**

Status: Production design in draft (revisions from the latest review applied) · Owner: Varun · Audience: engineering, security, product
Companion build spec: `mahoraga-mvp.md`.

---

## What changed in this revision

Applied the latest review's specification corrections (no services added or removed):

- **Production integration never publishes pre-resolved `DomainEvent`s.** The Spanner adapter (inside Dataflow) emits `SourceEvent`s; only the orchestrator's transaction produces `DomainEvent`s.
- **Source and domain events have distinct identifiers.** `source_event_id` (causation link) vs `domain_event_id`; one source event may yield many domain facts.
- **`SourceEvent` is self-describing** via an `event_type` discriminator.
- **Canonical event hashing is defined and server-computed** (`canonical_source_hash`); a producer-supplied hash is never trusted.
- **Source streams are bound to trusted tenant/engagement ownership;** reuse under another tenant/engagement is rejected.
- **Domain ordering is reconstructable:** `effective_at` is the domain chronology; source stream, sequence, and a deterministic domain ordinal are tie-breakers; `recorded_at` is operational and never alters historical posture.
- **Concurrent aggregate folds serialize safely from the first write:** database identity constraints make concurrent observations converge, and `aggregate_heads` is initialized before it is locked.
- **Kubernetes kinds are modeled correctly:** Deployment/StatefulSet own Pods; a Service routes to Pods; canonical identity is `cluster_id + resource_kind + resource_uid`. Findings attach to the appropriate kind.
- **Coverage compatibility is an executable predicate** (policy version 1), with a check-specific context fingerprint that distinguishes stable logical identity from ephemeral addresses.
- **One production write path:** frontend commands use stable command IDs and are converted to internal `SourceEvent`s on a server-owned command stream; the frontend never grows an independent writer.
- **The report identity is a report-writer** (insert-only on `report_versions`, no update/delete), not "read-only."
- **Production completeness is partition-aware** and persisted behind one canonical boundary representation rather than assuming a scalar watermark.
- **Provenance heads initialize safely under concurrency** (`INSERT … ON CONFLICT DO NOTHING` then `FOR UPDATE`).
- **Evidence transitions and deletion behavior are defined** for readiness, controlled deletion, and unexpected object loss.
- **Topology wording, tech-map MVP column, tradecraft wording, and authorization/provenance wording are reconciled** with `mahoraga-mvp.md`.

---

## 0. TL;DR for a junior engineer

Armadin's swarm attacks a customer, writes down what it finds, then **forgets everything** when it's done. Every engagement starts from a blank notebook.

**Mahoraga is that team's shared, permanent memory.** On the second engagement it says *"you found a bug here last time — go check if it's still there,"* recognizes the same service even though its pod IP changed, and classifies each finding: **new**, **still open**, **verified resolved**, **regressed**, **not retested**, or **inconclusive**.

It remembers five things: **Posture** (findings and how each one's status evolves), **Environment** (services and how they connect), **Coverage** (what we actually tested, so "not seen" ≠ "fixed"), **Tradecraft** (which techniques worked against which kinds of stacks), and **Provenance** (a record of what we did and what authorization the source claimed).

**Production topology:** one shared library (**maho-core** — contracts, database code, tenancy) and **two active services** — **maho-orchestrator** (the *writer*; memory goes in) and **maho-frontend** (the *reader*; memory comes out) — plus a **report job**. A future third service, **maho-gate** (the cross-tenant *guard*), is designed but deferred. (The MVP collapses all of this into one application — see `mahoraga-mvp.md`.)

It connects to Armadin in two places: it **reads** the snapshots Armadin already writes to Spanner, and it **serves** advisory memory-lookup calls the swarm makes mid-attack. Its suggestions are advice, never permission to act.

The name fits: Mahoraga adapts to any attack it has already seen — recognizing the same target and weakness even when surface details change.

---

## 1. Background: where Armadin is today

Armadin runs **authorized** automated pentests via AI agent swarms. **Overwatch** orchestrates a graph of containerized subagents, choosing the next node dynamically from prior output, and captures per-node memory snapshots to **Spanner** for replay and evals. All of this is within a single engagement — re-attack next quarter and the swarm starts from zero, unable to say whether a past vulnerability persists, regressed, or recurs, or which techniques historically worked against this kind of stack.

## 2. What we are building, and why

**A memory engine for offensive security** — a separate plane giving the swarm longitudinal memory. Engram personalizes memory to a user; Pi brings memory to security *remediation*; Mahoraga brings it to *offensive* security.

**Value:** it turns a point-in-time scan into continuous, longitudinal security (regression detection, verified-resolution tracking), which justifies subscription pricing; coverage memory makes reports defensible ("not found" ≠ "fixed"); memory-steered attacks skip redundant recon; and because memory is one horizontal API, every product in the suite can consume it.

### 2.1 The five kinds of memory

| # | Memory | Holds | Access | Scope |
|---|--------|-------|--------|-------|
| 1 | Posture | Findings + occurrence history; classifications | Temporal/relational, exact | Per-tenant |
| 2 | Environment | Assets, topology, trust edges; drift | Graph, temporal | Per-tenant |
| 3 | Coverage | Immutable test attempts, incl. negatives | Relational | Per-tenant |
| 4 | Tradecraft | Technique-class → stack-category outcomes | Structured (+ optional vectors) | Tenant-local later slice; cross-tenant much later, gated |
| 5 | Provenance | Action record | Append-only (hash-chained in production) | Per-tenant |

## 3. Core design principles

1. **Group by runtime role, not logical responsibility.**
2. **Introduce an interface only for a boundary that is externally owned, security-sensitive, independently testable, or has multiple real implementations.**
3. **Vectors propose, deterministic logic disposes.**
4. **Immutable facts + pure fold.** Facts and recorded decisions are immutable; current projections are disposable, rebuildable caches; the fold (`ordered facts + recorded decisions + policy_bundle_version + explicit as_of → projection`) is pure. Identity resolution and vector search are **not** in the fold; their recorded decisions are inputs. Random IDs are immutable creation facts; a rebuild reproduces them, never mints new ones.
5. **Tenancy is enforced in storage** (RLS + IAM), not by library code.
6. **Suggestions are advice, never authority.** Execution-time policy lives at the executor.
7. **Retrieved memory is untrusted data,** never instructions.
8. **DRY, YAGNI, KISS.**

## 4. Architecture overview

**Production deployable topology:** 2 active services (`maho-orchestrator`, `maho-frontend`), 1 report job (`maho-report`), 1 shared library (`maho-core`), 1 future deferred service (`maho-gate`). The Emitter is a non-production fixture runner used only by the MVP. `maho-core` is a library, not a service.

```
======================  ARMADIN (already built)  ==========================
   Overwatch ──spawns──▶ Agent Swarm (graph) ──node snapshots──▶ Spanner
       ▲                      ▲                                     │
       │ (B) advisory calls   │ subagents call MCP (typed, scoped)  │ (A) change stream
       │                      │                                     ▼
=======│======================│=========================  Dataflow (Spanner adapter)  ===
       │                      │                            emits SourceEvent + trusted
       │                      │                            source metadata → Pub/Sub
       │                      │            WRITE PATH               │
       │                      │      ┌──────────────────────────────▼───────┐
       │                      │      │        maho-orchestrator             │
       │                      │      │  ingest(source_events = inbox) →      │
       │                      │      │  deterministic resolver →             │
       │                      │      │  produce DomainEvent(s) → fold        │
       │                      │      └──────────────────┬───────────────────┘
       │   READ PATH          │                         │ one Postgres tx
       │  ┌───────────────────┴──────┐                  ▼  (aggregate_heads locked)
       └─▶│      maho-frontend       │ reads   ┌────────────────────────────┐
          │  api (REST+MCP, advisory)│◀────────│        maho-core           │
          │  commands ─▶ orchestrator│         │ contracts | repositories   │
          │  maho-report (report-    │         │            | tenancy       │
          │   writer: insert-only)   │         └────────────┬───────────────┘
          └──────────────────────────┘                      ▼
   DEFERRED: maho-gate (§13)          ┌────────────────────────────────────┐
                                      │   Cloud SQL PostgreSQL (RLS on)     │
                                      │  source_events(=inbox), engagements,│
                                      │  canonical_assets, asset_observ.,   │
                                      │  asset_resolution_decisions,        │
                                      │  findings, finding_occurrences,     │
                                      │  test_attempts, topology_*,         │
                                      │  aggregate_heads, projections,      │
                                      │  provenance_events, provenance_heads│
                                      │  evidence_records, report_versions  │
                                      └─────────────┬───────────────────────┘
                                                    ▼  GCS evidence (only READY dereferenced)
```

All storage access is implemented through `maho-core` repository packages, **under the importing service's identity and database role**. `maho-core` is a code-organization boundary, not a runtime or security boundary; RLS + IAM are the security boundary.

## 5. Components

### 5.1 maho-core (library)
- **`contracts`** — the two event families (§6), domain types (`CanonicalAsset`, `AssetObservation`, `Finding`, `FindingOccurrence`, `TestAttempt`, `EvidenceRecord`), and the taxonomy. Pure.
- **`repositories`** — capability-specific (`TenantReader`, `TenantWriter`, `TestAttemptRepo`, `TopologyRepo`, `ProvenanceWriter`, `EvidenceStore`, `ReportWriter`), each mapping to a distinct DB role.
- **`tenancy`** — derives/validates `tenant_id` at a trusted boundary, stamps keys, sets up RLS-aware connections. Not the boundary.

Single migration owner; `buf breaking` in CI.

### 5.2 maho-orchestrator (writer) — the one production write path
- **`adapters`** — map a source payload to internal `SourceEvent`s + `TrustedContext`. Two real implementations: `EmitterAdapter` (MVP fixtures) and `SpannerChangeStreamAdapter` (production, runs inside Dataflow, emits `SourceEvent`s — never `DomainEvent`s).
- **`ingest`** — idempotent (`source_events` is the inbox), one transaction over deterministic resolution + `DomainEvent` facts + fold + provenance + outbox, then ack.
- **`resolver`** — candidate collection → decision policy (§8): `RESOLVED | AMBIGUOUS | CREATED_PROVISIONAL | REJECTED`.
- **`fold`** — the pure projection, serialized per aggregate (§6).

State-changing commands from the frontend are sent to and executed exclusively by `maho-orchestrator`. A shared library may define command handlers, but it does not own a runtime or database write path. The frontend never writes directly.

Each command request carries a stable `command_id`, command type, effective time, and typed payload. Authenticated request context supplies tenant and actor identity. The writer assigns an idempotent position on a server-owned command stream, converts the request to a `SourceEvent`, and processes it through the same inbox and transaction as adapter events. Retrying the same ID and content is a no-op; reusing an ID with different content is rejected.

### 5.3 maho-frontend (reader) + maho-report (job)
- **`api`** — advisory reads (`get_prior_findings`, `get_known_topology`, `was_this_tested` → returns `TestAttempt` records) and **named commands** (`record_asset_resolution_decision`, `record_finding_validity_decision`, `record_risk_treatment`, `acknowledge_report`) that are routed to the writer over the same idempotent transactional path. `suggest_next_action` is advisory only. REST + MCP (typed outputs, scoped tokens, read vs command scopes separate, no token passthrough).
- **`maho-report`** — the versioned diff (§9/§12), under a dedicated **report-writer** role: read on facts/projections, **insert-only** on `report_versions`, no update/delete on issued reports.

`acknowledge_report` targets an immutable `report_version_id`. It is recorded and audited through the writer but does not affect report-semantic state or create another report revision.

### 5.4 maho-gate (deferred — §13)

## 6. Event contracts, chronology, and the correctness boundary

**Two contracts with distinct identifiers.** One `SourceEvent` can yield several domain facts, so they cannot share an id:

```
SourceEvent { source_event_id, event_type, source_stream_id, source_sequence,
              schema_version, occurred_at, payload }
TrustedContext { tenant_id, engagement_id, source_identity }          # from adapter/request context

DomainEvent { domain_event_id, source_event_id, tenant_id, engagement_id,
              aggregate_id, aggregate_version, event_type, schema_version,
              policy_bundle_version, effective_at, recorded_at,
              source_stream_id, source_sequence, domain_ordinal, payload }
```

`source_event_id` is the causation link. `domain_ordinal` is assigned deterministically from the semantic fact ordering for one source event. `domain_event_id` is a deterministic UUID derived from tenant, source event, aggregate, event type, and ordinal, then persisted as an immutable fact. Projection rebuilds reuse recorded domain facts and IDs. `tenant_id`/`aggregate_id`/`aggregate_version`/`recorded_at` are assigned by the orchestrator; a producer or Dataflow never supplies them.

**`event_type` discriminator** distinguishes, without inspecting arbitrary JSON: asset observation, finding observation, test attempt, engagement completion, remediation/change claim, explicit retirement, and named frontend command types.

**Canonical hashing (server-computed).** Mahoraga computes and stores `canonical_source_hash = hash(canonical serialization of the immutable SourceEvent)` after parsing/normalizing. The serialization has stable field order, normalizes equivalent typed values, and covers `source_event_id`, `event_type`, `source_stream_id`, `source_sequence`, `schema_version`, `occurred_at`, and normalized payload. It excludes ingestion-only metadata such as `recorded_at`. Canonicalization rules are immutable for a `schema_version`; changing them requires a new schema version so a retry cannot conflict merely because Mahoraga was deployed. A producer-supplied hash is never trusted.

**Idempotency / conflict:**
```
UNIQUE (tenant_id, source_event_id)
UNIQUE (tenant_id, source_stream_id, source_sequence)
same source_event_id + same canonical hash → no-op
same source_event_id + different canonical hash → conflict
same stream/sequence + different id or content → conflict
```
Source streams are bound one-to-one to `tenant_id`/`engagement_id` (persisted on `engagements`); reuse under another tenant/engagement is rejected.

**Chronology.** `effective_at` is the domain chronology. Fold order is `(effective_at, source_stream_id, source_sequence, domain_ordinal, domain_event_id)`; `recorded_at` is operational metadata and never changes historical posture. Before applying that order, historical queries restrict facts to source positions included by the requested immutable knowledge boundary. This prevents a later-arriving backdated fact or command from changing an already-issued report.

**Concurrent folds serialize.** Identity resolution first uses the database uniqueness constraints in §8 and re-reads the winning row after a conflict. For each winning aggregate, initialize and lock its head before allocating a version:

```
INSERT INTO aggregate_heads (...) VALUES (...)
ON CONFLICT (tenant_id, aggregate_id) DO NOTHING;
SELECT ... FROM aggregate_heads
WHERE tenant_id = ? AND aggregate_id = ?
FOR UPDATE;
```

Multi-aggregate commands acquire aggregate heads in stable sorted order. Transactions that also append provenance acquire all aggregate heads before the tenant provenance head. Two-connection tests cover concurrent first asset, first finding, and first aggregate-head creation.

**The synchronous ingest transaction:**
```
adapt source OUTSIDE the transaction
BEGIN
  insert source_events row (inbox; dedup + canonical hash)
  resolve identity via unique upsert + re-read
  record resolution decision (fact)
  initialize and lock aggregate_heads; allocate aggregate_version
  append DomainEvent fact(s)
  fold/update affected projection(s)
  append provenance event (per-tenant chain, §11)
  append outbox work
COMMIT
acknowledge source
```
If resolution needs async embedding/review, it is two transactions: the first stores the observation with workflow status `PENDING` plus outbox work; a later transaction records the final resolution outcome, facts, and projection. `PENDING` is workflow state, not a resolution outcome. The ingest transaction never includes a decision that does not exist yet.

## 7. Data model

One Postgres DB; every tenant-data key includes `tenant_id`. First-class entities: `engagements`, `canonical_assets`, `asset_observations`. Minimum tables:

```
source_events(=inbox) · engagements · canonical_assets · asset_observations
asset_resolution_decisions · asset_alias_events
findings · finding_occurrences · finding_events
test_attempts · topology_assertions
aggregate_heads · current_posture_projection · current_topology_projection
outbox · projector_checkpoints
provenance_events · provenance_heads · evidence_records
ingestion_boundaries · report_versions
```

## 8. Identity resolution

**Kubernetes model:** Deployment/StatefulSet own Pods (via ReplicaSet for Deployments); a Service routes to Pods but does not own them. Canonical identity is `cluster_id + resource_kind + resource_uid`. Pods (UID/name/IP) are **observations** of the owning workload; DNS/labels/banner are corroborating candidates. Findings attach to the appropriate kind (container/process → workload; deployment-config → Deployment; exposed-service → Service; cloud resource → cloud-resource asset type).

Canonical assets enforce:

```
UNIQUE (tenant_id, cluster_id, resource_kind, resource_uid)
```

```
collect candidates from all signals, then decide:
  exactly one authoritative (cluster_id + resource_kind + resource_uid) match ─▶ RESOLVED
  valid authoritative identity with no existing row ─▶ atomically create canonical asset,
                                                       then RESOLVED
  authoritative match rejected by a hard rule                                 ─▶ REJECTED
  only weak-signal candidates, or >1 plausible                                ─▶ AMBIGUOUS (held; no posture effect)
  no authoritative identity and no candidate                                  ─▶ CREATED_PROVISIONAL
```

`CREATED_PROVISIONAL` assets are excluded from customer-facing posture until confirmed; a later decision merges without rewriting history (merge/split/alias/correction are immutable `asset_alias_events`).

**Finding identity:** immutable random `finding_id` (a creation fact). Matching policy v1 uses `(tenant_id, canonical_asset_id, vuln_class, normalized_location_signature, match_key_version)`. The components have a database uniqueness constraint; creation uses insert-on-conflict followed by re-read so concurrent first observations converge on one finding. `match_key` is a hash of the stable components used as a versioned lookup aid, not the finding's identity. Each detection is a `FindingOccurrence`.

Every test-attempt input carries an independently resolvable authoritative target key plus its `verification_key`/check ID. Findings store the applicable verification key. A test attempt therefore resolves its canonical asset without depending on a finding observation arriving first.

## 9. Lifecycle, coverage, and report classification

**Separate dimensions,** so "not retested" never erases last-known state:
```
Last verified exposure:        OPEN | VERIFIED_RESOLVED
Current-engagement assessment: DETECTED | NOT_DETECTED | NOT_RETESTED | INCONCLUSIVE
Validity:                      VALID | FALSE_POSITIVE | UNDETERMINED
Treatment:                     NONE | ACCEPTED_RISK | REMEDIATION_IN_PROGRESS
```

**Coverage compatibility is an executable predicate (policy version 1):**
```
is_compatible(test_attempt, finding) =
  same tenant_id AND same canonical_asset_id
  AND same verification_key/check_id AND exact check_version
  AND exact relevant_context_hash AND compatibility_policy_version = 1
```
`relevant_context_hash` is check-specific. For a workload-level check it covers protocol, port, normalized route, and security-relevant check parameters while excluding ephemeral Pod names and addresses; raw addresses remain evidence. A check whose meaning is bound to an exact address includes that address explicitly. Policy v1 does **not** let a newer `check_version` resolve an older finding. Exactly one of `resolves_finding_id | verification_key` is set. DB checks require `result = not_detected` to have `execution_status = completed`. Only a compatible `completed + not_detected` yields `VERIFIED_RESOLVED`.

**Verified resolution ≠ remediation.** Record `VERIFIED_RESOLVED` + `time_to_verified_resolution`; use "remediated"/MTTR only when an actual remediation-claim or change event exists.

**Report classifications are mutually exclusive, with precedence** — they classify the **engagement episode/change**, not the final exposure at the last instant; the current assessment is always shown separately:
```
1. NEW               first valid occurrence is in the current engagement
2. REGRESSED         positive occurrence follows a verified resolution
3. VERIFIED_RESOLVED compatible completed negative closes prior open state
4. STILL_OPEN        positive occurrence follows prior open state
5. INCONCLUSIVE      only incomplete/inconclusive compatible attempts exist
6. NOT_RETESTED      no compatible attempt exists in the current engagement
```
`RECURRING` is a derived flag (≥2 distinct reopen episodes), never a primary bucket. A composite history (e.g. `REGRESSED` episode with current assessment `NOT_DETECTED` and last exposure `VERIFIED_RESOLVED`, after a regression is re-verified in the same engagement) is possible and is covered by production tests.

## 10. Topology is temporal

Assertions carry `source, observed_at, recorded_at, confidence, validity_interval, last_comparable_coverage`; the current topology is a projection with explicit staleness. Failure to observe never closes an assertion — only explicit retirement or comparable successful coverage does.

## 11. Provenance and evidence

**Provenance is tamper-evident,** hash-chained per tenant, written in the fact transaction. Concurrent appends serialize on a head row that is safe to initialize:
```
provenance_heads { tenant_id, next_sequence, last_hash }
INSERT … ON CONFLICT DO NOTHING;  SELECT … FOR UPDATE;  -- then append one successor, update head
```
`provenance_events` enforces `UNIQUE (tenant_id, sequence)` and immutable predecessor/hash fields. The app role has no update/delete on completed provenance rows. Production adds signed external anchors exported by a separate identity, so no single identity can alter both the records and their anchor.

**Evidence lifecycle (manifest):**
```
evidence_records { evidence_id, tenant_id, object_key, expected_generation, expected_checksum,
                   status: PENDING | READY | FAILED | DELETING | DELETED | LOST }
Legal transitions:
  PENDING → READY | FAILED
  READY → DELETING → DELETED       # controlled retention/deletion
  READY → LOST                     # unexpected missing/wrong generation
```
A dedicated worker verifies generation + checksum and performs the conditional `READY` update. A fact may reference a `PENDING` manifest, but only a `READY` record may be served. Controlled deletion marks the row `DELETING` before deleting the exact GCS generation, then marks it `DELETED`. Reconciliation marks unexpected missing or mismatched content `LOST` and alerts. Generation-match preconditions, retention, and restricted IAM reduce the inconsistency window. Operational invariant: **Mahoraga serves only `READY` evidence at its recorded generation, and unexpected loss becomes explicit rather than silently serving the wrong object.**

## 12. Tenancy and report completeness

**Tenancy in storage:** `tenant_id` derived/validated at a trusted boundary; in every key; RLS `FORCE`d; app role is a non-owner without `BYPASSRLS`. Production splits roles (writer/reader/report-writer/migrations/gate/reviewer/anchor) and schemas; the RLS role matrix is completed **before a second tenant's data is ingested**.

**Report completeness is gap-aware.** Production persists one adapter-independent representation:

```
ingestion_boundaries {
  boundary_id, tenant_id, engagement_id, adapter_version, strategy,
  canonical_boundary_payload, boundary_hash
}
UNIQUE (tenant_id, engagement_id, boundary_hash)

report_versions {
  report_version_id, tenant_id, engagement_id, current_boundary_id,
  knowledge_boundary_payload, knowledge_boundary_hash,
  policy_bundle_version
}
UNIQUE (tenant_id, engagement_id, knowledge_boundary_hash, policy_bundle_version)
```

Source discovery selects exactly one strategy per adapter version: an assembled engagement stream position, a canonical sorted vector of partition positions, or an authoritative Armadin event-stream boundary. `canonical_boundary_payload` stores that strategy's immutable position data. `boundary_hash` is server-computed from adapter version, strategy, and canonical payload. If the source cannot produce an authoritative boundary, reports remain **provisional**.

`knowledge_boundary_payload` is a canonical sorted structure containing the selected immutable ingestion-boundary IDs for all prior and current engagements visible to the report, plus the inclusive position of every report-semantic server-owned command stream. It captures validity, treatment, and resolution decisions without treating unbounded command streams as complete. Report acknowledgments are associated directly with their immutable `report_version_id` and are excluded from report-semantic boundaries.

A report finalizes only when its current ingestion boundary is present and complete, required prior boundaries are selected, required identity decisions are complete or explicitly unresolved, and the required projection version processed every represented position. In one stable-snapshot transaction, the report job captures report-semantic command-stream positions, computes the knowledge-boundary hash, restricts facts to that knowledge boundary, folds them by domain chronology, and inserts the report version. Late observations or report-semantic commands create a new knowledge boundary and report **revision**; acknowledgments do not. Issued reports are never rewritten.

## 13. Tradecraft and the deferred gate

Cross-tenant tradecraft is excluded from current scope. **Tenant-local structured tradecraft** (technique IDs, stack categories, prerequisites, aggregated outcomes; exact SQL retrieval, embeddings optional) is a later slice delivering "similar prior situation → what worked" across one tenant's repeat engagements.

**Later (cross-tenant, gated) — `maho-gate`:** a separately privileged promotion pipeline (scrub → DLP → generalize → human review) promoting only an allowlisted structured schema, dropping raw text/evidence before shared embedding, suppressing unique/small cohorts, requiring contractual opt-in, recording promotion lineage/expiry/revocation, with revocation and deletion propagation. Built only after legal, privacy, poisoning, and isolation acceptance pass. DLP + review is not proof of anonymity — re-identification via rare combinations and timing is the risk it exists to manage.

## 14. Serving and the MCP trust boundary

`suggest_next_action` fuses posture + coverage (+ tenant-local tradecraft once that slice exists) into a ranked recommendation — never authority. Execution-time authorization (a signed, current, non-revoked Rules-of-Engagement decision) lives at the executor, because passive CDC arrives *after* execution: Mahoraga can **record claimed authorization supplied by the source**, but cannot enforce or independently prove it. The MCP surface returns strict typed outputs (controlled technique IDs, not free-text instructions or raw evidence), with short-lived audience-bound credentials, separate read vs command scopes, no token passthrough, rate limiting, per-tool auditing. Retrieved memory is always data, never instructions.

## 15. How this plugs into Armadin

**Step zero — source discovery.** Obtain representative anonymized Armadin records and confirm they carry stable tenant, engagement, action, test, result, authorization, timing, and evidence identifiers. If they don't, coverage and action provenance can't be inferred from CDC and must be sourced differently. Precedes writing the production adapter.

**Ingest (A) — one consistent flow, one adapter owner:**
```
Spanner change stream
  → Dataflow containing the SpannerChangeStreamAdapter
  → SourceEvent + trusted source metadata on Pub/Sub
  → maho-orchestrator transaction
  → DomainEvent(s)
```
Dataflow never publishes `DomainEvent`s and the integration bus never accepts them. No-gap bootstrap: checkpoint CDC at T0, snapshot/export at T0, backfill with deterministic IDs, consume CDC from T0 deduplicating the overlap, mark backfilled events so they don't fire live notifications. Partition-aware completeness per §12.

**Serve (B):** Overwatch / tool-capable subagents make advisory calls; execution authorization stays at the executor.

**On the contract:** the MVP emitter produces Mahoraga's **internal `SourceEvent` contract** — not the unknown future Spanner source contract. The production adapter maps the (to-be-discovered) Spanner shape onto that same internal contract. This reduces integration risk; it is not a "one-line swap."

## 16. Deferred-to-production register

| Deferred item | Trigger |
|---|---|
| Dataflow CDC reader + no-gap bootstrap + partition-aware completeness | Real Armadin integration, after source discovery |
| `maho-gate` cross-tenant promotion + DLP | First contractual opt-in; privacy + poisoning tests pass |
| External provenance anchoring | Before any legal/compliance/non-repudiation claim |
| Full RLS role matrix + schema separation | Before a second tenant's data is ingested |
| DLQ + replay tooling, backpressure alerts | Real (non-simulated) ingestion volume |
| Capacity model + mixed load tests | Before production SLOs are committed |
| HA Cloud SQL, PITR/DR drills | Before go-live; product-approved RPO/RTO |
| Observability dashboards, paging, runbooks | Before any external production workload |
| N/N-1 rolling-deploy tests | First rolling deploy with live consumers |
| Evidence type/malware controls + retention | Before storing any real customer evidence |

## 17. Design-pattern assessment

Adapter (ingest) and Facade (api) kept. Repository is capability-specific. State/Observer/Memento labels dropped (immutable facts + pure fold; Pub/Sub is the mechanism). "First-match-wins" replaced by candidate-collection + decision policy. Proxy/Ward is app checks only — RLS + IAM are the boundary. Gate is a promotion pipeline (deferred). Abstract Factory, Visitor, Singleton, Flyweight remain unused.

## 18. Technology map

The **Core MVP** column matches `mahoraga-mvp.md` exactly.

| Concern | Core MVP | Production |
|---|---|---|
| Contracts | Typed models + versioned JSON fixtures | protobuf + buf |
| Source | Fixture runner emitting internal `SourceEvent`s | Spanner adapter in Dataflow → `SourceEvent`s |
| Bus | Direct in-process / application call | Pub/Sub |
| Storage | PostgreSQL; local/container by default | Cloud SQL Postgres + role split, schema separation |
| Resolver | Deterministic authoritative matching | + pgvector candidate generation (proposing only) |
| Topology | Deferred | Temporal assertions (Spanner Graph only after measurement) |
| Tradecraft | Deferred (optional structured later slice) | Tenant-local, then gated cross-tenant |
| Evidence | Fixture URI / inline synthetic metadata | Manifest + GCS, type/malware controls, retention |
| Provenance | Immutable source/audit metadata only; **no hash chain** | Hash-chained, head-locked, + signed external anchors |
| Serving | CLI/report; REST optional | REST + gRPC option; hardened tokens |
| MCP | Deferred | Typed, scoped MCP |
| Gate | Deferred | `maho-gate` |

## 19. Build order (production)

1. Source discovery, threat model, data classification, invariants (§20).
2. `SourceEvent`/`DomainEvent` contracts (distinct IDs, `event_type`, canonical hashing), `TestAttempt` semantics, trusted context + stream binding.
3. Idempotent inbox, chronology/fold with `aggregate_heads` serialization, immutable facts, outbox, replay, reconciliation.
4. Storage-enforced tenancy and service identities (incl. report-writer role).
5. Kubernetes-correct canonical-asset model + finding identity with ambiguity/merge/split history.
6. Posture, coverage predicate, temporal topology, partition-aware gap-aware reporting.
7. REST API + named commands routed to the single writer.
8. MCP after scoped auth + hostile-memory controls.
9. Tenant-local deterministic tradecraft.
10. Cross-tenant promotion only after legal/privacy/poisoning/isolation acceptance.

## 20. Critical invariants

- Source event identity is stable across retries/re-drives; a duplicate ID with different content is rejected; a stream can't be reused under another tenant/engagement.
- Production integration publishes raw changes or `SourceEvent`s, never pre-resolved `DomainEvent`s; Dataflow cannot forge aggregate-assigned domain events.
- Arrival order does not determine posture; facts are first restricted to the selected immutable knowledge boundary and then ordered by `(effective_at, source_stream_id, source_sequence, domain_ordinal, domain_event_id)`; `recorded_at` is operational.
- Facts and recorded decisions are immutable; projections are disposable and rebuildable; rebuilds preserve recorded IDs and rerun no fuzzy matching.
- Concurrent identity creation converges through database uniqueness; aggregate heads initialize before locking; multi-aggregate commands lock in stable order before the provenance head.
- Canonical assets and observed instances are separate; weak identifiers never silently auto-merge.
- An unsuccessful or absent test never resolves a finding; last-known-open is preserved when not retested.
- Reports use mutually exclusive primary classifications; completion is gap-aware (partition-aware in production); a new policy version never rewrites an issued report; the report-writer can insert but not update/delete issued reports.
- Only `READY` evidence at the recorded generation is served; controlled deletion and unexpected loss have explicit states.
- Suggestions never grant execution authority; RLS + IAM are the security boundary, not library code.

---

## Appendix — Glossary

- **SourceEvent / DomainEvent** — internal adapter output/orchestrator input (with `source_event_id`, `event_type`) vs internal immutable fact (with `domain_event_id`, `effective_at`).
- **canonical_source_hash** — server-computed hash of the normalized source event; producer hashes are not trusted.
- **Canonical asset** — a workload/service identified by `cluster_id + resource_kind + resource_uid`.
- **effective_at** — the domain chronology field; source stream, sequence, and deterministic domain ordinal are tie-breakers; `recorded_at` is operational.
- **aggregate_heads / provenance_heads** — per-aggregate / per-tenant lock+sequence rows serializing folds and provenance appends.
- **is_compatible** — the coverage predicate; only a compatible completed negative yields `VERIFIED_RESOLVED`.
- **Report classification** — the engagement episode/change, shown alongside the current assessment and last verified exposure.
