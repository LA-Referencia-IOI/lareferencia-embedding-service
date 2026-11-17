-- Preserve prepared text as a snapshot before dropping source-text associations.
ALTER TABLE dvp.vector_units ADD COLUMN prepared_text text;
ALTER TABLE dvp.vector_units ADD COLUMN prepared_key varchar(100);
UPDATE dvp.vector_units u SET
  prepared_text=substring(s.content FROM u.start_cp+1 FOR u.end_cp-u.start_cp),
  prepared_key='legacy:' || u.id::text
FROM dvp.source_texts s WHERE s.id=u.source_text_id;

DO $$
DECLARE entry record; expected_name text;
BEGIN
  IF EXISTS (SELECT 1 FROM dvp.vector_units WHERE source_text_id IS NOT NULL AND prepared_text IS NULL) THEN
    RAISE EXCEPTION 'Cannot migrate a unit without its source text';
  END IF;
  FOR entry IN SELECT id,vector_table FROM dvp.indexations LOOP
    expected_name := 'vec_' || replace(entry.id::text,'-','');
    IF entry.vector_table <> expected_name THEN
      RAISE EXCEPTION 'Invalid indexation table identity; migration aborted';
    END IF;
    IF to_regclass(format('dvp.%I',expected_name)) IS NOT NULL THEN
      EXECUTE format('ALTER TABLE dvp.%I DROP COLUMN source_text_id CASCADE, DROP COLUMN source_revision CASCADE',expected_name);
    END IF;
  END LOOP;
END $$;

ALTER TABLE dvp.vector_units DROP COLUMN source_text_id CASCADE;
ALTER TABLE dvp.vector_units DROP COLUMN source_revision CASCADE;
CREATE UNIQUE INDEX units_document_prepared_key ON dvp.vector_units(indexation_id,document_id,prepared_key) WHERE prepared_key IS NOT NULL;
ALTER TABLE dvp.vector_units ADD CONSTRAINT vector_units_snapshot_check CHECK (
  (prepared_key IS NULL AND prepared_text IS NULL AND embedding_key IS NOT NULL)
  OR (prepared_key IS NOT NULL AND prepared_text IS NOT NULL AND embedding_key IS NULL AND input_hash IS NOT NULL)
);
