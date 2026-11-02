-- Each retention sweep selects the oldest overdue rows by one timestamp. Without these indexes
-- every class scans its table once per pass.
create index ums_profile_export_capabilities_expiry on ums_profile_export_capabilities (expires_at);
create index ums_profile_exports_requested on ums_profile_exports (requested_at);
create index ums_profile_export_receipts_export on ums_profile_export_receipts (export_reference);
create index ums_profile_patch_receipts_accepted on ums_profile_patch_receipts (accepted_at);
create index ums_profile_privacy_receipts_accepted on ums_profile_privacy_receipts (accepted_at);
create index ums_event_outbox_published on ums_event_outbox (published_at)
    where published_at is not null;
create index ums_event_inbox_processed on ums_event_inbox (processed_at);
create index ums_profile_privacy_audit_occurred on ums_profile_privacy_audit (occurred_at);
create index ums_profile_lifecycle_evidence_occurred on ums_profile_lifecycle_evidence (occurred_at);
create index ums_iam_onboarding_receipts_accepted on ums_iam_onboarding_receipts (accepted_at);
