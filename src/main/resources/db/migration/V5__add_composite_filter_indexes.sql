
DROP INDEX IF EXISTS idx_logs_service;
DROP INDEX IF EXISTS idx_logs_level;

CREATE INDEX idx_logs_service_timestamp_id ON logs (service, timestamp DESC, id DESC);
CREATE INDEX idx_logs_level_timestamp_id ON logs (level, timestamp DESC, id DESC);