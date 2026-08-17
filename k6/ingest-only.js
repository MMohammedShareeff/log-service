// Usage:
//   k6 run k6/ingest-only.js
//   k6 run -e TARGET_LOGS_PER_SEC=15000 -e DURATION=120s k6/ingest-only.js
//   k6 run -e TARGET_LOGS_PER_SEC=5000 -e DURATION=30s k6/ingest-only.js   (quick smoke run)

import http from "k6/http";
import { check } from "k6";
import { Counter, Trend } from "k6/metrics";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.2/index.js";
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

  const achievedRate = durationSeconds
    ? (accepted / durationSeconds).toFixed(0)
    : "N/A";

  const p95 =
    data.metrics.ingest_batch_duration &&
    data.metrics.ingest_batch_duration.values["p(95)"] !== undefined
      ? data.metrics.ingest_batch_duration.values["p(95)"].toFixed(1)
      : "N/A";

  const customSummary = `
=== Ingest-Only Load Test Summary ===
Target:          ${TARGET_LOGS_PER_SEC} logs/sec for ${DURATION} (batch size ${BATCH_SIZE})
Accepted:        ${accepted}
Rejected:        ${rejected} (validation failures)
503 (shed load): ${overloaded} requests
Achieved rate:   ${achievedRate} logs/sec
Batch p95:       ${p95} ms
`;

  const stamp = new Date().toISOString().replace(/[:.]/g, "-");

  return {
    stdout:
      textSummary(data, { indent: " ", enableColors: true }) + customSummary,

    [`summary-${stamp}.json`]: JSON.stringify(data, null, 2),
    [`local/summary-${stamp}.txt`]: customSummary,
    [`local/k6-detailed-${stamp}.txt`]: textSummary(data, {
      indent: " ",
      enableColors: false,
    }),
  };
}
