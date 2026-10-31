# Harness Agent Builtin

`harness-agent-builtin` 是第一方 Builtin Agent Driver。它实现 [Harness Runtime](harness-runtime.md) 的 `AgentDriver` 契约，拥有 Builtin 分支的模型生成状态、Turn 解析、请求物化与响应物化，以及 Builtin 专属的自动压缩策略。Runtime 只保留与执行后端无关的公共内核；Builtin 的模型调用、流式 checkpoint、重试与模型组推进都在本模块完成。

模块生产依赖只有 `kk-studio-common`、`kk-studio-harness-model` 与 `kk-studio-harness-runtime`，不依赖 Spring、JDBC、HTTP 或模型 SDK，也不依赖 Platform。依赖声明见 [`pom.xml`](../../harness/agent-builtin/pom.xml)，方向由仓库检查 [`check.mjs`](../../scripts/dev/verify/repository/check.mjs) 守护。

## Driver 边界

[`BuiltinAgentDriver`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/driver/BuiltinAgentDriver.java) 由组合根直接装配，使用分支 `BranchSettings.model` 中的 `ModelSelection` 选择模型。Driver 方法在 Runtime 已开启的事务内执行，不开启新事务、不执行外部 I/O；Provider I/O 由 MODEL Work 路径承担。

| 方法 | Builtin 行为 |
| --- | --- |
| `resolveTurn` | 调用 [`TurnResolver`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/port/TurnResolver.java)，把 Resolved 结果校验为冻结 `ModelRequestSpec`、上下文窗口、输出预算与能力；Accepted 由 Runtime 调用 `install` |
| `install` | 在同一事务创建 `READY` 的 [`ModelInvocation`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/model/ModelInvocation.java) 并请求 MODEL Work |
| `lockState` | 按 open `TURN_START` 锁定当前 generation；terminal 但尚未物化时为 `RESPONSE_PENDING`，否则为 `ACTIVE` 或 `NONE` |
| `applyResponse` | 经 [`ModelOutcomeAppender`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/processor/ModelOutcomeAppender.java) 把终态物化为 Assistant / Error / Compaction / ToolBatch 历史 |
| `completeToolBatch` | 校验 generation 已附着到当前 Assistant head，并删除临时 ModelInvocation |
| `prepareAutomaticCompaction` | 委托 [`AutomaticCompactionPlanner`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/compaction/AutomaticCompactionPlanner.java) |
| `stop` | 把在途生成收敛为 `ASSISTANT_ERROR(CANCELLED)` 或携带已冻结流式内容的 `ASSISTANT_ABORTED` barrier，并取消 generation |

Builtin 的私有持久化端口是 [`BuiltinStore`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/store/BuiltinStore.java)。它只把调用方已有的 Runtime 事务投影为窄 typed 视图，模型状态 CRUD 只存在于该接口；生产实现与 Runtime transaction 共享同一连接、数据库时间与锁序跟踪，视图本身绝不开启事务。

## 模型生成状态

`ModelInvocation` 是一条 Thread 至多一行的当前生成事实，保存冻结请求、状态、`attempt`、流式 checkpoint、终态结果、失败 attempt 审计、可选的 Provider replay 与模型组进度。状态机为：

```text
READY   -> RUNNING（准入通过）/ FAILED（准入拒绝或请求物化失败）/ CANCELLED
RUNNING -> SUCCEEDED / FAILED / CANCELLED / UNKNOWN / READY（重试）
```

`attempt` 只在 `READY -> RUNNING` 上 +1，且该转换在请求发出之前持久化。失败 attempt 以 [`ModelAttemptFailure`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/model/ModelAttemptFailure.java) 追加审计，下一次 attempt 用同一冻结请求重新发起；`OVERFLOW` 与 `CANCELLED` 不进入可重试失败审计。

[`ModelRequestSpec`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/model/ModelRequestSpec.java) 冻结一次调用不可变的请求契约：

```text
providerType / providerConnectionGenerationId / model / variant / outputTokens
systemInstruction / toolBindings / subagentBindings / cacheControl
groupExecution?
```

完整历史、Provider tools、YOLO、凭证与端点都留在各自事实源里；Skill 的名称、描述与稳定路径已写入 `systemInstruction`。[`ModelRequestMaterializer`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/model/ModelRequestMaterializer.java) 每次 attempt 从不可变 `EntryPath` 与冻结 Spec 纯内存重建供应商中立的 `ProviderRequest`。[`ProviderCacheControl`](../../harness/model/src/main/java/fun/fengwk/kkstudio/harness/model/cache/ProviderCacheControl.java) 冻结留存档位（`NONE` / `SHORT` / `LONG`）与会话 key（Session UUID），压缩回合固定 `NONE`；协议映射由 [Harness Model](harness-model.md) 承担。

[`ProviderMessageProjector`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/request/ProviderMessageProjector.java) 把语义上下文投影为 `ProviderMessage`，投影只影响本次请求，从不改写 durable 历史：

- 会话角色只有 `USER`、`ASSISTANT`、`TOOL`，系统指令由顶层 `systemInstruction` 承载。
- native 资格按本次 `toolBindings` 与调用冻结的 Environment name 判断；工具未绑定、未知或环境变化时，调用与结果降级为 USER `Previous context:` 语义上下文。降级项的动作文本取调用成功时冻结的 `historyAction`，没有时用逐字保留 arguments 的中性描述。
- 需要改写的 assistant 消息只在本次请求中放弃不兼容的 opaque replay；未配对的 native 调用在本次请求中合成 error 结果 `No result provided`，不写历史。
- 编辑类结果的模型可见 diff 从持久化的 `details.editReview` 纯内存派生；投影、压缩预算估算与请求预览共用同一派生内容。

[`ModelRequestSpecJsonCodec`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/model/ModelRequestSpecJsonCodec.java) 是冻结 Spec 的严格确定性 JSON codec，拒绝重复键、尾随内容与未声明字段。

## MODEL Work 与流式执行

[`ModelProcessor`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/processor/ModelProcessor.java) 消费 Dispatcher 已 claim 的 MODEL Work。进程内执行治理组合复用 Runtime 公开的 [`ClaimedExecutionSupport`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ClaimedExecutionSupport.java)：所有权校验、按 invocation 的准入与 execution 登记、派发前的租约 margin、本地取消 / 关闭与活跃执行查询都由骨架提供，本类只实现 Builtin 语义；事务内最终所有权围栏失败抛唯一的 [`ClaimLostSignal`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ClaimLostSignal.java)。短事务只锁 own Thread -> ModelInvocation -> Work，不取执行树锁、Session 锁或祖先锁。它先物化请求并调用 [`ModelGateway`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/port/ModelGateway.java) 的 `admit`：

- `Busy` 按 `retryAfter` 重排；
- `Rejected` 与请求物化失败的确定性问题转 `FAILED`，都不曾发出请求；
- `Admitted` 后先持久化 `READY -> RUNNING`，这一步同时是陈旧启动围栏。

随后 [`ModelExecution`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/processor/ModelExecution.java) 在独占虚拟线程上阻塞执行，结束后提交终态；Stop、租约丢失与关闭只需 interrupt。旧租约过期的 RUNNING 按当前 attempt 计入 [`InvocationRetryPolicy`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/retry/InvocationRetryPolicy.java)：允许重试则记一条失败 attempt 回到 `READY`，预算耗尽才终止，同一 attempt 绝不重放。

流式增量在内存合并，按 [`ModelStreamConfig`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/processor/ModelStreamConfig.java) 的窗口推送有损增量；累计文本按独立节奏落库为 checkpoint，提交成功后才发布 checkpoint 事件，集成方也可以请求当前 owner 立即追帧。默认值为 50ms 推送窗口、256 个事件、64 KiB 增量载荷与 1500ms checkpoint 间隔。旧 attempt、租约丢失或 Stop 竞态立即停止发布，绝不补写未提交批次。

成功响应在落库前先经 [`ModelResponseValidator`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/processor/ModelResponseValidator.java) 校验 canonical 不变量，再由 [`ModelResponsePlanner`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/processor/ModelResponsePlanner.java) 规划 ToolBatch：COMPLETE 下的有效调用为 `READY`，schema 不合法为 `FAILED(INVALID_TOOL_ARGUMENTS)`，未知工具为 `FAILED(UNKNOWN_TOOL)`，LENGTH 截断为 `FAILED(MODEL_OUTPUT_TRUNCATED)`。每个工具调用的历史动作由 [`ToolHistoryActions`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/processor/ToolHistoryActions.java) 在持久化前经 Tool 历史渲染器冻结；任何缺失或失败都退化为不冻结，绝不阻断模型调用。

[`ModelOutcomeAppender`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/processor/ModelOutcomeAppender.java) 只把已提交的终态物化为不可变历史 Entry，并维护 ModelInvocation 的 attach-then-delete；它不推进 Thread head、不 request Work、不 complete claim，这些仍由 Runtime 掌握。失败 attempt 由 [`ModelAttemptFailureAppender`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/processor/ModelAttemptFailureAppender.java) 按顺序写成透明的 `MODEL_ATTEMPT_FAILURE` Entry，[`ModelAttemptMaterialization`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/history/ModelAttemptMaterialization.java) 在删除临时行前校验结果路径已精确物化每个失败 attempt。[`BuiltinHistoryPayloadMapper`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/history/BuiltinHistoryPayloadMapper.java) 把 Provider 响应投影为公共 Assistant payload，保留 stop reason、七维 usage、可选解码时长、实际模型、请求时刻与 service tier。

费用不是 durable 事实：读取时按当前目录定价用 [`ModelCost`](../../harness/model/src/main/java/fun/fengwk/kkstudio/harness/model/ModelCost.java) 精确现算（`BigDecimal`，总额等于六个互斥计费类别之和）。[`ModelPricing`](../../harness/model/src/main/java/fun/fengwk/kkstudio/harness/model/ModelPricing.java) 由基础费率与按顺序应用的覆盖、倍率规则组成；协议映射由 [Harness Model](harness-model.md) 承担。

## 模型组

模型组在逻辑调用开始时把组名、组版本、有序成员执行快照、降级状态码与冷却秒数冻结进 [`ModelGroupExecution`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/model/ModelGroupExecution.java)。成员切换只使用冻结快照，绝不重新读取 Catalog 混入新配置。当前成员与成员内普通尝试次数由 [`ModelGroupProgress`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/model/ModelGroupProgress.java) 推进；全局 `ModelInvocation.attempt` 仍是所有真实上游 attempt 的审计序号。

[`ModelGroupFailureDecider`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/processor/ModelGroupFailureDecider.java) 是唯一失败决策点：`CANCELLED` 与 `OVERFLOW` 保留专用终止/压缩语义；命中组配置的 `fallbackStatusCodes` 或普通重试预算耗尽时推进到下一成员；否则重试当前成员。成员冷却状态在每次候选选择时读取并写入 `BuiltinStore`，不进入请求 Spec。

## 自动压缩

[`AutomaticCompactionPlanner`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/compaction/AutomaticCompactionPlanner.java) 是 Builtin 的自动压缩策略，按 owned `HISTORY` -> hard overflow -> eligible threshold 的顺序计算下一次压缩。它复用 Runtime 的 [`CompactionPlanner`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionPlanner.java) 与 [`CompactionConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionConfig.java)，不复制阈值判定；无压缩义务时返回 `null`。Runtime 只通过 `AgentDriver.prepareAutomaticCompaction` 调用本策略，不反向依赖 Builtin 的压缩实现。压缩回合的父子结构、子 Thread 创建与 `SETTLE_OWNER_TURN` 结算仍由 [Harness Runtime](harness-runtime.md#压缩) 掌握。

## 私有查询

[`BuiltinRequestQuery`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/query/BuiltinRequestQuery.java) 是 Builtin debug 专属查询：在同一 Runtime 事务内锁定 Thread，读取当前 open Turn 的冻结请求，返回 [`BuiltinRequestView`](../../harness/agent-builtin/src/main/java/fun/fengwk/kkstudio/harness/builtin/query/BuiltinRequestView.java)（generation id、attempt 与 `ModelRequestSpec`）。没有当前 generation 时返回 empty，公共 ThreadSnapshot 不携带私有 request spec。

## 包架构

本模块的全部生产包都在 `fun.fengwk.kkstudio.harness.builtin` 之下，与 Runtime 的包根严格分离；跨模块不存在同名 Java 包，由 [`check.mjs`](../../scripts/dev/verify/repository/check.mjs) 的 main 包唯一性守卫校验。

| 包名 | 职责与边界 |
| --- | --- |
| `fun.fengwk.kkstudio.harness.builtin.compaction` | Builtin 自动压缩策略；复用 Runtime 的阈值与规划纯函数，不拥有压缩回合结构 |
| `fun.fengwk.kkstudio.harness.builtin.driver` | `BuiltinAgentDriver`：Turn 解析、generation 锁定、响应物化、ToolBatch 收尾与 Stop |
| `fun.fengwk.kkstudio.harness.builtin.history` | Builtin 响应到公共历史的投影，以及 failed attempt 的精确物化校验 |
| `fun.fengwk.kkstudio.harness.builtin.model` | `ModelInvocation`、`ModelRequestSpec` 及其严格 codec、请求物化、失败审计、流式 checkpoint 与模型组冻结事实 |
| `fun.fengwk.kkstudio.harness.builtin.port` | `TurnResolver` 在调用方事务内同步解析，`ModelGateway` 在事务外执行 Provider I/O |
| `fun.fengwk.kkstudio.harness.builtin.processor` | MODEL Work 的 Processor / Execution、流式节奏、响应校验与规划、结果物化与模型组决策 |
| `fun.fengwk.kkstudio.harness.builtin.query` | 当前 open Turn 冻结请求的只读 debug 投影 |
| `fun.fengwk.kkstudio.harness.builtin.request` | Provider 消息投影与解析结果契约校验，都是纯函数 |
| `fun.fengwk.kkstudio.harness.builtin.store` | Builtin 私有模型状态存储端口；只投影调用方事务，不开启新事务 |

本模块只依赖 Runtime 的公开 API：`ClaimLostSignal`、准入票据与执行骨架提供 Driver 所需的公共能力，其内部登记结构保持私有。包名分离使越界使用无法通过编译。`fun.fengwk.kkstudio.harness.builtin` 下的 Runtime 内置 Contributor、`goal` / `input` / `subagent` / `environment` 工具属于 Kernel，不在本模块。

## 源码与测试

- 本模块测试只验证 Builtin 自身：Driver、私有状态、模型状态与请求物化、流式与重试、模型组、自动压缩和响应物化。测试入口都在 [`harness/agent-builtin/src/test`](../../harness/agent-builtin/src/test/java/fun/fengwk/kkstudio/harness/builtin/) 下。
- 测试通过 runtime test-jar 复用中立 kernel fixture（`InMemoryHarnessStore`、Store 契约基类、`FakeAgentDriver`）；本模块的 test-jar 仍导出 Builtin 私有模型契约/fixture，供 `infra` 等消费者使用，但 Runtime 内核测试和共享 fixture 不回流到本模块。
- 模块 `verify` 的 native `jacoco:check` 只校验本模块的 5 个关键类，逐类 `LINE` 覆盖率不低于 90%。Runtime 的 29 个关键类由 runtime 自己的 check 负责，不存在跨模块 Ant report。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Runtime](harness-runtime.md)、[Harness Model](harness-model.md)、[Harness Infra](harness-infra.md)、[Platform](platform.md)。
