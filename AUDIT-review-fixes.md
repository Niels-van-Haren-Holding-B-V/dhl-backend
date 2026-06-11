# AUDIT — review-findings remediation (pre-change verification)

Read-only audit per BRIEF step 0, executed 2026-06-11 against `main`.
Verdict up front: **every finding reproduces, no blockers — all brief
defaults apply.** Proceeding with the slices as written.

## 0.1 Finding re-verification (current locations)

| Finding | Status | Current location |
|---|---|---|
| Sessionless idempotency hole (derived query + non-NULL-safe unique) | CONFIRMED | `DeliveryRegistrationRepository.kt:8` (`findBySessionIdAndBarcode(sessionId: UUID?, …)` derived), `V1__init.sql:49` (`unique (session_id, barcode)`), KDoc claim `DeliveryService.kt:27-31` |
| Authorization is authentication-only | CONFIRMED | `courierId` written at `LockerSessionService.kt:44-50`, stored in `LockerSession.kt:16`, **never read back anywhere** (`grep courierId` → 3 hits, all writes) |
| Validation starter unused | CONFIRMED | `build.gradle.kts` ships `spring-boot-starter-validation`; `jakarta.validation` references in `src/main`: **0**. `/api/sim/parcels` accepts negative dimensions (sync `forDimensions(-1,-1,-1)` → XXS) |
| Walkaway-after-reopen corrupts state | CONFIRMED | `handInReopen` sets `comp.state = DOOR_OPEN` directly (no `open()`), `openedFrom` stale |
| DEFECT escapes one-open-door invariant | CONFIRMED | `requireAllDoorsClosed()` scans `DOOR_OPEN` only; `handInReportIssue` marks DEFECT from a physically open door |
| SessionLocks never evicted | CONFIRMED | `SessionLocks.kt` — `ConcurrentHashMap` of mutexes, no removal |
| Dropped throwables in logs | CONFIRMED | `OutboxPublisher` catch logs id/type without `e`; `SessionReaper.onFailure` logs without throwable |
| ISE→400 too broad | CONFIRMED | `ApiExceptionHandler.kt:50-52` maps `IllegalArgumentException` **and** `IllegalStateException` → 400 with `e.message`. Bonus finding for slice 7's audit pass: `NoSuchElementException` handler (line 46-48) also echoes `e.message` into the 404 body |
| CLAUDE.md drift | CONFIRMED | `CLAUDE.md:167` "fixed grid of 12 (S/M/L)" vs template layouts; `CLAUDE.md:122` + `V2__seed.sql:3` "compartment 7" vs first-enabled-M; `/locker-api/sim/reserve` + `parcel-intake` topic absent from inventory |
| Orphaned KDoc | CONFIRMED | "never opens a second door" block stranded above `reserve()` (~line 405) after `reserve()` was inserted |
| Dutch log lines | CONFIRMED | `LockerSimEngine.kt:424` ("GEEN capaciteit…"), `:429` ("vak … gereserveerd voor …") |
| No static analysis | CONFIRMED | ktlint only |

## 0.2 Flyway ceiling

`db/migration/` contains `V1__init.sql`, `V2__seed.sql`. **Next version: V3.**

## 0.3 Postgres versions

- Testcontainers: `postgres:17-alpine` (both IT classes)
- infra compose: `postgres:17-alpine`
- infra k8s: `postgres:17-alpine`

All ≥ 15 → **`UNIQUE NULLS NOT DISTINCT` path applies**, no partial-index fallback needed.

## 0.4 Existing-data hazard

`select … from delivery_registration where session_id is null group by barcode
having count(*) > 1` → **0 rows on the local dev DB and 0 rows on the prod
cluster DB.** The seed inserts no registrations. Migration is safe, no dedupe
needed.

## 0.5 Anonymous route inventory

Chains (`SecurityConfig.kt`): `@Order(0)` publicChain (permitAll), `@Order(1)`
`/api/**` courier-JWT (+ anonymous `OPTIONS` for CORS preflight), `@Order(2)`
`/locker-api/**` locker-JWT, `@Order(3)` denyAll catch-all.

| Route (anonymous) | Chain | Why open | On public ingress? | Verdict |
|---|---|---|---|---|
| `/actuator/health`, `/actuator/health/**` | public | kubelet liveness/readiness probes + CI smoke | yes (`/actuator/health` path) | keep, justify in code |
| `/v3/api-docs/**` | public | client generation | **yes** | close (slice 1: dev-only flag, conditional carve-out, drop ingress path) |
| `/swagger-ui/**`, `/swagger-ui.html` | public | browsable docs | **yes** (`/swagger-ui` path, added earlier today) | close (same) |
| `/webjars/**` | public | swagger-ui assets | **yes** | close (same) |
| `OPTIONS /api/**` | courier | CORS preflight carries no credentials | yes | keep, justify in code |
| `/actuator/*` beyond health (`info` is exposure-listed) | denyAll | not matched by public chain | not routed by ingress | already closed — no action |

Everything else: 401 via JWT chains or denied by the catch-all. Note: the
ingress (`infra/k8s/backend.yaml`) whitelists `/api`, `/actuator/health`,
`/v3/api-docs`, `/swagger-ui`, `/webjars` — the last three were added earlier
today for public Swagger and are removed again by slice 1 (decision reversed
by this brief; docs become dev-only).

## 0.6 springdoc wiring

No springdoc properties set anywhere (defaults on); no `GroupedOpenApi` beans.
Frontend generation: `dhl-frontend/package.json` `generate` script targets
`${API_DOCS_URL:-http://localhost:12080/v3/api-docs}` → **local backend
confirmed** (prod URL only as manual override). Slice 1's flag must be on in
the local bootRun path for `npm run generate` to keep working, and the public
chain's docs carve-out must be conditional on the same flag (an unconditional
removal would put anonymous local generation behind JWT and break it).
Frontend README mentions the prod swagger URL — needs a one-line follow-up in
the frontend repo after slice 1.

## 0.7 DEFECT-path physics

Trace: `DOOR_STUCK` armed → `door(CLOSE)` refuses (snapshot with
`failure=DOOR_STUCK`, state stays `HAND_IN_DOOR_OPEN`) → courier
`report-compartment-issue` → compartment becomes `DEFECT` **with the door
physically open**. `door()` can only close compartments in state `DOOR_OPEN`
(active path requires `activeNr` match; orphan path requires
`state == DOOR_OPEN`); a `DEFECT` compartment throws `WRONG_COMPARTMENT`.
The only recovery is the full demo reset (`sim/reset` → `loadConfig`).
**Conclusion: including DEFECT in `requireAllDoorsClosed()` would block every
subsequent hand-in until a demo reset — Slice 6 default (B): documented
exception + `log.warn`, do not block.**
