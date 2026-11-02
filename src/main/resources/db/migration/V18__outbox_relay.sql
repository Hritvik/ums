-- Delivery state for the outbox relay. A row is delivered at least once: it is only marked
-- published after the transport accepts it, so a crash between publish and mark replays it.
alter table ums_event_outbox
    add column attempts integer not null default 0 check (attempts >= 0),
    add column next_attempt_at timestamptz not null default current_timestamp,
    add column quarantined_at timestamptz,
    add column failure_class varchar(64),
    add constraint ums_event_outbox_quarantine_complete
        check (quarantined_at is null or failure_class is not null);

create index ums_event_outbox_due
    on ums_event_outbox (next_attempt_at, occurred_at, event_id)
    where published_at is null and quarantined_at is null;

create index ums_event_outbox_quarantine
    on ums_event_outbox (quarantined_at)
    where quarantined_at is not null;
