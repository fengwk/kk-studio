create table if not exists agent_provider (
    id                          bigint not null comment '主键',
    name                        varchar(64) not null comment 'provider 名称',
    description                 varchar(512) null comment '描述',
    provider_type               varchar(64) not null comment 'provider 类型',
    base_url                    varchar(512) null comment '服务地址',
    api_key                     varchar(512) null comment '访问凭据',
    timeout_millis              bigint null comment '请求超时毫秒',
    gmt_create                  datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified                datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version                     bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_provider_name (name)
) engine=InnoDB default charset=utf8mb4 comment='agent provider';

create table if not exists agent_model (
    id                bigint not null comment '主键',
    provider_id       bigint unsigned not null comment '所属 provider id',
    name              varchar(128) not null comment '模型名称',
    description       varchar(512) null comment '描述',
    default_variant   varchar(64) not null comment '默认 variant',
    variants_json     longtext not null comment 'variants JSON 数组',
    gmt_create        datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified      datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version           bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_model_provider_name (provider_id, name)
) engine=InnoDB default charset=utf8mb4 comment='agent model';

create table if not exists agent_definition (
    id                    bigint not null comment '主键',
    name                  varchar(64) not null comment 'agent 名称',
    description           varchar(512) null comment '描述',
    system_prompt         longtext null comment '系统提示词',
    default_provider_id   bigint unsigned not null comment '默认 provider id',
    default_model_id      bigint unsigned not null comment '默认 model id',
    default_variant       varchar(64) not null comment '默认 variant',
    tools_json            longtext null comment '工具名称 JSON 数组',
    gmt_create            datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified          datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version               bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_definition_name (name)
) engine=InnoDB default charset=utf8mb4 comment='agent definition';

create table if not exists agent_session (
    id                     bigint not null comment '主键',
    session_id             varchar(64) not null comment 'session 业务 id',
    agent_id               bigint unsigned null comment '绑定的 agent id',
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
