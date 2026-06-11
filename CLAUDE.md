# CLAUDE.md — dhl-backend

Kotlin + Spring Boot backend for the locker integration demo. This repo owns
**three things**: the courier app backend (BFF), the **locker-sim** (simulated
Locker API of the "other team"), and **all shared infrastructure** (server
bootstrap, k3s manifests, Keycloak realms, Postgres, Redpanda). The frontend
repo (dhl-frontend) only deploys its own image into the cluster this repo sets up.

## Git conventions

Commits carry NO AI attribution: no `Co-Authored-By: Claude`, no
"Generated with Claude Code" lines — in commit messages or PR bodies.

## Domains (DNS A-records → 167.233.127.230, set manually first)

```
dhl-courier.vanharen-it.nl   # courier app (frontend)
dhl-locker.vanharen-it.nl    # parcel machine page (frontend, same bundle)
dhl-api.vanharen-it.nl       # this backend
dhl-auth.vanharen-it.nl      # keycloak
```

vanharen-it.nl hosts other services elsewhere — only these dhl-* records point
at this server. locker-sim itself gets NO public hostname (ClusterIP only).

## Stack

- Kotlin 2.4 (latest 2.x), Spring Boot 4.x, Java 25, Gradle Kotlin DSL, Postgres 17
- **Spring WebFlux with coroutines as the only programming model**: suspend
  controllers/services, WebClient + `awaitBody`, coroutine `Mutex` for locks.
  Reactor runs underneath but NO hand-written Mono/Flux in production code
  (tests may bridge, e.g. the stub ReactiveJwtDecoder). Blocking JPA/engine
  work always hops to `Dispatchers.IO` — never on the event loop.
  *Why WebFlux*: this BFF is a polling proxy — many concurrent short calls
  (trips every 5s, session status every 1.5s, sim state every 1s, per client)
  fanning out to Keycloak and the locker-sim; non-blocking handles that
  fan-out without tying a thread per in-flight request. It also matches
  existing WebFlux experience from bol.com and Alliander.
- Spring Security 7 (two OAuth2 resource server chains), Spring Data JPA, Flyway
- Jackson 3 (`tools.jackson`), springdoc-openapi 3: spec at /v3/api-docs —
  dhl-frontend generates its client from this
- resilience4j circuit breaker (core lib, wired programmatically — no Boot 4 starter)
- Testcontainers 2.x (Postgres, Redpanda); artifact ids are
  `testcontainers-postgresql` etc., managed by the Boot BOM

## Code layout (package nl.callido.dhl)

Layer-first packages with feature subpackages; ONE class per file — every
entity, repository, service and DTO has its own file:

```
config/               # DhlProperties, SecurityConfig, LockerClientConfig
common/               # ParcelSize (t-shirt sizing, derived from dimensions)
domain/               # JPA entities + enums
repository/           # Spring Data repositories
dto/{trips,locker,delivery,sim}/
client/               # LockerSimClient + token provider + exceptions
service/{trips,locker,delivery,outbox,sim}/   # sim = the locker simulator engine
controller/{trips,locker,delivery,sim}/
```

## Build & run

- One Gradle module, one Spring Boot app. The BFF (`/api`) and locker-sim
  (`/locker-api`) live in the same image; env flags split roles in k3s:
  `DHL_BACKEND_ENABLED`, `LOCKER_SIM_SERVE` (see application.yml). The parcel
  machine is ALWAYS the simulator — there is no real-machine mode.
- Local dev: `docker compose -f infra/docker-compose.yml up -d` then
  `set -a; source infra/.env; set +a; ./gradlew bootRun` — one process serves
  both APIs, talking to itself. The env sourcing matters: secrets (e.g.
  LOCKER_CLIENT_SECRET) have no defaults in application.yml; in the IDE, add
  LOCKER_CLIENT_SECRET from infra/.env to the run configuration.
- Tests: `./gradlew test` (state machine + engine unit tests run anywhere;
  integration tests need Docker, auto-skip without it).
- Secrets for the demo server live in `infra/.env` (gitignored,
  `.env.example` documents the keys); `infra/apply-secrets.sh` pushes them
  into the cluster.

## Parcel sizing

Parcels carry real dimensions (length/width/height cm + weight); the t-shirt
size (XXS–XXL) is DERIVED from those via `ParcelSize.forDimensions`
(orientation-free fit), never stored. Compartments are modelled after a real
PostNL locker (label, column, hardware address, optional back door, enabled).
Machine layouts in `LockerConfigurations` are parsed from REAL machine
templates (`<steel> <hw> [<col> <slot>...]`): doors XS–XL, B = brievenbus
(letterbox, fixed hw address, disabled), TC = technical compartment (screen,
camera, scanner — the machine page console), FC = functional compartment.
Configs: MINI, COMPACT, BIG. The frontend renders doors true to scale via a
door-pitch table mirrored in MachinePage.tsx. The
hand-out parcel DHL-OUT-001 is pre-loaded in the first M compartment.

## Authentication — two Keycloak realms (in-cluster Keycloak pod)

- `/api/**` → resource server, realm `courier`
  (issuer https://dhl-auth.vanharen-it.nl/realms/courier)
- `/locker-api/**` → resource server, realm `locker`
  (issuer https://dhl-auth.vanharen-it.nl/realms/locker)
- Backend → locker-sim calls authenticate via **client credentials** in the
  `locker` realm (Spring OAuth2 client manager, token cached, auto-refreshed).
  The token must never appear in any response, log, or error message.
- Local dev: docker-compose Keycloak with `--import-realm`; realm JSONs live in
  /infra/keycloak/ (realm `courier`: public client `dhl-frontend` with PKCE,
  user koerier (password via DEMO_USER_PASSWORD); realm `locker`: confidential
  client `dhl-backend` (secret via LOCKER_CLIENT_SECRET) — both substituted
  from the env at realm import; values live in infra/.env, never in git).
- All endpoints/secrets via env vars so one image serves compose and k3s
  (KEYCLOAK_ISSUER_COURIER, KEYCLOAK_ISSUER_LOCKER, LOCKER_CLIENT_SECRET, ...).

## Domain model (Flyway migration + seed)

- trip → stop (deliveryLocationType: DOOR | SERVICE_POINT | LOCKER) → parcel
  (barcode, direction: HAND_IN | HAND_OUT, status)
- locker_session (id, stop_id, courier_id, external_session_id, status,
  sim_version, created_at, last_activity_at, finished_at)
- delivery_registration (id, session_id, barcode, status, registered_at,
  UNIQUE(session_id, barcode)) — the idempotency guard
- outbox (id, aggregate_type, aggregate_id, event_type, payload jsonb,
  created_at, published_at)
- Seed (Flyway V2, deterministic UUIDs): 1 trip, 3 stops; the LOCKER stop has
  2 hand-in parcels (DHL-IN-001, DHL-IN-002) + 1 hand-out parcel (DHL-OUT-001).
  The sim pre-loads DHL-OUT-001 in compartment 7 — keep these in sync.

## Courier API (`/api`, courier realm)

- GET  /api/trips
- POST /api/locker/sessions {stopId} → init at locker-sim, persist, return {sessionId, qrPayload}
- GET  /api/locker/sessions/{id} → proxied status
- POST /api/locker/sessions/{id}/finish
- POST /api/locker/sessions/{id}/hand-in/validate|attempt|confirm|continue|report-size|report-issue|reopen
- POST /api/locker/sessions/{id}/hand-out/start|continue|confirm|report-missing|abort
- POST /api/deliveries/register — the "existing" registerDelivery endpoint; the
  locker flow calls it internally on HANDED_IN / hand-out confirm. Keep it as a
  real endpoint to demonstrate path reuse.

Cross-cutting rules in the proxy layer:
1. Per-session serialization: in-memory lock keyed on sessionId. Code comment:
   multi-replica would need a Postgres advisory lock or Redis — talking point.
2. On 409 from locker-sim: refetch state, reconcile, return current state with
   `reconciled: true`. Never blind-retry mutations.
3. Idempotency on registration: key = sessionId + barcode; duplicate confirms
   must not create duplicate outbox rows.
4. @Scheduled session reaper: last_activity_at > 5 min → session/finished at sim
   + register NOT_DELIVERED.
5. resilience4j circuit breaker around all locker-sim calls; open circuit →
   503 {"message": "Pakketautomaat tijdelijk niet bereikbaar"}.

## Outbox → Kafka

- Outbox row written in the same transaction as the delivery registration.
- @Scheduled publisher polls unpublished rows → Redpanda topic `delivery-events`
  (JSON), marks published_at.
- Demo consumer: `rpk topic consume delivery-events` (documented in /infra/README).

## locker-sim (`/locker-api`, locker realm) — the simulated parcel machine

Mirrors the case's Locker API exactly:
- POST /locker-api/courier/session/init → {sessionId, qrCode}
- GET  /locker-api/courier/session/status → NOT_READY | READY
- POST /locker-api/courier/session/finished
- Hand-in: validate, attempt, confirm, continue, report-incorrect-compartment-size,
  report-compartment-issue, reopen-compartment
- Hand-out: start, continue, confirm, report-missing, report-compartment-issue, abort

State machine: explicit enum + allowed-transitions table (Map<State, Set<Event>>),
table-driven unit tests. Optimistic locking: integer version per session; stale
version → 409. Compartments: fixed grid of 12 (S/M/L), states
FREE | RESERVED | OCCUPIED | DOOR_OPEN | DEFECT.

### Simulator control API (`/locker-api/sim`, locker realm — demo only)

Powers the parcel-machine page in dhl-frontend:
- POST /locker-api/sim/bind {qrCode} — the "physical scan": binds session → READY
- GET  /locker-api/sim/state — full snapshot, polled by the machine page every 1s:
  {session: {id, state, version, boundAt}, compartments: [{nr, size, state, barcode?}],
   activeFailures: [...], eventLog: [last 50]}
- POST /locker-api/sim/door {compartmentNr, action: CLOSE | LEAVE_OPEN}
- POST /locker-api/sim/failures {mode, enabled} — modes:
  SIZE_TOO_SMALL, DOOR_STUCK, COMPARTMENT_DEFECT, PARCEL_MISSING (hand-out),
  SLOW_NETWORK (3s delay on all sim responses), FORCE_409 (next mutation → 409)
- POST /locker-api/sim/reset — clean state between demo runs

Event log: every received API call → {ts, endpoint, payload-summary,
resultingState, version}; in-memory ring buffer (50).

The backend exposes thin authenticated passthroughs under /api/sim/** (courier
realm) so the machine page never needs a locker-realm token. All /api/sim/**
behind a SIM_ENABLED env flag.

## Tests (minimum bar)

- State machine: table-driven, all legal + illegal transitions.
- Integration (Testcontainers, both realms): full hand-in happy path → outbox
  row + Kafka message asserted.
- Concurrency: two parallel continue calls → one 409 → reconciled response.
- Idempotency: double confirm → single outbox row.

---

# INFRA & BOOTSTRAP (/infra in this repo — shared by both repos)

This repo owns the cluster. dhl-frontend assumes everything below exists.

## /infra layout

```
infra/
  bootstrap/server-bootstrap.sh      # steps 1-2 below, idempotent
  keycloak/realm-courier.json        # PKCE client dhl-frontend, user koerier
  keycloak/realm-locker.json         # confidential client dhl-backend
  k8s/                               # kustomize base
    namespace.yaml                   # namespace: dhl-demo
    postgres.yaml                    # PVC via local-path (k3s default)
    redpanda.yaml                    # single node
    keycloak.yaml                    # official image, --import-realm via ConfigMap,
                                     # ingress dhl-auth.vanharen-it.nl + TLS
    backend.yaml                     # deployment + svc + ingress dhl-api.vanharen-it.nl
    locker-sim-svc.yaml              # ClusterIP ONLY — no ingress, deliberate (BFF proof)
    seed-job.yaml
    cluster-issuer.yaml              # letsencrypt
  docker-compose.yml                 # local dev: postgres, keycloak, redpanda
```

## 1. Server bootstrap (clean Ubuntu 24.04, root@167.233.127.230, key in ssh-agent)

```bash
ssh root@167.233.127.230 bash -s <<'EOF'
set -euo pipefail
apt-get update && apt-get -y upgrade
apt-get -y install ufw fail2ban unattended-upgrades curl
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config
systemctl restart ssh
ufw default deny incoming; ufw default allow outgoing
ufw allow 22/tcp; ufw allow 80/tcp; ufw allow 443/tcp; ufw allow 6443/tcp
ufw --force enable
systemctl enable --now fail2ban
EOF
```

(Tighter alternative: `ufw allow from <home-ip> to any port 6443 proto tcp`.)

## 2. k3s install

```bash
ssh root@167.233.127.230 \
  'curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--tls-san 167.233.127.230" sh - && k3s kubectl get nodes'
```

Traefik + servicelb ship with k3s — we use them as-is.

## 3. kubeconfig to workstation

```bash
scp root@167.233.127.230:/etc/rancher/k3s/k3s.yaml ~/.kube/dhl-demo.yaml
sed -i 's/127.0.0.1/167.233.127.230/' ~/.kube/dhl-demo.yaml   # macOS: sed -i ''
export KUBECONFIG=~/.kube/dhl-demo.yaml
kubectl get nodes
```

## 4. cert-manager + ClusterIssuer

```bash
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml
kubectl -n cert-manager wait --for=condition=Available deploy --all --timeout=180s
kubectl apply -f infra/k8s/cluster-issuer.yaml   # ACME http01, class traefik,
                                                 # email niels@ludicrous.dev
```

Every ingress: annotation `cert-manager.io/cluster-issuer: letsencrypt` + tls block.

## 5. Namespace + GHCR pull secret

```bash
kubectl apply -f infra/k8s/namespace.yaml   # dhl-demo
kubectl -n dhl-demo create secret docker-registry ghcr \
  --docker-server=ghcr.io --docker-username=<github-user> --docker-password=<ghcr-pat>
```

Never commit the PAT; Claude Code must ask for it interactively at this step.

## 6. Shared services + backend

```bash
kubectl kustomize --load-restrictor LoadRestrictionsNone infra/k8s | kubectl apply -f -
kubectl -n dhl-demo get pods    # postgres, redpanda, keycloak, dhl-backend Running
curl -s https://dhl-auth.vanharen-it.nl/realms/courier/.well-known/openid-configuration | head
curl -s https://dhl-api.vanharen-it.nl/actuator/health
```

## CI

GitHub Actions on main: build + push ghcr.io/<user>/dhl-backend, then a deploy
job applies the manifests and rolls the deployments (kubeconfig from the
`KUBECONFIG_B64` repo secret). Manual deploy if ever needed:
`kubectl kustomize --load-restrictor LoadRestrictionsNone infra/k8s | kubectl apply -f -`
(the realm JSONs live outside infra/k8s, so plain `apply -k` refuses them).

## Milestones

M0 infra (bootstrap → cluster-issuer READY → keycloak realms reachable) →
M1 skeleton+auth (both realms verified, client-credentials token fetch logged OK) →
M2 trips+seed → M3 session/QR/bind → M4 hand-in happy path + outbox/Kafka →
M5 failures + 409 + reaper + breaker → M6 hand-out → M7 full k3s deploy +
demo script clean twice in a row. One milestone per PR; sanity checks green
before starting the next.
