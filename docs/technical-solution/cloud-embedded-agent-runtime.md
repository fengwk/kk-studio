# 云端内嵌 agent runtime

本文描述当前仓库已经落地的云端 embedded runtime 形态。

## 运行时摘要

| 主题 | 当前状态 |
| --- | --- |
| 运行模型 | 服务端单进程 embedded runtime |
| 触发方式 | `web` 在 message 写事务返回后调用 `scheduleQueuedRun` |
| 存储位置 | session / event / run 统一落在 `core` 使用的存储 |
| 刷新方式 | session events 使用 SSE，active runs 使用轮询 |
| ToolRegistry | 当前运行时组装为空列表 |

## 运行模型

```mermaid
flowchart LR
    Frontend[frontend]
    Web[web]
    Core[core]
    Agent[agent]
    Store[(session/event/run 存储)]

    Frontend --> Web
    Web --> Core
    Core --> Agent
    Core --> Store
```

## 执行链路

```mermaid
sequenceDiagram
    participant U as User
    participant F as frontend
    participant W as web
    participant S as AgentSessionService
    participant R as AgentRunRuntimeService
    participant E as EmbeddedAgentRunRuntimeService
    participant A as agent
    participant DB as Store

    U->>F: 在 session 页提交 message
    F->>W: POST /api/agent/sessions/{sessionId}/messages
    W->>S: createMessage(sessionId, content)
    S->>DB: 锁定 session 并确认无 active run
    S->>DB: 写入 user_message 与 queued run
    S->>DB: 前移 session current head
    S-->>W: 返回已提交的 user_message
    W->>R: scheduleQueuedRun(runId, sessionId, content)
    R->>E: executeQueuedRun(...)
    E->>DB: run -> running
    E->>A: 重载 session branch 并提交 UserRequest
    A->>DB: 追加 set_agent_info / set_model_info / assistant_*
    E->>DB: 推进 head 指针
    E->>DB: run -> succeeded / failed
    W-->>F: SSE 推送 session events
    F->>W: active run 期间轮询 runs
```

## 执行步骤

| 步骤 | 行为 | 说明 |
| --- | --- | --- |
| 1 | 锁定 session 并检查 active run | 同一 session 只允许一个 `queued/running` run |
| 2 | 追加 `user_message` / `text` 事件 | 由 `AgentSessionService` 在事务内写入 |
| 3 | 创建 `queued` run | `triggerEventId` 指向刚创建的 `user_message` |
| 4 | 前移 session current head | 精确指向当前 `user_message` |
| 5 | `web` 在事务返回后调度 runtime | executor 拒绝任务时 queued run 立即转为 `failed` |
| 6 | run 进入 `running` | 由 `EmbeddedAgentRunRuntimeService` 执行 |
| 7 | 重载分支并继续追加事件 | `set_agent_info`、`set_model_info`、`assistant_*` |
| 8 | 再次前移 head | 指向最新 assistant 事件 |
| 9 | run 进入 `succeeded` 或 `failed` | 终态持久化 |

## 运行约束

| 主题 | 当前规则 |
| --- | --- |
| 调度模型 | 单进程 executor 调度；同一 session 只允许一个 active run |
| 推送方式 | `events` 使用 SSE，active `runs` 使用轮询 |
| Tool 组装 | 从 `AgentInfo.tools` 声明中解析当前已注册工具 |
| Provider 配置 | 通过 `kk-studio.agent.runtime.providers.<providerName>` 解析 |
| local-h2 验收 | `LocalH2AcceptanceConfiguration` 使用 stub provider 返回 `stub response` |
| 事件流 | 控制面 `user_message` 与 runtime `set_*` / `assistant_*` 混合保存 |

## 模块边界

| 模块 | 职责 |
| --- | --- |
| `agent` | 主循环、provider 调用、session event 投影与 branch 重放 |
| `core` | run 调度、状态迁移、事件落库、执行上下文组装 |
| `web` | session / run 查询、消息提交 API |
