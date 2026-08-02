-- Flyway PostgreSQL baseline schema for kk-studio.
--
-- Applied by Flyway to an empty database as version 1.
-- Each table is created without `IF NOT EXISTS` so migration drift fails loudly.
-- Time fields use `timestamptz(3)` (millisecond precision, with time zone).
-- Structured payloads use `jsonb`; binary uses `bytea`; flags use `boolean`.
-- `updated_at` is application-managed; PostgreSQL never emulates MySQL's
-- ON UPDATE CURRENT_TIMESTAMP.
--
-- DDL is grouped so cycle-closing and forward foreign keys are appended only
-- after both target tables exist.

------------------------------------------------------------------------------
-- 0. Sequence
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
    deleted_at      timestamptz(3),
    constraint ck_agent_provider_name check (
        name !~ '^[[:space:]]'
        and name !~ '[[:space:]]$'
        and char_length(name) > 0
        and position('/' in name) = 0
    ),
    constraint ck_agent_provider_version_nonneg check (version >= 0)
);

create table agent_provider_revision (
    provider_name     varchar(64)  not null,
    provider_version  bigint       not null,
    provider_type     varchar(64)  not null,
    base_url          varchar(512),
    credential        varchar(512),
    config            jsonb        not null,
    created_at        timestamptz(3) not null default current_timestamp,
    constraint pk_agent_provider_revision primary key (provider_name, provider_version),
    constraint fk_agent_provider_revision_provider foreign key (provider_name)
        references agent_provider (name),
    constraint ck_agent_provider_revision_version_nonneg check (provider_version >= 0)
);

create table agent_model (
    provider_name   varchar(64)   not null,
    name            varchar(128)  not null,
    description     varchar(512),
    config          jsonb         not null,
    created_at      timestamptz(3) not null default current_timestamp,
    updated_at      timestamptz(3) not null default current_timestamp,
    version         bigint        not null default 0,
    deleted_at      timestamptz(3),
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
    deleted_at      timestamptz(3),
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
    -- agent_name intentionally has no FK: it preserves a stale reference
    -- after an AgentDefinition is deleted, by design.
    agent_name          varchar(64)   not null,
    environment_name    varchar(128),
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
    ),
    constraint ck_chat_environment_name check (
        environment_name is null
        or (
            environment_name !~ '^[[:space:]]'
            and environment_name !~ '[[:space:]]$'
            and char_length(environment_name) > 0
            and char_length(environment_name) <= 128
        )
    )
);

create index idx_chat_modified on chat (updated_at, id);

------------------------------------------------------------------------------
-- 2. Harness durable facts (the Entry -> Session FK is appended later)
------------------------------------------------------------------------------

create table harness_entry (
    id               bigint        not null default nextval('kk_studio_id_seq'),
    session_id       bigint        not null,
    parent_entry_id  bigint,
    entry_type       varchar(32)   not null,
    payload          jsonb         not null,
    created_at       timestamptz(3) not null default current_timestamp,
    -- (session_id, id) is declared UNIQUE up front so the self-referential parent FK
    -- can reference it directly inside CREATE TABLE.
    constraint pk_harness_entry primary key (id),
    constraint uk_harness_entry_session_id unique (session_id, id),
    constraint ck_harness_entry_type check (
        entry_type in (
            'ROOT', 'MESSAGE', 'CUSTOM_MESSAGE', 'ASSISTANT_ERROR', 'ASSISTANT_ABORTED'
        )
    ),
    -- ROOT entries must have no parent; every other entry must have a parent.
    constraint ck_harness_entry_root_or_parent check (
        (entry_type = 'ROOT' and parent_entry_id is null)
        or (entry_type <> 'ROOT' and parent_entry_id is not null)
    ),
    constraint ck_harness_entry_parent_not_self check (
        parent_entry_id is null or parent_entry_id <> id
    ),
    constraint fk_harness_entry_parent
        foreign key (session_id, parent_entry_id)
        references harness_entry (session_id, id)
);

create unique index uk_harness_entry_single_root
    on harness_entry (session_id)
    where entry_type = 'ROOT';
create index idx_harness_entry_parent
    on harness_entry (session_id, parent_entry_id);

create table harness_session (
    id                    bigint        primary key default nextval('kk_studio_id_seq'),
    title                 varchar(256),
    created_at            timestamptz(3) not null default current_timestamp,
    constraint ck_harness_session_title check (
        title is null or char_length(btrim(title)) > 0
    )
);

create table harness_thread (
    id                bigint        primary key default nextval('kk_studio_id_seq'),
    head_entry_id     bigint        not null,
    input_sequence    bigint        not null,
    runnable          boolean       not null,
    execution_epoch   bigint        not null,
    revision          bigint        not null default 0,
    processor_token   varchar(128),
    processor_until   timestamptz(3),
    created_at        timestamptz(3) not null default current_timestamp,
    updated_at        timestamptz(3) not null default current_timestamp,
    -- head_entry_id is stored as a single-column FK into the global Entry id space
    -- (cross-session ownership is enforced upstream by commands).
    constraint fk_harness_thread_head foreign key (head_entry_id)
        references harness_entry (id)
        deferrable initially deferred,
    constraint ck_harness_thread_input_sequence_nonneg check (input_sequence >= 0),
    constraint ck_harness_thread_execution_epoch_nonneg check (execution_epoch >= 0),
    constraint ck_harness_thread_revision_nonneg check (revision >= 0),
    -- processor lease token/until are set or cleared together.
    constraint ck_harness_thread_lease_pair check (
        (processor_token is null and processor_until is null)
        or (processor_token is not null and processor_until is not null)
    ),
    constraint ck_harness_thread_lease_token check (
        processor_token is null or char_length(btrim(processor_token)) > 0
    ),
    constraint ck_harness_thread_time_order check (
        updated_at >= created_at
    )
);

create index idx_harness_thread_runnable
    on harness_thread (id) where runnable;
create index idx_harness_thread_processor_until
    on harness_thread (processor_until, id) where processor_until is not null;

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

create index idx_harness_thread_updated_id
    on harness_thread (updated_at desc, id desc);

create index idx_harness_thread_created_id
    on harness_thread (created_at desc, id desc);

create table harness_thread_input (
    id                bigint        primary key default nextval('kk_studio_id_seq'),
    thread_id         bigint        not null,
    sequence          bigint        not null,
    input_type        varchar(32)   not null,
    payload           jsonb         not null,
    idempotency_key   varchar(128)  not null,
    status            varchar(16)   not null,
    created_at        timestamptz(3) not null default current_timestamp,
    applied_at        timestamptz(3),
    constraint fk_harness_thread_input_thread foreign key (thread_id)
        references harness_thread (id),
    constraint ck_harness_thread_input_sequence_pos check (sequence > 0),
    constraint ck_harness_thread_input_idempotency check (
        char_length(btrim(idempotency_key)) > 0
    ),
    constraint ck_harness_thread_input_type check (
        input_type in (
            'USER_MESSAGE', 'CUSTOM_MESSAGE'
        )
    ),
    constraint ck_harness_thread_input_status check (
        status in ('QUEUED', 'APPLIED', 'CANCELLED')
    ),
    constraint ck_harness_thread_input_applied check (
        (status = 'APPLIED' and applied_at is not null)
        or (status <> 'APPLIED' and applied_at is null)
    ),
    constraint ck_harness_thread_input_time_order check (
        applied_at is null or applied_at >= created_at
    )
);

create unique index uk_harness_thread_input_sequence
    on harness_thread_input (thread_id, sequence);
create unique index uk_harness_thread_input_idempotency
    on harness_thread_input (thread_id, idempotency_key);
create index idx_harness_thread_input_pending
    on harness_thread_input (thread_id, status, sequence);

create table harness_model_invocation (
    id                    bigint        primary key default nextval('kk_studio_id_seq'),
    thread_id             bigint        not null,
    source_head_entry_id  bigint        not null,
    execution_epoch       bigint        not null,
    request               jsonb         not null,
    status                varchar(16)   not null,
    attempt               integer       not null,
    next_attempt_at       timestamptz(3),
    worker_token          varchar(128),
    worker_until          timestamptz(3),
    deadline_at           timestamptz(3),
    last_activity_at      timestamptz(3),
    result                jsonb,
    error                 jsonb,
    applied_at            timestamptz(3),
    created_at            timestamptz(3) not null default current_timestamp,
    started_at            timestamptz(3),
    finished_at           timestamptz(3),
    -- 跨节点持久化的安全流快照（text + thinking + attempt-local sequence）。每次流 delta 在 SSE 前
    -- fenced 写入；tool-call fragment 只推进 sequence，不进入文本。新 retry attempt 在 CAS 中重置该列。
    safe_stream_snapshot  jsonb,
    constraint uk_harness_model_invocation_source
        unique (thread_id, source_head_entry_id, execution_epoch),
    constraint uk_harness_model_invocation_thread_id
        unique (thread_id, id),
    -- Owning Thread is referenced by id only; the thread<->session relationship
    -- is enforced upstream by Thread commands via the head Entry.
    constraint fk_harness_model_invocation_thread foreign key (thread_id)
        references harness_thread (id),
    -- harness_entry.id is the global primary key, so the source head FK is a
    -- single-column reference. The thread<->session consistency is enforced
    -- upstream by Thread commands via the head Entry.
    constraint fk_harness_model_invocation_head foreign key (source_head_entry_id)
        references harness_entry (id),
    constraint ck_harness_model_invocation_status check (
        status in (
            'QUEUED', 'RUNNING', 'RETRY_WAIT',
            'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN'
        )
    ),
    constraint ck_harness_model_invocation_execution_epoch_nonneg
        check (execution_epoch >= 0),
    constraint ck_harness_model_invocation_attempt_pos check (attempt >= 1),
    constraint ck_harness_model_invocation_lease_pair check (
        (worker_token is null and worker_until is null)
        or (worker_token is not null and worker_until is not null)
    ),
    constraint ck_harness_model_invocation_worker_token check (
        worker_token is null or char_length(btrim(worker_token)) > 0
    ),
    constraint ck_harness_model_invocation_queued check (
        status <> 'QUEUED'
        or (next_attempt_at is null
            and worker_token is null
            and worker_until is null
            and started_at is null
            and deadline_at is null
            and last_activity_at is null
            and finished_at is null
            and result is null
            and error is null)
    ),
    constraint ck_harness_model_invocation_running check (
        status <> 'RUNNING'
        or (
            next_attempt_at is null
            and worker_token is not null
            and worker_until is not null
            and started_at is not null
            and deadline_at is not null
            and last_activity_at is not null
            and finished_at is null
            and result is null
            and error is null
        )
    ),
    constraint ck_harness_model_invocation_retry_wait check (
        status <> 'RETRY_WAIT'
        or (
            next_attempt_at is not null
            and worker_token is null
            and worker_until is null
            and started_at is not null
            and deadline_at is not null
            and last_activity_at is not null
            and finished_at is null
            and result is null
            and error is null
        )
    ),
    constraint ck_harness_model_invocation_terminal check (
        status not in ('SUCCEEDED','FAILED','CANCELLED','UNKNOWN')
        or (
            finished_at is not null
            and next_attempt_at is null
            and worker_token is null
            and worker_until is null
        )
    ),
    constraint ck_harness_model_invocation_applied check (
        applied_at is null
        or (status in ('SUCCEEDED','FAILED','CANCELLED','UNKNOWN')
            and finished_at is not null
            and applied_at >= finished_at)
    ),
    constraint ck_harness_model_invocation_succeeded_payload check (
        status <> 'SUCCEEDED'
        or (result is not null and error is null
            and started_at is not null
            and deadline_at is not null
            and last_activity_at is not null)
    ),
    constraint ck_harness_model_invocation_failed_payload check (
        status <> 'FAILED'
        or (result is null and error is not null
            and started_at is not null
            and deadline_at is not null
            and last_activity_at is not null)
    ),
    constraint ck_harness_model_invocation_unknown_payload check (
        status <> 'UNKNOWN'
        or (result is null and error is not null
            and started_at is not null
            and deadline_at is not null
            and last_activity_at is not null)
    ),
    constraint ck_harness_model_invocation_cancelled_payload check (
        status <> 'CANCELLED'
        or (result is null and error is null
            and ((started_at is null and deadline_at is null and last_activity_at is null)
                or (started_at is not null
                    and deadline_at is not null
                    and last_activity_at is not null)))
    ),
    -- 安全流快照仅在 RUNNING / RETRY_WAIT / 终态保留；QUEUED 不携带任何 Provider 进展。
    -- 终态 SUCCEEDED/FAILED/CANCELLED/UNKNOWN 可保留快照（审计用），但不再作为 live 实时来源。
    constraint ck_harness_model_invocation_safe_stream_snapshot check (
        status <> 'QUEUED' or safe_stream_snapshot is null
    ),
    constraint ck_harness_model_invocation_time_order check (
        (started_at is null or started_at >= created_at)
        and (deadline_at is null or (started_at is not null and deadline_at > started_at))
        and (worker_until is null
            or (started_at is not null and worker_until > started_at))
        and (last_activity_at is null
            or (started_at is not null and last_activity_at >= started_at))
        and (finished_at is null
            or finished_at >= coalesce(started_at, created_at))
        and (finished_at is null
            or last_activity_at is null
            or last_activity_at <= finished_at)
        and (next_attempt_at is null
            or (deadline_at is not null
                and last_activity_at is not null
                and next_attempt_at > last_activity_at
                and next_attempt_at < deadline_at))
    )
);

create index idx_harness_model_invocation_status
    on harness_model_invocation (status, next_attempt_at, id);
create index idx_harness_model_invocation_worker_until
    on harness_model_invocation (worker_until, id) where status = 'RUNNING';
create index idx_harness_model_invocation_deadline
    on harness_model_invocation (deadline_at, id) where status = 'RUNNING';
create index idx_harness_model_invocation_terminal_unapplied
    on harness_model_invocation (thread_id, id)
    where applied_at is null
      and status in ('SUCCEEDED','FAILED','CANCELLED','UNKNOWN');

create table harness_tool_invocation (
    id                    bigint        primary key default nextval('kk_studio_id_seq'),
    thread_id             bigint        not null,
    session_id            bigint        not null,
    assistant_entry_id    bigint        not null,
    model_invocation_id   bigint        not null,
    ordinal               integer       not null,
    tool_call_id          varchar(256)  not null,
    descriptor            jsonb         not null,
    arguments             jsonb         not null,
    environment_name      varchar(128),
    execution_epoch       bigint        not null,
    status                varchar(32)   not null,
    attempt               integer       not null,
    next_attempt_at       timestamptz(3),
    worker_token          varchar(128),
    worker_until          timestamptz(3),
    deadline_at           timestamptz(3),
    last_activity_at      timestamptz(3),
    result                jsonb,
    error                 jsonb,
    applied_at            timestamptz(3),
    created_at            timestamptz(3) not null default current_timestamp,
    started_at            timestamptz(3),
    finished_at           timestamptz(3),
    permission_state      varchar(16)   not null default 'PENDING',
    yolo_enabled          boolean       not null default false,
    constraint uk_harness_tool_invocation_source
        unique (thread_id, assistant_entry_id, execution_epoch, ordinal),
    constraint uk_harness_tool_invocation_session_id unique (session_id, id),
    -- Owning Thread is referenced by id only; the thread<->session relationship
    -- is enforced upstream by Thread commands via the head Entry.
    constraint fk_harness_tool_invocation_thread foreign key (thread_id)
        references harness_thread (id),
    constraint fk_harness_tool_invocation_model foreign key (thread_id, model_invocation_id)
        references harness_model_invocation (thread_id, id),
    -- session_id stays as a constraint carrier so the assistant Entry belongs
    -- to the same Session as the carrier.
    constraint fk_harness_tool_invocation_assistant foreign key (session_id, assistant_entry_id)
        references harness_entry (session_id, id),
    constraint ck_harness_tool_invocation_tool_call_id check (
        char_length(btrim(tool_call_id)) > 0
    ),
    constraint ck_harness_tool_invocation_environment_name check (
        environment_name is null
        or (
            environment_name !~ '^[[:space:]]'
            and environment_name !~ '[[:space:]]$'
            and char_length(environment_name) > 0
            and char_length(environment_name) <= 128
        )
    ),
    constraint ck_harness_tool_invocation_status check (
        status in (
            'QUEUED', 'RUNNING', 'WAITING_INTERACTION', 'RETRY_WAIT',
            'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN'
        )
    ),
    constraint ck_harness_tool_invocation_permission_state check (
        permission_state in ('PENDING', 'ALLOWED', 'ASKED', 'DENIED')
    ),
    constraint ck_harness_tool_invocation_execution_epoch_nonneg
        check (execution_epoch >= 0),
    constraint ck_harness_tool_invocation_attempt_pos check (attempt >= 1),
    constraint ck_harness_tool_invocation_ordinal_pos check (ordinal >= 0),
    constraint ck_harness_tool_invocation_lease_pair check (
        (worker_token is null and worker_until is null)
        or (worker_token is not null and worker_until is not null)
    ),
    constraint ck_harness_tool_invocation_worker_token check (
        worker_token is null or char_length(btrim(worker_token)) > 0
    ),
    constraint ck_harness_tool_invocation_queued check (
        status <> 'QUEUED'
        or (next_attempt_at is null
            and worker_token is null and worker_until is null
            and started_at is null and deadline_at is null and last_activity_at is null
            and finished_at is null and result is null and error is null
            and permission_state in ('PENDING', 'ALLOWED'))
    ),
    constraint ck_harness_tool_invocation_running check (
        status <> 'RUNNING'
        or (next_attempt_at is null
            and worker_token is not null and worker_until is not null
            and started_at is not null and deadline_at is not null
            and last_activity_at is not null
            and finished_at is null and result is null and error is null
            and permission_state in ('PENDING', 'ALLOWED'))
    ),
    constraint ck_harness_tool_invocation_waiting_interaction check (
        status <> 'WAITING_INTERACTION'
        or (next_attempt_at is null
            and worker_token is null and worker_until is null
            and started_at is null and deadline_at is null and last_activity_at is null
            and finished_at is null and result is null and error is null
            and permission_state = 'ASKED')
    ),
    constraint ck_harness_tool_invocation_retry_wait check (
        status <> 'RETRY_WAIT'
        or (next_attempt_at is not null
            and worker_token is null and worker_until is null
            and started_at is not null and deadline_at is not null
            and last_activity_at is not null
            and finished_at is null and result is null and error is null
            and permission_state = 'ALLOWED')
    ),
    constraint ck_harness_tool_invocation_terminal check (
        status not in ('SUCCEEDED','FAILED','CANCELLED','UNKNOWN')
        or (finished_at is not null and next_attempt_at is null
            and worker_token is null and worker_until is null)
    ),
    constraint ck_harness_tool_invocation_applied check (
        applied_at is null
        or (status in ('SUCCEEDED','FAILED','CANCELLED','UNKNOWN')
            and finished_at is not null
            and applied_at >= finished_at)
    ),
    constraint ck_harness_tool_invocation_succeeded_payload check (
        status <> 'SUCCEEDED'
        or (result is not null and error is null
            and started_at is not null
            and deadline_at is not null
            and last_activity_at is not null
            and permission_state = 'ALLOWED')
    ),
    constraint ck_harness_tool_invocation_failed_payload check (
        status <> 'FAILED'
        or (result is null and error is not null
            and started_at is not null
            and deadline_at is not null
            and last_activity_at is not null
            and permission_state in ('PENDING', 'ALLOWED', 'DENIED'))
    ),
    constraint ck_harness_tool_invocation_unknown_payload check (
        status <> 'UNKNOWN'
        or (result is null and error is not null
            and started_at is not null
            and deadline_at is not null
            and last_activity_at is not null
            and permission_state = 'ALLOWED')
    ),
    constraint ck_harness_tool_invocation_cancelled_payload check (
        status <> 'CANCELLED'
        or (result is null and error is null
            and ((started_at is null and deadline_at is null and last_activity_at is null)
                or (started_at is not null
                    and deadline_at is not null
                    and last_activity_at is not null))
            and permission_state in ('PENDING', 'ALLOWED'))
    ),
    constraint ck_harness_tool_invocation_time_order check (
        (started_at is null or started_at >= created_at)
        and (deadline_at is null or (started_at is not null and deadline_at > started_at))
        and (worker_until is null
            or (started_at is not null and worker_until > started_at))
        and (last_activity_at is null
            or (started_at is not null and last_activity_at >= started_at))
        and (finished_at is null
            or finished_at >= coalesce(started_at, created_at))
        and (finished_at is null
            or last_activity_at is null
            or last_activity_at <= finished_at)
        and (next_attempt_at is null
            or (deadline_at is not null
                and last_activity_at is not null
                and next_attempt_at > last_activity_at
                and next_attempt_at < deadline_at))
    )
);

create index idx_harness_tool_invocation_environment_claim
    on harness_tool_invocation (environment_name, status, next_attempt_at, id);
create index idx_harness_tool_invocation_model
    on harness_tool_invocation (model_invocation_id);
create index idx_harness_tool_invocation_environment_queue
    on harness_tool_invocation (environment_name, created_at, assistant_entry_id, ordinal, id)
    where environment_name is not null
      and status in ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'WAITING_INTERACTION');
create index idx_harness_tool_invocation_status
    on harness_tool_invocation (status, id);
create index idx_harness_tool_invocation_worker_until
    on harness_tool_invocation (worker_until, id) where status = 'RUNNING';
create index idx_harness_tool_invocation_deadline
    on harness_tool_invocation (deadline_at, id) where status = 'RUNNING';
create index idx_harness_tool_invocation_assistant
    on harness_tool_invocation (session_id, assistant_entry_id);
create index idx_harness_tool_invocation_terminal_unapplied
    on harness_tool_invocation (thread_id, id)
    where applied_at is null
      and status in ('SUCCEEDED','FAILED','CANCELLED','UNKNOWN');

create table harness_interaction (
    id              bigint        primary key default nextval('kk_studio_id_seq'),
    tool_invocation_id bigint     not null,
    request         jsonb         not null,
    status          varchar(16)   not null,
    response        jsonb,
    version         bigint        not null,
    created_at      timestamptz(3) not null default current_timestamp,
    resolved_at     timestamptz(3),
    constraint ck_harness_interaction_tool_invocation_id_pos check (tool_invocation_id > 0),
    constraint ck_harness_interaction_status check (
        status in ('OPEN','RESOLVED')
    ),
    constraint ck_harness_interaction_version_nonneg check (version >= 0),
    -- OPEN: no response, not resolved.
    constraint ck_harness_interaction_open check (
        status <> 'OPEN'
        or (response is null and resolved_at is null)
    ),
    -- RESOLVED: response and resolved_at both present.
    constraint ck_harness_interaction_resolved check (
        status <> 'RESOLVED'
        or (response is not null and resolved_at is not null)
    ),
    constraint ck_harness_interaction_time_order check (
        resolved_at is null or resolved_at >= created_at
    )
);

create unique index uk_harness_interaction_open
    on harness_interaction (tool_invocation_id)
    where status = 'OPEN';
create index idx_harness_interaction_tool_invocation
    on harness_interaction (tool_invocation_id);

create table harness_retry_policy (
    id                  integer       primary key,
    max_retries         integer       not null,
    backoff_strategy    varchar(32)   not null,
    base_delay_millis   bigint        not null,
    max_delay_millis    bigint        not null,
    constraint ck_harness_retry_policy_singleton check (id = 1),
    constraint ck_harness_retry_policy_max_retries_nonneg check (max_retries >= 0),
    constraint ck_harness_retry_policy_backoff check (
        backoff_strategy in ('FIXED','EXPONENTIAL')
    ),
    constraint ck_harness_retry_policy_base_delay_pos check (base_delay_millis > 0),
    constraint ck_harness_retry_policy_max_delay_ge_base check (max_delay_millis >= base_delay_millis)
);

create table harness_realtime_stream_policy (
    id              integer       primary key,
    max_length       bigint        not null,
    constraint ck_harness_realtime_stream_policy_singleton check (id = 1),
    constraint ck_harness_realtime_stream_policy_max_length_pos check (max_length > 0)
);

create table harness_thread_goal (
    thread_id       bigint        primary key,
    objective       text          not null,
    token_budget    bigint,
    status          varchar(16)   not null,
    reason          text,
    created_at      timestamptz(3) not null default current_timestamp,
    updated_at      timestamptz(3) not null default current_timestamp,
    constraint fk_harness_thread_goal_thread foreign key (thread_id)
        references harness_thread (id),
    constraint ck_harness_thread_goal_status check (
        status in ('active','complete','blocked')
    ),
    constraint ck_harness_thread_goal_objective check (
        char_length(btrim(objective)) > 0
    ),
    constraint ck_harness_thread_goal_token_budget_pos check (
        token_budget is null or token_budget > 0
    ),
    constraint ck_harness_thread_goal_reason check (
        (status = 'active' and reason is null)
        or (status in ('complete','blocked')
            and reason is not null
            and char_length(btrim(reason)) > 0)
    ),
    constraint ck_harness_thread_goal_time_order check (
        updated_at >= created_at
    )
);

create table harness_artifact (
    id          bigint        primary key default nextval('kk_studio_id_seq'),
    media_type  varchar(128)  not null,
    encoding    varchar(64)   not null,
    content     bytea         not null,
    size_bytes  bigint        not null,
    sha256      varchar(64)   not null,
    created_at  timestamptz(3) not null default current_timestamp,
    constraint ck_harness_artifact_size check (
        size_bytes >= 0 and size_bytes = octet_length(content)
    ),
    constraint ck_harness_artifact_sha256_format check (
        sha256 ~ '^[0-9a-f]{64}$'
    )
);

------------------------------------------------------------------------------
-- 2.5 Harness durable execution target (sole activation queue)
--
-- A single target row persists for the entire work lifecycle and is the only
-- durable activation queue. dispatch_enabled is the explicit gate: disabled
-- rows remain visible to ownership/reconciliation inspection but are excluded
-- from due scans and nearest-due timing. NULL route_key targets are eligible
-- on every node; non-NULL route_key targets are eligible only on a node whose
-- route eligibility snapshot contains that key. ENVIRONMENT Tool targets are
-- initially parked and only the FIFO route head is enabled. FIFO order is the
-- owning Tool invocation's created_at, assistant_entry_id, ordinal, id.
-- An enabled insert, an enable transition, or a strictly-earlier move of an
-- enabled row triggers transactional NOTIFY; lease extensions and disabled-row
-- rewrites do not.
------------------------------------------------------------------------------

create table harness_execution_target (
    target_kind     varchar(32)  not null,
    target_id       bigint       not null,
    route_key       varchar(128),
    dispatch_enabled boolean      not null default true,
    available_at    timestamptz(3) not null,
    constraint pk_harness_execution_target primary key (target_kind, target_id),
    constraint ck_harness_execution_target_kind check (
        target_kind in ('THREAD', 'MODEL_INVOCATION', 'TOOL_INVOCATION')
    ),
    constraint ck_harness_execution_target_id_pos check (target_id > 0),
    constraint ck_harness_execution_target_route_key check (
        route_key is null or char_length(btrim(route_key)) > 0
    )
);

create index idx_harness_execution_target_due
    on harness_execution_target (available_at, target_kind, target_id)
    where dispatch_enabled;

create index idx_harness_execution_target_route
    on harness_execution_target (route_key, available_at)
    where dispatch_enabled and route_key is not null;

-- The activation query must see parked rows as well as enabled rows. Keep this
-- route queue index non-partial; dispatcher scans use the partial indexes above.
create index idx_harness_execution_target_route_queue
    on harness_execution_target (route_key, target_kind, target_id);

-- Only an enabled insertion wakes a dispatcher. Parked rows are durable but
-- cannot be dispatched until the route head is activated.
create or replace function harness_execution_target_notify_insert()
returns trigger language plpgsql as $$
begin
    if new.dispatch_enabled then
        perform pg_notify('harness_execution_target', '');
    end if;
    return new;
end $$;

create trigger trg_harness_execution_target_notify_insert
    after insert on harness_execution_target
    for each row execute function harness_execution_target_notify_insert();

-- Wake when a row becomes enabled or an enabled row moves strictly earlier.
-- Lease extension, equal-time rewrites, and all disabled-row rewrites stay
-- silent. Keep dispatch_enabled in the UPDATE OF list so an enable transition
-- invokes this function.
create or replace function harness_execution_target_notify_update()
returns trigger language plpgsql as $$
begin
    if new.dispatch_enabled
       and (not old.dispatch_enabled or new.available_at < old.available_at) then
        perform pg_notify('harness_execution_target', '');
    end if;
    return new;
end $$;

create trigger trg_harness_execution_target_notify_update
    after update of dispatch_enabled, available_at on harness_execution_target
    for each row execute function harness_execution_target_notify_update();

------------------------------------------------------------------------------
-- 3. Harness model_usage ledger (depends on harness_* framework)
------------------------------------------------------------------------------

create table harness_model_usage (
    id                                 bigint        primary key default nextval('kk_studio_id_seq'),
    session_id                         bigint        not null,
    thread_id                          bigint        not null,
    assistant_entry_id                 bigint        not null,
    provider_name                      varchar(64)   not null,
    model_name                         varchar(128)  not null,
    provider_type                      varchar(64)   not null,
    prompt_cache_mode                  varchar(32)   not null,
    prompt_cache_retention             varchar(16)   not null,
    cache_eligible                     boolean       not null,
    cache_affinity_key                 varchar(512),
    stop_reason                        varchar(32)   not null,
    usage_input_tokens                 bigint        not null,
    usage_output_tokens                bigint        not null,
    usage_cache_read_tokens            bigint        not null,
    usage_cache_write_tokens           bigint        not null,
    usage_cache_write_long_tokens      bigint        not null,
    usage_reasoning_tokens             bigint        not null,
    usage_provider_total_tokens        bigint        not null,
    pricing_currency                   varchar(16)   not null,
    pricing_tier                       varchar(64)   not null,
    pricing_service_tier               varchar(64)   not null,
    pricing_service_tier_multiplier    numeric(32,12) not null,
    pricing_version                    varchar(64)   not null,
    pricing_input_per_million_tokens   numeric(32,12) not null,
    pricing_output_per_million_tokens  numeric(32,12) not null,
    pricing_cache_read_per_million_tokens  numeric(32,12) not null,
    pricing_cache_write_per_million_tokens numeric(32,12) not null,
    pricing_cache_write_long_per_million_tokens numeric(32,12) not null,
    pricing_reasoning_per_million_tokens numeric(32,12) not null,
    request_id                         varchar(256),
    reported_service_tier              varchar(64),
    raw_usage                          jsonb         not null,
    created_at                         timestamptz(3) not null default current_timestamp,
    constraint uk_harness_model_usage_assistant_entry unique (assistant_entry_id),
    -- Owning Thread is referenced by id only; the thread<->session relationship
    -- is enforced upstream by Thread commands via the head Entry.
    constraint fk_harness_model_usage_thread foreign key (thread_id)
        references harness_thread (id),
    -- session_id stays as a constraint carrier so the assistant Entry belongs
    -- to the same Session as the carrier.
    constraint fk_harness_model_usage_assistant_entry
        foreign key (session_id, assistant_entry_id)
        references harness_entry (session_id, id),
    constraint ck_harness_model_usage_token_counts_nonneg check (
        usage_input_tokens >= 0
        and usage_output_tokens >= 0
        and usage_cache_read_tokens >= 0
        and usage_cache_write_tokens >= 0
        and usage_cache_write_long_tokens >= 0
        and usage_reasoning_tokens >= 0
        and usage_provider_total_tokens >= 0
    ),
    constraint ck_harness_model_usage_pricing_nonneg check (
        pricing_input_per_million_tokens >= 0
        and pricing_output_per_million_tokens >= 0
        and pricing_cache_read_per_million_tokens >= 0
        and pricing_cache_write_per_million_tokens >= 0
        and pricing_cache_write_long_per_million_tokens >= 0
        and pricing_reasoning_per_million_tokens >= 0
        and pricing_service_tier_multiplier >= 0
    )
);

create index idx_harness_model_usage_thread
    on harness_model_usage (thread_id, id);
create index idx_harness_model_usage_session
    on harness_model_usage (session_id, id);
create index idx_harness_model_usage_model
    on harness_model_usage (provider_name, model_name, id);

------------------------------------------------------------------------------
-- 3.5 Thread revision invalidation
--
-- revision is the sole durable cursor for Thread projections. PostgreSQL
-- triggers keep the increment in the mutating transaction; NOTIFY is only a
-- wake-up and its payload is never treated as projection truth. Lease-only
-- updates deliberately do not advance it.
------------------------------------------------------------------------------

create or replace function harness_bump_thread_revision(p_thread_id bigint)
returns void language plpgsql as $$
begin
    update harness_thread
    set revision = revision + 1,
        updated_at = greatest(updated_at, current_timestamp)
    where id = p_thread_id;
end $$;

create or replace function harness_thread_revision_before_update()
returns trigger language plpgsql as $$
begin
    if new.head_entry_id is distinct from old.head_entry_id
       or new.input_sequence is distinct from old.input_sequence
       or new.runnable is distinct from old.runnable
       or new.execution_epoch is distinct from old.execution_epoch then
        new.revision := old.revision + 1;
        new.updated_at := greatest(old.updated_at, current_timestamp);
    elsif new.updated_at < old.updated_at then
        new.updated_at := old.updated_at;
    end if;
    return new;
end $$;

create trigger trg_harness_thread_revision_before_update
    before update on harness_thread
    for each row execute function harness_thread_revision_before_update();

create or replace function harness_thread_revision_notify()
returns trigger language plpgsql as $$
begin
    if new.revision <> old.revision then
        perform pg_notify('harness_thread_revision', new.id::text);
    end if;
    return new;
end $$;

create trigger trg_harness_thread_revision_notify
    after update of revision on harness_thread
    for each row execute function harness_thread_revision_notify();

create or replace function harness_thread_revision_from_child()
returns trigger language plpgsql as $$
declare
    v_thread_id bigint;
begin
    if tg_op = 'DELETE' then
        v_thread_id := old.thread_id;
    else
        v_thread_id := new.thread_id;
    end if;
    perform harness_bump_thread_revision(v_thread_id);
    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end $$;

create trigger trg_harness_thread_input_revision
    after insert or update or delete on harness_thread_input
    for each row execute function harness_thread_revision_from_child();
create trigger trg_harness_model_invocation_revision_insert_delete
    after insert or delete on harness_model_invocation
    for each row execute function harness_thread_revision_from_child();
create trigger trg_harness_model_invocation_revision_update
    after update of status, attempt, next_attempt_at, deadline_at, result, error, applied_at,
        started_at, finished_at on harness_model_invocation
    for each row
    execute function harness_thread_revision_from_child();
create trigger trg_harness_tool_invocation_revision_insert_delete
    after insert or delete on harness_tool_invocation
    for each row execute function harness_thread_revision_from_child();
create trigger trg_harness_tool_invocation_revision_update
    after update of status, attempt, next_attempt_at, deadline_at, result, error, applied_at,
        started_at, finished_at, permission_state, yolo_enabled on harness_tool_invocation
    for each row
    execute function harness_thread_revision_from_child();
create trigger trg_harness_model_usage_revision
    after insert on harness_model_usage
    for each row execute function harness_thread_revision_from_child();

create or replace function harness_thread_revision_from_interaction()
returns trigger language plpgsql as $$
declare
    v_tool_invocation_id bigint;
    v_thread_id bigint;
begin
    if tg_op = 'DELETE' then
        v_tool_invocation_id := old.tool_invocation_id;
    else
        v_tool_invocation_id := new.tool_invocation_id;
    end if;
    select thread_id into v_thread_id
    from harness_tool_invocation
    where id = v_tool_invocation_id;
    if v_thread_id is not null then
        perform harness_bump_thread_revision(v_thread_id);
    end if;
    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end $$;

create trigger trg_harness_interaction_revision
    after insert or update or delete on harness_interaction
    for each row execute function harness_thread_revision_from_interaction();

------------------------------------------------------------------------------
-- 4. Forward FKs whose targets now exist
------------------------------------------------------------------------------

-- harness_entry.session_id references harness_session.
alter table harness_entry
    add constraint fk_harness_entry_session
    foreign key (session_id)
    references harness_session (id);

-- harness_artifact immutable; no FK required.

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
