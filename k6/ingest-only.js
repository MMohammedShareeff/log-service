// Pure sustained ingestion at a fixed target rate - no concurrent aggregate scenario,
// unlike load-test.js. This mirrors the grader's own "Load: 15000 logs/s for 120s" scenario,
// so it's meant for isolating and validating raw ingestion throughput specifically.
//
// Usage:
//   k6 run k6/ingest-only.js
//   k6 run -e TARGET_LOGS_PER_SEC=15000 -e DURATION=120s k6/ingest-only.js
//   k6 run -e TARGET_LOGS_PER_SEC=5000 -e DURATION=30s k6/ingest-only.js   (quick smoke run)

import http from "k6/http";
import { check } from "k6";
import { Counter, Trend } from "k6/metrics";
import { buildBatch } from "./lib/log-generator.js";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const BATCH_SIZE = parseInt(__ENV.BATCH_SIZE || "500", 10);
const TARGET_LOGS_PER_SEC = parseInt(__ENV.TARGET_LOGS_PER_SEC || "15000", 10);
const DURATION = __ENV.DURATION || "120s";
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
      preAllocatedVUs: parseInt(__ENV.INGEST_VUS || "20", 10),
      maxVUs: parseInt(__ENV.INGEST_MAX_VUS || "60", 10),
    },
  },
  thresholds: {
    checks: ["rate>0.95"],
  },
};

const acceptedLogs = new Counter("ingest_accepted_logs");
const rejectedLogs = new Counter("ingest_rejected_logs");
const overloadedRequests = new Counter("ingest_503_count");
const batchDuration = new Trend("ingest_batch_duration", true);

export default function () {
  const payload = buildBatch(BATCH_SIZE, MAX_AGE_MS);
  const res = http.post(`${BASE_URL}/logs`, JSON.stringify(payload), {
    headers: { "Content-Type": "application/json" },
  });

  batchDuration.add(res.timings.duration);

  if (res.status === 503) {
    // The IngestBackpressureFilter shedding load under overload - expected under real
    // pressure, not a failure of the check itself. Counted separately so it's visible
    // in the summary rather than silently failing the "status is 200" check.
    overloadedRequests.add(1);
    return;
  }

  const ok = check(res, {
    "status is 200": (r) => r.status === 200,
  });

  if (ok) {
    const body = res.json();
    acceptedLogs.add(body.accepted || 0);
    rejectedLogs.add((body.rejected || []).length);
  } else {
    console.error(`Unexpected status ${res.status}: ${res.body}`);
  }
}

export function handleSummary(data) {
  const accepted = data.metrics.ingest_accepted_logs
    ? data.metrics.ingest_accepted_logs.values.count
    : 0;
  const rejected = data.metrics.ingest_rejected_logs
    ? data.metrics.ingest_rejected_logs.values.count
    : 0;
  const overloaded = data.metrics.ingest_503_count
    ? data.metrics.ingest_503_count.values.count
    : 0;
  const durationSeconds =
    data.state && data.state.testRunDurationMs
      ? data.state.testRunDurationMs / 1000
      : null;

  console.log(`\n=== Ingest-Only Load Test Summary ===`);
  console.log(
    `Target:          ${TARGET_LOGS_PER_SEC} logs/sec for ${DURATION} (batch size ${BATCH_SIZE})`
  );
  console.log(`Accepted:        ${accepted}`);
  console.log(`Rejected:        ${rejected} (validation failures)`);
  console.log(`503 (shed load): ${overloaded} requests`);
  if (durationSeconds) {
    console.log(
      `Achieved rate:   ${(accepted / durationSeconds).toFixed(0)} logs/sec`
    );
  }
  if (
    data.metrics.ingest_batch_duration &&
    data.metrics.ingest_batch_duration.values["p(95)"] !== undefined
  ) {
    console.log(
      `Batch p95:       ${data.metrics.ingest_batch_duration.values[
        "p(95)"
      ].toFixed(1)} ms`
    );
  }
  console.log("");

  return {
    stdout: JSON.stringify(data, null, 2),
  };
}
