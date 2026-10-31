alter table ums_profile_bindings add column closure_present boolean not null default false;
update ums_profile_bindings set closure_present = true where lifecycle = 'CLOSED';

create table ums_profile_deletion_causes (
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    subject_reference uuid not null,
    cause_type varchar(16) not null check (cause_type in ('APP_ONLY', 'IAM_GLOBAL')),
    request_reference uuid not null,
    source_revision bigint not null check (source_revision > 0),
    accepted_at timestamptz not null,
    deadline timestamptz not null check (deadline > accepted_at),
    primary key (issuer_environment_ref, app_reference, subject_reference, cause_type),
    foreign key (issuer_environment_ref, app_reference, subject_reference) references ums_profile_bindings
);
create table ums_profile_deletion_requests (
    request_reference uuid primary key,
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    subject_reference uuid not null,
    profile_reference uuid not null,
    generation bigint not null check (generation > 0),
    operation_revision bigint not null check (operation_revision > 0),
    status varchar(16) not null check (status in ('PENDING', 'CANCELLED')),
    accepted_at timestamptz not null,
    deadline timestamptz not null check (deadline = accepted_at + interval '720 hours'),
    foreign key (issuer_environment_ref, app_reference, subject_reference) references ums_profile_bindings
);
create unique index ums_profile_pending_deletion on ums_profile_deletion_requests
    (issuer_environment_ref, app_reference, subject_reference) where status = 'PENDING';
create table ums_profile_privacy_receipts (
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    subject_reference uuid not null,
    actor_reference uuid not null,
    operation varchar(32) not null check (operation in ('REQUEST_DELETION', 'CANCEL_DELETION')),
    idempotency_key uuid not null,
    input_fingerprint varchar(64) not null,
    operation_reference uuid not null references ums_profile_deletion_requests,
    operation_revision bigint not null,
    profile_reference uuid not null,
    generation bigint not null,
    accepted_at timestamptz not null,
    primary key (issuer_environment_ref, app_reference, subject_reference, actor_reference, operation, idempotency_key)
);
create table ums_profile_privacy_audit (
    audit_reference uuid primary key,
    operation_reference uuid not null references ums_profile_deletion_requests,
    actor_reference uuid not null,
    action varchar(32) not null check (action in ('REQUEST_DELETION', 'CANCEL_DELETION')),
    binding_revision bigint not null check (binding_revision > 0),
    occurred_at timestamptz not null,
    correlation_id varchar(128) not null
);
