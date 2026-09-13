-- Platform 独占的 Skill 来源配置、持久 inventory 与跨节点管理操作。
--
-- 本迁移把 Skill 事实的所有权从“Daemon READY 的瞬时上报”推进为 Platform 的持久权威状态：
--
--   environment_inventory      每个 Environment 恰一行：期望的来源集合版本与最近一次被围栏接受的 READY 报告
--   environment_skill_source   每个来源恰一行：Platform 唯一的来源配置，version 同时是 CAS 令牌与 Daemon sourceVersion
--   environment_skill          每个来源最新一次成功扫描的持久 inventory；Skill 正文永不入库
--   environment_operation      跨节点管理信箱与不可变历史；resource_id 故意不建 FK
--
-- 全部持久 id 由应用生成（uuid 主键，无序列、无触发器、无函数）。所有 guard 在任何 mutation 之前执行，
-- 且本迁移在单个事务内运行，因此任一 guard 失败都不会留下半迁移形状。

-- -----------------------------------------------------------------------------
-- 0. fail-fast guards
-- -----------------------------------------------------------------------------

do $$
begin
    if exists (
        select 1
        from harness_model_invocation
        where status in ('READY', 'DISPATCHING', 'RUNNING')
    ) then
        raise exception 'cannot install skill source schema while model invocations are active';
    end if;

    if exists (
        select 1
        from harness_tool_invocation
        where status in ('WAITING_APPROVAL', 'READY', 'DISPATCHING', 'RUNNING')
    ) then
        raise exception 'cannot install skill source schema while tool invocations are active';
    end if;
end
$$;

-- Agent definition 的 legacy skills 是短名列表，无法可靠映射到新的 (sourceId, name) 身份，
-- 因此非空即拒绝而不是猜测；malformed config 说明数据已经损坏，同样不能迁移。
do $$
begin
    if exists (
        select 1
        from agent_definition
        where jsonb_typeof(config) is distinct from 'object'
           or jsonb_typeof(config -> 'skills') is distinct from 'array'
    ) then
        raise exception 'cannot migrate malformed agent definition skill config';
    end if;

    if exists (
        select 1
        from agent_definition
        where config -> 'skills' <> '[]'::jsonb
    ) then
        raise exception 'cannot migrate non-empty legacy agent definition skills';
    end if;
end
$$;

-- 冻结的 skillBindings 携带旧身份，重写它们等于伪造历史事实；非空即拒绝。
do $$
begin
    if exists (
        select 1
        from harness_model_invocation
        where jsonb_typeof(request_spec -> 'skillBindings') is distinct from 'array'
    ) then
        raise exception 'cannot migrate malformed model request skill bindings';
    end if;

    if exists (
        select 1
        from harness_model_invocation
        where request_spec -> 'skillBindings' <> '[]'::jsonb
    ) then
        raise exception 'cannot migrate frozen model skill bindings';
    end if;
end
$$;

-- -----------------------------------------------------------------------------
-- 1. environment_inventory
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
-- 2. environment_skill_source
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
-- 3. environment_skill
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
-- 4. environment_operation
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

-- -----------------------------------------------------------------------------
-- 5. 确定性回填
--
-- 每个既有 Environment 得到一行 inventory 与一个缺省 PATH 来源（~/.agents/skills）。
-- id 由 environment id 与固定后缀经 md5 派生，因此重放同一迁移得到同一标识，不依赖扩展、
-- 随机数或序列。
-- -----------------------------------------------------------------------------

insert into environment_inventory (environment_id, source_set_version)
select id, 0
from environment;

insert into environment_skill_source (
    source_id, environment_id, source_type, path, default_source, version, status
)
select
    md5(id::text || ':default-skill-source')::uuid,
    id,
    'path',
    '~/.agents/skills',
    true,
    0,
    'UNAPPLIED'
from environment;
