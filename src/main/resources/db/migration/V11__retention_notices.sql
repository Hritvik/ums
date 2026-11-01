-- Advance notices are app-routed intents, not messages: UMS owns no contact
-- details and never picks a channel, wording, or delivery policy.
create table ums_profile_notice_intents (
    notice_reference uuid primary key,
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    subject_reference uuid not null,
    notice_class varchar(32) not null
        check (notice_class in ('CLOSURE_EXPIRY_14_DAY', 'CLOSURE_EXPIRY_7_DAY')),
    deadline timestamptz not null,
    dedupe_identity char(64) not null,
    state varchar(16) not null default 'QUEUED'
        check (state in ('QUEUED', 'HANDED_OFF', 'ACKNOWLEDGED', 'FAILED', 'SUPPRESSED')),
    failure_class varchar(64),
    queued_at timestamptz not null,
    handed_off_at timestamptz,
    acknowledged_at timestamptz,
    suppressed_at timestamptz,
    unique (issuer_environment_ref, app_reference, subject_reference, dedupe_identity)
);

create index ums_profile_notice_delivery
    on ums_profile_notice_intents (issuer_environment_ref, app_reference, subject_reference, state, deadline);
