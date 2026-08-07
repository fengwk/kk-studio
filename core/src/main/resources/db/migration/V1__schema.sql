-- Flyway PostgreSQL baseline schema for kk-studio.
--
-- Applied by Flyway to an empty database as version 1.
-- Each table is created without `IF NOT EXISTS` so migration drift fails loudly.
-- Time fields use `timestamptz(3)` (millisecond precision, with time zone).
-- Structured payloads use `jsonb`; flags use `boolean`; file blobs are never
-- stored in the database.
-- `updated_at` is application-managed; PostgreSQL never emulates MySQL's
-- ON UPDATE CURRENT_TIMESTAMP.
--
-- Harness execution state is the exact runtime-spring protocol: section 2 below
-- (`harness_runtime_id_seq` + the seven tables + their indexes) is a single
-- contiguous block copied verbatim from
-- `harness/runtime-spring/.../postgresql/harness-runtime-schema.sql` and must stay
-- byte-identical to that file (guarded by an architecture test). HarnessRuntime
-- owns every execution id via `harness_runtime_id_seq` and owns `revision`; the
-- database never mutates it. The only V1 additions around it are the application
-- business tables and the thread revision NOTIFY hint trigger (section 4).
--
-- DDL is grouped so cycle-closing and forward foreign keys are appended only
-- after both target tables exist.

------------------------------------------------------------------------------
-- 0. Sequences
------------------------------------------------------------------------------

create sequence kk_studio_id_seq
    as bigint
    increment by 1
    start with 1
    minvalue 1
    no cycle;

------------------------------------------------------------------------------
-- 1. Non-harness business tables (no mutual dependencies)
------------------------------------------------------------------------------

create table agent_provider (
    name            varchar(64)   primary key,
    description     varchar(512),
    provider_type   varchar(64)   not null,
    base_url        varchar(512),
    credential      varchar(512),
    config          jsonb         not null,
    created_at      timestamptz(3) not null default current_timestamp,
    updated_at      timestamptz(3) not null default current_timestamp,
    version         bigint        not null default 0,
    constraint ck_agent_provider_name check (
        name !~ '^[[:space:]]'
        and name !~ '[[:space:]]$'
        and char_length(name) > 0
        and position('/' in name) = 0
    ),
    constraint ck_agent_provider_version_nonneg check (version >= 0)
);

create table agent_model (
    provider_name   varchar(64)   not null,
    name            varchar(128)  not null,
    description     varchar(512),
    config          jsonb         not null,
    created_at      timestamptz(3) not null default current_timestamp,
    updated_at      timestamptz(3) not null default current_timestamp,
    version         bigint        not null default 0,
    constraint pk_agent_model primary key (provider_name, name),
    constraint ck_agent_model_name check (
        name !~ '^[[:space:]]'
        and name !~ '[[:space:]]$'
        and char_length(name) > 0
    ),
    constraint fk_agent_model_provider foreign key (provider_name)
        references agent_provider (name),
    constraint ck_agent_model_version_nonneg check (version >= 0)
);

create table agent_definition (
    name            varchar(64)   primary key,
    description     varchar(512),
    system_prompt   text,
    model_provider_name varchar(64) not null,
    model_name      varchar(128)  not null,
    variant         varchar(64),
    config          jsonb         not null,
    created_at      timestamptz(3) not null default current_timestamp,
    updated_at      timestamptz(3) not null default current_timestamp,
    version         bigint        not null default 0,
    constraint ck_agent_definition_name check (
        name !~ '^[[:space:]]'
        and name !~ '[[:space:]]$'
        and char_length(name) > 0
        and position('/' in name) = 0
    ),
    constraint fk_agent_definition_model foreign key (model_provider_name, model_name)
        references agent_model (provider_name, name),
    constraint ck_agent_definition_version_nonneg check (version >= 0)
);

create index idx_agent_definition_model
    on agent_definition (model_provider_name, model_name);

create table comfyui_workflow_api (
    id                bigint        primary key default nextval('kk_studio_id_seq'),
    api_name          varchar(64)   not null,
    name              varchar(128)  not null,
    description       varchar(512),
    workflow          jsonb         not null,
    input_bindings    jsonb         not null,
    default_selector  varchar(1024),
    enabled           boolean       not null,
    created_at        timestamptz(3) not null default current_timestamp,
    updated_at        timestamptz(3) not null default current_timestamp,
    version           bigint        not null default 0
);

create unique index uk_comfyui_workflow_api_api_name
    on comfyui_workflow_api (api_name);

create table canvas_document (
    id              bigint        primary key default nextval('kk_studio_id_seq'),
    title           varchar(256)  not null,
    revision        bigint        not null,
    home_viewport   jsonb         not null,
    updated_at      timestamptz(3) not null default current_timestamp,
    constraint ck_canvas_document_title_nonblank check (btrim(title) <> ''),
    constraint ck_canvas_document_revision_nonneg check (revision >= 0),
    constraint ck_canvas_document_id_pos check (id > 0)
);

create index idx_canvas_document_updated
    on canvas_document (updated_at, id);

create table canvas_node (
    id           bigint        primary key default nextval('kk_studio_id_seq'),
    canvas_id    bigint        not null,
    kind         varchar(32)   not null,
    node_type    varchar(128)  not null,
    name         varchar(256)  not null,
    x            double precision not null,
    y            double precision not null,
    width        double precision not null,
    height       double precision not null,
    data         jsonb         not null,
    constraint ck_canvas_node_id_pos check (id > 0),
    constraint ck_canvas_node_canvas_id_pos check (canvas_id > 0),
    constraint ck_canvas_node_kind check (
        kind in ('RESOURCE','FUNCTION')
    ),
    constraint ck_canvas_node_type_nonblank check (btrim(node_type) <> ''),
    constraint ck_canvas_node_name_nonblank check (btrim(name) <> ''),
    constraint ck_canvas_node_width_pos check (width > 0),
    constraint ck_canvas_node_height_pos check (height > 0)
);

create index idx_canvas_node_canvas on canvas_node (canvas_id);
create unique index uk_canvas_node_canvas_id on canvas_node (canvas_id, id);

create table canvas_link (
    id              bigint        primary key default nextval('kk_studio_id_seq'),
    canvas_id       bigint        not null,
    source_node_id  bigint        not null,
    target_node_id  bigint        not null,
    constraint ck_canvas_link_id_pos check (id > 0),
    constraint ck_canvas_link_canvas_id_pos check (canvas_id > 0),
    constraint ck_canvas_link_source_id_pos check (source_node_id > 0),
    constraint ck_canvas_link_target_id_pos check (target_node_id > 0),
    constraint ck_canvas_link_distinct check (source_node_id <> target_node_id)
);

create unique index uk_canvas_link
    on canvas_link (canvas_id, source_node_id, target_node_id);

create table canvas_command_dedup (
    canvas_id      bigint        not null,
    command_id     varchar(128)  not null,
    request_hash   varchar(64)   not null,
    constraint pk_canvas_command_dedup primary key (canvas_id, command_id),
    constraint ck_canvas_command_dedup_canvas_id_pos check (canvas_id > 0),
    constraint ck_canvas_command_dedup_command_id_nonblank check (btrim(command_id) <> ''),
    constraint ck_canvas_command_dedup_request_hash_length check (
        char_length(request_hash) = 64
    )
);

create table chat (
    id                  bigint        primary key default nextval('kk_studio_id_seq'),
    title               varchar(256),
    -- agent_name 故意不加 FK：它只按名称引用 Agent。Agent 硬删除期间该引用失效
    -- （turn/attempt fail closed），同名重建后既有 Chat 引用解析到当前 AgentDefinition。
    agent_name          varchar(64)   not null,
    yolo_enabled        boolean       not null default false,
    created_at          timestamptz(3) not null default current_timestamp,
    updated_at          timestamptz(3) not null default current_timestamp,
    version             bigint        not null default 0,
    constraint ck_chat_version_nonneg check (version >= 0),
    constraint ck_chat_agent_name check (
        agent_name !~ '^[[:space:]]'
        and agent_name !~ '[[:space:]]$'
        and char_length(agent_name) > 0
        and position('/' in agent_name) = 0
    )
);

create index idx_chat_modified on chat (updated_at, id);

------------------------------------------------------------------------------
-- 2. Harness runtime execution protocol
--
-- The block below (`harness_runtime_id_seq` + the seven tables + their indexes)
-- is copied verbatim from
-- harness/runtime-spring/src/main/resources/fun/fengwk/kkstudio/harness/runtime/
-- spring/postgresql/harness-runtime-schema.sql and must stay byte-identical to
-- that file. Columns, checks, FKs and indexes must never drift; HarnessRuntime
-- allocates ids from `harness_runtime_id_seq` and inserts them explicitly (no
-- column defaults).
------------------------------------------------------------------------------

create sequence harness_runtime_id_seq
    as bigint
    minvalue 1
    maxvalue 9223372036854775807
    start with 1
    increment by 1
    no cycle;

create table harness_session (
    id bigint primary key check (id > 0),
    title varchar(256),
    created_at timestamptz(3) not null
);

create table harness_entry (
    id bigint primary key check (id > 0),
    session_id bigint not null,
    parent_entry_id bigint,
    entry_type varchar(32) not null,
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    created_at timestamptz(3) not null,
    constraint uk_harness_entry_session_id unique (session_id, id),
    constraint fk_harness_entry_session foreign key (session_id)
        references harness_session (id),
    constraint fk_harness_entry_parent foreign key (session_id, parent_entry_id)
        references harness_entry (session_id, id),
    constraint ck_harness_entry_type check (
        entry_type in (
            'ROOT',
            'TURN_START',
            'MESSAGE',
            'CUSTOM',
            'CUSTOM_MESSAGE',
            'ASSISTANT_ERROR',
            'ASSISTANT_ABORTED',
            'TURN_END'
        )
    ),
    constraint ck_harness_entry_parent_shape check (
        (entry_type = 'ROOT' and parent_entry_id is null)
        or (entry_type <> 'ROOT' and parent_entry_id is not null)
    ),
    constraint ck_harness_entry_parent_not_self check (
        parent_entry_id is null or parent_entry_id <> id
    )
);

create unique index uk_harness_entry_single_root
    on harness_entry (session_id)
    where entry_type = 'ROOT';

create index idx_harness_entry_parent
    on harness_entry (session_id, parent_entry_id);

create table harness_thread (
    id bigint primary key check (id > 0),
    head_entry_id bigint not null,
    yolo_enabled boolean not null,
    next_command_sequence bigint not null check (next_command_sequence >= 1),
    revision bigint not null check (revision >= 0),
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_harness_thread_head foreign key (head_entry_id)
        references harness_entry (id),
    constraint ck_harness_thread_time_order check (updated_at >= created_at)
);

create table harness_thread_command (
    id bigint primary key check (id > 0),
    thread_id bigint not null,
    sequence bigint not null check (sequence > 0),
    command_type varchar(32) not null,
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    client_command_id varchar(128) not null,
    consumed_turn_start_entry_id bigint,
    cancelled_at timestamptz(3),
    created_at timestamptz(3) not null,
    constraint fk_harness_thread_command_thread foreign key (thread_id)
        references harness_thread (id),
    constraint fk_harness_thread_command_consumed foreign key (consumed_turn_start_entry_id)
        references harness_entry (id),
    constraint uk_harness_thread_command_sequence unique (thread_id, sequence),
    constraint uk_harness_thread_command_client unique (thread_id, client_command_id),
    constraint ck_harness_thread_command_type check (
        command_type in (
            'USER_MESSAGE',
            'CUSTOM_MESSAGE',
            'SET_AGENT',
            'SET_MODEL',
            'SET_THINKING_LEVEL',
            'SET_ACTIVE_TOOLS',
            'SET_YOLO',
            'SET_ENVIRONMENT'
        )
    ),
    constraint ck_harness_thread_command_terminal check (
        consumed_turn_start_entry_id is null or cancelled_at is null
    ),
    constraint ck_harness_thread_command_cancel_time check (
        cancelled_at is null or cancelled_at >= created_at
    )
);

create index idx_harness_thread_command_queued
    on harness_thread_command (thread_id, sequence)
    where consumed_turn_start_entry_id is null and cancelled_at is null;

create table harness_model_invocation (
    id bigint primary key check (id > 0),
    thread_id bigint not null,
    turn_start_entry_id bigint not null,
    basis_head_entry_id bigint not null,
    request jsonb not null check (jsonb_typeof(request) = 'object'),
    status varchar(16) not null,
    attempt integer not null check (attempt >= 0),
    stream_checkpoint jsonb check (
        stream_checkpoint is null or jsonb_typeof(stream_checkpoint) = 'object'
    ),
    result jsonb check (result is null or jsonb_typeof(result) = 'object'),
    error jsonb check (error is null or jsonb_typeof(error) = 'object'),
    result_entry_id bigint,
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_harness_model_invocation_thread foreign key (thread_id)
        references harness_thread (id),
    constraint fk_harness_model_invocation_turn_start foreign key (turn_start_entry_id)
        references harness_entry (id),
    constraint fk_harness_model_invocation_basis foreign key (basis_head_entry_id)
        references harness_entry (id),
    constraint fk_harness_model_invocation_result foreign key (result_entry_id)
        references harness_entry (id),
    constraint uk_harness_model_invocation_turn unique (thread_id, turn_start_entry_id),
    constraint ck_harness_model_invocation_status check (
        status in (
            'READY',
            'DISPATCHING',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
            'CANCELLED',
            'UNKNOWN'
        )
    ),
    constraint ck_harness_model_invocation_terminal_facts check (
        result is null or error is null
    ),
    constraint ck_harness_model_invocation_time_order check (updated_at >= created_at)
);

create unique index uk_harness_model_invocation_result
    on harness_model_invocation (result_entry_id)
    where result_entry_id is not null;

create table harness_tool_invocation (
    id bigint primary key check (id > 0),
    model_invocation_id bigint not null,
    assistant_entry_id bigint not null,
    ordinal integer not null check (ordinal >= 0),
    request jsonb not null check (jsonb_typeof(request) = 'object'),
    status varchar(32) not null,
    attempt integer not null check (attempt >= 0),
    approval jsonb check (approval is null or jsonb_typeof(approval) = 'object'),
    result jsonb check (result is null or jsonb_typeof(result) = 'object'),
    error jsonb check (error is null or jsonb_typeof(error) = 'object'),
    result_entry_id bigint,
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_harness_tool_invocation_model foreign key (model_invocation_id)
        references harness_model_invocation (id),
    constraint fk_harness_tool_invocation_assistant foreign key (assistant_entry_id)
        references harness_entry (id),
    constraint fk_harness_tool_invocation_result foreign key (result_entry_id)
        references harness_entry (id),
    constraint uk_harness_tool_invocation_ordinal unique (assistant_entry_id, ordinal),
    constraint ck_harness_tool_invocation_status check (
        status in (
            'WAITING_APPROVAL',
            'READY',
            'DISPATCHING',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
            'CANCELLED',
            'UNKNOWN'
        )
    ),
    constraint ck_harness_tool_invocation_terminal_facts check (
        result is null or error is null
    ),
    constraint ck_harness_tool_invocation_time_order check (updated_at >= created_at)
);

create unique index uk_harness_tool_invocation_result
    on harness_tool_invocation (result_entry_id)
    where result_entry_id is not null;

create table harness_work (
    target_type varchar(16) not null,
    target_id bigint not null check (target_id > 0),
    available_at timestamptz(3) not null,
    wake_version bigint not null check (wake_version > 0),
    lease_token varchar(128),
    lease_until timestamptz(3),
    primary key (target_type, target_id),
    constraint ck_harness_work_target_type check (
        target_type in ('THREAD', 'MODEL', 'TOOL')
    ),
    constraint ck_harness_work_lease_pair check (
        (lease_token is null) = (lease_until is null)
    ),
    constraint ck_harness_work_lease_token check (
        lease_token is null
        or (length(lease_token) > 0 and btrim(lease_token) = lease_token)
    )
);

create index idx_harness_work_available
    on harness_work (available_at, target_type, target_id);

create index idx_harness_work_lease_until
    on harness_work (lease_until, target_type, target_id)
    where lease_until is not null;

------------------------------------------------------------------------------
-- 3. Application-owned tables referencing harness_thread
------------------------------------------------------------------------------

create table chat_thread (
    chat_id     bigint        not null,
    thread_id   bigint        not null,
    created_at  timestamptz(3) not null default current_timestamp,
    constraint pk_chat_thread primary key (chat_id, thread_id),
    constraint fk_chat_thread_chat foreign key (chat_id)
        references chat (id) on delete cascade,
    constraint fk_chat_thread_thread foreign key (thread_id)
        references harness_thread (id) on delete cascade
);

create index idx_chat_thread_thread
    on chat_thread (thread_id, chat_id);

-- Product goal is a product capability (Core Goal), not runtime execution
-- truth; it intentionally does not use the `harness_` prefix.
create table agent_thread_goal (
    thread_id       bigint        primary key,
    objective       text          not null,
    token_budget    bigint,
    status          varchar(16)   not null,
    reason          text,
    created_at      timestamptz(3) not null default current_timestamp,
    updated_at      timestamptz(3) not null default current_timestamp,
    constraint fk_agent_thread_goal_thread foreign key (thread_id)
        references harness_thread (id),
    constraint ck_agent_thread_goal_status check (
        status in ('active','complete','blocked')
    ),
    constraint ck_agent_thread_goal_objective check (
        char_length(btrim(objective)) > 0
    ),
    constraint ck_agent_thread_goal_token_budget_pos check (
        token_budget is null or token_budget > 0
    ),
    constraint ck_agent_thread_goal_reason check (
        (status = 'active' and reason is null)
        or (status in ('complete','blocked')
            and reason is not null
            and char_length(btrim(reason)) > 0)
    ),
    constraint ck_agent_thread_goal_time_order check (
        updated_at >= created_at
    )
);

------------------------------------------------------------------------------
-- 4. Thread revision NOTIFY hint
--
-- revision is owned by HarnessRuntime; PostgreSQL never bumps it. This trigger
-- is only a wake-up hint for in-process projection listeners: it notifies when
-- a Thread row is inserted or its revision column actually changed, and never
-- mutates the row. There are deliberately no child-table revision triggers.
------------------------------------------------------------------------------

create or replace function harness_thread_revision_notify()
returns trigger language plpgsql as $$
begin
    if tg_op = 'INSERT' or new.revision is distinct from old.revision then
        perform pg_notify('harness_thread_revision', new.id::text);
    end if;
    return new;
end $$;

create trigger trg_harness_thread_revision_notify
    after insert or update of revision on harness_thread
    for each row execute function harness_thread_revision_notify();

------------------------------------------------------------------------------
-- 5. Canvas self-referencing FKs added after targets exist.
--    Node hard-delete cascades to its links via ON DELETE CASCADE on the
--    same-canvas composite FK (canvas_id, source_node_id) / (canvas_id, target_node_id).
------------------------------------------------------------------------------

alter table canvas_node
    add constraint fk_canvas_node_canvas foreign key (canvas_id)
    references canvas_document (id) on delete cascade;

alter table canvas_command_dedup
    add constraint fk_canvas_command_dedup_canvas foreign key (canvas_id)
    references canvas_document (id) on delete cascade;

alter table canvas_link
    add constraint fk_canvas_link_source
    foreign key (canvas_id, source_node_id)
    references canvas_node (canvas_id, id) on delete cascade;

alter table canvas_link
    add constraint fk_canvas_link_target
    foreign key (canvas_id, target_node_id)
    references canvas_node (canvas_id, id) on delete cascade;
