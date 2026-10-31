alter table ums_profile_bindings
    add column display_name varchar(320),
    add column avatar_reference varchar(256),
    add column display_locale varchar(64),
    add column display_timezone varchar(128);

create table ums_profile_patch_receipts (
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    subject_reference uuid not null,
    actor_reference uuid not null,
    operation varchar(32) not null check (operation = 'PROFILE_PATCH'),
    idempotency_key uuid not null,
    input_fingerprint varchar(64) not null,
    accepted_at timestamptz not null default current_timestamp,
    profile_reference uuid not null,
    applied_revision bigint not null check (applied_revision > 0),
    primary key (issuer_environment_ref, app_reference, subject_reference, actor_reference, operation, idempotency_key)
);
