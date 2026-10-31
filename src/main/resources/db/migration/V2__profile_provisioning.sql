-- App-qualified profile provisioning. No personal profile fields are accepted yet.
alter table ums_event_inbox add column input_fingerprint varchar(64);

create table ums_profile_policies (
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    policy jsonb not null check (jsonb_typeof(policy) = 'object'),
    primary key (issuer_environment_ref, app_reference)
);

create table ums_profile_bindings (
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    subject_reference uuid not null,
    profile_reference uuid unique,
    binding_revision bigint not null check (binding_revision > 0),
    policy_revision bigint not null check (policy_revision > 0),
    generation bigint check (generation > 0),
    profile_revision bigint check (profile_revision > 0),
    lifecycle varchar(32) not null check (lifecycle in ('ACTIVE', 'CLOSED', 'DELETION_PENDING', 'ERASED')),
    readiness varchar(32) not null check (readiness in ('PROFILE_REQUIRED', 'PROFILE_READY')),
    check ((lifecycle = 'ERASED' and profile_reference is null and generation is null and profile_revision is null)
        or (lifecycle <> 'ERASED' and profile_reference is not null and generation is not null and profile_revision is not null)),
    check (lifecycle = 'ACTIVE' or readiness = 'PROFILE_REQUIRED'),
    primary key (issuer_environment_ref, app_reference, subject_reference),
    foreign key (issuer_environment_ref, app_reference)
        references ums_profile_policies (issuer_environment_ref, app_reference)
);

create table ums_input_checkpoints (
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    subject_reference uuid not null,
    producer varchar(128) not null,
    stream_id varchar(256) not null,
    source_revision bigint not null check (source_revision > 0),
    primary key (issuer_environment_ref, app_reference, subject_reference, producer, stream_id),
    foreign key (issuer_environment_ref, app_reference)
        references ums_profile_policies (issuer_environment_ref, app_reference)
);
