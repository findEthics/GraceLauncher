import hashlib, json, pathlib, subprocess, time, uuid
import psycopg
from psycopg.types.json import Jsonb
import pytest

HERE = pathlib.Path(__file__).parent
MIGRATIONS = sorted(HERE.parent.glob("[0-9][0-9][0-9]_*.sql"))
STUB = HERE / "stub_dashboard.sql"


def _docker(*args):
    return subprocess.run(["docker", *args], check=True, capture_output=True, text=True).stdout.strip()


def _apply(dsn, *files):
    with psycopg.connect(dsn, autocommit=False) as c:
        for f in files:
            c.execute(f.read_text())
        c.commit()


@pytest.fixture(scope="session")
def cluster():
    name = f"gm-test-{uuid.uuid4().hex[:8]}"
    _docker("run", "--rm", "-d", "--name", name, "-p", "127.0.0.1:0:5432",
            "-e", "POSTGRES_PASSWORD=pw", "postgres:16")
    try:
        port = _docker("port", name, "5432/tcp").splitlines()[0].rsplit(":", 1)[1]
        base = f"host=127.0.0.1 port={port} user=postgres password=pw"
        for _ in range(60):
            try:
                psycopg.connect(base + " dbname=postgres", connect_timeout=2).close()
                break
            except psycopg.OperationalError:
                time.sleep(1)
        else:
            raise RuntimeError("postgres did not start")
        with psycopg.connect(base + " dbname=postgres", autocommit=True) as c:
            c.execute("create database tmpl")
        tmpl = base + " dbname=tmpl"
        _apply(tmpl, STUB, *MIGRATIONS)
        yield base
    finally:
        subprocess.run(["docker", "rm", "-f", name], capture_output=True)


@pytest.fixture
def db(cluster):
    """Fresh superuser connection to a per-test DB cloned from the migrated template."""
    name = "t_" + uuid.uuid4().hex[:10]
    with psycopg.connect(cluster + " dbname=postgres", autocommit=True) as c:
        c.execute(f'create database {name} template tmpl')
    conn = psycopg.connect(cluster + f" dbname={name}", autocommit=True)
    yield conn
    conn.close()
    with psycopg.connect(cluster + " dbname=postgres", autocommit=True) as c:
        c.execute(f"drop database {name} with (force)")


def token_hash(tok: str) -> str:
    return hashlib.sha256(tok.strip().encode()).hexdigest()


class World:
    """Users: A = active owner, B = other (inactive, non-owner). Tokens are generated fixtures."""

    def __init__(self, conn):
        self.conn = conn
        self.a = conn.execute("insert into public.users(email,is_active) values ('a@test',true) returning id").fetchone()[0]
        self.b = conn.execute("insert into public.users(email,is_active) values ('b@test',false) returning id").fetchone()[0]
        conn.execute("insert into public.personal_owner(user_id) values (%s)", (self.a,))
        self.tok = "hc_test_" + uuid.uuid4().hex
        self.tok_revoked = "hc_test_" + uuid.uuid4().hex
        self.tok_b = "hc_test_" + uuid.uuid4().hex
        self.tok_unknown = "hc_test_" + uuid.uuid4().hex
        self._add(self.a, self.tok)
        self._add(self.a, self.tok_revoked, revoked=True)
        self._add(self.b, self.tok_b)

    def _add(self, uid, tok, revoked=False):
        self.conn.execute(
            "insert into public.mcp_tokens(user_id, token_hash, revoked_at) "
            "values (%s,%s,case when %s then now() end)",
            (uid, token_hash(tok), revoked),
        )

    def rpc(self, fn, *args, token=None, header=None):
        """PostgREST simulation: tx-local request.headers + role anon. Returns python value; raises psycopg errors."""
        if header is None:
            header = json.dumps({"x-grace-token": self.tok if token is None else token})
        with self.conn.transaction():
            self.conn.execute("select set_config('request.headers', %s, true)", (header,))
            self.conn.execute("set local role anon")
            args = tuple(Jsonb(a) if isinstance(a, (dict, list)) else a for a in args)
            ph = ",".join(["%s"] * len(args))
            return self.conn.execute(f"select grace_memory.{fn}({ph})", args).fetchone()[0]

    def entry(self, **kw):
        e = {"id": str(uuid.uuid4()), "type": "fact", "subject": "Home city", "body": "Berlin",
             "source": "test", "valid_from": "2026-01-01T00:00:00Z"}
        e.update(kw)
        return e

    def rows(self, sql="select * from grace_memory.memory_entries order by valid_from", args=()):
        return self.conn.execute(sql, args).fetchall()


@pytest.fixture
def w(db):
    return World(db)
