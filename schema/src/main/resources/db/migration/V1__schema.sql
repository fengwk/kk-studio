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
-- (the eight tables + their indexes) is the single authoritative definition of
-- the durable Harness protocol schema. There is no separate infra
-- schema file; this block is the only copy. HarnessRuntime owns every execution
-- id via the injected Supplier<UUID> (production: UUID::randomUUID) and owns
-- `version`; the database never mutates them. Application-owned relation tables
-- and NOTIFY hints surround this protocol without duplicating its state machine.
--
-- Product facts live in one flat model: Canvas keeps a single node shape with
-- immutable content and a replaceable current Resource[]; Project keeps one
-- strict workflow JSON with Issue Runs, one ordered Activity timeline, explicit
-- published Evidence and a per-Issue work mailbox; Chat holds multiple Sessions
-- through the direct `chat_session` association. Every product id is an
-- application-allocated UUID (client for node/group/request/command ids, server
-- for document/resource/run ids): there are no id sequences. `revision` (Canvas)
-- and `version` (Project) are application-owned cursors; the NOTIFY triggers
-- below only hint listeners after commit and never mutate a row. Resource blobs
-- are owned by the global `storage_blob` refcount lifecycle; product rows only
-- reference them (RESTRICT).
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
    install_config      jsonb,
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
    constraint ck_environment_version_nonneg check (version >= 0),
    constraint ck_environment_install_config_object check (
        install_config is null or jsonb_typeof(install_config) = 'object'
    )
);

comment on table environment is '稳定 Environment 注册表：UUID 主键跨重启不变，name 是唯一展示与配置标识，registration_token 是 Daemon 握手凭证';
comment on column environment.id is 'Environment 的全局唯一 UUID（服务端生成，永不变更）';
comment on column environment.name is 'Environment 唯一名称（NFKC trim，<= 64 字符，不含空白或斜杠）';
comment on column environment.registration_token is 'Daemon HELLO 握手的注册凭证（部署侧秘密，仅 create/rotate 响应一次性返回，正常查询绝不泄露）';
comment on column environment.install_config is '保存的安装设置（不含 token，不表示已部署）';
comment on column environment.created_at is '创建时间（毫秒精度）';
comment on column environment.updated_at is '最后更新时间（毫秒精度），应用侧维护';
comment on column environment.version is 'CAS 乐观锁版本：非负，从 0 开始，每次更新 +1';

create index idx_environment_updated
    on environment (updated_at, id);

create table agent_provider (
    name            varchar(64)   primary key,
    description     text,
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
    description     text,
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
    description     text,
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

-- -----------------------------------------------------------------------------
-- Platform 全局 Skill Package
--
-- Skill 正文、references、scripts 与 assets 全部保留在 Git：Platform 每个 packageName 只
-- 保存一行——不可变 repository URL、只用于检查候选更新的 branch、人工确认的 exact current
-- commit、最近观察到的 branch HEAD 与检查结果，以及从 current commit 派生的 Skill
-- manifest JSON。repository URL 创建后不可改，更换仓库要使用新的 Package。
--
-- canonical 文本规则与 SkillNames 一致：无环绕空白、无控制字符、不含 : / @ \。
-- -----------------------------------------------------------------------------

create table skill_package (
    package_name         varchar(128)  not null,
    description          text,
    repository_url       text          not null,
    branch               varchar(255)  not null,
    current_commit       varchar(64)   not null,
    observed_head_commit varchar(64),
    head_checked_at      timestamptz(3),
    head_check_error     text,
    skills               jsonb         not null default '[]'::jsonb,
    version              bigint        not null default 0,
    create_time          timestamptz(3) not null default current_timestamp,
    update_time          timestamptz(3) not null default current_timestamp,
    constraint pk_skill_package primary key (package_name),
    constraint ck_skill_package_name check (
        package_name = btrim(package_name)
        and char_length(package_name) > 0
        and package_name !~ '[[:cntrl:]]'
        and position(':' in package_name) = 0
        and position('/' in package_name) = 0
        and position('@' in package_name) = 0
        and position('\' in package_name) = 0
    ),
    constraint ck_skill_package_description check (
        description is null
        or (description = btrim(description) and char_length(description) > 0)
    ),
    constraint ck_skill_package_repository_url check (
        repository_url = btrim(repository_url)
        and repository_url ~ '^[a-z][a-z0-9+.-]*://[^[:space:]]+$'
        and char_length(repository_url) <= 2048
        and repository_url !~ '[[:cntrl:]]'
        and repository_url !~ '://[^/[:space:]]*@'
    ),
    constraint ck_skill_package_branch check (
        branch = btrim(branch)
        and char_length(branch) > 0
        and branch !~ '[[:cntrl:]]'
    ),
    constraint ck_skill_package_current_commit check (
        current_commit ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'
    ),
    constraint ck_skill_package_observed_head_commit check (
        observed_head_commit is null
        or observed_head_commit ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'
    ),
    constraint ck_skill_package_head_check_error check (
        head_check_error is null
        or (
            head_check_error = btrim(head_check_error)
            and char_length(head_check_error) > 0
            and octet_length(head_check_error) <= 4096
        )
    ),
    constraint ck_skill_package_skills check (jsonb_typeof(skills) = 'array'),
    constraint ck_skill_package_version check (version >= 0),
    constraint ck_skill_package_time_order check (update_time >= create_time)
);

comment on table skill_package is 'Platform 全局 Skill Package 权威行：每个 packageName 恰一行，内容保存在 Git，数据库只保存不可变仓库事实、人工确认的 exact commit、branch 检查观察值与派生的 Skill manifest';
comment on column skill_package.package_name is 'package 名（主键与不可变路由身份；非空白、无环绕空白、无控制字符、不含 : / @ \、≤128）';
comment on column skill_package.description is '可空 package 描述；null 表示未填写';
comment on column skill_package.repository_url is '不可变 Git repository URL（必须带 scheme、无内嵌 userinfo、无空白、≤2048）；更换仓库必须新建 Package';
comment on column skill_package.branch is '只用于检查候选更新的 branch（非空白、无环绕空白、无控制字符、≤255）；不决定发布内容';
comment on column skill_package.current_commit is '人工确认的 exact Git object id（40 或 64 位小写 hex），与 skills 在同一次 CAS 中原子切换';
comment on column skill_package.observed_head_commit is '最近一次成功检查到的 branch HEAD（40 或 64 位小写 hex）；从未成功检查时为 null';
comment on column skill_package.head_checked_at is '最近一次检查时间（毫秒精度）；从未检查时为 null';
comment on column skill_package.head_check_error is '最近一次检查的有界错误摘要（非空、无环绕空白、≤4096 字节）；检查成功时为 null';
comment on column skill_package.skills is '从 current_commit 派生的 Skill manifest（JSON array，元素为 {name, description} 并按 name 排序）；元素形状由应用严格验证';
comment on column skill_package.version is 'CAS 乐观锁版本：非负，从 0 开始，实际事实变化时 +1';
comment on column skill_package.create_time is '创建时间（毫秒精度）';
comment on column skill_package.update_time is '最后更新时间（毫秒精度），应用侧维护';

-- Skill Package 发布提示。
--
-- version 由应用拥有，PostgreSQL 绝不修改行。该触发器只在行被插入、version 真正变化或
-- 行被删除后发出提示，payload 是 package 名；listener 建连/重连时全量回读 Package，
-- 因此通知丢失不会改变 durable truth。
create or replace function skill_package_changed_notify()
returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        perform pg_notify('skill_package_changed', old.package_name);
        return old;
    end if;
    if tg_op = 'INSERT' or new.version is distinct from old.version then
        perform pg_notify('skill_package_changed', new.package_name);
    end if;
    return new;
end $$;

create trigger trg_skill_package_changed
    after insert or update or delete on skill_package
    for each row execute function skill_package_changed_notify();

-- -----------------------------------------------------------------------------
-- 构建期 Plugin 凭据
--
-- 所有构建期 Plugin 共用一行一凭据存储：plugin_id 与 classpath 中的 StudioPlugin.pluginId
-- 对齐；encrypted_payload 是 AES-256-GCM 二进制 envelope（格式版本 + 随机 nonce + 认证
-- 密文，AAD 绑定 pluginId/region/formatVersion）。加密主密钥来自部署侧 owner-only key
-- file，绝不进入本表、SystemSettings、DTO 或日志；管理查询永远不返回 encrypted_payload。
-- 断连以删除行表达，迟到 finalize 由 lease token 与 version 围栏拒绝。
-- -----------------------------------------------------------------------------

create table plugin_credential (
    plugin_id           varchar(64)   not null,
    encrypted_payload   bytea         not null,
    region              varchar(64)   not null,
    expires_at          timestamptz(3) not null,
    next_refresh_at     timestamptz(3) not null,
    status              varchar(32)   not null,
    last_refreshed_at   timestamptz(3),
    last_refresh_error  text,
    refresh_lease_token varchar(128),
    refresh_lease_until timestamptz(3),
    version             bigint        not null default 0,
    create_time         timestamptz(3) not null default current_timestamp,
    update_time         timestamptz(3) not null default current_timestamp,
    constraint pk_plugin_credential primary key (plugin_id),
    constraint ck_plugin_credential_plugin_id check (
        plugin_id ~ '^[a-z0-9]+([.-][a-z0-9]+)*$'
    ),
    constraint ck_plugin_credential_encrypted_payload check (
        octet_length(encrypted_payload) > 0
    ),
    constraint ck_plugin_credential_region check (
        region = btrim(region)
        and char_length(region) > 0
    ),
    constraint ck_plugin_credential_status check (
        status in ('CONNECTED', 'REFRESH_FAILED', 'REFRESH_UNCERTAIN', 'REAUTH_REQUIRED')
    ),
    constraint ck_plugin_credential_last_refresh_error check (
        last_refresh_error is null
        or (
            last_refresh_error = btrim(last_refresh_error)
            and char_length(last_refresh_error) > 0
            and octet_length(last_refresh_error) <= 4096
        )
    ),
    constraint ck_plugin_credential_refresh_lease check (
        (refresh_lease_token is null) = (refresh_lease_until is null)
        and (
            refresh_lease_token is null
            or (
                refresh_lease_token = btrim(refresh_lease_token)
                and char_length(refresh_lease_token) > 0
            )
        )
    ),
    constraint ck_plugin_credential_version check (version >= 0),
    constraint ck_plugin_credential_time_order check (update_time >= create_time)
);

comment on table plugin_credential is '构建期 Plugin 的加密凭据权威行：每个已认证 Plugin 一行，未认证时无行，断连即删除行';
comment on column plugin_credential.plugin_id is 'Plugin 全局唯一不可变安装身份（canonical 小写点划线标识符，≤64），与 classpath 中的 StudioPlugin.pluginId 对齐';
comment on column plugin_credential.encrypted_payload is 'AES-256-GCM 二进制 envelope（格式版本 + 96-bit 随机 nonce + 认证密文，AAD 绑定 pluginId/region/formatVersion）；任何查询投影都不得返回';
comment on column plugin_credential.region is '非秘密 region 路由元数据（无环绕空白、≤64），只允许 Plugin descriptor 声明的固定 region 候选';
comment on column plugin_credential.expires_at is 'access token 失效时刻（毫秒精度）';
comment on column plugin_credential.next_refresh_at is '下一次 refresh claim 时刻（毫秒精度）；仅当该时刻已到且 lease 为空或过期时可被 claim';
comment on column plugin_credential.status is '状态：CONNECTED, REFRESH_FAILED, REFRESH_UNCERTAIN, REAUTH_REQUIRED';
comment on column plugin_credential.last_refreshed_at is '最近一次成功刷新时刻（毫秒精度）；从未成功刷新时为 null';
comment on column plugin_credential.last_refresh_error is '最近一次刷新失败的有界去敏错误摘要（非空、无环绕空白、≤4096 字节）；成功时为 null';
comment on column plugin_credential.refresh_lease_token is '跨节点 refresh lease 令牌（与 refresh_lease_until 同时存在或同时缺失）';
comment on column plugin_credential.refresh_lease_until is 'refresh lease 到期时刻（毫秒精度）；过期 lease 视为结果未知';
comment on column plugin_credential.version is 'CAS 乐观锁版本：非负，从 0 开始，每次写入 +1；finalize 必须匹配 lease token 与 version';
comment on column plugin_credential.create_time is '创建时间（毫秒精度）';
comment on column plugin_credential.update_time is '最后更新时间（毫秒精度），应用侧维护';

create index idx_plugin_credential_refresh_due
    on plugin_credential (next_refresh_at);

comment on index idx_plugin_credential_refresh_due is 'refresh dispatcher 按 next_refresh_at 扫描到期行';

-- Platform MCP Server 配置：name 是主键与唯一路由身份，创建后不可变。Backend 只通过
-- Streamable HTTP 连接 Server；请求 header 可能内嵌凭据，绝不进入列表投影与日志。
create table mcp_server (
    name              varchar(32)    primary key,
    url               varchar(2048)  not null,
    headers           jsonb          not null default '{}'::jsonb,
    enabled           boolean        not null default true,
    timeout_millis    bigint         not null,
    discovery_status  varchar(16)    not null default 'UNVERIFIED',
    created_at        timestamptz(3) not null default current_timestamp,
    updated_at        timestamptz(3) not null default current_timestamp,
    version           bigint         not null default 0,
    constraint ck_mcp_server_name check (
        name ~ '^[a-z][a-z0-9_]*$'
    ),
    constraint ck_mcp_server_url_nonblank check (btrim(url) <> ''),
    constraint ck_mcp_server_headers_object check (
        jsonb_typeof(headers) = 'object'
    ),
    constraint ck_mcp_server_timeout_positive check (timeout_millis > 0),
    constraint ck_mcp_server_discovery_status check (
        discovery_status in ('UNVERIFIED', 'AVAILABLE', 'FAILED')
    ),
    constraint ck_mcp_server_version_nonneg check (version >= 0),
    constraint ck_mcp_server_time_order check (updated_at >= created_at)
);

comment on table mcp_server is 'Platform MCP Server 持久配置：name 即主键与不可变路由身份，仅支持 Streamable HTTP 传输';
comment on column mcp_server.name is '唯一名与主键（创建后不可变）：^[a-z][a-z0-9_]*$，≤32 字符；同时作为模型工具名 mcp_<name>_<tool> 的组成段';
comment on column mcp_server.url is 'Streamable HTTP endpoint URL（http/https 绝对地址，不得内嵌 user-info 凭据）';
comment on column mcp_server.headers is '自定义请求 header JSON object；值支持环境变量整值占位符，由 Backend 从进程环境替换，绝不回显';
comment on column mcp_server.enabled is '公共启用开关：默认 true；false 时即使 AVAILABLE 也不可被 Agent 选择';
comment on column mcp_server.timeout_millis is '正整数毫秒超时：连接、发现与 tools/call 共用';
comment on column mcp_server.discovery_status is '发现状态：UNVERIFIED（未验证）、AVAILABLE（可用）、FAILED（失败）；仅 enabled 且 AVAILABLE 进入运行时目录';
comment on column mcp_server.created_at is '创建时间（毫秒精度）';
comment on column mcp_server.updated_at is '最后更新时间（毫秒精度），应用侧维护，不得早于 created_at';
comment on column mcp_server.version is '乐观锁行版本：非负，从 0 开始，每次写操作 +1；CAS 更新依据';

-- MCP Server 当前发现结果的工具行：成功发现整体物理替换，不存在 tombstone 或修订版本。
create table mcp_tool (
    name           varchar(64)   primary key,
    server_name    varchar(32)   not null,
    source_name    varchar(128)  not null,
    description    text          not null,
    input_schema   jsonb         not null,
    constraint fk_mcp_tool_server foreign key (server_name)
        references mcp_server (name) on delete cascade,
    constraint ck_mcp_tool_name check (
        name ~ '[A-Za-z][A-Za-z0-9_-]*'
    ),
    constraint ck_mcp_tool_source_name check (char_length(source_name) > 0),
    constraint ck_mcp_tool_description_nonblank check (btrim(description) <> ''),
    constraint ck_mcp_tool_input_schema_object check (
        jsonb_typeof(input_schema) = 'object'
    )
);

create unique index uk_mcp_tool_server_source_name
    on mcp_tool (server_name, source_name);

comment on table mcp_tool is 'MCP Server 当前发现结果的工具行：name 即模型可见工具身份与主键，成功发现整体替换，父 Server 删除时级联清理';
comment on column mcp_tool.name is '模型可见工具名（主键）：mcp_<server_name>_<normalized_source_tool_name>，须满足 ToolDescriptor name 语法且 ≤64';
comment on column mcp_tool.server_name is '所属 MCP Server name；随父行删除级联硬删除';
comment on column mcp_tool.source_name is 'MCP 工具原始名（同一 Server 内唯一）';
comment on column mcp_tool.description is '工具描述（非空白），冻结进 ToolDescriptor';
comment on column mcp_tool.input_schema is '工具 JSON input schema（JSON object），冻结进 ToolDescriptor';

-- Canvas 领域：一个节点模型、不可变内容与可替换的当前 Resource[]。
--
-- `revision` 只是同步位置坐标（命令批与旧 ACK 的比较基准），绝不作为整图 CAS
-- 前提。全部 ownership 外键都是 ON DELETE RESTRICT：删除顺序由应用显式编排
-- （pins -> runs -> resources -> nodes -> groups -> dedup -> document），
-- CASCADE 会绕过全局 storage_blob 引用计数，因此一律不用。

create table canvas_document (
    id uuid primary key,
    title varchar(256) not null check (btrim(title) <> ''),
    revision bigint not null default 0 check (revision >= 0),
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp
);

comment on table canvas_document is 'Canvas 聚合头：revision 是单调递增的同步位置，旧 ACK 不覆盖新输入；标题规范化由应用负责，DB 只拒绝空白标题';
comment on column canvas_document.id is 'Canvas 全局唯一 UUID（服务端生成）';
comment on column canvas_document.revision is 'graph 同步位置：任何成功命令批或 Function Run 状态前进恰好 +1';
comment on column canvas_document.created_at is '创建时间（毫秒精度）';
comment on column canvas_document.updated_at is '最后更新时间（毫秒精度），应用侧维护';

create index idx_canvas_document_updated
    on canvas_document (updated_at, id);

create table canvas_group (
    id uuid primary key,
    canvas_id uuid not null references canvas_document (id) on delete restrict,
    title varchar(256) not null check (btrim(title) <> ''),
    x double precision not null,
    y double precision not null,
    width double precision not null,
    height double precision not null,
    unique (canvas_id, id),
    constraint ck_canvas_group_geometry check (
        x not in ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8)
        and y not in ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8)
        and width > 0 and width < 'Infinity'::float8
        and height > 0 and height < 'Infinity'::float8
    )
);

comment on table canvas_group is '不嵌套、使用 world 坐标的 Canvas group；(canvas_id, id) 同时是节点同 Canvas 复合引用的目标';
comment on column canvas_group.id is 'Group 全局唯一 UUID（客户端生成）';
comment on column canvas_group.canvas_id is '所属 Canvas（RESTRICT）';
comment on column canvas_group.title is '规范化标题（非空白，<= 256 字符）';
comment on column canvas_group.x is 'world 坐标 x（有限数）';
comment on column canvas_group.y is 'world 坐标 y（有限数）';
comment on column canvas_group.width is '正宽度（有限数）';
comment on column canvas_group.height is '正高度（有限数）';

create table canvas_node (
    id uuid primary key,
    canvas_id uuid not null references canvas_document (id) on delete restrict,
    name varchar(256) not null check (btrim(name) <> '' and name = btrim(name)),
    -- Compatibility-folded uniqueness key. btrim runs after normalize so that
    -- NBSP and other compatibility whitespace cannot smuggle a duplicate or an
    -- all-whitespace name; control characters stay an application concern.
    name_key text generated always as (lower(btrim(normalize(name, NFKC)))) stored,
    x double precision not null,
    y double precision not null,
    width double precision not null,
    height double precision not null,
    group_id uuid,
    "function" jsonb,
    constraint uk_canvas_node_canvas_id unique (canvas_id, id),
    constraint uk_canvas_node_canvas_name_key unique (canvas_id, name_key),
    constraint fk_canvas_node_group foreign key (canvas_id, group_id)
        references canvas_group (canvas_id, id) on delete restrict,
    constraint ck_canvas_node_name_key check (name_key <> ''),
    constraint ck_canvas_node_geometry check (
        x not in ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8)
        and y not in ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8)
        and width > 0 and width < 'Infinity'::float8
        and height > 0 and height < 'Infinity'::float8
    ),
    -- Base shape {name,args}; strict plugin schemas and resource-reference
    -- validation are application responsibilities.
    constraint ck_canvas_node_function check (
        "function" is null
        or (
            jsonb_typeof("function") = 'object'
            and coalesce(jsonb_typeof("function" -> 'name'), '') = 'string'
            and coalesce(btrim("function" ->> 'name'), '') <> ''
            and coalesce(jsonb_typeof("function" -> 'args'), '') = 'object'
        )
    )
);

comment on table canvas_node is 'Canvas 唯一的节点形态：普通资源节点或可携带资源输出的 Function 节点；name_key 折叠兼容空白与大小写后同 Canvas 唯一';
comment on column canvas_node.id is 'Node 全局唯一 UUID（客户端生成）';
comment on column canvas_node.canvas_id is '所属 Canvas（RESTRICT）';
comment on column canvas_node.name is '展示名（非空白且无环绕空白；控制字符由应用校验）';
comment on column canvas_node.name_key is '唯一性键：lower(btrim(normalize(name, NFKC)))';
comment on column canvas_node.x is 'world 坐标 x（有限数）';
comment on column canvas_node.y is 'world 坐标 y（有限数）';
comment on column canvas_node.width is '正宽度（有限数）';
comment on column canvas_node.height is '正高度（有限数）';
comment on column canvas_node.group_id is '所属 Group（可空，必须与节点同 Canvas）';
comment on column canvas_node."function" is 'Function 配置 {name,args}（可空；严格插件 schema 与资源引用由应用校验）';

create index idx_canvas_node_group on canvas_node (canvas_id, group_id) where group_id is not null;

-- 不可变内容行：blob_id 与 text_content 恰好一个；行可以在只被活跃 Run pin 时
-- 暂时无 owner。blob_id 的 storage_blob 外键在存储区之后追加。
create table canvas_resource (
    id uuid primary key,
    canvas_id uuid not null references canvas_document (id) on delete restrict,
    owner_node_id uuid,
    resource_index integer,
    name varchar(512) not null check (btrim(name) <> ''),
    blob_id uuid,
    text_content text,
    created_at timestamptz(3) not null default current_timestamp,
    constraint uk_canvas_resource_canvas_id unique (canvas_id, id),
    constraint uk_canvas_resource_slot unique (canvas_id, owner_node_id, resource_index),
    constraint fk_canvas_resource_owner foreign key (canvas_id, owner_node_id)
        references canvas_node (canvas_id, id) on delete restrict,
    constraint ck_canvas_resource_owner_pair check ((owner_node_id is null) = (resource_index is null)),
    constraint ck_canvas_resource_index check (resource_index is null or resource_index >= 0),
    constraint ck_canvas_resource_content check ((blob_id is null) <> (text_content is null))
);

comment on table canvas_resource is '不可变资源行：可见资源直接属于节点（owner_node_id + resource_index）；Function 目标物化与 pinned orphan 可暂时无 owner；内容要么是全局 Storage blob 引用要么是内联文本';
comment on column canvas_resource.id is 'Resource 全局唯一 UUID（服务端生成）';
comment on column canvas_resource.canvas_id is '所属 Canvas（RESTRICT）';
comment on column canvas_resource.owner_node_id is '所属节点；与 resource_index 同存同缺，无 owner 的资源只可由 Function pin 保活';
comment on column canvas_resource.resource_index is '节点内从 0 递增的资源序号；与 owner_node_id 同存同缺';
comment on column canvas_resource.name is '资源显示名（非空白，<= 512 字符）';
comment on column canvas_resource.blob_id is '全局 Storage blob 引用（TEXT 资源为 null；RESTRICT，引用计数由全局存储管理）';
comment on column canvas_resource.text_content is 'TEXT 资源的内联内容（blob 资源为 null），与 blob_id 恰好互斥';
comment on column canvas_resource.created_at is '创建时间（毫秒精度）';

create index idx_canvas_resource_blob on canvas_resource (blob_id) where blob_id is not null;
create index idx_canvas_resource_created on canvas_resource (canvas_id, created_at, id);

-- 每个 Function 节点只有一行当前/最后一次 Run；结构化列服务 status/claim 查询，
-- state_json 只保存冻结计划与 checkpoint。
create table canvas_function_run (
    node_id uuid primary key references canvas_node (id) on delete restrict,
    request_id uuid not null,
    status varchar(16) not null,
    attempt integer not null default 0 check (attempt >= 0),
    available_at timestamptz(3),
    lease_token varchar(128),
    lease_until timestamptz(3),
    state_json jsonb not null check (jsonb_typeof(state_json) = 'object'),
    error text,
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp,
    unique (node_id, request_id),
    check (status in ('READY', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')),
    constraint ck_canvas_function_run_lease_pair check ((lease_token is null) = (lease_until is null)),
    check (lease_token is null or btrim(lease_token) <> ''),
    constraint ck_canvas_function_run_status_shape check (
        (status = 'READY' and available_at is not null and lease_token is null)
        or (status = 'RUNNING' and available_at is null and lease_token is not null)
        or (status in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN')
            and available_at is null and lease_token is null)
    ),
    check (status not in ('FAILED', 'UNKNOWN') or (error is not null and btrim(error) <> ''))
);

comment on table canvas_function_run is 'Function 节点当前/最后一次 Run：PK 为 node_id，request_id 全 UUID 且 (node_id, request_id) 唯一';
comment on column canvas_function_run.node_id is 'Function 节点（一个节点同时至多一个 Run 行）';
comment on column canvas_function_run.request_id is 'Run 请求 UUID（客户端生成，幂等键）';
comment on column canvas_function_run.status is '生命周期：READY / RUNNING / SUCCEEDED / FAILED / CANCELLED / UNKNOWN';
comment on column canvas_function_run.attempt is '成功 claim 次数；首次 claim 从 0 增加为 1';
comment on column canvas_function_run.available_at is 'READY 可领取时间（毫秒精度），其它状态为 null';
comment on column canvas_function_run.lease_token is 'RUNNING ownership fencing token，其它状态为 null';
comment on column canvas_function_run.lease_until is 'RUNNING lease 截止时间（毫秒精度），其它状态为 null';
comment on column canvas_function_run.state_json is 'typed/versioned 冻结计划与 checkpoint（JSON object）';
comment on column canvas_function_run.error is '公开错误信息（FAILED/UNKNOWN 时非空）';
comment on column canvas_function_run.created_at is '当前 request 创建时间（毫秒精度）';
comment on column canvas_function_run.updated_at is '最后更新时间（毫秒精度）';

create index idx_canvas_function_run_claim
    on canvas_function_run (status, available_at, lease_until, created_at, node_id)
    where status in ('READY', 'RUNNING');

-- Pin 只引用真实存在的 Resource：计划中的 OUTPUT id 在 Resource 行与 OUTPUT pin
-- 同一事务写入前只存在于 state_json，真实外键保证 pin 不会比 Resource 存活更久。
create table canvas_function_resource_pin (
    canvas_id uuid not null,
    node_id uuid not null,
    request_id uuid not null,
    role varchar(16) not null check (role in ('INPUT', 'OUTPUT')),
    resource_id uuid not null,
    primary key (canvas_id, node_id, request_id, role, resource_id),
    constraint fk_canvas_pin_node foreign key (canvas_id, node_id)
        references canvas_node (canvas_id, id) on delete restrict,
    constraint fk_canvas_pin_run foreign key (node_id, request_id)
        references canvas_function_run (node_id, request_id) on delete restrict,
    constraint fk_canvas_pin_resource foreign key (canvas_id, resource_id)
        references canvas_resource (canvas_id, id) on delete restrict
);

comment on table canvas_function_resource_pin is 'Function Run 生命周期内对资源的 pin：INPUT 为启动时冻结的引用资源，OUTPUT 为预分配的目标资源；只保护生命周期，绝不参与 blob 引用计数';
comment on column canvas_function_resource_pin.canvas_id is '所属 Canvas';
comment on column canvas_function_resource_pin.node_id is 'Function 节点';
comment on column canvas_function_resource_pin.request_id is 'Run 请求 UUID';
comment on column canvas_function_resource_pin.role is 'pin 角色：INPUT / OUTPUT';
comment on column canvas_function_resource_pin.resource_id is '被 pin 的资源（必须已存在）';

create index idx_canvas_function_pin_resource on canvas_function_resource_pin (canvas_id, resource_id);

-- 每次 Canvas 写入准入（含 Function start）都记一行；accepted_revision 记录请求
-- 首次定位的位置，不是完整响应。
create table canvas_command_dedup (
    canvas_id uuid not null references canvas_document (id) on delete restrict,
    idempotency_key uuid not null,
    request_hash char(64) not null check (request_hash ~ '^[0-9a-f]{64}$'),
    accepted_revision bigint not null,
    constraint pk_canvas_command_dedup primary key (canvas_id, idempotency_key),
    constraint ck_canvas_command_dedup_revision check (accepted_revision >= 0)
);

comment on table canvas_command_dedup is '命令批幂等：相同 (canvas_id, idempotency_key) 只能以相同 request_hash 精确回放一次；graph 同步位置由 canvas_document.revision 负责，本表不冗余存储';
comment on column canvas_command_dedup.canvas_id is '所属 Canvas（RESTRICT）';
comment on column canvas_command_dedup.idempotency_key is '客户端整批命令的幂等 UUID';
comment on column canvas_command_dedup.request_hash is '整批命令的 SHA-256（64 位小写十六进制）';
comment on column canvas_command_dedup.accepted_revision is '首次接受时的 revision 位置（非负）；重放不改变它';

-- Canvas revision NOTIFY hint.
--
-- revision is owned by the application; PostgreSQL never bumps it. This trigger
-- only wakes the in-process Canvas revision/application event hub after a
-- committed insert or actual revision change. The payload is the parsable text
-- `{canvasId}:{revision}`.
create or replace function notify_canvas_document_revision() returns trigger as $$
begin
    if tg_op = 'INSERT' or new.revision is distinct from old.revision then
        perform pg_notify('canvas_revision', new.id::text || ':' || new.revision::text);
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_canvas_document_revision_notify after insert or update of revision on canvas_document
    for each row execute function notify_canvas_document_revision();

-- Canvas Function durable work NOTIFY hint.
--
-- canvas_function_run remains the only queue fact. This trigger only hints when
-- the row written by the current statement is immediately claimable READY work;
-- future READY work and expired RUNNING leases are recovered by periodic poll.
create or replace function notify_canvas_function_work() returns trigger as $$
begin
    if new.status = 'READY' and new.lease_token is null and new.available_at <= current_timestamp then
        perform pg_notify('canvas_function_work', '');
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_canvas_function_work_notify after insert or update on canvas_function_run
    for each row execute function notify_canvas_function_work();

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
    archived_at         timestamptz(3),
    constraint ck_chat_version_nonneg check (version >= 0),
    constraint ck_chat_agent_name check (
        agent_name !~ '^[[:space:]]'
        and agent_name !~ '[[:space:]]$'
        and char_length(agent_name) > 0
        and position('/' in agent_name) = 0
    )
);

create index idx_chat_modified on chat (updated_at, created_at);
create index idx_chat_archived on chat (archived_at, updated_at, id);

comment on column chat.archived_at is '归档时间戳（毫秒精度）；非空即离开默认列表，但历史与资源保留';
comment on index idx_chat_archived is '归档 Chat 列表按 (archived_at, updated_at, id) 读取';

------------------------------------------------------------------------------
-- 1b. Environment connections and operations
------------------------------------------------------------------------------

create table environment_connection (
    environment_id  uuid           primary key,
    owner_node_id   uuid           not null,
    lease_token     uuid           not null,
    status          varchar(32)    not null,
    runtime_info    jsonb,
    skill_state     jsonb          not null default '[]'::jsonb,
    recent_events   jsonb          not null default '[]'::jsonb,
    last_seen_at    timestamptz(3) not null,
    lease_until     timestamptz(3) not null,
    constraint fk_environment_connection_environment foreign key (environment_id)
        references environment (id) on delete cascade,
    constraint ck_environment_connection_status check (
        status in ('CONNECTING', 'READY')
    ),
    constraint ck_environment_connection_runtime_info check (
        runtime_info is null or jsonb_typeof(runtime_info) = 'object'
    ),
    constraint ck_environment_connection_ready_runtime_info check (
        status <> 'READY' or (runtime_info is not null and jsonb_typeof(runtime_info) = 'object')
    ),
    constraint ck_environment_connection_skill_state check (
        jsonb_typeof(skill_state) = 'array'
    ),
    constraint ck_environment_connection_recent_events check (
        jsonb_typeof(recent_events) = 'array'
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
comment on column environment_connection.runtime_info is '最近一次被接受的 READY 宿主 metadata（JSON object）；断线或重新 CONNECTING 时保留，从未 READY 时为 null';
comment on column environment_connection.skill_state is '当前 Daemon 对各 Skill Package 的可重建同步投影（JSON array，元素含 installed commit、稳定 path、状态与有界错误）；新的 READY 先清空再全量同步，写回必须命中 owner/lease fence';
comment on column environment_connection.recent_events is '最近 200 条连接与 Skill 同步运维事件（JSON array，元素只有时间、级别、类型与去敏消息）；不保存 stdout、凭据、Git URL userinfo 或签名地址';
comment on column environment_connection.last_seen_at is '最后活跃时间（毫秒精度）';
comment on column environment_connection.lease_until is '租约到期时间（毫秒精度），必须晚于 last_seen_at';

create index idx_environment_connection_lease_until
    on environment_connection (lease_until);

create index idx_environment_connection_owner
    on environment_connection (owner_node_id);

------------------------------------------------------------------------------
-- 1c. Singleton system settings (id=1)
--
-- system_setting 是全局强类型配置聚合的权威存储：恒为一行（id=1），config 保存完整
-- SystemSettings 七个 section 的 canonical JSON（写路径只接受强类型 DTO，绝无任意 JSON
-- 写接口），version 是乐观锁 CAS 令牌（非负，每次更新 +1），时间字段毫秒精度。
-- 默认行插入安全默认聚合：tool.permission 默认 write/edit/bash 各 `* -> ask`。
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
comment on column system_setting.config is '七个 section（tool/aiRuntime/environment/network/integrations/storageMedia/advanced）的完整强类型 canonical JSON，必须为 object';
comment on column system_setting.version is '乐观锁行版本：非负，从 0 开始，每次写操作 +1';
comment on column system_setting.created_at is '创建时间（毫秒精度）';
comment on column system_setting.updated_at is '最后更新时间（毫秒精度），应用侧维护';

insert into system_setting (id, config) values (
    1,
     '{"advanced":{"applicationEventHeartbeatIntervalMillis":20000,"applicationEventMaxBytes":2097152,"applicationEventQueueCapacity":512,"applicationEventSendTimeoutMillis":10000,"modelDispatchBusyFallbackDelayMillis":1000,"postgresqlWorkNotificationPollMillis":5000,"postgresqlWorkReconnectBackoffMillis":1000,"processorHeartbeatIntervalMillis":10000,"processorLeaseDurationMillis":30000,"resourceMaxBytes":16777216,"threadResolveFailureDelayMillis":1000,"toolDispatchBusyFallbackDelayMillis":1000,"toolPreflightFailureDelayMillis":1000},"aiRuntime":{"compactionKeepRecentTokens":20000,"retryBackoffStrategy":"EXPONENTIAL","retryBaseDelayMillis":2000,"retryMaxDelayMillis":60000,"retryMaxRetries":3,"subagentMaxConcurrency":10,"subagentMaxDepth":2,"subagentMaxTotalConcurrency":0,"subagentMaxTurns":50},"environment":{"heartbeatTimeoutMillis":60000,"maxResourceBytes":16777216},"integrations":{"comfyui":{"connectTimeoutMillis":10000,"enabled":false,"maxInputFileBytes":52428800,"readTimeoutMillis":30000,"websocketTimeoutMillis":1800000},"gptImage2":{"askTimeoutSeconds":900,"hubExecutionTimeoutMillis":960000,"maxWaitMillis":1200000,"paidEnabled":false},"minimaxH3":{"comfyConnectTimeoutMillis":10000,"comfyMaxWaitMillis":1800000,"comfyPollIntervalMillis":2000,"comfyRequestTimeoutMillis":30000,"enabled":false,"promptMaxWaitMillis":600000},"openCliHub":{"baseUrl":null,"connectTimeoutMillis":5000,"enabled":false,"longPollTimeoutMillis":130000,"maxErrorResponseBytes":4096,"maxJsonResponseBytes":524288,"maxOutputChars":65535,"requestTimeoutMillis":120000,"streamBufferBytes":16384},"seedance":{"enabled":false,"hubExecutionTimeoutMillis":600000,"maxWaitMillis":1800000,"retry":0,"statusPollIntervalMillis":30000}},"network":{"noProxyHosts":"localhost,127.*,::1"},"storageMedia":{"canvasMediaProcessTimeoutMillis":30000,"s3PresignDefaultExpiresSeconds":600,"s3PresignMaxExpiresSeconds":3600,"thumbnailMaxDimension":512,"thumbnailQuality":80,"uploadExpiresSeconds":3600},"tool":{"defaultYolo":false,"modelGatewayBusyRetryMillis":5000,"permission":{"bash":[{"action":"ask","pattern":"*"}],"edit":[{"action":"ask","pattern":"*"}],"write":[{"action":"ask","pattern":"*"}]},"toolGatewayBusyRetryMillis":1000,"toolGatewayOverloadRetryMillis":5000}}'::jsonb
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
-- The block below (the eight tables + their indexes, including comments) is the
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
    parent_thread_id uuid,
    head_entry_id uuid not null,
    creation_request_hash char(64) not null,
    name varchar(256) not null,
    yolo_enabled boolean not null,
    status varchar(16) not null,
    next_command_sequence bigint not null check (next_command_sequence >= 1),
    version bigint not null check (version >= 0),
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_harness_thread_session foreign key (session_id)
        references harness_session (id),
    constraint fk_harness_thread_parent foreign key (parent_thread_id)
        references harness_thread (id),
    constraint fk_harness_thread_head foreign key (session_id, head_entry_id)
        references harness_entry (session_id, id),
    -- 同 Session 复合引用目标：产品表（如 Issue Run）用 (session_id, id) 把
    -- Thread 与 Session 一起冻结，无法配错 Session。
    constraint uk_harness_thread_session unique (session_id, id),
    constraint ck_harness_thread_parent_not_self check (
        parent_thread_id is null or parent_thread_id <> id
    ),
    constraint ck_harness_thread_creation_request_hash check (
        creation_request_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint ck_harness_thread_name check (
        btrim(name) <> ''
    ),
    constraint ck_harness_thread_status check (
        status in ('IDLE', 'ACTIVE', 'WAITING_CHILDREN')
    ),
    constraint ck_harness_thread_time_order check (updated_at >= created_at)
);

comment on table harness_thread is 'Thread：指向 head Entry 的游标状态机，version 随每次对外字段变化精确 +1；session_id 与 creation_request_hash 创建后不可变，head 必须与 session 同 Session；parent_thread_id 记录不可变执行父关系，status 维护递归生命周期（IDLE/ACTIVE/WAITING_CHILDREN）；(session_id, id) 唯一供同 Session 复合引用';
comment on column harness_thread.id is 'Thread 的全局唯一 UUID';
comment on column harness_thread.session_id is '所属 Session（创建后不可变）';
comment on column harness_thread.parent_thread_id is '不可变父 Thread UUID（无父/根 Thread 为 null，禁止指向自身）';
comment on column harness_thread.head_entry_id is '当前 head Entry（必须存在且属于 thread.session_id 的 Session）';
comment on column harness_thread.creation_request_hash is 'NEW_SESSION/NEW_THREAD 初始创建请求指纹：服务端 64 位小写 SHA-256 身份键（创建后不可变，不对产品 DTO 暴露）';
comment on column harness_thread.name is 'Thread 显示名称：应用保证非空、单行且至多 256 个 Unicode 码点，并由应用生成默认名或手动重命名（check 只防御空白串）';
comment on column harness_thread.yolo_enabled is '当前 yolo 模式开关';
comment on column harness_thread.status is '递归生命周期状态（IDLE/ACTIVE/WAITING_CHILDREN）';
comment on column harness_thread.next_command_sequence is '下一条 Command 的 sequence（从 1 递增）';
comment on column harness_thread.version is '并发控制版本：任何对外字段变化必须 +1';
comment on column harness_thread.created_at is 'Thread 创建时间（毫秒精度）';
comment on column harness_thread.updated_at is 'Thread 最后更新时间（毫秒精度），不得早于 created_at';

create index idx_harness_thread_parent
    on harness_thread (parent_thread_id);

create index idx_harness_thread_session
    on harness_thread (session_id, created_at, id);

comment on index idx_harness_thread_parent is 'parent 回溯与子 Thread 树遍历索引';
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
            'GOAL',
            'CUSTOM_MESSAGE',
            'SET_AGENT',
            'SET_MODEL',
            'SET_ENVIRONMENT'
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
    input_receipt jsonb,
    constraint fk_harness_tool_invocation_model foreign key (model_invocation_id)
        references harness_model_invocation (id),
    constraint fk_harness_tool_invocation_assistant foreign key (assistant_entry_id)
        references harness_entry (id),
    constraint uk_harness_tool_invocation_call_index unique (assistant_entry_id, call_index),
    constraint ck_harness_tool_invocation_status check (
        status in (
            'WAITING_APPROVAL',
            'WAITING_INPUT',
            'READY',
            'DISPATCHING',
            'RUNNING',
            'SUCCEEDED',
            'FAILED',
            'CANCELLED',
            'UNKNOWN'
        )
    ),
    -- Human input is not a tool permission approval: WAITING_INPUT freezes a
    -- binding and carries no result/error until the user answers.
    constraint ck_harness_tool_waiting_input check (
        status <> 'WAITING_INPUT'
        or (binding is not null and result is null and error is null)
    ),
    -- Runtime-owned receipt until the ToolResult and its metadata enter Entry
    -- history atomically. Questionnaire answers themselves remain in result.
    constraint ck_harness_tool_input_receipt check (
        input_receipt is null or (
            status = 'SUCCEEDED' and result is not null
            and jsonb_typeof(input_receipt) = 'object'
            and coalesce(jsonb_typeof(input_receipt -> 'submissionId'), '') = 'string'
            and coalesce(btrim(input_receipt ->> 'submissionId'), '') <> ''
            and coalesce(jsonb_typeof(input_receipt -> 'actor'), '') = 'string'
            and coalesce(btrim(input_receipt ->> 'actor'), '') <> ''
            and coalesce(jsonb_typeof(input_receipt -> 'acceptedAt'), '') = 'string'
            and coalesce(btrim(input_receipt ->> 'acceptedAt'), '') <> ''
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

comment on table harness_tool_invocation is 'ToolInvocation：一次 tool 调用的 durable 生命周期记录，按 (assistant_entry_id, call_index) 与 assistant 消息对齐；WAITING_INPUT 冻结 binding 等待人输入，batch apply 后行被物理删除';
comment on column harness_tool_invocation.id is 'ToolInvocation 的全局唯一 UUID';
comment on column harness_tool_invocation.model_invocation_id is '所属 ModelInvocation';
comment on column harness_tool_invocation.assistant_entry_id is '携带对应 ToolCall 的 Assistant MESSAGE Entry';
comment on column harness_tool_invocation.call_index is 'assistant 消息内 tool call 的下标（从 0 递增）';
comment on column harness_tool_invocation.call is '冻结的 ToolCall（JSON object）';
comment on column harness_tool_invocation.binding is '冻结的 tool binding（JSON object，仅在 immediate FAILED attempt=0 槽位可空）';
comment on column harness_tool_invocation.status is '生命周期状态（含 WAITING_APPROVAL 与 WAITING_INPUT）';
comment on column harness_tool_invocation.attempt is '已确认的 start 尝试次数（从 0 递增）';
comment on column harness_tool_invocation.approval is '审批记录（JSON object，可空）';
comment on column harness_tool_invocation.result is 'terminal 成功结果（JSON object，与 error 互斥）';
comment on column harness_tool_invocation.effects is '副作用批（JSON object；非 SUCCEEDED 时必须为空批）';
comment on column harness_tool_invocation.error is 'terminal 失败错误（JSON object，与 result 互斥）';
comment on column harness_tool_invocation.created_at is '创建时间（毫秒精度）';
comment on column harness_tool_invocation.updated_at is '最后更新时间（毫秒精度），不得早于 created_at';
comment on column harness_tool_invocation.input_receipt is '交互回执（JSON object，可空）：仅在 SUCCEEDED 且 result 非空时存在，携带 submissionId/actor/acceptedAt，历史物化前由 runtime 持有';

create index idx_harness_tool_invocation_model_nonterminal
    on harness_tool_invocation (model_invocation_id)
    where status in ('WAITING_APPROVAL', 'WAITING_INPUT', 'READY', 'DISPATCHING', 'RUNNING');

comment on index idx_harness_tool_invocation_model_nonterminal is '未收尾 ToolInvocation 按所属 ModelInvocation 的查找路径（已终态行在 batch apply 后物理删除，不需要覆盖）';

-- Pending input/approval paging uses one stable ordinal, not two status queries.
create index idx_harness_tool_invocation_pending
    on harness_tool_invocation (created_at, id)
    where status in ('WAITING_APPROVAL', 'WAITING_INPUT');

comment on index idx_harness_tool_invocation_pending is '待处理（审批与人工输入）统一按 (created_at, id) 稳定分页，不按状态拆成两次查询';

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

-- Thread Join 契约记录：以 invocation_id 为主键记录原子源 prompt 接受与 join 契约、
-- 子 Thread 执行边界（after_version）、匹配的首个 Idle 版本与结果 head Entry、以及交付给父
-- Thread 的命令序列。无独立状态枚举，无 prompt/report 冗余复制。
create table harness_thread_join (
    invocation_id uuid primary key,
    request_hash char(64) not null,
    parent_thread_id uuid,
    child_thread_id uuid not null,
    source_command_sequence bigint not null check (source_command_sequence > 0),
    after_version bigint not null check (after_version >= 0),
    agent varchar(256) not null,
    max_turns integer,
    reminder_turn bigint not null default 0,
    matched_idle_version bigint,
    result_head_entry_id uuid,
    delivery_command_sequence bigint,
    created_at timestamptz(3) not null,
    updated_at timestamptz(3) not null,
    constraint fk_harness_thread_join_child_thread foreign key (child_thread_id)
        references harness_thread (id),
    constraint fk_harness_thread_join_parent_thread foreign key (parent_thread_id)
        references harness_thread (id),
    constraint fk_harness_thread_join_source_command foreign key (child_thread_id, source_command_sequence)
        references harness_thread_command (thread_id, sequence),
    constraint fk_harness_thread_join_result_head foreign key (result_head_entry_id)
        references harness_entry (id),
    constraint fk_harness_thread_join_delivery_command foreign key (parent_thread_id, delivery_command_sequence)
        references harness_thread_command (thread_id, sequence),
    constraint ck_harness_thread_join_request_hash check (
        request_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint ck_harness_thread_join_parent_not_child check (
        parent_thread_id is null or parent_thread_id <> child_thread_id
    ),
    constraint ck_harness_thread_join_agent check (
        btrim(agent) <> ''
    ),
    constraint ck_harness_thread_join_max_turns check (
        max_turns is null or max_turns > 0
    ),
    constraint ck_harness_thread_join_reminder_turn check (
        reminder_turn >= 0
    ),
    constraint ck_harness_thread_join_receipt_pair check (
        (matched_idle_version is null and result_head_entry_id is null)
        or (matched_idle_version is not null and result_head_entry_id is not null)
    ),
    constraint ck_harness_thread_join_matched_order check (
        matched_idle_version is null or matched_idle_version > after_version
    ),
    constraint ck_harness_thread_join_delivery check (
        delivery_command_sequence is null
        or (delivery_command_sequence > 0 and matched_idle_version is not null and parent_thread_id is not null)
    ),
    constraint ck_harness_thread_join_time_order check (
        updated_at >= created_at
    )
);

comment on table harness_thread_join is 'Thread Join 契约记录：以 invocation_id 为主键记录原子源 prompt 接受与 join 契约、子 Thread 执行边界、匹配的 Idle 版本与结果 head、以及交付给父 Thread 的命令序列；无独立状态枚举，无 prompt/report 冗余复制';
comment on column harness_thread_join.invocation_id is 'Join 的全局唯一 UUID（主键，与发起调用的 Tool/Ticket invocation 对齐）';
comment on column harness_thread_join.request_hash is '创建请求指纹（64 位小写 SHA-256）';
comment on column harness_thread_join.parent_thread_id is '父 Thread UUID（可空，空表示 root one-shot completion ticket，不投递父消息）';
comment on column harness_thread_join.child_thread_id is '目标子 Thread UUID';
comment on column harness_thread_join.source_command_sequence is '子 Thread 接受源 prompt 的 command sequence';
comment on column harness_thread_join.after_version is '源 prompt 接受完成后的 child Thread version';
comment on column harness_thread_join.agent is '本次执行的 Agent 名';
comment on column harness_thread_join.max_turns is '软预算最大 turn 数（可空）';
comment on column harness_thread_join.reminder_turn is '已发出的 max_turns 软提醒轮次计数';
comment on column harness_thread_join.matched_idle_version is '匹配的首个 Idle 时的 child Thread version（与 result_head_entry_id 同空或同非空，matched > after_version）';
comment on column harness_thread_join.result_head_entry_id is '匹配的首个 Idle 时的 child Thread head Entry UUID（与 matched_idle_version 同空或同非空）';
comment on column harness_thread_join.delivery_command_sequence is '向父 Thread 投递结果 CUSTOM_MESSAGE 的 command sequence（非空必须已 matched 且 parent_thread_id 非空）';
comment on column harness_thread_join.created_at is '创建时间（毫秒精度）';
comment on column harness_thread_join.updated_at is '最后更新时间（毫秒精度），不得早于 created_at';

create index idx_harness_thread_join_child_pending
    on harness_thread_join (child_thread_id)
    where matched_idle_version is null;

comment on index idx_harness_thread_join_child_pending is '子 Thread 变为空闲时检索等待匹配的 pending join';

create index idx_harness_thread_join_parent_pending
    on harness_thread_join (parent_thread_id)
    where delivery_command_sequence is null;

comment on index idx_harness_thread_join_parent_pending is '父 Thread 恢复或接受输入时检索待交付给父的 pending join';

------------------------------------------------------------------------------
-- 3. Project / Issue business facts
--
-- Project 只是容器与项目级设置：工作流是一条严格 JSON 配置，而不是关系化的
-- states/transitions/dependencies；它拥有的下级表统一使用 project_ 前缀。Run 冻结
-- 自己的 Issue/state/Session/Thread 坐标，Evidence 是 Issue 自有的 Blob owner
-- edge；Issue+Agent 的 Harness Session 归属由 project_issue_agent_thread 直接
-- 持有，没有独立的归属排他表，也没有归属变更触发器。
------------------------------------------------------------------------------

-- Project 的工作流是一条严格 JSON 配置，不是关系化的 states/transitions/
-- dependencies。charset 正则之外的 state 合法性由应用校验。
create table project (
    id uuid primary key,
    title varchar(256) not null check (btrim(title) <> '' and title = btrim(title)),
    description text not null default '' check (octet_length(description) <= 65536),
    workflow jsonb not null check (
        jsonb_typeof(workflow) = 'object'
        and coalesce(jsonb_typeof(workflow -> 'states'), '') = 'array'
    ),
    yolo_enabled boolean not null default true,
    next_issue_number bigint not null default 1 check (next_issue_number >= 1),
    version bigint not null default 0 check (version >= 0),
    archived_at timestamptz(3),
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp
);

comment on table project is 'Project 核心实体：项目资料、YOLO 策略、工作流 JSON 与 Issue 编号单调分配器';
comment on column project.id is '项目 UUID 主键（应用生成）';
comment on column project.title is '展示标题（非空白且无环绕空白，<= 256 字符）';
comment on column project.description is '自洽目标、约束与验收规范描述（最大 64 KiB）';
comment on column project.workflow is '严格工作流配置（JSON object 且携带 states array）；state 成员关系由应用校验';
comment on column project.yolo_enabled is 'Project 下 Issue 运行的 YOLO 策略：跳过普通工具审批预检，不影响身份校验与资源授权';
comment on column project.next_issue_number is '项目内单调递增 Issue 编号分配器，>= 1';
comment on column project.version is '乐观锁版本号，>= 0';
comment on column project.archived_at is '归档时间戳，为空表示活跃';

create index idx_project_updated on project (updated_at, id);

create table project_issue (
    id uuid primary key,
    project_id uuid not null references project (id) on delete restrict,
    number bigint not null check (number >= 1),
    title varchar(256) not null check (btrim(title) <> ''),
    description text not null default '' check (octet_length(description) <= 1048576),
    state varchar(64) not null check (state ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    blocked_from_state varchar(64) check (
        blocked_from_state is null or blocked_from_state ~ '^[A-Z][A-Z0-9_]{0,63}$'
    ),
    block_reason text,
    pause_reason varchar(16),
    pause_detail text,
    next_run_ordinal bigint not null default 1 check (next_run_ordinal >= 1),
    next_activity_sequence bigint not null default 1 check (next_activity_sequence >= 1),
    version bigint not null default 0 check (version >= 0),
    archived_at timestamptz(3),
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp,
    unique (project_id, number),
    check (
        (state = 'BLOCKED' and blocked_from_state is not null
            and blocked_from_state not in ('BLOCKED', 'DONE')
            and block_reason is not null and btrim(block_reason) <> '')
        or (state <> 'BLOCKED' and blocked_from_state is null and block_reason is null)
    ),
    check (
        (pause_reason is null and pause_detail is null)
        or (pause_reason is not null and pause_reason in ('USER', 'ERROR', 'UNKNOWN')
            and pause_detail is not null and btrim(pause_detail) <> '')
    )
);

comment on table project_issue is 'Issue 核心实体：工作流 state、阻塞恢复点、控制暂停与编号/序号分配器';
comment on column project_issue.id is 'Issue UUID 主键（应用生成）';
comment on column project_issue.project_id is '归属项目 UUID（RESTRICT）';
comment on column project_issue.number is '项目内单调递增编号，>= 1';
comment on column project_issue.state is
    'Workflow-scoped natural code; the DB only checks the charset, membership in project.workflow is application-validated';
comment on column project_issue.blocked_from_state is 'BLOCKED 的恢复目标（不得是 BLOCKED/DONE，也不能在非 BLOCKED 状态保留）';
comment on column project_issue.block_reason is 'BLOCKED 的原因（非空白，且非 BLOCKED 时必须为空）';
comment on column project_issue.pause_reason is '控制暂停原因：USER / ERROR / UNKNOWN（与 pause_detail 成对）';
comment on column project_issue.pause_detail is '控制暂停详情（非空白，且与 pause_reason 成对）';
comment on column project_issue.next_run_ordinal is '下一个 Run ordinal 分配器，>= 1';
comment on column project_issue.next_activity_sequence is '下一个 Activity sequence 分配器，>= 1';
comment on column project_issue.version is '乐观锁行版本，>= 0';
comment on column project_issue.archived_at is '归档时间戳，为空表示活跃';

create index idx_project_issue_board on project_issue (project_id, state, number) where archived_at is null;

-- 稳定的 Issue+Agent Thread 归属：(issue_id, agent_name) -> 一条 Harness Thread。
-- 每个 Agent Thread 由服务受控创建自己的 Harness Session，绝不跨 Agent、Issue 或
-- 产品复用；归属行不保存 session 指针，应用无法重新指向。
create table project_issue_agent_thread (
    issue_id uuid not null,
    agent_name varchar(64) not null,
    thread_id uuid not null,
    created_at timestamptz(3) not null default current_timestamp,
    constraint pk_project_issue_agent_thread primary key (issue_id, agent_name),
    constraint uk_project_issue_agent_thread_thread unique (thread_id),
    constraint uk_project_issue_agent_thread_issue unique (issue_id, thread_id),
    constraint fk_project_issue_agent_thread_issue foreign key (issue_id)
        references project_issue (id) on delete restrict,
    constraint fk_project_issue_agent_thread_agent foreign key (agent_name)
        references agent_definition (name) on delete restrict,
    constraint fk_project_issue_agent_thread_thread foreign key (thread_id)
        references harness_thread (id) on delete restrict
);

comment on table project_issue_agent_thread is
    'Issue+Agent 稳定 Thread 归属：每个 Agent 使用自己的 Harness Session（应用受控创建，不跨 Agent/Issue/产品复用），绑定行不保存 session_id';

-- 每个 Issue 工作阶段一份预算，按 (issue_id,state) 对每个 Agent 的 Run 计数；切换
-- Agent 既不重置也不拆分额度；保留态（INIT/BLOCKED/DONE）没有预算行。
create table project_issue_stage_budget (
    issue_id uuid not null,
    state varchar(64) not null,
    max_runs integer not null,
    budget_after_ordinal bigint not null default 0,
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp,
    constraint pk_project_issue_stage_budget primary key (issue_id, state),
    constraint fk_project_issue_stage_budget_issue foreign key (issue_id)
        references project_issue (id) on delete restrict,
    constraint ck_project_issue_stage_budget_state check (state ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    constraint ck_project_issue_stage_budget_work_stage check (state not in ('INIT', 'BLOCKED', 'DONE')),
    constraint ck_project_issue_stage_budget_max_runs check (max_runs > 0),
    constraint ck_project_issue_stage_budget_after_ordinal check (budget_after_ordinal >= 0)
);

comment on table project_issue_stage_budget is 'Issue 工作阶段额度：每 Issue+state 一份，max_runs 为正，budget_after_ordinal 是重置高水位';
comment on column project_issue_stage_budget.state is '工作阶段 state（保留态 INIT/BLOCKED/DONE 不允许建预算行）';
comment on column project_issue_stage_budget.max_runs is '本阶段允许的 Run 数上限（正整数）';
comment on column project_issue_stage_budget.budget_after_ordinal is
    'Reset high-water mark; consumed = this Issue+state Runs with a greater ordinal, all statuses, never filtered by Agent';

-- 一条 Run 冻结自己的 Issue/state/Session/Thread 坐标。预算外键只用
-- (issue_id,state)，归属外键只用 (issue_id,thread_id)，因此切换 Agent 既不会重写
-- 旧 Run，旧 Run 也不依赖今天工作流选了哪个 Agent。
create table project_issue_run (
    id uuid primary key,
    issue_id uuid not null,
    ordinal bigint not null check (ordinal >= 1),
    state varchar(64) not null check (state ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    session_id uuid not null,
    thread_id uuid not null,
    status varchar(16) not null,
    start_entry_id uuid not null,
    end_entry_id uuid,
    final_answer_entry_id uuid,
    next_state varchar(64),
    observed_activity_sequence bigint not null default 0 check (observed_activity_sequence >= 0),
    remaining_execution_ms bigint not null check (remaining_execution_ms >= 0),
    active_since timestamptz(3),
    error text,
    version bigint not null default 0 check (version >= 0),
    started_at timestamptz(3) not null default current_timestamp,
    ended_at timestamptz(3),
    constraint uk_project_issue_run_issue_ordinal unique (issue_id, ordinal),
    constraint uk_project_issue_run_id_issue unique (id, issue_id),
    constraint fk_project_issue_run_stage_budget foreign key (issue_id, state)
        references project_issue_stage_budget (issue_id, state) on delete restrict,
    constraint fk_project_issue_run_agent_thread foreign key (issue_id, thread_id)
        references project_issue_agent_thread (issue_id, thread_id) on delete restrict,
    constraint fk_project_issue_run_thread_session foreign key (session_id, thread_id)
        references harness_thread (session_id, id) on delete restrict,
    constraint fk_project_issue_run_start_entry foreign key (session_id, start_entry_id)
        references harness_entry (session_id, id) on delete restrict,
    constraint fk_project_issue_run_end_entry foreign key (session_id, end_entry_id)
        references harness_entry (session_id, id) on delete restrict,
    constraint fk_project_issue_run_final_answer foreign key (session_id, final_answer_entry_id)
        references harness_entry (session_id, id) on delete restrict,
    constraint ck_project_issue_run_status check (
        status in ('RUNNING', 'WAITING', 'COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN')
    ),
    constraint ck_project_issue_run_next_state check (
        next_state is null
        or (next_state ~ '^[A-Z][A-Z0-9_]{0,63}$' and next_state <> state and next_state <> 'BLOCKED')
    ),
    constraint ck_project_issue_run_clock check (
        (status = 'RUNNING' and active_since is not null and remaining_execution_ms > 0)
        or (status = 'WAITING' and active_since is null and remaining_execution_ms > 0)
        or (status in ('COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN') and active_since is null)
    ),
    constraint ck_project_issue_run_terminal_shape check (
        (status in ('RUNNING', 'WAITING') and ended_at is null and end_entry_id is null
            and final_answer_entry_id is null and error is null)
        or (status in ('COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN')
            and ended_at is not null and ended_at >= started_at and end_entry_id is not null)
    ),
    constraint ck_project_issue_run_error_reason check (
        status not in ('FAILED', 'UNKNOWN') or (error is not null and btrim(error) <> '')
    ),
    constraint ck_project_issue_run_completed_error check (status <> 'COMPLETED' or error is null)
);

comment on table project_issue_run is 'Issue 运行实体：冻结的 Issue/state/Session/Thread 坐标、剩余执行预算与终态收尾';
comment on column project_issue_run.id is 'Run UUID 主键（应用生成）';
comment on column project_issue_run.issue_id is '归属 Issue UUID';
comment on column project_issue_run.ordinal is 'Issue 内单调运行编号，>= 1';
comment on column project_issue_run.state is '运行时冻结的工作阶段 state（必须已有阶段预算）';
comment on column project_issue_run.session_id is '冻结的 Agent 私有 Harness Session';
comment on column project_issue_run.thread_id is '冻结的 Issue+Agent 工作 Thread（必须已绑定且属于该 Session）';
comment on column project_issue_run.status is '状态：RUNNING, WAITING, COMPLETED, FAILED, CANCELLED, UNKNOWN';
comment on column project_issue_run.start_entry_id is 'Run 起始 Entry（必须属于本 Session）';
comment on column project_issue_run.end_entry_id is '终态冻结的结束 Entry（活跃 Run 必须为空）';
comment on column project_issue_run.final_answer_entry_id is '终态最终答复 Entry（可空，必须属于本 Session）';
comment on column project_issue_run.next_state is
    'Accepted handoff target; only a successful Run close commits it to Issue.state';
comment on column project_issue_run.observed_activity_sequence is
    'Delivery cursor in the Issue activity stream, not an Entry boundary';
comment on column project_issue_run.remaining_execution_ms is '剩余执行预算（非负）；活跃与等待 Run 必须为正';
comment on column project_issue_run.active_since is 'RUNNING 活跃区间起点（WAITING 与终态必须为空）';
comment on column project_issue_run.error is 'FAILED/UNKNOWN 的失败原因（非空白；COMPLETED 必须为空）';
comment on column project_issue_run.version is '乐观锁行版本，>= 0';
comment on column project_issue_run.started_at is 'Run 创建时间（毫秒精度）';
comment on column project_issue_run.ended_at is '终态时间（毫秒精度，不得早于 started_at；活跃 Run 为空）';

create unique index uk_project_issue_run_active on project_issue_run (issue_id)
    where status in ('RUNNING', 'WAITING');
create index idx_project_issue_run_budget on project_issue_run (issue_id, state, ordinal);
create index idx_project_issue_run_session on project_issue_run (session_id, thread_id);

-- 一条有序、幂等的 Issue 时间线：RUN 只引用自己的 Run 而不复制正文；文本类
-- 携带 body；事件类只携带 typed data。
create table project_issue_activity (
    issue_id uuid not null,
    sequence bigint not null check (sequence >= 1),
    kind varchar(24) not null,
    actor_type varchar(16) not null check (actor_type in ('HUMAN', 'AGENT', 'SYSTEM')),
    actor_agent_name varchar(64),
    run_id uuid,
    body text,
    data jsonb not null default '{}'::jsonb check (jsonb_typeof(data) = 'object'),
    idempotency_key varchar(128) not null check (btrim(idempotency_key) <> ''),
    request_hash char(64) not null check (request_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz(3) not null default current_timestamp,
    constraint pk_project_issue_activity primary key (issue_id, sequence),
    constraint uk_project_issue_activity_request unique (issue_id, idempotency_key),
    constraint fk_project_issue_activity_issue foreign key (issue_id)
        references project_issue (id) on delete restrict,
    constraint fk_project_issue_activity_agent foreign key (actor_agent_name)
        references agent_definition (name) on delete restrict,
    constraint fk_project_issue_activity_run foreign key (run_id, issue_id)
        references project_issue_run (id, issue_id) on delete restrict,
    check (
        (actor_type = 'AGENT' and actor_agent_name is not null)
        or (actor_type in ('HUMAN', 'SYSTEM') and actor_agent_name is null)
    ),
    check (kind in ('COMMENT', 'RUN', 'INSTRUCTION', 'SPEC_CHANGE', 'STATE_CHANGE', 'CONTROL')),
    check (
        (kind = 'RUN' and run_id is not null and actor_type = 'SYSTEM'
            and body is null and data = '{}'::jsonb)
        or (kind = 'COMMENT' and body is not null and btrim(body) <> '' and data = '{}'::jsonb)
        or (kind = 'INSTRUCTION' and run_id is not null
            and body is not null and btrim(body) <> '' and data = '{}'::jsonb)
        or (kind in ('SPEC_CHANGE', 'STATE_CHANGE', 'CONTROL') and body is null)
    ),
    check (body is null or octet_length(body) <= 1048576)
);

comment on table project_issue_activity is 'Issue 有序幂等事实流：评论、Run 呈现、指示、规格/状态变更与控制事件';
comment on column project_issue_activity.issue_id is '关联 Issue UUID（复合主键）';
comment on column project_issue_activity.sequence is 'Issue 内单调递增序号，>= 1，同时是投递位置';
comment on column project_issue_activity.kind is '类型：COMMENT, RUN, INSTRUCTION, SPEC_CHANGE, STATE_CHANGE, CONTROL';
comment on column project_issue_activity.actor_type is '操作者类型：HUMAN, AGENT, SYSTEM';
comment on column project_issue_activity.actor_agent_name is '操作者 Agent 身份（AGENT 必填，HUMAN/SYSTEM 必须为空）';
comment on column project_issue_activity.run_id is '相关 Run（必须属于同一 Issue；RUN/INSTRUCTION 必填）';
comment on column project_issue_activity.body is '文本正文（最大 1 MiB；事件类必须为空）';
comment on column project_issue_activity.data is '事件 typed data（JSON object；文本类必须为空对象）';
comment on column project_issue_activity.idempotency_key is '同 Issue 幂等键（非空白），重放同一动作不产生新记录';
comment on column project_issue_activity.request_hash is '请求正文的 SHA-256（64 位小写十六进制）';

create unique index uk_project_activity_run on project_issue_activity (issue_id, run_id)
    where kind = 'RUN';
create index idx_project_activity_run on project_issue_activity (run_id) where run_id is not null;

-- 每个 Issue 至多一行的调度邮箱：确定性 wake/lease 围栏，是 Issue 恢复的唯一
-- durable 入口。
create table project_issue_work (
    issue_id uuid,
    wake_version bigint not null check (wake_version > 0),
    due_at timestamptz(3) not null,
    lease_token varchar(128),
    lease_until timestamptz(3),
    created_at timestamptz(3) not null default current_timestamp,
    updated_at timestamptz(3) not null default current_timestamp,
    constraint pk_project_issue_work primary key (issue_id),
    constraint fk_project_issue_work_issue foreign key (issue_id)
        references project_issue (id) on delete restrict,
    constraint ck_project_issue_work_lease_pair check ((lease_token is null) = (lease_until is null)),
    constraint ck_project_issue_work_lease_token check (lease_token is null or btrim(lease_token) <> '')
);

comment on table project_issue_work is 'Issue 调度工作：每 Issue 最多单行，确定性 lease/wake 围栏';
comment on column project_issue_work.issue_id is '所属 Issue UUID（主键）';
comment on column project_issue_work.wake_version is '唤醒版本号，每次请求唤醒递增，> 0';
comment on column project_issue_work.due_at is '下次可调度时间戳';
comment on column project_issue_work.lease_token is '当前持有节点租约令牌（与 lease_until 成对）';
comment on column project_issue_work.lease_until is '租约截止时间戳';

create index idx_project_issue_work_due on project_issue_work (due_at, issue_id);

-- Issue work due notification hint（提交后的回读提示，不是事件日志）。
create or replace function notify_project_issue_work_due()
returns trigger as $$
begin
    if new.due_at <= clock_timestamp() and (new.lease_until is null or new.lease_until <= clock_timestamp()) then
        perform pg_notify('project_issue_work_due', new.issue_id::text);
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_project_issue_work_due
    after insert or update on project_issue_work
    for each row execute function notify_project_issue_work_due();
------------------------------------------------------------------------------
-- 4. Chat Session association
--
-- Chat 通过直接关联表持有多个 Session：Chat 创建复用 Harness NEW_SESSION 的
-- request-hash 重放，因此不需要产品级幂等列；主键保证一个 Session 至多属于一个
-- Chat，删除任一侧前必须先删除本行。
------------------------------------------------------------------------------

create table chat_session (
    session_id uuid,
    chat_id uuid not null,
    created_at timestamptz(3) not null default current_timestamp,
    constraint pk_chat_session primary key (session_id),
    constraint fk_chat_session_session foreign key (session_id)
        references harness_session (id) on delete restrict,
    constraint fk_chat_session_chat foreign key (chat_id)
        references chat (id) on delete restrict
);

comment on table chat_session is 'Chat 与 Harness Session 的直接关联：一个 Session 至多属于一个 Chat，删除任一侧前必须先删本行';
comment on column chat_session.session_id is '该 Chat 持有的 Harness Session（主键，RESTRICT FK）';
comment on column chat_session.chat_id is '所属 Chat（RESTRICT FK）';
comment on column chat_session.created_at is '关联建立时间（毫秒精度）';

create index idx_chat_session_chat on chat_session (chat_id, created_at, session_id);

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
--
-- storage_object_cleanup is the durable tombstone for object keys that must
-- never be re-bound to a live row. A key is enqueued in the same transaction
-- that removes (or claims for removal) its last database fact, so a late PUT
-- or server-side COPY that lands after the fact is gone is still deleted by
-- the background sweep. Records are never deleted by time: each sweep claims
-- the next due batch, pushes next_attempt_at far into the future before doing
-- any object I/O, and moves a failed key back to a short retry deadline.
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

create table storage_object_cleanup (
    key              varchar(512)   not null,
    next_attempt_at  timestamptz(3) not null,
    constraint pk_storage_object_cleanup primary key (key),
    constraint ck_storage_object_cleanup_key_nonblank check (btrim(key) <> '')
);

create index idx_storage_object_cleanup_due
    on storage_object_cleanup (next_attempt_at, key);

comment on table storage_object_cleanup is
    'Durable object-key tombstone: a key whose last database fact was removed is'
    ' deleted again whenever a late PUT/COPY recreates it. Records are permanent'
    ' (never removed by TTL); each sweep claims the next due batch, advances'
    ' next_attempt_at before doing object I/O and backs off failures.';

comment on column storage_object_cleanup.key is
    'Object storage key (uploads/{id}/original, blobs/{id}/original or'
    ' blobs/{id}/preview.webp); primary key, so enqueueing is idempotent.';
comment on column storage_object_cleanup.next_attempt_at is
    'Next sweep deadline: driven by database time so every node claims with the'
    ' same clock; advanced far on claim and set back on object deletion failure.';

comment on index idx_storage_object_cleanup_due is
    'Storage Maintenance tombstone scan: due keys in next_attempt_at order.';

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
-- 7. Issue published evidence
--
-- 一条记录是 Issue 显式持有的一个已发布 Blob 引用：只有执行者最终答复明确引用且来源 Run
-- 的 Session 在提交时确实持有该引用的产物，以及人经 Issue 入口上传的附件，才成为公开
-- 证据；其余附件保持私有。主键即“同 Issue 同 Blob 至多一条”的幂等键，重复发布不新增行
-- 也不重复 retain。
--
-- 本表是 Issue 自有的 owner edge，与 Session 无关：删除 Session 不释放这里的引用，只有
-- Issue/Project 深删除才逐行 release。三个 FK 都是 RESTRICT，行必须早于 project_issue_run
-- 删除；ref_count 变更完全由应用事务完成，不依赖 cascade 或触发器。
------------------------------------------------------------------------------
create table project_issue_evidence (
    issue_id uuid not null,
    blob_id uuid not null,
    actor_agent_name varchar(64),
    run_id uuid,
    name varchar(512) not null check (btrim(name) <> '' and name = btrim(name)),
    created_at timestamptz(3) not null default current_timestamp,
    constraint pk_project_issue_evidence primary key (issue_id, blob_id),
    constraint fk_project_issue_evidence_issue foreign key (issue_id)
        references project_issue (id) on delete restrict,
    constraint fk_project_issue_evidence_blob foreign key (blob_id)
        references storage_blob (id) on delete restrict,
    constraint fk_project_issue_evidence_agent foreign key (actor_agent_name)
        references agent_definition (name) on delete restrict,
    constraint fk_project_issue_evidence_run foreign key (run_id, issue_id)
        references project_issue_run (id, issue_id) on delete restrict,
    constraint ck_project_issue_evidence_author check (run_id is null or actor_agent_name is not null)
);

create index idx_project_issue_evidence_blob
    on project_issue_evidence (blob_id);

comment on table project_issue_evidence is
    'Explicit published Blob references independent of Session lifetime; application retains/releases once';
comment on column project_issue_evidence.issue_id is '所属 Issue UUID（复合主键）';
comment on column project_issue_evidence.blob_id is '已发布 Blob UUID（复合主键，RESTRICT FK，ACTIVE 行）';
comment on column project_issue_evidence.actor_agent_name is '发布者 Agent 身份（作者归属，RESTRICT FK，可空）';
comment on column project_issue_evidence.run_id is
    '来源 Run（同 Issue 复合 FK，可空）；非空时必须同时给出 actor_agent_name';
comment on column project_issue_evidence.name is
    '权威展示名（varchar(512)，非空且无首尾空白）：人工上传取自上传行，执行者发布由服务端从规范 URI 派生，不接受客户端可伪造文件名';
comment on column project_issue_evidence.created_at is '发布时间戳（毫秒精度）';

comment on index idx_project_issue_evidence_blob is '按 blob 反向枚举引用它的 Issue（对账与深删除）';

------------------------------------------------------------------------------
-- 8. Canvas resource blob FK (must follow the global blob storage section)
------------------------------------------------------------------------------

alter table canvas_resource
    add constraint fk_canvas_resource_blob foreign key (blob_id)
    references storage_blob (id) on delete restrict;
