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
    workspace_id    bigint not null,
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
    unique (workspace_id, name)
);

create table if not exists agent_model (
    id                  bigint not null,
    workspace_id        bigint not null,
    provider_id         bigint not null,
    name                varchar(128) not null,
    description         varchar(512),
    capabilities_json   text not null,
    config_json         text not null,
    gmt_create          timestamp(3) not null default current_timestamp(),
    gmt_modified        timestamp(3) not null default current_timestamp(),
    version             bigint not null default 0,
    primary key (id),
    unique (workspace_id, name)
);

create table if not exists agent_definition (
    id              bigint not null,
    workspace_id    bigint not null,
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
    unique (workspace_id, name)
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
