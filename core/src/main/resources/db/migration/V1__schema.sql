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
-- (the seven tables + their indexes) is a single contiguous block copied
-- verbatim from
-- `harness/runtime-spring/.../postgresql/harness-runtime-schema.sql` and must
-- stay byte-identical to that file (guarded by an architecture test).
-- HarnessRuntime owns every execution id via the injected Supplier<UUID>
-- (production: UUID::randomUUID) and owns `revision`; the database never
-- mutates them. The only V1 additions around it are the application business
-- tables and the thread revision NOTIFY hint trigger (section 4).
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
    id                uuid          primary key,
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
    id              bigint         primary key default nextval('kk_studio_id_seq'),
    title           varchar(256)   not null,
    graph_revision  bigint         not null default 0,
    created_at      timestamptz(3) not null default current_timestamp,
    updated_at      timestamptz(3) not null default current_timestamp,
    constraint ck_canvas_document_title_nonblank check (btrim(title) <> ''),
    constraint ck_canvas_document_revision_nonneg check (graph_revision >= 0),
    constraint ck_canvas_document_time_order check (updated_at >= created_at),
    constraint ck_canvas_document_id_pos check (id > 0)
);

create index idx_canvas_document_updated
    on canvas_document (updated_at, id);

create table canvas_group (
    id           bigint         primary key default nextval('kk_studio_id_seq'),
    canvas_id    bigint         not null,
    title        varchar(256)   not null,
    x            double precision not null,
    y            double precision not null,
    width        double precision not null,
    height       double precision not null,
    constraint ck_canvas_group_id_pos check (id > 0),
    constraint ck_canvas_group_canvas_id_pos check (canvas_id > 0),
    constraint ck_canvas_group_title_nonblank check (btrim(title) <> ''),
    constraint ck_canvas_group_geometry check (
        x not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and y not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and width not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and height not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and width > 0 and height > 0
    ),
    constraint uk_canvas_group_canvas_id unique (canvas_id, id)
);

create index idx_canvas_group_canvas on canvas_group (canvas_id, id);

create table canvas_node (
    id                    bigint         primary key default nextval('kk_studio_id_seq'),
    canvas_id             bigint         not null,
    name                  varchar(256)   not null,
    name_normalized       varchar(256)   not null,
    x                     double precision not null,
    y                     double precision not null,
    width                 double precision not null,
    height                double precision not null,
    group_id              bigint,
    model_key             varchar(256),
    function_config_json  jsonb,
    constraint ck_canvas_node_id_pos check (id > 0),
    constraint ck_canvas_node_canvas_id_pos check (canvas_id > 0),
    constraint ck_canvas_node_group_id_pos check (group_id is null or group_id > 0),
    constraint ck_canvas_node_name_nonblank check (btrim(name) <> ''),
    constraint ck_canvas_node_name_normalized_nonblank check (btrim(name_normalized) <> ''),
    constraint ck_canvas_node_geometry check (
        x not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and y not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and width not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and height not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and width > 0 and height > 0
    ),
    constraint ck_canvas_node_function_pair check (
        (model_key is null and function_config_json is null)
        or (
            model_key is not null
            and btrim(model_key) <> ''
            and function_config_json is not null
            and jsonb_typeof(function_config_json) = 'object'
        )
    ),
    constraint uk_canvas_node_canvas_id unique (canvas_id, id)
);

create index idx_canvas_node_canvas on canvas_node (canvas_id, id);
create index idx_canvas_node_group on canvas_node (canvas_id, group_id)
    where group_id is not null;
create unique index uk_canvas_node_name_normalized
    on canvas_node (canvas_id, name_normalized);

create table canvas_resource (
    id             bigint         primary key default nextval('kk_studio_id_seq'),
    canvas_id      bigint         not null,
    kind           varchar(16)    not null,
    media_type     varchar(256)   not null,
    name           varchar(256)   not null,
    size           bigint         not null,
    text_content   text,
    metadata_json  jsonb          not null,
    created_at     timestamptz(3) not null default current_timestamp,
    constraint ck_canvas_resource_id_pos check (id > 0),
    constraint ck_canvas_resource_canvas_id_pos check (canvas_id > 0),
    constraint ck_canvas_resource_kind check (kind in ('IMAGE', 'VIDEO', 'AUDIO', 'TEXT')),
    constraint ck_canvas_resource_media_type_nonblank check (btrim(media_type) <> ''),
    constraint ck_canvas_resource_name_nonblank check (btrim(name) <> ''),
    constraint ck_canvas_resource_size_nonneg check (size >= 0),
    constraint ck_canvas_resource_text_shape check (
        (kind = 'TEXT' and text_content is not null)
        or (kind <> 'TEXT' and text_content is null)
    ),
    constraint ck_canvas_resource_metadata_object check (jsonb_typeof(metadata_json) = 'object'),
    constraint uk_canvas_resource_canvas_id unique (canvas_id, id)
);

create index idx_canvas_resource_canvas_created
    on canvas_resource (canvas_id, created_at, id);

create table canvas_upload (
    id                   bigint         primary key default nextval('kk_studio_id_seq'),
    canvas_id            bigint         not null,
    kind                 varchar(16)    not null,
    filename             varchar(512)   not null,
    declared_media_type  varchar(256)   not null,
    declared_size        bigint         not null,
    expires_at           timestamptz(3) not null,
    created_at           timestamptz(3) not null default current_timestamp,
    constraint ck_canvas_upload_id_pos check (id > 0),
    constraint ck_canvas_upload_canvas_id_pos check (canvas_id > 0),
    constraint ck_canvas_upload_kind check (kind in ('IMAGE', 'VIDEO', 'AUDIO')),
    constraint ck_canvas_upload_filename_nonblank check (btrim(filename) <> ''),
    constraint ck_canvas_upload_media_type_nonblank check (btrim(declared_media_type) <> ''),
    constraint ck_canvas_upload_size_nonneg check (declared_size >= 0),
    constraint ck_canvas_upload_expiry check (expires_at > created_at)
);

create index idx_canvas_upload_canvas_expiry
    on canvas_upload (canvas_id, expires_at, id);

create table canvas_node_resource (
    canvas_id       bigint   not null,
    node_id         bigint   not null,
    resource_index  integer  not null,
    resource_id     bigint   not null,
    constraint pk_canvas_node_resource primary key (canvas_id, node_id, resource_index),
    constraint ck_canvas_node_resource_canvas_id_pos check (canvas_id > 0),
    constraint ck_canvas_node_resource_node_id_pos check (node_id > 0),
    constraint ck_canvas_node_resource_index_nonneg check (resource_index >= 0),
    constraint ck_canvas_node_resource_resource_id_pos check (resource_id > 0)
);

create index idx_canvas_node_resource_resource
    on canvas_node_resource (canvas_id, resource_id);

create table canvas_link (
    canvas_id       bigint        not null,
    source_node_id  bigint        not null,
    target_node_id  bigint        not null,
    constraint pk_canvas_link primary key (canvas_id, source_node_id, target_node_id),
    constraint ck_canvas_link_canvas_id_pos check (canvas_id > 0),
    constraint ck_canvas_link_source_id_pos check (source_node_id > 0),
    constraint ck_canvas_link_target_id_pos check (target_node_id > 0),
    constraint ck_canvas_link_distinct check (source_node_id <> target_node_id)
);

create index idx_canvas_link_target
    on canvas_link (canvas_id, target_node_id, source_node_id);

create table canvas_function_run (
    node_id      bigint         primary key,
    request_id   varchar(128)   not null,
    status       varchar(16)    not null,
    state_json   jsonb          not null,
    error        text,
    updated_at   timestamptz(3) not null default current_timestamp,
    constraint ck_canvas_function_run_node_id_pos check (node_id > 0),
    constraint ck_canvas_function_run_request_id check (
        btrim(request_id) <> ''
        and request_id = btrim(request_id)
        and request_id !~ '[[:cntrl:]]'
    ),
    constraint ck_canvas_function_run_status check (
        status in ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    constraint ck_canvas_function_run_state_object check (jsonb_typeof(state_json) = 'object')
);

create index idx_canvas_function_run_running
    on canvas_function_run (node_id)
    where status = 'RUNNING';

create table canvas_command_dedup (
    canvas_id        bigint         not null,
    command_id       varchar(128)   not null,
    request_hash     varchar(64)    not null,
    applied_revision bigint         not null,
    created_at       timestamptz(3) not null default current_timestamp,
    constraint pk_canvas_command_dedup primary key (canvas_id, command_id),
    constraint ck_canvas_command_dedup_canvas_id_pos check (canvas_id > 0),
    constraint ck_canvas_command_dedup_command_id_nonblank check (btrim(command_id) <> ''),
    constraint ck_canvas_command_dedup_request_hash check (request_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_canvas_command_dedup_applied_revision_nonneg check (applied_revision >= 0)
);

create table chat (
    id                  uuid          primary key,
    title               varchar(256),
    -- agent_name 故意不加 FK：它只按名称引用 Agent。Agent 硬删除期间该引用失效
    -- （turn/attempt fail closed），同名重建后既有 Chat 引用解析到当前 AgentDefinition。
    agent_name          varchar(64)   not null,
    -- 新空面板/线程草稿的默认分支 Environment 逻辑路由名称（可空；用户发送前可显式更改或清空）。
    environment_name    varchar(64),
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
            environment_name ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
            and char_length(environment_name) <= 64
        )
    )
);

create index idx_chat_modified on chat (updated_at, created_at);

------------------------------------------------------------------------------
-- 2. Harness runtime execution protocol
--
-- The block below (the seven tables + their indexes, including comments) is
-- copied verbatim from
-- harness/runtime-spring/src/main/resources/fun/fengwk/kkstudio/harness/runtime/
-- spring/postgresql/harness-runtime-schema.sql and must stay byte-identical to
-- that file. Columns, checks, FKs and indexes must never drift; HarnessRuntime
-- allocates ids via the injected Supplier<UUID> (production: UUID::randomUUID)
-- and inserts them explicitly (no column defaults). ThreadCommand has no
-- surrogate primary key: identity is (thread_id, sequence).
------------------------------------------------------------------------------

-- Harness Runtime durable schema.
--
-- 所有 Harness 生成的持久实体 ID（Session / Entry / Thread / ThreadCommand client id / ModelInvocation /
-- ToolInvocation / WorkTarget）均为 PostgreSQL uuid，由 HarnessStore 注入的 Supplier<UUID> 生成（生产：
-- UUID::randomUUID），本 schema 不再提供任何序列。ThreadCommand 无代理主键，身份为 (thread_id, sequence)。
-- 时间列统一使用毫秒精度 timestamptz(3)，与 HarnessStore 的时间精度契约一致。

create table harness_session (
    id uuid primary key,
    created_at timestamptz(3) not null
);

comment on table harness_session is 'Session 聚合边界：组织一份 append-only Entry Tree，不拥有 Thread；只记录自身与创建时间';
comment on column harness_session.id is 'Session 的全局唯一 UUID';
comment on column harness_session.created_at is 'Session 创建时间（毫秒精度）';

create table harness_entry (
    id uuid primary key,
    session_id uuid not null,
    parent_entry_id uuid,
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
            'COMPACTION',
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

comment on table harness_entry is '不可变 Entry：append-only 树节点，ROOT 必须先于其他 Entry，parent 链必须连续且同 Session';
comment on column harness_entry.id is 'Entry 的全局唯一 UUID';
comment on column harness_entry.session_id is '所属 Session';
comment on column harness_entry.parent_entry_id is '父 Entry；ROOT 为 null，其余必须非 null 且不能指向自身';
comment on column harness_entry.entry_type is 'Entry 类型（ROOT/TURN_START/MESSAGE/CUSTOM/CUSTOM_MESSAGE/ASSISTANT_ERROR/ASSISTANT_ABORTED/COMPACTION/TURN_END）';
comment on column harness_entry.payload is '按 entry_type 编码的不可变 payload（JSON object）';
comment on column harness_entry.created_at is 'Entry 创建时间（毫秒精度）';

create unique index uk_harness_entry_single_root
    on harness_entry (session_id)
    where entry_type = 'ROOT';

create index idx_harness_entry_parent
    on harness_entry (session_id, parent_entry_id);

comment on index uk_harness_entry_single_root is '每个 Session 至多一个 ROOT Entry';
comment on index idx_harness_entry_parent is 'parent 回溯与同 Session 树遍历索引';

create table harness_thread (
    id uuid primary key,
    head_entry_id uuid not null,
    yolo_enabled boolean not null,
    next_command_sequence bigint not null check (next_command_sequence >= 1),
    revision bigint not null check (revision >= 0),
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_harness_thread_head foreign key (head_entry_id)
        references harness_entry (id),
    constraint ck_harness_thread_time_order check (updated_at >= created_at)
);

comment on table harness_thread is 'Thread：指向 head Entry 的游标状态机，revision 随每次对外字段变化精确 +1';
comment on column harness_thread.id is 'Thread 的全局唯一 UUID';
comment on column harness_thread.head_entry_id is '当前 head Entry（必须存在）';
comment on column harness_thread.yolo_enabled is '当前 yolo 模式开关';
comment on column harness_thread.next_command_sequence is '下一条 Command 的 sequence（从 1 递增）';
comment on column harness_thread.revision is '并发控制版本：任何对外字段变化必须 +1';
comment on column harness_thread.created_at is 'Thread 创建时间（毫秒精度）';
comment on column harness_thread.updated_at is 'Thread 最后更新时间（毫秒精度），不得早于 created_at';

create table harness_thread_command (
    thread_id uuid not null,
    sequence bigint not null check (sequence > 0),
    command_type varchar(32) not null,
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    client_command_id uuid not null,
    request_hash char(64) not null,
    consumed_turn_start_entry_id uuid,
    cancelled_at timestamptz(3),
    created_at timestamptz(3) not null,
    primary key (thread_id, sequence),
    constraint fk_harness_thread_command_thread foreign key (thread_id)
        references harness_thread (id),
    constraint fk_harness_thread_command_consumed foreign key (consumed_turn_start_entry_id)
        references harness_entry (id),
    constraint uk_harness_thread_command_client unique (thread_id, client_command_id),
    constraint ck_harness_thread_command_type check (
        command_type in (
            'USER_MESSAGE',
            'CUSTOM_MESSAGE',
            'SET_AGENT',
            'SET_MODEL',
            'SET_ACTIVE_TOOLS',
            'SET_YOLO',
            'SET_ENVIRONMENT'
        )
    ),
    constraint ck_harness_thread_command_request_hash check (
        request_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint ck_harness_thread_command_terminal check (
        consumed_turn_start_entry_id is null or cancelled_at is null
    ),
    constraint ck_harness_thread_command_cancel_time check (
        cancelled_at is null or cancelled_at >= created_at
    )
);

comment on table harness_thread_command is 'ThreadCommand：无代理主键，身份为 (thread_id, sequence)；QUEUED 只能推进为 APPLIED 或 CANCELLED';
comment on column harness_thread_command.thread_id is '所属 Thread';
comment on column harness_thread_command.sequence is 'Thread 内单调递增序号（与身份一起构成主键）';
comment on column harness_thread_command.command_type is 'Command payload 类型';
comment on column harness_thread_command.payload is '按 command_type 编码的 payload（JSON object）';
comment on column harness_thread_command.client_command_id is '客户端幂等 ID（UUID），同一 Thread 内唯一';
comment on column harness_thread_command.request_hash is '客户端 raw 命令（含 ordered contents 与 uploadId）的 canonical SHA-256（64 小写 hex）；同 clientCommandId 重放必须精确匹配';
comment on column harness_thread_command.consumed_turn_start_entry_id is 'APPLIED 时消费的 TURN_START Entry；与 cancelled_at 互斥';
comment on column harness_thread_command.cancelled_at is 'CANCELLED 时间；不得早于 created_at';
comment on column harness_thread_command.created_at is 'Command 创建时间（毫秒精度）';

create index idx_harness_thread_command_queued
    on harness_thread_command (thread_id, sequence)
    where consumed_turn_start_entry_id is null and cancelled_at is null;

comment on index idx_harness_thread_command_queued is '按 sequence 升序读取 QUEUED Command（for update 锁序）';

create table harness_model_invocation (
    id uuid primary key,
    thread_id uuid not null,
    turn_start_entry_id uuid not null,
    basis_head_entry_id uuid not null,
    request jsonb not null check (jsonb_typeof(request) = 'object'),
    status varchar(16) not null,
    attempt integer not null check (attempt >= 0),
    stream_checkpoint jsonb check (
        stream_checkpoint is null or jsonb_typeof(stream_checkpoint) = 'object'
    ),
    result jsonb check (result is null or jsonb_typeof(result) = 'object'),
    error jsonb check (error is null or jsonb_typeof(error) = 'object'),
    result_entry_id uuid,
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

comment on table harness_model_invocation is 'ModelInvocation：一次 model turn 的 durable 生命周期记录（READY/DISPATCHING/RUNNING 与四个 terminal）';
comment on column harness_model_invocation.id is 'ModelInvocation 的全局唯一 UUID';
comment on column harness_model_invocation.thread_id is '所属 Thread';
comment on column harness_model_invocation.turn_start_entry_id is '本次 turn 的 TURN_START Entry';
comment on column harness_model_invocation.basis_head_entry_id is '创建时的 Thread head（basis CAS 快照）';
comment on column harness_model_invocation.request is '冻结的 model 请求（JSON object）';
comment on column harness_model_invocation.status is '生命周期状态';
comment on column harness_model_invocation.attempt is '已确认的 start 尝试次数（从 0 递增）';
comment on column harness_model_invocation.stream_checkpoint is 'RUNNING 流式断点（JSON object，可空）';
comment on column harness_model_invocation.result is 'terminal 成功结果（JSON object，与 error 互斥）';
comment on column harness_model_invocation.error is 'terminal 失败错误（JSON object，与 result 互斥）';
comment on column harness_model_invocation.result_entry_id is '结果 Entry（Assistant/AssistantError/AssistantAborted/COMPACTION），全局唯一';
comment on column harness_model_invocation.created_at is '创建时间（毫秒精度）';
comment on column harness_model_invocation.updated_at is '最后更新时间（毫秒精度），不得早于 created_at';

create unique index uk_harness_model_invocation_result
    on harness_model_invocation (result_entry_id)
    where result_entry_id is not null;

comment on index uk_harness_model_invocation_result is 'resultEntryId 全局唯一（非 null 时）';

create table harness_tool_invocation (
    id uuid primary key,
    model_invocation_id uuid not null,
    assistant_entry_id uuid not null,
    ordinal integer not null check (ordinal >= 0),
    request jsonb not null check (jsonb_typeof(request) = 'object'),
    status varchar(32) not null,
    attempt integer not null check (attempt >= 0),
    approval jsonb check (approval is null or jsonb_typeof(approval) = 'object'),
    result jsonb check (result is null or jsonb_typeof(result) = 'object'),
    effects jsonb not null check (jsonb_typeof(effects) = 'object'),
    error jsonb check (error is null or jsonb_typeof(error) = 'object'),
    result_entry_id uuid,
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
    constraint ck_harness_tool_invocation_effects_status check (
        status = 'SUCCEEDED'
        or effects = '{"version": 1, "customEntries": []}'::jsonb
    ),
    constraint ck_harness_tool_invocation_time_order check (updated_at >= created_at)
);

comment on table harness_tool_invocation is 'ToolInvocation：一次 tool 调用的 durable 生命周期记录，按 (assistant_entry_id, ordinal) 与 assistant 消息对齐';
comment on column harness_tool_invocation.id is 'ToolInvocation 的全局唯一 UUID';
comment on column harness_tool_invocation.model_invocation_id is '所属 ModelInvocation';
comment on column harness_tool_invocation.assistant_entry_id is '携带对应 ToolCall 的 Assistant MESSAGE Entry';
comment on column harness_tool_invocation.ordinal is 'assistant 消息内 tool call 的序号（从 0 递增）';
comment on column harness_tool_invocation.request is '冻结的 tool 请求（JSON object）';
comment on column harness_tool_invocation.status is '生命周期状态（含 WAITING_APPROVAL）';
comment on column harness_tool_invocation.attempt is '已确认的 start 尝试次数（从 0 递增）';
comment on column harness_tool_invocation.approval is '审批记录（JSON object，可空）';
comment on column harness_tool_invocation.result is 'terminal 成功结果（JSON object，与 error 互斥）';
comment on column harness_tool_invocation.effects is '副作用批（JSON object；非 SUCCEEDED 时必须为空批）';
comment on column harness_tool_invocation.error is 'terminal 失败错误（JSON object，与 result 互斥）';
comment on column harness_tool_invocation.result_entry_id is '结果 Entry（TOOL MESSAGE），全局唯一';
comment on column harness_tool_invocation.created_at is '创建时间（毫秒精度）';
comment on column harness_tool_invocation.updated_at is '最后更新时间（毫秒精度），不得早于 created_at';

create unique index uk_harness_tool_invocation_result
    on harness_tool_invocation (result_entry_id)
    where result_entry_id is not null;

comment on index uk_harness_tool_invocation_result is 'resultEntryId 全局唯一（非 null 时）';

create table harness_work (
    target_type varchar(16) not null,
    target_id uuid not null,
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

comment on table harness_work is 'Work：一个 work mailbox target（Thread/Model/Tool 实体）的 durable 调度状态，wake_version 递增防止旧 processor 完成更新的 wake';
comment on column harness_work.target_type is 'target 实体类型（THREAD/MODEL/TOOL）';
comment on column harness_work.target_id is 'target 实体的 UUID（与 target_type 构成主键）';
comment on column harness_work.available_at is '最早可被 claim 的时间（毫秒精度）';
comment on column harness_work.wake_version is 'wake 计数（从 1 递增）';
comment on column harness_work.lease_token is '当前 lease token（与 lease_until 同时存在或同时缺失）';
comment on column harness_work.lease_until is '当前 lease 到期时间（毫秒精度）';

create index idx_harness_work_available
    on harness_work (available_at, target_type, target_id);

create index idx_harness_work_lease_until
    on harness_work (lease_until, target_type, target_id)
    where lease_until is not null;

comment on index idx_harness_work_available is 'claimNextWork 按 (available_at, target_type, target_id) 选取候选';
comment on index idx_harness_work_lease_until is '过期 lease 扫描索引';

------------------------------------------------------------------------------
-- 3. Application-owned tables referencing harness_thread
------------------------------------------------------------------------------

create table chat_thread (
    chat_id     uuid          not null,
    thread_id   uuid          not null,
    created_at  timestamptz(3) not null default current_timestamp,
    constraint pk_chat_thread primary key (chat_id, thread_id),
    constraint uk_chat_thread_thread unique (thread_id),
    constraint fk_chat_thread_chat foreign key (chat_id)
        references chat (id) on delete cascade,
    constraint fk_chat_thread_thread foreign key (thread_id)
        references harness_thread (id) on delete cascade
);

create index idx_chat_thread_thread
    on chat_thread (thread_id, chat_id);


------------------------------------------------------------------------------
-- 4. Thread revision NOTIFY hint
--
-- revision is owned by HarnessRuntime; PostgreSQL never bumps it. This trigger
-- is only a wake-up hint for in-process projection listeners: it notifies when
-- a Thread row is inserted or its revision column actually changed, and never
-- mutates the row. The NOTIFY payload is the canonical UUID text of
-- `harness_thread.id` (`new.id::text`); there are deliberately no child-table
-- revision triggers.
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
-- 5. Canvas ownership and same-canvas composite FKs.
------------------------------------------------------------------------------

alter table canvas_group
    add constraint fk_canvas_group_canvas foreign key (canvas_id)
    references canvas_document (id) on delete cascade;

alter table canvas_node
    add constraint fk_canvas_node_canvas foreign key (canvas_id)
    references canvas_document (id) on delete cascade;

alter table canvas_node
    add constraint fk_canvas_node_group
    foreign key (canvas_id, group_id)
    references canvas_group (canvas_id, id);

alter table canvas_resource
    add constraint fk_canvas_resource_canvas foreign key (canvas_id)
    references canvas_document (id) on delete cascade;

alter table canvas_upload
    add constraint fk_canvas_upload_canvas foreign key (canvas_id)
    references canvas_document (id) on delete cascade;

alter table canvas_node_resource
    add constraint fk_canvas_node_resource_node
    foreign key (canvas_id, node_id)
    references canvas_node (canvas_id, id) on delete cascade;

alter table canvas_node_resource
    add constraint fk_canvas_node_resource_resource
    foreign key (canvas_id, resource_id)
    references canvas_resource (canvas_id, id);

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

alter table canvas_function_run
    add constraint fk_canvas_function_run_node foreign key (node_id)
    references canvas_node (id) on delete cascade;

------------------------------------------------------------------------------
-- 6. Global blob storage
--
-- storage_blob is the deduplicated immutable content address of the global
-- storage foundation. sha256+size_bytes uniquely identify one content in the
-- ACTIVE state (partial unique index); width/height/duration_ms are immutable
-- media facts written once at complete time (nullable until probed). ref_count
-- counts live owner references (a READY upload plus later Canvas/Harness
-- consumers): ACTIVE rows always hold ref_count > 0, DELETING is terminal with
-- ref_count = 0 and keeps the row as cleanup evidence until its S3 objects are
-- removed and the row is conditionally deleted.
--
-- storage_upload is the per-upload contract between the browser and the
-- server: blob_id NULL means PENDING (the client must PUT the content to the
-- deterministic key uploads/{uploadId}/original), non-NULL means READY.
-- candidate_blob_id is the server-pre-assigned blob id for the PENDING->READY
-- transition; it deliberately has no FK because the blob row is only created
-- at complete time. The only FK (blob_id -> storage_blob) is ON DELETE
-- RESTRICT: counted references must never be removed by a CASCADE.
------------------------------------------------------------------------------

create table storage_blob (
    id            uuid           primary key,
    sha256        char(64)       not null,
    size_bytes    bigint         not null,
    media_type    varchar(256)   not null,
    width         integer,
    height        integer,
    duration_ms   bigint,
    ref_count     bigint         not null default 0,
    state         varchar(16)    not null default 'ACTIVE',
    created_at    timestamptz(3) not null default current_timestamp,
    updated_at    timestamptz(3) not null default current_timestamp,
    constraint ck_storage_blob_sha256 check (sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_storage_blob_size_nonneg check (size_bytes >= 0),
    constraint ck_storage_blob_media_type_nonblank check (btrim(media_type) <> ''),
    constraint ck_storage_blob_dimensions_pair check ((width is null) = (height is null)),
    constraint ck_storage_blob_dimensions_positive check (
        width is null or (width > 0 and height > 0)
    ),
    constraint ck_storage_blob_duration_positive check (duration_ms is null or duration_ms > 0),
    constraint ck_storage_blob_state_ref_count check (
        (state = 'ACTIVE' and ref_count > 0) or (state = 'DELETING' and ref_count = 0)
    )
);

create unique index uk_storage_blob_active_hash
    on storage_blob (sha256, size_bytes)
    where state = 'ACTIVE';

create table storage_upload (
    id                  uuid           primary key,
    candidate_blob_id   uuid           not null,
    blob_id             uuid,
    filename            varchar(512)   not null,
    declared_media_type varchar(256)   not null,
    declared_size       bigint         not null,
    declared_sha256     char(64)       not null,
    expires_at          timestamptz(3) not null,
    created_at          timestamptz(3) not null default current_timestamp,
    constraint uk_storage_upload_candidate unique (candidate_blob_id),
    constraint ck_storage_upload_filename_nonblank check (btrim(filename) <> ''),
    constraint ck_storage_upload_media_type_nonblank check (btrim(declared_media_type) <> ''),
    constraint ck_storage_upload_size_nonneg check (declared_size >= 0),
    constraint ck_storage_upload_sha256 check (declared_sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_storage_upload_expiry check (expires_at > created_at),
    constraint fk_storage_upload_blob foreign key (blob_id)
        references storage_blob (id) on delete restrict
);

create index idx_storage_upload_expiry
    on storage_upload (expires_at, id);

comment on table storage_blob is
    'Deduplicated immutable content address of the global blob storage: one'
    ' ACTIVE row per (sha256, size_bytes) content, with immutable media facts'
    ' and a counted reference lifecycle ACTIVE -> DELETING -> row removal.';

comment on column storage_blob.id is
    'Blob id (uuid): deterministic S3 keys are derived from it'
    ' (blobs/{id}/original, blobs/{id}/preview.webp), never persisted.';
comment on column storage_blob.sha256 is 'Lowercase hex SHA-256 of the original content (64 chars).';
comment on column storage_blob.size_bytes is 'Original content size in bytes (non-negative).';
comment on column storage_blob.media_type is
    'Canonical media type probed at complete time (not the client declaration).';
comment on column storage_blob.width is
    'Immutable pixel width probed at complete time; null when not probed.';
comment on column storage_blob.height is
    'Immutable pixel height probed at complete time; null when not probed.';
comment on column storage_blob.duration_ms is
    'Immutable media duration in milliseconds probed at complete time; null for still media.';
comment on column storage_blob.ref_count is
    'Live owner reference count: READY uploads and later Canvas/Harness consumers.';
comment on column storage_blob.state is
    'Lifecycle state: ACTIVE (ref_count > 0) or DELETING (terminal, ref_count = 0).';
comment on column storage_blob.created_at is 'Row creation time (timestamptz, millisecond precision).';
comment on column storage_blob.updated_at is
    'Application-managed last write time (timestamptz, millisecond precision).';

comment on index uk_storage_blob_active_hash is
    'Content dedup: at most one ACTIVE blob row per (sha256, size_bytes);'
    ' DELETING rows stay outside the key so a new upload of the same content can proceed.';

comment on table storage_upload is
    'Per-upload contract: blob_id NULL = PENDING (client PUTs to'
    ' uploads/{uploadId}/original), non-NULL = READY (blob is retained for this upload).';

comment on column storage_upload.id is
    'Upload id (uuid): the deterministic temp key uploads/{id}/original is derived from it.';
comment on column storage_upload.candidate_blob_id is
    'Server-pre-assigned blob id for the PENDING->READY transition (no FK: the'
    ' blob row is created at complete time).';
comment on column storage_upload.blob_id is
    'Referenced blob while READY (FK RESTRICT); NULL while PENDING. The READY'
    ' upload holds exactly one reference to this blob.';
comment on column storage_upload.filename is 'Client-declared original filename (non-blank, <= 512 chars).';
comment on column storage_upload.declared_media_type is 'Client-declared media type (non-blank, <= 256 chars).';
comment on column storage_upload.declared_size is 'Client-declared content size in bytes (non-negative).';
comment on column storage_upload.declared_sha256 is 'Client-declared lowercase hex SHA-256 (64 chars).';
comment on column storage_upload.expires_at is
    'Cleanup deadline: PENDING temp objects and rows, or READY rows plus the'
    ' upload reference, are removed after this instant.';
comment on column storage_upload.created_at is 'Row creation time (timestamptz, millisecond precision).';

comment on index uk_storage_upload_candidate is
    'Every upload pre-assigns a distinct candidate blob id so PENDING rows can'
    ' never collide on the future blob identity.';
comment on index idx_storage_upload_expiry is
    'Expiry sweep: opportunistic SKIP LOCKED batches and startup recovery scan'
    ' expired uploads oldest-first in bounded batches.';

-- Session 级 Blob 引用：Session 的持久化 message（USER/RESOURCE 与 TOOL 结果）通过本表持有 storage_blob 的
-- 活跃引用。ref_count 维护完全由应用层 SessionBlobRefManager 显式执行（insert+retain / delete+release 成对），
-- 绝不依赖 ON DELETE CASCADE 或触发器；两个 FK 都是 RESTRICT，删除 Session / blob 前必须先删除本表对应行。
create table harness_session_blob_ref (
    session_id  uuid          not null,
    blob_id     uuid          not null,
    created_at  timestamptz(3) not null default current_timestamp,
    constraint pk_harness_session_blob_ref primary key (session_id, blob_id),
    constraint fk_harness_session_blob_ref_session foreign key (session_id)
        references harness_session (id),
    constraint fk_harness_session_blob_ref_blob foreign key (blob_id)
        references storage_blob (id)
);

create index idx_harness_session_blob_ref_blob
    on harness_session_blob_ref (blob_id, session_id);

comment on table harness_session_blob_ref is
    'Session 与 storage_blob 的显式引用边：每行恰好对应一次 blob retain，删除时由应用层逐行 release；'
    'FK 均为 RESTRICT，深删除必须先删本表';
comment on column harness_session_blob_ref.session_id is '所属 Harness Session（RESTRICT FK）';
comment on column harness_session_blob_ref.blob_id is '被引用的全局 blob（RESTRICT FK，ACTIVE 行）';
comment on column harness_session_blob_ref.created_at is '引用创建时间（timestamptz，毫秒精度）';

comment on index idx_harness_session_blob_ref_blob is '按 blob 反向枚举持有它的 Session（深删除与对账）';
