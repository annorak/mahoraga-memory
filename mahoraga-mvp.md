# Mahoraga — MVP Build Spec

**Companion to [`mahoraga-design.md`](mahoraga-design.md).** The core MVP is **one application + one Postgres + deterministic fixtures + a memory-aware planner**, proving the differentiator with the smallest thing that can prove it. Everything heavier is a named later slice.

The core question:

> Can longitudinal memory change the second engagement and correctly distinguish **new, still-open, verified-resolved, regressed, not-retested, and inconclusive** findings — and can we show, without leakage, that memory *changed the plan*, not just the report?

---

## `maho-gate` decision: excluded from the core MVP

Tradecraft is out of the core build entirely; cross-tenant learning is roadmap. A stub gate is explicitly not built (it validates none of the trust boundary and risks false confidence). If the network-effect story must be pitched, add a clearly-labeled synthetic moat demo *after* the core MVP (two synthetic tenants → allowlisted record `{technique_id, stack_category, prerequisite_category, outcome, sample_count}` → manual offline promotion → isolated shared table → tenant B retrieves a generalized technique), marked "synthetic product-value demonstration; not a production privacy or security validation." It must not block the core MVP.

## 1. What the core MVP proves

One closed memory loop, demonstrating longitudinal posture, coverage-aware planning, stable identity across endpoint churn, regression detection, correct absence handling, idempotent ingestion, and deterministic reporting — then a planner experiment proving memory changes the plan.

## 2. Runtime shape

```
Versioned fixture files ─▶ One Mahoraga application ─▶ PostgreSQL
   modules: ingest · identity · posture/coverage fold ·
            pre-engagement query · deterministic planner · report (CLI; REST optional)
   plus a fixture-runner process
```

One application artifact, one fixture runner, one Postgres. The MVP modules preserve useful ownership seams and reduce later extraction work; service extraction will still require explicit APIs, authorization, deployment, and consistency decisions. If a hosted demo is needed, deploy the same artifact to one Cloud Run service + Cloud SQL — do not split until independent scaling, failure, or privilege requirements exist.

Fixtures are **checked-in ordered event datasets containing Kubernetes-shaped snapshots** (v1 → v2). They encode Pod/IP/name churn, a detection followed by negative verification in Engagement 1, reintroduction in Engagement 2, an untested finding, a partial attempt, and a new finding. The value is the churn data, not operating a cluster.

## 3. Event contract (MVP)

Typed models + versioned JSON fixtures (protobuf/buf deferred). The fixture runner supplies trusted context `{ tenant_id, engagement_id }`; Mahoraga computes and stores the canonical hash.

```
SourceEvent { source_event_id, event_type, source_stream_id, source_sequence,
              schema_version, occurred_at, payload }
effective_at = validated occurred_at                                     # fixture MVP rule
canonical_source_hash = server-computed over every SourceEvent field     # fixture supplies no hash
```

`event_type` ∈ { asset_observation, finding_observation, test_attempt, engagement_completed }.

The canonical serialization covers `source_event_id`, `event_type`, `source_stream_id`, `source_sequence`, `schema_version`, `occurred_at`, and normalized payload. Canonicalization rules are immutable for a `schema_version`; changing them requires a new version. `recorded_at` is assigned by Mahoraga and never orders posture.

## 4. Data model (7 tables) and where things live

```
source_events(=inbox) · engagements · assets(canonical) · asset_observations
findings · finding_occurrences · test_attempts
```

- **`source_events` is both the immutable source log and the inbox/dedup ledger:**
  `UNIQUE (tenant_id, source_event_id)`, `UNIQUE (tenant_id, source_stream_id, source_sequence)`. Same id + same canonical hash → no-op; same id + different hash → reject; same stream/sequence + different id/content → reject.
- **Resolution outcome is stored on `asset_observations`** (no separate table).
  The production taxonomy names `{RESOLVED, AMBIGUOUS, CREATED_PROVISIONAL,
  REJECTED}`, but the core MVP intentionally persists only `RESOLVED` and
  `AMBIGUOUS`. A valid authoritative Deployment identity atomically creates or
  finds its canonical asset and records `RESOLVED`. Any UID-less weak match is
  `AMBIGUOUS`, has a null `canonical_asset_id`, and has no posture effect. A
  UID-less input with no weak candidate, or any hard rejection, fails ingestion
  and writes no observation. Persisting the other two production states requires
  an explicit later use case.
- **Finding matching uses existing tables:** `findings` stores `match_key_version`, `vuln_class`, `normalized_location_signature`, and the applicable `verification_key`, with `UNIQUE (tenant_id, canonical_asset_id, vuln_class, normalized_location_signature, match_key_version)`. Creation uses insert-on-conflict followed by re-read.
- **Stream binding lives on `engagements`:** `source_stream_id → tenant_id → engagement_id`, rejecting reuse under another tenant/engagement.
- Optional 8th table `current_finding_projection` only if the pre-engagement query benefits; otherwise the report derives directly from facts. Every tenant-data key includes `tenant_id`.

Not added until first concrete use: `asset_alias_events, finding_events, topology_assertions, current_topology_projection, aggregate_heads, outbox, projector_checkpoints, provenance_events, provenance_heads, evidence_records, report_versions, quarantine`.

## 5. Identity model (MVP)

One canonical resource kind — **Deployment** — identified by `cluster_id + resource_kind + resource_uid`. Changing pod UID/name/IP are **observations** of that Deployment:

```
asset_observations { pod_uid, pod_name, ip_address, dns, labels, banner, observed_at, + resolution fields }
```

Demo: `same Deployment UID + changed pod UID/IP/name → same canonical asset`. Include **one weak-signal collision** (reused DNS or label) with the **Deployment UID omitted** → `AMBIGUOUS` → held out of posture. (If the authoritative UID is present, the authoritative match wins and it is not ambiguous.) No review UI required.

`assets` enforces `UNIQUE (tenant_id, cluster_id, resource_kind, resource_uid)`. A valid authoritative identity with no row creates the confirmed canonical asset in the ingest transaction; it is not provisional.

Finding match policy v1 is:

```
(tenant_id, canonical_asset_id, vuln_class,
 normalized_location_signature, match_key_version = 1)
```

Every `finding_observation` fixture carries the authoritative asset key, match components, and applicable verification key, never an internal `finding_id` or an `F-*` label. Every test-attempt payload independently carries the authoritative Deployment key, `verification_key`/check ID, check version, and context fields. It therefore resolves its canonical asset without depending on a finding observation arriving first. Sequentially shuffled ingestion into a fresh database must still produce one canonical asset and one matching finding.

## 6. Coverage predicate (MVP)

```
is_compatible = same tenant AND same canonical asset AND same verification/check key
                AND exact check_version AND exact context fingerprint (relevant_context_hash)
                AND compatibility_policy_version = 1
```

For Deployment-level checks, `relevant_context_hash` covers protocol, port, normalized route, and security-relevant check parameters; it excludes ephemeral Pod names and IPs. Raw addresses remain observation/evidence fields. An explicitly address-bound check includes its exact address in the fingerprint.

Only a compatible `completed + not_detected` resolves a finding. DB checks require `result = not_detected` to have `execution_status = completed`. Acceptance changes exactly one dimension at a time and confirms the negative test does **not** resolve.

## 7. Classification (mutually exclusive, with precedence)

Classifies the **engagement episode/change**, with the current assessment shown separately:

```
1. NEW               first valid occurrence is in the current engagement
2. REGRESSED         positive occurrence follows a verified resolution
3. VERIFIED_RESOLVED compatible completed negative closes prior open state
4. STILL_OPEN        positive occurrence follows prior open state
5. INCONCLUSIVE      only incomplete/inconclusive compatible attempts exist
6. NOT_RETESTED      no compatible attempt exists in the current engagement
```

Last verified exposure (`OPEN | VERIFIED_RESOLVED`) is separate from current-engagement assessment (`DETECTED | NOT_DETECTED | NOT_RETESTED | INCONCLUSIVE`); `NOT_RETESTED` keeps last-known-open. The six fixtures avoid composite histories (a regression re-verified within the same engagement) by construction; that case is a production test. No `RECURRING`, no MTTR.

## 8. Named finding scenarios

| Finding | Engagement 1 | Planner boundary (`as_of`) | Engagement 2 | E2 classification |
|---|---|---|---|---|
| `F-STILL` | Detected | last exposure OPEN | Detected | **STILL_OPEN** |
| `F-FIXED` | Detected | last exposure OPEN | compatible completed negative | **VERIFIED_RESOLVED** |
| `F-REGRESS` | Detected **+ completed compatible negative verification** | last exposure **VERIFIED_RESOLVED**, no E2 outcomes | Detected | **REGRESSED** |
| `F-UNTESTED` | Detected | last exposure OPEN | no compatible completed test | **NOT_RETESTED** |
| `F-INCONCLUSIVE` | Detected | last exposure OPEN | partial/failed compatible attempt | **INCONCLUSIVE** |
| `F-NEW` | Absent | — | first detection | **NEW** |

Expected report: `1 still open · 1 verified resolved · 1 regressed · 1 not retested · 1 inconclusive · 1 new`. Every number traces to a named fixture. `F-REGRESS`'s negative verification is in Engagement 1, before the planner boundary — never ambiguous.

The `F-*` names exist only in the runner manifest and test assertions. They never appear in ingested payloads or planner inputs.

## 9. The steering experiment (the sharp part — leakage-free, exact)

Storage isn't the pitch; the plan changing is.

**Boundary.** The planner runs at an explicit `as_of` = the finalized Engagement 1 boundary, after all E1 facts are finalized and **before any E2 outcome is ingested**. Every planner query takes the `as_of` parameter.

**Controls.** Memory-off and memory-on execute against isolated exact clones of the same finalized pre-E2 database state. They receive the same opaque candidate IDs, verification keys, deterministic tie-break, frozen runner-only outcome map, and action budget of three. The only difference is whether historical memory features are supplied. The planner may see stable check ID, prior finding status, prior verification history, and current coverage needs. It must **not** see runner scenario labels, the outcome map, any E2 result, or the eventual classification.

**Planner.** `candidate tests + prior findings + current coverage → ordered tests`.

```
memory off: order by candidate_id ascending
memory on:  order by has_prior_verified_resolution descending,
                     candidate_id ascending
```

`has_prior_verified_resolution` is derived by joining the candidate's verification key to facts visible at the E1 boundary. It is not fixture metadata.

The runner-only manifest connects the opaque candidates to deterministic actions:

| Candidate | Fixture action | Frozen result | Report scenario |
|---|---|---|---|
| `T-A` | check prior open finding | detected | `F-STILL` |
| `T-B` | check prior open finding | completed, not detected | `F-FIXED` |
| `T-C` | recheck prior verified-resolved finding | detected | `F-REGRESS` |

`F-INCONCLUSIVE` is produced by a background partial attempt, `F-NEW` by a background first detection, and `F-UNTESTED` has no E2 attempt. These background events are ingested for the report but are not planner candidates.

**Exact expected result:**
```
Candidate tests:        [T-A, T-B, T-C]
Memory disabled:        [T-A, T-B, T-C]
Memory enabled:         [T-C, T-A, T-B]
Actions before regression detection:  3 → 1
```

**Execution.** Both returned plans execute in separate exact clones of the finalized E1 database. In each clone, the runner executes candidates in the returned order and ingests the corresponding frozen outcomes. The metric is calculated from the two executed histories, not from an asserted control order. No LLM — the result is observable and reproducible.

## 9.1 What this proves to Armadin (stateless vs memory)

The planner metric is one half of the value story; the report contrast is the other, and it lands the customer-facing case. The same finalized Engagement 2 facts are rendered twice by scoping the knowledge boundary — no new machinery, just the mechanism §10 already defines:

- **Stateless** (knowledge boundary = the E2 stream only, prior engagement out
  of scope) — the way a point-in-time scan sees E2: `F-REGRESS`, `F-STILL`, and
  `F-NEW` are detected; `F-FIXED` is a completed not-detected result; and
  `F-INCONCLUSIVE` is partial. `F-UNTESTED` has no E2 fact and is therefore
  invisible and unrepresentable, not a second "not found" result. A stateless
  view cannot identify that missing subject, say a fix came back, distinguish
  verified resolution from a first negative, or warn that an earlier finding
  was not retested.
- **Memory** (knowledge boundary = E1 + E2) — `F-REGRESS` → **REGRESSED**, `F-FIXED` → **VERIFIED_RESOLVED**, `F-UNTESTED` → **NOT_RETESTED**, `F-STILL` → **STILL_OPEN**, `F-NEW` → **NEW**, `F-INCONCLUSIVE` → **INCONCLUSIVE**.

Three capabilities a stateless scan structurally cannot produce — regression detection, verified-resolution semantics, and coverage honesty — plus the planner's `3 → 1` efficiency, are the business case: **continuous longitudinal security instead of a repeated point-in-time scan**, which is what justifies a retainer over a one-off engagement. Both renderings come from identical E2 facts; the only difference is whether prior-engagement memory is in scope. This also earns the knowledge-boundary mechanism its place twice: it is what makes the steering experiment leakage-free *and* what makes this contrast a one-line scope change rather than a second code path.

## 10. Ingestion, ordering, completeness (MVP)

**Ingest:** synchronous and single-process/sequential, one transaction — insert `source_events` (dedup + server hash), perform unique authoritative asset/finding upserts, record asset resolution on `asset_observations`, append facts (occurrences/attempts), and fold the projection (or derive at report time). Validation errors return directly (no quarantine table yet). Shuffle convergence proves sequential order independence, not concurrent-write safety.

**Ordering:** for the fixture MVP, `effective_at := validated occurred_at`. A knowledge boundary is a canonical sorted set of `(source_stream_id, last_data_sequence)` pairs: the planner boundary contains finalized E1; the E2 report boundary contains finalized E1 and E2. Historical queries retain an event only when its stream is present in that set and its sequence is within that stream's limit, then fold in `(effective_at, source_stream_id, source_sequence, source_event_id)` order. Arrival and `recorded_at` order never determine status. Duplicate and reorder are separate tests.

**Completeness (in-stream marker):** data events `1..N`, then an `EngagementCompleted` event at `N+1` with `payload.last_data_sequence = N`. The engagement boundary is finalized only when every sequence `1..N` exists. A report uses the immutable set of all engagement boundaries included in that report. This contiguous-sequence protocol is the MVP fixture's completeness implementation; the production adapter may implement the same interface differently (partition-aware — see the design doc).

## 11. Build order (MVP)

1. Six named scenarios + expected report.
2. Mutually-exclusive classification rules.
3. Coverage predicate + valid test-outcome combinations.
4. Minimal typed `SourceEvent` contract (`source_event_id`, `event_type`, server hash).
5. Two checked-in ordered engagement event datasets containing Kubernetes-shaped snapshots (v1, v2).
6. Seven-table schema + tenant-bound constraints (`source_events` = inbox; resolution on `asset_observations`; stream binding on `engagements`).
7. Transactional ingestion + duplicate/conflict detection + rollback tests.
8. Authoritative Deployment upsert + versioned finding matching (+ the one `AMBIGUOUS` collision).
9. Deterministic posture + coverage fold.
10. Pre-engagement memory query (takes `as_of`).
11. Deterministic memory-aware planner (leakage-free; exact result; drives the fixture).
12. Final report (gap-aware; reproducible).
13. Prove replay + shuffled-order convergence.
14. Thin REST endpoints only if the demo needs interactivity.
15. *Slice:* temporal topology (environmental drift).
16. *Slice:* structured tenant-local tradecraft (exact SQL, no embeddings) if the pitch needs it.
17. *Slice:* hosted deployment if stakeholders need remote access.

## 12. MVP acceptance tests

**Source-event identity** — same id + same canonical content is a no-op; same id + different content is rejected; same stream/sequence with a different id/content is rejected; a stream cannot be reused under another tenant/engagement. Conflict tests include changing only `schema_version` and changing only `occurred_at`.

**Atomicity** — failure after `source_events` insertion rolls back everything; failure after occurrence/test-attempt insertion rolls back everything; failure before the optional projection write rolls back everything; retry after rollback succeeds. (The larger Pub/Sub/GCS crash matrix stays deferred.)

**Ordering** — shuffled arrival produces the same canonical report. Prior `VERIFIED_RESOLVED` followed by detection produces episode `REGRESSED`, current assessment `DETECTED`, and last exposure `OPEN`. Prior `OPEN` followed by a current detection and compatible negative produces episode `VERIFIED_RESOLVED`, current assessment `NOT_DETECTED`, and last exposure `VERIFIED_RESOLVED`.

**Identity** — the first valid Deployment UID creates a confirmed canonical asset; Pod UID/name/IP churn preserves it; a weak-signal collision without the authoritative UID becomes `AMBIGUOUS`; an ambiguous observation does not affect posture. An E2 finding observation carrying no internal finding ID maps to the E1 finding, and shuffled fresh-database ingestion creates exactly one asset and one finding.

**Coverage** — only a completed compatible negative resolves; a mismatch in asset/check/version/context/policy does not; failed/blocked/partial/skipped/absent/incompatible do not. Changed ephemeral Pod IP remains compatible for the Deployment check; changed port, route, or check parameter is incompatible.

**Steering** — planner runs at the pre-E2 `as_of`; memory-off and memory-on use isolated clones of the same E1 state, candidates, frozen outcome map, and budget; candidate IDs are opaque; both returned plans drive fixture execution; memory reduces actions-before-regression-detection per the declared metric (`3 → 1`).

**Completeness** — an in-stream completion marker with a missing earlier sequence blocks the report; supplying it unblocks. An E2 report includes both E1 and E2 stream boundaries, and overlapping sequence numbers in those two streams do not include or exclude facts from the wrong engagement.

**Rebuild** — rebuilding from facts preserves recorded asset and finding IDs and reruns no fuzzy matching; with no projection, reports derived from the same facts are equal; with the optional projection, delete-only-then-rebuild and compare canonicalized semantic rows.

**Value contrast** — the same finalized E2 facts rendered with the prior engagement out of scope (knowledge boundary = E2 stream only) vs in scope (E1 + E2) produce different reports; only the memory rendering yields `REGRESSED`, `VERIFIED_RESOLVED`, and `NOT_RETESTED`, and those three classifications are absent from the stateless rendering. Reuses the six fixtures; asserts the contrast is a knowledge-boundary scope change, not a separate code path.

**Report reproducibility** — report queries first restrict facts to the immutable knowledge-boundary set, then apply domain chronology under a fixed `policy_bundle_version`. For fresh-database shuffle comparisons, canonical semantic content replaces internal asset/finding UUIDs with stable asset and finding match components and excludes `generated_at`, request/trace IDs, and non-semantic ordering. Rebuilding the same recorded fact database still preserves its original internal IDs.

## 13. Explicitly deferred

Spanner discovery + production adapter · Dataflow + no-gap partition-aware bootstrap · Pub/Sub · cross-tenant promotion · `maho-gate` · DLP/sanitization · external provenance anchoring · **hash-chained provenance** · GCS evidence lifecycle · RLS + full role matrix · MCP · vector search + embeddings · **tradecraft (any)** · `aggregate_heads` concurrency machinery · HA/PITR/DR · load testing · dashboards/runbooks · N/N-1 rolling-deploy tests · malware scanning + retention.

## 14. Roadmap after the core MVP

1. **Environmental drift** — temporal topology assertions, new/retired assets, trust-edge changes, staleness.
2. **Tenant-local tradecraft** — structured stack categories, controlled technique IDs, prerequisites, exact SQL retrieval, outcome aggregation (no embeddings initially).
3. **Real integration** — Armadin source discovery, production adapter, Spanner change stream, Dataflow reader, Pub/Sub, partition-aware backfill + cutover.
4. **Production evidence & provenance** — GCS evidence manifest, outbox, malware/file controls, retention, hash-chained + externally anchored provenance.
5. **Multi-tenant hardening** — RLS, separate DB roles, cross-tenant negative tests, tenant-specific evidence access.
6. **Cross-tenant moat** — customer opt-in, promotion schema, `maho-gate`, DLP, reviewer workflow, cohort suppression, poisoning tests, shared corpus, revocation/deletion propagation.

## 15. What the MVP intentionally does not claim

Not production scale/HA/DR; not cross-tenant learning; not tradecraft; not enforceable **or recorded** authorization (advisory suggestions only — the MVP schema records no authorization); not tamper-evident provenance (hash-chaining is deferred; the MVP keeps immutable source/audit metadata only); not "zero-change/one-line" Armadin integration (only a consumer-defined *internal* `SourceEvent` contract that reduces, not eliminates, integration work). It is a **product-value demonstration**, not a production privacy or security validation.
