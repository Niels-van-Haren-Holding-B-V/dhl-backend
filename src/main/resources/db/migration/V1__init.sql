create table trip (
    id        uuid primary key,
    name      text not null,
    trip_date date not null
);

create table stop (
    id                     uuid primary key,
    trip_id                uuid not null references trip (id),
    seq                    int  not null,
    address                text not null,
    delivery_location_type text not null check (delivery_location_type in ('DOOR', 'SERVICE_POINT', 'LOCKER'))
);

-- Real dimensions are leading; the t-shirt size (XXS..XXL) is DERIVED from
-- them (ParcelSizing.kt) and never stored — one source of truth.
create table parcel (
    id        uuid primary key,
    stop_id   uuid not null references stop (id),
    barcode   text not null unique,
    direction text not null check (direction in ('HAND_IN', 'HAND_OUT')),
    status    text not null,
    length_cm int  not null,
    width_cm  int  not null,
    height_cm int  not null,
    weight_g  int  not null default 1000
);

create table locker_session (
    id                  uuid primary key,
    stop_id             uuid        not null references stop (id),
    courier_id          text        not null,
    external_session_id text        not null,
    status              text        not null,
    sim_version         int         not null default 0,
    created_at          timestamptz not null,
    last_activity_at    timestamptz not null,
    finished_at         timestamptz
);

-- Idempotency guard: one registration per (session, barcode). Duplicate
-- confirms hit this table and must not produce a second outbox row.
create table delivery_registration (
    id            uuid primary key,
    session_id    uuid references locker_session (id),
    barcode       text        not null,
    status        text        not null,
    registered_at timestamptz not null,
    unique (session_id, barcode)
);

create table outbox (
    id             uuid primary key,
    aggregate_type text        not null,
    aggregate_id   text        not null,
    event_type     text        not null,
    payload        jsonb       not null,
    created_at     timestamptz not null,
    published_at   timestamptz
);

create index outbox_unpublished_idx on outbox (created_at) where published_at is null;
create index locker_session_active_idx on locker_session (status, last_activity_at);
