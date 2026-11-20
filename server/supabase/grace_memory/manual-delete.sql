-- NOT A MIGRATION (unnumbered; never applied by tooling). Manual, tombstone-safe delete of Grace memory notes.
-- Run in the Supabase SQL editor as the project owner, one query at a time.
-- 1. Sync every phone first (Advanced -> Memory -> Sync memory now) so no REMEMBER is still queued: a queued save of
--    the same note id can recreate it after the purge.
-- 2. Never touch public.user_memories (the dashboard's own notes); this does not update mcp_tokens either.
-- The memory_purge RPC cannot run here (it needs the x-grace-token header), so these replicate it for the single owner
-- in public.personal_owner. The change_seq trigger assigns the real sequence, 0 is a placeholder.
-- Queries are extracted by tests: a "-- @query N" marker line starts each one; the in-app copy must stay identical.

-- @query 1 list notes
select id, type, subject, body, status, occurred_at, valid_to, created_at
from grace_memory.memory_entries
where user_id = (select user_id from public.personal_owner where singleton is true)
order by created_at desc;

-- @query 2 purge one note and its whole supersession chain (replace <NOTE-ID> with an id from query 1)
with recursive c(id) as (
  select e.id from grace_memory.memory_entries e
  where e.id = '<NOTE-ID>'::uuid and e.user_id = (select user_id from public.personal_owner where singleton is true)
  union
  select n.id from c
  join grace_memory.memory_entries cur on cur.id = c.id
  join grace_memory.memory_entries n on n.id in (cur.supersedes, cur.superseded_by)
    and n.user_id = (select user_id from public.personal_owner where singleton is true)
), del as (
  delete from grace_memory.memory_entries where id in (select id from c) returning id
)
insert into grace_memory.purged_ids(id, user_id, change_seq)
select id, (select user_id from public.personal_owner where singleton is true), 0 from del
on conflict (id) do update set purged_at = now() where grace_memory.purged_ids.user_id = excluded.user_id;

-- @query 3 purge ALL notes of the owner
with del as (
  delete from grace_memory.memory_entries
  where user_id = (select user_id from public.personal_owner where singleton is true) returning id
)
insert into grace_memory.purged_ids(id, user_id, change_seq)
select id, (select user_id from public.personal_owner where singleton is true), 0 from del
on conflict (id) do update set purged_at = now() where grace_memory.purged_ids.user_id = excluded.user_id;

-- @query 4 list tombstones
select id, purged_at, change_seq from grace_memory.purged_ids
where user_id = (select user_id from public.personal_owner where singleton is true)
order by purged_at desc;
