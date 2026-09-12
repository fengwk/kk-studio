-- Remove the product-level Workspace state and convert durable Harness JSON to the
-- direct EnvironmentId shape consumed by the strict runtime codecs.

do $$
begin
    if exists (
        select 1
        from harness_model_invocation
        where status in ('READY', 'DISPATCHING', 'RUNNING')
    ) then
        raise exception 'cannot remove workspace while model invocations are active';
    end if;

    if exists (
        select 1
        from harness_tool_invocation
        where status in ('WAITING_APPROVAL', 'READY', 'DISPATCHING', 'RUNNING')
    ) then
        raise exception 'cannot remove workspace while tool invocations are active';
    end if;
end
$$;

do $$
begin
    if exists (
        select 1
        from harness_entry
        where entry_type in ('ROOT', 'TURN_START')
          and (
              jsonb_typeof(payload -> 'settings') is distinct from 'object'
              or not ((payload -> 'settings') ? 'workspacePath')
          )
    ) then
        raise exception 'cannot migrate malformed branch settings';
    end if;

    if exists (
        select 1
        from harness_model_invocation
        where jsonb_typeof(request_spec -> 'toolBindings') is distinct from 'array'
           or jsonb_typeof(request_spec -> 'skillBindings') is distinct from 'array'
    ) then
        raise exception 'cannot migrate malformed model request bindings';
    end if;

    if exists (
        select 1
        from harness_model_invocation as invocation
        cross join lateral jsonb_array_elements(
            invocation.request_spec -> 'toolBindings'
        ) as binding(value)
        where jsonb_typeof(binding.value) is distinct from 'object'
           or not (binding.value ? 'environment')
           or binding.value ? 'environmentId'
           or (
               binding.value -> 'environment' <> 'null'::jsonb
               and (
                   jsonb_typeof(binding.value -> 'environment') is distinct from 'object'
                   or not (
                       (binding.value -> 'environment')
                           ?& array['environmentId', 'workspacePath']
                   )
                   or (binding.value -> 'environment')
                       - 'environmentId'
                       - 'workspacePath' <> '{}'::jsonb
                   or jsonb_typeof(
                       binding.value #> '{environment,environmentId}'
                   ) is distinct from 'string'
                   or jsonb_typeof(
                       binding.value #> '{environment,workspacePath}'
                   ) is distinct from 'string'
               )
           )
    ) then
        raise exception 'cannot migrate malformed model tool binding';
    end if;

    if exists (
        select 1
        from harness_model_invocation as invocation
        cross join lateral jsonb_array_elements(
            invocation.request_spec -> 'skillBindings'
        ) as binding(value)
        where jsonb_typeof(binding.value) is distinct from 'object'
           or not (binding.value ? 'sourceEnvironment')
           or binding.value ? 'sourceEnvironmentId'
           or (
               binding.value -> 'sourceEnvironment' <> 'null'::jsonb
               and (
                   jsonb_typeof(
                       binding.value -> 'sourceEnvironment'
                   ) is distinct from 'object'
                   or not (
                       (binding.value -> 'sourceEnvironment')
                           ?& array['environmentId', 'workspacePath']
                   )
                   or (binding.value -> 'sourceEnvironment')
                       - 'environmentId'
                       - 'workspacePath' <> '{}'::jsonb
                   or jsonb_typeof(
                       binding.value #> '{sourceEnvironment,environmentId}'
                   ) is distinct from 'string'
                   or jsonb_typeof(
                       binding.value #> '{sourceEnvironment,workspacePath}'
                   ) is distinct from 'string'
               )
           )
    ) then
        raise exception 'cannot migrate malformed model skill binding';
    end if;

    if exists (
        select 1
        from harness_tool_invocation
        where binding is not null
          and (
              jsonb_typeof(binding) is distinct from 'object'
              or not (binding ? 'environment')
              or binding ? 'environmentId'
              or (
                  binding -> 'environment' <> 'null'::jsonb
                  and (
                      jsonb_typeof(binding -> 'environment') is distinct from 'object'
                      or not (
                          (binding -> 'environment')
                              ?& array['environmentId', 'workspacePath']
                      )
                      or (binding -> 'environment')
                          - 'environmentId'
                          - 'workspacePath' <> '{}'::jsonb
                      or jsonb_typeof(
                          binding #> '{environment,environmentId}'
                      ) is distinct from 'string'
                      or jsonb_typeof(
                          binding #> '{environment,workspacePath}'
                      ) is distinct from 'string'
                  )
              )
          )
    ) then
        raise exception 'cannot migrate malformed tool invocation binding';
    end if;

    if exists (
        select 1
        from system_setting
        where jsonb_typeof(config -> 'environment') is distinct from 'object'
           or not ((config -> 'environment') ? 'directoryListTimeoutMillis')
    ) then
        raise exception 'cannot migrate malformed environment settings';
    end if;
end
$$;

update harness_entry
set payload = jsonb_set(
    payload,
    '{settings}',
    (payload -> 'settings') - 'workspacePath'
)
where entry_type in ('ROOT', 'TURN_START');

update harness_model_invocation as invocation
set request_spec = jsonb_set(
    jsonb_set(
        invocation.request_spec,
        '{toolBindings}',
        (
            select coalesce(
                jsonb_agg(
                    (binding.value - 'environment')
                    || jsonb_build_object(
                        'environmentId',
                        coalesce(
                            binding.value #> '{environment,environmentId}',
                            'null'::jsonb
                        )
                    )
                    order by binding.ordinality
                ),
                '[]'::jsonb
            )
            from jsonb_array_elements(
                invocation.request_spec -> 'toolBindings'
            ) with ordinality as binding(value, ordinality)
        )
    ),
    '{skillBindings}',
    (
        select coalesce(
            jsonb_agg(
                (binding.value - 'sourceEnvironment')
                || jsonb_build_object(
                    'sourceEnvironmentId',
                    coalesce(
                        binding.value #> '{sourceEnvironment,environmentId}',
                        'null'::jsonb
                    )
                )
                order by binding.ordinality
            ),
            '[]'::jsonb
        )
        from jsonb_array_elements(
            invocation.request_spec -> 'skillBindings'
        ) with ordinality as binding(value, ordinality)
    )
);

update harness_tool_invocation
set binding = (binding - 'environment')
    || jsonb_build_object(
        'environmentId',
        coalesce(binding #> '{environment,environmentId}', 'null'::jsonb)
    )
where binding is not null;

delete from harness_thread_command
where command_type = 'SET_ENVIRONMENT';

alter table harness_thread_command
    drop constraint ck_harness_thread_command_type;

alter table harness_thread_command
    add constraint ck_harness_thread_command_type check (
        command_type in (
            'USER_MESSAGE',
            'CUSTOM_MESSAGE',
            'SET_AGENT',
            'SET_MODEL'
        )
    );

update system_setting
set config = jsonb_set(
    config,
    '{environment}',
    (config -> 'environment') - 'directoryListTimeoutMillis'
);

drop table environment_directory_query;

alter table chat
    drop column workspace_path;
