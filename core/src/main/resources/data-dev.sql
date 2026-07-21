merge into agent_provider (
    id, name, description, provider_type, base_url, credential, config_json,
    gmt_create, gmt_modified, version
) key (id) values (
    1, 'stub', 'Deterministic stub provider for offline dev profile.',
    'openai', 'http://stub.local/v1', 'stub-key', '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
    current_timestamp(), current_timestamp(), 0
);

merge into agent_model (
    id, provider_id, name, description, config_json,
    gmt_create, gmt_modified, version
) key (id) values (
    1, 1, 'acceptance-stub',
    'Local deterministic model for development and acceptance tests.',
    '{"limit":{"context":32768,"output":4096},"abilities":{"tools":true,"reasoning":false,"inputModalities":["TEXT"]},"defaultVariant":"default","variants":[{"id":"default"}],"pricing":{"currency":"USD","pricingTier":"acceptance","serviceTier":"default","serviceTierMultiplier":1,"version":"acceptance-v1","inputPerMillionTokens":0,"outputPerMillionTokens":0,"cacheReadPerMillionTokens":0,"cacheWritePerMillionTokens":0,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}',
    current_timestamp(), current_timestamp(), 0
);

merge into agent_definition (
    id, name, description, system_prompt, model_id, variant, config_json,
    gmt_create, gmt_modified, version
) key (id) values (
    1, 'default-assistant', 'Acceptance stub agent for local studio pages.',
    'You are the local acceptance stub assistant for kk-studio.', 1, 'default',
    '{"tools":[],"skills":[],"allowedSubagents":[],"executionPolicy":{"maxTurns":null,"maxDepth":null,"maxDirectSubagents":null,"maxTotalSubagents":null}}',
    current_timestamp(), current_timestamp(), 0
);
