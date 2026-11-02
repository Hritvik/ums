-- One live capability per export: a second concurrent request must not be able to turn
-- "single use" into "one token per retry". Consumed rows drop out of the partial index.
-- Existing duplicates keep the newest capability row so the index can be built anywhere.
delete from ums_profile_export_capabilities stale
using ums_profile_export_capabilities newer
where stale.export_reference = newer.export_reference
  and stale.consumed_at is null and newer.consumed_at is null
  and (newer.requested_at, newer.capability_reference) > (stale.requested_at, stale.capability_reference);

create unique index ums_profile_export_capability_live
    on ums_profile_export_capabilities (export_reference) where consumed_at is null;
