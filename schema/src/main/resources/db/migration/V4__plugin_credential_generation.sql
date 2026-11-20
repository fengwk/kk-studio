alter table plugin_credential
    add column generation uuid not null default gen_random_uuid();

comment on column plugin_credential.generation is '凭据行生命周期标识：断连后重建取新值，调用期拒绝匹配代号与版本';
