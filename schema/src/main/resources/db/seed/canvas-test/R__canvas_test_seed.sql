-- Docker Canvas test stack 的启动快照：只覆盖该环境需要启用或缩短的非敏感 system settings。
update system_setting
set config =
    jsonb_set(
        config,
        '{storageMedia}',
        config -> 'storageMedia'
            || '{"uploadExpiresSeconds":900}'::jsonb
    )
where id = 1;
