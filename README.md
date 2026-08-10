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
