-- Durable "last reported" state so a display-only edit stays silent even after the published
-- outbox row is swept, and a content debt can travel with the erasure obligation.
create table ums_profile_association_state (
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    subject_reference uuid not null,
    readiness varchar(32) not null,
    lifecycle varchar(32) not null,
    updated_at timestamptz not null default current_timestamp,
    primary key (issuer_environment_ref, app_reference, subject_reference)
);

alter table ums_profile_erasure_obligations
    add column content_obligation boolean not null default false;
