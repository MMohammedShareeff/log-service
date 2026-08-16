// Seeds the database to a bit over 1M rows via the real POST /logs endpoint, so the
// bulk-insert path is genuinely exercised (not a shortcut like a direct SQL COPY).
// This run is NOT your measured performance number - it's setup for load-test.js.
//
// Timestamps are spread across the last 3 days by default, matching the daily partitions
// pre-created by the V2 migration / RetentionScheduler on startup, so seeded rows land in
// real per-day partitions rather than the DEFAULT catch-all partition.
//
// Usage:
//   k6 run k6/seed.js
//   k6 run -e TARGET_ROWS=1200000 -e BATCH_SIZE=500 -e VUS=20 k6/seed.js
//
// Keep the Docker volume around after this (don't `docker compose down -v`) so you don't
// have to re-seed before every load-test.js run while tuning.

import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";
import { buildBatch } from "./lib/log-generator.js";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const BATCH_SIZE = parseInt(__ENV.BATCH_SIZE || "500", 10);
// Slightly over 1M for margin - some entries could theoretically be rejected, though with
// this generator none should be, since it only ever produces valid entries.
const TARGET_ROWS = parseInt(__ENV.TARGET_ROWS || "1100000", 10);
const VUS = parseInt(__ENV.VUS || "20", 10);
const MAX_AGE_MS = parseInt(
  __ENV.MAX_AGE_MS || String(3 * 24 * 60 * 60 * 1000),
  10
);

const totalIterations = Math.ceil(TARGET_ROWS / BATCH_SIZE);

export const options = {
  scenarios: {
    seed: {
      executor: "shared-iterations",
      vus: VUS,
      iterations: totalIterations,
      maxDuration: __ENV.MAX_DURATION || "20m",
    },
  },
  thresholds: {
    checks: ["rate>0.99"],
  },
};

const acceptedLogs = new Counter("seed_accepted_logs");
const rejectedLogs = new Counter("seed_rejected_logs");

export default function () {
  const payload = buildBatch(BATCH_SIZE, MAX_AGE_MS);
  const res = http.post(`${BASE_URL}/logs`, JSON.stringify(payload), {
    headers: { "Content-Type": "application/json" },
  });

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
  const accepted = data.metrics.seed_accepted_logs
    ? data.metrics.seed_accepted_logs.values.count
    : 0;
  const rejected = data.metrics.seed_rejected_logs
    ? data.metrics.seed_rejected_logs.values.count
    : 0;

  console.log(`\n=== Seed Summary ===`);
  console.log(`Target rows: ${TARGET_ROWS}`);
  console.log(`Accepted:    ${accepted}`);
  console.log(`Rejected:    ${rejected}`);
  console.log(
    `Verify actual row count yourself, e.g.: SELECT count(*) FROM logs;\n`
  );

  return {
    stdout: JSON.stringify(data, null, 2),
  };
}
