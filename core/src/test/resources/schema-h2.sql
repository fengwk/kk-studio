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
    title                 varchar(256),
    main_thread_id        bigint not null,
    parent_session_id     bigint,
    root_session_id       bigint not null,
    parent_invocation_id  bigint,
    depth                 integer not null,
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
    entry_type       varchar(32) not null,
    payload_json     text not null,
    gmt_create       timestamp(3) not null default current_timestamp(),
    primary key (id)
);

create index if not exists idx_harness_session_entry_session_id
    on harness_session_entry (session_id, id);
create index if not exists idx_harness_session_entry_parent on harness_session_entry (parent_entry_id);

-- durable user execution panel / tree cursor
create table if not exists harness_thread (
    id                           bigint not null,                 -- 业务主键
    session_id                   bigint not null,                 -- 所属 session tree
    head_entry_id                bigint not null,                 -- 当前 tree cursor
    status                       varchar(32) not null,            -- IDLE/RUNNING/WAITING/FAILED/RETRYING
    input_sequence               bigint not null,                 -- 已分配 input sequence 最大值
    active_agent_definition_id   bigint,                          -- 当前 AgentDefinition id
    active_agent_name            varchar(256),                    -- 捕获的 Agent 名称
    model_id                     varchar(128),                    -- Thread 级 model id
    variant                      varchar(128),                    -- Thread 级 model variant
    yolo_enabled                 boolean not null default false,  -- Thread 级 YOLO
    processor_token              varchar(128),                    -- 当前 processor fencing token
    processor_until              timestamp(3),                    -- token 租约截止
    gmt_create                   timestamp(3) not null default current_timestamp(),
    gmt_modified                 timestamp(3) not null default current_timestamp(),
    version                      bigint not null default 0,
    primary key (id)
);

create index if not exists idx_harness_thread_session on harness_thread (session_id, id);

create table if not exists harness_thread_input (
    id                    bigint not null,
    thread_id             bigint not null,
    sequence              bigint not null,
    input_type            varchar(32) not null,
    payload_json          text not null,
    client_message_id     varchar(128) not null,            -- 客户端幂等键
    status                varchar(32) not null,
    applied_entry_id      bigint,
    resolved_at           timestamp(3),
    cancelled_by_stop_id  bigint,
    gmt_create            timestamp(3) not null default current_timestamp(),
    primary key (id),
    unique (thread_id, sequence)
);

create unique index if not exists uk_harness_thread_input_client
    on harness_thread_input (thread_id, client_message_id);
create index if not exists idx_harness_thread_input_pending
    on harness_thread_input (thread_id, status, sequence);

create table if not exists harness_thread_stop (
    id                 bigint not null,
    thread_id          bigint not null,
    client_request_id  varchar(128) not null,
    gmt_create         timestamp(3) not null default current_timestamp(),
    primary key (id),
    unique (thread_id, client_request_id)
);

create index if not exists idx_harness_thread_stop_thread
    on harness_thread_stop (thread_id, id);

create table if not exists harness_thread_event (
    id                 bigint not null,
    thread_id          bigint not null,
    subject_entry_id   bigint,
    event_type         varchar(64) not null,
    payload_json       text not null,
    gmt_create         timestamp(3) not null default current_timestamp(),
    primary key (id)
);

create index if not exists idx_harness_thread_event_thread
    on harness_thread_event (thread_id, id);

create table if not exists tool_invocation (
    id                    bigint not null,
    thread_id             bigint not null,
    assistant_entry_id    bigint not null,
    ordinal               integer not null,
    tool_call_id          varchar(256) not null,
    tool_name             varchar(128) not null,
    tool_version          varchar(128) not null,
    target_type           varchar(32) not null,
    environment_name      varchar(128),
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
    unique (thread_id, tool_call_id),
    unique (assistant_entry_id, ordinal)
);

create index if not exists idx_tool_invocation_thread_status
    on tool_invocation (thread_id, status, ordinal);

create index if not exists idx_tool_invocation_claim
    on tool_invocation (target_type, status, deadline_at, id);

create index if not exists idx_tool_invocation_environment_claim
    on tool_invocation (environment_name, status, deadline_at, id);

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
    parent_thread_id      bigint not null,
    root_thread_id        bigint not null,
    child_session_id      bigint not null,
    child_thread_id       bigint not null,
    target_agent          varchar(128) not null,
    working_copy_policy   varchar(32) not null,
    working_copy_revision varchar(512),
    max_turns             integer not null,
    status                varchar(32) not null,
    report_json           text,
    gmt_create            timestamp(3) not null default current_timestamp(),
    gmt_modified          timestamp(3) not null default current_timestamp(),
    primary key (parent_invocation_id),
    unique (child_thread_id)
);

create index if not exists idx_harness_subagent_task_parent
    on harness_subagent_task (parent_session_id, status);

create table if not exists model_usage_record (
    id                                 bigint not null,
    session_id                         bigint not null,
    thread_id                          bigint not null,
    assistant_entry_id                 bigint not null,
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
    unique (assistant_entry_id)
);

create index if not exists idx_model_usage_record_thread on model_usage_record (thread_id, id);
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

create table if not exists chat (
    id                  bigint not null,
    title               varchar(256),
    default_agent_id    bigint,
    gmt_create          timestamp(3) not null default current_timestamp(),
    gmt_modified        timestamp(3) not null default current_timestamp(),
    version             bigint not null default 0,
    primary key (id)
);
create index if not exists idx_chat_modified on chat (gmt_modified, id);

create table if not exists chat_session (
    id                  bigint not null,
    chat_id             bigint not null,
    session_id          bigint not null,
    gmt_create          timestamp(3) not null default current_timestamp(),
    primary key (id),
    unique (chat_id, session_id)
);
create index if not exists idx_chat_session_session on chat_session (session_id);
