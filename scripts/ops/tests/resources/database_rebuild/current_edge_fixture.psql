-- Deterministic edge-character fixture for the current V1 baseline.
--
-- The rebuild carries these rows through a COPY CSV stream, so the values stress every encoding
-- hazard at once: JSON with escapes, bytea, NULL next to the empty string, and text containing a
-- newline, a tab, a backslash, a double quote, a single quote, a `\.` sequence and Unicode.

insert into environment (id, name, registration_token, created_at, updated_at, version) values
    ('77777777-7777-4777-8777-777777777777', 'probe-edge-environment', 'probe-edge-token',
     '2024-03-01 12:34:56.789+05:30', '2024-03-02 00:00:00-08:00', 7);

insert into agent_provider (name, description, provider_type, base_url, credential, config,
    connection_generation_id, created_at, updated_at, version) values
    ('probe-edge-provider', E'line\nbreak\ttab\\backslash\\.dot"quote''apostrophe-日本語-🚀',
     'openai', 'https://probe.invalid/v1?x="y"\\z',
     E'credential\nwith\ttabs\\and"quotes', '{"modelCallTimeoutMillis": 600000}'::jsonb,
     '88888888-8888-4888-8888-888888888888',
     '2024-03-03 00:00:00.001+00', '2024-03-04 00:00:00.002+00', 1),
    ('probe-empty-provider', '', 'anthropic', null, '', '{}'::jsonb,
     '99999999-9999-4999-8999-999999999999',
     '2024-03-05 00:00:00+00', '2024-03-05 00:00:00+00', 0),
    ('probe-null-provider', null, 'google', null, null, '{}'::jsonb,
     'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
     '2024-03-06 00:00:00+00', '2024-03-06 00:00:00+00', 0);

insert into agent_model (provider_name, name, model_id, description, config, created_at,
    updated_at, version) values
    ('probe-edge-provider', 'probe-edge-model', 'probe-edge-wire-model',
     E'line\nbreak\ttab\\backslash\\.dot"quote''apostrophe-日本語-🚀',
     '{"variants": [{"id": "variant\nwith\nescape"}]}'::jsonb,
     '2024-03-07 00:00:00.003+00', '2024-03-08 00:00:00.004+00', 2);

insert into skill_package (package_name, description, repository_url, branch, current_commit,
    observed_head_commit, head_checked_at, head_check_error, skills, version, create_time,
    update_time) values
    ('probe-edge-package', E'line\nbreak\ttab\\backslash\\.dot"quote''apostrophe-日本語-🚀',
     'https://probe.invalid/git/repo.git?x="y"\\z', 'feature/日本語-🚀',
     '0123456789abcdef0123456789abcdef01234567', null, '2024-03-09 05:06:07.008+09:00',
     E'trimmed\nhead\tcheck\\error', '[{"name": "probe-skill", "description": "说\n明"}]'::jsonb,
     5, '2024-03-09 00:00:00+00', '2024-03-10 00:00:00.500+00'),
    ('probe-null-package', null, 'https://probe.invalid/git/other.git', 'main',
     'fedcba9876543210fedcba9876543210fedcba98',
     'fedcba9876543210fedcba9876543210fedcba98', null, null, '[]'::jsonb, 0,
     '2024-03-11 00:00:00+00', '2024-03-11 00:00:00+00');

insert into agent_definition (name, description, system_prompt, model_provider_name, model_name,
    variant, config, created_at, updated_at, version) values
    ('probe-edge-agent', E'line\nbreak\ttab\\backslash\\.dot"quote''apostrophe-日本語-🚀',
     E'line\nbreak\ttab\\backslash\\.dot"quote''apostrophe-日本語-🚀', 'probe-edge-provider',
     'probe-edge-model', 'probe-variant',
     '{"tools": ["read"], "skills": [{"packageName": "probe-edge-package", "name": "probe-skill"}], "subagents": [], "inheritParentEnvironment": false}'::jsonb,
     '2024-03-12 00:00:00.123+02:00', '2024-03-12 23:00:00.456-08:00', 9),
    ('probe-null-agent', null, '', 'probe-edge-provider', 'probe-edge-model', null,
     '{"tools": [], "skills": [], "subagents": [], "inheritParentEnvironment": true}'::jsonb,
     '2024-03-13 00:00:00+00', '2024-03-13 00:00:00+00', 0);

insert into plugin_credential (plugin_id, encrypted_payload, region, expires_at, next_refresh_at,
    status, last_refreshed_at, last_refresh_error, refresh_lease_token, refresh_lease_until,
    version, create_time, update_time) values
    ('probe.edge.plugin', '\x000a0d095c220a2e5c2e5cff'::bytea, 'probe-region',
     '2024-03-14 00:00:00.123+00', '2024-03-15 00:00:00.456+00', 'CONNECTED', null,
     E'trimmed\nrefresh\terror', E'lease\\token"quote', '2024-03-16 01:00:00+00', 2,
     '2024-03-17 00:00:00+00', '2024-03-18 00:00:00+00'),
    ('probe.plain.plugin', '\xdeadbeef'::bytea, 'probe-region-2',
     '2024-03-19 00:00:00+00', '2024-03-20 00:00:00+00', 'REAUTH_REQUIRED',
     '2024-03-21 00:00:00+00', null, null, null, 0,
     '2024-03-22 00:00:00+00', '2024-03-23 00:00:00+00');
