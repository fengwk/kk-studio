merge into workspace (id, name, settings_json, gmt_create, gmt_modified, version) key (id) values (
    1, 'default', '{}', current_timestamp(), current_timestamp(), 0
);

merge into agent_provider (
    id, name, description, provider_type, base_url, credential, config_json,
    gmt_create, gmt_modified, version
) key (id) values (
    1, 'minimax', 'MiniMax provider for local development.',
    'openai', 'https://api.minimaxi.com/v1', null, '{"timeoutMillis":60000}',
    current_timestamp(), current_timestamp(), 0
);

merge into agent_model (
    id, provider_id, name, description, capabilities_json, config_json,
    gmt_create, gmt_modified, version
) key (id) values (
    1, 1, 'MiniMax-M2.7', 'MiniMax M2.7 model.', '[]',
    '{"variants":[{"name":"default"}]}', current_timestamp(), current_timestamp(), 0
);

merge into agent_definition (
    id, workspace_id, name, description, system_prompt, model_id, variant, config_json,
    gmt_create, gmt_modified, version
) key (id) values (
    1, 1, 'default-assistant', 'Default MiniMax assistant.',
    '你是 kk-studio 的本地开发助手。', 1, 'default',
    '{"tools":[],"skills":[],"allowedSubagents":[],"executionPolicy":{"maxTurns":null,"maxDepth":null,"maxDirectSubagents":null,"maxTotalSubagents":null,"idleTimeoutMillis":null,"runTimeoutMillis":null}}',
    current_timestamp(), current_timestamp(), 0
);
