-- One live capability per export: a second concurrent request must not be able to turn
-- "single use" into "one token per retry". Consumed rows drop out of the partial index.
create unique index ums_profile_export_capability_live
    on ums_profile_export_capabilities (export_reference) where consumed_at is null;
