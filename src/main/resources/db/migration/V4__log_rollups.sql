CREATE TABLE IF NOT EXISTS log_rollup (
    bucket_start TIMESTAMPTZ NOT NULL,
    service      TEXT        NOT NULL,
    level        TEXT        NOT NULL CHECK (level IN ('debug', 'info', 'warn', 'error')),
    count        BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (bucket_start, service, level)
);

CREATE INDEX idx_rollup_bucket ON log_rollup (bucket_start ASC);

INSERT INTO log_rollup (bucket_start, service, level, count)
SELECT
    date_trunc('minute', timestamp) AS bucket_start,
    service,
    level,
    count(*) AS count
FROM logs
GROUP BY 1, 2, 3
ON CONFLICT (bucket_start, service, level)
DO UPDATE SET count = log_rollup.count + EXCLUDED.count;