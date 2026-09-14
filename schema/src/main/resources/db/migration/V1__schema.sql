-- Flyway PostgreSQL baseline schema for kk-studio.
--
-- Applied by Flyway to an empty database as version 1.
-- Each table is created without `IF NOT EXISTS` so schema drift fails loudly.
-- Time fields use `timestamptz(3)` (millisecond precision, with time zone).
-- Structured payloads use `jsonb`; flags use `boolean`; file blobs are never
-- stored in the database.
-- `updated_at` is application-managed; PostgreSQL never emulates MySQL's
-- ON UPDATE CURRENT_TIMESTAMP.
--
-- Harness execution state is the exact infra protocol: section 2 below
-- (the seven tables + their indexes) is the single authoritative definition of
-- the durable Harness protocol schema. There is no separate infra
-- schema file; this block is the only copy. HarnessRuntime owns every execution
-- id via the injected Supplier<UUID> (production: UUID::randomUUID) and owns
-- `version`; the database never mutates them. Application-owned relation tables
-- and NOTIFY hints surround this protocol without duplicating its state machine.
--
-- Canvas is fully UUID: every Canvas-generated id is allocated by the
-- application (client for node/group/request/command ids, server for
-- document/resource/run-target ids) and inserted explicitly; there are no
-- id sequences. `canvas_document.version` is the single graph version cursor;
-- version changes are hinted to the Canvas version/application event hub via the `canvas_version`
-- NOTIFY trigger (section 5). Resource blobs are owned by the global
-- `storage_blob` refcount lifecycle; Canvas rows only reference them (RESTRICT).
--
-- DDL is grouped so cycle-closing and forward foreign keys are appended only
-- after both target tables exist.

------------------------------------------------------------------------------
-- 1. Business tables (no mutual dependencies)
------------------------------------------------------------------------------

create table environment (
    id                  uuid          primary key,
    name                varchar(64)   not null,
    registration_token  varchar(128)  not null,
    created_at          timestamptz(3) not null default current_timestamp,
    updated_at          timestamptz(3) not null default current_timestamp,
    version             bigint        not null default 0,
    constraint uk_environment_name unique (name),
    constraint uk_environment_registration_token unique (registration_token),
    constraint ck_environment_name check (
        name !~ '^[[:space:]]'
        and name !~ '[[:space:]]$'
        and char_length(name) > 0
        and position('/' in name) = 0
    ),
    constraint ck_environment_registration_token check (
        btrim(registration_token) <> ''
        and char_length(registration_token) <= 128
        and registration_token = btrim(registration_token)
    ),
    constraint ck_environment_version_nonneg check (version >= 0)
);

comment on table environment is '稳定 Environment 注册表：UUID 主键跨重启不变，name 是唯一展示与配置标识，registration_token 是 Daemon 握手凭证';
comment on column environment.id is 'Environment 的全局唯一 UUID（服务端生成，永不变更）';
comment on column environment.name is 'Environment 唯一名称（NFKC trim，<= 64 字符，不含空白或斜杠）';
comment on column environment.registration_token is 'Daemon HELLO 握手的注册凭证（部署侧秘密，仅 create/rotate 响应一次性返回，正常查询绝不泄露）';
comment on column environment.created_at is '创建时间（毫秒精度）';
comment on column environment.updated_at is '最后更新时间（毫秒精度），应用侧维护';
comment on column environment.version is 'CAS 乐观锁版本：非负，从 0 开始，每次更新 +1';

create index idx_environment_updated
    on environment (updated_at, id);

create table agent_provider (
    name            varchar(64)   primary key,
    description     varchar(512),
    provider_type   varchar(64)   not null,
    base_url        varchar(512),
    credential      varchar(512),
    config          jsonb         not null,
    connection_generation_id uuid not null,
    created_at      timestamptz(3) not null default current_timestamp,
    updated_at      timestamptz(3) not null default current_timestamp,
    version         bigint        not null default 0,
    constraint ck_agent_provider_name check (
        name !~ '^[[:space:]]'
        and name !~ '[[:space:]]$'
        and char_length(name) > 0
        and position('/' in name) = 0
    ),
    constraint ck_agent_provider_provider_type check (
        provider_type in ('openai', 'openai_response', 'anthropic', 'google')
    ),
    constraint ck_agent_provider_version_nonneg check (version >= 0)
);

create table agent_model (
    provider_name   varchar(64)   not null,
    name            varchar(128)  not null,
    model_id        varchar(256)  not null,
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
    constraint ck_agent_model_model_id check (
        model_id !~ '^[[:space:]]'
        and model_id !~ '[[:space:]]$'
        and char_length(model_id) > 0
    ),
    constraint fk_agent_model_provider foreign key (provider_name)
        references agent_provider (name),
    constraint ck_agent_model_version_nonneg check (version >= 0)
);

comment on column agent_model.model_id is
    '发往上游 Provider 的真实 wire 模型标识：非空白、无环绕空白、≤256；与逻辑身份 (provider_name, name) 独立且不唯一';

create table agent_definition (
    name            varchar(64)   primary key,
    description     varchar(512),
    system_prompt   text,
    model_provider_name varchar(64) not null,
    model_name      varchar(128)  not null,
    variant         varchar(64),
    -- 每轮 turn 开始时按此 Environment 解析出当时事实（EnvironmentBinding）；
    -- 可空表示不绑定 Environment（unbound branch）。
    environment_id  uuid,
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
    constraint fk_agent_definition_environment foreign key (environment_id)
        references environment (id) on delete restrict,
    constraint ck_agent_definition_version_nonneg check (version >= 0)
);

create index idx_agent_definition_model
    on agent_definition (model_provider_name, model_name);

-- Platform MCP Server 配置。name 是产品侧唯一路由身份且创建后不可变；
-- connection_config 只保存与 connection_type 对应的传输参数。
create table mcp_server (
    id                  uuid          primary key,
    name                varchar(32)   not null,
    connection_type     varchar(16)   not null,
    environment_id      uuid,
    connection_config   jsonb         not null,
    enabled             boolean       not null default true,
    timeout_millis      bigint        not null,
    discovery_status    varchar(16)   not null default 'UNVERIFIED',
    discovered_version  bigint,
    created_at          timestamptz(3) not null default current_timestamp,
    updated_at          timestamptz(3) not null default current_timestamp,
    version             bigint        not null default 0,
    constraint fk_mcp_server_environment foreign key (environment_id)
        references environment (id) on delete restrict,
    constraint ck_mcp_server_name check (
        name ~ '^[a-z][a-z0-9_]*$'
    ),
    constraint ck_mcp_server_connection_type check (
        connection_type in ('REMOTE', 'LOCAL')
    ),
    constraint ck_mcp_server_local_environment check (
        connection_type not in ('REMOTE', 'LOCAL')
        or (connection_type = 'LOCAL' and environment_id is not null)
        or (connection_type = 'REMOTE' and environment_id is null)
    ),
    constraint ck_mcp_server_connection_config_object check (
        jsonb_typeof(connection_config) = 'object'
    ),
    constraint ck_mcp_server_connection_config_shape check (
        connection_type not in ('REMOTE', 'LOCAL')
        or (
            connection_type = 'REMOTE'
            and connection_config ? 'url'
            and connection_config ? 'headers'
            and jsonb_typeof(connection_config -> 'url') = 'string'
            and jsonb_typeof(connection_config -> 'headers') = 'object'
            and not (connection_config ? 'command')
            and not (connection_config ? 'cwd')
            and not (connection_config ? 'env')
        ) or (
            connection_type = 'LOCAL'
            and connection_config ? 'command'
            and connection_config ? 'cwd'
            and connection_config ? 'env'
            and jsonb_typeof(connection_config -> 'command') = 'array'
            and jsonb_array_length(connection_config -> 'command') > 0
            and jsonb_typeof(connection_config -> 'cwd') = 'string'
            and jsonb_typeof(connection_config -> 'env') = 'object'
            and not (connection_config ? 'url')
            and not (connection_config ? 'headers')
        )
    ),
    constraint ck_mcp_server_timeout_positive check (timeout_millis > 0),
    constraint ck_mcp_server_discovery_status check (
        discovery_status in ('UNVERIFIED', 'AVAILABLE', 'FAILED')
    ),
    constraint ck_mcp_server_discovered_version check (
        discovered_version is null
        or (discovered_version >= 0 and discovered_version <= version)
    ),
    constraint ck_mcp_server_version_nonneg check (version >= 0),
    constraint ck_mcp_server_time_order check (updated_at >= created_at)
);

create unique index uk_mcp_server_name
    on mcp_server (name);

create index idx_mcp_server_environment
    on mcp_server (environment_id)
    where environment_id is not null;

comment on table mcp_server is 'Platform MCP Server 持久配置：REMOTE 由 Backend 直连 Streamable HTTP，LOCAL 由绑定的 Environment Daemon 启动 stdio 进程';
comment on column mcp_server.id is 'Server 的全局唯一 UUID（应用侧生成）';
comment on column mcp_server.name is '唯一名（创建后不可变）：^[a-z][a-z0-9_]*$，≤32 字符；同时作为模型工具名 mcp_<server_name>_<tool> 的组成段';
comment on column mcp_server.connection_type is '连接类型：REMOTE（Backend 直连 Streamable HTTP）或 LOCAL（Daemon stdio）';
comment on column mcp_server.environment_id is '关联 Environment UUID：LOCAL 必填且受 restrict 保护；REMOTE 为 null';
comment on column mcp_server.connection_config is '仅含传输参数的 JSON：Remote 含 url/headers，Local 含 command/cwd/env';
comment on column mcp_server.enabled is '公共启用开关：默认 true；false 时即使 AVAILABLE 也不可被 Agent 选择';
comment on column mcp_server.timeout_millis is '正整数毫秒超时：连接、发现与 tools/call 共用';
comment on column mcp_server.discovery_status is '发现状态：UNVERIFIED（未验证）、AVAILABLE（可用）、FAILED（失败）';
comment on column mcp_server.discovered_version is '最近一次成功验证的配置版本：<= version，未成功或变更后为 null';
comment on column mcp_server.created_at is '创建时间（毫秒精度）';
comment on column mcp_server.updated_at is '最后更新时间（毫秒精度），应用侧维护，不得早于 created_at';
comment on column mcp_server.version is '乐观锁行版本：非负，从 0 开始，每次写操作 +1；CAS 更新依据';

-- 从 MCP Server 发现并冻结的工具行。下线工具以 available=false 保留稳定身份。
create table mcp_tool (
    id               uuid          primary key,
    mcp_server_id    uuid          not null,
    source_name      varchar(128)  not null,
    model_name       varchar(64)   not null,
    description      text          not null,
    input_schema     jsonb         not null,
    schema_revision  bigint        not null default 0,
    available        boolean       not null default true,
    constraint fk_mcp_tool_server foreign key (mcp_server_id)
        references mcp_server (id) on delete cascade,
    constraint ck_mcp_tool_source_name check (char_length(source_name) > 0),
    constraint ck_mcp_tool_model_name check (
        model_name ~ '[A-Za-z][A-Za-z0-9_-]*'
    ),
    constraint ck_mcp_tool_description_nonblank check (btrim(description) <> ''),
    constraint ck_mcp_tool_input_schema_object check (
        jsonb_typeof(input_schema) = 'object'
    ),
    constraint ck_mcp_tool_schema_revision_nonneg check (schema_revision >= 0)
);

create unique index uk_mcp_tool_server_source_name
    on mcp_tool (mcp_server_id, source_name);

create unique index uk_mcp_tool_model_name
    on mcp_tool (model_name);

create index idx_mcp_tool_server_available
    on mcp_tool (mcp_server_id, available);

comment on table mcp_tool is 'MCP Server 发现的工具冻结行：稳定 UUID/model_name 支撑 AgentToolId 引用；父 Server 删除时级联清理';
comment on column mcp_tool.id is '工具的全局唯一稳定 UUID（按 (mcp_server_id, source_name) 跨发现保留）';
comment on column mcp_tool.mcp_server_id is '所属 MCP Server；随父行删除级联硬删除';
comment on column mcp_tool.source_name is 'MCP 工具原始名（同一 Server 内唯一，既有行不可变）';
comment on column mcp_tool.model_name is '全局唯一模型可见工具名：mcp_<server_name>_<normalized_source_tool_name>，须满足 ToolDescriptor name 语法且 ≤64';
comment on column mcp_tool.description is '工具描述（非空白），冻结进 ToolDescriptor';
comment on column mcp_tool.input_schema is '工具 JSON input schema（JSON object），冻结进 ToolDescriptor';
comment on column mcp_tool.schema_revision is '模式修订版本：非负，从 0 开始；schema/description 变更或下线重现时递增';
comment on column mcp_tool.available is '是否可用：true 表示在当前发现结果中；false 表示已消失的 tombstone';

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
    id uuid primary key,
    title varchar(256) not null,
    version bigint not null default 0,
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp,
    constraint ck_canvas_document_title_nonblank check (btrim(title) <> ''),
    constraint ck_canvas_document_version_nonneg check (version >= 0)
);

comment on table canvas_document is 'Canvas 聚合头：version 是单调递增的 graph 版本（command expected 游标与 patch 坐标系的公共基准）；Harness 会话归属由 session_owner 表持有';
comment on column canvas_document.id is 'Canvas 全局唯一 UUID（服务端生成）';
comment on column canvas_document.title is '规范化标题（NFKC trim 后非空，<= 256 字符）';
comment on column canvas_document.version is 'graph 版本：任何成功命令批或 Function Run 状态前进恰好 +1';
comment on column canvas_document.created_at is '创建时间（毫秒精度）';
comment on column canvas_document.updated_at is '最后更新时间（毫秒精度），不得早于 created_at';

create index idx_canvas_document_updated
    on canvas_document (updated_at, id);

create table canvas_group (
    id uuid primary key,
    canvas_id uuid not null,
    title varchar(256) not null,
    x double precision not null,
    y double precision not null,
    width double precision not null,
    height double precision not null,
    constraint ck_canvas_group_title_nonblank check (btrim(title) <> ''),
    constraint ck_canvas_group_geometry check (
        x not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and y not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and width not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and height not in ('NaN'::double precision, 'Infinity'::double precision, '-Infinity'::double precision)
        and width > 0 and height > 0
    ),
    constraint uk_canvas_group_canvas unique (canvas_id, id)
);

comment on table canvas_group is '不嵌套、使用 world 坐标的 Canvas group';
comment on column canvas_group.id is 'Group 全局唯一 UUID（客户端生成）';
comment on column canvas_group.canvas_id is '所属 Canvas';
comment on column canvas_group.title is '规范化标题（<= 256 字符）';
comment on column canvas_group.x is 'world 坐标 x（有限数）';
comment on column canvas_group.y is 'world 坐标 y（有限数）';
comment on column canvas_group.width is '正宽度（有限数）';
comment on column canvas_group.height is '正高度（有限数）';

create index idx_canvas_group_canvas on canvas_group (canvas_id, id);

create table canvas_node (
    id uuid primary key,
    canvas_id uuid not null,
    name varchar(256) not null,
    x double precision not null,
    y double precision not null,
    width double precision not null,
    height double precision not null,
    group_id uuid,
    model_key varchar(256),
    function_config_json jsonb,
    constraint ck_canvas_node_name_nonblank check (btrim(name) <> ''),
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
    constraint uk_canvas_node_canvas unique (canvas_id, id)
);

comment on table canvas_node is 'Canvas 中唯一的业务节点形态：普通资源节点（文本/媒体）或 Function 节点（可携带资源输出）';
comment on column canvas_node.id is 'Node 全局唯一 UUID（客户端生成）';
comment on column canvas_node.canvas_id is '所属 Canvas';
comment on column canvas_node.name is '规范化节点名（<= 256 字符）';
comment on column canvas_node.x is 'world 坐标 x（有限数）';
comment on column canvas_node.y is 'world 坐标 y（有限数）';
comment on column canvas_node.width is '正宽度（有限数）';
comment on column canvas_node.height is '正高度（有限数）';
comment on column canvas_node.group_id is '所属 Group（可空）';
comment on column canvas_node.model_key is 'Function model 标识（与 function_config_json 同存同缺）';
comment on column canvas_node.function_config_json is '规范化 Function 配置（JSON object，与 model_key 同存同缺）';

create index idx_canvas_node_canvas on canvas_node (canvas_id, id);
create index idx_canvas_node_group on canvas_node (canvas_id, group_id)
    where group_id is not null;

create table canvas_link (
    canvas_id uuid not null,
    source_node_id uuid not null,
    target_node_id uuid not null,
    constraint pk_canvas_link primary key (canvas_id, source_node_id, target_node_id),
    constraint ck_canvas_link_distinct check (source_node_id <> target_node_id)
);

comment on table canvas_link is 'Link 以 (canvas_id, source_node_id, target_node_id) 作为身份；target 必须是 Function 节点且 source 至少拥有一个当前资源';
comment on column canvas_link.canvas_id is '所属 Canvas';
comment on column canvas_link.source_node_id is 'source 节点（必须与 target 不同）';
comment on column canvas_link.target_node_id is 'target Function 节点';

create index idx_canvas_link_target
    on canvas_link (canvas_id, target_node_id, source_node_id);

create table canvas_resource (
    id uuid primary key,
    canvas_id uuid not null,
    owner_node_id uuid,
    resource_index integer,
    blob_id uuid,
    name varchar(256) not null,
    text_content text,
    created_at timestamptz(3) not null default current_timestamp,
    constraint ck_canvas_resource_owner_pair check (
        (owner_node_id is null) = (resource_index is null)
    ),
    constraint ck_canvas_resource_index_nonneg check (
        resource_index is null or resource_index >= 0
    ),
    constraint ck_canvas_resource_name_nonblank check (btrim(name) <> ''),
    constraint ck_canvas_resource_content check (
        (blob_id is null) <> (text_content is null)
    ),
    constraint uk_canvas_resource_canvas unique (canvas_id, id),
    constraint uk_canvas_resource_owner_index unique (canvas_id, owner_node_id, resource_index)
);

comment on table canvas_resource is '不可变资源行：可见资源直接属于节点（owner_node_id + resource_index）；Function 目标物化和 pinned orphan 可暂时无 owner；内容要么是全局 Storage blob 引用要么是内联文本';
comment on column canvas_resource.id is 'Resource 全局唯一 UUID（服务端生成）';
comment on column canvas_resource.canvas_id is '所属 Canvas';
comment on column canvas_resource.owner_node_id is '所属节点；与 resource_index 同存同缺，无 owner 的资源只可由 Function pin 保活';
comment on column canvas_resource.resource_index is '节点内从 0 递增的资源序号；与 owner_node_id 同存同缺';
comment on column canvas_resource.blob_id is '全局 Storage blob 引用（TEXT 资源为 null，blob 持有者计数由全局存储管理）';
comment on column canvas_resource.name is '资源显示名（<= 256 字符）';
comment on column canvas_resource.text_content is 'TEXT 资源的内联内容（blob 资源为 null），与 blob_id 恰好互斥';
comment on column canvas_resource.created_at is '创建时间（毫秒精度）';

create index idx_canvas_resource_canvas_created
    on canvas_resource (canvas_id, created_at, id);

create table canvas_function_run (
    node_id uuid primary key,
    request_id uuid not null,
    status varchar(16) not null,
    attempt int not null default 0,
    available_at timestamptz(3),
    lease_token varchar(128),
    lease_until timestamptz(3),
    state_json jsonb not null,
    error text,
    updated_at timestamptz(3) not null default current_timestamp,
    created_at timestamptz(3) not null default current_timestamp,
    constraint uk_canvas_function_run_request unique (node_id, request_id),
    constraint ck_canvas_function_run_status check (
        status in ('READY', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    constraint ck_canvas_function_run_attempt_nonneg check (attempt >= 0),
    constraint ck_canvas_function_run_lease_pair check (
        (lease_token is null) = (lease_until is null)
    ),
    constraint ck_canvas_function_run_work_state check (
        (status = 'READY' and available_at is not null and lease_token is null)
        or (status = 'RUNNING' and available_at is null and lease_token is not null)
        or (
            status in ('SUCCEEDED', 'FAILED', 'CANCELLED')
            and available_at is null
            and lease_token is null
        )
    ),
    constraint ck_canvas_function_run_state_object check (jsonb_typeof(state_json) = 'object')
);

comment on table canvas_function_run is 'Function 节点当前/最后一次 Run：PK 为 node_id，request_id 全 UUID 且 (node_id, request_id) 唯一';
comment on column canvas_function_run.node_id is 'Function 节点（一个节点同时至多一个 Run 行）';
comment on column canvas_function_run.request_id is 'Run 请求 UUID（客户端生成，幂等键）';
comment on column canvas_function_run.status is '生命周期：READY / RUNNING / SUCCEEDED / FAILED / CANCELLED';
comment on column canvas_function_run.attempt is '成功 claim 次数；首次 claim 从 0 增加为 1';
comment on column canvas_function_run.available_at is 'READY 可领取时间（毫秒精度），其它状态为 null';
comment on column canvas_function_run.lease_token is 'RUNNING ownership fencing token，其它状态为 null';
comment on column canvas_function_run.lease_until is 'RUNNING lease 截止时间（毫秒精度），其它状态为 null';
comment on column canvas_function_run.state_json is 'typed/versioned 冻结计划与 checkpoint（JSON object）';
comment on column canvas_function_run.error is '公开错误信息（terminal 失败时非空）';
comment on column canvas_function_run.updated_at is '最后更新时间（毫秒精度）';
comment on column canvas_function_run.created_at is '当前 request 创建时间（毫秒精度）';

create index idx_canvas_function_run_claim
    on canvas_function_run (status, available_at, lease_until, created_at, node_id)
    where status in ('READY', 'RUNNING');

create table canvas_command_dedup (
    canvas_id uuid not null,
    idempotency_key uuid not null,
    request_hash char(64) not null,
    constraint pk_canvas_command_dedup primary key (canvas_id, idempotency_key),
    constraint ck_canvas_command_dedup_request_hash check (request_hash ~ '^[0-9a-f]{64}$')
);

comment on table canvas_command_dedup is '命令批幂等：相同 (canvas_id, idempotency_key) 只能以相同 request_hash 精确回放一次；graph 版本由 canvas_document.version 游标负责，本表不冗余存储';
comment on column canvas_command_dedup.canvas_id is '所属 Canvas';
comment on column canvas_command_dedup.idempotency_key is '客户端整批命令的幂等 UUID';
comment on column canvas_command_dedup.request_hash is '整批命令的 SHA-256（64 位小写十六进制）';

create table canvas_function_resource_pin (
    canvas_id uuid not null,
    node_id uuid not null,
    request_id uuid not null,
    role varchar(16) not null,
    resource_id uuid not null,
    constraint pk_canvas_function_resource_pin primary key (canvas_id, node_id, request_id, role, resource_id),
    constraint ck_canvas_function_resource_pin_role check (role in ('INPUT', 'OUTPUT'))
);

comment on table canvas_function_resource_pin is 'Function Run 生命周期内对资源的 pin：INPUT 为启动时冻结的引用资源，OUTPUT 为预分配的目标资源；只保护生命周期，绝不参与 blob 引用计数';
comment on column canvas_function_resource_pin.canvas_id is '所属 Canvas';
comment on column canvas_function_resource_pin.node_id is 'Function 节点';
comment on column canvas_function_resource_pin.request_id is 'Run 请求 UUID';
comment on column canvas_function_resource_pin.role is 'pin 角色：INPUT / OUTPUT';
comment on column canvas_function_resource_pin.resource_id is '被 pin 的资源（OUTPUT 可为尚未物化的预分配 id）';

create index idx_canvas_function_resource_pin_resource
    on canvas_function_resource_pin (canvas_id, resource_id);

create table chat (
    id                  uuid          primary key,
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

create index idx_chat_modified on chat (updated_at, created_at);

------------------------------------------------------------------------------
-- 1b. Environment connections, Skill inventory and operations
------------------------------------------------------------------------------

create table environment_connection (
    environment_id  uuid           primary key,
    owner_node_id   uuid           not null,
    lease_token     uuid           not null,
    status          varchar(32)    not null,
    runtime_info    jsonb,
    last_seen_at    timestamptz(3) not null,
    lease_until     timestamptz(3) not null,
    constraint fk_environment_connection_environment foreign key (environment_id)
        references environment (id) on delete cascade,
    constraint ck_environment_connection_status check (
        status in ('CONNECTING', 'READY')
    ),
    constraint ck_environment_connection_runtime_info check (
        status not in ('CONNECTING', 'READY')
        or (status = 'CONNECTING' and runtime_info is null)
        or (status = 'READY' and runtime_info is not null and jsonb_typeof(runtime_info) = 'object')
    ),
    constraint ck_environment_connection_lease check (
        lease_until > last_seen_at
    )
);

comment on table environment_connection is 'Environment 的 daemon 连接租约：每个 Environment 由持有租约的节点独占路由（fencing 见 lease_token）';
comment on column environment_connection.environment_id is 'Environment 的全局唯一 UUID（PK，FK cascade）';
comment on column environment_connection.owner_node_id is '当前持有该连接路由的 App 节点实例 UUID';
comment on column environment_connection.lease_token is '当前路由租约代币 UUID（每次接管/重绑生成新 token，fence 旧持有者）';
comment on column environment_connection.status is '路由状态：CONNECTING / READY';
comment on column environment_connection.runtime_info is 'READY 状态下 daemon 通告的 JSON 运行信息与能力对象；CONNECTING 为 null';
comment on column environment_connection.last_seen_at is '最后活跃时间（毫秒精度）';
comment on column environment_connection.lease_until is '租约到期时间（毫秒精度），必须晚于 last_seen_at';

create index idx_environment_connection_lease_until
    on environment_connection (lease_until);

create index idx_environment_connection_owner
    on environment_connection (owner_node_id);

-- -----------------------------------------------------------------------------
-- Environment inventory
--
-- 每个 Environment 恰一行当前事实（不是历史头）：source_set_version 是 Platform 期望的
-- 活跃来源集合代际，只有它前进后才会把该代际下发给 Daemon；applied_source_set_version 是
-- 最近一次被围栏接受的 READY 集合版本，永远不超过期望值。报告列（capabilities/OS/时区/备注/
-- root/持有节点/租约/上报时间）同生同灭：没有已接受报告时全空，有报告时全非空。
-- -----------------------------------------------------------------------------

create table environment_inventory (
    environment_id              uuid          primary key,
    source_set_version          bigint        not null default 0,
    applied_source_set_version  bigint,
    capabilities_version        integer,
    operating_system            varchar(16),
    time_zone                   varchar(64),
    note                        varchar(512),
    root_path                   varchar(4096),
    owner_node_id               uuid,
    lease_token                 uuid,
    reported_at                 timestamptz(3),
    created_at                  timestamptz(3) not null default current_timestamp,
    updated_at                  timestamptz(3) not null default current_timestamp,
    constraint fk_environment_inventory_environment foreign key (environment_id)
        references environment (id) on delete cascade,
    constraint ck_environment_inventory_versions_nonneg check (
        source_set_version >= 0
        and (applied_source_set_version is null or applied_source_set_version >= 0)
    ),
    constraint ck_environment_inventory_applied_fence check (
        applied_source_set_version is null
        or applied_source_set_version <= source_set_version
    ),
    constraint ck_environment_inventory_report_shape check (
        case
            when applied_source_set_version is null then
                capabilities_version is null
                and operating_system is null
                and time_zone is null
                and note is null
                and root_path is null
                and owner_node_id is null
                and lease_token is null
                and reported_at is null
            else
                capabilities_version = 2
                and operating_system in ('windows', 'wsl', 'linux', 'macos')
                and time_zone is not null
                and time_zone = btrim(time_zone)
                and char_length(time_zone) > 0
                and note is not null
                and note = btrim(note)
                and char_length(note) > 0
                and root_path is not null
                and root_path = btrim(root_path)
                and char_length(root_path) > 0
                and owner_node_id is not null
                and lease_token is not null
                and reported_at is not null
        end
    ),
    constraint ck_environment_inventory_time_order check (
        updated_at >= created_at
        and (reported_at is null or reported_at >= created_at)
    )
);

comment on table environment_inventory is '每个 Environment 恰一行的 Skill inventory 期望/已应用事实：期望来源集合代际与最近一次被围栏接受的 READY 报告（Platform 独占写入）';
comment on column environment_inventory.environment_id is 'Environment 的全局唯一 UUID（PK，FK cascade）';
comment on column environment_inventory.source_set_version is 'Platform 期望的活跃来源集合代际（非负，从 0 开始，每次来源集合变更 +1）';
comment on column environment_inventory.applied_source_set_version is '最近一次被 READY 围栏接受的来源集合代际；必须不超过 source_set_version';
comment on column environment_inventory.capabilities_version is '已接受 READY 的 capabilities 协议版本（当前恒为 2）';
comment on column environment_inventory.operating_system is '已接受 READY 报告的宿主系统 wire 值：windows/wsl/linux/macos';
comment on column environment_inventory.time_zone is '已接受 READY 报告的 IANA 时区 ID（非空白、无环绕空白）';
comment on column environment_inventory.note is '已接受 READY 报告的可信操作者备注（非空白、无环绕空白）';
comment on column environment_inventory.root_path is '已接受 READY 报告的 Daemon canonical Environment root（仅展示）';
comment on column environment_inventory.owner_node_id is '接受该报告的 App 节点实例 UUID';
comment on column environment_inventory.lease_token is '接受该报告时的路由租约代币，用于识别陈旧报告';
comment on column environment_inventory.reported_at is '该 READY 报告被接受的时间（毫秒精度）';
comment on column environment_inventory.created_at is '创建时间（毫秒精度）';
comment on column environment_inventory.updated_at is '最后更新时间（毫秒精度），应用侧维护';

-- -----------------------------------------------------------------------------
-- Environment Skill sources
--
-- Platform 唯一的来源配置事实。version 同时承担两个职责：行级 CAS 乐观锁，以及下发给
-- Daemon 的 sourceVersion；不存在第二个版本维度。default_source 每个 Environment 至多一个。
-- applied_* 三元组只在来源被 Daemon 成功应用后出现，因此 UNAPPLIED 允许保留旧的已应用事实
-- （配置刚改、Daemon 还没重新应用），但绝不允许残留 last error。
-- -----------------------------------------------------------------------------

create table environment_skill_source (
    source_id           uuid          primary key,
    environment_id      uuid          not null,
    source_type         varchar(16)   not null,
    path                varchar(4096),
    default_source      boolean       not null default false,
    git_url             varchar(4096),
    git_ref             varchar(1024),
    scan_path           varchar(4096),
    version             bigint        not null default 0,
    status              varchar(16)   not null default 'UNAPPLIED',
    applied_version     bigint,
    applied_revision    varchar(64),
    diagnostics         jsonb         not null default '[]',
    last_error_code     varchar(64),
    last_error_message  text,
    last_applied_at     timestamptz(3),
    created_at          timestamptz(3) not null default current_timestamp,
    updated_at          timestamptz(3) not null default current_timestamp,
    constraint uk_environment_skill_source_environment unique (environment_id, source_id),
    constraint fk_environment_skill_source_environment foreign key (environment_id)
        references environment (id) on delete cascade,
    constraint ck_environment_skill_source_type check (
        source_type in ('path', 'git')
    ),
    constraint ck_environment_skill_source_type_shape check (
        (
            source_type = 'path'
            and path is not null
            and git_url is null
            and git_ref is null
            and scan_path is null
        )
        or (
            source_type = 'git'
            and path is null
            and default_source = false
            and git_url is not null
        )
    ),
    constraint ck_environment_skill_source_path check (
        path is null or (path = btrim(path) and char_length(path) > 0)
    ),
    constraint ck_environment_skill_source_git_url check (
        git_url is null or (git_url = btrim(git_url) and char_length(git_url) > 0)
    ),
    constraint ck_environment_skill_source_git_ref check (
        git_ref is null or (git_ref = btrim(git_ref) and char_length(git_ref) > 0)
    ),
    constraint ck_environment_skill_source_scan_path check (
        scan_path is null or (scan_path = btrim(scan_path) and char_length(scan_path) > 0)
    ),
    constraint ck_environment_skill_source_version_nonneg check (
        version >= 0 and (applied_version is null or applied_version >= 0)
    ),
    constraint ck_environment_skill_source_applied_fence check (
        applied_version is null or applied_version <= version
    ),
    constraint ck_environment_skill_source_applied_tuple check (
        (
            applied_version is null
            and applied_revision is null
            and last_applied_at is null
        )
        or (
            applied_version is not null
            and applied_revision is not null
            and last_applied_at is not null
        )
    ),
    constraint ck_environment_skill_source_applied_revision check (
        applied_revision is null
        or applied_revision ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'
    ),
    constraint ck_environment_skill_source_diagnostics check (
        jsonb_typeof(diagnostics) = 'array'
    ),
    constraint ck_environment_skill_source_status check (
        status in ('UNAPPLIED', 'READY', 'FAILED')
    ),
    constraint ck_environment_skill_source_state check (
        (status = 'UNAPPLIED' and last_error_code is null and last_error_message is null)
        or (
            status = 'READY'
            and applied_version is not null
            and applied_version = version
            and last_error_code is null
            and last_error_message is null
        )
        or (
            status = 'FAILED'
            and last_error_code is not null
            and last_error_code = btrim(last_error_code)
            and char_length(last_error_code) > 0
            and last_error_message is not null
            and last_error_message = btrim(last_error_message)
            and char_length(last_error_message) > 0
        )
    ),
    constraint ck_environment_skill_source_time_order check (
        updated_at >= created_at
        and (last_applied_at is null or last_applied_at >= created_at)
    )
);

comment on table environment_skill_source is 'Platform 唯一的 Skill 来源配置：每个来源一行，version 既是 CAS 乐观锁也是下发给 Daemon 的 sourceVersion';
comment on column environment_skill_source.source_id is '来源的全局唯一 UUID（应用生成，永不变更）';
comment on column environment_skill_source.environment_id is '所属 Environment 的全局唯一 UUID（FK cascade）';
comment on column environment_skill_source.source_type is '来源类型 wire 值：path / git';
comment on column environment_skill_source.path is 'PATH 来源的宿主目录（仅 path 类型非空；Daemon 以自己的文件系统解析）';
comment on column environment_skill_source.default_source is '是否该 Environment 的缺省来源；每个 Environment 至多一个（仅 path 类型可为 true）';
comment on column environment_skill_source.git_url is 'GIT 来源的仓库 URL（仅 git 类型非空；绝不回显到错误或列表摘要）';
comment on column environment_skill_source.git_ref is 'GIT 来源可选 ref：为空表示跟踪远端默认 HEAD，非空固定到该 ref';
comment on column environment_skill_source.scan_path is 'GIT 来源可选的仓库内相对扫描目录（仅 git 类型可非空）';
comment on column environment_skill_source.version is '行版本与 Daemon sourceVersion（非负，从 0 开始，每次配置更新 +1）';
comment on column environment_skill_source.status is '应用状态：UNAPPLIED / READY / FAILED';
comment on column environment_skill_source.applied_version is '最近一次成功应用的配置版本；必须不超过 version';
comment on column environment_skill_source.applied_revision is '最近一次成功应用的内容 revision（小写 40 位 SHA-1 commit 或 64 位 SHA-256 聚合）';
comment on column environment_skill_source.diagnostics is '最近一次扫描的有界诊断（JSON array，可为空数组）';
comment on column environment_skill_source.last_error_code is 'FAILED 状态下的失败分类码（非空、无环绕空白）';
comment on column environment_skill_source.last_error_message is 'FAILED 状态下的失败描述（非空、无环绕空白）';
comment on column environment_skill_source.last_applied_at is '最近一次成功应用的时间（毫秒精度，与 applied_version/revision 成对）';
comment on column environment_skill_source.created_at is '创建时间（毫秒精度）';
comment on column environment_skill_source.updated_at is '最后更新时间（毫秒精度），应用侧维护';

-- 每个 Environment 至多一个缺省来源：缺省发现不能有两个权威目录。
create unique index uk_environment_skill_source_default
    on environment_skill_source (environment_id)
    where default_source;

comment on index uk_environment_skill_source_default is '每个 Environment 至多一个缺省 PATH 来源';

-- -----------------------------------------------------------------------------
-- Environment Skill inventory
--
-- 每个来源最新一次成功扫描的持久 inventory，正文永不入库（正文由 Daemon 按 revision 持有）。
-- 复合 FK 让来源删除级联清理其 inventory；同一 Environment 内 Skill name 全局唯一，因此
-- Platform 无需优先级即可唯一定位目标。
-- -----------------------------------------------------------------------------

create table environment_skill (
    environment_id    uuid          not null,
    source_id         uuid          not null,
    name              varchar(128)  not null,
    source_version    bigint        not null,
    description       varchar(1024) not null,
    base_directory    varchar(4096) not null,
    content_revision  char(64)      not null,
    discovered_at     timestamptz(3) not null,
    constraint pk_environment_skill primary key (source_id, name),
    constraint uk_environment_skill_environment_name unique (environment_id, name),
    constraint fk_environment_skill_source foreign key (environment_id, source_id)
        references environment_skill_source (environment_id, source_id) on delete cascade,
    constraint ck_environment_skill_name check (
        name = btrim(name) and char_length(name) > 0
    ),
    constraint ck_environment_skill_description check (
        description = btrim(description) and char_length(description) > 0
    ),
    constraint ck_environment_skill_base_directory check (
        base_directory = btrim(base_directory) and char_length(base_directory) > 0
    ),
    constraint ck_environment_skill_source_version_nonneg check (source_version >= 0),
    constraint ck_environment_skill_content_revision check (
        content_revision ~ '^[0-9a-f]{64}$'
    )
);

comment on table environment_skill is '每个来源最新一次成功扫描的持久 Skill inventory；正文永不入库，只保存可唯一定位的身份与描述';
comment on column environment_skill.environment_id is '所属 Environment（与 source_id 一起构成指向来源配置的复合 FK）';
comment on column environment_skill.source_id is '所属来源的全局唯一 UUID';
comment on column environment_skill.name is 'Skill canonical 名（同一 Environment 内全局唯一）';
comment on column environment_skill.source_version is '发现该 Skill 时的来源行版本（对齐 Daemon READY 的 sourceVersion）';
comment on column environment_skill.description is 'Skill 描述（非空、无环绕空白；正文不在此表）';
comment on column environment_skill.base_directory is 'Daemon 宿主上的 Skill 目录（仅事实记录，不在 SQL 解析路径）';
comment on column environment_skill.content_revision is '内容 revision（小写 64 位 SHA-256）';
comment on column environment_skill.discovered_at is '最近一次发现该 Skill 的时间（毫秒精度）';

create index idx_environment_skill_source
    on environment_skill (environment_id, source_id);

comment on index idx_environment_skill_source is '按 Environment 与来源列出持久 inventory';

-- -----------------------------------------------------------------------------
-- Environment operations
--
-- 跨节点管理信箱与不可变历史。resource_id 故意不建 FK：删除或改写资源配置不得抹掉操作历史，
-- 陈旧操作由 Platform 依据资源是否存在/版本是否变化收敛为 RESOURCE_CHANGED。
-- arguments 是冻结的私有执行参数（可能含凭据或敏感配置），errors/list 摘要绝不回显它；
-- parameter_summary 是可以安全公开的摘要。
-- -----------------------------------------------------------------------------

create table environment_operation (
    id                  uuid           primary key,
    environment_id      uuid           not null,
    resource_type       varchar(32)    not null,
    resource_id         uuid           not null,
    operation_type      varchar(32)    not null,
    status              varchar(16)    not null,
    resource_version    bigint         not null,
    arguments           jsonb          not null,
    parameter_summary   jsonb          not null,
    deadline_at         timestamptz(3) not null,
    owner_node_id       uuid,
    lease_token         uuid,
    started_at          timestamptz(3),
    finished_at         timestamptz(3),
    result_summary      jsonb,
    failure_code        varchar(64),
    failure_message     text,
    created_at          timestamptz(3) not null default current_timestamp,
    updated_at          timestamptz(3) not null default current_timestamp,
    constraint fk_environment_operation_environment foreign key (environment_id)
        references environment (id) on delete cascade,
    constraint ck_environment_operation_type check (
        operation_type in ('SKILL_REFRESH', 'SKILL_INSTALL', 'SKILL_UPDATE', 'MCP_SERVER_DISCOVER')
    ),
    constraint ck_environment_operation_resource_type check (
        resource_type in ('SKILL_SOURCE', 'MCP_SERVER')
    ),
    constraint ck_environment_operation_type_resource_pair check (
        (operation_type in ('SKILL_REFRESH', 'SKILL_INSTALL', 'SKILL_UPDATE') and resource_type = 'SKILL_SOURCE')
        or (operation_type = 'MCP_SERVER_DISCOVER' and resource_type = 'MCP_SERVER')
    ),
    constraint ck_environment_operation_status check (
        status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCELLED')
    ),
    constraint ck_environment_operation_version_nonneg check (
        resource_version >= 0
    ),
    constraint ck_environment_operation_arguments_object check (
        jsonb_typeof(arguments) = 'object'
    ),
    constraint ck_environment_operation_parameter_summary_object check (
        jsonb_typeof(parameter_summary) = 'object'
    ),
    constraint ck_environment_operation_result_summary_object check (
        result_summary is null or jsonb_typeof(result_summary) = 'object'
    ),
    constraint ck_environment_operation_state check (
        (
            status = 'PENDING'
            and owner_node_id is null
            and lease_token is null
            and started_at is null
            and finished_at is null
            and result_summary is null
            and failure_code is null
            and failure_message is null
        )
        or (
            status = 'RUNNING'
            and owner_node_id is not null
            and lease_token is not null
            and started_at is not null
            and finished_at is null
            and result_summary is null
            and failure_code is null
            and failure_message is null
        )
        or (
            status = 'SUCCEEDED'
            and owner_node_id is not null
            and lease_token is not null
            and started_at is not null
            and finished_at is not null
            and result_summary is not null
            and failure_code is null
            and failure_message is null
        )
        or (
            status = 'FAILED'
            and finished_at is not null
            and result_summary is null
            and failure_code is not null
            and failure_code = btrim(failure_code)
            and char_length(failure_code) > 0
            and failure_message is not null
            and failure_message = btrim(failure_message)
            and char_length(failure_message) > 0
            and (
                (owner_node_id is null and lease_token is null and started_at is null)
                or (
                    owner_node_id is not null
                    and lease_token is not null
                    and started_at is not null
                )
            )
        )
        or (
            status = 'UNKNOWN'
            and owner_node_id is not null
            and lease_token is not null
            and started_at is not null
            and finished_at is not null
            and result_summary is null
            and failure_code is not null
            and failure_code = btrim(failure_code)
            and char_length(failure_code) > 0
            and failure_message is not null
            and failure_message = btrim(failure_message)
            and char_length(failure_message) > 0
        )
        or (
            status = 'CANCELLED'
            and owner_node_id is null
            and lease_token is null
            and started_at is null
            and finished_at is not null
            and result_summary is null
            and failure_code is null
            and failure_message is null
        )
    ),
    constraint ck_environment_operation_time_order check (
        deadline_at >= created_at
        and updated_at >= created_at
        and (started_at is null or started_at >= created_at)
        and (finished_at is null or finished_at >= coalesce(started_at, created_at))
    )
);

comment on table environment_operation is '跨节点管理操作信箱与不可变历史：PENDING 可被任一合格节点认领，终态不可回退；resource_id 故意不建 FK 以保留资源删除后的历史';
comment on column environment_operation.id is '操作 UUID（调用方生成，永不重用）';
comment on column environment_operation.environment_id is '目标 Environment 的全局唯一 UUID（FK cascade）';
comment on column environment_operation.resource_type is '目标资源类型：SKILL_SOURCE / MCP_SERVER';
comment on column environment_operation.resource_id is '目标资源的全局唯一 UUID（故意不建 FK：资源删除后操作历史仍然存在）';
comment on column environment_operation.operation_type is '操作类型：SKILL_REFRESH / SKILL_INSTALL / SKILL_UPDATE / MCP_SERVER_DISCOVER';
comment on column environment_operation.status is '生命周期：PENDING / RUNNING / SUCCEEDED / FAILED / UNKNOWN / CANCELLED';
comment on column environment_operation.resource_version is '发起时的目标资源版本；过期操作必须收敛为 RESOURCE_CHANGED';
comment on column environment_operation.arguments is '冻结的私有调用参数（JSON object；错误与列表摘要绝不回显）';
comment on column environment_operation.parameter_summary is '可公开的参数摘要（JSON object，不含 URL/凭证）';
comment on column environment_operation.deadline_at is '认领与执行的硬超时（毫秒精度，不得早于 created_at）';
comment on column environment_operation.owner_node_id is '认领该操作的 App 节点实例 UUID（PENDING 为空）';
comment on column environment_operation.lease_token is '认领租约代币，用于围栏被接管的旧执行者';
comment on column environment_operation.started_at is '认领时间（毫秒精度）';
comment on column environment_operation.finished_at is '终态时间（毫秒精度，不得早于 started_at）';
comment on column environment_operation.result_summary is 'SUCCEEDED 状态下的结果摘要（JSON object，可空）';
comment on column environment_operation.failure_code is '失败分类码（FAILED/UNKNOWN 状态下非空、无环绕空白）';
comment on column environment_operation.failure_message is '失败描述（FAILED/UNKNOWN 状态下非空、无环绕空白）';
comment on column environment_operation.created_at is '创建时间（毫秒精度）';
comment on column environment_operation.updated_at is '最后更新时间（毫秒精度），应用侧维护';

-- 同一目标资源同时至多一个未终结操作：重复触发不能产生两个竞争执行者。
create unique index uk_environment_operation_active
    on environment_operation (environment_id, resource_type, resource_id)
    where status in ('PENDING', 'RUNNING');

comment on index uk_environment_operation_active is '同一 (environment, resource_type, resource_id) 至多一个未终结操作';

create index idx_environment_operation_claim
    on environment_operation (status, deadline_at, environment_id)
    where status = 'PENDING';

comment on index idx_environment_operation_claim is 'PENDING 认领扫描：按截止时间取最早可执行操作';

create index idx_environment_operation_deadline
    on environment_operation (deadline_at);

comment on index idx_environment_operation_deadline is '过期操作清扫索引：不区分状态即可按截止时间收敛';

create index idx_environment_operation_environment
    on environment_operation (environment_id, created_at, id);

comment on index idx_environment_operation_environment is '按 Environment 读取操作历史（created_at, id 稳定序）';

------------------------------------------------------------------------------
-- 1c. Singleton system settings (id=1)
--
-- system_setting 是全局强类型配置聚合的权威存储：恒为一行（id=1），config 保存完整
-- SystemSettings 六个 section 的 canonical JSON（写路径只接受强类型 DTO，绝无任意 JSON
-- 写接口），version 是乐观锁 CAS 令牌（非负，每次更新 +1），时间字段毫秒精度。
-- 默认行插入安全默认聚合：tool.permission 默认 base.write/base.edit/base.bash 各 `* -> ask`。
------------------------------------------------------------------------------

create table system_setting (
    id         bigint         primary key,
    config     jsonb          not null,
    version    bigint         not null default 0,
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp,
    constraint ck_system_setting_id check (id = 1),
    constraint ck_system_setting_config_object check (jsonb_typeof(config) = 'object'),
    constraint ck_system_setting_version_nonneg check (version >= 0)
);

comment on table system_setting is '全局 system settings 单行聚合（id=1）：config 为强类型 canonical JSON，version 为 CAS 乐观锁版本';
comment on column system_setting.id is '恒为 1：全局配置恰好一行';
comment on column system_setting.config is '六个 section（tool/aiRuntime/environment/integrations/storageMedia/advanced）的完整强类型 canonical JSON，必须为 object';
comment on column system_setting.version is '乐观锁行版本：非负，从 0 开始，每次写操作 +1';
comment on column system_setting.created_at is '创建时间（毫秒精度）';
comment on column system_setting.updated_at is '最后更新时间（毫秒精度），应用侧维护';

insert into system_setting (id, config) values (
    1,
     '{"advanced":{"applicationEventHeartbeatIntervalMillis":20000,"applicationEventMaxBytes":2097152,"applicationEventQueueCapacity":512,"applicationEventSendTimeoutMillis":10000,"modelDispatchBusyFallbackDelayMillis":1000,"postgresqlWorkNotificationPollMillis":5000,"postgresqlWorkReconnectBackoffMillis":1000,"processorHeartbeatIntervalMillis":10000,"processorLeaseDurationMillis":30000,"resourceMaxBytes":16777216,"threadResolveFailureDelayMillis":1000,"toolDispatchBusyFallbackDelayMillis":1000,"toolPreflightFailureDelayMillis":1000},"aiRuntime":{"compactionKeepRecentTokens":20000,"retryBackoffStrategy":"EXPONENTIAL","retryBaseDelayMillis":2000,"retryMaxDelayMillis":60000,"retryMaxRetries":3,"subagentIdleTimeoutMillis":0,"subagentMaxConcurrency":10,"subagentMaxDepth":2,"subagentMaxTotalConcurrency":0,"subagentMaxTurns":50},"environment":{"heartbeatTimeoutMillis":60000,"maxResourceBytes":8388608},"integrations":{"comfyui":{"connectTimeoutMillis":10000,"enabled":false,"maxInputFileBytes":52428800,"readTimeoutMillis":30000,"websocketTimeoutMillis":1800000},"gptImage2":{"askTimeoutSeconds":900,"hubExecutionTimeoutMillis":960000,"maxWaitMillis":1200000,"paidEnabled":false},"minimaxH3":{"comfyConnectTimeoutMillis":10000,"comfyMaxWaitMillis":1800000,"comfyPollIntervalMillis":2000,"comfyRequestTimeoutMillis":30000,"enabled":false,"promptMaxWaitMillis":600000},"openCliHub":{"baseUrl":null,"connectTimeoutMillis":5000,"enabled":false,"longPollTimeoutMillis":130000,"maxErrorResponseBytes":4096,"maxJsonResponseBytes":524288,"maxOutputChars":65535,"requestTimeoutMillis":120000,"streamBufferBytes":16384},"seedance":{"enabled":false,"hubExecutionTimeoutMillis":600000,"maxWaitMillis":1800000,"retry":0,"statusPollIntervalMillis":30000}},"storageMedia":{"canvasMediaProcessTimeoutMillis":30000,"s3PresignDefaultExpiresSeconds":600,"s3PresignMaxExpiresSeconds":3600,"thumbnailMaxDimension":512,"thumbnailQuality":80,"uploadExpiresSeconds":3600},"tool":{"defaultYolo":false,"modelGatewayBusyRetryMillis":5000,"permission":{"base.bash":[{"action":"ask","pattern":"*"}],"base.edit":[{"action":"ask","pattern":"*"}],"base.write":[{"action":"ask","pattern":"*"}]},"skillLoadTimeoutMillis":30000,"toolGatewayBusyRetryMillis":1000,"toolGatewayOverloadRetryMillis":5000}}'::jsonb
);

-- System settings version NOTIFY hint.
--
-- version is owned by the application; PostgreSQL never bumps it. This trigger
-- only wakes settings listeners after a committed insert or actual version
-- change. The payload is the nonnegative decimal `version`.

create or replace function system_setting_version_notify()
returns trigger language plpgsql as $$
begin
    if tg_op = 'INSERT' or new.version is distinct from old.version then
        perform pg_notify('system_settings_changed', new.version::text);
    end if;
    return new;
end $$;

create trigger trg_system_setting_version_notify
    after insert or update of version on system_setting
    for each row execute function system_setting_version_notify();

------------------------------------------------------------------------------
-- 2. Harness runtime execution protocol
--
-- The block below (the seven tables + their indexes, including comments) is the
-- single authoritative definition of the durable Harness protocol schema.
-- Columns, checks, FKs and indexes must never drift; HarnessRuntime allocates
-- ids via the injected Supplier<UUID> (production: UUID::randomUUID) and
-- inserts them explicitly (no column defaults). ThreadCommand has no surrogate
-- primary key: identity is (thread_id, sequence).
------------------------------------------------------------------------------

-- Harness Runtime durable schema.
--
-- 所有 Harness 生成的持久实体 ID（Session / Entry / Thread / ThreadCommand client id / ModelInvocation /
-- ToolInvocation / WorkTarget）均为 PostgreSQL uuid，由 HarnessStore 注入的 Supplier<UUID> 生成（生产：
-- UUID::randomUUID），本 schema 不再提供任何序列。ThreadCommand 无代理主键，身份为 (thread_id, sequence)。
-- 时间列统一使用毫秒精度 timestamptz(3)，与 HarnessStore 的时间精度契约一致。

create table harness_session (
    id uuid primary key,
    name varchar(256) not null,
    created_at timestamptz(3) not null,
    constraint ck_harness_session_name check (
        btrim(name) <> ''
    )
);

comment on table harness_session is 'Session 聚合边界：组织一份 append-only Entry Tree，不拥有 Thread；只记录自身 id、显示名称与创建时间';
comment on column harness_session.id is 'Session 的全局唯一 UUID（创建后不可变）';
comment on column harness_session.name is 'Session 显示名称：应用保证非空、单行且至多 256 个 Unicode 码点，并由应用生成默认名或手动重命名（check 只防御空白串）';
comment on column harness_session.created_at is 'Session 创建时间（毫秒精度，创建后不可变）';

create table harness_entry (
    id uuid primary key,
    session_id uuid not null,
    parent_entry_id uuid,
    entry_type varchar(32) not null,
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    created_at timestamptz(3) not null,
    provider_replay_state jsonb,
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
            'MODEL_ATTEMPT_FAILURE',
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
    ),
    constraint ck_harness_entry_provider_replay_state check (
        provider_replay_state is null
        or (
            jsonb_typeof(provider_replay_state) = 'object'
            and entry_type = 'MESSAGE'
            and payload->'message'->>'role' = 'ASSISTANT'
        )
    )
);

comment on table harness_entry is '不可变 Entry：append-only 树节点，ROOT 必须先于其他 Entry，parent 链必须连续且同 Session';
comment on column harness_entry.id is 'Entry 的全局唯一 UUID';
comment on column harness_entry.session_id is '所属 Session';
comment on column harness_entry.parent_entry_id is '父 Entry；ROOT 为 null，其余必须非 null 且不能指向自身';
comment on column harness_entry.entry_type is 'Entry 类型（ROOT/TURN_START/MESSAGE/CUSTOM/MODEL_ATTEMPT_FAILURE/CUSTOM_MESSAGE/ASSISTANT_ERROR/ASSISTANT_ABORTED/COMPACTION/TURN_END）';
comment on column harness_entry.payload is '按 entry_type 编码的不可变 payload（JSON object）';
comment on column harness_entry.created_at is 'Entry 创建时间（毫秒精度）';
comment on column harness_entry.provider_replay_state is 'Provider native terminal replay 状态（JSON object，仅 ASSISTANT MESSAGE，可空）';

create unique index uk_harness_entry_single_root
    on harness_entry (session_id)
    where entry_type = 'ROOT';

create index idx_harness_entry_parent
    on harness_entry (session_id, parent_entry_id);

comment on index uk_harness_entry_single_root is '每个 Session 至多一个 ROOT Entry';
comment on index idx_harness_entry_parent is 'parent 回溯与同 Session 树遍历索引';

create table harness_thread (
    id uuid primary key,
    session_id uuid not null,
    head_entry_id uuid not null,
    creation_request_hash char(64) not null,
    name varchar(256) not null,
    yolo_enabled boolean not null,
    next_command_sequence bigint not null check (next_command_sequence >= 1),
    version bigint not null check (version >= 0),
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_harness_thread_session foreign key (session_id)
        references harness_session (id),
    constraint fk_harness_thread_head foreign key (session_id, head_entry_id)
        references harness_entry (session_id, id),
    constraint ck_harness_thread_creation_request_hash check (
        creation_request_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint ck_harness_thread_name check (
        btrim(name) <> ''
    ),
    constraint ck_harness_thread_time_order check (updated_at >= created_at)
);

comment on table harness_thread is 'Thread：指向 head Entry 的游标状态机，version 随每次对外字段变化精确 +1；session_id 与 creation_request_hash 创建后不可变，head 必须与 session 同 Session';
comment on column harness_thread.id is 'Thread 的全局唯一 UUID';
comment on column harness_thread.session_id is '所属 Session（创建后不可变）';
comment on column harness_thread.head_entry_id is '当前 head Entry（必须存在且属于 thread.session_id 的 Session）';
comment on column harness_thread.creation_request_hash is 'NEW_SESSION/NEW_THREAD 初始创建请求指纹：服务端 64 位小写 SHA-256 身份键（创建后不可变，不对产品 DTO 暴露）';
comment on column harness_thread.name is 'Thread 显示名称：应用保证非空、单行且至多 256 个 Unicode 码点，并由应用生成默认名或手动重命名（check 只防御空白串）';
comment on column harness_thread.yolo_enabled is '当前 yolo 模式开关';
comment on column harness_thread.next_command_sequence is '下一条 Command 的 sequence（从 1 递增）';
comment on column harness_thread.version is '并发控制版本：任何对外字段变化必须 +1';
comment on column harness_thread.created_at is 'Thread 创建时间（毫秒精度）';
comment on column harness_thread.updated_at is 'Thread 最后更新时间（毫秒精度），不得早于 created_at';

create index idx_harness_thread_session
    on harness_thread (session_id, created_at, id);

comment on index idx_harness_thread_session is 'listThreadsBySession 按 (session_id, created_at, id) 读取 Session 的 Thread 列表';

create table harness_thread_command (
    thread_id uuid not null,
    sequence bigint not null check (sequence > 0),
    command_type varchar(32) not null,
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    idempotency_key uuid not null,
    request_hash char(64) not null,
    applied_turn_start_entry_id uuid,
    stop_request_id uuid,
    cancelled_at timestamptz(3),
    created_at timestamptz(3) not null,
    primary key (thread_id, sequence),
    constraint fk_harness_thread_command_thread foreign key (thread_id)
        references harness_thread (id),
    constraint fk_harness_thread_command_applied foreign key (applied_turn_start_entry_id)
        references harness_entry (id),
    constraint uk_harness_thread_command_idempotency unique (thread_id, idempotency_key),
    constraint ck_harness_thread_command_type check (
        command_type in (
            'USER_MESSAGE',
            'CUSTOM_MESSAGE',
            'SET_AGENT',
            'SET_MODEL'
        )
    ),
    constraint ck_harness_thread_command_request_hash check (
        request_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint ck_harness_thread_command_terminal check (
        applied_turn_start_entry_id is null or cancelled_at is null
    ),
    constraint ck_harness_thread_command_cancel_pair check (
        (stop_request_id is null and cancelled_at is null)
        or (stop_request_id is not null and cancelled_at is not null)
    ),
    constraint ck_harness_thread_command_cancel_time check (
        cancelled_at is null or cancelled_at >= created_at
    )
);

comment on table harness_thread_command is 'ThreadCommand：无代理主键，身份为 (thread_id, sequence)；QUEUED 只能推进为 APPLIED 或 CANCELLED，CANCELLED 必须 stop_request_id 与 cancelled_at 成对';
comment on column harness_thread_command.thread_id is '所属 Thread';
comment on column harness_thread_command.sequence is 'Thread 内单调递增序号（与身份一起构成主键）';
comment on column harness_thread_command.command_type is 'Command payload 类型';
comment on column harness_thread_command.payload is '按 command_type 编码的 payload（JSON object）';
comment on column harness_thread_command.idempotency_key is '客户端幂等键（UUID），同一 Thread 内唯一';
comment on column harness_thread_command.request_hash is '客户端 raw 命令（含 ordered contents 与 uploadId）的 canonical SHA-256（64 小写 hex）；同 idempotencyKey 重放必须精确匹配';
comment on column harness_thread_command.applied_turn_start_entry_id is 'APPLIED 时应用的 TURN_START Entry；与 cancelled_at 互斥';
comment on column harness_thread_command.stop_request_id is 'CANCELLED 时取消它的 Stop stopRequestId（queued-only receipt 幂等键）；与 cancelled_at 成对';
comment on column harness_thread_command.cancelled_at is 'CANCELLED 时间；不得早于 created_at';
comment on column harness_thread_command.created_at is 'Command 创建时间（毫秒精度）';

create index idx_harness_thread_command_queued
    on harness_thread_command (thread_id, sequence)
    where applied_turn_start_entry_id is null and cancelled_at is null;

comment on index idx_harness_thread_command_queued is '按 sequence 升序读取 QUEUED Command（for update 锁序）';

create index idx_harness_thread_command_stop_request
    on harness_thread_command (thread_id, stop_request_id, sequence)
    where stop_request_id is not null;

comment on index idx_harness_thread_command_stop_request is 'Stop 幂等键：(thread_id, stop_request_id) 按 sequence 升序汇总被该 stopRequestId 取消的 Command';

create table harness_model_invocation (
    id uuid primary key,
    thread_id uuid not null,
    turn_start_entry_id uuid not null,
    request_head_entry_id uuid not null,
    request_spec jsonb not null check (jsonb_typeof(request_spec) = 'object'),
    status varchar(16) not null,
    attempt integer not null check (attempt >= 0),
    stream_checkpoint jsonb check (
        stream_checkpoint is null or jsonb_typeof(stream_checkpoint) = 'object'
    ),
    result jsonb check (result is null or jsonb_typeof(result) = 'object'),
    error jsonb check (error is null or jsonb_typeof(error) = 'object'),
    result_entry_id uuid,
    failed_attempts jsonb not null check (jsonb_typeof(failed_attempts) = 'array'),
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    provider_replay_state jsonb,
    constraint fk_harness_model_invocation_thread foreign key (thread_id)
        references harness_thread (id),
    constraint fk_harness_model_invocation_turn_start foreign key (turn_start_entry_id)
        references harness_entry (id),
    constraint fk_harness_model_invocation_request_head foreign key (request_head_entry_id)
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
    constraint ck_harness_model_invocation_time_order check (updated_at >= created_at),
    constraint ck_harness_model_invocation_provider_replay_state check (
        provider_replay_state is null
        or (
            jsonb_typeof(provider_replay_state) = 'object'
            and status = 'SUCCEEDED'
            and result is not null
            and result_entry_id is null
        )
    )
);

comment on table harness_model_invocation is 'ModelInvocation：一次 model turn 的 durable 生命周期记录（READY/DISPATCHING/RUNNING 与四个 terminal）';
comment on column harness_model_invocation.id is 'ModelInvocation 的全局唯一 UUID';
comment on column harness_model_invocation.thread_id is '所属 Thread';
comment on column harness_model_invocation.turn_start_entry_id is '本次 turn 的 TURN_START Entry';
comment on column harness_model_invocation.request_head_entry_id is '创建时的 Thread head（request 头 Entry CAS 快照）';
comment on column harness_model_invocation.request_spec is '冻结的 model 请求规格（JSON object）';
comment on column harness_model_invocation.status is '生命周期状态';
comment on column harness_model_invocation.attempt is '已确认的 start 尝试次数（从 0 递增）';
comment on column harness_model_invocation.stream_checkpoint is 'RUNNING 流式断点（JSON object，可空）';
comment on column harness_model_invocation.result is 'terminal 成功结果（JSON object，与 error 互斥）';
comment on column harness_model_invocation.error is 'terminal 失败错误（JSON object，与 result 互斥）';
comment on column harness_model_invocation.result_entry_id is '结果 Entry（Assistant/AssistantError/AssistantAborted/COMPACTION），全局唯一';
comment on column harness_model_invocation.failed_attempts is '由 retry policy 驱动的 append-only TRANSIENT 失败 attempt 历史（JSON array）';
comment on column harness_model_invocation.created_at is '创建时间（毫秒精度）';
comment on column harness_model_invocation.updated_at is '最后更新时间（毫秒精度），不得早于 created_at';
comment on column harness_model_invocation.provider_replay_state is 'Provider native terminal replay 状态（JSON object，仅未物化结果的 SUCCEEDED 状态，可空）';

create unique index uk_harness_model_invocation_result
    on harness_model_invocation (result_entry_id)
    where result_entry_id is not null;

comment on index uk_harness_model_invocation_result is 'resultEntryId 全局唯一（非 null 时）';

create table harness_tool_invocation (
    id uuid primary key,
    model_invocation_id uuid not null,
    assistant_entry_id uuid not null,
    call_index integer not null check (call_index >= 0),
    call jsonb not null check (jsonb_typeof(call) = 'object'),
    binding jsonb check (binding is null or jsonb_typeof(binding) = 'object'),
    status varchar(32) not null,
    attempt integer not null check (attempt >= 0),
    approval jsonb check (approval is null or jsonb_typeof(approval) = 'object'),
    result jsonb check (result is null or jsonb_typeof(result) = 'object'),
    effects jsonb not null check (jsonb_typeof(effects) = 'object'),
    error jsonb check (error is null or jsonb_typeof(error) = 'object'),
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_harness_tool_invocation_model foreign key (model_invocation_id)
        references harness_model_invocation (id),
    constraint fk_harness_tool_invocation_assistant foreign key (assistant_entry_id)
        references harness_entry (id),
    constraint uk_harness_tool_invocation_call_index unique (assistant_entry_id, call_index),
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

comment on table harness_tool_invocation is 'ToolInvocation：一次 tool 调用的 durable 生命周期记录，按 (assistant_entry_id, call_index) 与 assistant 消息对齐；batch apply 后行被物理删除';
comment on column harness_tool_invocation.id is 'ToolInvocation 的全局唯一 UUID';
comment on column harness_tool_invocation.model_invocation_id is '所属 ModelInvocation';
comment on column harness_tool_invocation.assistant_entry_id is '携带对应 ToolCall 的 Assistant MESSAGE Entry';
comment on column harness_tool_invocation.call_index is 'assistant 消息内 tool call 的下标（从 0 递增）';
comment on column harness_tool_invocation.call is '冻结的 ToolCall（JSON object）';
comment on column harness_tool_invocation.binding is '冻结的 tool binding（JSON object，仅在 immediate FAILED attempt=0 槽位可空）';
comment on column harness_tool_invocation.status is '生命周期状态（含 WAITING_APPROVAL）';
comment on column harness_tool_invocation.attempt is '已确认的 start 尝试次数（从 0 递增）';
comment on column harness_tool_invocation.approval is '审批记录（JSON object，可空）';
comment on column harness_tool_invocation.result is 'terminal 成功结果（JSON object，与 error 互斥）';
comment on column harness_tool_invocation.effects is '副作用批（JSON object；非 SUCCEEDED 时必须为空批）';
comment on column harness_tool_invocation.error is 'terminal 失败错误（JSON object，与 result 互斥）';
comment on column harness_tool_invocation.created_at is '创建时间（毫秒精度）';
comment on column harness_tool_invocation.updated_at is '最后更新时间（毫秒精度），不得早于 created_at';

create table harness_work (
    target_type varchar(16) not null,
    target_id uuid not null,
    available_at timestamptz(3) not null,
    wake_version bigint not null check (wake_version > 0),
    lease_token varchar(128),
    lease_until timestamptz(3),
    required_environment_id uuid,
    primary key (target_type, target_id),
    constraint fk_harness_work_environment foreign key (required_environment_id)
        references environment (id) on delete restrict,
    constraint ck_harness_work_target_type check (
        target_type in ('THREAD', 'MODEL', 'TOOL')
    ),
    constraint ck_harness_work_lease_pair check (
        (lease_token is null) = (lease_until is null)
    ),
    constraint ck_harness_work_lease_token check (
        lease_token is null
        or (length(lease_token) > 0 and btrim(lease_token) = lease_token)
    ),
    constraint ck_harness_work_required_environment check (
        required_environment_id is null or target_type = 'TOOL'
    )
);

comment on table harness_work is 'Work：一个 work mailbox target（Thread/Model/Tool 实体）的 durable 调度状态，wake_version 递增防止旧 processor 完成更新的 wake';
comment on column harness_work.target_type is 'target 实体类型（THREAD/MODEL/TOOL）';
comment on column harness_work.target_id is 'target 实体的 UUID（与 target_type 构成主键）';
comment on column harness_work.available_at is '最早可被 claim 的时间（毫秒精度）';
comment on column harness_work.wake_version is 'wake 计数（从 1 递增）';
comment on column harness_work.lease_token is '当前 lease token（与 lease_until 同时存在或同时缺失）';
comment on column harness_work.lease_until is '当前 lease 到期时间（毫秒精度）';
comment on column harness_work.required_environment_id is '执行该 Work 所需 Environment 的全局唯一 UUID（仅 TOOL 可非空；非空时仅持有该 environment READY 连接租约的节点可 claim）';

create index idx_harness_work_available
    on harness_work (available_at, target_type, target_id);

create index idx_harness_work_lease_until
    on harness_work (lease_until, target_type, target_id)
    where lease_until is not null;

comment on index idx_harness_work_available is 'claimNextWork 按 (available_at, target_type, target_id) 选取候选';
comment on index idx_harness_work_lease_until is '过期 lease 扫描索引';

------------------------------------------------------------------------------
-- 3. Project / Issue orchestration and global Session ownership
------------------------------------------------------------------------------

------------------------------------------------------------------------------
-- Project
------------------------------------------------------------------------------
create table project (
    id                     uuid          not null,
    title                  varchar(255)  not null,
    description            text          not null default '',
    coordinator_agent_name varchar(128)  not null,
    next_issue_number      bigint        not null default 1,
    version                bigint        not null default 0,
    archived_at            timestamptz(3),
    created_at             timestamptz(3) not null default clock_timestamp(),
    updated_at             timestamptz(3) not null default clock_timestamp(),
    constraint pk_project primary key (id),
    constraint fk_project_coordinator foreign key (coordinator_agent_name)
        references agent_definition (name) on delete restrict,
    constraint chk_project_title_not_blank check (length(trim(title)) > 0 and title = btrim(title)),
    constraint chk_project_description_len check (octet_length(description) <= 65536),
    constraint chk_project_coordinator_not_blank check (length(trim(coordinator_agent_name)) > 0 and coordinator_agent_name = btrim(coordinator_agent_name)),
    constraint chk_project_next_issue_number check (next_issue_number >= 1),
    constraint chk_project_version check (version >= 0)
);

comment on table project is 'Project 核心实体：目标、约束、Coordinator 配置与 Issue 编号单调分配器';
comment on column project.id is '项目 UUID 主键（应用生成）';
comment on column project.title is '展示标题，非空';
comment on column project.description is '自洽目标、约束与验收规范描述';
comment on column project.coordinator_agent_name is 'Coordinator 引用现存 AgentDefinition 名称（RESTRICT）';
comment on column project.next_issue_number is '项目内单调递增 Issue 编号分配器，>= 1';
comment on column project.version is '乐观锁版本号，>= 0';
comment on column project.archived_at is '归档时间戳，为空表示活跃';
comment on column project.created_at is '创建时间戳（毫秒精度）';
comment on column project.updated_at is '更新时间戳（毫秒精度）';

------------------------------------------------------------------------------
-- Issue
------------------------------------------------------------------------------
create table issue (
    id                  uuid          not null,
    project_id          uuid          not null,
    number              bigint        not null,
    title               varchar(255)  not null,
    description         text          not null default '',
    status              varchar(32)   not null,
    assignee_agent_name varchar(128),
    reviewer_agent_name varchar(128),
    version             bigint        not null default 0,
    spec_revision       bigint        not null default 0,
    input_sequence      bigint        not null default 0,
    archived_at         timestamptz(3),
    created_at          timestamptz(3) not null default clock_timestamp(),
    updated_at          timestamptz(3) not null default clock_timestamp(),
    constraint pk_issue primary key (id),
    constraint fk_issue_project foreign key (project_id)
        references project (id) on delete restrict,
    constraint fk_issue_assignee foreign key (assignee_agent_name)
        references agent_definition (name) on delete restrict,
    constraint fk_issue_reviewer foreign key (reviewer_agent_name)
        references agent_definition (name) on delete restrict,
    constraint uk_issue_project_number unique (project_id, number),
    constraint uk_issue_id_project unique (id, project_id),
    constraint chk_issue_title_not_blank check (length(trim(title)) > 0 and title = btrim(title)),
    constraint chk_issue_description_len check (octet_length(description) <= 65536),
    constraint chk_issue_assignee_not_blank check (assignee_agent_name is null or (length(trim(assignee_agent_name)) > 0 and assignee_agent_name = btrim(assignee_agent_name))),
    constraint chk_issue_reviewer_not_blank check (reviewer_agent_name is null or (length(trim(reviewer_agent_name)) > 0 and reviewer_agent_name = btrim(reviewer_agent_name))),
    constraint chk_issue_number check (number >= 1),
    constraint chk_issue_status check (status in ('BACKLOG', 'TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE', 'CANCELED')),
    constraint chk_issue_version check (version >= 0),
    constraint chk_issue_spec_revision check (spec_revision >= 0),
    constraint chk_issue_input_sequence check (input_sequence >= 0),
    constraint chk_issue_archived_status check (archived_at is null or status in ('DONE', 'CANCELED'))
);

create index idx_issue_project on issue (project_id);
create index idx_issue_project_status on issue (project_id, status);

comment on table issue is 'Issue 核心实体：六态生命周期、执行/评审分配及游标版本';
comment on column issue.id is 'Issue UUID 主键（应用生成）';
comment on column issue.project_id is '归属项目 UUID';
comment on column issue.number is '项目内单调递增编号，>= 1';
comment on column issue.title is 'Issue 标题';
comment on column issue.description is 'Issue 规格说明与验收要求描述';
comment on column issue.status is '状态：BACKLOG, TODO, IN_PROGRESS, IN_REVIEW, DONE, CANCELED';
comment on column issue.assignee_agent_name is '分配执行者 Agent 名称（可空）';
comment on column issue.reviewer_agent_name is '分配评审者 Agent 名称（可空，空表示人工评审）';
comment on column issue.version is '乐观锁行版本，>= 0';
comment on column issue.spec_revision is '规格版本游标，>= 0';
comment on column issue.input_sequence is '输入序列游标，>= 0';
comment on column issue.archived_at is '归档时间戳（仅允许终态 Issue 归档）';

------------------------------------------------------------------------------
-- Issue dependencies
------------------------------------------------------------------------------
create table issue_dependency (
    issue_id            uuid          not null,
    depends_on_issue_id uuid          not null,
    project_id          uuid          not null,
    created_at          timestamptz(3) not null default clock_timestamp(),
    constraint pk_issue_dependency primary key (issue_id, depends_on_issue_id),
    constraint fk_issue_dependency_issue foreign key (issue_id, project_id)
        references issue (id, project_id) on delete restrict,
    constraint fk_issue_dependency_depends_on foreign key (depends_on_issue_id, project_id)
        references issue (id, project_id) on delete restrict,
    constraint chk_issue_dependency_no_self check (issue_id <> depends_on_issue_id)
);

create index idx_issue_dependency_depends_on on issue_dependency (depends_on_issue_id);
create index idx_issue_dependency_project on issue_dependency (project_id);

comment on table issue_dependency is 'Issue 间依赖边：复合外键保障同项目，禁止自环';
comment on column issue_dependency.issue_id is '被阻塞 Issue UUID';
comment on column issue_dependency.depends_on_issue_id is '前提 Issue UUID';
comment on column issue_dependency.project_id is '冗余项目 UUID，保证依赖两端必须归属同一项目';

------------------------------------------------------------------------------
-- Issue inputs
------------------------------------------------------------------------------
create table issue_input (
    issue_id        uuid          not null,
    sequence        bigint        not null,
    kind            varchar(32)   not null,
    body            text          not null,
    idempotency_key varchar(128),
    created_at      timestamptz(3) not null default clock_timestamp(),
    constraint pk_issue_input primary key (issue_id, sequence),
    constraint fk_issue_input_issue foreign key (issue_id)
        references issue (id) on delete restrict,
    constraint uk_issue_input_idempotency unique (issue_id, idempotency_key),
    constraint chk_issue_input_sequence check (sequence >= 1),
    constraint chk_issue_input_kind check (kind in ('HUMAN', 'REVIEW_FEEDBACK', 'RETRY', 'SYSTEM')),
    constraint chk_issue_input_body_not_blank check (length(trim(body)) > 0),
    constraint chk_issue_input_body_len check (octet_length(body) <= 1048576),
    constraint chk_issue_input_idempotency_not_blank check (idempotency_key is null or (length(trim(idempotency_key)) > 0 and idempotency_key = btrim(idempotency_key)))
);

comment on table issue_input is 'Issue 追加输入流：人类输入、评审反馈、重试标记与系统指令';
comment on column issue_input.issue_id is '关联 Issue UUID';
comment on column issue_input.sequence is 'Issue 内单调递增序号，>= 1';
comment on column issue_input.kind is '输入类别：HUMAN, REVIEW_FEEDBACK, RETRY, SYSTEM';
comment on column issue_input.body is '输入正文纯文本（最大 1 MiB）';
comment on column issue_input.idempotency_key is '同 Issue 幂等键';

------------------------------------------------------------------------------
-- Issue runs
------------------------------------------------------------------------------
create table issue_run (
    id                      uuid          not null,
    issue_id                uuid          not null,
    ordinal                 bigint        not null,
    role                    varchar(32)   not null,
    actor_type              varchar(32)   not null,
    agent_name              varchar(128),
    submission_run_id       uuid,
    status                  varchar(32)   not null,
    outcome                 varchar(32),
    observed_spec_revision  bigint        not null default 0,
    observed_input_sequence bigint        not null default 0,
    continuation_count      int           not null default 0,
    max_continuations       int           not null default 10,
    deadline                timestamptz(3),
    waiting_reason          text,
    result                  jsonb,
    terminal_action_id      varchar(255),
    version                 bigint        not null default 0,
    created_at              timestamptz(3) not null default clock_timestamp(),
    updated_at              timestamptz(3) not null default clock_timestamp(),
    completed_at            timestamptz(3),
    constraint pk_issue_run primary key (id),
    constraint fk_issue_run_issue foreign key (issue_id)
        references issue (id) on delete restrict,
    constraint fk_issue_run_agent foreign key (agent_name)
        references agent_definition (name) on delete restrict,
    constraint uk_issue_run_issue_ordinal unique (issue_id, ordinal),
    constraint uk_issue_run_id_issue unique (id, issue_id),
    constraint fk_issue_run_submission foreign key (submission_run_id, issue_id)
        references issue_run (id, issue_id) on delete restrict,
    constraint uk_issue_run_terminal_action unique (terminal_action_id),
    constraint chk_issue_run_ordinal check (ordinal >= 1),
    constraint chk_issue_run_role check (
        (role = 'EXECUTOR' and actor_type = 'AGENT' and submission_run_id is null) or
        (role = 'REVIEWER' and submission_run_id is not null and (actor_type <> 'HUMAN' or status = 'COMPLETED'))
    ),
    constraint chk_issue_run_actor_agent check (
        (actor_type = 'AGENT' and agent_name is not null and length(trim(agent_name)) > 0 and agent_name = btrim(agent_name)) or
        (actor_type = 'HUMAN' and agent_name is null)
    ),
    constraint chk_issue_run_status check (status in ('RUNNING', 'WAITING_HUMAN', 'COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN')),
    constraint chk_issue_run_outcome check (outcome is null or outcome in ('SUBMITTED', 'APPROVED', 'CHANGES_REQUESTED')),
    constraint chk_issue_run_observed_spec check (observed_spec_revision >= 0),
    constraint chk_issue_run_observed_input check (observed_input_sequence >= 0),
    constraint chk_issue_run_continuation_limit check (
        continuation_count >= 0 and max_continuations >= 0 and continuation_count <= max_continuations
    ),
    constraint chk_issue_run_version check (version >= 0),
    constraint chk_issue_run_terminal_action_not_blank check (
        terminal_action_id is null or (length(trim(terminal_action_id)) > 0 and terminal_action_id = btrim(terminal_action_id))
    ),
    constraint chk_issue_run_waiting_reason_len check (
        waiting_reason is null or (length(trim(waiting_reason)) > 0 and octet_length(waiting_reason) <= 16384 and waiting_reason = btrim(waiting_reason))
    ),
    constraint chk_issue_run_result_len check (result is null or octet_length(result::text) <= 65536),
    constraint chk_issue_run_lifecycle check (
        (
            status = 'RUNNING' and
            waiting_reason is null and
            completed_at is null and
            terminal_action_id is null and
            outcome is null and
            result is null
        ) or
        (
            status = 'WAITING_HUMAN' and
            waiting_reason is not null and
            length(trim(waiting_reason)) > 0 and
            waiting_reason = btrim(waiting_reason) and
            completed_at is null and
            terminal_action_id is null and
            outcome is null and
            result is null
        ) or
        (
            status = 'COMPLETED' and
            waiting_reason is null and
            completed_at is not null and
            terminal_action_id is not null and
            length(trim(terminal_action_id)) > 0 and
            terminal_action_id = btrim(terminal_action_id) and
            outcome is not null and
            result is not null and
            jsonb_typeof(result) = 'object' and
            (
                (role = 'EXECUTOR' and outcome = 'SUBMITTED') or
                (role = 'REVIEWER' and outcome in ('APPROVED', 'CHANGES_REQUESTED'))
            )
        ) or
        (
            status in ('FAILED', 'CANCELLED', 'UNKNOWN') and
            waiting_reason is not null and
            length(trim(waiting_reason)) > 0 and
            waiting_reason = btrim(waiting_reason) and
            completed_at is not null and
            outcome is null and
            terminal_action_id is null and
            result is null
        )
    )
);

create unique index uk_issue_run_single_active on issue_run (issue_id)
    where status in ('RUNNING', 'WAITING_HUMAN');
create index idx_issue_run_issue on issue_run (issue_id);

comment on table issue_run is 'IssueRun 运行实体：执行/评审周期记录与围栏状态';
comment on column issue_run.id is 'Run UUID 主键（应用生成）';
comment on column issue_run.issue_id is '归属 Issue UUID';
comment on column issue_run.ordinal is 'Issue 内单调运行编号，>= 1';
comment on column issue_run.role is '角色：EXECUTOR, REVIEWER';
comment on column issue_run.actor_type is '行为者类型：AGENT, HUMAN';
comment on column issue_run.agent_name is '冻结的 AgentDefinition 名称（AGENT 必填，HUMAN 为空）';
comment on column issue_run.submission_run_id is 'REVIEWER 必填，指向被评审的 EXECUTOR Run（同 Issue 约束）';
comment on column issue_run.status is '状态：RUNNING, WAITING_HUMAN, COMPLETED, FAILED, CANCELLED, UNKNOWN';
comment on column issue_run.outcome is '终态结果：SUBMITTED, APPROVED, CHANGES_REQUESTED';
comment on column issue_run.observed_spec_revision is '观察到的 spec revision 游标';
comment on column issue_run.observed_input_sequence is '观察到的 input sequence 游标';
comment on column issue_run.continuation_count is '已投递 continuation 计数';
comment on column issue_run.max_continuations is '允许最大 continuation 计数';
comment on column issue_run.deadline is 'Run 绝对截止时间戳';
comment on column issue_run.waiting_reason is '等待或失败原因说明（最大 16 KiB）';
comment on column issue_run.result is '终态结构化结果 JSONB（最大 64 KiB）';
comment on column issue_run.terminal_action_id is '终态动作唯一幂等标识';

------------------------------------------------------------------------------
-- Session ownership
--
-- 一行只允许一个非空 owner 外键，以关系约束直接表达排他弧；session_id 主键保证
-- Chat、Canvas、Project、IssueRun 四类 owner 全局互斥。Project 和 IssueRun 额外
-- 唯一，分别至多绑定一个长期 Session；Chat 和 Canvas 可以持有多个 Session。
------------------------------------------------------------------------------
create table session_owner (
    session_id   uuid          not null,
    chat_id      uuid,
    canvas_id    uuid,
    project_id   uuid,
    issue_run_id uuid,
    created_at   timestamptz(3) not null default clock_timestamp(),
    constraint pk_session_owner primary key (session_id),
    constraint fk_session_owner_session foreign key (session_id)
        references harness_session (id) on delete restrict,
    constraint fk_session_owner_chat foreign key (chat_id)
        references chat (id) on delete restrict,
    constraint fk_session_owner_canvas foreign key (canvas_id)
        references canvas_document (id) on delete restrict,
    constraint fk_session_owner_project foreign key (project_id)
        references project (id) on delete restrict,
    constraint fk_session_owner_issue_run foreign key (issue_run_id)
        references issue_run (id) on delete restrict,
    constraint uk_session_owner_project unique (project_id),
    constraint uk_session_owner_issue_run unique (issue_run_id),
    constraint ck_session_owner_exactly_one check (
        num_nonnulls(chat_id, canvas_id, project_id, issue_run_id) = 1
    )
);

create index idx_session_owner_chat
    on session_owner (chat_id, created_at desc, session_id desc)
    where chat_id is not null;

create index idx_session_owner_canvas
    on session_owner (canvas_id, created_at desc, session_id desc)
    where canvas_id is not null;

comment on table session_owner is 'Harness Session 的产品归属排他弧：每行恰有一个 Chat、Canvas、Project 或 IssueRun owner';
comment on column session_owner.session_id is 'Harness Session UUID（主键，全局至多一个 owner）';
comment on column session_owner.chat_id is 'Chat owner；非空时其他 owner 列必须为空';
comment on column session_owner.canvas_id is 'Canvas owner；非空时其他 owner 列必须为空';
comment on column session_owner.project_id is 'Project owner；非空时其他 owner 列必须为空，且每 Project 至多一行';
comment on column session_owner.issue_run_id is 'IssueRun owner；非空时其他 owner 列必须为空，且每 IssueRun 至多一行';
comment on column session_owner.created_at is '归属边建立时间（毫秒精度）';
comment on index idx_session_owner_chat is '按 Chat 枚举 Session，覆盖最近归属优先排序';
comment on index idx_session_owner_canvas is '按 Canvas 枚举 Session，覆盖最近归属优先排序';

------------------------------------------------------------------------------
-- Issue Controller work
------------------------------------------------------------------------------
create table issue_controller_work (
    issue_id     uuid          not null,
    wake_version bigint        not null default 1,
    due_at       timestamptz(3) not null default clock_timestamp(),
    lease_token  varchar(128),
    lease_until  timestamptz(3),
    updated_at   timestamptz(3) not null default clock_timestamp(),
    constraint pk_issue_controller_work primary key (issue_id),
    constraint fk_issue_controller_work_issue foreign key (issue_id)
        references issue (id) on delete restrict,
    constraint chk_issue_controller_work_wake check (wake_version > 0),
    constraint chk_issue_controller_work_lease check (
        (lease_token is null and lease_until is null) or
        (lease_token is not null and lease_until is not null and length(trim(lease_token)) > 0 and length(trim(lease_token)) <= 128 and lease_token = btrim(lease_token))
    )
);

create index idx_issue_controller_work_due on issue_controller_work (due_at);

comment on table issue_controller_work is 'Issue Controller 调度工作：每 Issue 最多单行，确定性 lease/wake 围栏';
comment on column issue_controller_work.issue_id is '所属 Issue UUID（主键）';
comment on column issue_controller_work.wake_version is '唤醒版本号，每次请求唤醒递增，> 0';
comment on column issue_controller_work.due_at is '下次可调度时间戳';
comment on column issue_controller_work.lease_token is '当前持有节点租约令牌';
comment on column issue_controller_work.lease_until is '租约截止时间戳';
comment on column issue_controller_work.updated_at is '更新时间戳';

------------------------------------------------------------------------------
-- Issue Controller due-work notification hint
------------------------------------------------------------------------------
create or replace function notify_issue_controller_work_due()
returns trigger as $$
begin
    if new.due_at <= clock_timestamp() and (new.lease_until is null or new.lease_until <= clock_timestamp()) then
        perform pg_notify('issue_controller_work_due', new.issue_id::text);
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_issue_controller_work_due
    after insert or update on issue_controller_work
    for each row execute function notify_issue_controller_work_due();

------------------------------------------------------------------------------
-- 4. Project/Issue snapshot invalidation hint
------------------------------------------------------------------------------

-- Project/Issue facts notify browser-facing listeners only after transaction commit.
-- The payload is a refetch hint, never an event log.
create or replace function project_issue_changed_notify()
returns trigger as $$
declare
    target_project_id uuid;
    target_issue_id uuid;
    target_run_id uuid;
    target_session_id uuid;
begin
    if tg_table_name = 'project' then
        if tg_op = 'DELETE' then
            target_project_id := old.id;
        else
            target_project_id := new.id;
        end if;
    elsif tg_table_name in ('issue', 'issue_dependency') then
        if tg_op = 'DELETE' then
            target_project_id := old.project_id;
        else
            target_project_id := new.project_id;
        end if;
    elsif tg_table_name in ('issue_input', 'issue_run') then
        if tg_op = 'DELETE' then
            target_issue_id := old.issue_id;
        else
            target_issue_id := new.issue_id;
        end if;
        select project_id into target_project_id
        from issue
        where id = target_issue_id;
    elsif tg_table_name = 'session_owner' then
        if tg_op = 'DELETE' then
            if old.project_id is not null then
                target_project_id := old.project_id;
            else
                target_run_id := old.issue_run_id;
            end if;
        else
            if new.project_id is not null then
                target_project_id := new.project_id;
            else
                target_run_id := new.issue_run_id;
            end if;
        end if;
        if target_project_id is null and target_run_id is not null then
            select i.project_id into target_project_id
            from issue_run r
            join issue i on i.id = r.issue_id
            where r.id = target_run_id;
        end if;
    elsif tg_table_name = 'harness_thread' then
        if tg_op = 'DELETE' then
            target_session_id := old.session_id;
        else
            target_session_id := new.session_id;
        end if;
        select project_id into target_project_id
        from session_owner
        where session_id = target_session_id;

        if target_project_id is null then
            select i.project_id into target_project_id
            from session_owner so
            join issue_run r on r.id = so.issue_run_id
            join issue i on i.id = r.issue_id
            where so.session_id = target_session_id;
        end if;
    end if;

    if target_project_id is not null then
        perform pg_notify('project_issue_changed', target_project_id::text);
    end if;
    return null;
end;
$$ language plpgsql;

create trigger trg_project_issue_changed_project
    after insert or update or delete on project
    for each row execute function project_issue_changed_notify();

create trigger trg_project_issue_changed_issue
    after insert or update or delete on issue
    for each row execute function project_issue_changed_notify();

create trigger trg_project_issue_changed_dependency
    after insert or update or delete on issue_dependency
    for each row execute function project_issue_changed_notify();

create trigger trg_project_issue_changed_input
    after insert or update or delete on issue_input
    for each row execute function project_issue_changed_notify();

create trigger trg_project_issue_changed_run
    after insert or update or delete on issue_run
    for each row execute function project_issue_changed_notify();

create trigger trg_project_issue_changed_session_owner
    after insert or update or delete on session_owner
    for each row execute function project_issue_changed_notify();

create trigger trg_project_issue_changed_thread
    after insert or update or delete on harness_thread
    for each row execute function project_issue_changed_notify();


------------------------------------------------------------------------------
-- 5. Thread version NOTIFY hint
--
-- version is owned by HarnessRuntime; PostgreSQL never bumps it. This trigger
-- is only a wake-up hint for in-process projection listeners: it notifies when
-- a Thread row is inserted or its version column actually changed, and never
-- mutates the row. The NOTIFY payload is the strict parsable text
-- `{threadId}:{version}`; there are deliberately no child-table version
-- triggers.
------------------------------------------------------------------------------

create or replace function harness_thread_version_notify()
returns trigger language plpgsql as $$
begin
    if tg_op = 'INSERT' or new.version is distinct from old.version then
        perform pg_notify(
            'harness_thread_version',
            new.id::text || ':' || new.version::text
        );
    end if;
    return new;
end $$;

create trigger trg_harness_thread_version_notify
    after insert or update of version on harness_thread
    for each row execute function harness_thread_version_notify();

------------------------------------------------------------------------------
-- 6. Canvas ownership, same-canvas composite FKs and version
--    NOTIFY hint.
--
-- All Canvas ownership FKs are ON DELETE RESTRICT: deletion is always driven
-- by the application in explicit order (pins -> runs -> resources -> links ->
-- nodes -> groups -> dedup -> Session ownership -> document), never by cascades that could bypass
-- StorageBlobManager refcounts.
------------------------------------------------------------------------------

alter table canvas_group
    add constraint fk_canvas_group_canvas foreign key (canvas_id)
    references canvas_document (id) on delete restrict;

alter table canvas_node
    add constraint fk_canvas_node_canvas foreign key (canvas_id)
    references canvas_document (id) on delete restrict;

alter table canvas_node
    add constraint fk_canvas_node_group
    foreign key (canvas_id, group_id)
    references canvas_group (canvas_id, id);

alter table canvas_link
    add constraint fk_canvas_link_source
    foreign key (canvas_id, source_node_id)
    references canvas_node (canvas_id, id) on delete restrict;

alter table canvas_link
    add constraint fk_canvas_link_target
    foreign key (canvas_id, target_node_id)
    references canvas_node (canvas_id, id) on delete restrict;

alter table canvas_resource
    add constraint fk_canvas_resource_canvas foreign key (canvas_id)
    references canvas_document (id) on delete restrict;

alter table canvas_resource
    add constraint fk_canvas_resource_owner
    foreign key (canvas_id, owner_node_id)
    references canvas_node (canvas_id, id) on delete restrict;

alter table canvas_command_dedup
    add constraint fk_canvas_command_dedup_canvas foreign key (canvas_id)
    references canvas_document (id) on delete restrict;

alter table canvas_function_run
    add constraint fk_canvas_function_run_node foreign key (node_id)
    references canvas_node (id) on delete restrict;

alter table canvas_function_resource_pin
    add constraint fk_canvas_function_resource_pin_node
    foreign key (canvas_id, node_id)
    references canvas_node (canvas_id, id) on delete restrict;

-- Canvas document version NOTIFY hint.
--
-- version is owned by the application; PostgreSQL never bumps it. This trigger
-- is only a wake-up hint for the in-process Canvas version/application event hub: it notifies when a
-- canvas_document row is inserted or its version column actually changed, and
-- never mutates the row. The NOTIFY payload is the parsable text
-- `{canvasId}:{version}`.

create or replace function canvas_document_version_notify()
returns trigger language plpgsql as $$
begin
    if tg_op = 'INSERT' or new.version is distinct from old.version then
        perform pg_notify('canvas_version', new.id::text || ':' || new.version::text);
    end if;
    return new;
end $$;

create trigger trg_canvas_document_version_notify
    after insert or update of version on canvas_document
    for each row execute function canvas_document_version_notify();

-- Canvas Function durable work NOTIFY hint.
--
-- canvas_function_run remains the only queue fact. This trigger only hints when
-- the row written by the current statement is immediately claimable READY work;
-- future READY work and expired RUNNING leases are recovered by periodic poll.

create or replace function canvas_function_work_notify()
returns trigger language plpgsql as $$
begin
    if new.status = 'READY'
        and new.lease_token is null
        and new.available_at <= current_timestamp then
        perform pg_notify('canvas_function_work', '');
    end if;
    return new;
end $$;

create trigger trg_canvas_function_work_notify
    after insert or update on canvas_function_run
    for each row execute function canvas_function_work_notify();

-- 7. Global blob storage
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
    cleanup_requested_at timestamptz(3),
    cleanup_token       varchar(128),
    cleanup_until       timestamptz(3),
    created_at          timestamptz(3) not null default current_timestamp,
    constraint uk_storage_upload_candidate unique (candidate_blob_id),
    constraint ck_storage_upload_filename_nonblank check (btrim(filename) <> ''),
    constraint ck_storage_upload_media_type_nonblank check (btrim(declared_media_type) <> ''),
    constraint ck_storage_upload_size_nonneg check (declared_size >= 0),
    constraint ck_storage_upload_sha256 check (declared_sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_storage_upload_expiry check (expires_at > created_at),
    constraint ck_storage_upload_cleanup_pair check (
        (cleanup_token is null) = (cleanup_until is null)
    ),
    constraint ck_storage_upload_cleanup_token check (
        cleanup_token is null or (
            btrim(cleanup_token) <> ''
            and cleanup_token = btrim(cleanup_token)
            and char_length(cleanup_token) <= 128
        )
    ),
    constraint fk_storage_upload_blob foreign key (blob_id)
        references storage_blob (id) on delete restrict
);

create index idx_storage_upload_cleanup_claim
    on storage_upload (cleanup_requested_at, expires_at, cleanup_until, id);

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
comment on column storage_upload.cleanup_requested_at is
    'Durable explicit cleanup request; NULL for active uploads and retained until'
    ' maintenance removes the upload row.';
comment on column storage_upload.cleanup_token is
    'Opaque cleanup ownership token; NULL when unclaimed and fenced on finalize/release.';
comment on column storage_upload.cleanup_until is
    'Cleanup lease deadline paired with cleanup_token; an expired lease is reclaimable.';
comment on column storage_upload.created_at is 'Row creation time (timestamptz, millisecond precision).';

comment on index uk_storage_upload_candidate is
    'Every upload pre-assigns a distinct candidate blob id so PENDING rows can'
    ' never collide on the future blob identity.';
comment on index idx_storage_upload_cleanup_claim is
    'Storage Maintenance claim scan: requested or expired uploads with absent/expired'
    ' leases, ordered by request/deadline time.';

-- Session 级 Blob 引用：Session 的持久化 message（USER/RESOURCE 与 TOOL 结果）通过本表持有 storage_blob 的
-- 活跃引用。ref_count 维护完全由应用层 SessionBlobRefManager 显式执行（insert+retain / delete+release 成对），
-- 绝不依赖 ON DELETE CASCADE 或触发器；两个 FK 都是 RESTRICT，删除 Session / blob 前必须先删除本表对应行。
-- 本表属于应用层业务（非 Harness runtime 协议），因此不使用 harness_ 前缀。
create table session_blob_ref (
    session_id  uuid          not null,
    blob_id     uuid          not null,
    created_at  timestamptz(3) not null default current_timestamp,
    constraint pk_session_blob_ref primary key (session_id, blob_id),
    constraint fk_session_blob_ref_session foreign key (session_id)
        references harness_session (id) on delete restrict,
    constraint fk_session_blob_ref_blob foreign key (blob_id)
        references storage_blob (id) on delete restrict
);

create index idx_session_blob_ref_blob
    on session_blob_ref (blob_id, session_id);

comment on table session_blob_ref is
    'Session 与 storage_blob 的显式引用边：每行恰好对应一次 blob retain，删除时由应用层逐行 release；'
    'FK 均为 RESTRICT，深删除必须先删本表';
comment on column session_blob_ref.session_id is '所属 Harness Session（RESTRICT FK）';
comment on column session_blob_ref.blob_id is '被引用的全局 blob（RESTRICT FK，ACTIVE 行）';
comment on column session_blob_ref.created_at is '引用创建时间（timestamptz，毫秒精度）';

comment on index idx_session_blob_ref_blob is '按 blob 反向枚举持有它的 Session（深删除与对账）';

------------------------------------------------------------------------------
-- 8. Canvas resource blob FK (must follow the global blob storage section)
------------------------------------------------------------------------------

alter table canvas_resource
    add constraint fk_canvas_resource_blob foreign key (blob_id)
    references storage_blob (id) on delete restrict;
