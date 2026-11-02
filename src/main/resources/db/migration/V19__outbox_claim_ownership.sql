-- V18 has already run in environments that deployed it, so its delivered shape is unchanged.
-- The claim token lets a relay instance prove it still owns a row before marking it published or
-- quarantining it; the claim count bounds an instance that keeps dying before it can record a
-- failure.
alter table ums_event_outbox
    add column claim_token uuid,
    add column claims integer not null default 0 check (claims >= 0);

-- The due index replaces the unpublished scan; nothing reads this one any more.
drop index if exists ums_event_outbox_unpublished;
