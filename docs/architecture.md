# Architectuur — locker-integratie

Alle diagrammen hieronder zijn gebaseerd op de daadwerkelijke code in deze repo
(controllers, services, security-configuratie en schedulers), niet op de
ideaalplaatjes uit de casetekst. Waar de implementatie bewust afwijkt van de
letterlijke case-API staat dat bij het diagram aangegeven. Renderen naar SVG:
`make diagrams-export` (output in `docs/img/`).

Vaste deelnemers in alle sequencediagrammen: **Koerier-app**, **Machine-pagina**,
**BFF**, **Locker-sim**, **Keycloak**, **Postgres**, **Kafka**.

## 1. Architectuuroverzicht

Drie vertrouwenszones: de browser (alleen courier-realm tokens), het k3s-cluster
(namespace `dhl-demo`) en — daarbinnen — het locker-realm dat uitsluitend
server-side bestaat. De locker-sim heeft bewust géén ingress en géén publieke
hostname: de enige route ernaartoe loopt via de BFF.

```mermaid
flowchart TB
    subgraph browser["Browser (courier-realm tokens, PKCE)"]
        courier["Koerier-app<br/>dhl-courier.vanharen-it.nl"]
        machine["Machine-pagina<br/>dhl-locker.vanharen-it.nl"]
    end

    subgraph cluster["k3s-cluster — namespace dhl-demo"]
        keycloak["Keycloak<br/>dhl-auth.vanharen-it.nl<br/>realms: courier + locker"]
        bff["BFF /api<br/>dhl-api.vanharen-it.nl<br/>(deze backend)"]

        subgraph lockerzone["locker-realm — uitsluitend server-side"]
            sim["Locker-sim /locker-api<br/>ClusterIP only — geen ingress,<br/>geen publieke hostname (bewust)"]
        end

        pg[("Postgres<br/>trips, parcels, locker_session,<br/>delivery_registration, outbox")]
        kafka[["Redpanda<br/>topic delivery-events"]]
    end

    courier -- "Bearer (courier-realm)" --> bff
    machine -- "Bearer (courier-realm)<br/>/api/sim/** passthrough" --> bff
    courier -. "PKCE login" .-> keycloak
    machine -. "PKCE login" .-> keycloak

    bff -- "client credentials (locker-realm)<br/>token bereikt nooit de browser" --> sim
    bff -. "token request" .-> keycloak
    bff --> pg
    bff -- "outbox-publisher (@Scheduled 2s)" --> kafka

    classDef zone fill:#fff8e1,stroke:#d40511
    class lockerzone zone
```

## 2. Sessie, QR en koppeling (case-vraag 2)

Hoe start de koerier een lockersessie en hoe wordt hij gekoppeld aan de fysieke
automaat? De BFF initieert de sessie bij de Locker API, bewaart de koppeling in
Postgres en geeft de QR-payload terug; de machine "scant" de QR (webcam op de
machine-pagina) en de statuspoll van de app klapt van NOT_READY naar READY.

```mermaid
sequenceDiagram
    participant K as Koerier-app
    participant M as Machine-pagina
    participant B as BFF
    participant S as Locker-sim
    participant P as Postgres

    K->>B: POST /api/locker/sessions {stopId}
    B->>S: POST /locker-api/courier/session/init
    S-->>B: {sessionId, qrCode}
    B->>P: insert locker_session (ACTIVE, simVersion 0)
    B-->>K: 201 {sessionId, qrPayload}
    K->>K: toont QR fullscreen — "Wachten op koppeling…"

    loop elke 1,5s
        K->>B: GET /api/locker/sessions/{id}
        B->>S: GET /locker-api/courier/session/status
        S-->>B: NOT_READY (state CREATED)
        B-->>K: {status: NOT_READY, simState: CREATED}
    end

    M->>B: POST /api/sim/bind {qrCode}  (webcam-scan)
    B->>S: POST /locker-api/sim/bind
    S->>S: CREATED → READY, version++
    S-->>B: snapshot READY
    B-->>M: "Gekoppeld"

    K->>B: GET /api/locker/sessions/{id}
    B->>S: GET status
    S-->>B: READY
    B-->>K: {status: READY} → wizard start
```

## 3. Authenticatie over twee realms (case-vraag 3)

De browser logt éénmalig in via PKCE in het courier-realm; elke `/api`-call
draagt dat bearer-token. Richting de Locker API gebruikt de BFF een eigen
client-credentials-token uit het locker-realm, dat door Spring's
`AuthorizedClientServiceOAuth2AuthorizedClientManager` wordt gecachet en pas
tegen expiratie ververst — het locker-token verlaat de backend nooit.

```mermaid
sequenceDiagram
    participant K as Koerier-app
    participant KC as Keycloak
    participant B as BFF
    participant S as Locker-sim

    K->>KC: redirect /realms/courier/auth (PKCE, client dhl-frontend)
    KC-->>K: code → token-exchange → access token (courier)

    K->>B: GET /api/trips — Authorization: Bearer (courier)
    B->>B: courierChain valideert JWT (issuer realm courier)

    K->>B: POST /api/locker/sessions/{id}/hand-in/attempt
    alt token in cache en niet verlopen
        B->>B: cache-hit: hergebruik locker-token
    else verlopen of eerste call
        B->>KC: POST /realms/locker/token (client_credentials, dhl-backend)
        KC-->>B: access token (locker) — gecachet
    end
    B->>S: POST /locker-api/courier/hand-in/attempt — Bearer (locker)
    S->>S: lockerChain valideert JWT (issuer realm locker)
    S-->>B: snapshot
    B-->>K: LockerActionResponse — locker-token zit NIET in de respons
```

## 4. Hand-in happy path + outbox → Kafka (case-vragen 1 en 4)

Al het verkeer loopt via de BFF (vraag 1); de eigen backend blijft in sync
doordat de bevestiging in dezelfde transactie een `delivery_registration` én
een outbox-rij schrijft (vraag 4). **Afwijking t.o.v. de case-tekst**: de
voortgang wordt niet via `POST /continue`-polling bewaakt maar via de
1,5s-statuspoll; `continue` betekent hier "volgend pakket".

```mermaid
sequenceDiagram
    participant K as Koerier-app
    participant M as Machine-pagina
    participant B as BFF
    participant S as Locker-sim
    participant P as Postgres
    participant KA as Kafka

    K->>B: POST .../hand-in/validate {barcode}
    B->>S: validate
    S-->>B: {valid: true, suggestedSize}
    K->>B: POST .../hand-in/attempt {barcode}
    Note over B: per-sessie Mutex (SessionLocks)
    B->>S: attempt (version n)
    S->>S: kleinste passende vrije deur → DOOR_OPEN
    S-->>B: snapshot + compartment
    B-->>K: vak X open — "plaats pakket"

    M->>B: POST /api/sim/door {nr, CLOSE}
    B->>S: sim/door CLOSE
    S->>S: HAND_IN_DOOR_OPEN → HAND_IN_AWAITING_CONFIRM
    Note over K: 1,5s-statuspoll ziet AWAITING_CONFIRM

    K->>B: POST .../hand-in/confirm {barcode}
    B->>S: confirm
    S-->>B: HAND_IN_COMPLETED
    B->>P: registerDelivery — één @Transactional:<br/>1. idempotentie-check UNIQUE(session_id, barcode)<br/>2. delivery_registration HANDED_IN<br/>3. parcel.status = HANDED_IN<br/>4. outbox-rij DELIVERY_REGISTERED
    B-->>K: HANDED_IN ✓

    loop @Scheduled elke 2s
        B->>P: findTop100ByPublishedAtIsNull
        B->>KA: send "delivery-events" (key = barcode)
        B->>P: publishedAt = now()
    end

    K->>B: POST .../hand-in/continue  (volgend pakket)
    B->>S: continue → READY
```

## 5. Storing: vak te klein (case-vraag 5)

**Afwijking t.o.v. de case-tekst**: er bestaat geen `SIZE_PROPOSAL`-status in
de respons van `attempt` of `continue`. In de implementatie levert de sim met
`SIZE_TOO_SMALL` actief bewust een te klein vak uit; de koerier meldt dat via
`report-incorrect-compartment-size`, waarna de sim de gemelde maat onthoudt en
de eerstvolgende attempt automatisch één maat hoger kiest (S → M → … → XXL).
Past zelfs het grootste vrije vak niet, dan valt het pakket terug op de
standaard niet-bezorgd-afhandeling.

```mermaid
sequenceDiagram
    participant K as Koerier-app
    participant B as BFF
    participant S as Locker-sim
    participant P as Postgres

    Note over S: storing SIZE_TOO_SMALL actief (operator-console)
    K->>B: POST .../hand-in/attempt {barcode}
    B->>S: attempt
    S->>S: sabotage: bewust te klein vak → DOOR_OPEN
    S-->>B: compartment (te klein)
    B-->>K: vak open

    K->>B: POST .../hand-in/report-size  ("Vak te klein")
    B->>S: report-incorrect-compartment-size
    S->>S: vak weer FREE, sizeHint[barcode] = gemelde maat
    S-->>B: READY
    B-->>K: READY — app her-attempt automatisch

    K->>B: POST .../hand-in/attempt {barcode}
    B->>S: attempt
    S->>S: minimummaat = hint + 1 → kleinste vrije grotere deur
    S-->>B: compartment (één maat groter)
    B-->>K: groter vak open

    opt zelfs grootste vrije vak te klein
        B->>S: validate
        S-->>B: {valid: false, reason: NO_CAPACITY}
        B-->>K: "kan niet bezorgd worden"
        K->>B: POST /api/deliveries/register {NOT_DELIVERED}
        B->>P: registratie + outbox (standaard afhandeling)
    end
```

## 6. Storing: deur klemt en heropenen (case-vraag 5)

**Afwijking t.o.v. de case-tekst**: de case formuleert dit als "continue blijft
NOT_READY"; in de implementatie blijft de 1,5s-statuspoll in
`HAND_IN_DOOR_OPEN` hangen en weigert `sim/door CLOSE` met `failure:
DOOR_STUCK` zolang de storing actief is. Na acht polls (±12s) biedt de app de
uitwegen `reopen-compartment` en `report-compartment-issue` aan.

```mermaid
sequenceDiagram
    participant K as Koerier-app
    participant M as Machine-pagina
    participant B as BFF
    participant S as Locker-sim

    Note over S: storing DOOR_STUCK actief
    K->>B: attempt → vak open (HAND_IN_DOOR_OPEN)
    M->>B: POST /api/sim/door {CLOSE}
    B->>S: sim/door CLOSE
    S-->>B: failure DOOR_STUCK — state blijft HAND_IN_DOOR_OPEN

    loop statuspoll blijft HAND_IN_DOOR_OPEN (> 8 polls)
        K->>B: GET sessie-status
        B-->>K: HAND_IN_DOOR_OPEN
    end
    K->>K: toont "Deur niet gesloten?" met uitwegen

    alt heropenen
        K->>B: POST .../hand-in/reopen
        B->>S: reopen-compartment → zelfde vak weer DOOR_OPEN
        Note over S: operator zet storing uit
        M->>B: door CLOSE → AWAITING_CONFIRM → herstel
    else vak defect melden
        K->>B: POST .../hand-in/report-issue
        B->>S: report-compartment-issue
        S->>S: vak → DEFECT, sessie → READY
        B-->>K: app her-attempt → volgende vrije deur,<br/>zelfde maat eerst, anders één maat groter
    end
```

## 7. Storing: 409-conflict en reconciliatie (case-vraag 4)

De Locker API gebruikt optimistic locking: een mutatie met een verouderde
versie levert 409. De BFF serialiseert mutaties per sessie met een in-memory
`Mutex` (bewust eenvoudig — multi-replica zou een Postgres advisory lock of
Redis vragen) en bij een 409 wordt nooit blind opnieuw geprobeerd: de BFF haalt
de actuele staat op en geeft die terug met `reconciled: true`.

```mermaid
sequenceDiagram
    participant K as Koerier-app
    participant B as BFF
    participant S as Locker-sim
    participant P as Postgres

    par twee gelijktijdige mutaties
        K->>B: POST .../hand-in/continue (tab 1)
    and
        K->>B: POST .../hand-in/continue (tab 2)
    end
    Note over B: SessionLocks: coroutine-Mutex per sessionId —<br/>tab 2 wacht tot tab 1 klaar is

    B->>S: continue (version n)
    S-->>B: OK, version n+1
    B->>P: simVersion = n+1
    B-->>K: respons tab 1

    B->>S: continue (version n — verouderd)
    S-->>B: 409 Conflict {reason: STALE_VERSION}
    Note over B: nooit blind herhalen
    B->>S: GET session/status  (waarheid ophalen)
    S-->>B: state + version n+1
    B->>P: simVersion = n+1
    B-->>K: {reconciled: true, reconcileReason: STALE_VERSION,<br/>simState: actuele staat} → UI toont actuele stap
```

## 8. Verbindingsverlies, hervatten en de reaper (case-vraag 5)

Alle voortgang is server-side: de wizard rendert puur op de laatst gepolde
status, dus een gesloten tab die heropent op dezelfde sessie-URL landt vanzelf
op de juiste stap. Verdwijnt de koerier definitief, dan ruimt de reaper
(elke 60s, sessies zonder activiteit > `dhl.reaper.timeout`, default 5m) de
sessie op en registreert hij de resterende pakketten als NOT_DELIVERED.

```mermaid
sequenceDiagram
    participant K as Koerier-app
    participant B as BFF
    participant S as Locker-sim
    participant P as Postgres

    K->>B: attempt → HAND_IN_DOOR_OPEN
    Note over K: tab sluit / verbinding valt weg

    alt koerier komt terug (zelfde URL)
        K->>B: GET /api/locker/sessions/{id}
        B->>S: status
        S-->>B: HAND_IN_DOOR_OPEN
        B-->>K: wizard landt direct op "sluit de deur"-stap
    else koerier blijft weg
        loop reaper @Scheduled elke 60s
            B->>P: sessies ACTIVE met lastActivityAt < now − 5m
        end
        Note over B: per sessie, onder de sessie-Mutex
        B->>S: POST session/finished (best effort)
        B->>P: voor elk EXPECTED-pakket van de stop:<br/>registerDelivery NOT_DELIVERED + outbox-rij
        B->>P: sessie → EXPIRED, finishedAt = now
    end
```

## 9. Circuit breaker: automaat onbereikbaar (case-vraag 5)

Alle sim-calls lopen door één resilience4j circuit breaker (window 10, drempel
50%, 10s open, 2 half-open proefcalls). 409 en 422 zijn business-signalen en
tellen niet mee; verbindingsfouten en 5xx wel. Bij een open circuit krijgt de
koerier direct een nette Nederlandse 503 en valt het pakket terug op de
standaard niet-bezorgd-afhandeling.

```mermaid
sequenceDiagram
    participant K as Koerier-app
    participant B as BFF
    participant S as Locker-sim
    participant P as Postgres

    K->>B: POST .../hand-in/attempt
    B->>S: attempt
    S--xB: connectiefout (automaat down)
    Note over B: breaker telt failure (409/422 tellen NIET mee)
    B-->>K: 503 {"message": "Pakketautomaat tijdelijk niet bereikbaar"}

    Note over B: ≥50% failures in window van 10 → breaker OPEN (10s)
    K->>B: retry
    B--xB: CallNotPermitted — sim wordt niet eens aangeroepen
    B-->>K: 503 (zelfde nette melding)

    opt pakket kan niet wachten
        K->>B: POST /api/deliveries/register {NOT_DELIVERED}
        B->>P: registratie + outbox (standaard afhandeling)
    end

    Note over B,S: na 10s half-open: 2 proefcalls —<br/>slagen ze, dan sluit de breaker
```

## 10. Hand-out: pakket ophalen (case-vraag 4)

**Afwijking t.o.v. de case-tekst**: de case beschrijft een machine-gedreven
iteratie (`start` laadt alle pakketten, `continue` levert per pakket
`COMPARTMENT_OPENED`/`DENIED`/`FINISHED`). De implementatie is pakket-gedreven:
de koerier kiest een pakket, `start {barcode}` opent precies het vak waar dat
pakket ligt, en `continue` zet de sessie terug op READY voor het volgende.
`report-missing` registreert NOT_DELIVERED via hetzelfde
`DELIVERY_REGISTERED`-outbox-event (een bezorgstatus, geen apart
exception-event — dat is een tweede bewuste vereenvoudiging).

```mermaid
sequenceDiagram
    participant K as Koerier-app
    participant M as Machine-pagina
    participant B as BFF
    participant S as Locker-sim
    participant P as Postgres
    participant KA as Kafka

    K->>B: POST .../hand-out/start {barcode}
    B->>S: hand-out/start
    S->>S: OCCUPIED vak met barcode → DOOR_OPEN
    S-->>B: compartment + parcelPresent
    B-->>K: "neem pakket uit vak X"

    alt pakket aanwezig
        M->>B: POST /api/sim/door {CLOSE}
        B->>S: door CLOSE → HAND_OUT_AWAITING_CONFIRM (vak FREE)
        K->>B: POST .../hand-out/confirm {barcode}
        B->>S: confirm → HAND_OUT_COMPLETED
        B->>P: registerDelivery HANDED_OUT + outbox (zelfde tx)
        B-->>K: opgehaald ✓
        K->>B: POST .../hand-out/continue → READY (volgend pakket)
    else pakket ontbreekt
        K->>B: POST .../hand-out/report-missing {barcode}
        B->>S: report-missing → vak FREE, sessie READY
        B->>P: registerDelivery NOT_DELIVERED + outbox (zelfde tx)
        B-->>K: gemeld — standaard afhandeling
    else afbreken
        K->>B: POST .../hand-out/abort
        B->>S: abort → READY
    end

    Note over B,KA: outbox-publisher levert alle registraties<br/>als DELIVERY_REGISTERED op topic delivery-events
```
