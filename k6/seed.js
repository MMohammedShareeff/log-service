import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";
import { buildBatch } from "./lib/log-generator.js";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const BATCH_SIZE = parseInt(__ENV.BATCH_SIZE || "2000", 10);
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
