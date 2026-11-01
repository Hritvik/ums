-- Poisoned or non-authentic inbound IAM payloads are quarantined with a safe class and a
-- digest only. The body is never stored: a quarantine is not a personal-data archive.
create table ums_iam_ingestion_quarantine (
    quarantine_reference uuid primary key,
    producer varchar(64) not null,
    application_id varchar(64) not null,
    payload_digest char(64) not null,
    failure_class varchar(64) not null
        check (failure_class in ('PRODUCER_MISMATCH', 'PAYLOAD_TOO_LARGE', 'INVALID_ENCODING', 'SCHEMA_INVALID')),
    correlation_id varchar(128) not null,
    state varchar(16) not null default 'OPEN' check (state in ('OPEN', 'RESOLVED')),
    received_at timestamptz not null,
    resolved_at timestamptz
);

create index ums_iam_ingestion_open
    on ums_iam_ingestion_quarantine (received_at) where state = 'OPEN';
