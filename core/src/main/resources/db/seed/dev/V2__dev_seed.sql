-- PostgreSQL dev seed.
--
-- Idempotent: re-application is a no-op via INSERT ... ON CONFLICT DO NOTHING,
-- so existing timestamps and versions remain unchanged. No real credentials;
-- the stub provider holds a local-only stub key for offline dev profiles.

insert into agent_provider (
    name, description, provider_type, base_url, credential, config,
    created_at, updated_at, version
) values (
    'stub', 'Deterministic stub provider for offline dev profile.',
    'openai', 'http://stub.local/v1', 'stub-key',
    '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
    current_timestamp, current_timestamp, 0
)
on conflict (name) do nothing;

insert into agent_model (
    provider_name, name, description, config,
    created_at, updated_at, version
) values (
    'stub', 'acceptance-stub',
    'Local deterministic model for development and acceptance tests.',
    '{"limit":{"context":32768,"output":4096},"abilities":{"tools":true,"reasoning":false,"inputModalities":["TEXT"]},"defaultVariant":"default","variants":[{"id":"default"}],"pricing":{"currency":"USD","pricingTier":"acceptance","serviceTier":"default","serviceTierMultiplier":1,"version":"acceptance-v1","inputPerMillionTokens":0,"outputPerMillionTokens":0,"cacheReadPerMillionTokens":0,"cacheWritePerMillionTokens":0,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}',
    current_timestamp, current_timestamp, 0
)
on conflict (provider_name, name) do nothing;

insert into agent_definition (
    name, description, system_prompt, model_provider_name, model_name, variant, config,
    created_at, updated_at, version
) values (
    'default-assistant', 'Acceptance stub agent for local studio pages.',
    'You are the local acceptance stub assistant for kk-studio.',
    'stub', 'acceptance-stub', 'default',
    '{"tools":[],"skills":[]}',
    current_timestamp, current_timestamp, 0
)
on conflict (name) do nothing;

-- Harness realtime stream policy singleton (id=1, default 5000).
insert into harness_realtime_stream_policy (
    id, max_length
) values (
    1, 5000
)
on conflict (id) do nothing;

-- Harness retry policy singleton (id=1).
insert into harness_retry_policy (
    id, max_retries, backoff_strategy, base_delay_millis, max_delay_millis
) values (
    1, 3, 'EXPONENTIAL', 2000, 60000
)
on conflict (id) do nothing;

-- Keep the shared sequence ahead of the remaining deterministic singleton id.
with seed_max(value) as (
    select greatest(
        coalesce((select max(id) from harness_realtime_stream_policy), 0),
        coalesce((select max(id) from harness_retry_policy), 0)
    )
)
select setval('kk_studio_id_seq', seed_max.value, true)
from seed_max, kk_studio_id_seq current_sequence
where current_sequence.last_value < seed_max.value
   or (not current_sequence.is_called and current_sequence.last_value <= seed_max.value);
