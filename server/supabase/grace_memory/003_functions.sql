-- skeleton (slice 1)
-- Token comes only from the PostgREST header; hash expression verified against the dashboard (sha256 hex of btrim'd token).
-- Every failure path raises the same bare 'invalid_token'.
-- subject_key normalizer. MUST stay byte-identical to Kotlin `subjectKey` (assistant/memory/MemoryTypes.kt); both are
-- checked against app/src/test/resources/memory/subject_key_vectors.json. Explicit table, no unaccent/extension.
-- Order: fold ß/ẞ→ss, fold listed letters (upper+lower; İ→i, Æ→æ, Œ→œ, Þ→þ), lower the rest, collapse [ \t\n\v\f\r]+, trim spaces.
-- Not folded: non-listed scripts are only lower()'d (locale-dependent in PG; Supabase is en_US.UTF-8); NBSP is not whitespace.
create function grace_memory._subject_key(p text) returns text language sql immutable strict
  set search_path = pg_catalog as $$
  select btrim(regexp_replace(lower(translate(replace(replace(p, 'ß', 'ss'), 'ẞ', 'ss'),
    'àáâãäåāăąçćĉċčðďđèéêëēĕėęěĝğġģĥħìíîïĩīĭįıĵķĺļľŀłñńņňòóôõöøōŏőŕŗřśŝşšșţťŧțùúûüũūŭůűųŵýÿŷźżžÀÁÂÃÄÅĀĂĄÇĆĈĊČÐĎĐÈÉÊËĒĔĖĘĚĜĞĠĢĤĦÌÍÎÏĨĪĬĮĴĶĹĻĽĿŁÑŃŅŇÒÓÔÕÖØŌŎŐŔŖŘŚŜŞŠȘŢŤŦȚÙÚÛÜŨŪŬŮŰŲŴÝŸŶŹŻŽİÆŒÞ',
    'aaaaaaaaacccccdddeeeeeeeeegggghhiiiiiiiiijklllllnnnnooooooooorrrsssssttttuuuuuuuuuuwyyyzzzaaaaaaaaacccccdddeeeeeeeeegggghhiiiiiiiijklllllnnnnooooooooorrrsssssttttuuuuuuuuuuwyyyzzziæœþ')),
    '[ \t\n\v\f\r]+', ' ', 'g'), ' ')
$$;

create function grace_memory._caller_user_id() returns uuid language plpgsql stable security definer
  set search_path = pg_catalog, grace_memory, extensions as $$
declare
  v_tok text;
  v_uid uuid;
begin
  begin
    v_tok := current_setting('request.headers', true)::json ->> 'x-grace-token';
  exception when others then
    v_tok := null;
  end;
  if v_tok is not null and btrim(v_tok) <> '' then
    select t.user_id into v_uid
      from public.mcp_tokens t
      join public.personal_owner p on p.user_id = t.user_id and p.singleton is true
      join public.users u on u.id = p.user_id and u.is_active is true
     where t.token_hash = encode(extensions.digest(convert_to(btrim(v_tok), 'UTF8'), 'sha256'), 'hex')
       and t.revoked_at is null;
  end if;
  if v_uid is null then
    raise exception 'invalid_token';
  end if;
  return v_uid;
end $$;
-- All RPCs resolve the user from the token first, so any auth failure wins over argument errors.
create function grace_memory.memory_remember(p_entry jsonb, p_supersede_id uuid default null) returns jsonb
  language plpgsql security definer set search_path = pg_catalog, grace_memory, extensions as $$
declare
  v_uid uuid := grace_memory._caller_user_id();
  v_id uuid; v_type text; v_subject text; v_key text; v_body text; v_source text;
  v_occurred timestamptz; v_from timestamptz;
  v_target grace_memory.memory_entries;
  v_head uuid; v_n int;
begin
  begin
    if jsonb_typeof(p_entry) is distinct from 'object' then raise exception 'x'; end if;
    v_id := (p_entry ->> 'id')::uuid;
    v_type := p_entry ->> 'type';
    v_subject := btrim(p_entry ->> 'subject');
    v_key := grace_memory._subject_key(v_subject);
    v_body := p_entry ->> 'body';
    v_source := btrim(p_entry ->> 'source');
    v_occurred := (p_entry ->> 'occurred_at')::timestamptz;
    v_from := coalesce((p_entry ->> 'valid_from')::timestamptz, now());
    if v_id is null
       or v_type is null or v_type not in ('fact','preference','event','place','note')
       or coalesce(v_subject, '') = '' or char_length(v_subject) > 200
       or char_length(coalesce(v_key, '')) not between 1 and 200
       or coalesce(btrim(v_body), '') = '' or char_length(v_body) > 8000
       or coalesce(v_source, '') = '' or char_length(v_source) > 100
    then raise exception 'x'; end if;
  exception when others then
    raise exception 'bad_entry';
  end;
  -- a row can never supersede itself (would create a chain cycle)
  if p_supersede_id = v_id then raise exception 'bad_entry'; end if;
  -- ids are globally unique: never reuse an id another user has purged (their tombstone must stay theirs)
  if exists (select 1 from grace_memory.purged_ids where id = v_id and user_id <> v_uid) then
    raise exception 'bad_entry';
  end if;

  insert into grace_memory.memory_entries(id, user_id, type, subject, subject_key, body, occurred_at, valid_from, source)
  values (v_id, v_uid, v_type, v_subject, v_key, v_body, v_occurred, v_from, v_source)
  on conflict (id) do nothing;
  get diagnostics v_n = row_count;

  if v_n = 0 then
    -- same id again: idempotent only for the owner's own row; never touch another user's id
    if not exists (select 1 from grace_memory.memory_entries where id = v_id and user_id = v_uid) then
      raise exception 'bad_entry';
    end if;
    return jsonb_build_object('conflict', false, 'id', v_id);
  end if;

  if p_supersede_id is null then
    return jsonb_build_object('conflict', false, 'id', v_id);
  end if;

  select * into v_target from grace_memory.memory_entries
   where id = p_supersede_id and user_id = v_uid for update;
  if found and v_target.status = 'active' then
    update grace_memory.memory_entries
       set valid_to = v_from, status = 'superseded', superseded_by = v_id
     where id = v_target.id;
    update grace_memory.memory_entries set supersedes = v_target.id where id = v_id;
    return jsonb_build_object('conflict', false, 'id', v_id);
  end if;

  -- target gone/not active: keep the new entry active, close nothing, point at the current head if any
  if found then
    -- follow superseded_by forward to the current active head (null if the chain ends invalidated)
    -- plain UNION (not ALL): a revisited row is dropped, so even a pre-existing cycle terminates
    with recursive f(id, nxt, st) as (
      select e.id, e.superseded_by, e.status from grace_memory.memory_entries e where e.id = v_target.superseded_by and e.user_id = v_uid
      union
      select e.id, e.superseded_by, e.status from f join grace_memory.memory_entries e on e.id = f.nxt and e.user_id = v_uid
       where f.st <> 'active'
    )
    select id into v_head from f where st = 'active' limit 1;
  end if;
  return jsonb_build_object('conflict', true, 'head_id', v_head, 'id', v_id);
end $$;

create function grace_memory.memory_forget(p_id uuid) returns jsonb
  language plpgsql security definer set search_path = pg_catalog, grace_memory, extensions as $$
declare
  v_uid uuid := grace_memory._caller_user_id();
  v_n int;
begin
  update grace_memory.memory_entries set status = 'invalidated', valid_to = now()
   where id = p_id and user_id = v_uid and status = 'active';
  get diagnostics v_n = row_count;
  return jsonb_build_object('forgotten', v_n = 1);
end $$;

create function grace_memory.memory_purge(p_id uuid, p_confirm text) returns jsonb
  language plpgsql security definer set search_path = pg_catalog, grace_memory, extensions as $$
declare
  v_uid uuid := grace_memory._caller_user_id();
  v_n int;
begin
  if p_confirm is distinct from 'PURGE' then raise exception 'confirm_required'; end if;
  -- whole chain, both directions, one DELETE: self-referencing FKs are checked at end of statement
  -- (UNION, not UNION ALL, dedups ids so a cycle terminates)
  with recursive c(id) as (
    select e.id from grace_memory.memory_entries e where e.id = p_id and e.user_id = v_uid
    union
    select n.id from c
      join grace_memory.memory_entries cur on cur.id = c.id
      join grace_memory.memory_entries n on n.id in (cur.supersedes, cur.superseded_by) and n.user_id = v_uid
  ), del as (
    delete from grace_memory.memory_entries where id in (select id from c) returning id
  ), ins as (
    insert into grace_memory.purged_ids(id, user_id, change_seq)
    select id, v_uid, 0 from del
    -- WHERE guard: never modify another user's tombstone (remember already refuses such ids; defence in depth)
    on conflict (id) do update set purged_at = now() where grace_memory.purged_ids.user_id = excluded.user_id
    returning 1
  )
  select count(*) into v_n from del;  -- rows deleted, whether or not a tombstone was written
  return jsonb_build_object('purged', v_n);
end $$;

create function grace_memory.memory_recall(p_query text, p_limit int default 10) returns jsonb
  language plpgsql security definer set search_path = pg_catalog, grace_memory, extensions as $$
declare
  v_uid uuid := grace_memory._caller_user_id();
  v_limit int := least(greatest(coalesce(p_limit, 10), 1), 50);
  v_q tsquery;
  v_res jsonb;
begin
  if btrim(coalesce(p_query, '')) <> '' then
    v_q := websearch_to_tsquery('simple', p_query);
  end if;
  -- conflict = another active head shares this (type, subject_key) for single-valued types
  select coalesce(jsonb_agg(j order by rnk desc, vf desc), '[]'::jsonb) into v_res from (
    select to_jsonb(e) - 'fts' - 'user_id'
             || jsonb_build_object('conflict', e.type in ('fact','preference','place') and (
                  select count(*) from grace_memory.memory_entries o
                   where o.user_id = e.user_id and o.status = 'active' and o.type = e.type
                     and o.subject_key = e.subject_key) > 1) as j,
           case when v_q is null then 0 else ts_rank(e.fts, v_q) end as rnk,
           e.valid_from as vf
      from grace_memory.memory_entries e
     where e.user_id = v_uid and e.status = 'active' and (v_q is null or e.fts @@ v_q)
     order by rnk desc, e.valid_from desc
     limit v_limit
  ) s;
  return v_res;
end $$;

create function grace_memory.memory_history(p_id uuid) returns jsonb
  language plpgsql security definer set search_path = pg_catalog, grace_memory, extensions as $$
declare
  v_uid uuid := grace_memory._caller_user_id();
begin
  return (
    -- UNION (not ALL) dedups ids so a cycle terminates
    with recursive c(id) as (
      select e.id from grace_memory.memory_entries e where e.id = p_id and e.user_id = v_uid
      union
      select n.id from c
        join grace_memory.memory_entries cur on cur.id = c.id
        join grace_memory.memory_entries n on n.id in (cur.supersedes, cur.superseded_by) and n.user_id = v_uid
    )
    select coalesce(jsonb_agg(to_jsonb(e) - 'fts' - 'user_id' order by e.valid_from, e.created_at), '[]'::jsonb)
      from grace_memory.memory_entries e where e.id in (select id from c)
  );
end $$;

create function grace_memory.memory_changes(p_since_seq bigint default 0) returns jsonb
  language plpgsql security definer set search_path = pg_catalog, grace_memory, extensions as $$
declare
  v_uid uuid := grace_memory._caller_user_id();
  v_since bigint := coalesce(p_since_seq, 0);
  v_entries jsonb; v_purged jsonb; v_max bigint;
begin
  select coalesce(jsonb_agg(to_jsonb(e) - 'fts' - 'user_id' order by e.change_seq), '[]'::jsonb), max(e.change_seq)
    into v_entries, v_max
    from grace_memory.memory_entries e where e.user_id = v_uid and e.change_seq > v_since;
  select coalesce(jsonb_agg(jsonb_build_object('id', p.id, 'purged_at', p.purged_at, 'change_seq', p.change_seq)
                            order by p.change_seq), '[]'::jsonb), greatest(v_max, max(p.change_seq))
    into v_purged, v_max
    from grace_memory.purged_ids p where p.user_id = v_uid and p.change_seq > v_since;
  return jsonb_build_object('entries', v_entries, 'purged', v_purged, 'max_seq', coalesce(v_max, v_since));
end $$;

create function grace_memory.memory_export() returns jsonb
  language plpgsql security definer set search_path = pg_catalog, grace_memory, extensions as $$
declare
  v_uid uuid := grace_memory._caller_user_id();
begin
  return (select coalesce(jsonb_agg(to_jsonb(e) - 'fts' - 'user_id' order by e.valid_from, e.created_at), '[]'::jsonb)
            from grace_memory.memory_entries e where e.user_id = v_uid);
end $$;

alter function grace_memory._subject_key(text) owner to grace_memory_owner;
alter function grace_memory._caller_user_id() owner to grace_memory_owner;
alter function grace_memory.memory_remember(jsonb, uuid) owner to grace_memory_owner;
alter function grace_memory.memory_forget(uuid) owner to grace_memory_owner;
alter function grace_memory.memory_purge(uuid, text) owner to grace_memory_owner;
alter function grace_memory.memory_recall(text, int) owner to grace_memory_owner;
alter function grace_memory.memory_history(uuid) owner to grace_memory_owner;
alter function grace_memory.memory_changes(bigint) owner to grace_memory_owner;
alter function grace_memory.memory_export() owner to grace_memory_owner;

revoke all on all functions in schema grace_memory from public, anon, authenticated;
grant execute on function
  grace_memory.memory_remember(jsonb, uuid), grace_memory.memory_forget(uuid),
  grace_memory.memory_purge(uuid, text), grace_memory.memory_recall(text, int),
  grace_memory.memory_history(uuid), grace_memory.memory_changes(bigint), grace_memory.memory_export()
  to anon;

revoke create on schema grace_memory from grace_memory_owner;
