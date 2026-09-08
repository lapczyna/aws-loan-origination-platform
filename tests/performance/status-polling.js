// =============================================================================
// Status polling under concurrency.
//
// The highest-volume read on the platform: a client waiting for a decision polls
// this endpoint, and there is one client per in-flight application.
//
// NEVER RUN AGAINST PRODUCTION. This creates applications, and an application on
// this platform is a record about a person.
//
// THIS HAS NEVER BEEN RUN. No deployment exists.
// =============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const statusLatency = new Trend('status_latency_ms');
const statusErrors = new Rate('status_errors');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const TOKEN = __ENV.TOKEN;

export const options = {
  scenarios: {
    // A ramp rather than a step. A step load measures how the platform handles a
    // thundering herd, which is a different question from how it handles load.
    polling: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: 20 },
        { duration: '2m', target: 20 },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '15s',
    },
  },
  thresholds: {
    // p95 and p99, not the average. The average hides the requests that hurt.
    status_latency_ms: ['p(95)<300', 'p(99)<800'],
    // Any error under load is more interesting than any latency number: it is a
    // capacity limit expressed as a failure.
    status_errors: ['rate<0.001'],
  },
};

export function setup() {
  if (!TOKEN) {
    throw new Error('TOKEN is required. See tests/performance/README.md.');
  }

  const headers = {
    'Content-Type': 'application/json',
    Authorization: 'Bearer ' + TOKEN,
  };

  // One application to poll. Creating one per iteration would measure the write
  // path, which application-submission.js already does.
  const created = http.post(
    BASE_URL + '/v1/applications',
    JSON.stringify(syntheticApplication(0)),
    { headers: headers },
  );

  if (created.status !== 201) {
    throw new Error('Could not create the application to poll: ' + created.status);
  }

  return { applicationId: created.json('applicationId') };
}

export default function (data) {
  const response = http.get(
    BASE_URL + '/v1/applications/' + data.applicationId + '/status',
    { headers: { Authorization: 'Bearer ' + TOKEN } },
  );

  statusLatency.add(response.timings.duration);
  statusErrors.add(response.status !== 200);

  check(response, {
    'status is 200': (r) => r.status === 200,
    'body carries a status': (r) => r.json('status') !== undefined,
    // The status endpoint is deliberately small and separate from the full
    // representation. If it starts returning the whole application, the
    // highest-volume read on the platform just became several times more
    // expensive and nobody noticed.
    'response stays small': (r) => r.body.length < 512,
  });

  // A real client polls; it does not spin. Without this the test measures how
  // fast k6 can generate requests.
  sleep(1);
}

// Every value is invented. The email address uses the RFC 2606 reserved domain.
function syntheticApplication(virtualUser) {
  return {
    applicant: {
      givenName: 'Loadtest',
      familyName: 'Synthetic' + virtualUser,
      emailAddress: 'loadtest-' + virtualUser + '@example.com',
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
