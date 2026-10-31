-- Closure is a lifecycle fact, not a deletion request. The retention deadline is
-- absolute and published at closure; expiring it is a later worker's job.
alter table ums_profile_bindings
    add column closure_at timestamptz,
    add column closure_retain_until timestamptz;

alter table ums_profile_bindings add constraint ums_profile_closure_pair
    check ((closure_at is null) = (closure_retain_until is null));
alter table ums_profile_bindings add constraint ums_profile_closure_retention
    check (closure_retain_until = closure_at + interval '2160 hours');
alter table ums_profile_bindings add constraint ums_profile_closure_not_erased
    check (closure_at is null or lifecycle <> 'ERASED');

create index ums_profile_closure_expiry
    on ums_profile_bindings (issuer_environment_ref, app_reference, closure_retain_until)
    where lifecycle = 'CLOSED' and closure_at is not null;

-- Minimized, PII-free lifecycle evidence. Deliberately carries no foreign key to the
-- binding: it must survive independently so a restored backup can be reconciled
-- against acknowledged lifecycle transitions before traffic is served.
create table ums_profile_lifecycle_evidence (
    evidence_reference uuid primary key,
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    subject_reference uuid not null,
    action varchar(32) not null check (action in ('CLOSED')),
    binding_revision bigint not null check (binding_revision > 0),
    effective_at timestamptz not null,
    retain_until timestamptz,
    occurred_at timestamptz not null,
    causation_event_id uuid not null,
    correlation_id varchar(128) not null,
    unique (issuer_environment_ref, app_reference, subject_reference, action, causation_event_id)
);

create index ums_profile_lifecycle_evidence_scope
    on ums_profile_lifecycle_evidence (issuer_environment_ref, app_reference, subject_reference, occurred_at);
