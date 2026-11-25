-- Disposable development and test profiles only; never add this location to prod.
-- Fixed local credential: kkstudio / kkstudio. This repeatable seed deliberately
-- restores the disposable administrator whenever Flyway reapplies this seed.
insert into studio_user (id, username, password_hash, role)
values (
    '00000000-0000-0000-0000-000000000001',
    'kkstudio',
    '{pbkdf2}473d0449576a25f739f57765f5b5d4ddd346ec503ad602aeab8da49810d4c61f460d54a08e070b1552a60ccab0285830',
    'ADMIN'
)
on conflict (id) do update set
    username = excluded.username,
    password_hash = excluded.password_hash,
    role = excluded.role,
    disabled_at = null,
    updated_at = current_timestamp;
