# Code review: what was wrong and what was fixed

Severity: **H** = security / data-loss / feature cannot work, **M** = wrong behaviour, **L** = robustness.

## Security
| | Where | Problem | Fix |
|---|---|---|---|
| H | api-gateway `GatewayAuthFilter` | Client-supplied `X-Merchant-Id`, `X-User-Role`, `X-Environment`, `X-Key-Id` headers were forwarded to the services (only the ones the gateway itself set were overwritten), so identity could be forged. | All four headers are stripped from every inbound request, then replaced with the verified values. `HeaderAugmentingRequestWrapper` rewritten (case-insensitive, supports blocking). |
| H | vault `charge` | Any merchant could charge any other merchant's card token (token ownership was never checked). | `VaultChargeRequest` now carries `merchantId`; vault rejects tokens that belong to someone else. |
| M | merchant `login` | Unknown email -> 404 "User not found", wrong password -> 400. Reveals which emails are registered. | Both return the same HTTP 401 `INVALID_CREDENTIALS`. |
| M | gateway | No limit on login/signup attempts (brute force). | Per-IP limit on `/v1/auth/**` (`app.rate-limit.use-case.auth.requests-per-minute`, default 30) -> HTTP 429. |
| M | local config | Internal services listened on all network interfaces, so anyone on the Wi-Fi could call `/internal/**`. | Internal services bind to `127.0.0.1` only. |
| M | merchant | Emails were case-sensitive (`A@x.com` and `a@x.com` were two accounts). | Emails are trimmed and lower-cased on signup and login. |
| H | repo | A GitHub token and passwords were committed (`config-service/application.yaml`, `k8s/k8s-secrets.env`) and are in the old git history. | Removed; read from env vars. `k8s-secrets.env.example` added; `.gitignore` added. **Revoke the token and do not push the old `.git` history.** |

## Bugs that broke features
| | Where | Problem | Fix |
|---|---|---|---|
| H | operations `Settlement` | `refundAmount` and `bankReference` are NOT NULL columns but were never set -> every settlement failed to save. | Initialised to 0 / `"PENDING"`. |
| H | operations settlement event | The whole `Settlement` entity was put into the Kafka/outbox payload instead of its id. | Plain `settlementId` / `merchantId` strings. |
| H | operations webhooks | The HMAC signature was computed over one JSON string, but a different re-serialised body was sent, so merchants could never verify it. | The exact signed text is stored (`request_body`) and sent byte-for-byte. |
| H | merchant `DELETE /api-keys/{id}` | Mapped to the literal text `/keyId`, so revoking a key never worked. | `@DeleteMapping("/{keyId}")`. |
| H | common-lib `AesEncryptionConfig` | Key created with algorithm `"AES/GCM/NoPadding"` instead of `"AES"` (breaks webhook-secret encryption), no key-length check. | Fixed, with a clear error for bad keys. |
| H | gateway | Failed to start: no `RateLimiter` bean because `app.rate-limit.method` was missing from the config. | Property added to `local-config/api-gateway-service.yaml`; the manual `RateLimiterConfig` workaround is no longer needed and was removed. |
| H | payment `PaymentRepository.findByOrder_Id` | Declared with an `OrderRecord` parameter, but the query is on the id -> would fail at runtime. | Takes a `UUID`. |
| M | common-lib `GlobalExceptionHandler` | The catch-all turned 404 / 405 / 415 / missing-header errors into HTTP 500 (this is why `GET /v1/payments/{id}` returned "server error"). | Proper 404, 405, 415 and 400 handlers. |
| H | payment `OrderMapper` | The entity field is `orderStatus` but the response field is `status`, so **every order was returned with `"status": null`** (merchants could never see PAID / CANCELLED). | `@Mapping(target = "status", source = "orderStatus")` + regression test. |
| H | common-lib `PaymentProcessorResponse` | Sent as JSON from vault to payment-service as a bare interface, so Jackson could not rebuild it and **every card payment failed** (`PAYMENT_GATEWAY_ROUTER_UNREACHABLE`). | `@JsonTypeInfo` / `@JsonSubTypes` + round-trip test; the real exception is now logged. |
| M | payment `Payment.method` | Enum stored as a number (ordinal). | `@Enumerated(STRING)`. **Needs a fresh DB** (`docker compose down -v`). |
| M | payment simulator / settlement simulator | One failing record aborted the loop and was retried first forever, blocking all others. | Per-record try/catch. |
| M | payment | Orders with amount 0, negative, or non-INR; expiry in the past, were accepted; expired orders could still be paid. | Validated (400); `ORDER_EXPIRED` check. |
| M | payment | CARD without token / NETBANKING without bank -> `NullPointerException` deep inside (shown to clients as a gateway failure; this is what produced your earlier `UPI_FAILED` payments). | 400 up front, null-safe processors. |
| M | payment | Manual capture did not mark the order PAID; `failedAt` never set; cancel allowed while a payment was in flight; `markSettled` bypassed the state machine. | All fixed. |
| M | common-lib `IdempotencyFilter` | Missing `return` after the "in progress" conflict (crash on corrupt value); same key on two endpoints replayed the wrong response. | Fixed; key is scoped to method + path. |
| M | operations Kafka consumer | When the DB was down the record was neither retried nor acknowledged, so a later ack silently skipped it. | `ack.nack(5s)`. |
| L | merchant | `POST /api-keys` without `environment` -> 500; rotating a disabled key -> 500. | 400 / business error. |
| L | common-lib | `FixedWindowRateLimiter` used `&` (NPE when TTL is null); `RedisApiKeyCache.evict` threw if Redis was down. | Fixed. |
| L | local config | Resilience4j retried and counted business errors (404, duplicates) as failures. | `ignore-exceptions` added. |
| L | poms | Jib pushed Docker images to the original author's registry on every `package`. | Jib is skipped by default (`-Djib.skip=false` to enable). |

## New
- `GET /v1/orders/{id}`, `GET /v1/orders/{id}/payments`, `POST /v1/orders/{id}/cancel`
- Unit tests that need no infrastructure (`mvnw test` passes anywhere); the old failing `contextLoads` tests were replaced.
- `verify-all.ps1`: full end-to-end verification.
- `.github/workflows/ci.yml`, `.gitignore`, config server defaults to the `native` profile.

## Known limitations (not changed)
- **No refund API.** The `Refund` entity and state events exist, but there is no endpoint. The settlement calculation ignores refunds.
- **Merchants never become ACTIVE.** Signup sets `PENDING_KYC` and there is no KYC/activation endpoint. Settlement only runs for ACTIVE merchants, and nothing enforces merchant status on payments.
- **Webhook SSRF.** A merchant can set any http(s) target URL (including internal addresses). Add an allow/deny list before exposing this publicly.
- **Simulator race:** a payment is saved as AUTHORIZING *before* the gateway call. If that call is slow (e.g. the first card call), the bank simulator can finish the payment first, so the HTTP response may already say CAPTURED. If the gateway then returned a failure, the state machine would reject the late transition. A production design would set the state after the call, or lock the row.
- Direct service ports trust the `X-Merchant-Id` header (the gateway is the security boundary; the services are bound to localhost for that reason).
- `Money.amountUnits` is an `int`, so the largest amount is 2,147,483,647 paise (about Rs 2.1 crore).
- Outbox events that fail 3 times stay FAILED forever (no alerting / dead-letter handling).

## Renaming (branding cleanup)
| Old | New |
|---|---|
| Java package / Maven group id (course project name) | `com.paymentplatform` |
| Docker containers `razorpay-postgres`, `-redis`, `-kafka`, `-zipkin` | `pay-postgres`, `pay-redis`, `pay-kafka`, `pay-zipkin` |
| Databases `razorpay-{merchant,payment,operations,vault}-db` | `pay-{merchant,payment,operations,vault}-db` |
| Kubernetes namespace `razorpay-core` (and its DNS names), kind cluster, Grafana provider, Kafka UI cluster name | `pay-core`, `pay-kind`, `pay-dashboards`, `pay-local` |
| Docker image names `razorpay-<service>` | `pay-<service>` (prefix set by `docker.registry.prefix`) |
| API key prefix `rzp_test_` / `rzp_live_` | `pp_test_` / `pp_live_` (only affects newly created keys) |
| Webhook signature header `X-Razorpay-Signature` | `X-Webhook-Signature` (configurable with `webhook.delivery.signature-header`) |

After this rename, start from a clean state: `docker compose down -v` in the **old** folder (old container and database names no longer exist in the new files), then start the new folder.
