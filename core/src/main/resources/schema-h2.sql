create table if not exists agent_provider (
    id              bigint not null,
    name            varchar(64) not null,
    description     varchar(512),
    provider_type   varchar(64) not null,
    base_url        varchar(512),
    credential      varchar(512),
    config_json     text not null,
    gmt_create      timestamp(3) not null default current_timestamp(),
    gmt_modified    timestamp(3) not null default current_timestamp(),
    version         bigint not null default 0,
    primary key (id),
    unique (name)
);

create table if not exists agent_model (
    id                  bigint not null,
    provider_id         bigint not null,
    name                varchar(128) not null,
    description         varchar(512),
    capabilities_json   text not null,
    config_json         text not null,
    gmt_create          timestamp(3) not null default current_timestamp(),
    gmt_modified        timestamp(3) not null default current_timestamp(),
    version             bigint not null default 0,
    primary key (id),
    unique (name)
);

create table if not exists agent_definition (
    id              bigint not null,
    name            varchar(64) not null,
    description     varchar(512),
    system_prompt   text,
    model_id        bigint not null,
    variant         varchar(64) not null,
    config_json     text not null,
    gmt_create      timestamp(3) not null default current_timestamp(),
    gmt_modified    timestamp(3) not null default current_timestamp(),
    version         bigint not null default 0,
    primary key (id),
    unique (name)
);

create table if not exists harness_session (
    id                    bigint not null,
    agent_definition_id   bigint,
    title                 varchar(256),
    leaf_entry_id         bigint,
    active_run_id         bigint,
    parent_session_id     bigint,
    root_session_id       bigint not null,
    parent_invocation_id  bigint,
    depth                 integer not null,
    yolo_enabled          boolean not null,
    gmt_create            timestamp(3) not null default current_timestamp(),
    gmt_modified          timestamp(3) not null default current_timestamp(),
    version               bigint not null default 0,
    primary key (id)
);

create index if not exists idx_harness_session_root on harness_session (root_session_id);

create table if not exists harness_session_entry (
    id               bigint not null,
    session_id       bigint not null,
    parent_entry_id  bigint,
    run_id           bigint,
    entry_type       varchar(32) not null,
    payload_json     text not null,
    gmt_create       timestamp(3) not null default current_timestamp(),
    primary key (id)
);

create index if not exists idx_harness_session_entry_session_id
    on harness_session_entry (session_id, id);
create index if not exists idx_harness_session_entry_parent on harness_session_entry (parent_entry_id);
create index if not exists idx_harness_session_entry_run on harness_session_entry (run_id, id);

-- durable Agent 执行单元：排队 / 租约 claim / 多 turn / 终态均可恢复
create table if not exists harness_run (
    id                  bigint not null,                 -- 业务主键
    session_id          bigint not null,                 -- 所属 session
    trigger_entry_id    bigint not null,                 -- 触发本 run 的 entry（通常 USER）
    status              varchar(32) not null,            -- QUEUED/RUNNING/WAITING_TOOLS/SUCCEEDED/FAILED/CANCELLED
    turn_index          integer not null,                -- 同 run 内 turn 序号
    attempt             integer not null,                -- claim 次数（与 lease CAS）
    event_sequence      bigint not null,                 -- 已分配 run event 最大 sequence
    lease_owner         varchar(128),                    -- 当前 worker；仅 RUNNING 非空
    lease_until         timestamp(3),                    -- 租约截止；过期可 reclaim
    next_attempt_at     timestamp(3) not null,           -- 下次可 claim 时间
    cancel_requested_at timestamp(3),                    -- abort 请求时间
    gmt_create          timestamp(3) not null default current_timestamp(), -- 创建时间
    started_at          timestamp(3),                    -- 首次开始时间
    finished_at         timestamp(3),                    -- 终态时间
    gmt_modified        timestamp(3) not null default current_timestamp(), -- 更新时间
    primary key (id)
);

create index if not exists idx_harness_run_claim
    on harness_run (status, next_attempt_at, lease_until, id);

create table if not exists harness_run_event (
    id           bigint not null,
    run_id       bigint not null,
    sequence     bigint not null,
    event_type   varchar(64) not null,
    payload_json text not null,
    gmt_create   timestamp(3) not null default current_timestamp(),
    primary key (id),
    unique (run_id, sequence)
);

create index if not exists idx_harness_run_event_run on harness_run_event (run_id, id);

create table if not exists tool_invocation (
    id                    bigint not null,
    run_id                bigint not null,
    assistant_entry_id    bigint not null,
    ordinal               integer not null,
    tool_call_id          varchar(256) not null,
    tool_name             varchar(128) not null,
    tool_version          varchar(128) not null,
    target_type           varchar(32) not null,
    environment_id        bigint,
    arguments_json        text not null,
    status                varchar(32) not null,
    permission_action     varchar(16) not null,
    permission_decision   varchar(16),
    side_effect           varchar(32) not null,
    deadline_at           timestamp(3) not null,
    lease_owner           varchar(128),
    lease_until           timestamp(3),
    cancel_requested_at   timestamp(3),
    result_json           text,
    error_message         text,
    gmt_create            timestamp(3) not null default current_timestamp(),
    started_at            timestamp(3),
    finished_at           timestamp(3),
    gmt_modified          timestamp(3) not null default current_timestamp(),
    primary key (id),
    unique (run_id, tool_call_id),
    unique (assistant_entry_id, ordinal)
);

create index if not exists idx_tool_invocation_run_status
    on tool_invocation (run_id, status, ordinal);

create index if not exists idx_tool_invocation_claim
    on tool_invocation (target_type, status, deadline_at, id);

create index if not exists idx_tool_invocation_environment_claim
    on tool_invocation (environment_id, status, deadline_at, id);

create table if not exists tool_artifact (
    id bigint not null,
    media_type varchar(128) not null,
    encoding varchar(64) not null,
    content blob not null,
    size_bytes bigint not null,
    sha256 varchar(64) not null,
    gmt_create timestamp(3) not null default current_timestamp(),
    primary key (id)
);

create table if not exists harness_subagent_task (
    parent_invocation_id  bigint not null,
    parent_session_id     bigint not null,
    child_session_id      bigint not null,
    child_run_id          bigint not null,
    target_agent          varchar(128) not null,
    working_copy_policy   varchar(32) not null,
    working_copy_revision varchar(512),
    max_turns             integer not null,
    idle_timeout_millis   bigint,
    status                varchar(32) not null,
    report_json           text,
    gmt_create            timestamp(3) not null default current_timestamp(),
    gmt_modified          timestamp(3) not null default current_timestamp(),
    primary key (parent_invocation_id)
);

create index if not exists idx_harness_subagent_task_child
    on harness_subagent_task (child_session_id, child_run_id);
create index if not exists idx_harness_subagent_task_parent
    on harness_subagent_task (parent_session_id, status);

create table if not exists harness_run_control_message (
    id                 bigint not null,
    session_id         bigint not null,
    run_id             bigint,
    control_kind       varchar(16) not null,
    consumption_mode   varchar(32) not null,
    message_json       text not null,
    status             varchar(16) not null,
    consumed_run_id    bigint,
    consumed_entry_id  bigint,
    gmt_create         timestamp(3) not null default current_timestamp(),
    consumed_at        timestamp(3),
    gmt_modified       timestamp(3) not null default current_timestamp(),
    primary key (id)
);

create index if not exists idx_harness_control_pending
    on harness_run_control_message (run_id, control_kind, status, id);
create index if not exists idx_harness_control_session
    on harness_run_control_message (session_id, status, id);

create table if not exists model_usage_record (
    id                                 bigint not null,
    session_id                         bigint not null,
    run_id                             bigint not null,
    assistant_entry_id                 bigint not null,
    attempt                            integer not null,
    turn_index                         integer not null,
    provider_resource_id               bigint not null,
    model_resource_id                  bigint not null,
    provider_type                      varchar(64) not null,
    provider_model_id                  varchar(256) not null,
    prompt_cache_mode                  varchar(32) not null,
    prompt_cache_retention             varchar(16) not null,
    cache_eligible                     boolean not null,
    cache_affinity_key                 varchar(512),
    stop_reason                        varchar(32) not null,
    usage_input_tokens                 bigint not null,
    usage_output_tokens                bigint not null,
    usage_cache_read_tokens            bigint not null,
    usage_cache_write_tokens           bigint not null,
    usage_cache_write_long_tokens      bigint not null,
    usage_reasoning_tokens             bigint not null,
    usage_provider_total_tokens        bigint not null,
    cost_currency                      varchar(16) not null,
    cost_input                         numeric(32,12) not null,
    cost_output                        numeric(32,12) not null,
    cost_cache_read                    numeric(32,12) not null,
    cost_cache_write                   numeric(32,12) not null,
    cost_cache_write_long              numeric(32,12) not null,
    cost_reasoning                     numeric(32,12) not null,
    cost_total                         numeric(32,12) not null,
    pricing_currency                   varchar(16) not null,
    pricing_tier                       varchar(64) not null,
    pricing_service_tier               varchar(64) not null,
    pricing_service_tier_multiplier    numeric(32,12) not null,
    pricing_version                    varchar(64) not null,
    pricing_input_per_million_tokens   numeric(32,12) not null,
    pricing_output_per_million_tokens  numeric(32,12) not null,
    pricing_cache_read_per_million_tokens  numeric(32,12) not null,
    pricing_cache_write_per_million_tokens numeric(32,12) not null,
    pricing_cache_write_long_per_million_tokens numeric(32,12) not null,
    pricing_reasoning_per_million_tokens numeric(32,12) not null,
    request_id                         varchar(256),
    reported_service_tier              varchar(64),
    raw_usage_json                     clob not null,
    gmt_create                         timestamp(3) not null default current_timestamp(),
    primary key (id),
    unique (assistant_entry_id),
    unique (run_id, attempt, turn_index)
);

create index if not exists idx_model_usage_record_run on model_usage_record (run_id, id);
create index if not exists idx_model_usage_record_session on model_usage_record (session_id, id);
create index if not exists idx_model_usage_record_model on model_usage_record (model_resource_id, id);

create table if not exists comfyui_workflow_api (
    id                    bigint not null,
    api_name              varchar(64) not null,
    name                  varchar(128) not null,
    description           varchar(512),
    workflow_json         text not null,
    input_bindings_json   text not null,
    default_selector      varchar(1024),
    enabled               boolean not null,
    gmt_create            timestamp(3) not null default current_timestamp(),
    gmt_modified          timestamp(3) not null default current_timestamp(),
    version               bigint not null default 0,
    primary key (id),
    unique (api_name)
);

create table if not exists tool_environment (
    id                    bigint not null,
    name                  varchar(128) not null,
    description           varchar(512),
    capabilities_json     text not null,
    last_seen_at          timestamp(3),
    gmt_create            timestamp(3) not null default current_timestamp(),
    gmt_modified          timestamp(3) not null default current_timestamp(),
    version               bigint not null default 0,
    primary key (id),
    unique (name)
);

create table if not exists canvas_document (
    id                  bigint not null,
    workspace_id        bigint not null,
    title               varchar(256) not null,
    schema_version      integer not null,
    revision            bigint not null,
    lifecycle           varchar(32) not null,
    home_viewport_json  text not null,
    gmt_create          timestamp(3) not null default current_timestamp(),
    gmt_modified        timestamp(3) not null default current_timestamp(),
    version             bigint not null default 0,
    primary key (id)
);
create index if not exists idx_canvas_document_workspace on canvas_document (workspace_id, gmt_modified);

create table if not exists canvas_node (
    id                  bigint not null,
    canvas_id           bigint not null,
    kind                varchar(32) not null,
    node_type           varchar(128) not null,
    node_type_version   integer not null,
    name                varchar(256) not null,
    parent_group_id     bigint,
    x                   double not null,
    y                   double not null,
    width               double not null,
    height              double not null,
    rotation            double not null,
    z_index             bigint not null,
    locked              boolean not null,
    hidden              boolean not null,
    validity            varchar(32) not null,
    data_json           text not null,
    revision            bigint not null,
    gmt_deleted         timestamp(3),
    gmt_create          timestamp(3) not null default current_timestamp(),
    gmt_modified        timestamp(3) not null default current_timestamp(),
    version             bigint not null default 0,
    primary key (id)
);
create index if not exists idx_canvas_node_canvas on canvas_node (canvas_id, gmt_deleted);

create table if not exists canvas_link (
    id                  bigint not null,
    canvas_id           bigint not null,
    source_node_id      bigint not null,
    target_node_id      bigint not null,
    revision            bigint not null,
    gmt_create          timestamp(3) not null default current_timestamp(),
    primary key (id),
    unique (canvas_id, source_node_id, target_node_id)
);

create table if not exists canvas_command (
    id                  bigint not null,
    command_id          varchar(128) not null,
    workspace_id        bigint not null,
    canvas_id           bigint not null,
    base_revision       bigint not null,
    result_revision     bigint not null,
    request_hash        varchar(128) not null,
    payload_json        text not null,
    result_json         text not null,
    gmt_create          timestamp(3) not null default current_timestamp(),
    primary key (id),
    unique (workspace_id, command_id)
);
