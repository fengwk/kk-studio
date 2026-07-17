create table if not exists agent_provider (
    id              bigint not null comment '主键',
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
    unique key uk_agent_provider_name (name)
) engine=InnoDB default charset=utf8mb4 comment='agent provider';

create table if not exists agent_model (
    id                  bigint not null comment '主键',
    provider_id         bigint not null comment '所属 provider',
    name                varchar(128) not null comment '模型名称',
    description         varchar(512) null comment '描述',
    capabilities_json   longtext not null comment '能力 JSON 数组',
    config_json         longtext not null comment '模型配置 JSON',
    gmt_create          datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified        datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version             bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_model_name (name)
) engine=InnoDB default charset=utf8mb4 comment='agent model';

create table if not exists agent_definition (
    id              bigint not null comment '主键',
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
    unique key uk_agent_definition_name (name)
) engine=InnoDB default charset=utf8mb4 comment='agent definition';

create table if not exists harness_session (
    id                    bigint not null comment '唯一业务与主键',
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

create table if not exists harness_run (
    id                  bigint not null comment '唯一业务与主键',
    session_id          bigint not null comment '所属 session',
    trigger_entry_id    bigint not null comment '触发 user entry',
    status              varchar(32) not null comment 'durable run 状态',
    turn_index          int not null comment '已完成 turn 数',
    attempt             int not null comment '已 claim attempt 数',
    event_sequence      bigint not null comment '最后分配的 event sequence',
    lease_owner         varchar(128) null comment '当前 worker',
    lease_until         datetime(3) null comment 'lease 截止时间',
    next_attempt_at     datetime(3) not null comment '下次可 claim 时间',
    cancel_requested_at datetime(3) null comment '取消请求时间',
    gmt_create          datetime(3) not null default current_timestamp(3) comment '创建时间',
    started_at          datetime(3) null comment '首次开始时间',
    finished_at         datetime(3) null comment '终态时间',
    gmt_modified        datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    primary key (id),
    key idx_harness_run_claim (status, next_attempt_at, lease_until, id)
) engine=InnoDB default charset=utf8mb4 comment='harness durable run';

create table if not exists harness_run_event (
    id           bigint not null comment '唯一业务与主键',
    run_id       bigint not null comment '所属 run',
    sequence     bigint not null comment 'run 内线性 sequence',
    event_type   varchar(64) not null comment '事件类型',
    payload_json longtext not null comment '含 schemaVersion 的 payload',
    gmt_create   datetime(3) not null default current_timestamp(3) comment '创建时间',
    primary key (id),
    unique key uk_harness_run_event_sequence (run_id, sequence),
    key idx_harness_run_event_run (run_id, id)
) engine=InnoDB default charset=utf8mb4 comment='harness run event journal';

create table if not exists tool_invocation (
    id                    bigint not null comment '唯一 Snowflake 主键与执行幂等键',
    run_id                bigint not null comment '所属 run',
    assistant_entry_id    bigint not null comment '产生调用的 Assistant Entry',
    ordinal               int not null comment 'Assistant source order',
    tool_call_id          varchar(256) not null comment 'Provider tool call id',
    tool_name             varchar(128) not null comment '冻结工具名称',
    tool_version          varchar(128) not null comment '冻结工具版本',
    target_type           varchar(32) not null comment 'CONTROL/CLOUD/ENVIRONMENT',
    environment_id        bigint null comment 'ENVIRONMENT 目标 id；其他类型为空',
    arguments_json        longtext not null comment 'interceptor 后参数 JSON',
    status                varchar(32) not null comment '持久状态',
    permission_action     varchar(16) not null comment 'ALLOW/ASK/DENY',
    permission_decision   varchar(16) null comment '用户 ALLOW/DENY 决定',
    side_effect           varchar(32) not null comment '冻结 ToolSideEffect',
    deadline_at           datetime(3) not null comment '冻结 deadline',
    lease_owner           varchar(128) null comment '执行 lease owner',
    lease_until           datetime(3) null comment '执行 lease 截止',
    cancel_requested_at   datetime(3) null comment '取消请求时间',
    result_json           longtext null comment '确定性或执行 ToolResult JSON',
    error_message         longtext null comment '错误摘要',
    gmt_create            datetime(3) not null default current_timestamp(3) comment '创建时间',
    started_at            datetime(3) null comment '开始时间',
    finished_at           datetime(3) null comment '终态时间',
    gmt_modified          datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    primary key (id),
    unique key uk_tool_invocation_run_call (run_id, tool_call_id),
    unique key uk_tool_invocation_assistant_ordinal (assistant_entry_id, ordinal),
    key idx_tool_invocation_run_status (run_id, status, ordinal),
    key idx_tool_invocation_claim (target_type, status, deadline_at, id),
    key idx_tool_invocation_environment_claim (environment_id, status, deadline_at, id)
) engine=InnoDB default charset=utf8mb4 comment='durable tool invocation';

create table if not exists tool_artifact (
    id           bigint not null comment 'Snowflake globally unique primary key',
    media_type   varchar(128) not null comment 'RFC media type',
    encoding     varchar(64) not null comment 'content encoding',
    content      longblob not null comment 'immutable complete output',
    size_bytes   bigint not null comment 'content bytes',
    sha256       varchar(64) not null comment 'content digest',
    gmt_create   datetime(3) not null default current_timestamp(3) comment 'creation timestamp',
    primary key (id)
) engine=InnoDB default charset=utf8mb4 comment='globally addressable tool output artifact';

create table if not exists harness_subagent_task (
    parent_invocation_id  bigint not null comment 'task ToolInvocation idempotency key',
    parent_session_id     bigint not null,
    child_session_id      bigint not null,
    child_run_id          bigint not null,
    target_agent          varchar(128) not null,
    working_copy_policy   varchar(32) not null,
    working_copy_revision varchar(512),
    max_turns             int not null,
    idle_timeout_millis   bigint,
    status                varchar(32) not null,
    report_json           text,
    gmt_create            datetime(3) not null default current_timestamp(3),
    gmt_modified         datetime(3) not null default current_timestamp(3),
    primary key (parent_invocation_id),
    key idx_harness_subagent_task_child (child_session_id, child_run_id),
    key idx_harness_subagent_task_parent (parent_session_id, status)
) engine=InnoDB default charset=utf8mb4 comment='durable task invocation to child run relation';

create table if not exists harness_run_control_message (
    id                 bigint not null comment 'Snowflake control id (独立 namespace)',
    session_id         bigint not null comment 'target session',
    run_id             bigint null comment 'active run when accepted; null when FOLLOW_UP 直接 promotion',
    control_kind       varchar(16) not null comment 'STEER/FOLLOW_UP',
    consumption_mode   varchar(32) not null comment 'frozen ONE_AT_A_TIME/ALL snapshot',
    message_json       longtext not null comment 'encoded USER AgentMessage via SessionEntryJsonCodec',
    status             varchar(16) not null comment 'PENDING/CONSUMED/CLEARED/PROMOTED',
    consumed_run_id    bigint null comment 'CONSUMED/PROMOTED 写入的目标 run id',
    consumed_entry_id  bigint null comment 'consumed session entry id (CONSUMED/PROMOTED only)',
    gmt_create         datetime(3) not null default current_timestamp(3) comment '入库时间',
    consumed_at        datetime(3) null comment 'PENDING->CLEARED/CONSUMED/PROMOTED 时间',
    gmt_modified       datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    primary key (id),
    key idx_harness_control_pending (run_id, control_kind, status, id),
    key idx_harness_control_session (session_id, status, id)
) engine=InnoDB default charset=utf8mb4 comment='durable run control queue: steer/follow-up + frozen policy';

create table if not exists model_usage_record (
    id                                 bigint not null comment 'Snowflake ledger id (独立 namespace)',
    session_id                         bigint not null comment '所属 session',
    run_id                             bigint not null comment '所属 run',
    assistant_entry_id                 bigint not null comment '产生账本的 Assistant Entry (唯一)',
    attempt                            int not null comment 'Assistant Entry 写入时的 attempt',
    turn_index                         int not null comment 'Assistant Entry 写入时的 turnIndex',
    provider_resource_id               bigint not null comment 'provider resource id 冻结',
    model_resource_id                  bigint not null comment 'model resource id 冻结',
    provider_type                      varchar(64) not null comment 'Provider 类型冻结',
    provider_model_id                  varchar(256) not null comment 'Provider 模型 id 冻结',
    prompt_cache_mode                  varchar(32) not null comment 'PromptCacheMode 冻结',
    prompt_cache_retention             varchar(16) not null comment 'PromptCacheRetention 冻结',
    cache_eligible                     tinyint(1) not null comment '缓存可计费事实',
    cache_affinity_key                 varchar(512) null comment '缓存亲和 key (NONE 时为 null)',
    stop_reason                        varchar(32) not null comment 'ProviderStopReason 冻结',
    usage_input_tokens                 bigint not null comment 'input tokens 冻结',
    usage_output_tokens                bigint not null comment 'output tokens 冻结',
    usage_cache_read_tokens            bigint not null comment 'cache read tokens 冻结',
    usage_cache_write_tokens           bigint not null comment 'cache write tokens 冻结',
    usage_cache_write_long_tokens      bigint not null comment 'cache write long tokens 冻结',
    usage_reasoning_tokens             bigint not null comment 'reasoning tokens 冻结',
    usage_provider_total_tokens        bigint not null comment 'Provider 报告总 tokens',
    cost_currency                      varchar(16) not null comment '成本币种',
    cost_input                         decimal(32,12) not null comment 'input 成本',
    cost_output                        decimal(32,12) not null comment 'output 成本',
    cost_cache_read                    decimal(32,12) not null comment 'cache read 成本',
    cost_cache_write                   decimal(32,12) not null comment 'cache write 成本',
    cost_cache_write_long              decimal(32,12) not null comment 'cache write long 成本',
    cost_reasoning                     decimal(32,12) not null comment 'reasoning 成本',
    cost_total                         decimal(32,12) not null comment '六分项之和',
    pricing_currency                   varchar(16) not null comment 'pricing 币种',
    pricing_tier                       varchar(64) not null comment 'pricing tier 冻结',
    pricing_service_tier               varchar(64) not null comment 'service tier 冻结',
    pricing_service_tier_multiplier    decimal(32,12) not null comment 'service tier multiplier',
    pricing_version                    varchar(64) not null comment 'pricing version 冻结',
    pricing_input_per_million_tokens   decimal(32,12) not null comment 'input 单价',
    pricing_output_per_million_tokens  decimal(32,12) not null comment 'output 单价',
    pricing_cache_read_per_million_tokens  decimal(32,12) not null comment 'cache read 单价',
    pricing_cache_write_per_million_tokens decimal(32,12) not null comment 'cache write 单价',
    pricing_cache_write_long_per_million_tokens decimal(32,12) not null comment 'cache write long 单价',
    pricing_reasoning_per_million_tokens decimal(32,12) not null comment 'reasoning 单价',
    request_id                         varchar(256) null comment 'Provider 报告的 request id',
    reported_service_tier              varchar(64) null comment 'Provider 报告的 service tier',
    raw_usage_json                     longtext not null comment 'Provider 原始 usage JSON',
    gmt_create                         datetime(3) not null default current_timestamp(3) comment '入库时间',
    primary key (id),
    unique key uk_model_usage_record_assistant_entry (assistant_entry_id),
    unique key uk_model_usage_record_run_attempt_turn (run_id, attempt, turn_index),
    key idx_model_usage_record_run (run_id, id),
    key idx_model_usage_record_session (session_id, id),
    key idx_model_usage_record_model (model_resource_id, id)
) engine=InnoDB default charset=utf8mb4 comment='durable model usage ledger: per Assistant Entry 唯一一行';

create table if not exists comfyui_workflow_api (
    id                    bigint not null comment '主键',
    api_name              varchar(64) not null comment '对外 api 名（小写字母开头、仅含 [a-z0-9-]）',
    name                  varchar(128) not null comment '展示名',
    description           varchar(512) comment '描述',
    workflow_json         longtext not null comment 'ComfyUI API 格式 workflow JSON',
    input_bindings_json   longtext not null comment '输入绑定 JSON 数组',
    default_selector      varchar(1024) comment '默认 JSONPath selector',
    enabled               tinyint(1) not null comment '是否启用',
    gmt_create            datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified          datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version               bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_comfyui_workflow_api_api_name (api_name)
) engine=InnoDB default charset=utf8mb4 comment='comfyui workflow api card';

create table if not exists tool_environment (
    id                    bigint not null comment 'Snowflake 主键；Environment 全局资源，不属于 Workspace/Tenant',
    name                  varchar(128) not null comment 'Environment 唯一名',
    description           varchar(512) null comment '描述',
    capabilities_json     longtext not null comment 'canonical Daemon CAPABILITIES payload',
    last_seen_at          datetime(3) null comment '最近一次 daemon 主动上报时间',
    gmt_create            datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified          datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version               bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_tool_environment_name (name)
) engine=InnoDB default charset=utf8mb4 comment='global environment daemon registry';
