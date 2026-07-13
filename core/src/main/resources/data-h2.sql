merge into workspace (id, name, settings_json, gmt_create, gmt_modified, version) key (id) values (
    1, 'default', '{}', current_timestamp(), current_timestamp(), 0
);

merge into agent_provider (
    id, workspace_id, name, description, provider_type, base_url, credential, config_json,
    gmt_create, gmt_modified, version
) key (id) values (
    1, 1, 'stub', 'Local deterministic provider for development and acceptance tests.',
    'openai', 'http://stub.local/v1', 'stub-key', '{"timeoutMillis":60000}',
    current_timestamp(), current_timestamp(), 0
);

merge into agent_model (
    id, workspace_id, provider_id, name, description, capabilities_json, config_json,
    gmt_create, gmt_modified, version
) key (id) values (
    1, 1, 1, 'acceptance-stub',
    'Local deterministic model for development and acceptance tests.', '[]',
    '{"variants":[{"name":"default","temperature":0.2,"maxOutputTokens":4096}]}',
    current_timestamp(), current_timestamp(), 0
);

merge into agent_definition (
    id, workspace_id, name, description, system_prompt, model_id, variant, config_json,
    gmt_create, gmt_modified, version
) key (id) values (
    1, 1, 'default-assistant', 'Acceptance stub agent for local studio pages.',
    'You are the local acceptance stub assistant for kk-studio.', 1, 'default',
    '{"tools":[],"skills":[],"allowedSubagents":[],"executionPolicy":{"maxTurns":null,"maxDepth":null,"maxDirectSubagents":null,"maxTotalSubagents":null,"idleTimeoutMillis":null,"runTimeoutMillis":null}}',
    current_timestamp(), current_timestamp(), 0
);
