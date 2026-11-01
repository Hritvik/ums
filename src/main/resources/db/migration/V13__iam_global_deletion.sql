-- IAM-global deletion is an independent obligation from app-only deletion. Its source
-- revision is authoritative IAM ordering and never resets on replay.
alter table ums_profile_bindings add column global_source_revision bigint not null default 0
    check (global_source_revision >= 0);

alter table ums_profile_lifecycle_evidence
    drop constraint ums_profile_lifecycle_evidence_action_check;
alter table ums_profile_lifecycle_evidence
    add constraint ums_profile_lifecycle_evidence_action_check
    check (action in ('CLOSED', 'REACTIVATED', 'EXPIRED', 'GLOBAL_ERASED', 'GLOBAL_FINAL_BLOCKED'));
