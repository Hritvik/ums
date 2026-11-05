-- An erasure that drops an avatar association keeps the reference so the content owner can be
-- asked to clean it up; local erasure and the provider confirmation are separate outcomes.
alter table ums_profile_erasure_obligations
    add column content_reference varchar(256),
    add column content_state varchar(16) not null default 'NONE'
        check (content_state in ('NONE', 'PENDING', 'CONFIRMED', 'FAILED')),
    add column content_attempts integer not null default 0 check (content_attempts >= 0),
    add column content_failure_class varchar(64),
    add constraint ums_profile_content_state_pair
        check ((content_reference is null) = (content_state = 'NONE'));

-- A validated sweep needs fair rotation over every association it rechecks.
alter table ums_profile_bindings add column content_checked_at timestamptz;

-- An explicit clear or a replacement drops an association without erasing the profile; the
-- reference still needs a durable, retryable cleanup request of its own.
create table ums_content_cleanup_requests (
    request_reference uuid primary key,
    issuer_environment_ref uuid not null,
    app_reference uuid not null,
    subject_reference uuid not null,
    content_reference varchar(256) not null,
    content_state varchar(16) not null default 'PENDING'
        check (content_state in ('PENDING', 'CONFIRMED', 'FAILED')),
    content_attempts integer not null default 0 check (content_attempts >= 0),
    content_failure_class varchar(64),
    requested_at timestamptz not null,
    confirmed_at timestamptz
);
create index ums_content_cleanup_requests_pending
    on ums_content_cleanup_requests (content_state, requested_at);

-- A blocked global final writes its cleanup fence before the erasure exists.
alter table ums_profile_erasure_obligations
    drop constraint ums_profile_erasure_obligations_obligation_kind_check,
    add constraint ums_profile_erasure_obligations_obligation_kind_check
        check (obligation_kind in ('APP_DELETION', 'CLOSURE_EXPIRY', 'IAM_GLOBAL', 'IAM_GLOBAL_BLOCKED'));
