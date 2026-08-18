# Prompt Cache 与 Usage/Cost 冻结

本文描述当前的提示缓存控制、usage/cost 归一化与冻结边界。一次 Provider 调用使用每次 MODEL attempt 从 `basisHeadEntryId + compact ModelRequestSpec` 重建的内存 `ProviderRequest` 与 `ProviderResponse` 生成 Assistant Entry；usage/cost 冻结在 Invocation result 与 Assistant Entry metadata 中，**不存在 usage ledger 表、聚合表或查询 API**。

## 1. 端到端链路

```mermaid
flowchart LR
    A[TurnResolver<br/>生成 compact ModelRequestSpec<br/>含 ProviderCacheControl]
    B[PromptCacheRequestFinalizer]
    C[冻结 ModelRequestSpec]
    D[MODEL claim 内 ModelRequestMaterializer<br/>重建内存 ProviderRequest]
    E[ModelProcessor / Provider Adapter]
    F[ProviderResponse<br/>usage + cost]
    G[ASSISTANT Message Entry<br/>metadata 快照 usage/cost]

    A --> B --> C --> D --> E --> F --> G
```

事实边界：

1. Resolver 生成 compact `ModelRequestSpec`，其中 `cacheControl` 的 policy 由当前行的 `ProviderFactory` capability 解析；
2. `PromptCacheRequestFinalizer` 是 turn-time cache control 的唯一生成点；
3. spec 冻结 `providerType`/model/variant/preamble/tool/skill/subagent bindings/`cacheControl`/可空 compaction；**不包含 history messages、YOLO、contextWindow 与完整 ProviderRequest**；`ModelDescriptor` 只含 providerName/modelName/inputModalities/tools/reasoning/pricing 六个字段；
4. 每次 MODEL attempt 由 `ModelRequestMaterializer` 在有效 claim 内从 `basisHeadEntryId + spec` 重建内存 ProviderRequest；
5. attempt 时按 `providerName` 读取当前 `agent_provider` 行构造 Provider；CoreModelGateway 仅按当前 `ProviderFactory` capability 规范化 cache control，其他 spec 字段保持不变，不兼容能力降级为 `none()`；
6. terminal `ProviderResponse` 的 `usage`/`cost` 冻结进 `resultJson`；
7. apply 时由 `HistoryPayloadMapper` 把 `stopReason`、`usage`、`cost` 快照进 ASSISTANT Message Entry 的 `AssistantMessageMetadata`（`turn_usage` 前端 meta 消息展示）。

## 2. Prompt Cache

### 能力与 policy

`PromptCacheCapability(mode, supportedRetentions, supportedBreakpoints)`：

| mode | 语义 |
| --- | --- |
| `UNKNOWN` / `UNSUPPORTED` | 只允许 `NONE`（无缓存控制） |
| `AUTOMATIC` | Provider 自行决定缓存命中；harness 不发送 cache hint，依赖 Provider 报告 cache 用量 |
| `AFFINITY` | harness 通过稳定哈希派生 affinity key，不依赖显式 breakpoint |
| `BREAKPOINTS` | harness 在 system 和/或 tools 上显式打 cache_control 标记 |

`PromptCacheRetention`：`NONE`（唯一对所有 mode 合法）/ `SHORT` / `LONG`。`PromptCacheBreakpoint`：`SYSTEM` / `TOOLS`。

`ProviderCacheControl(retention, affinityKey, breakpoints)` 是派发给 Provider Adapter 的不可变快照：

- `NONE` 时 affinityKey 与 breakpoints 必须为空；
- 非 `NONE` 时 affinityKey 必须非空白；
- `AFFINITY` 不携带 breakpoints；`BREAKPOINTS` 至少携带一个 breakpoint。

Finalizer 的规则：

| 情况 | 最终 control |
| --- | --- |
| retention 为 `NONE` | `ProviderCacheControl.none()` |
| capability 为 `UNKNOWN` / `UNSUPPORTED` / `AUTOMATIC` | `none()` |
| `AFFINITY` | 派生 affinity key |
| `BREAKPOINTS` | capability 与当前 SYSTEM/TOOLS 内容求交集；空交集为 `none()` |

### Affinity key

格式固定为：

```text
pc1-<SHA-256 Base64URL without padding>
```

digest 使用 `(type, nameLen:4B, name, valueLen:4B, value)` 长度前缀帧（字段值可含 NUL，避免跨字段拼接歧义），输入包括 key format version、session 前缀、Model 身份与请求前缀内容（leading system messages、tool definitions 等）。

### Provider 映射

| ProviderType | capability | adapter 形态 |
| --- | --- | --- |
| `OPENAI` | `AFFINITY`（仅 SHORT） | Chat Completions `prompt_cache_key` |
| `OPENAI_RESPONSES` | `AFFINITY`（仅 SHORT） | Responses `promptCacheKey` |
| `ANTHROPIC` | `BREAKPOINTS`（仅 SHORT + SYSTEM/TOOLS） | system/tools `cache_control` |
| `GOOGLE` | `AUTOMATIC` | 不发送显式 cache control；读 Provider cached count |

## 3. Usage 归一化

`ModelUsage` 包含七个非负 token 字段：

| 字段 | 语义 |
| --- | --- |
| `inputTokens` | 普通输入 token |
| `outputTokens` | 普通输出 token |
| `cacheReadTokens` | 从提示缓存读取 |
| `cacheWriteTokens` | 写入短期缓存 |
| `cacheWriteLongTokens` | 写入长期缓存 |
| `reasoningTokens` | reasoning/thoughts token |
| `providerTotalTokens` | Provider 原样报告的 total（独立观测值，不由前六项补造） |

前六项是互斥计费类别；负值在归一化边界失败。`rawUsageJson` 只保存 Provider usage metadata（缺失为 `{}`，必须是 JSON object 或 array）。

OpenAI Responses 兼容端点可能省略 `input_tokens_details` / `output_tokens_details` 或其中的计数字段，而 OpenAI Java SDK 将这些字段建模为必填。Adapter 在 SDK 反序列化边界仅把缺失或 `null` 的 breakdown count 补为 `0`，保留 input/output/total 与扩展字段；非 object 等畸形值仍按无效 Provider 响应失败。

## 4. Cost

`ModelCost` 是金额快照：

```text
currency
input / output / cacheRead / cacheWrite / cacheWriteLong / reasoning
total
```

`ModelPricing` 是请求使用的不可变价格快照（单价非负、multiplier 为正），由 Catalog 冻结。成本分项按 `pricePerMillionTokens * tokens / 1_000_000 * serviceTierMultiplier` 计算，scale 12、`HALF_UP`；`total` 是已舍入分项之和。聚合按 currency 分组，不跨币种相加。

## 5. 冻结边界

- terminal `ProviderResponse` 的 `usage`/`cost`/`requestId`/`serviceTier`/`rawUsageJson` 与 `stopReason` 一起写入 `harness_model_invocation.result`；
- apply 时 `HistoryPayloadMapper` 把 `stopReason`、`usage`、`cost` 快照进 ASSISTANT Message Entry 的 `AssistantMessageMetadata`；
- `provider_name`/`model_name` 等身份是写入时冻结的**名称引用**（字符串本身不变，解析发生在下一 turn / 每次 attempt），不随 Catalog 当前内容变化；
- **不存在** `harness_model_usage` 账本表、usage 聚合 API（`/usage/...`）或 settings API；前端只从 snapshot Entry 的 `turn_usage` meta 读取单次调用的 usage/cost。

## 6. Model config 与运行时解析

Model config 写入时严格校验：`limit.context`/`limit.output` 为正整数且 output 不超过 context；`abilities.tools`/`abilities.reasoning` 为 boolean；`inputModalities` 非空且只包含受支持 enum；`variants` 非空、variant id 唯一且命中 `defaultVariant`；pricing 字段完整、单价非负、multiplier 为正。Agent config 写入时校验可选择 Tool 名与 Skill 字段结构。

每次 turn 由 `DatabaseTurnResolver` 从 `BranchSettings` 读取 agent/model/environment 引用，再读取最新 Agent、Provider、Model、Variant 与 ToolCatalog，并从最新 Agent config 派生 tools/skills/subagents，结果冻结进 compact `ModelRequestSpec`（Agent/Model 修改下一 turn 生效；历史 activeTools 不限制或扩张能力；spec 不含 YOLO/contextWindow/messages）。缺失 Agent/Provider/Model/Variant、未知可选择 Tool，或 skills 所需 Environment 不可用 → `ASSISTANT_ERROR` barrier；普通 ENVIRONMENT 工具允许冻结 null/未 READY binding，在实际 start 时产生模型可见的失败 ToolResult。每次 Model attempt 由 `ModelRequestMaterializer` 在有效 claim 内从 `basisHeadEntryId + spec` 重建内存 ProviderRequest，再由 `DatabaseProviderResolutionService` 按 `providerName` 读取当前 `agent_provider` 行（providerType/baseUrl/credential/config），以当前 `ProviderFactory` 构造短生命周期 attempt-local Provider；当前行缺失时 fail closed，同名重建后解析到新行。effective cache control 按 attempt 时当前 capability 规范化。

## 7. 实现与测试入口

| 能力 | 文件 |
| --- | --- |
| Cache affinity | [`PromptCacheAffinityKeyFactory`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/cache/PromptCacheAffinityKeyFactory.java) |
| Cache finalizer | [`PromptCacheRequestFinalizer`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/cache/PromptCacheRequestFinalizer.java) |
| Cache 类型 | [`ProviderCacheControl`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/cache/ProviderCacheControl.java) |
| Usage 模型 | [`ModelUsage`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/ModelUsage.java) |
| Cost 模型 | [`ModelCost`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/ModelCost.java) |
| Entry metadata | [`HistoryPayloadMapper`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/HistoryPayloadMapper.java) |
