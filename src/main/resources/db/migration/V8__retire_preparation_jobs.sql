-- Keep history and release outstanding preparation jobs from the retired worker.
UPDATE dvp.job_items i
SET status = 'failed', error_code = 'job_kind_unavailable', lease_owner = NULL, lease_until = NULL
FROM dvp.jobs j
WHERE i.job_id = j.id AND j.kind = 'prepare' AND j.status IN ('queued', 'running')
  AND i.status IN ('queued', 'running');

UPDATE dvp.jobs
SET status = 'failed', global_error_code = 'job_kind_unavailable', finished_at = clock_timestamp(),
    lease_owner = NULL, lease_until = NULL
WHERE kind = 'prepare' AND status IN ('queued', 'running');
