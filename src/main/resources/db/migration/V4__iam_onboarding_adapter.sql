create table ums_iam_source_bindings (
    issuer_environment_ref uuid primary key,
    environment varchar(16) not null check (environment in ('development', 'test', 'staging', 'production'))
);
create table ums_iam_app_mappings (
    issuer_environment_ref uuid not null references ums_iam_source_bindings,
    app_registration_id varchar(64) not null,
    app_reference uuid not null,
    primary key (issuer_environment_ref, app_registration_id),
    unique (issuer_environment_ref, app_reference)
);
create table ums_iam_subject_mappings (
    issuer_environment_ref uuid not null references ums_iam_source_bindings,
    app_reference uuid not null,
    app_subject varchar(132) not null,
    subject_reference uuid not null unique,
    activation_id varchar(129) not null,
    primary key (issuer_environment_ref, app_reference, app_subject),
    foreign key (issuer_environment_ref, app_reference) references ums_profile_policies
);
create table ums_iam_activation_owners (
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    activation_id varchar(129) not null,
    app_subject varchar(132) not null,
    primary key (issuer_environment_ref, app_reference, activation_id),
    foreign key (issuer_environment_ref, app_reference)
        references ums_iam_app_mappings (issuer_environment_ref, app_reference)
);
create table ums_iam_onboarding_receipts (
    issuer_environment_ref uuid not null references ums_iam_source_bindings,
    environment varchar(16) not null,
    producer varchar(8) not null check (producer = 'iam'),
    original_event_id varchar(132) not null,
    internal_event_id uuid not null unique,
    app_reference uuid not null,
    app_registration_id varchar(64) not null,
    app_subject varchar(132) not null,
    activation_id varchar(129) not null,
    activation_version bigint not null check (activation_version > 0),
    onboarding_version bigint not null check (onboarding_version > 0),
    onboarding_reason varchar(32) not null,
    input_fingerprint varchar(64) not null,
    payload_fingerprint varchar(64) not null,
    accepted_at timestamptz not null default current_timestamp,
    primary key (issuer_environment_ref, environment, producer, original_event_id)
);
create index ums_iam_onboarding_revision on ums_iam_onboarding_receipts
    (issuer_environment_ref, app_reference, activation_id, onboarding_version);
