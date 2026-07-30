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
     current_timestamp, current_timestamp, 0),
    (6, 'anthropic', 'Anthropic.', 'anthropic', null, null,
     '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
     current_timestamp, current_timestamp, 0),
    (7, 'zai', 'ZAI (OpenAI Chat Completions).', 'openai', null, null,
     '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
     current_timestamp, current_timestamp, 0)
on conflict (id) do nothing;

-- Effective Pi 0.82.1 model snapshot. `minimax-responses` is mapped to provider id 1
-- (`minimax`); all other provider names match. Variants are Pi's supported thinking
-- levels after applying ~/.pi/agent/models.json and clamping its default `max` level.
with model_seed (
    id, provider_id, name, description, context_window, max_output_tokens,
    input_modalities, default_variant, variants,
    input_price, output_price, cache_read_price, cache_write_price, reasoning_price
) as (
  values
    (1, 1, 'MiniMax-M2.7', 'MiniMax-M2.7 (Responses)', 204800, 131072, '["TEXT"]'::jsonb, 'high', '[{"id":"off","reasoningEffort":"none"},{"id":"high","reasoningEffort":"high"}]'::jsonb, 0.3, 1.2, 0.06, 0.375, 1.2),
    (2, 1, 'MiniMax-M3', 'MiniMax-M3 (Responses)', 450000, 128000, '["TEXT","IMAGE"]'::jsonb, 'high', '[{"id":"off","reasoningEffort":"none"},{"id":"high","reasoningEffort":"high"}]'::jsonb, 0.3, 1.2, 0.06, 0, 1.2),
    (3, 2, 'gpt-5.4', 'GPT-5.4', 272000, 128000, '["TEXT","IMAGE"]'::jsonb, 'xhigh', '[{"id":"off","reasoningEffort":"none"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"xhigh","reasoningEffort":"xhigh"}]'::jsonb, 2.5, 15, 0.25, 0, 15),
    (4, 2, 'gpt-5.5', 'GPT-5.5', 272000, 128000, '["TEXT","IMAGE"]'::jsonb, 'xhigh', '[{"id":"off","reasoningEffort":"none"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"xhigh","reasoningEffort":"xhigh"}]'::jsonb, 5, 30, 0.5, 0, 30),
    (5, 2, 'gpt-5.6-luna', 'GPT-5.6 Luna', 272000, 128000, '["TEXT","IMAGE"]'::jsonb, 'max', '[{"id":"off","reasoningEffort":"none"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"xhigh","reasoningEffort":"xhigh"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 1, 6, 0.1, 1.25, 6),
    (6, 2, 'gpt-5.6-sol', 'GPT-5.6 Sol', 272000, 128000, '["TEXT","IMAGE"]'::jsonb, 'max', '[{"id":"off","reasoningEffort":"none"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"xhigh","reasoningEffort":"xhigh"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 5, 30, 0.5, 6.25, 30),
    (7, 2, 'gpt-5.6-terra', 'GPT-5.6 Terra', 272000, 128000, '["TEXT","IMAGE"]'::jsonb, 'max', '[{"id":"off","reasoningEffort":"none"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"xhigh","reasoningEffort":"xhigh"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 2.5, 15, 0.25, 3.125, 15),
    (8, 3, 'grok-4.5', 'Grok 4.5', 500000, 500000, '["TEXT","IMAGE"]'::jsonb, 'high', '[{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"}]'::jsonb, 2, 6, 0.3, 0, 6),
    (9, 4, 'deepseek-v4-flash', 'DeepSeek V4 Flash', 272000, 128000, '["TEXT"]'::jsonb, 'max', '[{"id":"off"},{"id":"high","reasoningEffort":"high"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 0.14, 0.28, 0.0028, 0, 0.28),
    (10, 4, 'deepseek-v4-pro', 'DeepSeek V4 Pro', 272000, 128000, '["TEXT"]'::jsonb, 'max', '[{"id":"off"},{"id":"high","reasoningEffort":"high"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 0.435, 0.87, 0.003625, 0, 0.87),
    (11, 5, 'gemini-3.5-flash', 'Gemini 3.5 Flash', 1048576, 65536, '["TEXT","IMAGE"]'::jsonb, 'high', '[{"id":"minimal","reasoningEffort":"minimal"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"}]'::jsonb, 1.5, 9, 0.15, 0, 9),
    (12, 5, 'gemini-3.6-flash', 'Gemini 3.6 Flash', 1048576, 65536, '["TEXT","IMAGE"]'::jsonb, 'high', '[{"id":"minimal","reasoningEffort":"minimal"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"}]'::jsonb, 1.5, 7.5, 0.15, 0, 7.5),
    (13, 5, 'gemini-3.1-pro-preview', 'Gemini 3.1 Pro Preview', 1048576, 65536, '["TEXT","IMAGE"]'::jsonb, 'high', '[{"id":"low","reasoningEffort":"low"},{"id":"high","reasoningEffort":"high"}]'::jsonb, 2, 12, 0.2, 0, 12),
    (14, 6, 'claude-sonnet-4-6', 'Claude Sonnet 4.6', 1000000, 128000, '["TEXT","IMAGE"]'::jsonb, 'max', '[{"id":"off"},{"id":"minimal","reasoningEffort":"low"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 3, 15, 0.3, 3.75, 0),
    (15, 6, 'claude-opus-4-6', 'Claude Opus 4.6', 1000000, 128000, '["TEXT","IMAGE"]'::jsonb, 'max', '[{"id":"off"},{"id":"minimal","reasoningEffort":"low"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 5, 25, 0.5, 6.25, 0),
    (16, 6, 'claude-sonnet-5', 'Claude Sonnet 5', 1000000, 128000, '["TEXT","IMAGE"]'::jsonb, 'max', '[{"id":"off"},{"id":"minimal","reasoningEffort":"low"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"xhigh","reasoningEffort":"xhigh"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 2, 10, 0.2, 2.5, 0),
    (17, 6, 'claude-opus-5', 'Claude Opus 5', 1000000, 128000, '["TEXT","IMAGE"]'::jsonb, 'max', '[{"id":"off"},{"id":"minimal","reasoningEffort":"low"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"xhigh","reasoningEffort":"xhigh"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 5, 25, 0.5, 6.25, 0),
    (18, 6, 'claude-fable-5', 'Claude Fable 5', 1000000, 128000, '["TEXT","IMAGE"]'::jsonb, 'max', '[{"id":"minimal","reasoningEffort":"low"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"xhigh","reasoningEffort":"xhigh"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 10, 50, 1, 12.5, 0),
    (19, 7, 'glm-5.2', 'GLM-5.2', 200000, 128000, '["TEXT"]'::jsonb, 'max', '[{"id":"off"},{"id":"low","reasoningEffort":"high"},{"id":"medium","reasoningEffort":"high"},{"id":"high","reasoningEffort":"high"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 0, 0, 0, 0, 0)
)
insert into agent_model (
    id, provider_id, name, description, config,
    created_at, updated_at, version
)
select
    id,
    provider_id,
    name,
    description,
    jsonb_build_object(
        'limit', jsonb_build_object(
            'context', context_window,
            'output', max_output_tokens
        ),
        'abilities', jsonb_build_object(
            'tools', true,
            'reasoning', true,
            'inputModalities', input_modalities
        ),
        'pricing', jsonb_build_object(
            'currency', 'USD',
            'pricingTier', 'pi-base',
            'serviceTier', 'default',
            'serviceTierMultiplier', 1,
            'version', 'pi-0.82.1',
            'inputPerMillionTokens', input_price,
            'outputPerMillionTokens', output_price,
            'cacheReadPerMillionTokens', cache_read_price,
            'cacheWritePerMillionTokens', cache_write_price,
            'cacheWriteLongPerMillionTokens',
                case when provider_id = 6 then input_price * 2 else 0 end,
            'reasoningPerMillionTokens', reasoning_price
        ),
        'defaultVariant', default_variant,
        'variants', variants
    ),
    current_timestamp,
    current_timestamp,
    0
from model_seed
on conflict (id) do nothing;

insert into agent_definition (
    id, name, description, system_prompt, model_id, variant, config,
    created_at, updated_at, version
) values (
    1, 'default-assistant', 'Default e2e assistant.',
    '你是 kk-studio 的本地开发助手。',
    1, 'high',
    '{"tools":[],"skills":[]}',
    current_timestamp, current_timestamp, 0
)
on conflict (id) do nothing;

insert into harness_realtime_stream_policy (
    id, max_length
) values (
    1, 5000
)
on conflict (id) do nothing;

insert into harness_retry_policy (
    id, max_retries, backoff_strategy, base_delay_millis, max_delay_millis
) values (
    1, 3, 'EXPONENTIAL', 2000, 60000
)
on conflict (id) do nothing;

-- Provider ids stay deterministic because the E2E credential-sync script
-- addresses providers 1..7. Only advance the sequence when it is still behind
-- those seed ids; re-application after normal writes never calls setval.
with seed_max(value) as (
    select greatest(
        coalesce((select max(id) from agent_provider), 0),
        coalesce((select max(id) from agent_model), 0),
        coalesce((select max(id) from agent_definition), 0),
        coalesce((select max(id) from harness_realtime_stream_policy), 0),
        coalesce((select max(id) from harness_retry_policy), 0)
    )
)
select setval('kk_studio_id_seq', seed_max.value, true)
from seed_max, kk_studio_id_seq current_sequence
where current_sequence.last_value < seed_max.value
   or (not current_sequence.is_called and current_sequence.last_value <= seed_max.value);
