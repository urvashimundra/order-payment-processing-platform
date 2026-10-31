# Load test (JMeter)

## What the plan does (`order-payment-load-test.jmx`)
Per virtual user and iteration: **create order** (assert HTTP 201 and `status == CREATED`) -> **initiate UPI payment**
with a unique `X-Idempotency-Key` (assert 201, extract `payment_id`) -> **get payment** (assert 200 and same id).
It reports through the *Aggregate Report* and *Summary Report* listeners (the heavy *View Results Tree* is disabled by default).

It does **not** cover: invalid requests, authentication failures, duplicate/concurrent submission of the same key, capture,
rate-limit behaviour, webhooks, Kafka recovery or long endurance runs. Those belong to the end-to-end script and code-level tests.

## What changed from the original plan
The original plan had 1 thread, 1 iteration, no assertions, a hard-coded API key inside an Authorization Manager, port 8080,
and a personal VPA. Now: parameters via properties, one API key per virtual user from a CSV, assertions on every request,
no embedded credentials. **The modified plan has not been opened in JMeter or executed by the reviewer.** Open it in the JMeter GUI
once to confirm it loads before relying on it.

## Prepare
1. Start the platform (`run-all.bat`).
2. Create test API keys (the file is git-ignored):
   `powershell -ExecutionPolicy Bypass -File .\scripts\generate-load-test-keys.ps1 -Count 10`
3. Install JMeter 5.6.x and Java.

## Run (non-GUI)
```powershell
cd loadtest
jmeter -n -t order-payment-load-test.jmx -Jhost=localhost -Jport=8081 -Jthreads=5 -Jramp=10 -Jloops=20 `
       -Jkeys_file=api_keys.csv -l results/run1.jtl -e -o results/report1
```
Use at most as many threads as keys, otherwise virtual users share keys.

## Read the results correctly
- The gateway allows **600 requests per minute per API key** (`app.rate-limit.use-case.api-key.requests-per-minute`).
  `429` responses mean the limiter works; they are failures in this plan. Use more keys, fewer threads, or raise the limit for a throughput test.
- The bank simulator finishes payments after a few seconds, so this test measures the API, not payment completion.
- Run on a dedicated machine, record JMeter version, CPU/RAM, thread count, ramp-up, duration, the error percentage and the
  90/95/99th percentile latency from the report, and add them to `docs/TESTING.md` section 4.3. Never quote a number that was not measured.
