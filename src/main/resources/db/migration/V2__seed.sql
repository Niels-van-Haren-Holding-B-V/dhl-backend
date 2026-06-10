-- Demo seed. Deterministic UUIDs so the demo script and frontend fixtures can
-- reference them. Barcodes must stay in sync with the locker-sim constants
-- (DHL-OUT-001 is pre-loaded in sim compartment 7).

insert into trip (id, name, trip_date)
values ('00000000-0000-0000-0000-000000000001', 'Route Amsterdam-Zuid', current_date);

insert into stop (id, trip_id, seq, address, delivery_location_type)
values ('00000000-0000-0000-0001-000000000001', '00000000-0000-0000-0000-000000000001', 1,
        'Beethovenstraat 12, Amsterdam', 'DOOR'),
       ('00000000-0000-0000-0001-000000000002', '00000000-0000-0000-0000-000000000001', 2,
        'Primera Gelderlandplein, Amsterdam', 'SERVICE_POINT'),
       ('00000000-0000-0000-0001-000000000003', '00000000-0000-0000-0000-000000000001', 3,
        'PakketAutomaat AMS-042, Zuidplein 1, Amsterdam', 'LOCKER');

-- Dimensions chosen so the derived t-shirt sizes are S, L and M respectively
-- (see ParcelSizing.kt for the size table).
insert into parcel (id, stop_id, barcode, direction, status, length_cm, width_cm, height_cm, weight_g)
values ('00000000-0000-0000-0002-000000000001', '00000000-0000-0000-0001-000000000003', 'DHL-IN-001', 'HAND_IN', 'EXPECTED', 40, 30, 11, 1200),
       ('00000000-0000-0000-0002-000000000002', '00000000-0000-0000-0001-000000000003', 'DHL-IN-002', 'HAND_IN', 'EXPECTED', 55, 40, 25, 4500),
       ('00000000-0000-0000-0002-000000000003', '00000000-0000-0000-0001-000000000003', 'DHL-OUT-001', 'HAND_OUT', 'EXPECTED', 42, 30, 18, 2000);
