-- Rework after independent review (ums/W-3, ums/W-10).

-- 1. A request can be superseded when another obligation erases first.
alter table ums_profile_deletion_requests drop constraint ums_profile_deletion_requests_status_check;
alter table ums_profile_deletion_requests add constraint ums_profile_deletion_requests_status_check
    check (status in ('PENDING', 'CANCELLED', 'LOCAL_COMPLETED', 'SUPERSEDED'));

-- 2. One erasure fence for every obligation, with the causes it superseded.
create table ums_profile_erasure_obligations (
    fence_reference uuid primary key,
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    subject_reference uuid not null,
    obligation_kind varchar(24) not null
        check (obligation_kind in ('APP_DELETION', 'CLOSURE_EXPIRY', 'IAM_GLOBAL')),
    cause_reference uuid,
    retired_profile_reference uuid not null,
    retired_generation bigint not null check (retired_generation > 0),
    obligation_at timestamptz not null,
    superseded_causes jsonb not null default '[]'::jsonb
        check (jsonb_typeof(superseded_causes) = 'array'),
    erased_binding_revision bigint not null check (erased_binding_revision > 0),
    erased_at timestamptz not null,
    unique (retired_profile_reference)
);

insert into ums_profile_erasure_obligations (fence_reference, issuer_environment_ref, app_reference,
    subject_reference, obligation_kind, cause_reference, retired_profile_reference, retired_generation,
    obligation_at, superseded_causes, erased_binding_revision, erased_at)
select gen_random_uuid(), f.issuer_environment_ref, f.app_reference, f.subject_reference, 'APP_DELETION',
    f.request_reference, f.retired_profile_reference, f.retired_generation,
    coalesce(r.accepted_at, current_timestamp), '[]'::jsonb, f.erased_binding_revision,
    coalesce((select min(a.occurred_at) from ums_profile_privacy_audit a
              where a.operation_reference = f.request_reference and a.action = 'FINALIZE_APP_DELETION'),
             r.accepted_at, current_timestamp)
from ums_profile_erasure_fences f
left join ums_profile_deletion_requests r on r.request_reference = f.request_reference;

insert into ums_profile_erasure_obligations (fence_reference, issuer_environment_ref, app_reference,
    subject_reference, obligation_kind, cause_reference, retired_profile_reference, retired_generation,
    obligation_at, superseded_causes, erased_binding_revision, erased_at)
select gen_random_uuid(), e.issuer_environment_ref, e.app_reference, e.subject_reference, 'CLOSURE_EXPIRY',
    null, e.retired_profile_reference, e.retired_generation, e.closure_at, '[]'::jsonb,
    e.erased_binding_revision, e.erased_at
from ums_profile_expiry_fences e;

drop table ums_profile_erasure_fences;
drop table ums_profile_expiry_fences;

create index ums_profile_erasure_obligation_scope
    on ums_profile_erasure_obligations (issuer_environment_ref, app_reference, subject_reference, erased_at);

-- 3. A closure instance has its own identity, so a profile closed again after
-- reactivation gets fresh notices instead of colliding with suppressed ones.
alter table ums_profile_bindings add column closure_reference uuid;
alter table ums_profile_bindings add column notice_stream_revision bigint not null default 0;
update ums_profile_bindings set closure_reference = gen_random_uuid() where closure_at is not null;

-- The due scan must cover both retained lifecycles.
drop index if exists ums_profile_closure_expiry;
create index ums_profile_closure_expiry
    on ums_profile_bindings (issuer_environment_ref, app_reference, closure_retain_until)
    where lifecycle in ('CLOSED', 'DELETION_PENDING') and closure_at is not null;

-- 4. Notice delivery needs an operation revision, a failure timestamp, a late
-- acknowledgement flag, and a terminal state for a window that was observed late.
alter table ums_profile_notice_intents add column notice_revision bigint not null default 1;
alter table ums_profile_notice_intents add column closure_reference uuid;
alter table ums_profile_notice_intents add column failed_at timestamptz;
alter table ums_profile_notice_intents add column window_passed_at timestamptz;
alter table ums_profile_notice_intents
    add column delivered_after_suppression boolean not null default false;

alter table ums_profile_notice_intents drop constraint ums_profile_notice_intents_state_check;
alter table ums_profile_notice_intents add constraint ums_profile_notice_intents_state_check
    check (state in ('QUEUED', 'HANDED_OFF', 'ACKNOWLEDGED', 'FAILED', 'SUPPRESSED', 'WINDOW_PASSED'));

-- 5. Existing V11-era intents inherit their binding's closure instance so the
-- nullable column is never read into a non-null field.
update ums_profile_notice_intents n set closure_reference = b.closure_reference
from ums_profile_bindings b
where n.issuer_environment_ref = b.issuer_environment_ref and n.app_reference = b.app_reference
  and n.subject_reference = b.subject_reference and n.closure_reference is null;
