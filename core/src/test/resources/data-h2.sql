merge into agent_provider (
    id, name, description, provider_type, base_url, api_key,
    timeout_millis, gmt_create, gmt_modified, version
) key (id) values (
    1, 'stub', 'Local deterministic provider for development and acceptance tests.',
    'openai', 'http://stub.local/v1', 'stub-key',
    60000,
    current_timestamp(), current_timestamp(), 0
);

merge into agent_model (
    id, provider_id, name, description,
    default_variant, variants_json, gmt_create, gmt_modified, version
) key (id) values (
    1, 1, 'acceptance-stub',
    'Local deterministic model for development and acceptance tests.',
    'default', '[{"name":"default","temperature":0.2,"maxOutputTokens":4096}]',
    current_timestamp(), current_timestamp(), 0
);

merge into agent_definition (
    id, name, description, system_prompt,
    default_provider_id, default_model_id, default_variant,
    tools_json, gmt_create, gmt_modified, version
) key (id) values (
    1, 'default-assistant', 'Acceptance stub agent for local studio pages.',
    'You are the local acceptance stub assistant for kk-studio.',
    1, 1, 'default',
    '[]',
    current_timestamp(), current_timestamp(), 0
);
