-- Progress is tracked separately from document-owned vector units.
CREATE TABLE dvp.text_indexation_progress (
    indexation_id uuid NOT NULL REFERENCES dvp.indexations(id) ON DELETE CASCADE,
    source_id uuid NOT NULL REFERENCES dvp.source_texts(id) ON DELETE CASCADE,
    revision bigint NOT NULL CHECK (revision > 0),
    expected_units integer NOT NULL CHECK (expected_units > 0),
    state varchar(16) NOT NULL CHECK (state IN ('prepared','complete')),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (indexation_id, source_id)
);
CREATE INDEX text_indexation_progress_source ON dvp.text_indexation_progress(source_id);
