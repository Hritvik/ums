-- V21 has already run in environments that deployed it, so its shape is unchanged. Journal
-- append order is the reconciliation cursor: a database sequence rewinds on restore and would
-- let a new erasure reuse a number the cursor already passed.
alter table ums_erasure_journal_feed add column if not exists feed_sequence bigserial;
create unique index if not exists ums_erasure_journal_feed_sequence
    on ums_erasure_journal_feed (feed_sequence);

alter table ums_erasure_reconciliation add column if not exists last_position bigint not null default 0;
alter table ums_erasure_reconciliation drop column if exists last_erased_at;
alter table ums_erasure_reconciliation drop column if exists last_feed_reference;

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'ums_erasure_reconciliation_position') then
        alter table ums_erasure_reconciliation
            add constraint ums_erasure_reconciliation_position check (last_position >= 0);
    end if;
end $$;
