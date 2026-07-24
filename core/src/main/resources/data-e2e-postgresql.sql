-- PostgreSQL e2e seed.
--
-- Idempotent: re-application is a no-op via INSERT ... ON CONFLICT DO NOTHING,
-- so existing timestamps and versions remain unchanged. base_url and credential
-- are intentionally null; real values are injected via environment when the e2e
-- process actually authenticates.
-- This file must never contain real secrets.

insert into agent_provider (
    id, name, description, provider_type, base_url, credential, config,
    created_at, updated_at, version
) values
    (1, 'minimax', 'MiniMax (OpenAI Responses).', 'openai_response', null, null,
     '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
     current_timestamp, current_timestamp, 0),
    (2, 'openai', 'OpenAI (OpenAI Responses).', 'openai_response', null, null,
     '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
     current_timestamp, current_timestamp, 0),
    (3, 'xai', 'xAI / Grok (OpenAI Responses).', 'openai_response', null, null,
     '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
     current_timestamp, current_timestamp, 0),
    (4, 'deepseek', 'DeepSeek (OpenAI Chat Completions).', 'openai', null, null,
     '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
     current_timestamp, current_timestamp, 0),
    (5, 'google', 'Google Gemini.', 'google', null, null,
     '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
     current_timestamp, current_timestamp, 0)
on conflict (id) do nothing;

insert into agent_model (
    id, provider_id, name, description, config,
    created_at, updated_at, version
) values
    (1, 1, 'MiniMax-M2.7', 'MiniMax M2.7',
     '{"limit":{"context":204800,"output":131072},"abilities":{"tools":true,"reasoning":true,"inputModalities":["TEXT"]},"defaultVariant":"medium","variants":[{"id":"off"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"}],"pricing":{"currency":"USD","pricingTier":"default","serviceTier":"default","serviceTierMultiplier":1,"version":"v1","inputPerMillionTokens":0.3,"outputPerMillionTokens":1.2,"cacheReadPerMillionTokens":0.06,"cacheWritePerMillionTokens":0.375,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}',
     current_timestamp, current_timestamp, 0),
    (2, 1, 'MiniMax-M2.7-highspeed', 'MiniMax M2.7 highspeed',
     '{"limit":{"context":204800,"output":131072},"abilities":{"tools":true,"reasoning":true,"inputModalities":["TEXT"]},"defaultVariant":"medium","variants":[{"id":"off"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"}],"pricing":{"currency":"USD","pricingTier":"default","serviceTier":"default","serviceTierMultiplier":1,"version":"v1","inputPerMillionTokens":0.6,"outputPerMillionTokens":2.4,"cacheReadPerMillionTokens":0.06,"cacheWritePerMillionTokens":0.375,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}',
     current_timestamp, current_timestamp, 0),
    (3, 2, 'gpt-5.4', 'OpenAI GPT-5.4',
     '{"limit":{"context":1000000,"output":128000},"abilities":{"tools":true,"reasoning":true,"inputModalities":["TEXT","IMAGE"]},"defaultVariant":"medium","variants":[{"id":"off"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"}],"pricing":{"currency":"USD","pricingTier":"default","serviceTier":"default","serviceTierMultiplier":1,"version":"v1","inputPerMillionTokens":2.5,"outputPerMillionTokens":15,"cacheReadPerMillionTokens":0.25,"cacheWritePerMillionTokens":0,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}',
     current_timestamp, current_timestamp, 0),
    (4, 2, 'gpt-5.5', 'OpenAI GPT-5.5',
     '{"limit":{"context":1000000,"output":128000},"abilities":{"tools":true,"reasoning":true,"inputModalities":["TEXT","IMAGE"]},"defaultVariant":"medium","variants":[{"id":"off"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"}],"pricing":{"currency":"USD","pricingTier":"default","serviceTier":"default","serviceTierMultiplier":1,"version":"v1","inputPerMillionTokens":5,"outputPerMillionTokens":30,"cacheReadPerMillionTokens":0.5,"cacheWritePerMillionTokens":0,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}',
     current_timestamp, current_timestamp, 0),
    (5, 2, 'gpt-5.6-luna', 'OpenAI GPT-5.6 Luna',
     '{"limit":{"context":1050000,"output":128000},"abilities":{"tools":true,"reasoning":true,"inputModalities":["TEXT","IMAGE"]},"defaultVariant":"medium","variants":[{"id":"off"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"}],"pricing":{"currency":"USD","pricingTier":"default","serviceTier":"default","serviceTierMultiplier":1,"version":"v1","inputPerMillionTokens":1,"outputPerMillionTokens":6,"cacheReadPerMillionTokens":0.1,"cacheWritePerMillionTokens":1.25,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}',
     current_timestamp, current_timestamp, 0),
    (6, 2, 'gpt-5.6-sol', 'OpenAI GPT-5.6 Sol',
     '{"limit":{"context":1050000,"output":128000},"abilities":{"tools":true,"reasoning":true,"inputModalities":["TEXT","IMAGE"]},"defaultVariant":"medium","variants":[{"id":"off"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"}],"pricing":{"currency":"USD","pricingTier":"default","serviceTier":"default","serviceTierMultiplier":1,"version":"v1","inputPerMillionTokens":5,"outputPerMillionTokens":30,"cacheReadPerMillionTokens":0.5,"cacheWritePerMillionTokens":6.25,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}',
     current_timestamp, current_timestamp, 0),
    (7, 2, 'gpt-5.6-terra', 'OpenAI GPT-5.6 Terra',
     '{"limit":{"context":1050000,"output":128000},"abilities":{"tools":true,"reasoning":true,"inputModalities":["TEXT","IMAGE"]},"defaultVariant":"medium","variants":[{"id":"off"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"}],"pricing":{"currency":"USD","pricingTier":"default","serviceTier":"default","serviceTierMultiplier":1,"version":"v1","inputPerMillionTokens":2.5,"outputPerMillionTokens":15,"cacheReadPerMillionTokens":0.25,"cacheWritePerMillionTokens":3.125,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}',
     current_timestamp, current_timestamp, 0),
    (8, 3, 'grok-4.5', 'xAI Grok 4.5',
     '{"limit":{"context":500000,"output":128000},"abilities":{"tools":true,"reasoning":true,"inputModalities":["TEXT","IMAGE"]},"defaultVariant":"medium","variants":[{"id":"off"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"}],"pricing":{"currency":"USD","pricingTier":"default","serviceTier":"default","serviceTierMultiplier":1,"version":"v1","inputPerMillionTokens":2,"outputPerMillionTokens":6,"cacheReadPerMillionTokens":0.5,"cacheWritePerMillionTokens":0,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}',
     current_timestamp, current_timestamp, 0),
    (9, 4, 'deepseek-v4-flash', 'DeepSeek V4 Flash',
     '{"limit":{"context":1000000,"output":131072},"abilities":{"tools":true,"reasoning":true,"inputModalities":["TEXT"]},"defaultVariant":"high","variants":[{"id":"off"},{"id":"high","reasoningEffort":"high"}],"pricing":{"currency":"USD","pricingTier":"default","serviceTier":"default","serviceTierMultiplier":1,"version":"v1","inputPerMillionTokens":0.14,"outputPerMillionTokens":0.28,"cacheReadPerMillionTokens":0.0028,"cacheWritePerMillionTokens":0,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}',
     current_timestamp, current_timestamp, 0),
    (10, 4, 'deepseek-v4-pro', 'DeepSeek V4 Pro',
     '{"limit":{"context":1000000,"output":131072},"abilities":{"tools":true,"reasoning":true,"inputModalities":["TEXT"]},"defaultVariant":"high","variants":[{"id":"off"},{"id":"high","reasoningEffort":"high"}],"pricing":{"currency":"USD","pricingTier":"default","serviceTier":"default","serviceTierMultiplier":1,"version":"v1","inputPerMillionTokens":1.74,"outputPerMillionTokens":3.48,"cacheReadPerMillionTokens":0.2,"cacheWritePerMillionTokens":0,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}',
     current_timestamp, current_timestamp, 0),
    (11, 5, 'gemini-3.5-flash', 'Gemini 3.5 Flash',
     '{"limit":{"context":1048576,"output":65536},"abilities":{"tools":true,"reasoning":true,"inputModalities":["TEXT","IMAGE"]},"defaultVariant":"medium","variants":[{"id":"off"},{"id":"medium"}],"pricing":{"currency":"USD","pricingTier":"default","serviceTier":"default","serviceTierMultiplier":1,"version":"v1","inputPerMillionTokens":1.5,"outputPerMillionTokens":9,"cacheReadPerMillionTokens":0.15,"cacheWritePerMillionTokens":0,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}',
     current_timestamp, current_timestamp, 0),
    (12, 5, 'gemini-3.1-pro', 'Gemini 3.1 Pro',
     '{"limit":{"context":1048576,"output":65536},"abilities":{"tools":true,"reasoning":true,"inputModalities":["TEXT","IMAGE"]},"defaultVariant":"high","variants":[{"id":"off"},{"id":"low","reasoningEffort":"low"},{"id":"high","reasoningEffort":"high"}],"pricing":{"currency":"USD","pricingTier":"default","serviceTier":"default","serviceTierMultiplier":1,"version":"v1","inputPerMillionTokens":2,"outputPerMillionTokens":12,"cacheReadPerMillionTokens":0.2,"cacheWritePerMillionTokens":0,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}',
     current_timestamp, current_timestamp, 0)
on conflict (id) do nothing;

insert into agent_definition (
    id, name, description, system_prompt, model_id, variant, config,
    created_at, updated_at, version
) values (
    1, 'default-assistant', 'Default e2e assistant.',
    '你是 kk-studio 的本地开发助手。',
    1, 'medium',
    '{"tools":[],"skills":[],"allowedSubagents":[],"executionPolicy":{"maxTurns":32,"maxDepth":4,"maxDirectSubagents":4,"maxTotalSubagents":16}}',
    current_timestamp, current_timestamp, 0
)
on conflict (id) do nothing;

insert into harness_retry_policy (
    id, max_retries, backoff_strategy, base_delay_millis, max_delay_millis,
    created_at, updated_at
) values (
    1, 3, 'EXPONENTIAL', 2000, 60000,
    current_timestamp, current_timestamp
)
on conflict (id) do nothing;

-- Provider ids stay deterministic because the E2E credential-sync script
-- addresses providers 1..5. Only advance the sequence when it is still behind
-- those seed ids; re-application after normal writes never calls setval.
with seed_max(value) as (
    select greatest(
        coalesce((select max(id) from agent_provider), 0),
        coalesce((select max(id) from agent_model), 0),
        coalesce((select max(id) from agent_definition), 0),
        coalesce((select max(id) from harness_retry_policy), 0)
    )
)
select setval('kk_studio_id_seq', seed_max.value, true)
from seed_max, kk_studio_id_seq current_sequence
where current_sequence.last_value < seed_max.value
   or (not current_sequence.is_called and current_sequence.last_value <= seed_max.value);
