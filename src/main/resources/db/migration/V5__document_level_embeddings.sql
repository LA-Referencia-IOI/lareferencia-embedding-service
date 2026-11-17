-- Add document-level embedding units without changing or removing existing text units.
ALTER TABLE dvp.vector_units
    ADD COLUMN embedding_key varchar(200),
    ALTER COLUMN source_text_id DROP NOT NULL,
    ALTER COLUMN source_revision DROP NOT NULL,
    ALTER COLUMN start_cp DROP NOT NULL,
    ALTER COLUMN end_cp DROP NOT NULL,
    ALTER COLUMN input_hash DROP NOT NULL,
    ADD CONSTRAINT vector_units_identity UNIQUE (id, document_id, indexation_id),
    ADD CONSTRAINT vector_units_document_embedding_key UNIQUE (indexation_id, document_id, embedding_key),
    ADD CONSTRAINT vector_units_representation CHECK (
      (embedding_key IS NULL AND source_text_id IS NOT NULL AND source_revision IS NOT NULL
       AND start_cp IS NOT NULL AND end_cp IS NOT NULL AND input_hash IS NOT NULL)
      OR
      (embedding_key IS NOT NULL AND length(embedding_key)>0 AND source_text_id IS NULL AND source_revision IS NULL
       AND start_cp IS NULL AND end_cp IS NULL AND input_hash IS NULL)
    );

-- Extend existing service-owned vector tables in place; rows and indexes remain intact.
DO $$
DECLARE entry record; expected_name text;
BEGIN
  FOR entry IN SELECT id,vector_table FROM dvp.indexations LOOP
    expected_name := 'vec_' || replace(entry.id::text,'-','');
    IF entry.vector_table <> expected_name THEN
      RAISE EXCEPTION 'Invalid indexation table identity; migration aborted';
    END IF;
    IF to_regclass(format('dvp.%I',expected_name)) IS NOT NULL THEN
      EXECUTE format('ALTER TABLE dvp.%I ALTER COLUMN source_text_id DROP NOT NULL, ALTER COLUMN source_revision DROP NOT NULL, ALTER COLUMN input_hash DROP NOT NULL',expected_name);
      EXECUTE format('ALTER TABLE dvp.%I ADD CONSTRAINT %I FOREIGN KEY (unit_id,document_id,indexation_id) REFERENCES dvp.vector_units(id,document_id,indexation_id) ON DELETE CASCADE',expected_name,expected_name || '_unit_scope_fk');
      EXECUTE format('ALTER TABLE dvp.%I ADD CONSTRAINT %I CHECK ((source_text_id IS NOT NULL AND source_revision IS NOT NULL AND input_hash IS NOT NULL) OR (source_text_id IS NULL AND source_revision IS NULL AND input_hash IS NULL))',expected_name,expected_name || '_representation_ck');
    END IF;
  END LOOP;
END $$;
