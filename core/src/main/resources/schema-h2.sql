create table if not exists workspace (
    id              bigint not null,
    name            varchar(64) not null,
    settings_json   text not null,
    gmt_create      timestamp(3) not null default current_timestamp(),
    gmt_modified    timestamp(3) not null default current_timestamp(),
    version         bigint not null default 0,
    primary key (id),
    unique (name)
);

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

create table if not exists agent_session (
    id                     bigint not null,
    session_id             varchar(64) not null,
    agent_id               bigint,
    agent_name             varchar(64),
    title                  varchar(256),
    current_head_event_id  varchar(64) not null,
    gmt_create             timestamp(3) not null default current_timestamp(),
    gmt_modified           timestamp(3) not null default current_timestamp(),
    version                bigint not null default 0,
    primary key (id),
    unique (session_id)
);

create table if not exists agent_session_event (
    id               bigint not null,
    event_id         varchar(64) not null,
    session_id       varchar(64) not null,
    parent_event_id  varchar(64) not null,
    run_id           varchar(64),
    event_type       varchar(64) not null,
    payload_json     text not null,
    gmt_create       timestamp(3) not null default current_timestamp(),
    gmt_modified     timestamp(3) not null default current_timestamp(),
    version          bigint not null default 0,
    primary key (id),
    unique (event_id)
);

create table if not exists agent_run (
    id                bigint not null,
    run_id            varchar(64) not null,
    session_id        varchar(64) not null,
    trigger_event_id  varchar(64) not null,
    status            varchar(32) not null,
    gmt_create        timestamp(3) not null default current_timestamp(),
    gmt_modified      timestamp(3) not null default current_timestamp(),
    version           bigint not null default 0,
    primary key (id),
    unique (run_id)
);

create table if not exists harness_session (
    id                    bigint not null,
    workspace_id          bigint not null,
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

create index if not exists idx_harness_session_workspace on harness_session (workspace_id);
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

create table if not exists harness_run (
    id                  bigint not null,
    session_id          bigint not null,
    trigger_entry_id    bigint not null,
    status              varchar(32) not null,
    turn_index          integer not null,
    attempt             integer not null,
    event_sequence      bigint not null,
    lease_owner         varchar(128),
    lease_until         timestamp(3),
    next_attempt_at     timestamp(3) not null,
    cancel_requested_at timestamp(3),
    gmt_create          timestamp(3) not null default current_timestamp(),
    started_at          timestamp(3),
    finished_at         timestamp(3),
    gmt_modified        timestamp(3) not null default current_timestamp(),
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
    parent_invocation_id bigint not null,
    parent_session_id    bigint not null,
    child_session_id     bigint not null,
    child_run_id         bigint not null,
    target_agent         varchar(128) not null,
    workspace_policy     varchar(32) not null,
    workspace_revision   varchar(512),
    max_turns            integer not null,
    idle_timeout_millis  bigint,
    status               varchar(32) not null,
    report_json          text,
    gmt_create           timestamp(3) not null default current_timestamp(),
    gmt_modified         timestamp(3) not null default current_timestamp(),
    primary key (parent_invocation_id)
);

create index if not exists idx_harness_subagent_task_child
    on harness_subagent_task (child_session_id, child_run_id);
create index if not exists idx_harness_subagent_task_parent
    on harness_subagent_task (parent_session_id, status);
