-- PostgreSQL dev seed.
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
    provider_name, name, model_id, description, config,
    created_at, updated_at, version
) values (
    'stub', 'acceptance-stub', 'acceptance-stub',
    'Local deterministic model for development and acceptance tests.',
    '{"limit":{"context":32768,"output":4096},"abilities":{"tools":true,"reasoning":false,"inputModalities":["TEXT"]},"defaultVariant":"default","variants":[{"id":"default"}],"pricing":{"currency":"USD","pricingTier":"acceptance","serviceTier":"default","serviceTierMultiplier":1,"version":"acceptance-v1","inputPerMillionTokens":0,"outputPerMillionTokens":0,"cacheReadPerMillionTokens":0,"cacheWritePerMillionTokens":0,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}',
    current_timestamp, current_timestamp, 0
);

insert into agent_definition (
    name, description, system_prompt, model_provider_name, model_name, variant, config,
    created_at, updated_at, version
) values (
    'default-assistant', 'Acceptance stub agent for local studio pages.',
    'You are the local acceptance stub assistant for kk-studio.',
    'stub', 'acceptance-stub', 'default',
    '{"tools":[],"skills":[],"subagents":[],"inheritParentEnvironment":true}',
    current_timestamp, current_timestamp, 0
);

-- Harness runtime policy rows are gone: retry and realtime stream policy are
-- no longer database tables. The runtime owns execution state with
-- application-generated UUID ids, so the business sequence needs no seed alignment.
