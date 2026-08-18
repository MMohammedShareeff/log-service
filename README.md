# Log Ingestion and Query Service

A high-throughput log ingestion and query service. Applications send structured logs to the API; the service validates, stores them in PostgreSQL, and supports filtering, time-bucketed aggregation, and configurable retention under sustained ingestion load.

Built with Kotlin, Spring Boot, JdbcTemplate, and PostgreSQL 16. Runs entirely via docker compose up.

## Setup and Usage
### pre-requirements:
Docker desktop (or any docker service) up and running.

The system will be up and running using the below command
```bash
docker compose up
```
The service listens on `localhost:8080`. No configuration is required for the core,
unauthenticated service to work.



### Resource limits
the system is working with a strict resource requirements which is: 

| Container | CPU | Memory |
| --- | --- | --- |
| app | 0.5 | 256 MB |
| postgres | 1.0 | 1 GB |

## API Documentation

### GET /health
returns 200 when ready, 503 otherwise, Always unauthenticated.

### POST /logs
```json
{
  "logs": [
    {
      "timestamp": "2026-08-18T12:00:00.000Z",
      "level": "error",
      "service": "checkout",
      "message": "payment declined",
      "attributes": { "user_id": "42", "region": "eu-west", "retries": 3 }
    }
  ]
}
```

#### Validation (per entry):
* **timestamp**: — ISO 8601, not more than 5 minutes in the future
* **level**: one of debug | info | warn | error
* **service, message**: non-empty strings
* **attributes**: optional flat object of strings, numbers, or booleans only

**Invalid entries are rejected individually. valid ones are still accepted.**

| Case | Status |
| :--- | :--- |
| At least one entry accepted | 200 |
| Empty batch (`{"logs":[]}`) | 400 |
| Malformed JSON / wrong body shape | 400 |

**Example success body: 
```json
{
  "accepted": 9,
  "rejected": [{ "index": 3, "reason": "invalid level: 'critical'" }]
}
```

### GET /logs
| Parameter | Meaning |
| :--- | :--- |
| `service` | Exact service match |
| `level` | Exact level match |
| `since` / `until` | Time range |
| `attr.<key>` | Attribute equality (string comparison; numeric/boolean stored values still match) |
| `q` | Case-insensitive substring on `message` |
| `limit` | Max results (default 100, max 1000) |
| `cursor` | Opaque cursor from a previous response |

Results are ordered by timestamp DESC, then id DESC. Pagination uses next_cursor.

### GET /logs/aggregate
Supports the same filters as list (service, level, attr.*, q), plus:

| Parameter | Required | Values |
| :--- | :--- | :--- |
| `since` / `until` | Yes | ISO 8601 range |
| `bucket` | Yes | `1m` , `5m` , `1h` , `1d` |
| `group_by` | No | `service` or `level` |

**Response**: one row per bucket × group (empty buckets omitted).

## Schema and Index Design
logs is range-partitioned by day on timestamp, with a logs_default catch-all partition.

```text
logs (PARTITION BY RANGE (timestamp))
├── id          BIGINT GENERATED ALWAYS AS IDENTITY
├── timestamp   TIMESTAMPTZ NOT NULL
├── level       TEXT CHECK (debug|info|warn|error)
├── service     TEXT NOT NULL
├── message     TEXT NOT NULL
├── attributes  JSONB NOT NULL DEFAULT '{}'
└── PRIMARY KEY (id, timestamp)
```


**Why daily partitions**:
* Retention is **DROP TABLE** (fast, no bloat), not bulk DELETE
* Indexes stay **per-partition** and *small*
* Fits “~1M rows ≈ one month” style workloads

**Indexes**(B-tree on the hot path)
* Primary key / (timestamp DESC, id DESC) for range scans and cursors
* service, level

before i added trigram GIN indexes but they were dropped to reduce write amplifications
under strict resource requirements.


### Rollup table(rog_rollups)
Minute-granularity pre-aggregation, updated in the same transaction as log inserts:
```text
log_rollups
├── bucket   TIMESTAMPTZ  -- minute-aligned
├── service  TEXT
├── level    TEXT
├── count    BIGINT
└── PRIMARY KEY (bucket, service, level)
```

**GET /logs/aggregate** uses rollups when there is no q / attr.* filter; otherwise it falls back to scanning logs.

## Attribute Storage Strategy
Attributes are stored once in JSONB as submitted (string, number, or boolean).

attr.<key>=value uses a type-branched containment check (@>) so a string filter can still match numeric/boolean stored values. There is no mirror attributes_text column and no GIN index on attributes.

## Retention Strategy
* **RETENTION_DAYS** (default 30): daily partitions fully older than this are dropped
* **PARTITION_DAYS_AHEAD** (default 7): future daily partitions are pre-created so ingest rarely hits logs_default
* **RetentionScheduler**: Runs at application startup and on a daily cron.
* Also **deletes** expired rows from **log_rollups** (WHERE bucket < cutoff).

## Connection Pool Strategy
Two Hikari pools against the same database:

| Pool | Config | Default size | Used for |
| :--- | :--- | :--- | :--- |
| **Write** | `spring.datasource.*` / `DB_WRITE_POOL_MAX` | 6 | `POST /logs` , Flyway, retention |
| **Read** | `app.datasource.read.*` / `DB_READ_POOL_MAX` | 5 | `GET /logs` , `GET /logs/aggregate` |

### Why splitting them
Splitting pools prevents sustained ingest from starving concurrent list/aggregate queries under the graded limits.

## Ingest Path optimizations
* **Copy fr
* **Write coalescing (IngestCoalescer)**: concurrent POST /logs within INGEST_COALESCE_WINDOW_MS (default 5 ms) are merged into larger flushes, capped by INGEST_COALESCE_MAX_ROWS (default 2000)
*  **Rollup upsert** in the same DB transaction as the log insert
* **Virtual threads** enabled for blocking I/O concurrency on a small CPU budget

## Configuration
All variables have defaults; none are required for the basic Compose setup.

| Variable | Default |
| :--- | :--- |
| `SPRING_DATASOURCE_URL` | Compose $\rightarrow$ `jdbc:postgresql://postgres:5432/logs` |
| `SPRING_DATASOURCE_USERNAME` / `PASSWORD` | `logs` / `logs` |
| `DB_WRITE_POOL_MAX` | 6 |
| `DB_READ_POOL_MAX` | 5 |
| `INGEST_BATCH_SIZE` | 1000 |
| `INGEST_COALESCE_WINDOW_MS` | 5 |
| `INGEST_COALESCE_MAX_ROWS` | 2000 |
| `RETENTION_DAYS` | 30 |
| `PARTITION_DAYS_AHEAD` | 7 |
| `AUTH_ENABLED` | `false` |


## Load Test Methodology & Measured Performance Results

### FTS tool (mainly used and the registered results tested with it)

the main testing platform was  FTS tool which is an automated end-to-end benchmark suite designed 
to evaluate, test, and score log-processing backend services under varying operational conditions.

It acts as an orchestrated load generator and compliance validator that measures how well a log ingestion and analytics engine handles real-world traffic.

### Core Responsibilities
* **Correctness & Compliance Verification**: Runs standard API checks against the service to ensure functional endpoints comply with requirements (e.g., log batch ingestion, search filters, pagination cursors, and metric aggregation).

* **Multi-Scenario Testing**: Uses Docker-isolated k6 generators to execute distinct load profiles—such as steady load, stress, extreme spikes, and breakpoint tests—to measure maximum throughput, error rates, and p95 latency.

* **Data Consistency & Query Benchmarking**: Evaluates eventual consistency and read-after-write success by tracking how fast written logs become visible to analytical and aggregation queries.

* **Scoring Engine**: Calculates an overall rating (out of 100 points) divided across four weighted categories: Correctness, Performance, Queries, and Reliability.


---
### local k6 (self testing)
i added k6 for local self testing but they were providing very bad results. they depend on the device performance but they were very bad.
i tried to fix them but did not success. however!! they are still in the project and they are under k6/: 

| Script | Purpose |
| :--- | :--- |
| `k6/seed.js` | Pre-load ~1M rows |
| `k6/ingest-only.js` | Pure ingest throughput |
| `k6/load-test.js` | Ingest + aggregate |
| `k6/full-test.js` | Ingest + aggregate + consistency (time-to-visible) |


#### Example usage:
```bash
# seed
k6 run -e TARGET_ROWS=1100000 -e BATCH_SIZE=1000 k6/seed.js

# full scenario
k6 run -e TARGET_LOGS_PER_SEC=15000 -e DURATION=120s -e BATCH_SIZE=2000 k6/full-test.js
```

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

