-- Mahoraga MVP schema, version 1.
-- Seven tenant-qualified tables. Facts link to source_events for engagement
-- and chronology instead of duplicating those columns. Status vocabularies use
-- checked text, not PostgreSQL enums, so later changes are explicit migrations.

CREATE TABLE engagements (
    tenant_id          text NOT NULL,
    engagement_id      text NOT NULL,
    source_stream_id   text NOT NULL,
    -- Null until TASK-008A verifies the contiguous stream and finalizes it.
    last_data_sequence bigint,
    CONSTRAINT pk_engagements PRIMARY KEY (tenant_id, engagement_id),
    -- Global uniqueness: a source stream can never be rebound to another
    -- tenant or engagement.
    CONSTRAINT uq_engagements_source_stream UNIQUE (source_stream_id),
    CONSTRAINT uq_engagements_stream_binding UNIQUE (tenant_id, engagement_id, source_stream_id),
    CONSTRAINT ck_engagements_tenant_nonblank CHECK (tenant_id ~ '[^[:space:]]'),
    CONSTRAINT ck_engagements_engagement_nonblank CHECK (engagement_id ~ '[^[:space:]]'),
    CONSTRAINT ck_engagements_stream_nonblank CHECK (source_stream_id ~ '[^[:space:]]'),
    CONSTRAINT ck_engagements_last_sequence_positive
        CHECK (last_data_sequence IS NULL OR last_data_sequence > 0)
);

CREATE TABLE source_events (
    tenant_id             text NOT NULL,
    engagement_id         text NOT NULL,
    source_event_id       text NOT NULL,
    event_type            text NOT NULL,
    source_stream_id      text NOT NULL,
    source_sequence       bigint NOT NULL,
    schema_version        integer NOT NULL,
    occurred_at           timestamptz(6) NOT NULL,
    payload               jsonb NOT NULL,
    canonical_source_hash text NOT NULL,
    recorded_at           timestamptz(6) NOT NULL DEFAULT current_timestamp,
    CONSTRAINT pk_source_events PRIMARY KEY (tenant_id, source_event_id),
    CONSTRAINT uq_source_events_stream_position
        UNIQUE (tenant_id, source_stream_id, source_sequence),
    CONSTRAINT fk_source_events_engagement
        FOREIGN KEY (tenant_id, engagement_id, source_stream_id)
        REFERENCES engagements (tenant_id, engagement_id, source_stream_id),
    CONSTRAINT ck_source_events_tenant_nonblank CHECK (tenant_id ~ '[^[:space:]]'),
    CONSTRAINT ck_source_events_engagement_nonblank CHECK (engagement_id ~ '[^[:space:]]'),
    CONSTRAINT ck_source_events_event_id_nonblank CHECK (source_event_id ~ '[^[:space:]]'),
    CONSTRAINT ck_source_events_stream_nonblank CHECK (source_stream_id ~ '[^[:space:]]'),
    CONSTRAINT ck_source_events_event_type CHECK (event_type IN
        ('asset_observation', 'finding_observation', 'test_attempt', 'engagement_completed')),
    CONSTRAINT ck_source_events_sequence_positive CHECK (source_sequence > 0),
    CONSTRAINT ck_source_events_schema_version CHECK (schema_version = 1),
    CONSTRAINT ck_source_events_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_source_events_hash_hex CHECK (canonical_source_hash ~ '^[0-9a-f]{64}$')
);

-- One stream carries at most one completion marker.
CREATE UNIQUE INDEX uq_source_events_completion_marker
    ON source_events (tenant_id, source_stream_id)
    WHERE event_type = 'engagement_completed';

CREATE TABLE assets (
    tenant_id          text NOT NULL,
    canonical_asset_id uuid NOT NULL,
    cluster_id         text NOT NULL,
    resource_kind      text NOT NULL,
    resource_uid       text NOT NULL,
    CONSTRAINT pk_assets PRIMARY KEY (tenant_id, canonical_asset_id),
    CONSTRAINT uq_assets_authoritative_key
        UNIQUE (tenant_id, cluster_id, resource_kind, resource_uid),
    -- Supports exact-key composite references from asset_observations.
    CONSTRAINT uq_assets_exact_reference
        UNIQUE (tenant_id, canonical_asset_id, cluster_id, resource_kind, resource_uid),
    CONSTRAINT ck_assets_tenant_nonblank CHECK (tenant_id ~ '[^[:space:]]'),
    CONSTRAINT ck_assets_cluster_nonblank CHECK (cluster_id ~ '[^[:space:]]'),
    CONSTRAINT ck_assets_resource_uid_nonblank CHECK (resource_uid ~ '[^[:space:]]'),
    CONSTRAINT ck_assets_resource_kind CHECK (resource_kind = 'Deployment')
);

CREATE TABLE asset_observations (
    tenant_id                 text NOT NULL,
    source_event_id           text NOT NULL,
    canonical_asset_id        uuid,
    cluster_id                text NOT NULL,
    resource_kind             text NOT NULL,
    resource_uid              text,
    pod_uid                   text,
    pod_name                  text,
    ip_address                text,
    dns                       text,
    labels                    jsonb,
    banner                    text,
    resolution_outcome        text NOT NULL,
    resolution_policy_version integer NOT NULL,
    resolution_basis          text NOT NULL,
    CONSTRAINT pk_asset_observations PRIMARY KEY (tenant_id, source_event_id),
    CONSTRAINT fk_asset_observations_source_event
        FOREIGN KEY (tenant_id, source_event_id)
        REFERENCES source_events (tenant_id, source_event_id),
    -- Exact-key reference; any null column (the AMBIGUOUS case) makes the
    -- foreign key inapplicable by SQL MATCH SIMPLE semantics.
    CONSTRAINT fk_asset_observations_asset
        FOREIGN KEY (tenant_id, canonical_asset_id, cluster_id, resource_kind, resource_uid)
        REFERENCES assets (tenant_id, canonical_asset_id, cluster_id, resource_kind, resource_uid),
    CONSTRAINT ck_asset_observations_tenant_nonblank CHECK (tenant_id ~ '[^[:space:]]'),
    CONSTRAINT ck_asset_observations_cluster_nonblank CHECK (cluster_id ~ '[^[:space:]]'),
    CONSTRAINT ck_asset_observations_resource_uid_nonblank
        CHECK (resource_uid IS NULL OR resource_uid ~ '[^[:space:]]'),
    CONSTRAINT ck_asset_observations_pod_uid_nonblank
        CHECK (pod_uid IS NULL OR pod_uid ~ '[^[:space:]]'),
    CONSTRAINT ck_asset_observations_pod_name_nonblank
        CHECK (pod_name IS NULL OR pod_name ~ '[^[:space:]]'),
    CONSTRAINT ck_asset_observations_ip_address_nonblank
        CHECK (ip_address IS NULL OR ip_address ~ '[^[:space:]]'),
    CONSTRAINT ck_asset_observations_dns_nonblank
        CHECK (dns IS NULL OR dns ~ '[^[:space:]]'),
    CONSTRAINT ck_asset_observations_banner_nonblank
        CHECK (banner IS NULL OR banner ~ '[^[:space:]]'),
    CONSTRAINT ck_asset_observations_resource_kind CHECK (resource_kind = 'Deployment'),
    CONSTRAINT ck_asset_observations_policy_version CHECK (resolution_policy_version = 1),
    CONSTRAINT ck_asset_observations_labels_object
        CHECK (labels IS NULL OR jsonb_typeof(labels) = 'object'),
    CONSTRAINT ck_asset_observations_signal_present CHECK (
        pod_uid IS NOT NULL OR pod_name IS NOT NULL OR ip_address IS NOT NULL
        OR dns IS NOT NULL OR (labels IS NOT NULL AND labels <> '{}'::jsonb)
        OR banner IS NOT NULL),
    -- The MVP persists exactly two resolution states with fixed pairings.
    CONSTRAINT ck_asset_observations_resolution_pairing CHECK (
        (resolution_outcome = 'RESOLVED'
            AND canonical_asset_id IS NOT NULL
            AND resource_uid IS NOT NULL
            AND resolution_basis = 'AUTHORITATIVE_DEPLOYMENT_KEY')
        OR (resolution_outcome = 'AMBIGUOUS'
            AND canonical_asset_id IS NULL
            AND resource_uid IS NULL
            AND resolution_basis = 'WEAK_SIGNAL_COLLISION'))
);

CREATE TABLE findings (
    tenant_id                     text NOT NULL,
    finding_id                    uuid NOT NULL,
    canonical_asset_id            uuid NOT NULL,
    vuln_class                    text NOT NULL,
    normalized_location_signature text NOT NULL,
    match_key_version             integer NOT NULL,
    verification_key              text NOT NULL,
    check_version                 text NOT NULL,
    relevant_context_hash         text NOT NULL,
    compatibility_policy_version  integer NOT NULL,
    CONSTRAINT pk_findings PRIMARY KEY (tenant_id, finding_id),
    CONSTRAINT uq_findings_match_identity UNIQUE
        (tenant_id, canonical_asset_id, vuln_class, normalized_location_signature, match_key_version),
    CONSTRAINT fk_findings_asset
        FOREIGN KEY (tenant_id, canonical_asset_id)
        REFERENCES assets (tenant_id, canonical_asset_id),
    CONSTRAINT ck_findings_tenant_nonblank CHECK (tenant_id ~ '[^[:space:]]'),
    CONSTRAINT ck_findings_vuln_class_nonblank CHECK (vuln_class ~ '[^[:space:]]'),
    CONSTRAINT ck_findings_location_nonblank
        CHECK (normalized_location_signature ~ '[^[:space:]]'),
    CONSTRAINT ck_findings_verification_key_nonblank
        CHECK (verification_key ~ '[^[:space:]]'),
    CONSTRAINT ck_findings_check_version_nonblank CHECK (check_version ~ '[^[:space:]]'),
    CONSTRAINT ck_findings_match_key_version CHECK (match_key_version = 1),
    CONSTRAINT ck_findings_policy_version CHECK (compatibility_policy_version = 1),
    CONSTRAINT ck_findings_context_hash_hex CHECK (relevant_context_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE finding_occurrences (
    tenant_id       text NOT NULL,
    source_event_id text NOT NULL,
    finding_id      uuid NOT NULL,
    CONSTRAINT pk_finding_occurrences PRIMARY KEY (tenant_id, source_event_id),
    CONSTRAINT fk_finding_occurrences_source_event
        FOREIGN KEY (tenant_id, source_event_id)
        REFERENCES source_events (tenant_id, source_event_id),
    CONSTRAINT fk_finding_occurrences_finding
        FOREIGN KEY (tenant_id, finding_id)
        REFERENCES findings (tenant_id, finding_id),
    CONSTRAINT ck_finding_occurrences_tenant_nonblank CHECK (tenant_id ~ '[^[:space:]]')
);

CREATE INDEX ix_finding_occurrences_finding ON finding_occurrences (tenant_id, finding_id);

CREATE TABLE test_attempts (
    tenant_id                    text NOT NULL,
    source_event_id              text NOT NULL,
    canonical_asset_id           uuid NOT NULL,
    verification_key             text NOT NULL,
    check_version                text NOT NULL,
    relevant_context_hash        text NOT NULL,
    compatibility_policy_version integer NOT NULL,
    execution_status             text NOT NULL,
    result                       text,
    CONSTRAINT pk_test_attempts PRIMARY KEY (tenant_id, source_event_id),
    CONSTRAINT fk_test_attempts_source_event
        FOREIGN KEY (tenant_id, source_event_id)
        REFERENCES source_events (tenant_id, source_event_id),
    CONSTRAINT fk_test_attempts_asset
        FOREIGN KEY (tenant_id, canonical_asset_id)
        REFERENCES assets (tenant_id, canonical_asset_id),
    CONSTRAINT ck_test_attempts_tenant_nonblank CHECK (tenant_id ~ '[^[:space:]]'),
    CONSTRAINT ck_test_attempts_verification_key_nonblank
        CHECK (verification_key ~ '[^[:space:]]'),
    CONSTRAINT ck_test_attempts_check_version_nonblank
        CHECK (check_version ~ '[^[:space:]]'),
    CONSTRAINT ck_test_attempts_policy_version CHECK (compatibility_policy_version = 1),
    CONSTRAINT ck_test_attempts_context_hash_hex CHECK (relevant_context_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_test_attempts_execution_status CHECK (execution_status IN
        ('completed', 'failed', 'blocked', 'partial', 'skipped')),
    CONSTRAINT ck_test_attempts_result CHECK (result IS NULL OR result IN
        ('detected', 'not_detected', 'inconclusive')),
    -- Mirrors the TASK-002 legality rules: only completed attempts carry a
    -- detection outcome; everything else is inconclusive or open. The explicit
    -- IS NOT NULL keeps a null result from making the completed branch pass
    -- through SQL null semantics.
    CONSTRAINT ck_test_attempts_outcome_pairing CHECK (
        (execution_status = 'completed'
            AND result IS NOT NULL AND result IN ('detected', 'not_detected'))
        OR (execution_status <> 'completed' AND (result IS NULL OR result = 'inconclusive')))
);

CREATE INDEX ix_test_attempts_coverage ON test_attempts
    (tenant_id, canonical_asset_id, verification_key, check_version,
     relevant_context_hash, compatibility_policy_version);
