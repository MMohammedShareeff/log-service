// The actual measured test - run this AFTER seed.js has pushed the table past 1M rows.
// Two concurrent scenarios for the duration of the run:
//   - "ingest": sustained batch ingestion targeting TARGET_LOGS_PER_SEC logs/sec
//   - "aggregate": one GET /logs/aggregate request per second (per the spec's explicit target)
//
// This mirrors the spec's actual requirement: ingestion reliable AND queries fast
// WHILE the system already holds 1M+ rows - not two separate tests glued together.
//
// Usage:
//   k6 run k6/load-test.js
//   k6 run -e TARGET_LOGS_PER_SEC=15000 -e BATCH_SIZE=500 -e DURATION=120s k6/load-test.js
//
// Start conservative (e.g. TARGET_LOGS_PER_SEC=5000) while you're still tuning batch size /
// pool size, then push toward the real 15000 target once the pipeline is stable.

import http from "k6/http";
import { check } from "k6";
import { Counter, Trend } from "k6/metrics";
import { buildBatch } from "./lib/log-generator.js";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const BATCH_SIZE = parseInt(__ENV.BATCH_SIZE || "500", 10);
const TARGET_LOGS_PER_SEC = parseInt(__ENV.TARGET_LOGS_PER_SEC || "15000", 10);
const DURATION = __ENV.DURATION || "60s";
// Recent window for ingested timestamps - simulates live traffic, unlike seed.js's 3-day spread.
const MAX_AGE_MS = parseInt(__ENV.MAX_AGE_MS || String(5 * 60 * 1000), 10);

// constant-arrival-rate operates on requests/sec, but our unit of "throughput" is logs/sec -
// convert here so TARGET_LOGS_PER_SEC stays the meaningful knob to tune.
const batchRatePerSec = Math.max(
  1,
  Math.round(TARGET_LOGS_PER_SEC / BATCH_SIZE)
);

export const options = {
  scenarios: {
    ingest: {
      executor: "constant-arrival-rate",
      rate: batchRatePerSec,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: parseInt(__ENV.INGEST_VUS || "10", 10),
      maxVUs: parseInt(__ENV.INGEST_MAX_VUS || "20", 10),
      exec: "ingest",
    },
    aggregate: {
      executor: "constant-arrival-rate",
      rate: 1,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: 5,
      maxVUs: 10,
      exec: "aggregate",
    },
  },
  thresholds: {
    // The spec's explicit target: aggregation p95 under 1s while ingestion is active.
    "http_req_duration{scenario:aggregate}": ["p(95)<1000"],
    "checks{scenario:ingest}": ["rate>0.99"],
    "checks{scenario:aggregate}": ["rate>0.99"],
  },
};

const acceptedLogs = new Counter("load_accepted_logs");
const rejectedLogs = new Counter("load_rejected_logs");
const aggregateDuration = new Trend("aggregate_query_duration", true);

export function ingest() {
  const payload = buildBatch(BATCH_SIZE, MAX_AGE_MS);
  const res = http.post(`${BASE_URL}/logs`, JSON.stringify(payload), {
    headers: { "Content-Type": "application/json" },
    tags: { scenario: "ingest" },
  });

  const ok = check(
    res,
    { "ingest status is 200": (r) => r.status === 200 },
    { scenario: "ingest" }
  );

  if (ok) {
    const body = res.json();
    acceptedLogs.add(body.accepted || 0);
    rejectedLogs.add((body.rejected || []).length);
  } else {
    console.error(`Ingest failed: ${res.status} ${res.body}`);
  }
}

export function aggregate() {
  const until = new Date();
  const since = new Date(until.getTime() - 5 * 60 * 1000); // trailing 5-minute window
  const url =
    `${BASE_URL}/logs/aggregate?since=${since.toISOString()}` +
    `&until=${until.toISOString()}&bucket=1m`;

  const res = http.get(url, { tags: { scenario: "aggregate" } });

  check(
    res,
    { "aggregate status is 200": (r) => r.status === 200 },
    { scenario: "aggregate" }
  );

  aggregateDuration.add(res.timings.duration);
}

export function handleSummary(data) {
  const accepted = data.metrics.load_accepted_logs
    ? data.metrics.load_accepted_logs.values.count
    : 0;
  const durationSeconds =
    data.state && data.state.testRunDurationMs
      ? data.state.testRunDurationMs / 1000
      : null;

  console.log(`\n=== Load Test Summary ===`);
  console.log(
    `Target ingestion rate: ${TARGET_LOGS_PER_SEC} logs/sec (batch size ${BATCH_SIZE})`
  );
  console.log(`Total logs accepted:   ${accepted}`);
  if (durationSeconds) {
    console.log(
      `Achieved rate:         ${(accepted / durationSeconds).toFixed(
        0
      )} logs/sec`
    );
  }
  if (
    data.metrics.aggregate_query_duration &&
    data.metrics.aggregate_query_duration.values["p(95)"] !== undefined
  ) {
    console.log(
      `Aggregate p95 latency: ${data.metrics.aggregate_query_duration.values[
        "p(95)"
      ].toFixed(1)} ms`
    );
  }
  console.log(`\nCopy these numbers into README.md's performance section.\n`);

  return {
    stdout: JSON.stringify(data, null, 2),
  };
}
