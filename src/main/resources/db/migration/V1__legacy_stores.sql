-- Fresh installs only. Existing installations explicitly baseline at version 1
-- after the documented inventory/preflight; baseline-on-migrate is OFF by default.
CREATE TABLE stores (
    id uuid PRIMARY KEY,
    name varchar(60) NOT NULL CONSTRAINT uk_store_name UNIQUE,
    model varchar(60) NOT NULL,
    provider varchar(20) NOT NULL,
    embedding_dimension integer,
    created_at timestamptz NOT NULL,
    CONSTRAINT uk_store_model_dimension UNIQUE (model, embedding_dimension)
);
