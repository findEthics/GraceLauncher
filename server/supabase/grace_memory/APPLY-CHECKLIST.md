# grace_memory apply checklist — REQUIRES USER APPROVAL (Gate A)

Nothing here has been run against the live Supabase project. Do not run any step without explicit approval.
Tokens/anon keys/project refs: never write them into files or logs.

## 1. Pre-checks (stop and ask the user on any failure; do not fall back)
1. Role can create a BYPASSRLS role: `select rolbypassrls from pg_roles where rolname = current_user;` must be `true`. If not → STOP, ask the user.
2. pgcrypto lives in `extensions`: `select extnamespace::regnamespace from pg_extension where extname='pgcrypto';` → `extensions`. Else STOP.
3. Dashboard objects exist with expected columns: `public.mcp_tokens(token_hash,user_id,revoked_at)`, `public.personal_owner(singleton,user_id)`, `public.users(id,is_active)`.
4. No existing schema/role named `grace_memory` / `grace_memory_owner`.
5. Hash compat (already verified read-only): `token_hash` = lowercase hex SHA-256 of the trimmed full token incl. `hc_`.

## 2. Apply (single transaction per file, in order)
`001_schema.sql` → `002_auth.sql` → `003_functions.sql`  (`psql -v ON_ERROR_STOP=1 -1 -f <file>`)

## 3. Expose schema
Dashboard → Settings → API → Exposed schemas: **add** `grace_memory`, keep the existing ones. Clients send `Content-Profile`/`Accept-Profile: grace_memory`, `apikey: <anon>`, `x-grace-token: <hc_…>`.

## 4. Post-apply verification (all must match)
```sql
select has_table_privilege('anon','grace_memory.memory_entries','select');            -- f
select has_table_privilege('anon','grace_memory.purged_ids','select');                -- f
select has_sequence_privilege('anon','grace_memory.change_seq','usage');              -- f
select has_function_privilege('anon','grace_memory.memory_recall(text,int)','execute');-- t
select has_function_privilege('anon','grace_memory._caller_user_id()','execute');     -- f
select has_function_privilege('anon','grace_memory._subject_key(text)','execute');     -- f (internal helper)
select p.oid::regprocedure from pg_proc p join pg_namespace n on n.oid=p.pronamespace
 where n.nspname='grace_memory' and has_function_privilege('anon',p.oid,'execute');   -- exactly the 7 RPCs
select rolcanlogin, rolbypassrls from pg_roles where rolname='grace_memory_owner';    -- f, t
-- owner column grants on dashboard tables (expected rows: exactly the 7 below)
select c.relname, a.attname from pg_class c join pg_namespace n on n.oid=c.relnamespace
  join pg_attribute a on a.attrelid=c.oid
 where n.nspname='public' and c.relname in ('mcp_tokens','personal_owner','users') and a.attnum>0
   and not a.attisdropped and has_column_privilege('grace_memory_owner',c.oid,a.attnum,'select');
 -- mcp_tokens: token_hash,user_id,revoked_at | personal_owner: singleton,user_id | users: id,is_active
select count(*) from pg_policies where schemaname='public';                            -- unchanged vs before apply (no policies added)
select has_schema_privilege('grace_memory_owner','grace_memory','create');             -- f
```
Then one live RPC smoke with a real token (user-run): `memory_export` returns `[]`; bad token → `invalid_token`; confirm `mcp_tokens.last_used_at` unchanged.

Manual note deletion in the SQL editor (tombstone-safe, not a migration): `manual-delete.sql`.

## 5. Rollback
1. Dashboard → Exposed schemas: remove `grace_memory`.
2. `drop schema grace_memory cascade;`
3. `revoke select (token_hash,user_id,revoked_at) on public.mcp_tokens from grace_memory_owner;`
   `revoke select (singleton,user_id) on public.personal_owner from grace_memory_owner;`
   `revoke select (id,is_active) on public.users from grace_memory_owner;`
   `revoke usage on schema public, extensions from grace_memory_owner;`
4. `drop role grace_memory_owner;` (also `revoke grace_memory_owner from current_user` first if the drop complains).
Note: rollback deletes all memory data; export (`memory_export`) first if wanted.
