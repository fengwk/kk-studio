# 仓库质量审计

本文记录当前仓库的代码质量基线、工具契约、事件驱动边界、前端模块职责和验证入口。结论只描述当前生效实现；代码、自动化测试与本文必须同步维护。

## 1. 结论

- 当前没有已知的高、中优先级正确性或架构问题。
- Environment 固定工具目录不扩张；9 个 coding tool 的模型可见 schema 与 pi-base 对齐，2 个 MCP bridge 维持 kk-studio 的既有集成边界。
- `task` 与 Harness one-shot 的 durable Thread 观察由 PostgreSQL revision 通知唤醒；无事件时不重复读取 snapshot。
- Canvas Function 的 start/checkpoint/terminal 都在事务内前进 Canvas version 并发布 node patch；前端不轮询 Function run。
- Bound Chat 与 Canvas Bound 共用 branch draft/batch/view 编排；Composer 与 Canvas controller 的 timer/ref-heavy 状态机已拆为独立 hooks。
- 保留的周期等待只用于 lease/heartbeat、安全恢复、外部系统状态或 Environment liveness，不承担可由现有内部事件替代的主流程状态同步。

## 2. Tool 契约

### 2.1 Environment 固定目录

`EnvironmentToolCatalog` 固定暴露以下 11 个工具。所有 schema 都拒绝未知字段；相对路径以 invocation `workdir` 为基准，最终 canonical path 必须位于 Environment Root 内。

| Tool | Required | Optional | 默认/上限 | 当前实现与 pi-base 关系 |
| --- | --- | --- | --- | --- |
| `read` | `path` | `workdir`, `offset`, `limit` | offset=1；limit=200，最大 2000 | 文本窗口、编码/BOM、2000 字符单行截断与 pi-base 对齐；binary 通过 kk-studio Resource result 外部化；LSP header 反映可选 bridge 配置 |
| `write` | `path`, `content` | `workdir` | 无隐式 cwd 之外的路径 | 保留现有编码/BOM，父目录按需创建，同文件 mutation 串行 |
| `edit` | `path`, `old_string`, `new_string` | `replace_all`, `workdir` | `replace_all=false` | 在 LF 归一空间精确匹配；保留未修改区域 CR/LF/CRLF；拒绝不唯一、重叠和 no-op，与 pi-base 编辑语义对齐 |
| `bash` | `command` | `workdir`, `timeout_seconds` | 120s，最大 3600s | 流式 partial、exit code、超时、取消和进程树终止；本地 Daemon 明确使用 bash |
| `grep` | `pattern`, `path` | `workdir`, `include`, `ignore_case`, `literal`, `multiline`, `limit`, `timeout_seconds` | limit=100；timeout=15s | Java NIO/regex 实现，不依赖宿主 `rg`；通过 JGit 遵守 `.gitignore`、跳过 binary、支持取消 |
| `find` | `pattern`, `path` | `workdir`, `limit`, `timeout_seconds` | limit=1000；无默认 timeout | `path` 必填，不存在隐式搜索根；Java glob、JGit `.gitignore`、稳定排序与取消 |
| `lsp_goto_definition` | `path`, `line` | `workdir`, `character` | character=0；2min | schema 与 pi-base 对齐；通过可选本机 LSP bridge，缺失时明确失败 |
| `lsp_workspace_symbols` | `path`, `query` | `workdir`, `limit` | limit=50，最大 500；2min | schema 与 pi-base 对齐；通过同一 LSP bridge |
| `lsp_java_decompile` | `path`, `target` | `workdir` | 2min | 优先 LSP bridge；可解析 class 目标允许 `javap` fallback |
| `mcp_list_tools` | 无 | `server` | 30s | kk-studio 固定 MCP bridge；返回 server 状态与 READY tool schema |
| `mcp_call_tool` | `server`, `tool`, `arguments` | 无 | 5min | kk-studio 固定 MCP bridge；`arguments` 必须为 object，保留 upstream text/JSON/error |

`apply_patch` 不在当前目录中。pi-base 虽提供该 grammar tool，但 kk-studio 当前约束是不新增工具；自动化测试固定断言目录恰为上述 11 项。

主要契约测试：

- `harness/tool/src/test/java/fun/fengwk/kkstudio/harness/tool/EnvironmentToolCatalogSchemaTest.java`
- `harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/CodingToolsTest.java`
- `harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/CodingToolsEdgeTest.java`
- `harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/mcp/McpBridgeToolsTest.java`

### 2.2 Platform 与 plugin tools

| Tool | Schema | Visibility / side effect | 当前边界 |
| --- | --- | --- | --- |
| `task` v1 | required `subagent_type`, `prompt`; optional `maxTurns`, `session_id` | internal `PLATFORM` / `NON_IDEMPOTENT` | schema、resume、并发、深度、result envelope 与 pi-base 对齐；durable Thread 观察由 revision event 驱动，1s status heartbeat 复用缓存 snapshot |
| `load_skill` v1 | required `name` | internal `PLATFORM` / `READ_ONLY` | kk-studio 既有能力；只加载当前 invocation 已选且具有 Environment body 的 skill，取消会中断 pending future |
| `create_goal` v2 | required `objective`; optional `tokenBudget` | selectable plugin / `IDEMPOTENT` | 模型可见 schema 与 pi-base 对齐；写入当前 branch durable snapshot |
| `get_goal` v2 | 无参数 | selectable plugin / `READ_ONLY` | 读取当前 branch 最新 Goal |
| `update_goal` v2 | required `status`, `reason`; status=`complete|blocked` | selectable plugin / `IDEMPOTENT` | 模型可见终态 schema 与 pi-base 对齐 |

Goal 插件只实现上述模型工具的 durable snapshot 协议，不实现 pi-base host 侧 `/goal` 命令、pause/resume 或自动 continuation；这些不属于当前 kk-studio ToolCatalog。

主要契约测试：

- `harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/subagent/TaskToolTest.java`
- `harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/skill/LoadSkillToolTest.java`
- `plugins/goal/src/test/java/fun/fengwk/kkstudio/plugin/goal/GoalPluginTest.java`

## 3. 事件驱动与周期等待边界

| 路径 | 当前机制 | 是否读取内部 durable 状态 | 保留理由 |
| --- | --- | --- | --- |
| Harness work dispatch | PostgreSQL `LISTEN/NOTIFY` 唤醒 + 低频 periodic safety poll | safety poll 会 claim work | NOTIFY 不是 durable queue；周期兜底用于启动、丢通知和恢复 |
| TaskTool child observation | `HarnessThreadChangeSource` revision/resync + registry descendant signal | 仅首次与 revision wake 读取 snapshot | 主流程事件驱动；取消主动 wake；heartbeat 使用缓存 |
| Harness one-shot | `HarnessThreadChangeSource` revision/resync | 仅首次与 revision wake 读取 snapshot | 100ms timed wait 只检查 caller active/deadline，不读取 snapshot |
| Canvas graph/run | `canvas_version` PostgreSQL notification + `/changes` patch/snapshot | 前端按 version event 拉取 | Function run 不再有 800ms polling |
| Application event connection | WebSocket callback + reconnect backoff + 20s heartbeat | 否 | transport liveness 与断线恢复 |
| Work heartbeat | fixed-rate lease renew | 是，更新 work lease | 分布式 ownership 协议，不是 UI 状态轮询 |
| Environment 列表 | 页面可见时 10s React Query refresh | 是 | Daemon/进程 liveness 边界；当前 wire 没有 Environment collection revision |
| ComfyUI / Seedance / OpenCLI | adapter 专用 executor 中按外部 API 状态等待 | 否（外部系统） | 外部平台没有可复用的 kk-studio 事件源；均有 timeout、取消和测试 |
| PostgreSQL listener | `getNotifications(5s)` + 1s reconnect backoff | 仅收到通知后读当前 cursor | socket wait 与重连，不是固定查询业务表 |

不得重新引入以下模式：

- Task/one-shot 无事件时固定读取 Thread snapshot；
- Canvas RUNNING node 的固定 `refetchInterval`；
- 用 heartbeat 替代 durable revision 或 version 事实。

## 4. 前端职责边界

| 模块 | 职责 |
| --- | --- |
| `useBoundBranchPanel` | Bound Thread controller、branch base/draft、queued SET_* projection、原子 message batch、Thread rebind fail-closed |
| `useBoundThreadPanelViews` | Conversation/Debug 互斥视图、system prompt preview、Event detail 与公共 Footer/transcript 投影 |
| `useComposerFocus` | focus retry、Escape、interaction takeover、pending settle 后恢复与 timer cleanup |
| `useComposerSubmissionSettle` | submitted draft、失败恢复、detached upload 挂起与释放 |
| `useFunctionConfigSync` | Function config debounce、并发 flush 去重与失败保留 |
| `useCanvasFunctionRun` | start/cancel、本地 basis-CAS 投影与 response-lost fallback |
| `useCanvasTransformBatch` | transform debounce、in-flight owner、失败恢复、显式 group decision |
| `useCanvasUploadPipeline` | hash/reserve/PUT/complete、进度、alias 生命周期与 Resource node command |
| `useCanvasController` | Canvas query/queue 与上述 hooks 的 façade 编排；不内联 timer/ref-heavy 子状态机 |

Bound Thread、Composer 与 Canvas 的新状态机必须优先在独立 hook 测试中举证，再由场景组件测试证明 wiring；禁止仅用源码字符串或行数断言代替行为验证。

## 5. 当前验证入口

| 范围 | 命令 |
| --- | --- |
| Java 全仓 | `env JAVA_HOME=$JAVA_HOME_21 mvn test -B -fae` |
| Java 格式/架构 | `env JAVA_HOME=$JAVA_HOME_21 mvn validate` |
| 前端单测 | `npm --prefix frontend test` |
| 前端 lint/build | `npm --prefix frontend run lint && npm --prefix frontend run build` |
| 前端覆盖率 | `npm --prefix frontend run coverage` |
| 免费 API E2E | `./scripts/e2e.sh` |
| 免费 UI E2E | `./scripts/e2e.sh --ui` |
| 完整免费 Canvas | 按 [e2e-regression.md](e2e-regression.md) 启动 `deploy/test` 后执行 `--with-canvas-function --ui` |
| 真实模型 | 只允许显式 `--real`，且付费 case 硬校验 `minimax/MiniMax-M2.7` |

当前构建仍可能报告 Vite 单 chunk 大于 500kB 的提示；路由、Canvas 与 Mermaid 已按现有边界拆包，该提示不影响正确性门禁。新增重量级依赖或同步首屏模块时必须重新评估 chunk。

## 6. 当前验收证据

当前基线已完成以下验证：

- Java：13 个 Maven 模块全部通过，2837 tests，0 failure/error/skip；Spotless、Checkstyle 与 JaCoCo report 均成功。
- 前端：142 test files / 1109 tests；lint 与 production build 通过。
- 前端全局覆盖率：statements 87.79%、branches 82.19%、functions 88.82%、lines 87.83%。
- 关键 Java 路径：
  - `ChangeGate` line/branch 100%；
  - `TaskTool` line 98.2%；
  - `HarnessOneShotService` line 96.9%；
  - `SubagentRunRegistry` line 98.3%；
  - `CanvasFunctionRunTransactions.checkpoint` line 100%、branch 90%；
  - `EditTool.run` line 95.9%、branch 92.3%。
- Node E2E API：分层执行 L1 65 + L2 4 + L3 1 + L4 3，覆盖注册矩阵 73/73。
- Playwright UI：免费矩阵 35/35，Environment → Workspace Tool 场景 1/1，合计 36/36。
- 所有真实 Provider E2E 均由 runner 在执行前硬校验 `minimax/MiniMax-M2.7`。
