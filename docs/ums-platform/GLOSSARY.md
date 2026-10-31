# User Management Service

UMS manages app-isolated user profiles and their readiness and lifecycle for products that adopt it.

## Language

**App Profile**:
A person's presentation and profile state within one application, distinct from their authentication identity and the application's business records.
_Avoid_: Global user, IAM account, Member

**Profile Reference**:
A stable opaque reference to an App Profile within its application's boundary.
_Avoid_: Global user ID, Human Identity ID, Member ID

**App Profile Policy**:
An application's declared requirements for supported profile fields and completion.
_Avoid_: Authentication policy, product authorization, business onboarding

**Profile Readiness**:
Whether an App Profile exists and satisfies the application's declared profile requirements.
_Avoid_: Logged in, activated, authorized

**Display Preference**:
An app-specific presentation choice such as locale or timezone, distinct from product rules governing financial periods, schedules, or household days.
_Avoid_: Global preference, accounting timezone, current location

**Profile Closure**:
The end of ordinary use of an App Profile while its retained data remains subject to an explicit retention policy.
_Avoid_: Erasure, logout, global identity deletion

**Profile Deletion Request**:
An explicit app-scoped request to stop ordinary profile use and erase its personal data after a cancellable 30-day period.
_Avoid_: App departure, global deletion, reset profile

**Erased Profile**:
A profile whose personal data has been irreversibly removed or anonymized, leaving only purpose-limited evidence needed to prevent resurrection and meet declared retention rules.
_Avoid_: Recoverable profile, inactive account
