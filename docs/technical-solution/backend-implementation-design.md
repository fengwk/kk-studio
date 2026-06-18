# 后端落地设计

本文是整体技术方案的一部分，描述 `core`、`web`、`share`、`daemon` 与 `agent` 之间的包结构、模型边界和 API 协议落地方式。

## 模块依赖边界

后端模块依赖方向：

```text
web -> core -> agent
web -> share
core -> share
daemon -> share
agent -> third-party provider sdk
```

边界约束：

- `agent` 是云端 agent 执行内核，不依赖 `core`、`web`、`daemon`。
- `core` 负责把数据库中的 profile/env/tool/session/run 模型组装成 agent 可执行上下文。
- `web` 负责暴露 Studio API、daemon-facing API 与 daemon 连接通道。
- `share` 只放跨模块 API DTO、协议 DTO、错误码与稳定枚举值。
- `daemon` 使用 `share` 中的协议 DTO 与云端通信，使用自身本地 tool host 接口承载工具，不依赖 `agent` runtime。
- 云端 remote tool 由 `core` 包装成 `agent.tool.Tool`，并注册到 `agent.tool.ToolRegistry`。

实施前置变更：

- `core/pom.xml` 增加对 `kk-studio-agent` 的编译期依赖，用于装配云端 remote tool adapter。
- `web/pom.xml` 显式增加 WebSocket 相关依赖，用于 daemon 长连接通道。
- `daemon/pom.xml` 只依赖 `share`，不依赖 `agent` runtime。

## `share` 包结构

`share` 延续当前 `model` 与 `constant` 包风格，DTO 使用明确前缀区分领域。

```text
fun.fengwk.kkstudio.share
├── constant
│   └── AgentErrorCodes.java
└── model
    ├── AgentProfileDTO.java
    ├── AgentProfileCreateDTO.java
    ├── AgentProfileUpdateDTO.java
    ├── AgentProfileToolBindingDTO.java
    ├── AgentRuntimePolicyDTO.java
    ├── AgentEnvDTO.java
    ├── AgentRuntimeDTO.java
    ├── AgentToolCapabilityDTO.java
    ├── AgentSessionDTO.java
    ├── AgentSessionEventDTO.java
    ├── AgentSessionHeadDTO.java
    ├── AgentSessionCreateDTO.java
    ├── AgentUserMessageSubmitDTO.java
    ├── AgentMessageSubmitResultDTO.java
    ├── AgentSessionStreamEventDTO.java
    ├── AgentRunDTO.java
    ├── AgentToolCallDTO.java
    ├── EnvRegisterRequestDTO.java
    ├── EnvRegisterResponseDTO.java
    ├── EnvHeartbeatRequestDTO.java
    ├── EnvCapabilityUpdateRequestDTO.java
    ├── EnvReconfigureMessageDTO.java
    ├── ToolCapabilityDTO.java
    ├── DaemonWsEnvelopeDTO.java
    ├── ToolCallStartMessageDTO.java
    ├── ToolCallDeltaMessageDTO.java
    ├── ToolCallEndMessageDTO.java
    ├── ToolCallErrorMessageDTO.java
    └── ToolCallCancelMessageDTO.java
```

DTO 设计规则：

- REST 入参使用 `*CreateDTO`、`*UpdateDTO`、`*RequestDTO`。
- REST 出参使用 `*DTO`、`*ResponseDTO`。
- daemon 双向消息使用 `*MessageDTO`。
- JSON 字段在 DTO 中使用 `String` 承载稳定 JSON 文本，由服务层负责解析与校验。
- 持久化枚举值使用 lower snake case 字符串，例如 `online`、`offline`、`running`、`tool_start`。

关键协议 DTO 字段：

- `AgentSessionCreateDTO`
  - `profileId`
  - `title`
- `AgentUserMessageSubmitDTO`
  - `clientMessageId`
  - `text`
- `AgentMessageSubmitResultDTO`
  - `clientMessageId`
  - `run`
- `AgentSessionHeadDTO`
  - `headId`
  - `sessionId`
  - `headName`
  - `headEventId`
- `AgentSessionStreamEventDTO`
  - `seq`
  - `sessionId`
  - `headEventId`
  - `type`
  - `session`
  - `heads`
  - `run`
  - `events`
  - `heartbeatTime`
- `DaemonWsEnvelopeDTO`
  - `messageId`
  - `messageType`
  - `envId`
  - `timestamp`
  - `payloadJson`
- `ToolCallStartMessageDTO`
  - `toolCallId`
  - `runId`
  - `sessionId`
  - `envId`
  - `toolName`
  - `argumentsJson`
  - `timeoutMillis`
- `ToolCallDeltaMessageDTO`
  - `toolCallId`
  - `sequence`
  - `contentDeltasJson`
- `ToolCallEndMessageDTO`
  - `toolCallId`
  - `resultJson`
  - `completedTime`
- `ToolCallErrorMessageDTO`
  - `toolCallId`
  - `errorCode`
  - `errorMessage`
  - `retryable`
- `ToolCallCancelMessageDTO`
  - `toolCallId`
  - `reason`

## `core` 包结构

`core` 按 agent control plane 领域组织包，每个领域延续现有 `repo` / `service` / `converter` / `repo.impl.mapper` / `repo.impl.model` 分层。

```text
fun.fengwk.kkstudio.core.agent
├── profile
│   ├── repo
│   │   ├── AgentProfileRepository.java
│   │   ├── AgentProfileToolBindingRepository.java
│   │   ├── AgentRuntimePolicyRepository.java
│   │   └── impl
│   │       ├── MysqlAgentProfileRepository.java
│   │       ├── MysqlAgentProfileToolBindingRepository.java
│   │       ├── MysqlAgentRuntimePolicyRepository.java
│   │       ├── mapper
│   │       │   ├── AgentProfileMapper.java
│   │       │   ├── AgentProfileToolBindingMapper.java
│   │       │   └── AgentRuntimePolicyMapper.java
│   │       └── model
│   │           ├── AgentProfileDO.java
│   │           ├── AgentProfileToolBindingDO.java
│   │           └── AgentRuntimePolicyDO.java
│   └── service
│       ├── AgentProfileService.java
│       ├── converter
│       │   └── AgentProfileConverter.java
│       ├── impl
│       │   └── AgentProfileServiceImpl.java
│       └── model
│           ├── AgentProfile.java
│           ├── AgentProfileToolBinding.java
│           └── AgentRuntimePolicy.java
├── env
│   ├── repo
│   │   ├── AgentEnvRepository.java
│   │   ├── AgentRuntimeRepository.java
│   │   ├── AgentToolCapabilityRepository.java
│   │   └── impl
│   │       ├── MysqlAgentEnvRepository.java
│   │       ├── MysqlAgentRuntimeRepository.java
│   │       ├── MysqlAgentToolCapabilityRepository.java
│   │       ├── mapper
│   │       │   ├── AgentEnvMapper.java
│   │       │   ├── AgentRuntimeMapper.java
│   │       │   └── AgentToolCapabilityMapper.java
│   │       └── model
│   │           ├── AgentEnvDO.java
│   │           ├── AgentRuntimeDO.java
│   │           └── AgentToolCapabilityDO.java
│   └── service
│       ├── AgentEnvService.java
│       ├── EnvironmentRegistry.java
│       ├── converter
│       │   └── AgentEnvConverter.java
│       ├── impl
│       │   ├── AgentEnvServiceImpl.java
│       │   └── EnvironmentRegistryImpl.java
│       └── model
│           ├── AgentEnv.java
│           ├── AgentRuntime.java
│           └── AgentToolCapability.java
├── tool
│   ├── repo
│   │   ├── AgentToolCallRepository.java
│   │   └── impl
│   │       ├── MysqlAgentToolCallRepository.java
│   │       ├── mapper
│   │       │   └── AgentToolCallMapper.java
│   │       └── model
│   │           └── AgentToolCallDO.java
│   └── service
│       ├── RemoteToolRegistry.java
│       ├── RemoteToolRouter.java
│       ├── AgentToolCallService.java
│       ├── converter
│       │   └── RemoteToolConverter.java
│       ├── impl
│       │   ├── RemoteToolRegistryImpl.java
│       │   ├── RemoteToolRouterImpl.java
│       │   └── AgentToolCallServiceImpl.java
│       └── model
│           ├── RemoteToolRegistration.java
│           ├── RemoteToolRoute.java
│           └── AgentToolCall.java
├── session
│   ├── repo
│   │   ├── AgentSessionRepository.java
│   │   ├── AgentSessionEventRepository.java
│   │   ├── AgentSessionHeadRepository.java
│   │   └── impl
│   │       ├── MysqlAgentSessionRepository.java
│   │       ├── MysqlAgentSessionEventRepository.java
│   │       ├── MysqlAgentSessionHeadRepository.java
│   │       ├── mapper
│   │       │   ├── AgentSessionMapper.java
│   │       │   ├── AgentSessionEventMapper.java
│   │       │   └── AgentSessionHeadMapper.java
│   │       └── model
│   │           ├── AgentSessionDO.java
│   │           ├── AgentSessionEventDO.java
│   │           └── AgentSessionHeadDO.java
│   └── service
│       ├── AgentSessionService.java
│       ├── AgentSessionEventService.java
│       ├── converter
│       │   └── AgentSessionConverter.java
│       ├── impl
│       │   ├── AgentSessionServiceImpl.java
│       │   └── AgentSessionEventServiceImpl.java
│       └── model
│           ├── AgentSession.java
│           ├── AgentSessionEvent.java
│           └── AgentSessionHead.java
└── run
    ├── repo
    │   ├── AgentRunRepository.java
    │   ├── AgentRunLeaseRepository.java
    │   ├── AgentRunCheckpointRepository.java
    │   └── impl
    │       ├── MysqlAgentRunRepository.java
    │       ├── MysqlAgentRunLeaseRepository.java
    │       ├── MysqlAgentRunCheckpointRepository.java
    │       ├── mapper
    │       │   ├── AgentRunMapper.java
    │       │   ├── AgentRunLeaseMapper.java
    │       │   └── AgentRunCheckpointMapper.java
    │       └── model
    │           ├── AgentRunDO.java
    │           ├── AgentRunLeaseDO.java
    │           └── AgentRunCheckpointDO.java
    └── service
        ├── AgentRunService.java
        ├── AgentRunLeaseService.java
        ├── CloudAgentRuntimeService.java
        ├── converter
        │   └── AgentRunConverter.java
        ├── impl
        │   ├── AgentRunServiceImpl.java
        │   ├── AgentRunLeaseServiceImpl.java
        │   └── CloudAgentRuntimeServiceImpl.java
        └── model
            ├── AgentRun.java
            ├── AgentRunLease.java
            └── AgentRunCheckpoint.java
```

资源目录：

```text
core/src/main/resources
├── auto-mapper.config
├── schema-mysql.sql
├── schema-h2.sql
├── data-h2.sql
├── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
└── fun/fengwk/kkstudio/core/agent
    ├── profile/repo/impl/mapper/*Mapper.xml
    ├── env/repo/impl/mapper/*Mapper.xml
    ├── tool/repo/impl/mapper/*Mapper.xml
    ├── session/repo/impl/mapper/*Mapper.xml
    └── run/repo/impl/mapper/*Mapper.xml
```

数据库构建模式：

- `schema-mysql.sql` 是 MySQL DDL 基准脚本。
- `schema-h2.sql` 是 H2 DDL 基准脚本，用于测试与轻量本地运行。
- `data-h2.sql` 是 H2 seed 数据，使用 `merge into ... key (...) values ...` 保证重复执行稳定。
- `auto-mapper.config` 统一指定 AutoMapper 生成规则，Mapper 类不显式指定 `dbType`。
- 手写 Mapper XML 与 Mapper 接口包路径保持一致。

核心服务职责：

- `EnvironmentRegistry` 统一维护 env、runtime、tool capability 快照，并向 remote tool registry 提供可用工具视图。
- `RemoteToolRegistry` 根据环境能力和 profile tool binding 组装 `agent.tool.ToolInfo` 与 `agent.tool.Tool`。
- `RemoteToolRouter` 根据 `envId + toolName + toolCallId` 路由到 daemon 连接。
- `CloudAgentRuntimeService` 创建 run、加载 branch、组装 tool registry、启动 `agent.Agent` 并写入 session event。
- `AgentRunLeaseService` 保证同一个 run 同一时刻只有一个有效云端执行者。

## `web` 包结构

`web` 保持薄控制层，Controller 只做参数接收、鉴权上下文提取、调用 core service 与响应转换。

```text
fun.fengwk.kkstudio.web
├── controller
│   ├── StudioAgentProfileController.java
│   ├── StudioAgentEnvController.java
│   ├── StudioAgentSessionController.java
│   ├── StudioAgentRunController.java
│   └── DaemonEnvController.java
├── ws
│   ├── DaemonConnectionEndpoint.java
│   ├── DaemonConnectionRegistry.java
│   ├── DaemonMessageDispatcher.java
│   └── DaemonMessageCodec.java
└── WebApplication.java
```

Controller 路由：

| Controller | 路由前缀 | 职责 |
| --- | --- | --- |
| `StudioAgentProfileController` | `/api/agent/profiles` | profile、runtime policy、tool binding 管理 |
| `StudioAgentEnvController` | `/api/agent/envs` | 环境、runtime、tool capability 查询 |
| `StudioAgentSessionController` | `/api/agent/sessions` | session 创建、事件读取、用户消息提交 |
| `StudioAgentRunController` | `/api/agent/runs` | run 查询、abort、状态读取 |
| `DaemonEnvController` | `/api/daemon/env` | env register、heartbeat、capability update |

daemon 连接通道：

- WebSocket 路径：`/api/daemon/ws`
- 连接标识：`envId`
- 连接注册：`DaemonConnectionRegistry`
- 消息分发：`DaemonMessageDispatcher`
- 消息编解码：`DaemonMessageCodec`

WebSocket 能力需要在 `web` 模块显式声明对应 Spring WebSocket 依赖后启用，避免把未声明依赖作为隐式前提。

## `daemon` 包结构

`daemon` 作为本地环境进程，包结构围绕 server client、环境探测和本地工具执行组织。

```text
fun.fengwk.kkstudio.daemon
├── client
│   ├── DaemonServerClient.java
│   ├── DaemonWebSocketClient.java
│   ├── DaemonMessageSender.java
│   └── DaemonReconnectPolicy.java
├── env
│   ├── EnvIdentity.java
│   ├── EnvInfoCollector.java
│   ├── RuntimeInfoCollector.java
│   └── WorkspaceRootResolver.java
├── lifecycle
│   ├── DaemonLifecycle.java
│   ├── EnvRegistrationTask.java
│   └── EnvHeartbeatTask.java
├── tool
│   ├── DaemonTool.java
│   ├── DaemonToolInfo.java
│   ├── DaemonToolCallRequest.java
│   ├── DaemonToolExecutionHandle.java
│   ├── DaemonToolExecutionHandler.java
│   ├── DynamicToolManager.java
│   ├── ToolHost.java
│   ├── ToolInvoker.java
│   └── local
│       └── WorkspaceListTool.java
└── package-info.java
```

daemon tool SPI：

```java
public interface DaemonTool {

    DaemonToolExecutionHandle asyncExecute(DaemonToolCallRequest request,
                                           DaemonToolExecutionHandler handler);

    DaemonToolInfo toolInfo();

}
```

daemon tool SPI 与 `agent.tool.Tool` 分离：

- daemon tool SPI 面向本地进程执行和协议回传。
- `agent.tool.Tool` 面向云端 agent 主循环。
- 两者通过 `share.model.ToolCapabilityDTO` 与 daemon message DTO 连接。

## 领域模型

### profile 模型

| 模型 | 关键字段 | 说明 |
| --- | --- | --- |
| `AgentProfile` | `profileId`, `name`, `systemPrompt`, `defaultProvider`, `defaultModel`, `enabled`, `configJson` | agent 配置主体 |
| `AgentProfileToolBinding` | `bindingId`, `profileId`, `envId`, `toolName`, `toolAlias`, `enabled`, `bindingConfigJson` | profile 可见工具绑定 |
| `AgentRuntimePolicy` | `profileId`, `maxRounds`, `maxConsecutiveErrors`, `toolTimeoutMillis`, `runTimeoutMillis`, `retryPolicyJson` | 运行策略 |

### env 模型

| 模型 | 关键字段 | 说明 |
| --- | --- | --- |
| `AgentEnv` | `envId`, `envName`, `deviceName`, `os`, `arch`, `workspaceRootsJson`, `status`, `lastSeenAt`, `capabilityRevision` | 环境注册表主体 |
| `AgentRuntime` | `runtimeId`, `envId`, `runtimeType`, `runtimeName`, `runtimeVersion`, `status`, `metadataJson` | 环境上的 runtime 信息 |
| `AgentToolCapability` | `capabilityId`, `envId`, `toolName`, `sourceType`, `schemaJson`, `enabled`, `revision`, `metadataJson` | 环境上报工具能力 |

### session 模型

| 模型 | 关键字段 | 说明 |
| --- | --- | --- |
| `AgentSession` | `sessionId`, `profileId`, `title`, `status`, `currentHeadEventId`, `metadataJson` | session 元数据 |
| `AgentSessionEvent` | `sessionId`, `eventId`, `eventType`, `parentEventId`, `runId`, `payloadType`, `payloadJson` | append-only event tree |
| `AgentSessionHead` | `sessionId`, `headName`, `headEventId` | 逻辑 head，默认 head 使用 `default` |

### run 模型

| 模型 | 关键字段 | 说明 |
| --- | --- | --- |
| `AgentRun` | `runId`, `sessionId`, `profileId`, `baseEventId`, `headEventId`, `status`, `triggerType`, `startedAt`, `endedAt`, `errorMessage` | 云端 agent run |
| `AgentRunLease` | `runId`, `ownerId`, `leaseToken`, `expiresAt`, `lastRenewAt` | run 执行租约 |
| `AgentRunCheckpoint` | `runId`, `checkpointType`, `checkpointEventId`, `stateJson` | run 恢复指针 |
| `AgentToolCall` | `toolCallId`, `runId`, `sessionId`, `envId`, `toolName`, `argumentsJson`, `status`, `resultJson`, `errorMessage` | remote tool 调用状态 |

## API 协议

### Studio API

| Method | Path | 入参 | 出参 | 职责 |
| --- | --- | --- | --- | --- |
| `POST` | `/api/agent/profiles` | `AgentProfileCreateDTO` | `AgentProfileDTO` | 创建 profile |
| `PATCH` | `/api/agent/profiles/{profileId}` | `AgentProfileUpdateDTO` | `AgentProfileDTO` | 更新 profile |
| `GET` | `/api/agent/profiles` | query | `Page<AgentProfileDTO>` | 查询 profile |
| `POST` | `/api/agent/profiles/{profileId}/tool-bindings` | `AgentProfileToolBindingDTO` | `AgentProfileToolBindingDTO` | 绑定环境工具 |
| `DELETE` | `/api/agent/profiles/{profileId}/tool-bindings/{bindingId}` | path | `Void` | 删除工具绑定 |
| `GET` | `/api/agent/envs` | query | `Page<AgentEnvDTO>` | 查询环境 |
| `GET` | `/api/agent/envs/{envId}/tools` | path | `List<AgentToolCapabilityDTO>` | 查询环境工具 |
| `POST` | `/api/agent/sessions` | `AgentSessionCreateDTO` | `AgentSessionDTO` | 创建 session |
| `GET` | `/api/agent/sessions/{sessionId}` | path | `AgentSessionDTO` | 查询 session |
| `GET` | `/api/agent/sessions/{sessionId}/heads` | path | `List<AgentSessionHeadDTO>` | 查询 session 可切换 heads |
| `GET` | `/api/agent/sessions/{sessionId}/events` | `headEventId` | `List<AgentSessionEventDTO>` | 读取 branch events |
| `POST` | `/api/agent/sessions/{sessionId}/messages` | `AgentUserMessageSubmitDTO` | `AgentMessageSubmitResultDTO` | 提交用户消息并启动 run |
| `GET` | `/api/agent/runs/{runId}` | path | `AgentRunDTO` | 查询 run |
| `POST` | `/api/agent/runs/{runId}/abort` | path | `AgentRunDTO` | 请求中断 run |

### Session SSE API

| Method | Path | query | 事件名 | 职责 |
| --- | --- | --- | --- | --- |
| `GET` | `/api/agent/sessions/{sessionId}/stream` | `headEventId`, `afterSeq` | `session.snapshot`, `session.updated`, `session.heartbeat` | 实时推送当前 branch 的 event 与 run 状态 |

SSE 规则：

- 初次连接必须先发送 `session.snapshot`。
- `session.snapshot` 携带 `session`、`heads`、当前 branch `events` 与最新 `run` 状态。
- 后续新增 event、run 状态变化发送 `session.updated`。
- 长连接保活发送 `session.heartbeat`。
- 前端使用 `seq` 去重；重连时带 `afterSeq`，服务端优先补发缺失更新，无法补齐时重新下发 `session.snapshot`。

### daemon-facing HTTP API

| Method | Path | 入参 | 出参 | 职责 |
| --- | --- | --- | --- | --- |
| `POST` | `/api/daemon/env/register` | `EnvRegisterRequestDTO` | `EnvRegisterResponseDTO` | 注册环境并返回连接配置 |
| `POST` | `/api/daemon/env/heartbeat` | `EnvHeartbeatRequestDTO` | `Void` | 刷新在线状态 |
| `POST` | `/api/daemon/env/capabilities` | `EnvCapabilityUpdateRequestDTO` | `Void` | 上报 capability 快照 |

### daemon WebSocket 消息

所有 WS 消息使用统一 envelope：

```json
{
  "messageId": "msg_...",
  "messageType": "tool_call_start",
  "envId": "env_...",
  "timestamp": "2026-06-19T12:00:00.000",
  "payloadJson": "{}"
}
```

server 到 daemon：

| messageType | payload | 职责 |
| --- | --- | --- |
| `ping` | `{}` | 探活 |
| `env_reconfigure` | `EnvReconfigureMessageDTO` | 触发 capability 重载 |
| `tool_call_start` | `ToolCallStartMessageDTO` | 启动本地工具 |
| `tool_call_cancel` | `ToolCallCancelMessageDTO` | 取消本地工具 |

daemon 到 server：

| messageType | payload | 职责 |
| --- | --- | --- |
| `pong` | `{}` | 探活响应 |
| `tool_call_delta` | `ToolCallDeltaMessageDTO` | 工具流式输出 |
| `tool_call_end` | `ToolCallEndMessageDTO` | 工具完成 |
| `tool_call_error` | `ToolCallErrorMessageDTO` | 工具失败 |

## remote tool 执行流程

```text
1. daemon 通过 env_register 注册环境。
2. daemon 通过 env_capability_update 上报本地工具 capability。
3. core 将 capability 写入 EnvironmentRegistry。
4. Studio 为 profile 添加 agent_profile_tool_binding。
5. 用户提交消息后，CloudAgentRuntimeService 创建 run。
6. RemoteToolRegistry 按 profile binding 组装 agent 可见工具列表。
7. agent 发起 ToolCallRequest。
8. RemoteToolRouter 根据 envId + toolName 发送 tool_call_start。
9. daemon ToolHost 执行本地工具并回传 delta/end/error。
10. core 写入 AgentToolCall 状态与 session event。
```

协议实现规则：

- `ToolCallDeltaMessageDTO.contentDeltasJson` 承载 `List<IndexedToolContentDelta>` 的稳定 JSON 表示。
- `ToolCallEndMessageDTO.resultJson` 承载 `List<ToolContent>` 的稳定 JSON 表示。
- `ToolCallErrorMessageDTO` 对应 `ToolExecutionHandler.onError(...)` 终态。
- 同一个 `toolCallId` 的 `sequence` 必须单调递增，用于前端和云端去重。
- `tool_call_cancel` 是 best-effort；daemon 已回 `end` / `error` 后可忽略取消请求。

## 持久化实现规则

- DO 继承 `ConventionDO<Long>`，字段映射到 `id`、`gmt_create`、`gmt_modified`、`version`。
- Mapper 继承 `BaseMapper` 并使用 `@AutoMapper`，不在 Mapper 上显式指定 `dbType`。
- JSON 字段在 DO 中使用 `String`，数据库使用文本列存储。
- Repository 实现类使用 `Mysql*Repository` 命名，并集中处理 DO 与 service model 转换。
- 参数校验使用 `IllegalArgumentException` 表达非法输入。
