-- Run on an empty database or a verified v1 database baselined at version 0.
-- The legacy store registry and its per-store vector tables are preserved.
CREATE TABLE IF NOT EXISTS public.stores (
    id uuid PRIMARY KEY,
    name varchar(60) NOT NULL CONSTRAINT uk_store_name UNIQUE,
    model varchar(60) NOT NULL,
    provider varchar(20) NOT NULL,
    embedding_dimension integer,
    created_at timestamptz NOT NULL,
    CONSTRAINT uk_store_model_dimension UNIQUE (model, embedding_dimension)
);

-- Fail without modifying or disclosing conflicting rows. Operators resolve them.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM public.stores GROUP BY lower(name) HAVING count(*) > 1)
       OR EXISTS (SELECT 1 FROM public.stores WHERE embedding_dimension IS NOT NULL
                  GROUP BY lower(model), embedding_dimension HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Legacy store duplicates found; resolve case-insensitive conflicts before migration';
    END IF;
END $$;
CREATE UNIQUE INDEX uk_store_name_ci ON public.stores (lower(name));
CREATE UNIQUE INDEX uk_store_model_dimension_ci ON public.stores (lower(model), embedding_dimension);

