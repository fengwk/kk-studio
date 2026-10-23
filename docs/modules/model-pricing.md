# 模型用量与计价

读取历史记录时，后端使用实际模型、请求时间、最终 service tier 和当前目录价格投影费用；费用不写回模型历史。

## 分类与未知事实

`inputTokens` 只包含未缓存输入；`cacheReadTokens` 独立；`cacheWriteTokens` 已包含 `cacheWriteByTtl` 中按正整数秒拆分的写入量，拆分不是额外用量。

`outputTokens` 包含推理。`reasoningTokens` 是其可空子集，不再加进输出或总费用。独立推理价格存在时，普通输出按 `outputTokens - reasoningTokens` 计费，推理按子集计费；未独立定价时全部输出只收一次输出费用。

`providerTotalTokens` 仅记录原生总数。未报告时为 null，不从分类合成，也不要求不同协议满足同一总量方程。零表示上游明确报告零，不能表示未报告。

## 费用投影

缺少判定定价规则必需的事实、没有匹配费率，或独立推理价格缺少可信推理子集时，`ModelCost.calculate` 返回 null。平台不为该条目生成费用投影；调用方必须显示为未知，不能补成零金额。

已知零费率或完整零用量仍可产生合法零金额。TTL 写入总量只计一次；有拆分时按对应 TTL 价格计费，没有专用 TTL 价格则使用默认写入价格。

## 协议来源

OpenAI Chat 与 Responses 的输出计数已包含 reasoning。Gemini 的 candidates 与 thoughts 合并一次成为内部输出；额外 hosted-tool 用量保留原始事实，不塞入普通输入输出。

Anthropic 的 `usage.output_tokens_details.thinking_tokens` 是输出的只读分解，`output_tokens` 仍是包含推理的权威输出总量。流式 usage 按顶层累计快照合并；后续同名详情覆盖前值，没有详情时保留未知。Anthropic 不提供标准原生 total。

官方依据：[Anthropic Usage 定义](https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/types/usage.py)、[Messages API 的 OutputTokensDetails](https://platform.claude.com/docs/en/api/messages)。
