# 存储模型

本文是整体技术方案的一部分，描述 control plane 侧的核心存储对象、表结构、索引约束与持久化模型。

## 目标

存储模型支撑：

- agent profile 管理。
- 本地环境注册表与工具能力注册。
- session / event 持久化。
- run / lease / checkpoint 管理。
- remote tool call 状态跟踪。

session / event / run 由云端管理，本地 daemon 不维护 canonical store。

## 命名规则

- 表名统一使用 `agent_*` 前缀。
- 物理主键统一为 `id bigint unsigned`，对应 `ConventionDO<Long>`。
- 业务标识使用 `{domain}_id` 字段，例如 `profile_id`、`env_id`、`session_id`、`event_id`、`run_id`。
- 时间字段使用 `datetime(3)`。
- 通用审计字段为 `gmt_create`、`gmt_modified`、`version`。
- JSON 内容使用文本列保存，Java DO 中使用 `String` 字段承载。
- 持久化枚举值使用 lower snake case 字符串。

## 数据库资源组织

数据库资源维护在 `core/src/main/resources` 下：

```text
core/src/main/resources
├── auto-mapper.config
├── schema-mysql.sql
├── schema-h2.sql
├── data-h2.sql
└── fun/fengwk/kkstudio/core/agent/**/repo/impl/mapper/*Mapper.xml
```

各文件职责：

| 文件 | 职责 |
| --- | --- |
| `schema-mysql.sql` | MySQL 生产与集成环境建表脚本 |
| `schema-h2.sql` | H2 测试与本地轻量运行建表脚本 |
| `data-h2.sql` | H2 默认 seed 数据 |
| `auto-mapper.config` | AutoMapper 模块级生成配置 |
| `*Mapper.xml` | AutoMapper 无法覆盖或需要手写优化的 SQL |

H2 资源规则：

- `schema-h2.sql` 使用 H2 兼容类型，例如 `timestamp(3)`、`boolean`、`text`。
- H2 索引使用单独的 `create index if not exists ...` 语句。
- `data-h2.sql` 使用 `merge into ... key (...) values ...` 编写可重复执行的 seed。
- seed 只放默认 workspace、默认 profile、默认环境占位、默认模型或测试所需最小数据。

MySQL 资源规则：

- `schema-mysql.sql` 使用 `engine=InnoDB default charset=utf8mb4`。
- 文本 JSON 字段使用 `longtext`。
- 软删除表使用 `deleted tinyint(1)`，并将 `deleted` 纳入常用查询索引。
- append-only event 表不使用软删除，依靠 session 层状态表达归档。

通用字段：

```sql
id             bigint unsigned not null auto_increment comment '主键',
gmt_create     datetime(3) not null default current_timestamp(3) comment '创建时间',
gmt_modified   datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
version        bigint not null default '0' comment '数据版本号'
```

## 表与模型对应关系

| 表 | DO | service model | 说明 |
| --- | --- | --- | --- |
| `agent_profile` | `AgentProfileDO` | `AgentProfile` | agent 配置主体 |
| `agent_profile_tool_binding` | `AgentProfileToolBindingDO` | `AgentProfileToolBinding` | profile 可见工具绑定 |
| `agent_profile_runtime_policy` | `AgentRuntimePolicyDO` | `AgentRuntimePolicy` | profile 运行策略 |
| `agent_env` | `AgentEnvDO` | `AgentEnv` | 统一环境注册表 |
| `agent_runtime` | `AgentRuntimeDO` | `AgentRuntime` | 环境 runtime 快照 |
| `agent_tool_capability` | `AgentToolCapabilityDO` | `AgentToolCapability` | 环境工具能力快照 |
| `agent_session` | `AgentSessionDO` | `AgentSession` | session 元数据 |
| `agent_session_event` | `AgentSessionEventDO` | `AgentSessionEvent` | append-only event tree |
| `agent_session_head` | `AgentSessionHeadDO` | `AgentSessionHead` | session 逻辑 head |
| `agent_run` | `AgentRunDO` | `AgentRun` | 云端 agent run |
| `agent_run_lease` | `AgentRunLeaseDO` | `AgentRunLease` | run 执行租约 |
| `agent_run_checkpoint` | `AgentRunCheckpointDO` | `AgentRunCheckpoint` | run 恢复指针 |
| `agent_tool_call` | `AgentToolCallDO` | `AgentToolCall` | remote tool 调用状态 |

## agent profile 表

### `agent_profile`

保存云端自研 agent 的系统提示、默认模型与配置。

```sql
create table if not exists agent_profile (
    id                 bigint unsigned not null auto_increment comment '主键',
    profile_id         varchar(64) not null comment 'agent profile业务id',
    name               varchar(128) not null comment 'profile名称',
    description        varchar(512) null comment 'profile描述',
    system_prompt      longtext null comment '系统提示词',
    default_provider   varchar(64) null comment '默认provider',
    default_model      varchar(128) null comment '默认模型',
    enabled            tinyint(1) not null default 1 comment '是否启用',
    config_json        longtext null comment '扩展配置JSON',
    gmt_create         datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified       datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version            bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_profile_profile_id (profile_id),
    key idx_agent_profile_enabled (enabled)
) engine=InnoDB default charset=utf8mb4 comment='agent profile';
```

### `agent_profile_tool_binding`

保存 agent profile 与特定环境工具能力之间的绑定关系。agent 只看到绑定后的工具列表。

```sql
create table if not exists agent_profile_tool_binding (
    id                   bigint unsigned not null auto_increment comment '主键',
    binding_id           varchar(64) not null comment '绑定业务id',
    profile_id           varchar(64) not null comment 'agent profile业务id',
    env_id               varchar(64) not null comment '环境业务id',
    tool_name            varchar(128) not null comment '环境上报的工具名称',
    tool_alias           varchar(128) null comment '暴露给agent的工具别名',
    enabled              tinyint(1) not null default 1 comment '是否启用',
    binding_config_json  longtext null comment '绑定扩展配置JSON',
    gmt_create           datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified         datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version              bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_profile_tool_binding_id (binding_id),
    unique key uk_agent_profile_env_tool (profile_id, env_id, tool_name),
    key idx_agent_profile_tool_profile (profile_id),
    key idx_agent_profile_tool_env (env_id)
) engine=InnoDB default charset=utf8mb4 comment='agent profile工具绑定';
```

### `agent_profile_runtime_policy`

保存 profile 运行策略。

```sql
create table if not exists agent_profile_runtime_policy (
    id                       bigint unsigned not null auto_increment comment '主键',
    policy_id                varchar(64) not null comment '策略业务id',
    profile_id               varchar(64) not null comment 'agent profile业务id',
    max_rounds               int not null default 32 comment '最大主循环轮数',
    max_consecutive_errors   int not null default 3 comment '最大连续错误次数',
    tool_timeout_millis      bigint not null default 0 comment '工具默认超时时间毫秒，0表示不限制',
    run_timeout_millis       bigint not null default 0 comment 'run默认超时时间毫秒，0表示不限制',
    retry_policy_json        longtext null comment '重试策略JSON',
    gmt_create               datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified             datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version                  bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_runtime_policy_id (policy_id),
    unique key uk_agent_runtime_policy_profile (profile_id)
) engine=InnoDB default charset=utf8mb4 comment='agent profile运行策略';
```

## 环境注册表

### `agent_env`

保存统一环境注册表中的环境信息。

```sql
create table if not exists agent_env (
    id                       bigint unsigned not null auto_increment comment '主键',
    env_id                   varchar(64) not null comment '环境业务id',
    env_name                 varchar(128) null comment '环境名称',
    device_name              varchar(128) null comment '设备名称',
    os_name                  varchar(64) null comment '操作系统',
    arch                     varchar(64) null comment 'CPU架构',
    workspace_roots_json     longtext null comment 'workspace root列表JSON',
    daemon_version           varchar(64) null comment 'daemon版本',
    status                   varchar(32) not null comment '环境状态',
    last_seen_at             datetime(3) null comment '最近心跳时间',
    capability_revision      bigint not null default 0 comment '能力快照版本',
    metadata_json            longtext null comment '扩展元信息JSON',
    gmt_create               datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified             datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version                  bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_env_env_id (env_id),
    key idx_agent_env_status_seen (status, last_seen_at)
) engine=InnoDB default charset=utf8mb4 comment='agent环境注册表';
```

### `agent_runtime`

保存环境上报的 runtime 快照。

```sql
create table if not exists agent_runtime (
    id                  bigint unsigned not null auto_increment comment '主键',
    runtime_id          varchar(64) not null comment 'runtime业务id',
    env_id              varchar(64) not null comment '环境业务id',
    runtime_type        varchar(64) not null comment 'runtime类型',
    runtime_name        varchar(128) not null comment 'runtime名称',
    runtime_version     varchar(128) null comment 'runtime版本',
    status              varchar(32) not null comment 'runtime状态',
    metadata_json       longtext null comment '扩展元信息JSON',
    gmt_create          datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified        datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version             bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_runtime_id (runtime_id),
    unique key uk_agent_runtime_env_type_name (env_id, runtime_type, runtime_name),
    key idx_agent_runtime_env (env_id)
) engine=InnoDB default charset=utf8mb4 comment='agent环境runtime';
```

### `agent_tool_capability`

保存环境工具 capability 快照。

```sql
create table if not exists agent_tool_capability (
    id                  bigint unsigned not null auto_increment comment '主键',
    capability_id       varchar(64) not null comment '工具能力业务id',
    env_id              varchar(64) not null comment '环境业务id',
    tool_name           varchar(128) not null comment '工具名称',
    display_name        varchar(128) null comment '展示名称',
    description         varchar(1024) null comment '工具描述',
    source_type         varchar(32) not null comment '工具来源类型',
    schema_json         longtext not null comment '工具参数schema JSON',
    enabled             tinyint(1) not null default 1 comment '是否启用',
    revision            bigint not null default 0 comment '工具能力版本',
    metadata_json       longtext null comment '扩展元信息JSON',
    last_seen_at        datetime(3) null comment '最近上报时间',
    gmt_create          datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified        datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version             bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_tool_capability_id (capability_id),
    unique key uk_agent_tool_capability_env_tool (env_id, tool_name),
    key idx_agent_tool_capability_env_enabled (env_id, enabled)
) engine=InnoDB default charset=utf8mb4 comment='agent环境工具能力';
```

## session / event 表

### `agent_session`

保存 session 元数据和默认继续点。

```sql
create table if not exists agent_session (
    id                     bigint unsigned not null auto_increment comment '主键',
    session_id             varchar(64) not null comment 'session业务id',
    profile_id             varchar(64) not null comment 'agent profile业务id',
    title                  varchar(256) null comment 'session标题',
    status                 varchar(32) not null comment 'session状态',
    current_head_event_id  varchar(64) not null comment '当前默认head event id',
    metadata_json          longtext null comment '扩展元信息JSON',
    gmt_create             datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified           datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version                bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_session_id (session_id),
    key idx_agent_session_profile_status (profile_id, status)
) engine=InnoDB default charset=utf8mb4 comment='agent session';
```

### `agent_session_event`

保存 append-only session event tree。`parent_event_id` 使用 `root` 表示根父节点。

```sql
create table if not exists agent_session_event (
    id                 bigint unsigned not null auto_increment comment '主键',
    event_id           varchar(64) not null comment 'event业务id',
    session_id         varchar(64) not null comment 'session业务id',
    parent_event_id    varchar(64) not null comment '父event业务id',
    run_id             varchar(64) null comment '关联run业务id',
    event_type         varchar(64) not null comment 'event类型',
    payload_type       varchar(128) not null comment 'payload类型',
    payload_json       longtext not null comment 'payload JSON',
    gmt_create         datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified       datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version            bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_session_event_id (event_id),
    key idx_agent_session_event_session_parent (session_id, parent_event_id),
    key idx_agent_session_event_session_create (session_id, gmt_create, id),
    key idx_agent_session_event_run (run_id)
) engine=InnoDB default charset=utf8mb4 comment='agent session event';
```

### `agent_session_head`

保存 session 的逻辑 head。默认 head 使用 `head_name = 'default'`。

```sql
create table if not exists agent_session_head (
    id              bigint unsigned not null auto_increment comment '主键',
    head_id         varchar(64) not null comment 'head业务id',
    session_id      varchar(64) not null comment 'session业务id',
    head_name       varchar(64) not null comment 'head名称',
    head_event_id   varchar(64) not null comment 'head event业务id',
    gmt_create      datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified    datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version         bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_session_head_id (head_id),
    unique key uk_agent_session_head_name (session_id, head_name),
    key idx_agent_session_head_event (head_event_id)
) engine=InnoDB default charset=utf8mb4 comment='agent session head';
```

## run 表

### `agent_run`

保存云端 agent run。

```sql
create table if not exists agent_run (
    id                    bigint unsigned not null auto_increment comment '主键',
    run_id                varchar(64) not null comment 'run业务id',
    session_id            varchar(64) not null comment 'session业务id',
    profile_id            varchar(64) not null comment 'agent profile业务id',
    base_event_id         varchar(64) not null comment 'run启动基准event id',
    head_event_id         varchar(64) not null comment 'run当前head event id',
    status                varchar(32) not null comment 'run状态',
    trigger_type          varchar(32) not null comment '触发类型',
    abort_requested       tinyint(1) not null default 0 comment '是否请求中断',
    started_at            datetime(3) null comment '开始时间',
    ended_at              datetime(3) null comment '结束时间',
    error_code            varchar(128) null comment '错误码',
    error_message         varchar(2048) null comment '错误信息',
    gmt_create            datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified          datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version               bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_run_id (run_id),
    key idx_agent_run_session_status (session_id, status),
    key idx_agent_run_profile_status (profile_id, status)
) engine=InnoDB default charset=utf8mb4 comment='agent run';
```

### `agent_run_lease`

保存 run 的有效执行租约。

```sql
create table if not exists agent_run_lease (
    id              bigint unsigned not null auto_increment comment '主键',
    lease_id        varchar(64) not null comment 'lease业务id',
    run_id          varchar(64) not null comment 'run业务id',
    owner_id        varchar(128) not null comment '执行者标识',
    lease_token     varchar(128) not null comment '租约token',
    status          varchar(32) not null comment '租约状态',
    expires_at      datetime(3) not null comment '过期时间',
    last_renew_at   datetime(3) null comment '最近续约时间',
    gmt_create      datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified    datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version         bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_run_lease_id (lease_id),
    unique key uk_agent_run_lease_run (run_id),
    key idx_agent_run_lease_expires (status, expires_at)
) engine=InnoDB default charset=utf8mb4 comment='agent run lease';
```

### `agent_run_checkpoint`

保存 run 恢复指针。

```sql
create table if not exists agent_run_checkpoint (
    id                    bigint unsigned not null auto_increment comment '主键',
    checkpoint_id         varchar(64) not null comment 'checkpoint业务id',
    run_id                varchar(64) not null comment 'run业务id',
    checkpoint_type       varchar(64) not null comment 'checkpoint类型',
    checkpoint_event_id   varchar(64) not null comment 'checkpoint event业务id',
    state_json            longtext null comment '恢复状态JSON',
    gmt_create            datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified          datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version               bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_run_checkpoint_id (checkpoint_id),
    unique key uk_agent_run_checkpoint_type (run_id, checkpoint_type)
) engine=InnoDB default charset=utf8mb4 comment='agent run checkpoint';
```

## remote tool call 表

### `agent_tool_call`

保存 remote tool 调用状态，与 session event 共同表达工具执行事实。

```sql
create table if not exists agent_tool_call (
    id                 bigint unsigned not null auto_increment comment '主键',
    tool_call_id       varchar(64) not null comment 'tool call业务id',
    run_id             varchar(64) not null comment 'run业务id',
    session_id         varchar(64) not null comment 'session业务id',
    env_id             varchar(64) not null comment '目标环境业务id',
    tool_name          varchar(128) not null comment '工具名称',
    arguments_json     longtext not null comment '工具参数JSON',
    status             varchar(32) not null comment 'tool call状态',
    result_json        longtext null comment '工具结果JSON',
    error_code         varchar(128) null comment '错误码',
    error_message      varchar(2048) null comment '错误信息',
    started_at         datetime(3) null comment '开始时间',
    ended_at           datetime(3) null comment '结束时间',
    gmt_create         datetime(3) not null default current_timestamp(3) comment '创建时间',
    gmt_modified       datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
    version            bigint not null default '0' comment '数据版本号',
    primary key (id),
    unique key uk_agent_tool_call_id (tool_call_id),
    key idx_agent_tool_call_run (run_id),
    key idx_agent_tool_call_session (session_id),
    key idx_agent_tool_call_env_status (env_id, status)
) engine=InnoDB default charset=utf8mb4 comment='agent remote tool call';
```

## 状态值

### env status

| 值 | 含义 |
| --- | --- |
| `online` | daemon 在线 |
| `offline` | daemon 离线 |
| `disabled` | 环境被禁用 |

### run status

| 值 | 含义 |
| --- | --- |
| `pending` | 等待执行 |
| `running` | 执行中 |
| `completed` | 正常完成 |
| `failed` | 异常失败 |
| `aborted` | 用户中断 |

### tool call status

| 值 | 含义 |
| --- | --- |
| `pending` | 等待发送到 daemon |
| `running` | 本地工具执行中 |
| `completed` | 工具正常完成 |
| `failed` | 工具执行失败 |
| `cancelled` | 工具调用被取消 |

## 存储实现

### AutoMapper 使用方式

使用 `mybatis-auto-mapper` 统一生成大部分 Mapper XML，但不在每个 Mapper 上显式写：

```java
@AutoMapper(dbType = DBType.SQLITE)
```

而是通过模块级 `auto-mapper.config` 统一指定方言。

### 重要边界

`mybatis-auto-mapper` 是编译期 SQL 生成器，不是运行时方言切换器。

采用方式：

- 代码层不写显式 `dbType`。
- 构建时由模块级 `auto-mapper.config` 统一指定方言。
- 存在明显方言差异的持久化语句使用手写 XML。

如果目标是同一发行物运行时自由切换 MySQL / H2，仅靠 `auto-mapper.config` 还不够，因为方言是在编译期固化的。

因此存储层采用单次构建对应一个目标方言的方式组织 SQL 生成与装配。

## daemon-facing API 与存储关系

daemon 通过语义化 API 接入环境注册表和工具能力，不直接访问 session / event / run 的 canonical store。

daemon-facing API 包括：

- `env_register`
- `env_heartbeat`
- `env_capability_update`

这些 API 最终落到环境注册表与工具能力表，而不是把表结构直接暴露给 daemon。

session / event / run 的读取、写入、lease 与 checkpoint 都是云端内部 API，由 `core` / `web` 和云端 agent runtime 使用。
