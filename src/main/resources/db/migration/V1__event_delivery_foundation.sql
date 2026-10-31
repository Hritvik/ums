-- Delivery primitives only. Application schemas and producer authority must be
-- validated before future adapters write here. No profile business state yet.
create table ums_event_inbox (
    producer varchar(128) not null check (length(trim(producer)) > 0),
    event_id uuid not null,
    application_id varchar(256) not null check (length(trim(application_id)) > 0),
    stream_id varchar(256) not null check (length(trim(stream_id)) > 0),
    stream_revision bigint not null check (stream_revision > 0),
    processed_at timestamptz not null default current_timestamp,
    primary key (producer, event_id)
);

create table ums_event_outbox (
    event_id uuid primary key,
    application_id varchar(256) not null check (length(trim(application_id)) > 0),
    stream_id varchar(256) not null check (length(trim(stream_id)) > 0),
    stream_revision bigint not null check (stream_revision > 0),
    event_type varchar(128) not null check (length(trim(event_type)) > 0),
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    occurred_at timestamptz not null default current_timestamp,
    published_at timestamptz,
    unique (application_id, stream_id, stream_revision)
);

create index ums_event_outbox_unpublished on ums_event_outbox (occurred_at, event_id)
    where published_at is null;
