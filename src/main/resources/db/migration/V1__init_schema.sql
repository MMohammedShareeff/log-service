
CREATE EXTENSION IF NOT EXISTS pg_trgm;


CREATE TABLE logs (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    timestamp   TIMESTAMPTZ NOT NULL,
    level       TEXT NOT NULL CHECK (level IN ('debug', 'info', 'warn', 'error')),
    service     TEXT NOT NULL,
    message     TEXT NOT NULL,
    attributes  JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (id, timestamp)  
) PARTITION BY RANGE (timestamp);


CREATE INDEX idx_logs_timestamp ON logs (timestamp DESC);

CREATE INDEX idx_logs_service ON logs (service);
CREATE INDEX idx_logs_level ON logs (level);
CREATE INDEX idx_logs_attributes ON logs USING GIN (attributes jsonb_path_ops);
CREATE INDEX idx_logs_message_trgm ON logs USING GIN (message gin_trgm_ops);
CREATE TABLE logs_default PARTITION OF logs DEFAULT;
