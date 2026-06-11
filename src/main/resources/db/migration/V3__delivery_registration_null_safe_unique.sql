-- Postgres treats NULLs as distinct in a plain unique constraint, so the
-- sessionless registerDelivery path (session_id IS NULL) was never
-- deduplicated: N identical doorstep registrations produced N rows and N
-- outbox events. NULLS NOT DISTINCT (PG >= 15) closes the hole; verified
-- beforehand that no environment holds duplicate (NULL, barcode) rows.
alter table delivery_registration
    drop constraint delivery_registration_session_id_barcode_key;
alter table delivery_registration
    add constraint delivery_registration_session_id_barcode_key
        unique nulls not distinct (session_id, barcode);
