-- Snapshot repair is an auditable reconciliation, and quarantine classes distinguish an
-- authorization rejection from a poison payload.
alter table ums_profile_lifecycle_evidence
    drop constraint ums_profile_lifecycle_evidence_action_check;
alter table ums_profile_lifecycle_evidence
    add constraint ums_profile_lifecycle_evidence_action_check
    check (action in ('CLOSED', 'REACTIVATED', 'EXPIRED', 'GLOBAL_ERASED', 'GLOBAL_FINAL_BLOCKED',
                      'SNAPSHOT_RECONCILED'));

alter table ums_iam_ingestion_quarantine
    drop constraint ums_iam_ingestion_quarantine_failure_class_check;
alter table ums_iam_ingestion_quarantine
    add constraint ums_iam_ingestion_quarantine_failure_class_check
    check (failure_class in ('PRODUCER_MISMATCH', 'PAYLOAD_TOO_LARGE', 'INVALID_ENCODING',
                             'SCHEMA_INVALID', 'APPLICATION_NOT_REGISTERED', 'APPLICATION_BINDING_MISMATCH'));
