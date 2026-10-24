# 模型用量与计价

上层职责见[系统设计](../system-design.md)。

读取历史记录时，后端使用实际模型、请求时间、最终 service tier 和当前目录价格投影费用；费用不写回模型历史。

## 分类与未知事实

`inputTokens` 只包含未缓存输入；`cacheReadTokens` 独立；`cacheWriteTokens` 已包含 `cacheWriteByTtl` 中按正整数秒拆分的写入量，拆分不是额外用量。

`outputTokens` 包含推理。`reasoningTokens` 是其可空子集，不再加进输出或总费用。独立推理价格存在时，普通输出按 `outputTokens - reasoningTokens` 计费，推理按子集计费；未独立定价时全部输出只收一次输出费用。

`providerTotalTokens` 仅记录原生总数。未报告时为 null，不从分类合成，也不要求不同协议满足同一总量方程。零表示上游明确报告零，不能表示未报告。

## 费用投影

缺少判定定价规则必需的事实、没有匹配费率，或独立推理价格与输出价格不同且非零输出缺少可信推理子集时，`ModelCost.calculate` 返回 null。平台不为该条目生成费用投影；调用方必须显示为未知，不能补成零金额。推理与输出的最终单价数值相同（不受小数 scale 影响），或输出为零时，未知推理子集不影响总额，仍可计算费用。

已知零费率或完整零用量仍可产生合法零金额。TTL 写入总量只计一次；有拆分时按对应 TTL 价格计费，没有专用 TTL 价格则使用默认写入价格。

## 协议来源

完整 usage 是成功模型响应的必要事实。Chat 必须报告 prompt/completion，Responses 必须报告 input/output，Anthropic 必须在累计 usage 中报告 input/output；缺少整份或必需计数以关联协议和模型的 `INVALID_RESPONSE` 结束，不产生零用量计费或测速。可选 reasoning 与原生 total 未报告不构成错误。明确报告的零计数仍是合法值。

Gemini 的例外来自官方 proto3：`prompt_token_count`、`candidates_token_count` 和 `cached_content_token_count` 是普通 `int32`，ProtoJSON 可省略零值。因此只要存在最新 `usageMetadata` 对象，这三个字段省略即解释为零；显式 null、负数、小数和非整数仍拒绝。整份 `usageMetadata` 缺失才是 `INVALID_RESPONSE`。`thoughtsTokenCount` 与 `totalTokenCount` 省略或 JSON null 继续表示未报告，不补字段到原始 JSON，也不从分类合成原生 total。依据是 Google `generative_service.proto` 的 `UsageMetadata`。

OpenAI Chat 与 Responses 的输出计数已包含 reasoning。Gemini 的 candidates 与 thoughts 合并一次成为内部输出；额外 hosted-tool 用量保留原始事实，不塞入普通输入输出。

Anthropic 的 `usage.output_tokens_details.thinking_tokens` 是输出的只读分解，`output_tokens` 仍是包含推理的权威输出总量。流式 usage 按顶层累计快照合并；后续同名详情覆盖前值，没有详情时保留未知。Anthropic 不提供标准原生 total。

官方依据：[Anthropic Usage 定义](https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/types/usage.py)、[Messages API 的 OutputTokensDetails](https://platform.claude.com/docs/en/api/messages)。
