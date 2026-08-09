# 技术方案

本文档目录维护 `kk-studio` 当前实现，只描述现行职责、结构、协议与约束。代码、Controller、DTO、schema 与测试是事实源；文档只做自洽的当前态描述，不保存历史方案或会话过程。

## 文档地图

```mermaid
flowchart TD
    A[技术方案入口]
    A --> B[architecture.md<br/>架构与模块边界]
    A --> O[domain-map.md<br/>领域词汇与映射]
    A --> Q[harness-runtime-architecture.md<br/>Harness Runtime 架构]
    A --> X[harness-runtime-contracts.md<br/>Runtime 公共契约]
    A --> S[harness-storage-runtime.md<br/>PostgreSQL durable facts 与 Work/Redis]
    A --> D[backend-implementation-design.md<br/>后端实现]
    A --> E[storage-models.md<br/>存储模型]
    A --> F[frontend-implementation-design.md<br/>前端实现]
    A --> H[harness-capability-wiring.md<br/>Harness 能力装配]
    A --> I[prompt-cache-usage-cost.md<br/>Prompt Cache 与 Usage/Cost 冻结]
    A --> J[s3-presign.md<br/>S3 预签名直传]
    A --> K[comfyui-workflow-api.md<br/>ComfyUI 工作流与运行 API]
    A --> L[environment-daemon-gateway.md<br/>Environment Daemon Gateway v2]
    A --> M[prompt-to-resource.md<br/>Prompt 到 Resource 数据流]
    A --> P[e2e-regression.md<br/>E2E 回归矩阵与报告]
```

## 阅读顺序

| 顺序 | 文档 | 关注点 |
| --- | --- | --- |
| 1 | [architecture.md](architecture.md) | 模块拓扑、依赖方向与所有权 |
| 2 | [domain-map.md](domain-map.md) | Harness/Studio 词汇与前后端映射 |
| 3 | [harness-runtime-architecture.md](harness-runtime-architecture.md) | Entry/Thread/Command/Invocation/Work、Agent Loop 与 processor |
| 4 | [harness-runtime-contracts.md](harness-runtime-contracts.md) | JSON/DTO、命令 batch、CAS、replay、snapshot 与异常 wire |
| 5 | [harness-storage-runtime.md](harness-storage-runtime.md) | HarnessStore 事务/锁序、7 表、Work wake 协议与 Redis overlay |
| 6 | [backend-implementation-design.md](backend-implementation-design.md) | `share` / `core` / `web` 的 composition root 与 HTTP 边界 |
| 7 | [storage-models.md](storage-models.md) | 全应用表结构；Harness 精确 7 表 |
| 8 | [frontend-implementation-design.md](frontend-implementation-design.md) | Chat defaults、BranchDraft、first send、batch、replay 与 Pane 门禁 |
| 9 | [harness-capability-wiring.md](harness-capability-wiring.md) | BranchSettings → Resolver → Model/Tool Gateway 装配 |
| 10 | [prompt-cache-usage-cost.md](prompt-cache-usage-cost.md) | cache control、usage/cost 冻结与 metadata |
| 11 | [s3-presign.md](s3-presign.md) | S3 预签名直传与直下载 |
| 12 | [comfyui-workflow-api.md](comfyui-workflow-api.md) | ComfyUI 工作流与运行 API |
| 13 | [environment-daemon-gateway.md](environment-daemon-gateway.md) | Environment registry、Daemon v2 与 Resource 边界 |
| 14 | [prompt-to-resource.md](prompt-to-resource.md) | Command → Turn → Resolver → Model → Tool → Resource 事实链 |
| 15 | [e2e-regression.md](e2e-regression.md) | E2E case、开关、验证与报告 |

## 贯穿约束

- Harness 单轨协议：恰好 5 个 Harness 基础模块（`harness-tool` / `harness-runtime` / `harness-plugin` / `harness-runtime-spring` / `harness-daemon`）、3 个 processor（Thread/Model/Tool）、3 个 Work target（THREAD/MODEL/TOOL）、7 张表、9 种 `EntryType`、1 个 Agent Loop；受信任插件位于独立 `plugins/*` 构建模块。
- `harness-runtime` 是纯 Java 领域模块，拥有 Thread 状态机与 processor；`harness-plugin` 提供构建期注册、启动时冻结的插件 API；`harness-runtime-spring` 只做 Store/Work/Redis 适配；`core` 提供 Catalog、TurnResolver、Model/Tool Gateway 与 Environment/Chat 应用能力，不写 `harness_*` 表；Goal 由 `plugins/goal` 提供；`web` 是生产组合根。
- PostgreSQL 是唯一 durable truth；`harness_work` 是唯一调度 mailbox（`wake_version` + lease）；Redis/NOTIFY 永非 correctness truth。
- 所有 Runtime id/sequence/revision 在 HTTP wire 上是 strict decimal strings：id 为 `[1-9][0-9]*`，revision 为 `0|[1-9][0-9]*`。
- Thread `nextCommandSequence` 从 1 开始；每次可见状态变化 `revision` 恰好 +1。
- 8 类 command：`USER_MESSAGE` / `CUSTOM_MESSAGE` / `SET_ENVIRONMENT` / `SET_AGENT` / `SET_MODEL` / `SET_THINKING_LEVEL` / `SET_ACTIVE_TOOLS` / `SET_YOLO`；前端 diff 顺序固定为 ENV → AGENT → MODEL → THINKING → TOOLS → YOLO，再追加 `USER_MESSAGE`。
- MOVE_HEAD 只允许**同 Session** 历史 Entry，revision CAS、要求 quiescent 且无 queued command；不能指向 `continueModel=true` 的 TURN_END。
- Stop 先按 `(threadId, stopRequestId)` durable key 精确 replay，再做 revision CAS；`STOPPED` / `IDLE`（no-op）/ `REPLAYED` 三态。
- Tool approval 输入 `ALLOW` / `DENY`，durable 值为 `ALLOWED` / `DENIED`；`ALLOWED` 恢复执行，`DENIED` 终结失败；YOLO 在加载权限 settings/evaluator 之前直接短路 Allow。
- AgentDefinitionConfigDTO 的 `tools`/`skills`/`subagents` 三个列表必填：`tools`/`skills` 是短名集合，`subagents` 是 Agent 名称 allowlist（引用锁定，被引用 Agent 不可删除）；branch `activeTools` 由 config.tools + skills 非空时的内部 `load_skill` + subagents 非空时的内部 `task` 派生（子 Agent 在最大深度处省略 `task`）。
- `load_skill` 与 `task` 是两个内部 `PLATFORM` Tool，绝不出现在 `GET /api/ai/catalog/tools`；`task` 以普通 durable Harness Thread 运行子 Agent（ROOT 冻结 `subagentContext`），复用 ToolInvocation/approval/stop/Work 与既有 Thread，无新表/新状态机/新调度器。
- Tool terminal success 的 `effects` 与 `SUCCEEDED` 同行原子持久化且 terminal immutable；唯一 `ToolOutcomeAppender` 按 `CUSTOM effects -> Tool Result` 顺序推进 Entry/head。
- Durable compaction 复用 MODEL Work 与现有 processor：阈值或一次 overflow recovery 启动 `TURN_START(COMPACTION) -> COMPACTION -> TURN_END`；split turn 使用 HISTORY/TURN_PREFIX，内部 turn 不进入后续 Provider Context 或前端 transcript。
- Environment 以 canonical bounded 小写 `environmentName` 作为唯一动态路由身份，不持久化独立环境资源；daemon wire 是 v2。
- Resource 安全边界：`data:` URI 才自动媒体预览；http/https 保持显式直连链接；file/s3 只按内容身份投影到同源 `GET /api/ai/runtime/resources/{sha256}?mediaType&size&name` 下载（attachment + nosniff），未知/不完整身份不渲染链接。

## 维护规则

| 规则 | 说明 |
| --- | --- |
| 状态准确 | Runtime 以 [harness-runtime-architecture.md](harness-runtime-architecture.md) 与 [harness-runtime-contracts.md](harness-runtime-contracts.md) 为事实源；存储与 Work/Redis 以 [harness-storage-runtime.md](harness-storage-runtime.md) 为事实源 |
| 上下文无关 | 文档可独立阅读，不依赖讨论过程；不写否决项、迁移历史或旧方案 |
| 分层清晰 | 架构、Runtime、存储、前后端实现分别维护 |
| 当前态 | 只描述当前实现，目录、API 与 E2E case 以仓库现状为准 |
