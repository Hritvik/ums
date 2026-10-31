create index ums_profile_due_app_deletion on ums_profile_deletion_requests
    (issuer_environment_ref, app_reference, deadline, request_reference) where status = 'PENDING';
