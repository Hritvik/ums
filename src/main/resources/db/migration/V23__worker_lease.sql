-- One active worker per named job: a second instance sees the lease held and skips the pass.
create table ums_worker_lease (
    worker varchar(64) primary key,
    holder uuid not null,
    leased_until timestamptz not null
);
