alter table ums_profile_lifecycle_evidence
    drop constraint ums_profile_lifecycle_evidence_action_check;
alter table ums_profile_lifecycle_evidence
    add constraint ums_profile_lifecycle_evidence_action_check
    check (action in ('CLOSED', 'REACTIVATED'));
