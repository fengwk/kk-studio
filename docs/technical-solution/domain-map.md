# 领域词汇与双域映射

本文是前后端共用的当前领域词汇事实源。

## 1. 产品域与依赖

| 域 | 代码位置 | 职责 |
| --- | --- | --- |
| Harness / AI | `harness-tool`、`harness-runtime`、`harness-daemon`、`core.ai`、`features/ai` | Catalog、Chat、Session、Entry Tree、Thread、Model/Tool Invocation、Interaction |
| Studio / Canvas | `studio`、`core.studio`、`features/canvas` | Canvas document、node、link、command dedup |

```text
frontend
  -> web
    -> core
      -> studio
      -> harness-runtime -> harness-tool
      -> harness-tool
  -> share
harness-daemon -> harness-tool
```

`studio` 不依赖 Spring、MyBatis、Harness 或 Web；`harness-*` 不依赖 `studio`。Agent 进入 Studio 通过 `system.agent.execute` FunctionRef。

## 2. Catalog 词汇

| 概念 | 含义 |
| --- | --- |
| Provider | 以 immutable `name` 标识的连接配置 |
| Model | 以 `(providerName, name)` 标识的模型配置 |
| Agent | 以 immutable `name` 标识的系统提示、Model/Variant 与 tools/skills 配置 |
| Model ref | `providerName/modelName`；解析只切第一个 `/` |
| Variant | Model config 中的 variant `id`；Agent 可指定覆盖值 |
| ToolCatalog | 本地 Tool、固定 Environment Tool 和 runtime-managed `load_skill` 的统一目录 |

Catalog 没有 bigint resource ID。Catalog 的版本仍作为并发更新 token 以十进制字符串暴露。

## 3. Harness 词汇

| 概念 | 含义 |
| --- | --- |
| Chat | 保存唯一可见发送设置 `agentName`、`environmentName`、`yoloEnabled` 的持久集合 |
| Session | append-only Entry Tree 的边界 |
| Entry | 语义持久事实：`ROOT`、`MESSAGE`、`CUSTOM_MESSAGE`、`ASSISTANT_ERROR`、`ASSISTANT_ABORTED` |
| HarnessThread | 持有非空 head、mailbox、runnable、revision、epoch 与 lease 的 durable runtime process |
| ThreadInput | 有序消息 mailbox，只允许 `USER_MESSAGE` 与 `CUSTOM_MESSAGE` |
| TurnSettings | 每条响应生产消息携带的 compact 名称引用 |
| ModelInvocation | 一次冻结 `ModelInvocationRequest` 的 Provider 调用 |
| ToolInvocation | 按冻结 ToolBinding 执行的一次 ToolCall durable 事实 |
| Interaction | Tool permission 等待与解决事实 |
| Environment | READY Daemon 的服务器内存资源，以名称唯一 |
| Usage ledger | 按 Provider/Model 名称记录历史用量、价格和缓存事实的不可变账本 |
| Realtime projection | Redis Streams 中有界、可丢失的输出覆盖层 |

Agent 的 tools/skills 决定本次运行能力；每次规划通过 `DatabaseTurnExecutionResolver` 读取 Catalog 与 READY Environment。解析失败形成 typed `PlanningFailure`，最终写成 `ASSISTANT_ERROR`。

## 4. Chat、Thread 与前端映射

| 前端对象 | 服务端事实 |
| --- | --- |
| Chat 卡片 | `ChatDTO`：标题、Agent/Environment/YOLO 可见设置、版本 |
| Chat 工作区 | `localStorage` 中的八个 Pane 槽位 |
| Pane | 本地 `threadId` 绑定；服务端通过 Chat↔Thread 历史关系聚合 |
| Composer | 每次发送构造包含当前可见设置的 USER message 请求 |
| Thread transcript | `HarnessThreadSnapshotDTO.entries` 与 queued inputs |
| `/session`、`/tree` | 选择 Entry 后调用 `PUT /api/ai/runtime/threads/{threadId}/head` |
| Footer | 依据当前 Chat 设置、Agent Catalog 和 Model ref 展示运行标签 |

## 5. Canvas 词汇

| 概念 | 含义 |
| --- | --- |
| CanvasDocument | 画布身份、标题与 revision |
| CanvasNodeKind | `RESOURCE` / `FUNCTION` |
| CanvasLink | 同一 Canvas 内的可见性边 |
| CanvasCommand | 带 `commandId` 与 `baseRevision` 的幂等命令 |

当前可持久化的 Function 节点是 `system.generate-text` v1。表现型节点类型映射见 `frontend/src/features/canvas/domain-map.ts`。

## 6. API 边界

| API | 当前职责 |
| --- | --- |
| `GET/POST /api/ai/catalog/providers` | Provider 分页查询与创建 |
| `GET/POST /api/ai/catalog/models` | Model 分页查询与创建 |
| `GET/POST /api/ai/catalog/agents` | Agent 分页查询与创建 |
| `GET/POST /api/ai/chat` | Chat 列表与创建 |
| `POST /api/ai/chat/{chatId}/threads` | 原子创建 Session、ROOT、Thread 并关联 Chat |
| `GET /api/ai/runtime/threads` | 全局 Thread 分页查询 |
| `GET /api/ai/runtime/threads/{threadId}/snapshot` | 单一 Thread runtime 投影 |
| `POST /api/ai/runtime/threads/{threadId}/messages` | USER message 入队 |
| `POST /api/ai/runtime/threads/{threadId}/messages/custom` | SYSTEM/USER custom message 入队 |
| `PUT /api/ai/runtime/threads/{threadId}/head` | 静止 Thread 的非空 head 重定位 |
| `GET /api/ai/catalog/tools` | 统一可选 ToolCatalog |
| `GET /api/ai/environment` | READY Environment 内存投影 |

## 7. 一句话实现

Harness 以 Session Entry Tree 记录语义事实，以非空 head 的 Thread 记录执行控制面，以每条消息的 TurnSettings 驱动逐轮 Catalog 解析，并以冻结的 Model/Tool/Usage 事实支持恢复；Studio 独立承载 Canvas。
