// =============================================================================
// The write path: create a draft, then attempt to submit it.
//
// WHAT THIS DOES NOT MEASURE: how long an applicant waits for a decision.
// Submission returns 202 as soon as the event is durable in the outbox, and the
// assessment happens afterwards, asynchronously. The number below is the cost of
// ACCEPTING the work, not of doing it.
//
// Measuring decision latency means measuring outbox drain, consumer lag and
// workflow poll intervals -- throughput questions, which is why they have
// CloudWatch alarms rather than a load test.
//
// NEVER RUN AGAINST PRODUCTION. THIS HAS NEVER BEEN RUN.
// =============================================================================

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

const createLatency = new Trend('create_latency_ms');
const submitLatency = new Trend('submit_latency_ms');
const writeErrors = new Rate('write_errors');
const conflicts = new Counter('idempotency_conflicts');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const TOKEN = __ENV.TOKEN;

export const options = {
  scenarios: {
    // A fixed ARRIVAL RATE, not a fixed number of users. Submissions arrive at a
    // rate the platform does not control; modelling them with a closed loop lets
    // a slow platform reduce its own offered load and look better than it is.
    submissions: {
      executor: 'constant-arrival-rate',
      rate: 5,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  thresholds: {
    create_latency_ms: ['p(95)<1000'],
    submit_latency_ms: ['p(95)<1500'],
    write_errors: ['rate<0.01'],
  },
};

export default function () {
  if (!TOKEN) {
    throw new Error('TOKEN is required. See tests/performance/README.md.');
  }

  const headers = {
    'Content-Type': 'application/json',
    Authorization: 'Bearer ' + TOKEN,
  };

  const createHeaders = Object.assign({}, headers, {
    'Idempotency-Key': 'perf-create-' + uniqueKey(),
  });

  const created = http.post(
    BASE_URL + '/v1/applications',
    JSON.stringify(syntheticApplication()),
    { headers: createHeaders },
  );

  createLatency.add(created.timings.duration);
  writeErrors.add(created.status !== 201);

  if (!check(created, { 'draft created': (r) => r.status === 201 })) {
    return;
  }

  const applicationId = created.json('applicationId');

  // Submission is EXPECTED to be refused with 422: no documents were uploaded,
  // and both proofs are mandatory. That refusal is the point. It is the guard
  // doing its job, and it still exercises the full authorisation, validation and
  // document-projection read path -- which is what this measures.
  //
  // Uploading two documents per iteration would turn this into an S3 load test.
  const submitHeaders = Object.assign({}, headers, {
    'Idempotency-Key': 'perf-submit-' + uniqueKey(),
  });

  const submitted = http.post(
    BASE_URL + '/v1/applications/' + applicationId + '/submit',
    null,
    { headers: submitHeaders },
  );

  submitLatency.add(submitted.timings.duration);

  if (submitted.status === 409) {
    conflicts.add(1);
  }

  check(submitted, {
    // 202 if documents somehow exist, 422 if they do not. Anything else is a
    // real failure.
    'submission handled correctly': (r) => r.status === 202 || r.status === 422,
    'no server error': (r) => r.status < 500,
  });

  writeErrors.add(submitted.status >= 500);
}

function uniqueKey() {
  return __VU + '-' + __ITER + '-' + Date.now();
}

// Every value is invented. The email address uses the RFC 2606 reserved domain.
function syntheticApplication() {
  return {
    applicant: {
      givenName: 'Loadtest',
      familyName: 'Synthetic' + __VU,
      emailAddress: 'loadtest-' + __VU + '@example.com',
      dateOfBirth: '1990-05-17',
      residenceCountry: 'PL',
    },
    loan: {
      amountMinorUnits: 1500000,
      currency: 'EUR',
      termMonths: 36,
      purpose: 'HOME_IMPROVEMENT',
      declaredAnnualIncomeMinorUnits: 6000000,
      productCode: 'PERSONAL-LOAN-STANDARD',
    },
  };
}
