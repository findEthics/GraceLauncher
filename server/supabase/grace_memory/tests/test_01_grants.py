import pytest

RPCS = [
    "memory_remember(jsonb,uuid)",
    "memory_forget(uuid)",
    "memory_purge(uuid,text)",
    "memory_recall(text,integer)",
    "memory_history(uuid)",
    "memory_changes(bigint)",
    "memory_export()",
]


def q(db, sql, *a):
    return db.execute(sql, a or None).fetchall()


def test_anon_has_no_table_or_sequence_privileges(db):
    rows = q(
        db,
        """select c.relname, p from pg_class c join pg_namespace n on n.oid=c.relnamespace,
        unnest(array['select','insert','update','delete','truncate','references','trigger']) p
        where n.nspname='grace_memory' and c.relkind in ('r','p','v','m')
          and (has_table_privilege('anon', c.oid, p) or has_table_privilege('authenticated', c.oid, p))""",
    )
    assert rows == []
    rows = q(
        db,
        """select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
        where n.nspname='grace_memory' and c.relkind='S'
          and (has_sequence_privilege('anon', c.oid, 'usage') or has_sequence_privilege('anon', c.oid, 'select')
               or has_sequence_privilege('anon', c.oid, 'update'))""",
    )
    assert rows == []


def test_anon_can_use_schema(db):
    assert db.execute(
        "select has_schema_privilege('anon','grace_memory','usage')"
    ).fetchone()[0]


def test_anon_executes_exactly_the_seven_rpcs(db):
    got = {
        r[0]
        for r in q(
            db,
            """select p.oid::regprocedure::text from pg_proc p join pg_namespace n on n.oid=p.pronamespace
        where n.nspname='grace_memory' and has_function_privilege('anon', p.oid, 'execute')""",
        )
    }
    assert got == {f"grace_memory.{r}" for r in RPCS}


def test_anon_cannot_execute_caller_helper(db):
    assert not db.execute(
        "select has_function_privilege('anon','grace_memory._caller_user_id()','execute')"
    ).fetchone()[0]


def test_anon_cannot_execute_subject_key_helper(db):
    assert not db.execute(
        "select has_function_privilege('anon','grace_memory._subject_key(text)','execute')"
    ).fetchone()[0]
    assert not db.execute(
        "select has_function_privilege('authenticated','grace_memory._subject_key(text)','execute')"
    ).fetchone()[0]
    assert db.execute(  # the definer functions run as the owner, which must keep EXECUTE
        "select has_function_privilege('grace_memory_owner','grace_memory._subject_key(text)','execute')"
    ).fetchone()[0]


def test_public_has_no_execute_on_any_function(db):
    rows = q(
        db,
        """select p.oid::regprocedure::text from pg_proc p join pg_namespace n on n.oid=p.pronamespace,
        aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
        where n.nspname='grace_memory' and a.grantee=0 and a.privilege_type='EXECUTE'""",
    )
    assert rows == []


def test_functions_are_security_definer_with_fixed_search_path_owned_by_owner(db):
    rows = q(
        db,
        """select p.proname, p.prosecdef, p.proconfig, pg_get_userbyid(p.proowner)
        from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='grace_memory'
        and p.proname like any (array['memory\\_%','\\_caller%'])""",
    )
    assert len(rows) == 8
    for name, secdef, cfg, owner in rows:
        assert secdef, name
        assert cfg and any(c.startswith("search_path=") for c in cfg), name
        assert owner == "grace_memory_owner", name


def test_no_function_has_a_token_parameter(db):
    rows = q(
        db,
        """select p.proname, p.proargnames from pg_proc p join pg_namespace n on n.oid=p.pronamespace
        where n.nspname='grace_memory' and p.proargnames is not null""",
    )
    assert rows
    for name, args in rows:
        assert not any("token" in a.lower() for a in args), name


def test_owner_reads_only_listed_dashboard_columns(db):
    allowed = {
        ("mcp_tokens", "token_hash"),
        ("mcp_tokens", "user_id"),
        ("mcp_tokens", "revoked_at"),
        ("personal_owner", "singleton"),
        ("personal_owner", "user_id"),
        ("users", "id"),
        ("users", "is_active"),
    }
    rows = q(
        db,
        """select c.relname, a.attname, has_column_privilege('grace_memory_owner', c.oid, a.attnum, 'select')
        from pg_class c join pg_namespace n on n.oid=c.relnamespace join pg_attribute a on a.attrelid=c.oid
        where n.nspname='public' and c.relname in ('mcp_tokens','personal_owner','users') and a.attnum>0 and not a.attisdropped""",
    )
    readable = {(r, a) for r, a, ok in rows if ok}
    assert readable == allowed
    for tbl in ("mcp_tokens", "personal_owner", "users"):
        for p in ("insert", "update", "delete"):
            assert not db.execute(
                f"select has_table_privilege('grace_memory_owner','public.{tbl}','{p}')"
            ).fetchone()[0]
        for p in ("insert", "update"):
            assert not db.execute(
                f"select bool_or(has_column_privilege('grace_memory_owner','public.{tbl}',attname,'{p}')) "
                f"from pg_attribute where attrelid='public.{tbl}'::regclass and attnum>0"
            ).fetchone()[0]


def test_owner_role_is_nologin_bypassrls(db):
    assert db.execute(
        "select rolcanlogin, rolbypassrls from pg_roles where rolname='grace_memory_owner'"
    ).fetchone() == (False, True)


def test_dashboard_tables_have_no_policies_and_nothing_in_public_created(db):
    assert q(db, "select 1 from pg_policies where schemaname='public'") == []
    # only stub tables exist in public
    assert {
        r[0]
        for r in q(
            db,
            "select relname from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and relkind in ('r','v','m','S','f','p')",
        )
    } <= {"users", "personal_owner", "mcp_tokens"}
    assert (
        q(
            db,
            "select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and proname like 'memory%'",
        )
        == []
    )
