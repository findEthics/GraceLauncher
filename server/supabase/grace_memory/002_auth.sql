-- Function owner role + minimal column-level read access to the dashboard auth tables.
-- BYPASSRLS: dashboard tables are FORCE RLS with no policies; privileges stay limited by the grants below.
-- Requires a role allowed to create BYPASSRLS roles (see APPLY-CHECKLIST.md).
do $$ begin
  if not exists (select from pg_roles where rolname = 'grace_memory_owner') then
    create role grace_memory_owner nologin bypassrls;
  end if;
end $$;

-- Needed so the migrating role can ALTER FUNCTION ... OWNER TO the owner role (no-op for superusers).
grant grace_memory_owner to current_user;

grant usage on schema public, extensions to grace_memory_owner;
grant select (token_hash, user_id, revoked_at) on public.mcp_tokens to grace_memory_owner;
grant select (singleton, user_id) on public.personal_owner to grace_memory_owner;
grant select (id, is_active) on public.users to grace_memory_owner;

grant usage on schema grace_memory to anon;
-- CREATE is required for non-superusers to ALTER FUNCTION ... OWNER TO; revoked at the end of 003.
grant usage, create on schema grace_memory to grace_memory_owner;
grant select, insert, update, delete on all tables in schema grace_memory to grace_memory_owner;
grant usage on all sequences in schema grace_memory to grace_memory_owner;

-- anon: schema usage only (PostgREST); explicitly strip anything default privileges may have granted.
revoke all on all tables in schema grace_memory from public, anon, authenticated;
revoke all on all sequences in schema grace_memory from public, anon, authenticated;
