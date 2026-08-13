// Shared random log-entry generator, reused by seed.js and load-test.js so both scripts
// produce data with the same shape and the same realistic spread of services/levels/attributes.

export const SERVICES = ['checkout', 'auth', 'inventory', 'notifications', 'payments'];
export const LEVELS = ['debug', 'info', 'warn', 'error'];
export const REGIONS = ['eu-west', 'us-east', 'us-west', 'ap-south'];
export const MESSAGES = [
  'payment declined',
  'request completed',
  'connection timeout',
  'user login',
  'cache miss',
  'retrying operation',
  'rate limit exceeded',
  'order confirmed',
];

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

// A mix of string, numeric, and boolean attribute values on purpose - exercises the
// attr.<key> filter's type-candidate matching (see LogRepository.attributeCandidateValues).
export function randomAttributes() {
  return {
    user_id: String(Math.floor(Math.random() * 100000)),
    region: pick(REGIONS),
    retries: Math.floor(Math.random() * 5),
    flagged: Math.random() < 0.1,
  };
}

// Random timestamp within the last `maxAgeMs` milliseconds, never in the future -
// satisfies the "not more than 5 minutes in the future" validation rule automatically.
export function randomTimestamp(maxAgeMs) {
  const now = Date.now();
  const offset = Math.floor(Math.random() * maxAgeMs);
  return new Date(now - offset).toISOString();
}

export function buildLogEntry(maxAgeMs) {
  return {
    timestamp: randomTimestamp(maxAgeMs),
    level: pick(LEVELS),
    service: pick(SERVICES),
    message: pick(MESSAGES),
    attributes: randomAttributes(),
  };
}

export function buildBatch(size, maxAgeMs) {
  const logs = [];
  for (let i = 0; i < size; i++) {
    logs.push(buildLogEntry(maxAgeMs));
  }
  return { logs };
}
