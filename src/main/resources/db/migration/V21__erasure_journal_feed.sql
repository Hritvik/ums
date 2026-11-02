-- The erasure journal lives outside the database so a pre-deletion backup cannot restore the
-- data it forgot. This feed is the retry queue: the entry is written in the same transaction as
-- the erasure, then appended to the journal and marked appended.
create table ums_erasure_journal_feed (
    feed_reference uuid primary key,
    feed_sequence bigserial not null unique,
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    subject_reference uuid not null,
    retired_profile_reference uuid not null,
    retired_generation bigint not null check (retired_generation > 0),
    erased_binding_revision bigint not null check (erased_binding_revision > 0),
    erased_at timestamptz not null,
    obligation_kind varchar(24) not null default 'APP_DELETION'
        check (obligation_kind in ('APP_DELETION', 'CLOSURE_EXPIRY', 'IAM_GLOBAL')),
    cause_reference uuid,
    appended_at timestamptz,
    attempts integer not null default 0 check (attempts >= 0),
    failure_class varchar(64)
);

create index ums_erasure_journal_feed_pending
    on ums_erasure_journal_feed (erased_at, feed_reference) where appended_at is null;

-- Reconciliation only replays entries beyond the last applied one, so startup cost follows new
-- erasures rather than the lifetime total. The cursor travels with the data, which is what makes
-- it correct after a restore: a pre-deletion backup also carries a pre-deletion cursor.
create table ums_erasure_reconciliation (
    singleton boolean primary key default true check (singleton),
    last_position bigint not null default 0,
    updated_at timestamptz not null default current_timestamp
);
