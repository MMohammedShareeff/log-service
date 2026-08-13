import http from "k6/http";
import { check } from "k6";
import { Counter, Trend } from "k6/metrics";
import { buildBatch } from "./lib/log-generator.js";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const BATCH_SIZE = parseInt(__ENV.BATCH_SIZE || "2000", 10);
const TARGET_LOGS_PER_SEC = parseInt(__ENV.TARGET_LOGS_PER_SEC || "15000", 10);
const DURATION = __ENV.DURATION || "60s";
const MAX_AGE_MS = parseInt(__ENV.MAX_AGE_MS || String(5 * 60 * 1000), 10);
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
      preAllocatedVUs: parseInt(__ENV.INGEST_VUS || "50", 10),
      maxVUs: parseInt(__ENV.INGEST_MAX_VUS || "200", 10),
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
  const since = new Date(until.getTime() - 5 * 60 * 1000);
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
  return {
    stdout: JSON.stringify(data, null, 2),
  };
}
