-- V18 has already run in environments that deployed it, so its delivered shape is unchanged.
-- The claim token lets a relay instance prove it still owns a row before marking it published or
-- quarantining it; the claim count bounds an instance that keeps dying before it can record a
-- failure.
-- if not exists keeps this harmless for a database migrated by the short-lived intermediate
-- build that added the same columns in V18.
alter table ums_event_outbox
    add column if not exists claim_token uuid,
    add column if not exists claims integer not null default 0 check (claims >= 0);

-- The due index created in V18 replaces the unpublished scan; nothing reads this one any more.
drop index if exists ums_event_outbox_unpublished;
