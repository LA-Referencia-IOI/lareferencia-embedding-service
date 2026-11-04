-- Fail without modifying or disclosing conflicting rows. Operators resolve them.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM stores GROUP BY lower(name) HAVING count(*) > 1)
       OR EXISTS (SELECT 1 FROM stores WHERE embedding_dimension IS NOT NULL
                  GROUP BY lower(model), embedding_dimension HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Legacy store duplicates found; resolve case-insensitive conflicts before migration';
    END IF;
END $$;
CREATE UNIQUE INDEX uk_store_name_ci ON stores (lower(name));
CREATE UNIQUE INDEX uk_store_model_dimension_ci ON stores (lower(model), embedding_dimension);
