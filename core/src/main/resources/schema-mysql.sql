create table if not exists workspace (
    id              bigint not null comment '主键',
    name            varchar(64) not null comment 'workspace 名称',
    settings_json   longtext not null comment 'workspace 设置 JSON',
    gmt_create      datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified    datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version         bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_workspace_name (name)
) engine=InnoDB default charset=utf8mb4 comment='workspace';

create table if not exists agent_provider (
    id              bigint not null comment '主键',
    workspace_id    bigint not null comment '所属 workspace',
    name            varchar(64) not null comment 'provider 名称',
    description     varchar(512) null comment '描述',
    provider_type   varchar(64) not null comment 'provider 类型',
    base_url        varchar(512) null comment '服务地址',
    credential      varchar(512) null comment '访问凭据',
    config_json     longtext not null comment 'provider 配置 JSON',
    gmt_create      datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified    datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version         bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_provider_workspace_name (workspace_id, name)
) engine=InnoDB default charset=utf8mb4 comment='agent provider';

create table if not exists agent_model (
    id                  bigint not null comment '主键',
    workspace_id        bigint not null comment '所属 workspace',
    provider_id         bigint not null comment '所属 provider',
    name                varchar(128) not null comment '模型名称',
    description         varchar(512) null comment '描述',
    capabilities_json   longtext not null comment '能力 JSON 数组',
    config_json         longtext not null comment '模型配置 JSON',
    gmt_create          datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified        datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version             bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_model_workspace_name (workspace_id, name)
) engine=InnoDB default charset=utf8mb4 comment='agent model';

create table if not exists agent_definition (
    id              bigint not null comment '主键',
    workspace_id    bigint not null comment '所属 workspace',
    name            varchar(64) not null comment 'agent 名称',
    description     varchar(512) null comment '描述',
    system_prompt   longtext null comment '系统提示词',
    model_id        bigint not null comment '默认 model',
    variant         varchar(64) not null comment '默认 variant',
    config_json     longtext not null comment 'Agent 配置 JSON',
    gmt_create      datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified    datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version         bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_definition_workspace_name (workspace_id, name)
) engine=InnoDB default charset=utf8mb4 comment='agent definition';

create table if not exists agent_session (
    id                     bigint not null comment '主键',
    session_id             varchar(64) not null comment 'session 业务 id',
    agent_id               bigint null comment '绑定的 agent id',
    agent_name             varchar(64) null comment '绑定的 agent 名称快照',
    title                  varchar(256) null comment 'session 标题',
    current_head_event_id  varchar(64) not null comment '当前默认 head event id',
    gmt_create             datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified           datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version                bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_session_session_id (session_id)
) engine=InnoDB default charset=utf8mb4 comment='agent session';

create table if not exists agent_session_event (
    id               bigint not null comment '主键',
    event_id         varchar(64) not null comment 'event 业务 id',
    session_id       varchar(64) not null comment 'session 业务 id',
    parent_event_id  varchar(64) not null comment '父 event id',
    run_id           varchar(64) null comment 'run 业务 id',
    event_type       varchar(64) not null comment '事件类型',
    payload_json     longtext not null comment 'payload JSON',
    gmt_create       datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified     datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version          bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_session_event_id (event_id)
) engine=InnoDB default charset=utf8mb4 comment='agent session event';

create table if not exists agent_run (
    id                bigint not null comment '主键',
    run_id            varchar(64) not null comment 'run 业务 id',
    session_id        varchar(64) not null comment 'session 业务 id',
    trigger_event_id  varchar(64) not null comment '触发 run 的 event id',
    status            varchar(32) not null comment 'run 状态',
    gmt_create        datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified      datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version           bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_run_run_id (run_id)
) engine=InnoDB default charset=utf8mb4 comment='agent run';

create table if not exists harness_session (
    id                    bigint not null comment '唯一业务与主键',
    workspace_id          bigint not null comment '所属 workspace',
    agent_definition_id   bigint null comment '创建时 agent definition',
    title                 varchar(256) null comment '标题',
    leaf_entry_id         bigint null comment '唯一活动 leaf',
    active_run_id         bigint null comment '活动 run',
    parent_session_id     bigint null comment '父 session',
    root_session_id       bigint not null comment '根 session',
    parent_invocation_id  bigint null comment '创建此 child 的 invocation',
    depth                 int not null comment 'subagent 深度',
    yolo_enabled          bit not null comment 'root YOLO 开关',
    gmt_create            datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified          datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version               bigint not null default '0' comment '数据版本号',
    primary key (id),
    key idx_harness_session_workspace (workspace_id),
    key idx_harness_session_root (root_session_id)
) engine=InnoDB default charset=utf8mb4 comment='harness session';

create table if not exists harness_session_entry (
    id               bigint not null comment '唯一业务与主键',
    session_id       bigint not null comment '所属 session',
    parent_entry_id  bigint null comment '父 entry',
    run_id           bigint null comment '写入 entry 的 run',
    entry_type       varchar(32) not null comment '语义 entry 类型',
    payload_json     longtext not null comment '语义 payload JSON',
    gmt_create       datetime(3) not null default current_timestamp(3) comment '创建时间',
    primary key (id),
    key idx_harness_session_entry_session_id (session_id, id),
    key idx_harness_session_entry_parent (parent_entry_id),
    key idx_harness_session_entry_run (run_id, id)
) engine=InnoDB default charset=utf8mb4 comment='harness session entry';
