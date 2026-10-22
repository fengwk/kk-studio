-- PostgreSQL test fixture seed: a deterministic stub provider, model and agent
-- for automated tests. No runtime profile applies it; a real installation starts
-- from an empty catalog and the first-run guide.
--
-- Deterministic replacement: seed-owned catalog rows are synchronized to the
-- exact definitions in this file on every repeatable migration run.
-- FK dependency order is preserved by cleaning dependent agents and models first.

delete from agent_definition where name = 'default-assistant';
delete from agent_model where provider_name = 'stub';
delete from agent_provider where name = 'stub';

insert into agent_provider (
    name, description, provider_type, base_url, credential, config, connection_generation_id,
    created_at, updated_at, version
) values (
    'stub', 'Deterministic stub provider for offline dev profile.',
    'openai', 'http://stub.local:8080/v1', 'stub-key',
    '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
    '00000000-0000-0000-0000-000000000001'::uuid,
    current_timestamp, current_timestamp, 0
);

insert into agent_model (
    name, kind, provider_name, model_id, description, config,
    created_at, updated_at, version
) values (
    'acceptance-stub', 'MODEL', 'stub', 'acceptance-stub',
    'Local deterministic model for development and acceptance tests.',
    '{"limit":{"context":32768,"output":4096},"abilities":{"tools":true,"reasoning":false,"inputModalities":["TEXT"]},"pricing":{"base":{"input":"0","output":"0","cacheRead":"0","cacheWrite":"0","cacheWriteByTtl":{},"reasoning":"0"},"overrides":[],"multipliers":[]},"defaultVariant":"default","variants":[{"id":"default"}]}',
    current_timestamp, current_timestamp, 0
);

insert into agent_definition (
    name, description, system_prompt, model_name, variant, config,
    created_at, updated_at, version
) values (
    'default-assistant', 'Acceptance stub agent for local studio pages.',
    'You are the local acceptance stub assistant for kk-studio.',
    'acceptance-stub', 'default',
    '{"tools":[],"skills":[],"subagents":[],"inheritParentEnvironment":true}',
    current_timestamp, current_timestamp, 0
);

-- Harness runtime policy rows are gone: retry and realtime stream policy are
-- no longer database tables. The runtime owns execution state with
-- application-generated UUID ids, so the business sequence needs no seed alignment.
