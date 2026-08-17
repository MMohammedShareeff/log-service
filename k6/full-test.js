// Usage:
//   k6 run k6/full-scenario-test.js
//   k6 run -e TARGET_LOGS_PER_SEC=15000 -e DURATION=120s k6/full-scenario-test.js

import http from "k6/http";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.2/index.js";
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
      preAllocatedVUs: parseInt(__ENV.INGEST_VUS || "20", 10),
      maxVUs: parseInt(__ENV.INGEST_MAX_VUS || "60", 10),
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
    consistency_probe: {
      executor: "constant-arrival-rate",
      rate: 1,
      timeUnit: "10s",
      duration: DURATION,
      preAllocatedVUs: 2,
      maxVUs: 5,
      exec: "consistencyProbe",
    },
  },
  thresholds: {
    "http_req_duration{scenario:aggregate}": ["p(95)<1000"],
    "checks{scenario:ingest}": ["rate>0.95"],
    "checks{scenario:aggregate}": ["rate>0.99"],
    consistency_success_rate: ["rate>0.95"],
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
    const body = res.json();
    acceptedLogs.add(body.accepted || 0);
    rejectedLogs.add((body.rejected || []).length);
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
}

export function consistencyProbe() {
  const probeId = `${__VU}-${__ITER}-${Date.now()}`;
  const entry = buildLogEntry(1000);
  entry.service = "consistency-probe";
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

  const attempts = Math.floor(
    CONSISTENCY_TIMEOUT_S / CONSISTENCY_POLL_INTERVAL_S
  );
  for (let i = 0; i < attempts; i++) {
    sleep(CONSISTENCY_POLL_INTERVAL_S);

    const queryUrl = `${BASE_URL}/logs?service=consistency-probe&attr.probe_id=${probeId}&limit=1`;
    const queryRes = http.get(queryUrl, {
      tags: { scenario: "consistency_probe" },
    });

    if (queryRes.status === 200) {
      const body = queryRes.json();
      if (body.logs && body.logs.length > 0) {
        const elapsedMs = Date.now() - writeStart;
        consistencyTimeToVisible.add(elapsedMs);
        consistencySuccess.add(true);
        return;
      }
    }
  }

  consistencyTimeouts.add(1);
  consistencySuccess.add(false);
}

export function handleSummary(data) {
  const accepted = data.metrics.total_accepted_logs
    ? data.metrics.total_accepted_logs.values.count
    : 0;
  const shed = data.metrics.total_503_shed
    ? data.metrics.total_503_shed.values.count
    : 0;
  const durationSeconds =
    data.state && data.state.testRunDurationMs
      ? data.state.testRunDurationMs / 1000
      : null;

  const achievedRate = durationSeconds
    ? (accepted / durationSeconds).toFixed(0)
    : "N/A";

  let aggP95 = "N/A";
  if (data.metrics["http_req_duration{scenario:aggregate}"]) {
    const agg = data.metrics["http_req_duration{scenario:aggregate}"];
    if (agg && agg.values["p(95)"] !== undefined) {
      aggP95 = `${agg.values["p(95)"].toFixed(1)} ms`;
    }
  }

  let consistencySuccess = "N/A";
  if (data.metrics.consistency_success_rate) {
    consistencySuccess = `${(
      data.metrics.consistency_success_rate.values.rate * 100
    ).toFixed(1)}%`;
  }

  let timeToVisibleAvg = "N/A";
  let timeToVisibleP95 = "N/A";
  if (data.metrics.consistency_time_to_visible_ms) {
    const c = data.metrics.consistency_time_to_visible_ms.values;
    if (c.avg !== undefined) timeToVisibleAvg = `${c.avg.toFixed(0)} ms`;
    if (c["p(95)"] !== undefined)
      timeToVisibleP95 = `${c["p(95)"].toFixed(0)} ms`;
  }

  const timeouts = data.metrics.consistency_timeouts
    ? data.metrics.consistency_timeouts.values.count
    : 0;

  const customSummary = `
=== Full Scenario Test Summary ===
Target ingestion:            ${TARGET_LOGS_PER_SEC} logs/sec for ${DURATION}
Accepted logs:               ${accepted}
Shed (503, backpressure):    ${shed}
Achieved rate:               ${achievedRate} logs/sec
Aggregate p95:               ${aggP95}
Consistency success:         ${consistencySuccess}
Time-to-visible (avg / p95): ${timeToVisibleAvg} / ${timeToVisibleP95}
Consistency timeouts (>20s): ${timeouts}
`;

  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  return {
    stdout:
      textSummary(data, { indent: " ", enableColors: true }) + customSummary,

    [`local/full-scenario-summary-${stamp}.json`]: JSON.stringify(
      data,
      null,
      2
    ),
    [`local/full-scenario-summary-${stamp}.txt`]: customSummary,
    [`local/k6-full-scenario-detailed-${stamp}.txt`]: textSummary(data, {
      indent: " ",
      enableColors: false,
    }),
  };
}
