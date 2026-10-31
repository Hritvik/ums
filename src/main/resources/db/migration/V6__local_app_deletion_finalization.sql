alter table ums_profile_deletion_requests drop constraint ums_profile_deletion_requests_status_check;
alter table ums_profile_deletion_requests add constraint ums_profile_deletion_requests_status_check
    check (status in ('PENDING', 'CANCELLED', 'LOCAL_COMPLETED'));
alter table ums_profile_privacy_audit drop constraint ums_profile_privacy_audit_action_check;
alter table ums_profile_privacy_audit add constraint ums_profile_privacy_audit_action_check
    check (action in ('REQUEST_DELETION', 'CANCEL_DELETION', 'FINALIZE_APP_DELETION'));
alter table ums_profile_bindings add column completed_deletion_request_reference uuid
    references ums_profile_deletion_requests (request_reference);

-- Minimal local replay fence, not independently durable recovery evidence or provider acknowledgement.
create table ums_profile_erasure_fences (
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    subject_reference uuid not null,
    request_reference uuid not null references ums_profile_deletion_requests,
    retired_profile_reference uuid not null,
    retired_generation bigint not null check (retired_generation > 0),
    erased_binding_revision bigint not null check (erased_binding_revision > 0),
    primary key (issuer_environment_ref, app_reference, subject_reference, request_reference),
    unique (retired_profile_reference),
    foreign key (issuer_environment_ref, app_reference, subject_reference) references ums_profile_bindings
);
