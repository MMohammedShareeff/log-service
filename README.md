# Log Ingestion and Query Service

A service that ingests high-volume structured logs, stores them efficiently in PostgreSQL,
and supports filtering, aggregation, and configurable retention.

## Setup and Usage

```bash
docker compose up
```

The service listens on `localhost:8080`. No configuration is required for the core,
unauthenticated service to work.

## API Documentation

TODO

- `GET /health`
- `POST /logs`
- `GET /logs`
- `GET /logs/aggregate`

## Schema and Index Design

TODO

## Attribute Storage Strategy

TODO

## Retention Strategy

TODO

## Connection Pool Strategy
The service uses two separate HikariCP pools against the same PostgreSQL database, rather than one shared pool for everything:

### Write pool:
(spring.datasource.*, size DB_WRITE_POOL_MAX, default 6) — used for POST /logs ingestion, Flyway migrations, and retention maintenance.

### Read pool
(app.datasource.read.*, size DB_READ_POOL_MAX, default 5) — used exclusively by GET /logs and GET /logs/aggregate.

### Why split them:
with a single shared pool, a burst of ingestion traffic can exhaust every available connection, starving concurrent read queries even though reads and writes don't otherwise conflict. Under the spec's own performance requirement — aggregation queries must stay fast while ingestion is active — that coupling is exactly the failure mode to avoid. Separating the pools means sustained write load can never starve reads of a connection, and vice versa.
## Load Test Methodology & Measured Performance Results

TODO

- Test environment
- Dataset size
- Batch size used for ingestion
- Sustained ingestion rate achieved
- Query rate and latency percentiles (p50/p95/p99) under concurrent ingestion
- Resource usage during the test
- Bottlenecks discovered and optimizations applied

## Known Limitations

TODO

## Optional Features

TODO
