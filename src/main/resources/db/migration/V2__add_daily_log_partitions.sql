DO $$
DECLARE
    partition_date DATE;
    partition_name TEXT;
BEGIN
    FOR partition_date IN
        SELECT d::date
        FROM generate_series(CURRENT_DATE - 3, CURRENT_DATE + 14, INTERVAL '1 day') AS d
    LOOP
        partition_name := 'logs_' || to_char(partition_date, 'YYYY_MM_DD');

        IF to_regclass('public.' || partition_name) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF logs FOR VALUES FROM (%L) TO (%L)',
                partition_name,
                partition_date,
                partition_date + 1
            );
        END IF;
    END LOOP;
END $$;

DROP INDEX IF EXISTS idx_logs_timestamp;
CREATE INDEX IF NOT EXISTS idx_logs_timestamp_id ON logs (timestamp DESC, id DESC);
