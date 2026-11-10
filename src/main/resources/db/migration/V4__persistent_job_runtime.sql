-- Additive scheduling/diagnostic metadata; existing registries and legacy tables are preserved.
ALTER TABLE dvp.jobs ADD COLUMN global_error_code varchar(80);
ALTER TABLE dvp.job_items ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX job_items_due ON dvp.job_items (job_id, status, next_attempt_at, item_key);
CREATE INDEX jobs_parent ON dvp.jobs (parent_job_id);
