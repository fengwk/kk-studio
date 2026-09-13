-- Platform MCP Server 配置重构与 Local MCP 支持。
--
-- 本迁移将 mcp_server 从旧的扁平 url / bearer_token 收敛为规范化 JSON 连接表示：
--   connection_type      REMOTE | LOCAL
--   environment_id       LOCAL 必填且有 FK restrict 保护；REMOTE 必须为 null
--   connection_config    jsonb 仅含传输参数（Remote: url/headers；Local: command/cwd/env）
--   enabled              公共启用开关，默认 true
--   timeout_millis       已有正整数毫秒超时
--   discovery_status     UNVERIFIED | AVAILABLE | FAILED
--   discovered_version   已成功发现的配置代际（nullable，<= version）
--   version              已有 CAS 行版本
--
-- mcp_tool 新增列：
--   schema_revision      模式代际（非负，从 0 开始，定义改变或下线重现时递增）
--   available            可用性标志，默认 true，远端消失时 tombstone 为 false 保留稳定 UUID

-- 1. 为 mcp_server 添加新列
alter table mcp_server
    add column connection_type varchar(16),
    add column environment_id uuid,
    add column connection_config jsonb,
    add column enabled boolean not null default true,
    add column discovery_status varchar(16),
    add column discovered_version bigint;

-- 2. 迁移既有 Remote 行至规范化表示（同步发现语义迁移为 AVAILABLE）
update mcp_server
set connection_type = 'REMOTE',
    environment_id = null,
    connection_config = case
        when bearer_token is not null and char_length(btrim(bearer_token)) > 0 then
            jsonb_build_object('url', url, 'headers', jsonb_build_object('Authorization', 'Bearer ' || bearer_token))
        else
            jsonb_build_object('url', url, 'headers', jsonb_build_object())
    end,
    enabled = true,
    discovery_status = 'AVAILABLE',
    discovered_version = version;

-- 3. 设置非空约束与默认值
alter table mcp_server
    alter column connection_type set not null,
    alter column connection_config set not null,
    alter column discovery_status set not null,
    alter column discovery_status set default 'UNVERIFIED';

-- 4. 移除旧列（url 约束随之删除）
alter table mcp_server
    drop column bearer_token,
    drop column url;

-- 5. 添加约束与索引
alter table mcp_server
    add constraint fk_mcp_server_environment foreign key (environment_id)
        references environment (id) on delete restrict,
    add constraint ck_mcp_server_connection_type check (
        connection_type in ('REMOTE', 'LOCAL')
    ),
    add constraint ck_mcp_server_local_environment check (
        connection_type not in ('REMOTE', 'LOCAL')
        or (connection_type = 'LOCAL' and environment_id is not null)
        or (connection_type = 'REMOTE' and environment_id is null)
    ),
    add constraint ck_mcp_server_discovery_status check (
        discovery_status in ('UNVERIFIED', 'AVAILABLE', 'FAILED')
    ),
    add constraint ck_mcp_server_discovered_version check (
        discovered_version is null
        or (discovered_version >= 0 and discovered_version <= version)
    ),
    add constraint ck_mcp_server_connection_config_object check (
        jsonb_typeof(connection_config) = 'object'
    ),
    add constraint ck_mcp_server_connection_config_shape check (
        connection_type not in ('REMOTE', 'LOCAL')
        or
        (
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
    );

create index idx_mcp_server_environment
    on mcp_server (environment_id)
    where environment_id is not null;

-- 6. 为 mcp_tool 添加 schema_revision 与 available 列
alter table mcp_tool
    add column schema_revision bigint not null default 0,
    add column available boolean not null default true;

alter table mcp_tool
    add constraint ck_mcp_tool_schema_revision_nonneg check (
        schema_revision >= 0
    );

create index idx_mcp_tool_server_available
    on mcp_tool (mcp_server_id, available);

-- 7. 注释说明
comment on column mcp_server.connection_type is '连接类型：REMOTE（Backend 直连 Streamable HTTP）或 LOCAL（Daemon stdio）';
comment on column mcp_server.environment_id is '关联 Environment UUID：LOCAL 必填且受 restrict 保护；REMOTE 为 null';
comment on column mcp_server.connection_config is '仅含传输参数的 JSON：Remote 含 url/headers，Local 含 command/cwd/env';
comment on column mcp_server.enabled is '公共启用开关：默认 true；false 时即使 AVAILABLE 也不可被 Agent 选择';
comment on column mcp_server.discovery_status is '发现状态：UNVERIFIED（未验证）、AVAILABLE（可用）、FAILED（失败）';
comment on column mcp_server.discovered_version is '最近一次成功验证的配置版本：<= version，未成功或变更后为 null';
comment on column mcp_tool.schema_revision is '模式修订版本：非负，从 0 开始；schema/description 变更或下线重现时递增';
comment on column mcp_tool.available is '是否可用：true 表示在当前发现结果中；false 表示远端已消失被标记为 tombstone，保留稳定 UUID';
