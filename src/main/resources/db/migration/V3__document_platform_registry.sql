-- Static v2 registry only; dynamic vector tables are provisioned by later maintenance jobs.
CREATE SCHEMA dvp;

CREATE TABLE dvp.collections (
    id uuid PRIMARY KEY,
    slug varchar(100) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9_-]*$'),
    name varchar(200) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE dvp.documents (
    id uuid PRIMARY KEY,
    collection_id uuid NOT NULL REFERENCES dvp.collections(id),
    sequence_id bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    external_id varchar(512) COLLATE "C" NOT NULL CHECK (length(external_id) > 0),
    metadata jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(metadata) = 'object'),
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (collection_id, external_id),
    UNIQUE (id, collection_id)
);
CREATE INDEX documents_collection_scan ON dvp.documents(collection_id, sequence_id);

CREATE TABLE dvp.source_texts (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES dvp.documents(id) ON DELETE CASCADE,
    text_key varchar(200) COLLATE "C" NOT NULL,
    kind varchar(60) NOT NULL,
    language varchar(35),
    content text NOT NULL CHECK (length(content) > 0),
    content_hash char(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_id, text_key),
    UNIQUE (id, document_id)
);

CREATE TABLE dvp.embedding_profiles (
    id uuid PRIMARY KEY,
    slug varchar(100) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9_-]*$'),
    model varchar(255) NOT NULL,
    model_revision varchar(255) NOT NULL,
    dimensions integer NOT NULL CHECK (dimensions BETWEEN 1 AND 16000),
    execution_mode varchar(20) NOT NULL CHECK (execution_mode IN ('http', 'import_only')),
    provider_ref varchar(100),
    preparation jsonb NOT NULL CHECK (jsonb_typeof(preparation) = 'object'),
    fingerprint char(64) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((execution_mode = 'http' AND provider_ref IS NOT NULL)
        OR (execution_mode = 'import_only' AND provider_ref IS NULL))
);

CREATE TABLE dvp.indexations (
    id uuid PRIMARY KEY,
    collection_id uuid NOT NULL REFERENCES dvp.collections(id),
    slug varchar(100) NOT NULL CHECK (slug ~ '^[a-z0-9][a-z0-9_-]*$'),
    profile_id uuid NOT NULL REFERENCES dvp.embedding_profiles(id),
    selection jsonb NOT NULL,
    chunking jsonb NOT NULL,
    metric varchar(20) NOT NULL CHECK (metric IN ('cosine', 'l2', 'inner_product')),
    ann_strategy varchar(30) NOT NULL CHECK (ann_strategy IN ('hnsw_vector', 'hnsw_halfvec', 'none')),
    vector_table varchar(63) NOT NULL UNIQUE,
    state varchar(20) NOT NULL CHECK (state IN ('provisioning', 'ready', 'failed')),
    ann_state varchar(20) NOT NULL CHECK (ann_state IN ('disabled', 'pending', 'building', 'ready', 'failed')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (collection_id, slug),
    UNIQUE (id, collection_id)
);

CREATE TABLE dvp.vector_units (
    id uuid PRIMARY KEY,
    collection_id uuid NOT NULL,
    indexation_id uuid NOT NULL,
    document_id uuid NOT NULL,
    source_text_id uuid NOT NULL,
    source_revision bigint NOT NULL CHECK (source_revision > 0),
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    start_cp integer NOT NULL CHECK (start_cp >= 0),
    end_cp integer NOT NULL CHECK (end_cp > start_cp),
    input_hash char(64) NOT NULL,
    FOREIGN KEY (indexation_id, collection_id) REFERENCES dvp.indexations(id, collection_id),
    FOREIGN KEY (document_id, collection_id) REFERENCES dvp.documents(id, collection_id) ON DELETE CASCADE,
    FOREIGN KEY (source_text_id, document_id) REFERENCES dvp.source_texts(id, document_id) ON DELETE CASCADE,
    UNIQUE (indexation_id, source_text_id, source_revision, ordinal),
    UNIQUE (id, document_id, source_text_id, source_revision, indexation_id)
);
CREATE INDEX units_source ON dvp.vector_units(source_text_id);
CREATE INDEX units_document ON dvp.vector_units(document_id, indexation_id);
CREATE INDEX units_indexation ON dvp.vector_units(indexation_id, id);

CREATE TABLE dvp.jobs (
    id uuid PRIMARY KEY,
    collection_id uuid NOT NULL REFERENCES dvp.collections(id),
    indexation_id uuid REFERENCES dvp.indexations(id),
    parent_job_id uuid REFERENCES dvp.jobs(id),
    kind varchar(20) NOT NULL CHECK (kind IN ('import', 'prepare', 'generate', 'maintenance')),
    status varchar(30) NOT NULL CHECK (status IN
        ('queued', 'running', 'succeeded', 'completed_with_errors', 'failed')),
    parameters jsonb NOT NULL,
    checkpoint jsonb NOT NULL DEFAULT '{}',
    counters jsonb NOT NULL DEFAULT '{}',
    lease_owner varchar(200),
    lease_until timestamptz,
    fencing_token bigint NOT NULL DEFAULT 0,
    attempts integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);
CREATE INDEX jobs_claim ON dvp.jobs(status, available_at, lease_until);

CREATE TABLE dvp.import_files (
    job_id uuid PRIMARY KEY REFERENCES dvp.jobs(id),
    storage_key text NOT NULL UNIQUE,
    sha256 char(64) NOT NULL,
    format varchar(10) NOT NULL CHECK (format IN ('json', 'jsonl')),
    byte_size bigint NOT NULL CHECK (byte_size >= 0),
    expires_at timestamptz
);

CREATE TABLE dvp.job_items (
    job_id uuid NOT NULL REFERENCES dvp.jobs(id) ON DELETE CASCADE,
    item_key varchar(512) NOT NULL,
    document_id uuid REFERENCES dvp.documents(id) ON DELETE SET NULL,
    status varchar(20) NOT NULL CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'skipped')),
    locator jsonb NOT NULL DEFAULT '{}',
    error_code varchar(80),
    error_details jsonb,
    lease_owner varchar(200),
    lease_until timestamptz,
    fencing_token bigint NOT NULL DEFAULT 0,
    attempts integer NOT NULL DEFAULT 0,
    PRIMARY KEY (job_id, item_key)
);
CREATE INDEX job_items_claim ON dvp.job_items(job_id, status, lease_until);

CREATE TABLE dvp.idempotency_requests (
    scope varchar(200) NOT NULL,
    request_key varchar(200) NOT NULL,
    request_hash char(64) NOT NULL,
    response_status integer NOT NULL,
    response_body jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, request_key)
);

-- Replay headers and active-job retention are persisted alongside the atomic response.
ALTER TABLE dvp.idempotency_requests
    ADD COLUMN response_headers jsonb NOT NULL DEFAULT '{}',
    ADD COLUMN job_id uuid REFERENCES dvp.jobs(id);
CREATE INDEX idempotency_retention ON dvp.idempotency_requests(created_at);
