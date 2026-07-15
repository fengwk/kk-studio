insert into workspace (id, name, settings_json, gmt_create, gmt_modified, version) values (
    1, 'default', '{}', current_timestamp(3), current_timestamp(3), 0
) on duplicate key update name = 'default', settings_json = '{}', gmt_modified = current_timestamp(3);

insert into agent_provider (
    id, name, description, provider_type, base_url, credential, config_json,
    gmt_create, gmt_modified, version
) values (
    1, 'stub', 'Local deterministic provider for development and acceptance tests.',
    'openai', 'http://stub.local/v1', 'stub-key', '{"timeoutMillis":60000}',
    current_timestamp(3), current_timestamp(3), 0
) on duplicate key update
    name = 'stub', description = 'Local deterministic provider for development and acceptance tests.',
    provider_type = 'openai', base_url = 'http://stub.local/v1', credential = 'stub-key',
    config_json = '{"timeoutMillis":60000}', gmt_modified = current_timestamp(3);

insert into agent_model (
    id, provider_id, name, description, capabilities_json, config_json,
    gmt_create, gmt_modified, version
) values (
    1, 1, 'acceptance-stub',
    'Local deterministic model for development and acceptance tests.', '[]',
    '{"variants":[{"name":"default","temperature":0.2,"maxOutputTokens":4096}]}',
    current_timestamp(3), current_timestamp(3), 0
) on duplicate key update
    provider_id = 1, name = 'acceptance-stub',
    description = 'Local deterministic model for development and acceptance tests.', capabilities_json = '[]',
    config_json = '{"variants":[{"name":"default","temperature":0.2,"maxOutputTokens":4096}]}',
    gmt_modified = current_timestamp(3);

insert into agent_definition (
    id, workspace_id, name, description, system_prompt, model_id, variant, config_json,
    gmt_create, gmt_modified, version
) values (
    1, 1, 'default-assistant', 'Acceptance stub agent for local studio pages.',
    'You are the local acceptance stub assistant for kk-studio.', 1, 'default',
    '{"tools":[],"skills":[],"allowedSubagents":[],"executionPolicy":{"maxTurns":null,"maxDepth":null,"maxDirectSubagents":null,"maxTotalSubagents":null,"idleTimeoutMillis":null,"runTimeoutMillis":null}}',
    current_timestamp(3), current_timestamp(3), 0
) on duplicate key update
    workspace_id = 1, name = 'default-assistant', description = 'Acceptance stub agent for local studio pages.',
    system_prompt = 'You are the local acceptance stub assistant for kk-studio.', model_id = 1, variant = 'default',
    config_json = '{"tools":[],"skills":[],"allowedSubagents":[],"executionPolicy":{"maxTurns":null,"maxDepth":null,"maxDirectSubagents":null,"maxTotalSubagents":null,"idleTimeoutMillis":null,"runTimeoutMillis":null}}',
    gmt_modified = current_timestamp(3);
