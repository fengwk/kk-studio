-- PostgreSQL e2e seed.
--
-- Idempotent: re-application is a no-op via INSERT ... ON CONFLICT DO NOTHING,
-- so existing timestamps and versions remain unchanged. base_url and credential
-- are intentionally null; real values are injected via environment when the e2e
-- process actually authenticates.
-- This file must never contain real secrets.

insert into agent_provider (
    name, description, provider_type, base_url, credential, config,
    created_at, updated_at, version
) values
    ('minimax', 'MiniMax (OpenAI Responses).', 'openai_response', null, null,
     '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
     current_timestamp, current_timestamp, 0),
    ('openai', 'OpenAI (OpenAI Responses).', 'openai_response', null, null,
     '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
     current_timestamp, current_timestamp, 0),
    ('xai', 'xAI / Grok (OpenAI Responses).', 'openai_response', null, null,
     '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
     current_timestamp, current_timestamp, 0),
    ('deepseek', 'DeepSeek (OpenAI Chat Completions).', 'openai', null, null,
     '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
     current_timestamp, current_timestamp, 0),
    ('google', 'Google Gemini.', 'google', null, null,
     '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
     current_timestamp, current_timestamp, 0),
    ('anthropic', 'Anthropic.', 'anthropic', null, null,
     '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
     current_timestamp, current_timestamp, 0),
    ('zai', 'ZAI (OpenAI Chat Completions).', 'openai', null, null,
     '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
     current_timestamp, current_timestamp, 0)
on conflict (name) do nothing;

-- Effective Pi 0.82.1 model snapshot. `minimax-responses` is mapped to provider
-- `minimax`; all other provider names match. Variants are Pi's supported thinking
-- levels after applying ~/.pi/agent/models.json and clamping its default `max` level.
with model_seed (
    provider_name, name, description, context_window, max_output_tokens,
    input_modalities, default_variant, variants,
    input_price, output_price, cache_read_price, cache_write_price, reasoning_price
) as (
  values
    ('minimax', 'MiniMax-M2.7', 'MiniMax-M2.7 (Responses)', 204800, 131072, '["TEXT"]'::jsonb, 'high', '[{"id":"off","reasoningEffort":"none"},{"id":"high","reasoningEffort":"high"}]'::jsonb, 0.3, 1.2, 0.06, 0.375, 1.2),
    ('minimax', 'MiniMax-M3', 'MiniMax-M3 (Responses)', 450000, 128000, '["TEXT","IMAGE"]'::jsonb, 'high', '[{"id":"off","reasoningEffort":"none"},{"id":"high","reasoningEffort":"high"}]'::jsonb, 0.3, 1.2, 0.06, 0, 1.2),
    ('openai', 'gpt-5.4', 'GPT-5.4', 272000, 128000, '["TEXT","IMAGE"]'::jsonb, 'xhigh', '[{"id":"off","reasoningEffort":"none"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"xhigh","reasoningEffort":"xhigh"}]'::jsonb, 2.5, 15, 0.25, 0, 15),
    ('openai', 'gpt-5.5', 'GPT-5.5', 272000, 128000, '["TEXT","IMAGE"]'::jsonb, 'xhigh', '[{"id":"off","reasoningEffort":"none"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"xhigh","reasoningEffort":"xhigh"}]'::jsonb, 5, 30, 0.5, 0, 30),
    ('openai', 'gpt-5.6-luna', 'GPT-5.6 Luna', 272000, 128000, '["TEXT","IMAGE"]'::jsonb, 'max', '[{"id":"off","reasoningEffort":"none"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"xhigh","reasoningEffort":"xhigh"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 1, 6, 0.1, 1.25, 6),
    ('openai', 'gpt-5.6-sol', 'GPT-5.6 Sol', 272000, 128000, '["TEXT","IMAGE"]'::jsonb, 'max', '[{"id":"off","reasoningEffort":"none"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"xhigh","reasoningEffort":"xhigh"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 5, 30, 0.5, 6.25, 30),
    ('openai', 'gpt-5.6-terra', 'GPT-5.6 Terra', 272000, 128000, '["TEXT","IMAGE"]'::jsonb, 'max', '[{"id":"off","reasoningEffort":"none"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"xhigh","reasoningEffort":"xhigh"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 2.5, 15, 0.25, 3.125, 15),
    ('xai', 'grok-4.5', 'Grok 4.5', 500000, 500000, '["TEXT","IMAGE"]'::jsonb, 'high', '[{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"}]'::jsonb, 2, 6, 0.3, 0, 6),
    ('deepseek', 'deepseek-v4-flash', 'DeepSeek V4 Flash', 272000, 128000, '["TEXT"]'::jsonb, 'max', '[{"id":"off"},{"id":"high","reasoningEffort":"high"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 0.14, 0.28, 0.0028, 0, 0.28),
    ('deepseek', 'deepseek-v4-pro', 'DeepSeek V4 Pro', 272000, 128000, '["TEXT"]'::jsonb, 'max', '[{"id":"off"},{"id":"high","reasoningEffort":"high"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 0.435, 0.87, 0.003625, 0, 0.87),
    ('google', 'gemini-3.5-flash', 'Gemini 3.5 Flash', 1048576, 65536, '["TEXT","IMAGE"]'::jsonb, 'high', '[{"id":"minimal","reasoningEffort":"minimal"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"}]'::jsonb, 1.5, 9, 0.15, 0, 9),
    ('google', 'gemini-3.6-flash', 'Gemini 3.6 Flash', 1048576, 65536, '["TEXT","IMAGE"]'::jsonb, 'high', '[{"id":"minimal","reasoningEffort":"minimal"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"}]'::jsonb, 1.5, 7.5, 0.15, 0, 7.5),
    ('google', 'gemini-3.1-pro-preview', 'Gemini 3.1 Pro Preview', 1048576, 65536, '["TEXT","IMAGE"]'::jsonb, 'high', '[{"id":"low","reasoningEffort":"low"},{"id":"high","reasoningEffort":"high"}]'::jsonb, 2, 12, 0.2, 0, 12),
    ('anthropic', 'claude-sonnet-4-6', 'Claude Sonnet 4.6', 1000000, 128000, '["TEXT","IMAGE"]'::jsonb, 'max', '[{"id":"off"},{"id":"minimal","reasoningEffort":"low"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 3, 15, 0.3, 3.75, 0),
    ('anthropic', 'claude-opus-4-6', 'Claude Opus 4.6', 1000000, 128000, '["TEXT","IMAGE"]'::jsonb, 'max', '[{"id":"off"},{"id":"minimal","reasoningEffort":"low"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 5, 25, 0.5, 6.25, 0),
    ('anthropic', 'claude-sonnet-5', 'Claude Sonnet 5', 1000000, 128000, '["TEXT","IMAGE"]'::jsonb, 'max', '[{"id":"off"},{"id":"minimal","reasoningEffort":"low"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"xhigh","reasoningEffort":"xhigh"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 2, 10, 0.2, 2.5, 0),
    ('anthropic', 'claude-opus-5', 'Claude Opus 5', 1000000, 128000, '["TEXT","IMAGE"]'::jsonb, 'max', '[{"id":"off"},{"id":"minimal","reasoningEffort":"low"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"xhigh","reasoningEffort":"xhigh"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 5, 25, 0.5, 6.25, 0),
    ('anthropic', 'claude-fable-5', 'Claude Fable 5', 1000000, 128000, '["TEXT","IMAGE"]'::jsonb, 'max', '[{"id":"minimal","reasoningEffort":"low"},{"id":"low","reasoningEffort":"low"},{"id":"medium","reasoningEffort":"medium"},{"id":"high","reasoningEffort":"high"},{"id":"xhigh","reasoningEffort":"xhigh"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 10, 50, 1, 12.5, 0),
    ('zai', 'glm-5.2', 'GLM-5.2', 200000, 128000, '["TEXT"]'::jsonb, 'max', '[{"id":"off"},{"id":"low","reasoningEffort":"high"},{"id":"medium","reasoningEffort":"high"},{"id":"high","reasoningEffort":"high"},{"id":"max","reasoningEffort":"max"}]'::jsonb, 0, 0, 0, 0, 0)
)
insert into agent_model (
    provider_name, name, description, config,
    created_at, updated_at, version
)
select
    provider_name,
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
                case when provider_name = 'anthropic' then input_price * 2 else 0 end,
            'reasoningPerMillionTokens', reasoning_price
        ),
        'defaultVariant', default_variant,
        'variants', variants
    ),
    current_timestamp,
    current_timestamp,
    0
from model_seed
on conflict (provider_name, name) do nothing;

insert into agent_definition (
    name, description, system_prompt, model_provider_name, model_name, variant, config,
    created_at, updated_at, version
) values (
    'default-assistant', 'Default e2e assistant.',
    '你是 kk-studio 的本地开发助手。',
    'minimax', 'MiniMax-M2.7', 'high',
    '{"toolIds":[],"skills":[],"subagents":[]}',
    current_timestamp, current_timestamp, 0
)
on conflict (name) do nothing;

-- Harness runtime policy rows are gone: retry and realtime stream policy are
-- no longer database tables. The runtime owns execution state with
-- application-generated UUID ids, so the business sequence needs no seed alignment.

-- -----------------------------------------------------------------------------
-- E2E Tool 权限语义：只在 e2e 数据库生效的 system_setting 覆盖。
--
-- V1 默认行（id=1）是 base.write/base.edit/base.bash 各 `* -> ask`，base.read 保持不限制（生产默认）。e2e 验收要求
-- base.read 也进入审批，因此本 seed 把 tool.permission 覆盖为 base.read/base.write/base.edit/base.bash 各 `* -> ask`
--（defaultYolo 仍为 false）。这是 e2e 的
-- 唯一权限事实来源；其余字段继续直接继承 V1 默认聚合，避免 seed 复制整份配置。
update system_setting
set config = jsonb_set(
    config,
    '{tool,permission}',
    '{"base.bash":[{"action":"ask","pattern":"*"}],"base.edit":[{"action":"ask","pattern":"*"}],"base.read":[{"action":"ask","pattern":"*"}],"base.write":[{"action":"ask","pattern":"*"}]}'::jsonb
)
where id = 1;
