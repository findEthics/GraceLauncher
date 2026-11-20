def test_owner_cannot_create_in_schema_after_migration(db):
    assert not db.execute("select has_schema_privilege('grace_memory_owner','grace_memory','create')").fetchone()[0]
