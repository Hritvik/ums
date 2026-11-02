-- Exports are a second copy of UMS-owned personal data, so they expire, are invalidated on
-- erasure, and are reachable only through a short-lived single-use capability.
create table ums_profile_exports (
    export_reference uuid primary key,
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    subject_reference uuid not null,
    state varchar(16) not null check (state in ('READY', 'EXPIRED', 'INVALIDATED', 'FAILED')),
    requested_at timestamptz not null,
    expires_at timestamptz not null check (expires_at = requested_at + interval '24 hours'),
    invalidated_at timestamptz,
    failure_class varchar(64),
    artifact jsonb,
    check ((state = 'READY' and artifact is not null) or (state <> 'READY' and artifact is null))
);

create index ums_profile_exports_scope
    on ums_profile_exports (issuer_environment_ref, app_reference, subject_reference, requested_at);

create table ums_profile_export_receipts (
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    subject_reference uuid not null,
    actor_reference uuid not null,
    idempotency_key uuid not null,
    input_fingerprint char(64) not null,
    export_reference uuid not null references ums_profile_exports (export_reference),
    primary key (issuer_environment_ref, app_reference, subject_reference, actor_reference, idempotency_key)
);

create table ums_profile_export_capabilities (
    capability_reference uuid primary key,
    export_reference uuid not null references ums_profile_exports (export_reference),
    token_hash char(64) not null unique,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    requested_at timestamptz not null,
    check (expires_at <= requested_at + interval '5 minutes')
);

create index ums_profile_export_capability_export
    on ums_profile_export_capabilities (export_reference, expires_at);
