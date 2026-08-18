-- Docker Canvas test stack 的启动快照：只覆盖该环境需要启用或缩短的非敏感 system settings。
update system_setting
set config =
    jsonb_set(
        jsonb_set(
            jsonb_set(
                jsonb_set(
                    jsonb_set(
                        config,
                        '{storageMedia}',
                        config -> 'storageMedia'
                            || '{"s3Enabled":true,"uploadExpiresSeconds":900}'::jsonb
                    ),
                    '{advanced}',
                    config -> 'advanced'
                        || '{"canvasFunctionExecutorCoreSize":1,"canvasFunctionExecutorMaxSize":2,"canvasFunctionExecutorQueueCapacity":8}'::jsonb
                ),
                '{integrations,openCliHub}',
                config #> '{integrations,openCliHub}'
                    || '{"enabled":true,"baseUrl":"http://opencli-hub:8080"}'::jsonb
            ),
            '{integrations,gptImage2}',
            config #> '{integrations,gptImage2}'
                || '{"paidEnabled":true}'::jsonb
        ),
        '{integrations,seedance}',
        config #> '{integrations,seedance}'
            || '{"enabled":true,"workspaceId":"mock-workspace","statusPollIntervalMillis":10,"maxWaitMillis":30000}'::jsonb
    )
where id = 1;
