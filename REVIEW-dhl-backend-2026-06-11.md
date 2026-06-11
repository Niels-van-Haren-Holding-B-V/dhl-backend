# Codebase Review — dhl-backend (2026-06-11)

Reviewer stance: external senior engineer, honest assessment. No findings softened.

Coverage note: the repo is small enough (~2,400 lines of production Kotlin,
~1,300 of tests) that this review read **every production source file** and
all six test suites in full, ran the full test suite against real Docker
(157 tests, 0 skipped, 0 failures), ran `ktlintCheck` (clean), and grepped
for the stack's standard smells (`.block()`, `println`, `!!`, `runBlocking`,
`Thread.sleep`, TODO/FIXME — all clean or justified, see below). This is not
a sampled review.

## TL;DR verdict

This is senior-level work, and more importantly it is *coherent* senior-level
work: the architecture, the comments, the tests and the infra all argue the
same case. The single best thing is the test suite — full-HTTP integration
tests through the real security chains against real Postgres and Redpanda
that pin exactly the behaviors the spec calls hard (reconcile-not-retry,
idempotent confirm, the reaper, both Kafka directions). The single worst
thing is that the loudly-documented idempotency guarantee has a silent hole:
the sessionless path of `/api/deliveries/register` is not idempotent at all,
because both the derived query and the unique constraint use SQL `NULL`
semantics. An assessor reading this codebase will conclude the author can
design, build, document and *defend* a distributed-ish system; the few real
defects are edge cases the tests didn't reach, not misunderstandings.

## Scorecard

| Dimension | Score /5 | One-line justification |
|---|---|---|
| 1. Readability | 4.5 | Small files, intention-revealing names, why-comments throughout; docked for an orphaned KDoc block and mixed-language log strings |
| 2. Structure & architecture | 4.5 | Layer-first packaging actually held; largest file is the 492-line sim engine, which is cohesive; clean seams (token provider, base URL, locks as interfaces/components) |
| 3. Convention adherence | 4.5 | Its own hard rules (no Mono/Flux in prod, IO-hop for blocking work, one class per file, no AI attribution in git) verified and held; ktlint clean |
| 4. Correctness & robustness | 4 | Breaker, reconcile, same-tx outbox, no-token-leak paths all real; docked for the null-session idempotency hole, two sim-engine edge cases, and zero bean validation |
| 5. Testing | 4.5 | 157 meaningful tests incl. full state-machine matrix, concurrency, idempotency, reaper, Kafka both ways; docked for untested breaker/503 path and the untested sessionless register path |
| 6. Maintainability & change-safety | 4.5 | Flyway + `ddl-auto: validate`, everything env-var'd, `.env.example`, CI builds→pushes→deploys→smoke-checks; single-replica assumptions documented where they bite |
| 7. Documentation & self-explanation | 4 | README, CLAUDE.md spec, architecture.md with rendered diagrams, infra runbook; docked because CLAUDE.md contradicts the code (and itself) in two places |
| **Overall** | **4.4** | Senior-grade demo codebase; production gaps are known, documented trade-offs more often than oversights |

## What's genuinely good (and why)

- **Defense-in-depth security config** — `SecurityConfig.kt:81-86`. Beyond the
  two realm chains, there is an explicit `@Order(3)` deny-all fallback chain,
  so an unmatched route fails closed instead of falling through to Boot
  defaults. The JWKS-override path (`SecurityConfig.kt:39-47`) still validates
  the issuer — the convenient local-dev shortcut doesn't weaken token
  validation. This is the property most "two realms" setups get wrong.
- **The secret-handling discipline is real, not aspirational.** The
  client-credentials token is mapped so that *any* failure — including
  401/403 meaning "our own credentials are broken" — becomes a constant-string
  503 (`LockerSimClient.kt:130-132`, `LockerClientConfig.kt:56-61`,
  `ApiExceptionHandler.kt:36-41`). Grep confirms the token value appears in no
  log statement. `infra/.env` is gitignored with a committed `.env.example`;
  k8s consumes secrets via `secretKeyRef` only; the `bootRun` loader
  whitelists exactly one key so prod values in `.env` can't leak into local
  runs (`build.gradle.kts:74-90`, with the *why* in a comment).
- **Reconcile-not-retry is implemented once, correctly, and reused** —
  `LockerSessionService.kt:147-183`. One `mutation()` template owns the
  per-session coroutine `Mutex`, the fresh-version fetch, the 409 → refetch →
  `reconciled: true` path, and the post-success registration hook. Every
  endpoint is a one-liner against it. This is the SOLID property that matters
  (single point of change for the hardest cross-cutting rule), not cosmetic
  "clean code".
- **Transactional outbox done by the book** — `DeliveryService.kt:31-50` writes
  registration and outbox row in one `@Transactional` method;
  `OutboxPublisher.kt:27-37` is sequential *with a comment explaining that
  ordering beats throughput here*, stops the batch on failure, and is
  at-least-once by construction (publish, then mark).
- **The test suite tests behavior, not implementation.**
  `SessionStateMachineTest.kt` generates the full state×event matrix (146
  named cases) against an *independently written* expected table, so any
  table edit fails exactly one named test — the KDoc says so explicitly.
  `HandInFlowIntegrationTest.kt:168-231` drives the happy path over real HTTP
  with the real security filter chain (only the JWT decoder seam is stubbed),
  then asserts the registration row, the single outbox row after a duplicate
  confirm, the parcel status, and the actual Kafka message.
  `LockerFailureScenariosIntegrationTest.kt:235-253` runs two parallel
  continues and asserts exactly one reconciles. The engine's config parser is
  property-tested (`LockerSimEngineTest.kt:157-185`: unique sequential
  hardware addresses, equal column pitch, module/letterbox never enabled).
- **N+1 avoided where it would actually occur** — `TripService.kt:17-24` does
  three bulk queries (`findByTripIdIn`, `findByStopIdIn`) and groups in
  memory instead of walking lazy relations.
- **Comments consistently explain *why*, including negative space.** Status
  polls deliberately don't touch `lastActivityAt` so an open tab can't keep a
  dead session alive (`LockerSessionService.kt:61-62`); machine-side events
  carry no version because "hardware does not do optimistic locking"
  (`LockerSimEngine.kt:376-378`); the sim never closes a door because doors
  are physical (`LockerSimEngine.kt:77-80`). These are the comments an
  assessor reads aloud in the debrief.
- **House rules verified, not just claimed.** Zero `Mono`/`Flux` in production
  code (the only Reactor usage is the test-only stub decoder, exactly as
  CLAUDE.md permits); every JPA/engine call hops to `Dispatchers.IO`
  (`CourierSessionController.kt:80` `sim {}` wrapper, `LockerSessionService.kt:192`);
  two `!!` total, both on paths where the invariant is locally provable
  (`LockerSimEngine.kt:379,465`); zero TODO/FIXME; git history has clean,
  scoped, no-attribution commits matching the documented convention.

## What's weak (ordered: blockers → majors → minors)

No blockers for the demo's purpose. For production these first two would be.

### Majors

- **Sessionless delivery registration is not idempotent — and the code claims
  it is** — `DeliveryService.kt:33-36`, `DeliveryRegistrationRepository.kt:8`,
  `V1__init.sql` (`unique (session_id, barcode)`).
  `findBySessionIdAndBarcode(null, barcode)` is a Spring Data *derived* query:
  the null parameter renders as SQL `session_id = NULL`, which matches
  nothing. The schema backstop has the same hole: Postgres treats NULLs as
  distinct in unique constraints unless `NULLS NOT DISTINCT` is specified.
  So the documented "existing registerDelivery endpoint" (`DeliveryController.kt:24-26`),
  kept as a real endpoint precisely to demonstrate path reuse, will happily
  create N registrations and N outbox rows for the same doorstep barcode.
  The locker flow always supplies a sessionId, so the demo never trips this —
  which is exactly why it survived. **This is a miss, not house style**: the
  KDoc on `register()` asserts idempotency without the session-scoped caveat.
  Fix: `unique nulls not distinct (session_id, barcode)` plus an explicit
  `@Query` with `IS NOT DISTINCT FROM` (or refuse null sessionId at the API).
- **Authorization is authentication-only.** `locker_session.courier_id` is
  captured at create (`LockerSessionController.kt:31-33`) and then never
  compared again: any valid courier-realm token can drive any other courier's
  session, finish it, or register deliveries with arbitrary barcode/status
  (`DeliveryController.kt:24-26` does no ownership or existence check —
  registering a nonexistent barcode still emits an outbox event).
  `GET /api/trips` returns all trips for any caller (`TripService.kt:17`).
  With one demo user this is *defensible scope* — but it is nowhere
  documented as a decision, which makes it read as an oversight. Write the
  one-paragraph ADR ("single-courier demo; ownership checks would live in
  `mutation()` next to the session load") or add the one-line
  `courierId == jwt.subject` check. Be ready to answer this in person; it is
  the first question a security-minded assessor asks.
- **`spring-boot-starter-validation` is on the classpath and never used** —
  `build.gradle.kts:31`, zero `jakarta.validation` references in `src/main`.
  Consequence: `POST /api/sim/parcels` (`SimProxyController.kt:67-80`) accepts
  zero or negative dimensions — `ParcelSize.forDimensions(-1, -1, -1)`
  cheerfully returns XXS and reserves a real compartment. A dead dependency
  plus a missing input-validation layer is two findings in one line of the
  build file. Either add `@Valid` + constraints on the announcement DTO or
  drop the starter.

### Minors

- **Walkaway-after-reopen corrupts compartment state** —
  `LockerSimEngine.kt:188-193`. `handInReopen` sets
  `comp.state = DOOR_OPEN` directly instead of calling `open()`, so
  `openedFrom` still holds the value from the *original* attempt (FREE or
  RESERVED). If the courier walks away after a reopen and the orphaned door
  is later closed, `revertOpenDoor()` (`LockerSimEngine.kt:44-47`) reverts to
  FREE and clears the barcode — while the parcel is physically inside. The
  sim's own "doors are physical" realism standard makes this a genuine bug by
  the project's own rules. Fix: `comp.open(comp.barcode)` or set
  `openedFrom = OCCUPIED` in reopen.
- **A DEFECT door escapes the one-open-door invariant** —
  `LockerSimEngine.kt:177-185` marks the compartment DEFECT from
  `HAND_IN_DOOR_OPEN` (door physically open), but `requireAllDoorsClosed()`
  (`LockerSimEngine.kt:450-454`) only scans for `DOOR_OPEN`. A subsequent
  attempt opens a second door while the defect door stands open —
  contradicting the documented invariant at `LockerSimEngine.kt:405-409`. The
  integration test asserting "never more than one door open"
  (`LockerFailureScenariosIntegrationTest.kt:242-247`) doesn't cover the
  defect path. Defensible if "defect compartments are off the grid" is the
  intent — but then say so where the invariant is documented.
- **`SessionLocks` grows forever** — `SessionLocks.kt:20-24`. One `Mutex` per
  session UUID, never evicted. Harmless at demo scale; in long-running prod
  it is a slow leak. The class already carries the multi-replica caveat —
  one more sentence (and eventually eviction on session finish) closes it.
- **Failure causes are dropped from operational logs** —
  `OutboxPublisher.kt:30-33` catches the Kafka send exception and logs only
  the event id/type, not `e`; `SessionReaper.kt:62-63`'s `onFailure` likewise
  logs without the throwable. When the demo box's Redpanda hiccups, the log
  will say *that* publishing failed but not *why*. Cheap fix, real
  operability cost.
- **`IllegalStateException → 400` is too broad a net** —
  `ApiExceptionHandler.kt:50-52`. It exists to map
  `check(session.status == ACTIVE)` to a client error, but it will also
  convert any future internal ISE into a 400 carrying `e.message` —
  contradicting in spirit the handler's own "constant strings only" KDoc. Use
  a dedicated exception type for the session-state check.
- **CLAUDE.md contradicts the code (and itself).** `CLAUDE.md:167` still says
  "Compartments: fixed grid of 12 (S/M/L)" while the code (and a later
  CLAUDE.md section) implement template-parsed MINI/COMPACT/BIG layouts with
  XS–XXL/B/TC/FC. CLAUDE.md and the `V2__seed.sql` header both say DHL-OUT-001
  is "pre-loaded in compartment 7", but `LockerSimEngine.kt:350-353` preloads
  the *first enabled M* compartment — nr 1 in the BIG layout. The
  `/locker-api/sim/reserve` endpoint and the `parcel-intake` Kafka topic
  (`ParcelIntakeConsumer.kt`) exist in code but not in CLAUDE.md's endpoint
  inventory. For a repo whose docs are explicitly the cross-repo contract,
  stale spec lines are a top-tier doc finding — they will misdirect exactly
  the reader they were written for.
- **Orphaned KDoc block** — `LockerSimEngine.kt:405-415`: the "a real machine
  never opens a second door" comment for `requireAllDoorsClosed` is stranded
  directly above a second KDoc for `reserve()`; the first block is attached
  to nothing. An artifact of moving `reserve()` in between — exactly the kind
  of thing a fresh reader trips on.
- **Mixed-language log strings** — `LockerSimEngine.kt:424,429` log in Dutch
  ("GEEN capaciteit…", "vak … gereserveerd") while every other engine log line
  is English. The courier-facing Dutch 503 message is clearly deliberate;
  these two read as flavor that leaked into an otherwise-English event log.
  Pick one language for the log and state the choice.
- **No static analysis beyond formatting.** ktlint (intellij_idea style) is
  wired and clean, but there is no detekt/qodana layer, so things like the
  unused validation starter or dropped exception parameters go unflagged. For
  a codebase this size it's an hour of setup.

## Design choices — defended and questioned

- **WebFlux + coroutines over MVC, with blocking JPA bridged to
  `Dispatchers.IO`.** The why is documented twice (CLAUDE.md and
  `build.gradle.kts:36-39`): a polling proxy fanning out many short concurrent
  calls. Legitimate — and the discipline held (no hand-written Reactor, every
  blocking call hopped). The cost is the hybrid itself: JPA on WebFlux means
  every DB touch needs the `io {}` ritual, and forgetting it once blocks an
  event-loop thread silently. A senior accepts the trade *because the
  rationale is written down*, but will ask "why not R2DBC?" — have the answer
  ready (Flyway/JPA maturity, demo timeline, the DB is not the hot path).
- **One Gradle module, one image, roles split by env flags**
  (`DHL_BACKEND_ENABLED`, `LOCKER_SIM_SERVE`). Smart for a demo: one build,
  one CI pipeline, and locally the process talks to itself over real HTTP so
  the client/breaker/auth path is exercised even in dev. Cost: the sim ships
  inside the production BFF image and bean wiring depends on flag
  combinations. Mitigated by ClusterIP-only exposure of the sim and
  `@ConditionalOnBooleanProperty` on every sim bean. Acceptable trade,
  consciously made.
- **In-memory per-session `Mutex` and in-memory sim state.** Both are
  single-replica by construction, and both say so in comments
  (`SessionLocks.kt:13-16` even names the Postgres-advisory-lock/Redis
  upgrade path — the exact "talking point" the spec demanded). Defended
  house style, not a miss.
- **Fetch-fresh-version-then-mutate** (`LockerSessionService.kt:155`) rather
  than trusting the locally stored `sim_version`. This deliberately narrows
  the optimistic-lock window to the gap between the status call and the
  mutation — machine-side events (door close, bind) bump the version, so
  trusting the local copy would 409 on every legitimate flow. Correct given
  the sim's semantics; costs one extra round-trip per mutation, irrelevant at
  this scale. Would not survive unscrutinized at high volume; fine here.
- **Hand-rolled client-credentials wiring** (`LockerClientConfig.kt:33-64`)
  instead of a Boot starter — justified in a comment: no Boot 4 starter for
  this yet, registration built by hand so the app boots while Keycloak is
  still starting. Verified plausible; the manager still caches/refreshes.
- **The sim engine is `@Synchronized` with a deliberate `Thread.sleep` failure
  mode** (`LockerSimEngine.kt:24-27,489-491`). The KDoc owns it: one physical
  machine, deliberately single-instance; controllers hop to IO so the sleep
  never lands on the event loop (`CourierSessionController.kt:25-27`). Note
  that SLOW_NETWORK sleeps *while holding the engine lock*, so it slows every
  sim call, not just the delayed one — arguably more realistic for one
  machine, but worth knowing it's lock-wide.
- **`@Scheduled` + `runBlocking` bridges in reaper/publisher.** Each carries
  a comment explaining the bridge and the no-overlap guarantee
  (`SessionReaper.kt:23-30`). Idiomatic enough for scheduled entry points;
  fine.

## Test reality check

Covered, with real assertions: the full state×event matrix (146 cases); the
engine's failure modes (FORCE_409 firing exactly once, SIZE_TOO_SMALL
escalation, PARCEL_MISSING); reservation flow including walkaway; layout
parser invariants; and end-to-end through real HTTP + real security chain +
Testcontainers Postgres/Redpanda: anonymous rejection (401), hand-in happy
path with registration/outbox/Kafka-message assertions, duplicate confirm →
one outbox row, two parallel continues → exactly one reconciled, vak-te-klein
escalation, the one-open-door rule across sessions, hand-out happy path and
report-missing, the reaper (backdated session → EXPIRED + 3× NOT_DELIVERED +
3 outbox events), and Kafka *ingestion* including a poison-pill message. For
2,400 lines of production code this is an unusually honest test suite — the
hard claims in the spec are each pinned by a named test.

Dangerously uncovered:

- **The circuit breaker and the 503 contract.** Nothing asserts that an open
  breaker (or a dead sim) yields the constant Dutch 503 — the headline
  resilience feature is the one path no test exercises.
- **The sessionless `/api/deliveries/register` path** — which is precisely
  where the idempotency hole lives. A two-line test would have caught it.
- **Authorization beyond 401** — no test for cross-courier access, because
  there is no such check (see Majors).
- The token-provider failure mapping (`LockerClientConfig.kt:51-61`) is
  commit-message-verified ("Token endpoint failures map to LockerUnavailable,
  never a raw 500") but not test-pinned.

## The verdict — answered directly

No explicit question accompanied this review, so here is the
professional-grade verdict an outside assessor would deliver.

**This codebase demonstrates senior-level engineering.** Not because it is
flawless, but because of what kind of flaws it has: the defects found are
edge cases (NULL semantics in an idempotency key, stale `openedFrom` on one
reopen path), while the things juniors and mid-levels get wrong at the core —
security chains, token hygiene, transactional outbox, conflict reconciliation,
test honesty, config/migration discipline — are all correct and, crucially,
*explained in place*. The comment culture is the standout signal: nearly
every non-obvious decision carries its rationale, which is exactly what a
due-diligence reviewer or interviewer is probing for.

What an assessing party will likely conclude: the author can own a service
end to end (code → tests → CI → cluster), thinks in failure modes, and knows
where their demo shortcuts are. The gaps they will press on, ranked:

1. **The idempotency hole** (`DeliveryService.kt:33`) — fix it or scope the
   claim; do not let an assessor find it first.
2. **Authorization** — add the ownership check or document the single-courier
   scope decision explicitly; "I know, and here's where the check would go"
   is a fine answer, silence is not.
3. **Validation** — an unused validation starter next to an endpoint that
   accepts negative dimensions is an easy point for a reviewer to score.
4. **Doc drift in CLAUDE.md** — for a repo whose docs are the cross-repo
   contract, the "grid of 12" and "compartment 7" lines actively mislead.
5. The two sim-engine edge cases — small, but they violate the project's own
   loudly-stated "doors are physical" standard, so they cost more here than
   they would elsewhere.

Where the rationale is strong enough to survive hostile questioning: WebFlux
choice, single-image role split, in-memory locks with named upgrade path,
reconcile-not-retry, outbox ordering. Where it currently would not: "is
register() idempotent?" and "what stops courier A from finishing courier B's
session?" — prepare both.

## If I had 2 days / 2 weeks to raise the grade

**2 days (highest leverage first):**
1. Fix sessionless idempotency: `unique nulls not distinct`, explicit
   `IS NOT DISTINCT FROM` query, plus a regression test hitting
   `/api/deliveries/register` twice without a sessionId.
2. Add the courier-ownership check in `LockerSessionService.mutation()`/`status()`
   (one comparison, one 403 mapping, one test) — or an ADR paragraph
   declaring the single-courier scope.
3. Add a breaker/unavailable integration test: kill the sim base URL, assert
   the constant 503 body; flip FORCE-open the breaker, assert the same.
4. Fix `handInReopen` bookkeeping (`comp.open(comp.barcode)`) and either fix
   or document the DEFECT-door invariant escape; extend the one-door test.
5. Sweep the docs: CLAUDE.md:167 grid line, both "compartment 7" references,
   add `sim/reserve` + `parcel-intake` to the endpoint inventory; delete the
   orphaned KDoc at `LockerSimEngine.kt:405-409` or reattach it.
6. Wire `@Valid` + positive-number constraints on `ParcelAnnouncement`, or
   drop `spring-boot-starter-validation`.

**2 weeks:**
1. Add detekt with a tuned baseline; let it police dropped exception params
   and unused dependencies continuously.
2. Pin the remaining contracts: token-provider failure mapping test,
   OutboxPublisher failure/ordering test (kill Redpanda mid-batch, assert
   rows stay unpublished and order holds), `LockerConfigurations` edge rules
   (5cm sliver swap, letterbox address 20-vs-0) as named unit tests.
3. Session-lock eviction on finish/expiry, and a `NULLS NOT DISTINCT`-era
   consolidation migration.
4. Replace the broad `IllegalStateException → 400` mapping with a typed
   `SessionNotActiveException`, and audit every `e.message` that reaches a
   response body.
5. An ADR directory (3–4 one-pagers: WebFlux+JPA hybrid, single-image role
   split, single-replica locks, demo-scope authz) — most of the text already
   exists as comments; promoting it makes the defense self-serve.
6. If multi-replica is ever on the table: the documented Postgres advisory
   lock for `SessionLocks`, and `FOR UPDATE SKIP LOCKED` on the outbox poll.
