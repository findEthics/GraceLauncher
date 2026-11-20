import pytest

TOKEN = "hc_TESTTOKEN_do_not_leak_0123456789abcdef"
URL = "https://example.invalid"
ANON = "anon-test-key-xyz"


@pytest.fixture
def env(monkeypatch):
    monkeypatch.setenv("SUPABASE_URL", URL)
    monkeypatch.setenv("SUPABASE_ANON_KEY", ANON)
    monkeypatch.setenv("GRACE_MEMORY_TOKEN", TOKEN)
