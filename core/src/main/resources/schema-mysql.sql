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
    title                 varchar(256) null comment '标题',
    main_thread_id        bigint not null comment '稳定 Main Thread id',
    parent_session_id     bigint null comment '父 session',
    root_session_id       bigint not null comment '根 session',
    parent_invocation_id  bigint null comment '创建此 child 的 invocation',
    depth                 int not null comment 'subagent 深度',
    gmt_create            datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified          datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version               bigint not null default '0' comment '数据版本号',
    primary key (id),
    key idx_harness_session_root (root_session_id)
) engine=InnoDB default charset=utf8mb4 comment='harness session tree container';

create table if not exists harness_session_entry (
    id               bigint not null comment '唯一业务与主键',
    session_id       bigint not null comment '所属 session',
    parent_entry_id  bigint null comment '父 entry',
    entry_type       varchar(32) not null comment '语义 entry 类型',
    payload_json     longtext not null comment '语义 payload JSON',
    gmt_create       datetime(3) not null default current_timestamp(3) comment '创建时间',
    primary key (id),
    key idx_harness_session_entry_session_id (session_id, id),
    key idx_harness_session_entry_parent (parent_entry_id)
) engine=InnoDB default charset=utf8mb4 comment='harness session entry';

create table if not exists harness_thread (
    id                           bigint not null comment '业务主键',
    session_id                   bigint not null comment '所属 session tree',
    head_entry_id                bigint not null comment '当前 tree cursor',
    status                       varchar(32) not null comment 'IDLE/RUNNING/WAITING/FAILED/RETRYING',
    input_sequence               bigint not null comment '已分配 input sequence 最大值',
    active_agent_definition_id   bigint null comment '当前 AgentDefinition id',
    active_agent_name            varchar(256) null comment '捕获的 Agent 名称',
    model_id                     varchar(128) null comment 'Thread 级 model id',
    variant                      varchar(128) null comment 'Thread 级 model variant',
    yolo_enabled                 tinyint(1) not null default 0 comment 'Thread 级 YOLO',
    processor_token              varchar(128) null comment '当前 processor fencing token',
    processor_until              datetime(3) null comment 'token 租约截止',
    gmt_create                   datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified                 datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version                      bigint not null default '0' comment '乐观锁版本',
    primary key (id),
    key idx_harness_thread_session (session_id, id),
    key idx_harness_thread_status (status, id)
) engine=InnoDB default charset=utf8mb4 comment='durable agent thread cursor';

create table if not exists harness_thread_input (
    id                    bigint not null comment '主键',
    thread_id             bigint not null comment '所属 thread',
    sequence              bigint not null comment 'thread 内有序序号',
    input_type            varchar(32) not null comment 'USER_MESSAGE/CUSTOM_MESSAGE/SET_*',
    payload_json          longtext not null comment '输入 payload JSON',
    client_message_id     varchar(128) not null comment '客户端幂等键',
    status                varchar(32) not null comment 'QUEUED/APPLIED/CANCELLED',
    applied_entry_id      bigint null comment '应用后产生的 entry',
    resolved_at           datetime(3) null comment '应用或取消时间',
    cancelled_by_stop_id  bigint null comment '取消该 input 的 stop id',
    gmt_create            datetime(3) not null default current_timestamp(3) comment '创建时间',
    primary key (id),
    unique key uk_harness_thread_input_sequence (thread_id, sequence),
    unique key uk_harness_thread_input_client (thread_id, client_message_id),
    key idx_harness_thread_input_pending (thread_id, status, sequence)
) engine=InnoDB default charset=utf8mb4 comment='ordered thread input queue';

create table if not exists harness_thread_stop (
    id                 bigint not null comment '主键',
    thread_id          bigint not null comment '所属 thread',
    client_request_id  varchar(128) not null comment 'Stop 幂等键',
    gmt_create         datetime(3) not null default current_timestamp(3) comment '创建时间',
    primary key (id),
    unique key uk_harness_thread_stop_client (thread_id, client_request_id),
    key idx_harness_thread_stop_thread (thread_id, id)
) engine=InnoDB default charset=utf8mb4 comment='thread stop receipt';

create table if not exists harness_thread_event (
    id                 bigint not null comment '全局事件 id / SSE cursor',
    thread_id          bigint not null comment '所属 thread',
    subject_entry_id   bigint null comment '关联 entry（如 planned assistant）',
    event_type         varchar(64) not null comment '事件类型',
    payload_json       longtext not null comment '含 schemaVersion 的 payload',
    gmt_create         datetime(3) not null default current_timestamp(3) comment '创建时间',
    primary key (id),
    key idx_harness_thread_event_thread (thread_id, id)
) engine=InnoDB default charset=utf8mb4 comment='thread event journal';

create table if not exists tool_invocation (
    id                    bigint not null comment '唯一 Snowflake 主键与执行幂等键',
    thread_id             bigint not null comment '所属 thread',
    assistant_entry_id    bigint not null comment '产生调用的 Assistant Entry',
    ordinal               int not null comment 'Assistant source order',
    tool_call_id          varchar(256) not null comment 'Provider tool call id',
    tool_name             varchar(128) not null comment '冻结工具名称',
    tool_version          varchar(128) not null comment '冻结工具版本',
    target_type           varchar(32) not null comment '工具执行目标类型',
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
    unique key uk_tool_invocation_thread_call (thread_id, tool_call_id),
    unique key uk_tool_invocation_assistant_ordinal (assistant_entry_id, ordinal),
    key idx_tool_invocation_thread_status (thread_id, status, ordinal),
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
    parent_thread_id      bigint not null,
    root_thread_id        bigint not null comment 'delegation tree root user thread',
    child_session_id      bigint not null,
    child_thread_id       bigint not null,
    target_agent          varchar(128) not null,
    working_copy_policy   varchar(32) not null,
    working_copy_revision varchar(512),
    max_turns             int not null,
    status                varchar(32) not null,
    report_json           text,
    gmt_create            datetime(3) not null default current_timestamp(3),
    gmt_modified         datetime(3) not null default current_timestamp(3),
    primary key (parent_invocation_id),
    unique key uk_harness_subagent_task_child_thread (child_thread_id),
    key idx_harness_subagent_task_parent (parent_session_id, status)
) engine=InnoDB default charset=utf8mb4 comment='durable task invocation to child thread relation';

create table if not exists model_usage_record (
    id                                 bigint not null comment 'Snowflake ledger id',
    session_id                         bigint not null comment '所属 session',
    thread_id                          bigint not null comment '所属 thread',
    assistant_entry_id                 bigint not null comment '产生账本的 Assistant Entry (唯一)',
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
    key idx_model_usage_record_thread (thread_id, id),
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

create table if not exists canvas_document (
    id                  bigint not null comment '主键',
    workspace_id        bigint not null comment '工作区',
    title               varchar(256) not null comment '标题',
    schema_version      int not null comment 'schema 版本',
    revision            bigint not null comment '业务 revision',
    lifecycle           varchar(32) not null comment '生命周期',
    home_viewport_json  longtext not null comment '默认视口 JSON',
    gmt_create          datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified        datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version             bigint not null default '0' comment '行版本',
    primary key (id),
    key idx_canvas_document_workspace (workspace_id, gmt_modified)
) engine=InnoDB default charset=utf8mb4 comment='canvas 文档';

create table if not exists canvas_node (
    id                  bigint not null comment '主键',
    canvas_id           bigint not null comment '画布',
    kind                varchar(32) not null comment 'RESOURCE/FUNCTION/GROUP',
    node_type           varchar(128) not null comment '节点类型',
    node_type_version   int not null comment '类型版本',
    name                varchar(256) not null comment '名称',
    parent_group_id     bigint null comment '父 Group',
    x                   double not null comment 'x',
    y                   double not null comment 'y',
    width               double not null comment '宽',
    height              double not null comment '高',
    rotation            double not null comment '旋转',
    z_index             bigint not null comment '层级',
    locked              tinyint(1) not null comment '锁定',
    hidden              tinyint(1) not null comment '隐藏',
    validity            varchar(32) not null comment '有效性',
    data_json           longtext not null comment '子类型数据',
    revision            bigint not null comment '节点 revision',
    gmt_deleted         datetime(3) null comment '删除时间',
    gmt_create          datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified        datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version             bigint not null default '0' comment '行版本',
    primary key (id),
    key idx_canvas_node_canvas (canvas_id, gmt_deleted)
) engine=InnoDB default charset=utf8mb4 comment='canvas 节点';

create table if not exists canvas_link (
    id                  bigint not null comment '主键',
    canvas_id           bigint not null comment '画布',
    source_node_id      bigint not null comment '源节点',
    target_node_id      bigint not null comment '目标节点',
    revision            bigint not null comment 'revision',
    gmt_create          datetime(3) not null default current_timestamp(3) comment '创建时间',
    primary key (id),
    unique key uk_canvas_link (canvas_id, source_node_id, target_node_id)
) engine=InnoDB default charset=utf8mb4 comment='canvas 可见 Link';

create table if not exists canvas_command (
    id                  bigint not null comment '主键',
    command_id          varchar(128) not null comment '客户端幂等键',
    workspace_id        bigint not null comment '工作区',
    canvas_id           bigint not null comment '画布',
    base_revision       bigint not null comment '基线 revision',
    result_revision     bigint not null comment '结果 revision',
    request_hash        varchar(128) not null comment '请求哈希',
    payload_json        longtext not null comment '命令载荷',
    result_json         longtext not null comment '结果快照摘要',
    gmt_create          datetime(3) not null default current_timestamp(3) comment '创建时间',
    primary key (id),
    unique key uk_canvas_command (workspace_id, command_id)
) engine=InnoDB default charset=utf8mb4 comment='canvas 幂等命令';
