-- grace_memory: schema, ledger table, tombstones, change sequence + triggers. See docs/memory/SPEC-memory-ledger.md.
create schema grace_memory;

create sequence grace_memory.change_seq;

create table grace_memory.memory_entries (
  id            uuid primary key,
  user_id       uuid not null,
  type          text not null check (type in ('fact','preference','event','place','note')),
  subject       text not null check (char_length(subject) between 1 and 200),
  subject_key   text not null check (char_length(subject_key) between 1 and 200),
  body          text not null check (char_length(body) between 1 and 8000),
  occurred_at   timestamptz,
  valid_from    timestamptz not null,
  valid_to      timestamptz,
  status        text not null default 'active' check (status in ('active','superseded','invalidated')),
  supersedes    uuid references grace_memory.memory_entries(id),
  superseded_by uuid references grace_memory.memory_entries(id),
  source        text not null check (char_length(source) between 1 and 100),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  change_seq    bigint not null,
  fts           tsvector generated always as (to_tsvector('simple', subject || ' ' || body)) stored
);

create table grace_memory.purged_ids (
  id         uuid primary key,
  user_id    uuid not null,
  purged_at  timestamptz not null default now(),
  change_seq bigint not null
);

create index memory_entries_user_status_type on grace_memory.memory_entries (user_id, status, type);
create index memory_entries_fts on grace_memory.memory_entries using gin (fts);
create index memory_entries_user_subject_key on grace_memory.memory_entries (user_id, subject_key);
create index memory_entries_user_change_seq on grace_memory.memory_entries (user_id, change_seq);
create index memory_entries_supersedes on grace_memory.memory_entries (supersedes);
create index memory_entries_superseded_by on grace_memory.memory_entries (superseded_by);
create index purged_ids_user_change_seq on grace_memory.purged_ids (user_id, change_seq);

create function grace_memory._touch_entry() returns trigger language plpgsql
  set search_path = pg_catalog, grace_memory as $$
begin
  new.change_seq := nextval('grace_memory.change_seq');
  new.updated_at := clock_timestamp();
  return new;
end $$;

create function grace_memory._touch_tombstone() returns trigger language plpgsql
  set search_path = pg_catalog, grace_memory as $$
begin
  new.change_seq := nextval('grace_memory.change_seq');
  return new;
end $$;

create trigger memory_entries_touch before insert or update on grace_memory.memory_entries
  for each row execute function grace_memory._touch_entry();
create trigger purged_ids_touch before insert or update on grace_memory.purged_ids
  for each row execute function grace_memory._touch_tombstone();
