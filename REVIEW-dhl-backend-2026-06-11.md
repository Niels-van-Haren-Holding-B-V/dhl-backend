# Codebase Review — dhl-backend (2026-06-11)

Reviewer stance: external senior engineer, honest assessment. No findings softened.

Scope: entire repo read (the codebase is ~3,000 LOC of Kotlin; all production
files, all four test classes, both migrations, application.yml, build.gradle.kts,
CI workflow, and the infra manifests were read in full — this is not a sampled
review). Gates run: `./gradlew test` (all green, including the Testcontainers
integration test against real Postgres 17 + Redpanda), `./gradlew ktlintCheck`
(clean). Note: the working tree contains uncommitted work (the `parcel-intake`
Kafka ingestion: `ParcelIntakeConsumer.kt`, `ParcelAnnouncement.kt`, modified
`SimProxyController.kt` / `application.yml`); it was reviewed as part of the tree.

## TL;DR verdict

This is senior-level work, and demonstrably so: the repo states its own rules
(CLAUDE.md) and then actually follows them, which is rarer than the rules
themselves. The single best thing is the comment discipline — nearly every
non-obvious decision carries a *why* at the point of use, from the SQL
migration to the lock implementation, and several deliberate simplifications
are labelled as such with their production alternative named. The single worst
thing is that authorization stops at authentication: any valid courier-realm
token can drive any other courier's locker session, and nothing in the docs
claims that gap on purpose. For a single-user demo it is invisible; under
due-diligence questioning it is the first thing a security-minded assessor
will find, and the only top-tier finding in this review that the repo doesn't
already defend in writing.

## Scorecard

| Dimension | Score /5 | One-line justification |
|---|---|---|
| Readability | 5 | Small single-purpose files, why-comments, zero dead code, zero TODOs; any file is understandable in minutes |
| Structure & architecture | 4.5 | Documented layer-first layout actually matches the tree; only convention (no tooling) enforces the boundaries |
| Convention adherence | 5 | House rules verified by grep/tooling: 0 hand-written Mono/Flux in production, one class per file, ktlint clean |
| Correctness & robustness | 4 | Strong concurrency story (Mutex, reconcile, breaker, outbox), but an authz gap and a check-then-insert race off the locked path |
| Testing | 4.5 | Full-matrix state machine test, real E2E with idempotency + race coverage, layout invariants; tests are order-dependent and the reaper is untested |
| Maintainability & change-safety | 4.5 | Flyway + validate, env-driven config, infra as code; bus-factor 1 and no mechanical layer enforcement |
| Documentation & self-explanation | 5 | CLAUDE.md is a real spec, infra runbook, architecture.md with diagrams, rationale at every decision point |
| **Overall** | **4.5** | Senior-level: deliberate trade-offs, named and defended — with one undefended security gap |

## What's genuinely good (and why)

- **The repo follows its own written rules, verifiably.** CLAUDE.md bans
  hand-written `Mono`/`Flux` in production code; a grep finds zero (the only
  match is the `@EnableWebFluxSecurity` import, `SecurityConfig.kt:7`). It
  mandates one class per file; the tree shows 60+ files, none with a second
  top-level class. It demands "blocking work always hops to `Dispatchers.IO`";
  every controller that touches the synchronized engine does exactly that,
  with the reason stated (`CourierSessionController.kt:25-27,81`). This is the
  readability/consistency property assessors actually test for: documented
  intent matching observed code.
- **Comments explain *why*, not *what* — consistently.** Examples: why status
  polls don't refresh `lastActivityAt` (`LockerSessionService.kt:61-62` — an
  open tab must not keep a walked-away session alive), why the sim never opens
  two doors (`LockerSimEngine.kt:390-394`), why machine-side mutations carry no
  version but still bump it (`LockerSimEngine.kt:361-363`), why the outbox
  publisher is sequential (`OutboxPublisher.kt:14-17`), even why a column is
  explicitly named (`Parcel.kt:24-25`, Hibernate's `weightg` trap). This is
  exactly the inline-rationale discipline rubric dimension 7 asks for.
- **Deliberate simplifications are labelled with their production fix.**
  `SessionLocks.kt:14-17`: "In-memory ON PURPOSE… must become a Postgres
  advisory lock or a Redis lock — deliberate demo simplification and a talking
  point." A reviewer cannot mark down what the author has already costed.
- **The concurrency design is real, not decorative.** Per-session coroutine
  `Mutex` (suspends, doesn't block — `SessionLocks.kt:23-24`), fresh version
  fetch before every mutation, and a 409 path that refetches truth and returns
  `reconciled: true` instead of blind-retrying (`LockerSessionService.kt:147-183`).
  The integration test then proves it: two parallel continues → both 200,
  exactly one reconciled (`HandInFlowIntegrationTest.kt:233-253`).
- **Transactional outbox done correctly.** Registration and outbox row commit
  in one transaction (`DeliveryService.kt:32-50`); the publisher marks
  `published_at` only after the Kafka send is acknowledged and stops the batch
  on failure — honest at-least-once (`OutboxPublisher.kt:25-35`); a partial
  index serves the poll (`V1__init.sql`, `outbox_unpublished_idx`).
- **Security plumbing is above demo grade.** Two independent resource-server
  chains plus an explicit deny-all fallback chain so an unmatched route can
  never slip through open (`SecurityConfig.kt:81-87`); constant Dutch error
  strings so nothing from the locker side can leak into a response
  (`ApiExceptionHandler.kt:20-23`); the client-credentials token lives only in
  memory and the code comments repeat the "never in a log or response" rule at
  both places it could go wrong (`LockerClientConfig.kt:33`,
  `LockerSimClient.kt:126-128`). Secrets reach k3s via `Secret` refs;
  `infra/.env` is gitignored with a documented `.env.example`.
- **The test suite tests behaviour, not lines.** The state machine test
  generates the *full* state×event matrix from an independently written
  expected table, so one accidental table edit fails one named case
  (`SessionStateMachineTest.kt:51-59`). `LockerSimEngineTest` covers failure
  modes (FORCE_409 fires exactly once, SIZE_TOO_SMALL escalation, PARCEL_MISSING)
  and ends with property-style invariants over every parsed machine layout —
  unique sequential hardware addresses, equal column pitch, exactly one TC
  (`LockerSimEngineTest.kt:146-176`). That last test is the kind of thing that
  separates "wrote tests" from "thought about what can break".
- **N+1 avoided where it matters.** `TripService.kt:17-22` loads stops and
  parcels in two batched `IN` queries and groups in memory, instead of the
  naive per-stop loop.

## What's weak (ordered: blockers → majors → minors)

### Majors

1. **No resource-level authorization on locker sessions** —
   `LockerSessionController.kt:34-86`, `LockerSessionService.kt:190`.
   `courierId` is captured at session creation (`create`, from the JWT) and
   then never checked again: `status`, `finish`, and every hand-in/hand-out
   mutation load the session by id alone. Any authenticated courier-realm
   token can drive any other courier's session; `GET /api/trips`
   (`TripController.kt:16`) likewise returns all trips with no courier scoping.
   Why a reviewer cares: this is the classic IDOR shape, and "authn yes,
   authz no" is the first probe in any security review. Defensible house
   style? Only partially — the demo has one user, but unlike the in-memory
   lock or the demo sim surface, *this* gap is not documented anywhere as
   deliberate. Fix: compare `jwt.preferred_username` to `session.courierId`
   in `load()` (one line plus a 403 path), and say so in CLAUDE.md if the
   trips query stays unscoped on purpose.

2. **Idempotency holds only on the locked path** — `DeliveryService.kt:34-38`,
   `V1__init.sql` (`unique (session_id, barcode)`). The duplicate check is
   check-then-insert. Behind the per-session Mutex (the locker flow) that's
   safe single-replica. But `POST /api/deliveries/register`
   (`DeliveryController.kt:25-27`) takes no lock: two concurrent identical
   calls can both pass the check; with a `sessionId` the DB constraint turns
   the loser into a 500 (not the documented `duplicate: true`), and with
   `sessionId = null` Postgres treats the NULLs as distinct, so *both* insert
   and two outbox rows ship — exactly what the idempotency rule exists to
   prevent. Fix: `NULLS NOT DISTINCT` on the unique constraint (PG 15+),
   catch the violation and return the duplicate response.

### Minors

3. **Order-dependent integration tests** —
   `HandInFlowIntegrationTest.kt:68,233-238`. `@TestMethodOrder` + test 3
   reading "the latest session" created by test 2. If the happy path fails,
   the concurrency test fails too, with a misleading message; tests can't run
   in isolation. Fix: have the race test create its own session and bind it.
4. **Reaper has no test** — `SessionReaper.kt:44-76`. It's a documented
   cross-cutting rule (rule 4 in CLAUDE.md) with real branching (best-effort
   remote finish, local expiry, per-parcel NOT_DELIVERED) and zero coverage.
   The bridge pattern (`runBlocking` justified at `SessionReaper.kt:28-29`) is
   fine; the lack of a test for the one component that mutates data on a
   timer is not.
5. **`finish()` leaves the local row ACTIVE on the reconciled path** —
   `LockerSessionService.kt:113-128`. If the remote finish 409s, the local
   status update is skipped and the session stays ACTIVE until the reaper
   expires it 5 minutes later — the status endpoint reports a session the
   locker considers finished as ACTIVE in between. Also: two separate lock
   acquisitions (`mutation` then the follow-up block) leave a small window
   between them. Likely harmless in practice; worth a comment if intended.
6. **SLOW_NETWORK sleeps while holding the engine monitor** —
   `LockerSimEngine.kt:434-436` + `@Synchronized` entry points. The 3s
   `Thread.sleep` serializes *all* sim traffic (the 1s machine-page poll and
   the 1.5s session poll queue behind it) and parks `Dispatchers.IO` threads.
   For a failure-injection demo mode this may even be the desired drama, but
   the doc says "3s delay on all sim responses", not "global stall"; sleeping
   outside the monitor would match the stated behaviour.
7. **`spring-boot-starter-validation` is a dependency, but bean validation is
   never used** — `build.gradle.kts:34`, zero `@Valid`/`@NotNull` in
   `src/main`. Input checking is manual (`requireBarcode`,
   `LockerSessionController.kt:86`) and type-driven, which mostly works — but
   the new `ParcelAnnouncement` (uncommitted) accepts negative dimensions and
   a zero-length barcode silently. Either use the starter or drop it.
8. **Exception detail dropped in the outbox failure log** —
   `OutboxPublisher.kt:30`. The caught `e` is not passed to `log.warn`, so the
   one log line you'll be staring at when publishing breaks has no cause in it.
9. **Two `!!` on `session`** — `LockerSimEngine.kt:364,410`. Both are
   reachable only after a null check upstream, but threading the checked
   value through would cost nothing and remove the trap for the next editor.
10. **Uncommitted feature on the working tree** — `ParcelIntakeConsumer.kt`,
    `ParcelAnnouncement.kt` untracked; `SimProxyController.kt`,
    `application.yml` modified. The code itself is fine (idempotent by
    barcode, malformed messages logged and skipped, never blocks the
    partition); the process note is that an audit of the *repo* would not see
    it. Commit or stash before any external review.
11. **No static-analysis beyond ktlint.** ktlint enforces format, not smells;
    detekt is the community-standard companion and its absence means rules
    like complexity caps and exception-swallowing are human-enforced only.
    Given the demonstrated discipline this is a small gap, but CI currently
    cannot catch a regression of the house rules it doesn't encode.

## Design choices — defended and questioned

- **WebFlux + coroutines for a BFF.** Reason given (CLAUDE.md): a polling
  proxy with high fan-out of short calls, plus prior team experience. Cost:
  the harder programming model, and the JPA mismatch below. Verdict: the
  rationale is genuinely strong *for this shape of service* and the rules
  that make it safe (IO hops, no hand-written Reactor) are documented and
  followed. A senior accepts this trade as argued.
- **Blocking JPA under WebFlux instead of R2DBC.** Every repository call is
  wrapped in `withContext(Dispatchers.IO)` (`LockerSessionService.kt:192`).
  Cost: each "non-blocking" request still parks an IO thread per DB call —
  the fan-out benefit applies to the HTTP legs, not the DB legs. Defensible
  (JPA's maturity vs R2DBC's sharp edges is a legitimate call), and the
  hops are flawless, but be ready for the obvious question: "why not R2DBC,
  and at what load does the IO pool become the bottleneck?"
- **In-memory per-session lock, single replica.** Named, costed, alternative
  specified (`SessionLocks.kt:14-17`). This is how to do a demo
  simplification. Accepted.
- **One Gradle module, two roles via env flags** (`application.yml`,
  `@ConditionalOnBooleanProperty` throughout). Cost: the sim and the BFF
  share a classpath, so a careless import could couple them invisibly —
  nothing mechanical prevents `service.locker` from calling
  `service.sim.LockerSimEngine` directly instead of going through HTTP.
  Today the only coupling is the deliberate, commented DTO reuse in
  `LockerSessionService`; an ArchUnit rule would make the boundary
  assessor-proof.
- **Sequential outbox publisher.** Ordering over throughput, stated
  (`OutboxPublisher.kt:14-17`). Note when questioned: the FIFO claim holds
  per instance; the at-least-once redelivery on partial batch failure is the
  part to be able to explain (consumers must be idempotent — yours is, by
  barcode).
- **Committed demo secret** (`locker-demo-secret` in `application.yml:56`
  fallback and `infra/keycloak/realm-locker.json`). Acknowledged in
  `.env.example` ("must match"). For a demo realm this is fine; say it out
  loud before someone else finds it.

## Test reality check

Covered, and well: the full state×event matrix (every legal *and* illegal
transition individually named), the complete hand-in happy path through real
HTTP + real Postgres + real Redpanda asserting the registration row, the
outbox row, the parcel status, *and* the consumed Kafka message; duplicate
confirm (idempotency); two-writer race (reconciliation); FORCE_409 one-shot
semantics; size-escalation; hand-out including PARCEL_MISSING; layout-parser
invariants; anonymous requests rejected (401).

Uncovered: the reaper (timer-driven data mutation — the riskiest untested
code in the repo), the circuit breaker (no test ever opens it), hand-out at
the integration level (engine-level only), `ParcelIntakeConsumer` (new,
untested), and any authorization test beyond "no token → 401" — which is
precisely the dimension where the code has its one real gap. Test quality is
high: assertions are about behaviour and invariants, not implementation.

## The verdict

No explicit question accompanied this audit, so: **is this professional,
senior-grade work? Yes.** An assessor reading this repo will conclude the
author understands distributed-systems failure modes (optimistic locking,
reconciliation, outbox, circuit breaking, idempotency) well enough to *build
the demonstration rig for them*, writes for the next reader, and — most
unusually — documents trade-offs before being asked.

What to fix or be ready to defend in person, ranked:

1. **Fix before showing anyone**: the session-ownership check (finding 1).
   It's a one-line fix and the single finding that reads as "didn't notice"
   rather than "chose not to".
2. **Fix cheaply**: the unlocked register race / NULL-distinct unique
   (finding 2), the order-dependent tests (3), the dropped exception in the
   outbox log (8). Commit the parcel-intake work (10).
3. **Be ready to explain**: JPA-on-WebFlux and where its ceiling is; the
   single-replica lock and its Postgres-advisory-lock successor; sequential
   outbox ordering semantics; why the trips endpoint is unscoped. The
   existing written rationale carries most of this already — that is worth
   real credit in any assessment.

## If I had 2 days / 2 weeks to raise the grade

**2 days:**
1. Session-ownership check + a 403 integration test (closes the only major).
2. `NULLS NOT DISTINCT` + constraint-violation handling in
   `DeliveryService.register`; concurrent-register test.
3. Make the integration tests order-independent.
4. Reaper test (stale session → remote finish attempted → EXPIRED +
   NOT_DELIVERED registered).
5. Pass `e` to the outbox warn log; commit the parcel-intake feature with
   validation on `ParcelAnnouncement`.

**2 weeks:**
1. ArchUnit (or Konsist) rules encoding CLAUDE.md: no `service.locker` →
   `service.sim` imports, controllers never touch repositories, no
   Mono/Flux in main — turning house rules into CI gates.
2. detekt with a tuned baseline.
3. Circuit-breaker integration test (kill the sim base URL, assert 503 +
   the Dutch message, assert recovery).
4. Courier-scoped trips query, multi-courier seed, and authz tests across
   every mutating endpoint.
5. A load smoke (k6/Gatling) of the polling fan-out to put a number on the
   JPA-on-IO-pool ceiling — the strongest possible answer to the WebFlux
   question.
