# Performance tests

**These have never been run against anything.** No deployment exists, and they
are deliberately excluded from the default build and from CI.

## Why they are not in CI

A load test on a shared CI runner measures the runner, not the platform. The
numbers vary with whatever else is running, which produces one of two outcomes:
a threshold loose enough to pass anything, or a flaky failure people learn to
rerun. Both are worse than no test.

These are for running deliberately, against an environment you control, when you
have a question. The question matters — "is it fast?" is not one.

## Running them

Requires [k6](https://k6.io). It is not vendored here.

```bash
# Against the local Compose stack.
make local-up
k6 run tests/performance/status-polling.js \
    -e BASE_URL=http://localhost:8081 \
    -e TOKEN="$(scripts/local-token.sh)"
```

**Never run these against a production environment.** They create applications.
On this platform an application is a record about a person, and a load test that
leaves ten thousand synthetic applicants in a real database is a data-protection
problem, not a performance result.

## The scenarios

| Script | Question it answers |
|---|---|
| `status-polling.js` | Does the highest-volume read stay fast under concurrency? |
| `application-submission.js` | What does the full write path cost, end to end? |

## What to look at

Not the average. The average hides exactly the requests that hurt.

- **p95 and p99.** A p99 of three seconds with an 80ms average means one request
  in a hundred is painful — and on this platform those are often submissions.
- **Error rate above zero at any load.** More interesting than latency: a 5xx
  under load is a capacity limit expressed as a failure.
- **Where the time goes.** API Gateway reports `Latency` and
  `IntegrationLatency`; the gap is the gateway itself. Check that before
  profiling the service.

## What these will not tell you

The assessment path is asynchronous. A submission returns `202` as soon as the
event is durable, so **submission latency does not include the assessment**.
Measuring how long an applicant waits for a decision means measuring outbox
drain, consumer lag and workflow poll intervals — which are throughput questions,
not request-latency ones, and the CloudWatch alarms for them exist for that
reason.

Nor do they say anything about the external providers: those are simulations with
configured latencies, so any number here reflects the simulator's settings, not a
bureau's real response time.
