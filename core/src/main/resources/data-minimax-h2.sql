merge into agent_provider (
    id, name, description, provider_type, base_url, api_key,
    timeout_millis, stream_idle_timeout_millis,
    gmt_create, gmt_modified, version
) key (id) values (
    1, 'minimax', 'MiniMax provider for local development and real-agent verification.',
    'openai', 'https://api.minimax.io/v1', 'set-by-scripts-dev-sh',
    120000, 120000,
    current_timestamp(), current_timestamp(), 0
);

merge into agent_model (
    id, provider_id, name, description, capabilities_json, limit_json, pricing_json,
    default_variant, variants_json, gmt_create, gmt_modified, version
) key (id) values (
    1, 1, 'MiniMax-M2.7',
    'MiniMax-M2.7 model for local development and real-agent verification.',
    null, null, null,
    'default', '[{"name":"default","temperature":1.0,"topP":0.95,"maxOutputTokens":16384}]',
    current_timestamp(), current_timestamp(), 0
);

merge into agent_definition (
    id, name, description, system_prompt,
    default_provider_id, default_model_id, default_variant,
    tools_json, subagents_json, skills_json,
    gmt_create, gmt_modified, version
) key (id) values (
    1, 'default-assistant', 'Default MiniMax agent for local studio pages.',
    '你是 kk-studio 的默认开发助手。请优先给出直接、准确、简洁的回答。',
    1, 1, 'default',
    '[]', '[]', '[]',
    current_timestamp(), current_timestamp(), 0
);
