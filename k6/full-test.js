// Usage:
//   k6 run -e TARGET_LOGS_PER_SEC=15000 -e DURATION=120s -e BATCH_SIZE=500 k6/full-test.js
//
// Recommended:
//   1. Seed first:  k6 run -e TARGET_ROWS=1100000 k6/seed.js
//   2. Then run this full test.

import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend, Rate } from "k6/metrics";
import { buildBatch, buildLogEntry } from "./lib/log-generator.js";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const BATCH_SIZE = parseInt(__ENV.BATCH_SIZE || "500", 10);
const TARGET_LOGS_PER_SEC = parseInt(__ENV.TARGET_LOGS_PER_SEC || "15000", 10);
const DURATION = __ENV.DURATION || "120s";
const MAX_AGE_MS = parseInt(__ENV.MAX_AGE_MS || String(5 * 60 * 1000), 10);

const CONSISTENCY_TIMEOUT_S = 20;
const CONSISTENCY_POLL_INTERVAL_S = 2;

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
      preAllocatedVUs: parseInt(__ENV.INGEST_VUS || "80", 10),
      maxVUs: parseInt(__ENV.INGEST_MAX_VUS || "250", 10),
      exec: "ingest",
      gracefulStop: "30s",
    },
    // aggregate: {
    //   executor: "constant-arrival-rate",
    //   rate: 1,
    //   timeUnit: "1s",
    //   duration: DURATION,
    //   preAllocatedVUs: 5,
    //   maxVUs: 15,
    //   exec: "aggregate",
    //   startTime: "5s",
    //   gracefulStop: "30s",
    // },
    // consistency_probe: {
    //   executor: "constant-arrival-rate",
    //   rate: 1,
    //   timeUnit: "5s",
    //   duration: DURATION,
    //   preAllocatedVUs: 3,
    //   maxVUs: 8,
    //   exec: "consistencyProbe",
    //   gracefulStop: "30s",
    // },
  },

  thresholds: {
    "http_req_duration{scenario:aggregate}": ["p(95)<1000"],
    "checks{scenario:ingest}": ["rate>0.95"],
    "checks{scenario:aggregate}": ["rate>0.99"],
    consistency_success_rate: ["rate>0.95"],
    total_503_shed: ["count<10"],
  },
};

const acceptedLogs = new Counter("total_accepted_logs");
const rejectedLogs = new Counter("total_rejected_logs");
const shedRequests = new Counter("total_503_shed");
const consistencySuccess = new Rate("consistency_success_rate");
const consistencyTimeToVisible = new Trend(
  "consistency_time_to_visible_ms",
  true
);
const consistencyTimeouts = new Counter("consistency_timeouts");

export function ingest() {
  const payload = buildBatch(BATCH_SIZE, MAX_AGE_MS);
  const res = http.post(`${BASE_URL}/logs`, JSON.stringify(payload), {
    headers: { "Content-Type": "application/json" },
    tags: { scenario: "ingest" },
    timeout: "15s",
  });

  if (res.status === 503) {
    shedRequests.add(1);
    return;
  }

  const ok = check(
    res,
    { "ingest status is 200": (r) => r.status === 200 },
    { scenario: "ingest" }
  );

  if (ok) {
    try {
      const body = res.json();
      acceptedLogs.add(body.accepted || 0);
      rejectedLogs.add((body.rejected || []).length);
    } catch (_) {}
  }
}

export function aggregate() {
  const until = new Date();
  const since = new Date(until.getTime() - 2 * 60 * 1000);

  const url =
    `${BASE_URL}/logs/aggregate` +
    `?since=${since.toISOString()}` +
    `&until=${until.toISOString()}` +
    `&bucket=1m`;

  const res = http.get(url, {
    tags: { scenario: "aggregate" },
    timeout: "10s",
  });

  check(
    res,
    {
      "aggregate status is 200": (r) => r.status === 200,
    },
    { scenario: "aggregate" }
  );
}

export function consistencyProbe() {
  const probeId = `probe-${__VU}-${__ITER}-${Date.now()}`;
  const entry = buildLogEntry(1000);
  entry.service = "consistency-probe";
  entry.attributes = entry.attributes || {};
  entry.attributes.probe_id = probeId;

  const writeStart = Date.now();

  const postRes = http.post(
    `${BASE_URL}/logs`,
    JSON.stringify({ logs: [entry] }),
    {
      headers: { "Content-Type": "application/json" },
      tags: { scenario: "consistency_probe" },
    }
  );

  if (postRes.status !== 200) {
    consistencySuccess.add(false);
    return;
  }

  const maxAttempts = Math.ceil(
    CONSISTENCY_TIMEOUT_S / CONSISTENCY_POLL_INTERVAL_S
  );

  for (let i = 0; i < maxAttempts; i++) {
    sleep(CONSISTENCY_POLL_INTERVAL_S);

    const queryUrl =
      `${BASE_URL}/logs` +
      `?service=consistency-probe` +
      `&attr.probe_id=${encodeURIComponent(probeId)}` +
      `&limit=1`;

    const queryRes = http.get(queryUrl, {
      tags: { scenario: "consistency_probe" },
    });

    if (queryRes.status === 200) {
      try {
        const body = queryRes.json();
        if (body.logs && body.logs.length > 0) {
          const elapsedMs = Date.now() - writeStart;
          consistencyTimeToVisible.add(elapsedMs);
          consistencySuccess.add(true);
          return;
        }
      } catch (_) {}
    }
  }

  consistencyTimeouts.add(1);
  consistencySuccess.add(false);
}

export function handleSummary(data) {
  const accepted = data.metrics.total_accepted_logs?.values?.count || 0;
  const shed = data.metrics.total_503_shed?.values?.count || 0;
  const durationSeconds = data.state?.testRunDurationMs
    ? data.state.testRunDurationMs / 1000
    : null;

  console.log("\n=== Full Scenario Test Summary ===");
  console.log(
    `Target ingestion:          ${TARGET_LOGS_PER_SEC} logs/sec for ${DURATION}`
  );
  console.log(`Accepted logs:             ${accepted}`);
  console.log(`Shed (503, backpressure):  ${shed}`);

  if (durationSeconds) {
    console.log(
      `Achieved rate:             ${(accepted / durationSeconds).toFixed(
        0
      )} logs/sec`
    );
  }

  const agg = data.metrics["http_req_duration{scenario:aggregate}"];
  if (agg?.values?.["p(95)"] !== undefined) {
    console.log(
      `Aggregate p95:             ${agg.values["p(95)"].toFixed(1)} ms`
    );
  }

  if (data.metrics.consistency_success_rate) {
    console.log(
      `Consistency success:       ${(
        data.metrics.consistency_success_rate.values.rate * 100
      ).toFixed(1)}%`
    );
  }

  if (data.metrics.consistency_time_to_visible_ms) {
    const c = data.metrics.consistency_time_to_visible_ms.values;
    console.log(
      `Time-to-visible avg/p95:   ${c.avg?.toFixed(0) ?? "–"} / ${
        c["p(95)"]?.toFixed(0) ?? "–"
      } ms`
    );
  }

  const timeouts = data.metrics.consistency_timeouts?.values?.count || 0;
  console.log(`Consistency timeouts (>20s): ${timeouts}`);
  console.log("");

  return {
    stdout: JSON.stringify(data, null, 2),
  };
}
