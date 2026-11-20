package com.galaxyrio.gracelauncher.assistant.memory

/** Copy-paste SQL shown in Advanced. Must stay identical (modulo whitespace) to `server/supabase/grace_memory/manual-delete.sql`; a JVM test enforces it. */
internal object MemorySql {
    const val LIST = """select id, type, subject, body, status, occurred_at, valid_to, created_at
from grace_memory.memory_entries
where user_id = (select user_id from public.personal_owner where singleton is true)
order by created_at desc;"""

    const val PURGE_ONE = """with recursive c(id) as (
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
on conflict (id) do update set purged_at = now() where grace_memory.purged_ids.user_id = excluded.user_id;"""

    const val PURGE_ALL = """with del as (
  delete from grace_memory.memory_entries
  where user_id = (select user_id from public.personal_owner where singleton is true) returning id
)
insert into grace_memory.purged_ids(id, user_id, change_seq)
select id, (select user_id from public.personal_owner where singleton is true), 0 from del
on conflict (id) do update set purged_at = now() where grace_memory.purged_ids.user_id = excluded.user_id;"""

    const val TOMBSTONES = """select id, purged_at, change_seq from grace_memory.purged_ids
where user_id = (select user_id from public.personal_owner where singleton is true)
order by purged_at desc;"""
}
