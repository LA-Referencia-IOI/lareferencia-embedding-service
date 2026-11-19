-- Keep store names unique and preserve each store's existing vector table.
-- Legacy installations may have used a generated or custom constraint name.
DO $$
DECLARE constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT c.conname
        FROM pg_constraint c
        WHERE c.conrelid = 'public.stores'::regclass AND c.contype = 'u'
          AND (SELECT array_agg(a.attname::text ORDER BY a.attname::text)
               FROM unnest(c.conkey) AS k(attnum)
               JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum)
              = ARRAY['embedding_dimension', 'model']::text[]
    LOOP
        EXECUTE format('ALTER TABLE public.stores DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;
DROP INDEX IF EXISTS public.uk_store_model_dimension_ci;
