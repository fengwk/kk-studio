# 模型用量与计价

上层职责见[系统设计](../system-design.md)。

读取历史记录时，后端使用实际模型、请求时间、最终 service tier、请求体条件快照和当前目录价格投影费用；费用不写回模型历史。

## 请求体条件定价

价格规则条件支持可选的 `requestBody` 叶子，对最终编码后实际发送给 Provider 的 JSON 请求体做精确标量比较：

```json
{
  "requestBody": {
    "path": "/speed",
    "equals": "fast"
  }
}
```

`path` 是 RFC 6901 JSON Pointer，必须非空、以 `/` 开头、只使用 `~0`/`~1` 转义，最多 16 段且不超过 512 UTF-8 字节；空属性名合法。数组下标必须是非负规范十进制（`0` 或无前导零），不做表达式、通配符或相对路径解析。`equals` 可为 JSON string、number、boolean 或 null；两个字段必须同时出现，省略 `equals` 与显式 `equals: null` 语义不同。字符串区分大小写、不裁剪、不做类型转换，长度不超过 1024 UTF-8 字节。

字段缺失、路径穿过标量或实际值为对象/数组都表示确定不匹配；`equals: null` 只匹配实际存在的 null，不匹配缺失字段。

数字使用十进制精确比较，不经过二进制浮点：`2` 与 `2.0` 相等，`"2"` 与 `2` 不相等；规范值去尾零、不用指数，零统一为 `0`，精度最多 64 位，规范十进制 scale 在 `-18..18` 之间。比较对象是 Provider encoder 实际输出的 JSON，而不是调用方草稿、URL 或二次 preview；客户端可控字段是否影响价格由管理员配置决定，不证明上游实际 tier 或计费档位。目录编辑器以结构化 path、类型和值控件编辑该叶子，不提供原始表达式，保存前复用同一组边界校验。

编辑数字条件需要浏览器同时支持 `JSON.parse` reviver 的 `context.source` 和 `JSON.rawJSON`。读取时保留数字原文，提交时仍写为 JSON number；缺少所需能力时明确报错，不先舍入或改成字符串。

一次成功 attempt 只持久化条件指纹到布尔结果的快照：指纹是 UTF-8 `path + NUL + type + NUL + canonicalExpectedValue` 的 SHA-256 小写十六进制；规则 id、价格和实际字段值都不进入指纹。快照最多 128 个唯一条件，未知结果不写入。请求体缺失、非法、超过 32 MiB、嵌套超过 64 层或没有可用的请求体观察时，request-body 条件保持未知，不截断请求体、不解析前缀、不伪造匹配结果。`all`/`any`/`not` 继续使用 false/unknown/true 三态语义，`NOT unknown` 仍为 unknown。

历史费用只用成功记录中已持久化的快照投影。旧记录或旧 attempt 没有该快照时，涉及的 request-body 条件一律为未知，即使今天配置了相同或不同条件也不能反推；因此可能显示未知费用，而不会补零或按今天的规则重新判定。价格变化影响未来投影，缺失指纹的结果不会因当前配置而被补造。只保存哈希与布尔值，不保存请求 body、消息、header 或实际参数值。

## 分类与未知事实

`inputTokens` 只包含未缓存输入；`cacheReadTokens` 独立；`cacheWriteTokens` 已包含 `cacheWriteByTtl` 中按正整数秒拆分的写入量，拆分不是额外用量。

`outputTokens` 包含推理。`reasoningTokens` 是其可空子集，不再加进输出或总费用。独立推理价格存在时，普通输出按 `outputTokens - reasoningTokens` 计费，推理按子集计费；未独立定价时全部输出只收一次输出费用。

`providerTotalTokens` 仅记录原生总数。未报告时为 null，不从分类合成，也不要求不同协议满足同一总量方程。零表示上游明确报告零，不能表示未报告。

## 费用投影

缺少判定定价规则必需的事实、没有匹配费率，或独立推理价格与输出价格不同且非零输出缺少可信推理子集时，`ModelCost.calculate` 返回 null。平台不为该条目生成费用投影；调用方必须显示为未知，不能补成零金额。推理与输出的最终单价数值相同（不受小数 scale 影响），或输出为零时，未知推理子集不影响总额，仍可计算费用。

已知零费率或完整零用量仍可产生合法零金额。TTL 写入总量只计一次；有拆分时按对应 TTL 价格计费，没有专用 TTL 价格则使用默认写入价格。

## 协议来源

完整 usage 是成功模型响应的必要事实。Chat 必须报告 prompt/completion，Responses 必须报告 input/output，Anthropic 必须在累计 usage 中报告 input/output；缺少整份或必需计数以关联协议和模型的 `INVALID_RESPONSE` 结束，不产生零用量计费或测速。可选 reasoning 与原生 total 未报告不构成错误。明确报告的零计数仍是合法值。Chat 编码器为此始终发送 `stream_options.include_usage: true`，不提供关闭选项；旧配置字段 `openAiChatIncludeUsage` 按未知字段忽略。

Gemini 的例外来自官方 proto3：`prompt_token_count`、`candidates_token_count` 和 `cached_content_token_count` 是普通 `int32`，ProtoJSON 可省略零值。因此只要存在最新 `usageMetadata` 对象，这三个字段省略即解释为零；显式 null、负数、小数和非整数仍拒绝。整份 `usageMetadata` 缺失才是 `INVALID_RESPONSE`。`thoughtsTokenCount` 与 `totalTokenCount` 省略或 JSON null 继续表示未报告，不补字段到原始 JSON，也不从分类合成原生 total。依据是 Google `generative_service.proto` 的 `UsageMetadata`。

OpenAI Chat 与 Responses 的输出计数已包含 reasoning。Gemini 的 candidates 与 thoughts 合并一次成为内部输出；额外 hosted-tool 用量保留原始事实，不塞入普通输入输出。

Anthropic 的 `usage.output_tokens_details.thinking_tokens` 是输出的只读分解，`output_tokens` 仍是包含推理的权威输出总量。流式 usage 按顶层累计快照合并；后续同名详情覆盖前值，没有详情时保留未知。Anthropic 不提供标准原生 total。

官方依据：[Anthropic Usage 定义](https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/types/usage.py)、[Messages API 的 OutputTokensDetails](https://platform.claude.com/docs/en/api/messages)。
