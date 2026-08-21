# Log Ingestion and Query Service

A high-throughput log ingestion and query service.
Applications send structured logs to the API; the service validates, stores them in PostgreSQL, and supports filtering,
time-bucketed aggregation, and configurable retention under sustained ingestion load.

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
* (service, timestamp DESC, id DESC) — service filter + same sort order as list pagination
* (level, timestamp DESC, id DESC) — level filter + same sort order as list pagination

Narrow single-column service / level indexes were replaced by these composites so filtered GET /logs can satisfy filter + ORDER BY timestamp DESC,
id DESC + cursor without an extra sort or a second lookup.
Trigram / JSONB GIN indexes were dropped earlier to cut write amplification under the graded CPU/memory limits; q and attr.* still work via partition-pruned scans.


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

**This was chosen over a separate EAV (key/value) table**: normalizes attributes into rows, but a 1M+ row logs table would need a join against a much larger attributes table for every filtered query, which is expensive exactly where the spec demands sub-second aggregation.
## Retention Strategy
Retention is partition-based. not row based. reasons are mentions in schema and index design section.
* **RETENTION_DAYS** (default 30): daily partitions fully older than this are dropped
* **PARTITION_DAYS_AHEAD** (default 7): future daily partitions are pre-created so ingest rarely hits logs_default
* **RetentionScheduler**: Runs at application startup and on a daily cron.
* Also **deletes** expired rows from **log_rollups** (WHERE bucket < cutoff).

## Connection Pool Strategy
Two Hikari pools against the same database:

| Pool | Config | Default size | Used for |
| :--- | :--- |:-------------| :--- |
| **Write** | `spring.datasource.*` / `DB_WRITE_POOL_MAX` | 10           | `POST /logs` , Flyway, retention |
| **Read** | `app.datasource.read.*` / `DB_READ_POOL_MAX` | 5            | `GET /logs` , `GET /logs/aggregate` |

### Why splitting them
Splitting pools prevents sustained ingest from starving concurrent list/aggregate queries under the graded limits.

## Ingest Path optimizations
* **COPY instead of INSERT/UNNEST**: bulk inserts use PostgreSQL's native COPY FROM STDIN protocol (via the driver's CopyManager) instead of a batched INSERT ... SELECT * FROM unnest(...) - bypasses the SQL parser/planner entirely for the insert path. Measured locally as the single biggest throughput lever, taking sustained ingestion from ~7-50 logs/sec to ~2,400 logs/sec under the same load.
* **Write coalescing (IngestCoalescer)**: concurrent POST /logs within INGEST_COALESCE_WINDOW_MS (default 5 ms) are merged into larger flushes, capped by INGEST_COALESCE_MAX_ROWS (default 2000)
*  **Rollup upsert** in the same DB transaction as the log insert
* **Virtual threads** enabled for blocking I/O concurrency on a small CPU budget.
* **fine tuned write pool size** to fit the amount of data (trial and error till fixed on 10).

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

### Performance Metrics

**This run measured about half the reference machine speed (0.50×)**.

so the performance score should be read as a rough signal,
not a fair cross-machine ranking. In every scenario the bottleneck was the load generator, not your service:
k6 ran out of CPU and skipped scheduled iterations, which means the reported logs/s are a lower bound on what the API could take. 
Treat these figures in that light, and only stack them against other results from this same host.

## Measured results

| Metric | Value |
| :--- | :--- |
| **Engine (Docker Desktop)** | 8 CPUs, ~6 GiB |
| **Generator (k6, isolated container)** | 2 CPUs, 1 GB |
| **Machine speed factor** | 0.50× reference |
| **Resource limits enforced** | app **0.5 CPU / 256 MB** · database **1.0 CPU / 1 GB** |
| **Dataset seeded** | (seeded by benchmark; load accepted **1,764,900** rows in the load scenario) |

**Correctness catalog**: 15 / 15 checks passed, covering health, ingestion (single / batch / partial-invalid / empty / malformed-json),
query (unfiltered / filters / invalid-parameters), pagination (stable order / cursor / invalid cursor), and aggregation (buckets / grouping / invalid options).

| Scenario | Offered logs/s | Accepted logs/s | Error rate | Ingest p95 | Aggregate p95 | Accepted records | Dropped generator iterations |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **load** | 15,000 | 14,707.5 | 0% | 136 ms | 87 ms | 1,764,900 | 350 |
| **stress** | 21,000 | 18,470 | 0% | 66 ms | 14 ms | 2,770,500 | 3,343 |
| **spike** | 15,375 | 0 | 100% | 0 ms | 59,997 ms | 0 | 12,654 |
| **breakpoint** | 24,375 | 0 | 100% | 0 ms | 30,001 ms | 0 | 26,049 |

None of the four scenarios aborted, and the process never crashed. Under load, everything that was accepted also showed up in reads afterward. Stress did not meet the eventual-visibility bar for that scenario (visibleRecords stayed at 0). 
Spike and breakpoint accepted no rows once the generator/overloaded path fell apart.
ASeparately, the benchmark’s strict “read it back immediately after write” check is not the same as eventual visibility: it passed on roughly 70% of load probes and 41% of stress probes.

| Score component | Points | Max | Notes |
| :--- | :--- | :--- | :--- |
| **Correctness** | **15.0** | **15** | 15/15 checks |
| **Performance** | **44.2** | **50** | throughput ~14.7k/s · errors 0% · ingest p95 ~136 ms · sustained bonus 0 |
| **Queries** | **11.9** | **15** | aggregate p95 ~87 ms · eventual consistency **3/4** · read-after-write ~0.70 |
| **Reliability** | **20.0** | **20** | 4/4 scenarios completed, crash-free |
| **Total** | **91.1** | **100** | |

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

## Known Limitations

* **No deadlock retry on the rollup upsert :** Concurrent rollup writes are sorted by (bucket, service, level) before upserting, 
    which prevents most deadlocks by keeping lock acquisition order consistent across transactions - but there's no retry wrapper for the residual case (e.g. contention with an unrelated concurrent operation). 
    A deadlock here would currently surface as an error rather than being retried transparently.
* **No automated regression tests**: The project doesn't currently have a unit or integration test suite. Two real bugs (an ambiguous GROUP BY column reference, and a cursor-decoding edge case) were found via production stack traces rather than caught by tests
  both are fixed, but nothing currently guards against similar regressions being reintroduced later.

