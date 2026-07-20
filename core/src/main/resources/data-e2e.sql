-- Default e2e seed uses MiniMax (OpenAI-compatible). Swap provider/model rows as needed.
merge into agent_provider (
    id, name, description, provider_type, base_url, credential, config_json,
    gmt_create, gmt_modified, version
) key (id) values (
    1, 'minimax', 'Default e2e provider seed (MiniMax; replaceable).',
    'openai', 'https://api.minimaxi.com/v1', null, '{"modelCallTimeoutMillis":1800000,"modelCallIdleTimeoutMillis":120000}',
    current_timestamp(), current_timestamp(), 0
);

merge into agent_model (
    id, provider_id, name, description, capabilities_json, config_json,
    gmt_create, gmt_modified, version
) key (id) values (
    1, 1, 'MiniMax-M2.7', 'MiniMax M2.7 model.', '["TEXT","TOOLS"]',
    '{"contextWindow":204800,"maxOutputTokens":131072,"reasoning":true,"inputModalities":["TEXT"],"variants":[{"name":"off","thinkingLevel":"off","maxOutputTokens":131072},{"name":"low","thinkingLevel":"low","maxOutputTokens":131072},{"name":"medium","thinkingLevel":"medium","maxOutputTokens":131072},{"name":"high","thinkingLevel":"high","maxOutputTokens":131072}],"pricing":{"currency":"USD","pricingTier":"default","serviceTier":"default","serviceTierMultiplier":1,"version":"v1","inputPerMillionTokens":0.3,"outputPerMillionTokens":1.2,"cacheReadPerMillionTokens":0.06,"cacheWritePerMillionTokens":0.375,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}',
    current_timestamp(), current_timestamp(), 0
);

merge into agent_definition (
    id, name, description, system_prompt, model_id, variant, config_json,
    gmt_create, gmt_modified, version
) key (id) values (
    1, 'default-assistant', 'Default e2e assistant.',
    '你是 kk-studio 的本地开发助手。', 1, 'medium',
    '{"tools":[],"skills":[],"allowedSubagents":[],"executionPolicy":{"maxTurns":null,"maxDepth":null,"maxDirectSubagents":null,"maxTotalSubagents":null}}',
    current_timestamp(), current_timestamp(), 0
);
