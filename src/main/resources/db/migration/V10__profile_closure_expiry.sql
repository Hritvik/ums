-- Closure expiry executes an already published retention obligation. It is not a new
-- deletion request, so it records its own fence rather than borrowing a request row.
alter table ums_profile_lifecycle_evidence
    drop constraint ums_profile_lifecycle_evidence_action_check;
alter table ums_profile_lifecycle_evidence
    add constraint ums_profile_lifecycle_evidence_action_check
    check (action in ('CLOSED', 'REACTIVATED', 'EXPIRED'));

-- Minimal purpose-limited replay fence for expired generations. Deliberately has no
-- foreign key to the binding: like the lifecycle evidence, it must survive independently
-- so a restored backup can be reconciled before serving reads.
create table ums_profile_expiry_fences (
    fence_reference uuid primary key,
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    subject_reference uuid not null,
    retired_profile_reference uuid not null,
    retired_generation bigint not null check (retired_generation > 0),
    closure_at timestamptz not null,
    erased_binding_revision bigint not null check (erased_binding_revision > 0),
    erased_at timestamptz not null,
    unique (retired_profile_reference)
);

create index ums_profile_expiry_fence_scope
    on ums_profile_expiry_fences (issuer_environment_ref, app_reference, subject_reference, erased_at);
