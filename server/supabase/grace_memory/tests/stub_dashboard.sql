-- TEST ONLY: local stand-in for the dashboard schema + Supabase roles. Never applied live.
do $$ begin
  if not exists (select from pg_roles where rolname = 'anon') then create role anon nologin; end if;
  if not exists (select from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
  if not exists (select from pg_roles where rolname = 'stub_dashboard_owner') then create role stub_dashboard_owner nologin; end if;
end $$;

-- Supabase-like default privileges (broader than real: all schemas) so migrations must revoke explicitly.
alter default privileges for role postgres grant all on tables to anon, authenticated;
alter default privileges for role postgres grant all on sequences to anon, authenticated;
alter default privileges for role postgres grant all on functions to anon, authenticated;

create schema extensions;
create extension pgcrypto schema extensions;
grant usage on schema extensions to public;

grant usage, create on schema public to stub_dashboard_owner;

set role stub_dashboard_owner;

create table public.users (
  id uuid primary key default gen_random_uuid(),
  email text,
  is_active boolean,
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);
create table public.personal_owner (
  singleton boolean primary key default true check (singleton),
  user_id uuid not null unique references public.users(id),
  created_at timestamptz not null default now()
);
create table public.mcp_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.users(id),
  token_hash text not null unique,
  label text not null default 'unnamed-client',
  created_at timestamptz not null default now(),
  last_used_at timestamptz null,
  revoked_at timestamptz null
);
alter table public.users enable row level security;
alter table public.users force row level security;
alter table public.personal_owner enable row level security;
alter table public.personal_owner force row level security;
alter table public.mcp_tokens enable row level security;
alter table public.mcp_tokens force row level security;

reset role;
