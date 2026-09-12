import { writeFileSync } from 'node:fs'
import path from 'node:path'
import {
  assert,
  assertDecimalVersion,
  envelopeData,
  expectHttpError,
  pageResults,
  sleep,
  cid,
} from '../lib/http.mjs'
import {
  acceptCommandBatch,
  approveToolInvocation,
  branchSettingsOf,
  canonicalUuid,
  chatOwner,
  createChat,
  getThread,
  getThreadSnapshot,
  listEnvironments,
  createNewThread,
  createNewSession,
  setAgentCommand,
  setModelCommand,
  snapshotEntries,
  stopThread,
  threadTarget,
  userMessageCommand,
  waitForModelContentDeltaAfterEventSubscribed,
  waitForQuiescentThread,
} from '../lib/harness.mjs'
import { registerCase, getCase } from '../lib/registry.mjs'

import {
  REAL_MODEL_DEFINITIONS,
  MINIMAX_ANTHROPIC_M3,
} from '../lib/real-models.mjs'

export {
  REAL_MODEL_DEFINITIONS,
  MINIMAX_ANTHROPIC_M3,
}

/** 最小确定性稳定前缀字节数（>= 16KiB = 16384 bytes）。 */
export const MIN_CACHE_PREFIX_BYTES = 16 * 1024

/**
 * 构造确定性稳定 prompt cache 前缀，确保 byteLength >= 16KiB。
 */
export function buildCachePrefix() {
  const paragraph =
    'Deterministic system prompt preamble for multi-turn prompt caching verification. '
    + 'The model assistant operates under structured evaluation with invariant system context across turns. '
    + 'Adhere to evaluation guidelines, preserve session continuity, and emit exact instruction markers when requested. '
    + 'Context padding line: [0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ].\n'
  const paragraphBytes = Buffer.byteLength(paragraph, 'utf8')
  const repeats = Math.ceil(MIN_CACHE_PREFIX_BYTES / paragraphBytes) + 2
  const prefix = paragraph.repeat(repeats)
  assert(Buffer.byteLength(prefix, 'utf8') >= MIN_CACHE_PREFIX_BYTES)
  return prefix
}

/**
 * 构造包含稳定大前缀与严格 marker 约束的 System Prompt。
 */
export function buildTextCacheSystemPrompt() {
  const prefix = buildCachePrefix()
  const instruction =
    'Strict Instruction: When the user message provides a marker, you must reply with that exact marker text. '
    + 'Do not call tools, do not explain, and keep the output concise.'
  return `${prefix}\n${instruction}`
}

/**
 * 从 catalog API 严格解析 model/provider：
 * - 校验 provider name / providerType / configured (必须为 true) / baseUrl (非空 string)
 * - 校验 model variant (存在于 variants 列表)
 * - 错误信息仅展示公开 DTO，杜绝泄露 secret
 */
export async function resolveRealModel(ctx, modelDef) {
  const { json: providersJson } = await ctx.call(
    'GET',
    '/api/ai/catalog/providers?pageNumber=1&pageSize=50',
  )
  const providers = pageResults(providersJson)
  const provider = providers.find((candidate) => candidate.name === modelDef.providerName)
  assert(
    provider,
    `provider ${modelDef.providerName} not found in catalog: ${safeDiagnosticJson(providers.map((p) => p.name))}`,
  )
  assert(
    provider.providerType === modelDef.providerType,
    `provider ${modelDef.providerName} type mismatch: expected ${modelDef.providerType}, got ${provider.providerType}`,
  )
  assert(
    provider.configured === true,
    `provider ${modelDef.providerName} is not configured`,
  )
  assert(
    typeof provider.baseUrl === 'string' && provider.baseUrl.trim().length > 0,
    `provider ${modelDef.providerName} has empty or missing baseUrl`,
  )

  const { json: modelsJson } = await ctx.call(
    'GET',
    '/api/ai/catalog/models?pageNumber=1&pageSize=50',
  )
  const models = pageResults(modelsJson)
  const model = models.find(
    (candidate) =>
      candidate.providerName === modelDef.providerName && candidate.name === modelDef.modelName,
  )
  assert(
    model,
    `model ${modelDef.providerName}/${modelDef.modelName} not found in catalog`,
  )
  const variants = model.config?.variants || []
  assert(
    Array.isArray(variants) && variants.some((v) => v.id === modelDef.variant),
    `model ${modelDef.providerName}/${modelDef.modelName} does not declare variant ${modelDef.variant}: ${safeDiagnosticJson(variants)}`,
  )

  if (Array.isArray(modelDef.variants)) {
    const declaredVariantIds = variants.map((v) => v.id)
    assert(
      declaredVariantIds.length === modelDef.variants.length
        && declaredVariantIds.every((v, i) => v === modelDef.variants[i]),
      `model ${modelDef.providerName}/${modelDef.modelName} variant contract mismatch: expected ${safeDiagnosticJson(modelDef.variants)}, got ${safeDiagnosticJson(declaredVariantIds)}`,
    )
  }

  return {
    providerName: modelDef.providerName,
    modelName: modelDef.modelName,
    variant: modelDef.variant,
    variants: modelDef.variants || [modelDef.variant],
    provider,
    model,
  }
}

/** 共享安全解析 minimax-anthropic/MiniMax-M3。 */
export async function requireRealMiniMaxM3(ctx) {
  return await resolveRealModel(ctx, MINIMAX_ANTHROPIC_M3)
}

/**
 * 针对当前运行时 ModelUsage 各归一化字段校验厂商特定用量代数。
 *
 * 各字段含义（依据当前各 Java StreamAccumulator 实现）：
 * - inputTokens: 互斥扣除 cached / cacheWrite 后的普通输入 token
 * - outputTokens: 互斥扣除 reasoning 后的普通输出 token
 * - cacheReadTokens: 命中的提示缓存 token
 * - cacheWriteTokens: 写入的 5m 提示缓存 token（Anthropic / OpenAI Responses）
 * - cacheWriteLongTokens: 写入的 1h 提示缓存 token（Anthropic）
 * - reasoningTokens: 推理 token（Gemini: thoughtsTokenCount; OpenAI: reasoning_tokens; DeepSeek: reasoning_tokens; Anthropic: 0L，因已计入 output_tokens 不重复计费）
 * - providerTotalTokens: 厂商报告的总 token（Anthropic 为 0L/未提供）
 */
export function assertProviderUsageAlgebra(usage, providerType, { modelName } = {}) {
  assert(usage && typeof usage === 'object', `usage must be an object: ${safeDiagnosticJson(usage)}`)

  const requiredFields = [
    'inputTokens',
    'outputTokens',
    'cacheReadTokens',
    'cacheWriteTokens',
    'cacheWriteLongTokens',
    'reasoningTokens',
    'providerTotalTokens',
  ]
  for (const field of requiredFields) {
    const value = usage[field]
    assert(
      typeof value === 'number' && Number.isSafeInteger(value) && value >= 0,
      `usage.${field} must be a non-negative safe integer, got: ${value} in ${safeDiagnosticJson(usage)}`,
    )
  }

  const {
    inputTokens,
    outputTokens,
    cacheReadTokens,
    cacheWriteTokens,
    cacheWriteLongTokens,
    reasoningTokens,
    providerTotalTokens,
  } = usage

  switch (providerType) {
    case 'google': {
      // Gemini 协议：
      // wire prompt = inputTokens + cacheReadTokens
      // wire candidates = outputTokens
      // wire thoughts = reasoningTokens
      // 不支持 cacheWrite / cacheWriteLong
      assert(cacheWriteTokens === 0, `Gemini cacheWriteTokens must be 0, got: ${cacheWriteTokens}`)
      assert(cacheWriteLongTokens === 0, `Gemini cacheWriteLongTokens must be 0, got: ${cacheWriteLongTokens}`)

      // providerTotal = prompt + candidates + thoughts = inputTokens + cacheReadTokens + outputTokens + reasoningTokens
      if (providerTotalTokens > 0) {
        const expectedTotal = inputTokens + cacheReadTokens + outputTokens + reasoningTokens
        assert(
          providerTotalTokens === expectedTotal,
          `Gemini providerTotalTokens algebra mismatch: expected input(${inputTokens}) + cacheRead(${cacheReadTokens}) + output(${outputTokens}) + reasoning(${reasoningTokens}) = ${expectedTotal}, got ${providerTotalTokens}`,
        )
      }
      break
    }

    case 'openai_response': {
      // OpenAI Responses 协议：
      // rawInputTokens = inputTokens + cacheReadTokens + cacheWriteTokens
      // rawOutputTokens = outputTokens + reasoningTokens
      // 不支持 cacheWriteLong
      assert(cacheWriteLongTokens === 0, `OpenAI Responses cacheWriteLongTokens must be 0, got: ${cacheWriteLongTokens}`)

      const wireInput = inputTokens + cacheReadTokens + cacheWriteTokens
      const wireOutput = outputTokens + reasoningTokens
      assert(
        cacheReadTokens <= wireInput,
        `cacheReadTokens (${cacheReadTokens}) must be <= wireInput (${wireInput})`,
      )
      assert(
        cacheWriteTokens <= wireInput,
        `cacheWriteTokens (${cacheWriteTokens}) must be <= wireInput (${wireInput})`,
      )
      assert(
        reasoningTokens <= wireOutput,
        `reasoningTokens (${reasoningTokens}) must be <= wireOutput (${wireOutput})`,
      )

      // providerTotal = rawInput + rawOutput = wireInput + wireOutput
      if (providerTotalTokens > 0) {
        const expectedTotal = wireInput + wireOutput
        assert(
          providerTotalTokens === expectedTotal,
          `OpenAI Responses providerTotalTokens algebra mismatch: expected wireInput(${wireInput}) + wireOutput(${wireOutput}) = ${expectedTotal}, got ${providerTotalTokens}`,
        )
      }
      break
    }

    case 'anthropic': {
      // Anthropic 协议：
      // inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens (5m), cacheWriteLongTokens (1h)
      // reasoningTokens 在 Anthropic wire outputTokens 中包含，归一化 DTO 设为 0L 避免重复计费
      assert(reasoningTokens === 0, `Anthropic reasoningTokens must be 0 in normalized DTO, got: ${reasoningTokens}`)

      // providerTotalTokens 必须恰好为 0（Wire 响应未提供 total_tokens 字段，归一化设为 0L）
      assert(
        providerTotalTokens === 0,
        `Anthropic providerTotalTokens must be 0 in normalized DTO, got: ${providerTotalTokens}`,
      )
      break
    }

    case 'openai': {
      // DeepSeek (通过 OpenAI Chat Completions 协议接入)：
      // promptTokens = inputTokens + cacheReadTokens
      // completionTokens = outputTokens + reasoningTokens
      // 不支持 cacheWrite / cacheWriteLong
      assert(cacheWriteTokens === 0, `DeepSeek/OpenAI Chat cacheWriteTokens must be 0, got: ${cacheWriteTokens}`)
      assert(cacheWriteLongTokens === 0, `DeepSeek/OpenAI Chat cacheWriteLongTokens must be 0, got: ${cacheWriteLongTokens}`)

      const wirePrompt = inputTokens + cacheReadTokens
      const wireCompletion = outputTokens + reasoningTokens
      assert(
        cacheReadTokens <= wirePrompt,
        `cacheReadTokens (${cacheReadTokens}) must be <= wirePrompt (${wirePrompt})`,
      )
      assert(
        reasoningTokens <= wireCompletion,
        `reasoningTokens (${reasoningTokens}) must be <= wireCompletion (${wireCompletion})`,
      )

      // providerTotal = wirePrompt + wireCompletion
      if (providerTotalTokens > 0) {
        const expectedTotal = wirePrompt + wireCompletion
        assert(
          providerTotalTokens === expectedTotal,
          `DeepSeek providerTotalTokens algebra mismatch: expected wirePrompt(${wirePrompt}) + wireCompletion(${wireCompletion}) = ${expectedTotal}, got ${providerTotalTokens}`,
        )
      }
      break
    }

    default:
      break
  }
}

/**
 * 安全格式化诊断用 JSON 字符串（递归脱敏后序列化）。
 */
export function safeDiagnosticJson(obj, indent = 2) {
  return JSON.stringify(sanitizeArtifact(obj), null, indent)
}

/**
 * 严格断言 Assistant Metadata Usage 七字段均为非负安全整数，且当 requirePositiveIO 为 true 时校验事实有效。
 */
export function assertAssistantUsage(
  usage,
  { requirePositiveIO = true, providerType, modelName } = {},
) {
  assert(usage && typeof usage === 'object', `usage must be an object: ${safeDiagnosticJson(usage)}`)
  const requiredFields = [
    'inputTokens',
    'outputTokens',
    'cacheReadTokens',
    'cacheWriteTokens',
    'cacheWriteLongTokens',
    'reasoningTokens',
    'providerTotalTokens',
  ]
  for (const field of requiredFields) {
    const value = usage[field]
    assert(
      typeof value === 'number' && Number.isSafeInteger(value) && value >= 0,
      `usage.${field} must be a non-negative safe integer, got: ${value} in ${safeDiagnosticJson(usage)}`,
    )
  }

  if (requirePositiveIO) {
    if (providerType === 'anthropic') {
      const totalInput =
        usage.inputTokens
        + usage.cacheReadTokens
        + usage.cacheWriteTokens
        + usage.cacheWriteLongTokens
      assert(
        totalInput > 0,
        `inputTokens + cacheReadTokens + cacheWriteTokens + cacheWriteLongTokens must be > 0: ${safeDiagnosticJson(usage)}`,
      )
      assert(
        usage.outputTokens > 0,
        `outputTokens must be > 0: ${safeDiagnosticJson(usage)}`,
      )
      assert(
        usage.providerTotalTokens === 0,
        `Anthropic providerTotalTokens must be 0: ${safeDiagnosticJson(usage)}`,
      )
    } else if (providerType === 'openai_response') {
      const totalInput =
        usage.inputTokens
        + usage.cacheReadTokens
        + usage.cacheWriteTokens
        + usage.cacheWriteLongTokens
      assert(
        totalInput > 0,
        `inputTokens + cacheReadTokens + cacheWriteTokens + cacheWriteLongTokens must be > 0: ${safeDiagnosticJson(usage)}`,
      )
      assert(
        usage.outputTokens + usage.reasoningTokens > 0,
        `outputTokens + reasoningTokens must be > 0: ${safeDiagnosticJson(usage)}`,
      )
      // OpenAI Responses 协议允许上游缺失 total_tokens 时的 providerTotalTokens=0；若 >0 则代数检查由 assertProviderUsageAlgebra 负责
    } else if (providerType === 'openai') {
      assert(
        usage.inputTokens + usage.cacheReadTokens > 0,
        `inputTokens + cacheReadTokens must be > 0: ${safeDiagnosticJson(usage)}`,
      )
      assert(
        usage.outputTokens + usage.reasoningTokens > 0,
        `outputTokens + reasoningTokens must be > 0: ${safeDiagnosticJson(usage)}`,
      )
      // OpenAI Chat 协议允许上游缺失 total_tokens 时的 providerTotalTokens=0；若 >0 则代数检查由 assertProviderUsageAlgebra 负责
    } else if (providerType === 'google') {
      assert(
        usage.inputTokens + usage.cacheReadTokens > 0,
        `inputTokens + cacheReadTokens must be > 0: ${safeDiagnosticJson(usage)}`,
      )
      assert(
        usage.outputTokens + usage.reasoningTokens > 0,
        `outputTokens + reasoningTokens must be > 0: ${safeDiagnosticJson(usage)}`,
      )
      assert(
        usage.providerTotalTokens > 0,
        `providerTotalTokens must be > 0: ${safeDiagnosticJson(usage)}`,
      )
    } else if (!providerType) {
      // 向后兼容旧单元测试：未指定 providerType 时严格要求 inputTokens > 0 与 outputTokens > 0
      assert(
        usage.inputTokens > 0,
        `usage.inputTokens must be > 0: ${safeDiagnosticJson(usage)}`,
      )
      assert(
        usage.outputTokens > 0,
        `outputTokens must be > 0: ${safeDiagnosticJson(usage)}`,
      )
      assert(
        usage.providerTotalTokens > 0,
        `usage.providerTotalTokens must be > 0: ${safeDiagnosticJson(usage)}`,
      )
    } else {
      assert(
        usage.inputTokens + usage.cacheReadTokens > 0,
        `inputTokens + cacheReadTokens must be > 0: ${safeDiagnosticJson(usage)}`,
      )
      assert(
        usage.outputTokens + usage.reasoningTokens > 0,
        `outputTokens + reasoningTokens must be > 0: ${safeDiagnosticJson(usage)}`,
      )
      assert(
        usage.providerTotalTokens > 0,
        `providerTotalTokens must be > 0: ${safeDiagnosticJson(usage)}`,
      )
    }
  }

  if (providerType) {
    assertProviderUsageAlgebra(usage, providerType, { modelName })
  }
}

/**
 * 脱敏写入 artifact 的数据：过滤所有凭证、密钥、base URL、原始 usage JSON、replay payload、thinking 全文与 HTTP bodies。
 */
export function sanitizeArtifact(data) {
  if (data === null || data === undefined) return data
  if (typeof data === 'string') {
    const trimmed = data.trim()
    if (
      (trimmed.startsWith('{') && trimmed.endsWith('}'))
      || (trimmed.startsWith('[') && trimmed.endsWith(']'))
    ) {
      try {
        const parsed = JSON.parse(data)
        if (parsed && typeof parsed === 'object') {
          return JSON.stringify(sanitizeArtifact(parsed))
        }
      } catch {
        // 非合法 JSON，回退至纯文本脱敏
      }
    }
    let sanitized = data
      .replace(/https?:\/\/[^\s"'`<>{}]+/gi, '[REDACTED_URL]')
      .replace(/\bBearer\s+[^\s"',]+/gi, 'Bearer [REDACTED]')
      .replace(/\b(?:sk|key|token)-[A-Za-z0-9_-]{8,}\b/gi, '[REDACTED]')
      .replace(
        /\b(TEST_[A-Z0-9_]*(?:API_KEY|BASE_URL)|apiKey|credential|authorization|secret|password)=([^\s&"']+)/gi,
        '$1=[REDACTED]',
      )
    if (sanitized.length > 200) {
      sanitized = `${sanitized.slice(0, 160)}...[TRUNCATED]`
    }
    return sanitized
  }
  if (Array.isArray(data)) {
    return data.map((item) => sanitizeArtifact(item))
  }
  if (typeof data === 'object') {
    // 1. Gemini part with thought: true (禁止残留兄弟 text 与 thoughtSignature)
    if (data.thought === true) {
      return {
        thought: true,
        present: true,
        textLength: typeof data.text === 'string' ? data.text.length : 0,
      }
    }

    // 2. Anthropic thinking / redacted_thinking block (禁止残留 thinking 文本、签名或加密 data)
    if (data.type === 'thinking' || data.type === 'redacted_thinking') {
      const length =
        typeof data.text === 'string'
          ? data.text.length
          : typeof data.thinking === 'string'
            ? data.thinking.length
            : typeof data.data === 'string'
              ? data.data.length
              : 0
      return {
        type: data.type,
        present: true,
        length,
      }
    }

    // 3. OpenAI Responses reasoning block (type: 'reasoning')
    if (data.type === 'reasoning') {
      return {
        type: 'reasoning',
        present: true,
        ...(data.id ? { id: data.id } : {}),
      }
    }
    if (data.type === 'summary_text') {
      return {
        type: 'summary_text',
        present: true,
        length: typeof data.text === 'string' ? data.text.length : 0,
      }
    }

    const result = {}
    for (const [key, value] of Object.entries(data)) {
      const lower = key.toLowerCase()
      if (
        lower.includes('key')
        || lower.includes('secret')
        || lower.includes('password')
        || lower.includes('credential')
        || lower.includes('authorization')
        || lower.includes('baseurl')
        || lower.includes('base_url')
        || lower === 'auth'
        || (lower.includes('token') && !lower.includes('tokens'))
      ) {
        result[key] = '[REDACTED]'
      } else if (
        lower === 'url'
        || lower === 'endpoint'
        || lower === 'endpointurl'
        || lower === 'endpoint_url'
        || lower.includes('url')
      ) {
        result[key] = '[REDACTED_URL]'
      } else if (
        lower.includes('rawusage')
        || lower.includes('raw_usage')
      ) {
        result[key] = '[REDACTED_RAW_USAGE]'
      } else if (
        lower.includes('replaystate')
        || lower.includes('replay_state')
        || lower.includes('replaypayload')
        || lower.includes('replay_payload')
        || lower === 'payload'
        || lower === 'payloadjson'
        || lower === 'payload_json'
      ) {
        result[key] = '[REDACTED_REPLAY_PAYLOAD]'
      } else if (
        lower === 'thoughttokens'
        || lower === 'thought_tokens'
        || lower === 'reasoningtokens'
        || lower === 'reasoning_tokens'
      ) {
        result[key] = value
      } else if (
        lower.includes('thoughtsignature')
        || lower.includes('thought_signature')
        || lower === 'signature'
        || lower === 'signatures'
      ) {
        result[key] = '[REDACTED_SIGNATURE]'
      } else if (
        lower.includes('encryptedreasoning')
        || lower.includes('encrypted_reasoning')
        || lower.includes('encryptedcontent')
        || lower.includes('encrypted_content')
      ) {
        result[key] = '[REDACTED_ENCRYPTED_CONTENT]'
      } else if (
        lower.includes('summarytext')
        || lower.includes('summary_text')
        || lower === 'summary'
      ) {
        result[key] = '[REDACTED_SUMMARY]'
      } else if (
        lower.includes('reasoningcontent')
        || lower.includes('reasoning_content')
        || lower.includes('reasoningdetails')
        || lower.includes('reasoning_details')
      ) {
        result[key] = '[REDACTED_REASONING_CONTENT]'
      } else if (lower === 'thinking' || lower === 'thought' || lower === 'thoughts') {
        result[key] =
          typeof value === 'string'
            ? { present: true, length: value.length }
            : '[REDACTED_THINKING]'
      } else if (
        lower.includes('httpbody')
        || lower.includes('http_body')
        || lower === 'body'
        || lower.includes('requestbody')
        || lower.includes('request_body')
        || lower.includes('responsebody')
        || lower.includes('response_body')
      ) {
        result[key] = '[REDACTED_HTTP_BODY]'
      } else {
        result[key] = sanitizeArtifact(value)
      }
    }
    return result
  }
  return data
}

async function cleanupChat(ctx, chat) {
  if (!chat?.id) return
  try {
    await ctx.call(
      'DELETE',
      `/api/ai/chats/${chat.id}?expectedVersion=${encodeURIComponent(chat.version ?? '0')}`,
    )
  } catch {
    // 尽力 cleanup，保留主断言失败
  }
}

async function cleanupAgent(ctx, agent) {
  if (!agent?.name) return
  try {
    await ctx.call(
      'DELETE',
      `/api/ai/catalog/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(agent.version ?? '0')}`,
    )
  } catch {
    // 尽力 cleanup，保留主断言失败
  }
}

// ---------------------------------------------------------------------------
// 1. 四指定模型文本+缓存 case 注册 (L2, requires: ['real'])
// ---------------------------------------------------------------------------
for (const def of REAL_MODEL_DEFINITIONS) {
  registerCase({
    id: `real.text_cache.${def.idSuffix}`,
    level: 'L2',
    title: `${def.title} 真实文本与缓存轮次`,
    requires: ['real'],
    docs:
      def.providerType === 'google'
        ? `验证 ${def.providerName}/${def.modelName}（variant ${def.variant}）：创建临时无工具 Agent，system prompt 包含 >=16KiB 确定性前缀；首轮验证 durable TURN_START -> USER -> normal ASSISTANT -> TURN_END(COMPLETED)、IDLE、无 active invocation、usage 七字段合法且 input/output/providerTotal 事实有效；同 Thread 发 1 个短 follow-up，断言非空/marker 回复与 usage 并观测 cache hit（Google Gemini implicit cache 为服务端机会性能力，只观测 cache hit，确定性 cachedContentTokenCount 映射由 provider 单测覆盖），写脱敏 artifact`
        : `验证 ${def.providerName}/${def.modelName}（variant ${def.variant}）：创建临时无工具 Agent，system prompt 包含 >=16KiB 确定性前缀；首轮验证 durable TURN_START -> USER -> normal ASSISTANT -> TURN_END(COMPLETED)、IDLE、无 active invocation、usage 七字段合法且 input/output/providerTotal 事实有效；同 Thread 发 1~3 个短 follow-up，逐轮断言非空/marker 回复与 usage，最终要求至少一个 follow-up cacheReadTokens > 0，写脱敏 artifact`,
    async run(ctx) {
      const resolved = await resolveRealModel(ctx, def)
      const suffix = cid().slice(0, 8)
      const systemPrompt = buildTextCacheSystemPrompt()
      let agent = null
      let chat = null
      try {
        const { json: agentJson } = await ctx.call('POST', '/api/ai/catalog/agents', {
          name: `e2e-cache-${def.idSuffix}-${suffix}`,
          description: `Temporary text cache agent for ${def.title}.`,
          systemPrompt,
          model: `${resolved.providerName}/${resolved.modelName}`,
          variant: resolved.variant,
          config: { toolIds: [], skills: [], subagents: [] },
        })
        agent = envelopeData(agentJson)
        chat = await createChat(ctx, {
          title: `e2e-chat-${def.idSuffix}-${suffix}`,
          agentName: agent.name,
          yoloEnabled: false,
        })

        const sessionId = cid()
        const threadId = cid()
        const firstMarker = `MARKER-FIRST-${cid().slice(0, 8)}`
        const firstUserPrompt = `Echo exactly this marker: ${firstMarker}`
        const accepted = await createNewSession(ctx, {
          owner: chatOwner(chat.id),
          sessionId,
          threadId,
          rootSettings: branchSettingsOf(agent, {
            providerName: resolved.providerName,
            modelName: resolved.modelName,
            variant: resolved.variant,
          }),
          yoloEnabled: false,
          commands: [userMessageCommand(firstUserPrompt, cid())],
        })
        assert(
          accepted.acceptedCommands[0]?.type === 'USER_MESSAGE',
          `expected USER_MESSAGE command: ${safeDiagnosticJson(accepted.acceptedCommands)}`,
        )

        const firstQuiescent = await waitForQuiescentThread(ctx, threadId, {
          timeoutMs: 180_000,
          intervalMs: 500,
        })
        assert(firstQuiescent.status === 'IDLE', `thread not IDLE: ${safeDiagnosticJson(firstQuiescent)}`)

        const firstSnapshot = await getThreadSnapshot(ctx, threadId)
        assert(
          firstSnapshot.modelInvocation === null,
          `IDLE snapshot must expose no active model invocation: ${safeDiagnosticJson(firstSnapshot.modelInvocation)}`,
        )
        assert(
          !('modelInvocations' in firstSnapshot),
          `snapshot DTO has no modelInvocations[]: ${safeDiagnosticJson(Object.keys(firstSnapshot))}`,
        )
        assert(
          (firstSnapshot.queuedCommands || []).length === 0,
          `queued commands must be empty: ${safeDiagnosticJson(firstSnapshot.queuedCommands)}`,
        )

        const firstEntries = firstSnapshot.entries || []
        const firstUserIndex = findUserEntryIndex(firstEntries, firstMarker)
        const firstTurnStartIndex = firstEntries.findIndex((entry) => entryType(entry) === 'TURN_START')
        const firstAssistants = normalAssistantEntries(firstEntries)
        assert(firstAssistants.length > 0, `no normal assistant entry found: ${safeDiagnosticJson(firstEntries)}`)
        const firstAssistantEntry = firstAssistants.at(-1)
        const firstAssistantIndex = firstEntries.findIndex(
          (entry) => String(entry.entryId) === String(firstAssistantEntry.entryId),
        )
        const firstTurnEndIndex = firstEntries.findIndex((entry) => entryType(entry) === 'TURN_END')

        assert(
          firstTurnStartIndex >= 0
            && firstUserIndex >= 0
            && firstTurnStartIndex < firstUserIndex
            && firstUserIndex < firstAssistantIndex
            && firstAssistantIndex < firstTurnEndIndex,
          `expected TURN_START -> USER -> normal ASSISTANT MESSAGE -> TURN_END(COMPLETED): ${safeDiagnosticJson(firstEntries)}`,
        )

        const firstTurnEndPayload = parseEntryPayload(firstEntries[firstTurnEndIndex])
        assert(
          firstTurnEndPayload.outcome === 'COMPLETED' && firstTurnEndPayload.continueModel === false,
          `expected COMPLETED TURN_END: ${safeDiagnosticJson(firstTurnEndPayload)}`,
        )

        const firstText = messageText(firstAssistantEntry)
        assert(
          firstText.trim().length > 0 && firstText.includes(firstMarker),
          `first assistant reply must contain marker ${firstMarker}: ${firstText}`,
        )

        const firstAssistantPayload = parseEntryPayload(firstAssistantEntry)
        const firstUsage = firstAssistantPayload.assistantMetadata?.usage
        assertAssistantUsage(firstUsage, {
          requirePositiveIO: true,
          providerType: def.providerType,
          modelName: def.modelName,
        })

        if (def.providerType === 'google' || def.providerType === 'openai') {
          assert(
            firstUsage.cacheWriteTokens === 0,
            `${def.providerType} does not support cache write, cacheWriteTokens must be 0`,
          )
          assert(
            firstUsage.cacheWriteLongTokens === 0,
            `${def.providerType} cacheWriteLongTokens must be 0`,
          )
        } else if (def.providerType === 'openai_response' || def.providerType === 'anthropic') {
          assert(
            firstUsage.cacheWriteTokens >= 0,
            `${def.providerType} cacheWriteTokens must be non-negative`,
          )
        }

        let cacheHit = false
        const maxFollowUpRounds = def.providerType === 'google' ? 1 : 3
        const followUpRecords = []
        for (let round = 1; round <= maxFollowUpRounds; round++) {
          const currentThread = await getThread(ctx, threadId)
          const followUpMarker = `MARKER-FOLLOWUP-${round}-${cid().slice(0, 8)}`
          const followUpPrompt = `Follow-up round ${round}. Echo exactly this marker: ${followUpMarker}`
          await acceptCommandBatch(ctx, {
            owner: chatOwner(chat.id),
            target: threadTarget({
              threadId,
              expectedHeadEntryId: currentThread.headEntryId,
              expectedNextCommandSequence: currentThread.nextCommandSequence,
            }),
            commands: [userMessageCommand(followUpPrompt, cid())],
          })

          const quiescent = await waitForQuiescentThread(ctx, threadId, {
            timeoutMs: 180_000,
            intervalMs: 500,
          })
          assert(
            quiescent.status === 'IDLE',
            `thread not IDLE after follow-up round ${round}: ${safeDiagnosticJson(quiescent)}`,
          )

          const roundSnapshot = await getThreadSnapshot(ctx, threadId)
          const roundEntries = roundSnapshot.entries || []
          const roundUserIndex = findUserEntryIndex(roundEntries, followUpMarker)
          assert(
            roundUserIndex >= 0,
            `follow-up USER entry missing in round ${round}: ${safeDiagnosticJson(roundEntries)}`,
          )

          const entriesAfterUser = roundEntries.slice(roundUserIndex + 1)
          const roundAssistants = normalAssistantEntries(entriesAfterUser)
          assert(roundAssistants.length > 0, `no assistant entry after user in round ${round}`)
          const roundAssistant = roundAssistants.at(-1)
          const roundText = messageText(roundAssistant)
          assert(
            roundText.trim().length > 0 && roundText.includes(followUpMarker),
            `follow-up round ${round} assistant reply must contain marker ${followUpMarker}: ${roundText}`,
          )

          const roundPayload = parseEntryPayload(roundAssistant)
          const usage = roundPayload.assistantMetadata?.usage
          assertAssistantUsage(usage, {
            requirePositiveIO: true,
            providerType: def.providerType,
            modelName: def.modelName,
          })

          followUpRecords.push({
            round,
            usage,
            replySnippet: roundText.slice(0, 80),
          })

          if (usage.cacheReadTokens > 0) {
            cacheHit = true
            break
          }
        }

        if (def.providerType !== 'google') {
          assert(
            cacheHit,
            `expected at least one follow-up turn to have cacheReadTokens > 0 for ${def.title}. Usages: ${safeDiagnosticJson(followUpRecords)}`,
          )
        }

        ctx.writeArtifact(
          `real-text-cache-${def.idSuffix}.json`,
          JSON.stringify(
            sanitizeArtifact({
              modelDef: {
                providerName: def.providerName,
                modelName: def.modelName,
                variant: def.variant,
                providerType: def.providerType,
              },
              threadId,
              cacheHitObserved: cacheHit,
              firstRound: {
                usage: firstUsage,
                replySnippet: firstText.slice(0, 80),
              },
              followUpRecords,
            }),
            null,
            2,
          ),
        )
      } finally {
        await cleanupChat(ctx, chat)
        await cleanupAgent(ctx, agent)
      }
    },
  })
}

// ---------------------------------------------------------------------------
// 2. 四指定模型多推理级别烟雾测试 case 注册 (L2, requires: ['real'])
// ---------------------------------------------------------------------------

export async function runRealReasoningLevelsSmoke(ctx, def) {
  const resolved = await resolveRealModel(ctx, def)
  const variantsToTest = resolved.variants || [def.variant]
  const testedVariants = []

  for (const variant of variantsToTest) {
    let varAgent = null
    let varChat = null
    try {
      const varSuffix = cid().slice(0, 8)
      const varMarker = `REASON-${def.idSuffix}-${variant}-${varSuffix}`
      const { json: varAgentJson } = await ctx.call('POST', '/api/ai/catalog/agents', {
        name: `e2e-reason-${def.idSuffix}-${variant}-${varSuffix}`,
        description: `Temporary reasoning agent for ${def.title} ${variant}.`,
        systemPrompt:
          'You are a precise reasoning assistant. Compute the answer carefully and reply with the required marker.',
        model: `${resolved.providerName}/${resolved.modelName}`,
        variant,
        config: { toolIds: [], skills: [], subagents: [] },
      })
      varAgent = envelopeData(varAgentJson)
      varChat = await createChat(ctx, {
        title: `e2e-reason-chat-${def.idSuffix}-${variant}-${varSuffix}`,
        agentName: varAgent.name,
        yoloEnabled: false,
      })

      const varSessionId = cid()
      const varThreadId = cid()
      const varPrompt = `Calculate 29 * 31. State the final product clearly and append this marker: ${varMarker}`
      const accepted = await createNewSession(ctx, {
        owner: chatOwner(varChat.id),
        sessionId: varSessionId,
        threadId: varThreadId,
        rootSettings: branchSettingsOf(varAgent, {
          providerName: resolved.providerName,
          modelName: resolved.modelName,
          variant,
        }),
        yoloEnabled: false,
        commands: [userMessageCommand(varPrompt, cid())],
      })
      assert(
        accepted.acceptedCommands[0]?.type === 'USER_MESSAGE',
        `expected USER_MESSAGE command: ${safeDiagnosticJson(accepted.acceptedCommands)}`,
      )

      const varQuiescent = await waitForQuiescentThread(ctx, varThreadId, {
        timeoutMs: 180_000,
        intervalMs: 500,
      })
      assert(varQuiescent.status === 'IDLE', `thread not IDLE: ${safeDiagnosticJson(varQuiescent)}`)

      const varSnapshot = await getThreadSnapshot(ctx, varThreadId)
      const varEntries = varSnapshot.entries || []
      assert(
        !varEntries.some((entry) => entryType(entry) === 'ASSISTANT_ERROR'),
        `unexpected ASSISTANT_ERROR in variant ${variant}: ${safeDiagnosticJson(varEntries)}`,
      )

      const varAssistants = normalAssistantEntries(varEntries)
      assert(varAssistants.length > 0, `no assistant entry in variant ${variant}`)
      const varAssistant = varAssistants.at(-1)
      const varPayload = parseEntryPayload(varAssistant)
      const varUsage = varPayload.assistantMetadata?.usage
      assertAssistantUsage(varUsage, {
        requirePositiveIO: true,
        providerType: def.providerType,
        modelName: def.modelName,
      })

      const hasThinkingContent = (varPayload.message?.contents || []).some(
        (c) => c?.type === 'thinking',
      )
      const hasReasoningTokens = (varUsage?.reasoningTokens || 0) > 0

      testedVariants.push({
        variant,
        inputTokens: varUsage.inputTokens,
        outputTokens: varUsage.outputTokens,
        reasoningTokens: varUsage.reasoningTokens,
        providerTotalTokens: varUsage.providerTotalTokens,
        hasThinkingContent,
        hasReasoningTokens,
        replySnippet: messageText(varAssistant).slice(0, 80),
      })
    } finally {
      await cleanupChat(ctx, varChat)
      await cleanupAgent(ctx, varAgent)
    }
  }

  assert(
    testedVariants.length === variantsToTest.length,
    `expected all ${variantsToTest.length} variants tested, got ${testedVariants.length}`,
  )

  ctx.writeArtifact(
    `real-reasoning-levels-${def.idSuffix}.json`,
    safeDiagnosticJson(
      {
        modelDef: {
          providerName: def.providerName,
          modelName: def.modelName,
          providerType: def.providerType,
        },
        testedVariants,
      },
      2,
    ),
  )
}

for (const def of REAL_MODEL_DEFINITIONS) {
  registerCase({
    id: `real.reasoning_levels.${def.idSuffix}`,
    level: 'L2',
    title: `${def.title} 多推理级别烟雾测试`,
    requires: ['real'],
    docs: `验证 ${def.providerName}/${def.modelName}：遍历所有声明变体（${def.variants.join(', ')}），为每个 variant 创建临时 Agent 运行简短推理任务，断言无 ASSISTANT_ERROR，校验七字段用量及代数等式，记录 thinking/reasoning 证据，写脱敏 artifact`,
    async run(ctx) {
      await runRealReasoningLevelsSmoke(ctx, def)
    },
  })
}

// ---------------------------------------------------------------------------
// 3. 四指定模型工具 case 注册 (L4, requires: ['real', 'tools'])
// ---------------------------------------------------------------------------
for (const def of REAL_MODEL_DEFINITIONS) {
  registerCase({
    id: `real.tool.${def.idSuffix}`,
    level: 'L4',
    title: `${def.title} 真实工具调用与 terminal replay`,
    requires: ['real', 'tools'],
    docs:
      def.providerType === 'openai_response' || def.providerType === 'anthropic'
        ? `验证 ${def.providerName}/${def.modelName}：在最强 variant 下运行真实工具调用（get_goal）与 terminal replay 闭环；严格校验 tool_call、tool_result、最终 ASSISTANT marker、两次模型调用及 provider-specific usage 代数（${def.title} 的 reasoning/replay 结构由确定性 provider 单测覆盖），写脱敏 artifact`
        : `验证 ${def.providerName}/${def.modelName}：在最强 variant 下运行真实工具调用（get_goal）与 terminal replay 闭环；严格校验 tool_call、首轮推理证据、tool_result、最终 ASSISTANT marker、两次模型调用及 provider-specific usage 代数与 replay 证据，写脱敏 artifact`,
    async run(ctx) {
      const resolved = await resolveRealModel(ctx, def)
      const suffix = cid().slice(0, 8)
      const strongestVariant = (resolved.variants || [def.variant]).at(-1)
      const marker = `GOAL-DONE-${cid().slice(0, 8)}`
      let agent = null
      let chat = null
      try {
        const { json: agentJson } = await ctx.call('POST', '/api/ai/catalog/agents', {
          name: `e2e-tool-${def.idSuffix}-${suffix}`,
          description: `Temporary tool agent for ${def.title}.`,
          systemPrompt:
            'You are an evaluation assistant with access to the get_goal tool. '
            + 'When instructed by the user, you MUST call the get_goal tool exactly once with arguments {}. '
            + 'Do not call any other tool. After receiving the tool result, answer the user with the requested marker.',
          model: `${resolved.providerName}/${resolved.modelName}`,
          variant: strongestVariant,
          config: {
            toolIds: ['base.goal.get'],
            skills: [],
            subagents: [],
          },
        })
        agent = envelopeData(agentJson)
        chat = await createChat(ctx, {
          title: `e2e-tool-chat-${def.idSuffix}-${suffix}`,
          agentName: agent.name,
          yoloEnabled: false,
        })

        const sessionId = cid()
        const threadId = cid()
        const userPrompt =
          `Please call the get_goal tool with arguments {}. `
          + `After you receive the result, reply with exactly: ${marker}`
        const accepted = await createNewSession(ctx, {
          owner: chatOwner(chat.id),
          sessionId,
          threadId,
          rootSettings: branchSettingsOf(agent, {
            providerName: resolved.providerName,
            modelName: resolved.modelName,
            variant: strongestVariant,
          }),
          yoloEnabled: false,
          commands: [userMessageCommand(userPrompt, cid())],
        })
        assert(
          accepted.acceptedCommands[0]?.type === 'USER_MESSAGE',
          `expected USER_MESSAGE command: ${safeDiagnosticJson(accepted.acceptedCommands)}`,
        )

        const finalThread = await waitForQuiescentThread(ctx, threadId, {
          timeoutMs: 180_000,
          intervalMs: 500,
        })
        assert(finalThread.status === 'IDLE', `thread not IDLE: ${safeDiagnosticJson(finalThread)}`)

        const snapshot = await getThreadSnapshot(ctx, threadId)
        const entries = snapshot.entries || []
        assert(
          !entries.some((entry) => entryType(entry) === 'ASSISTANT_ERROR'),
          `unexpected ASSISTANT_ERROR in tool turn: ${safeDiagnosticJson(entries)}`,
        )

        // 收集所有 durable tool_call
        const toolCalls = []
        for (const entry of entries) {
          if (entryType(entry) !== 'MESSAGE') continue
          const payload = parseEntryPayload(entry)
          if (payload.message?.role !== 'ASSISTANT') continue
          for (const content of payload.message.contents || []) {
            if (content?.type === 'tool_call') {
              toolCalls.push({ entry, payload, content })
            }
          }
        }

        assert(
          toolCalls.length === 1,
          `expected exactly one durable ASSISTANT tool_call, got ${toolCalls.length}: ${safeDiagnosticJson(toolCalls.map((t) => t.content))}`,
        )
        const { entry: toolCallEntry, payload: toolCallPayload, content: toolCallContent } = toolCalls[0]
        assert(
          toolCallContent.toolName === 'get_goal',
          `expected toolName get_goal, got: ${toolCallContent.toolName}`,
        )
        assert(
          typeof toolCallContent.argumentsJson === 'string',
          `argumentsJson must be string: ${toolCallContent.argumentsJson}`,
        )
        const parsedArgs = JSON.parse(toolCallContent.argumentsJson)
        assert(
          parsedArgs && typeof parsedArgs === 'object' && !Array.isArray(parsedArgs) && Object.keys(parsedArgs).length === 0,
          `argumentsJson must be an empty object, got: ${toolCallContent.argumentsJson}`,
        )
        const callId = toolCallContent.toolCallId
        assert(callId && typeof callId === 'string', `toolCallId missing: ${safeDiagnosticJson(toolCallContent)}`)

        // 收集匹配 callId 的 durable TOOL tool_result
        const toolResults = []
        let toolResultEntry = null
        for (const entry of entries) {
          if (entryType(entry) !== 'MESSAGE') continue
          const payload = parseEntryPayload(entry)
          if (payload.message?.role !== 'TOOL') continue
          for (const content of payload.message.contents || []) {
            if (content?.type === 'tool_result' && content.toolCallId === callId) {
              toolResults.push(content)
              toolResultEntry = entry
            }
          }
        }

        assert(
          toolResults.length === 1,
          `expected exactly one matching tool_result for callId ${callId}, got ${toolResults.length}`,
        )
        const result = toolResults[0]
        assert(result.toolName === 'get_goal', `tool_result toolName mismatch: ${result.toolName}`)
        const resultText = (result.contents || [])
          .filter((c) => c?.type === 'text')
          .map((c) => String(c.text || ''))
          .join('\n')
        assert(
          resultText.toLowerCase().includes('no current branch goal'),
          `expected tool_result text to contain "no current branch goal", got: ${resultText}`,
        )

        // 随后 normal final Assistant 含 marker
        const toolEntryIndex = entries.findIndex(
          (e) => String(e.entryId) === String(toolResultEntry.entryId),
        )
        const normalAssistantsAfterTool = entries
          .slice(toolEntryIndex + 1)
          .filter((entry) => {
            if (entryType(entry) !== 'MESSAGE') return false
            const payload = parseEntryPayload(entry)
            return payload.message?.role === 'ASSISTANT'
          })

        assert(
          normalAssistantsAfterTool.length === 1,
          `expected exactly one final assistant message after tool, got ${normalAssistantsAfterTool.length}: ${safeDiagnosticJson(normalAssistantsAfterTool)}`,
        )
        const finalAssistantEntry = normalAssistantsAfterTool[0]
        const finalAssistantPayload = parseEntryPayload(finalAssistantEntry)
        const finalText = messageText(finalAssistantEntry)
        assert(
          finalText.includes(marker),
          `final assistant reply must include marker ${marker}, got: ${finalText}`,
        )

        // Turn COMPLETED
        const turnEndEntries = entries.filter((e) => entryType(e) === 'TURN_END')
        assert(turnEndEntries.length >= 1, `expected at least one TURN_END: ${safeDiagnosticJson(entries)}`)
        const lastTurnEndPayload = parseEntryPayload(turnEndEntries.at(-1))
        assert(
          lastTurnEndPayload.outcome === 'COMPLETED' && lastTurnEndPayload.continueModel === false,
          `expected COMPLETED TURN_END: ${safeDiagnosticJson(lastTurnEndPayload)}`,
        )

        // 两次模型 Assistant metadata usage 合法且符合 provider-specific algebra
        const toolCallUsage = toolCallPayload.assistantMetadata?.usage
        const finalAssistantUsage = finalAssistantPayload.assistantMetadata?.usage
        assertAssistantUsage(toolCallUsage, {
          requirePositiveIO: true,
          providerType: def.providerType,
          modelName: def.modelName,
        })
        assertAssistantUsage(finalAssistantUsage, {
          requirePositiveIO: true,
          providerType: def.providerType,
          modelName: def.modelName,
        })

        // 记录两次响应的推理证据；仅对当前能稳定报告该证据的 Gemini / DeepSeek 保持真实门禁。
        // OpenAI Responses 与 MiniMax Anthropic 的 reasoning/replay 结构由确定性 provider 单测覆盖。
        const hasToolCallThinking = (toolCallPayload.message?.contents || []).some(
          (c) => c?.type === 'thinking',
        )
        const toolCallReasoningTokens = toolCallUsage?.reasoningTokens || 0
        const hasFinalThinking = (finalAssistantPayload.message?.contents || []).some(
          (c) => c?.type === 'thinking',
        )
        const finalReasoningTokens = finalAssistantUsage?.reasoningTokens || 0

        if (def.providerType === 'google') {
          assert(
            toolCallReasoningTokens > 0 || hasToolCallThinking,
            `Gemini ${strongestVariant} variant must demonstrate reasoning on first tool-call response (reasoningTokens > 0 or thinking content)`,
          )
        } else if (def.providerType === 'openai') {
          assert(
            toolCallReasoningTokens > 0 || hasToolCallThinking,
            `DeepSeek Chat ${strongestVariant} variant must demonstrate reasoning on first tool-call response (reasoningTokens > 0 or thinking content)`,
          )
        }

        ctx.writeArtifact(
          `real-tool-${def.idSuffix}.json`,
          safeDiagnosticJson(
            {
              modelDef: {
                providerName: def.providerName,
                modelName: def.modelName,
                variant: strongestVariant,
                providerType: def.providerType,
              },
              threadId,
              toolCall: {
                callId,
                toolName: toolCallContent.toolName,
                argumentsJson: toolCallContent.argumentsJson,
                usage: toolCallUsage,
                hasThinking: hasToolCallThinking,
                hasReasoningTokens: toolCallReasoningTokens > 0,
              },
              toolResult: {
                resultSnippet: resultText.slice(0, 80),
              },
              finalAssistant: {
                replySnippet: finalText.slice(0, 80),
                usage: finalAssistantUsage,
                hasThinking: hasFinalThinking,
                hasReasoningTokens: finalReasoningTokens > 0,
              },
            },
            2,
          ),
        )
      } finally {
        await cleanupChat(ctx, chat)
        await cleanupAgent(ctx, agent)
      }
    },
  })
}

// ---------------------------------------------------------------------------
// 3. 现有其他真实 case（迁移至 minimax-anthropic/MiniMax-M3）
// ---------------------------------------------------------------------------

registerCase({
  id: 'real.task_delegation',
  level: 'L2',
  title: '真实 task 委派创建 durable 子 Thread',
  requires: ['real', 'tools'],
  docs: '父 ModelInvocation 冻结 subagent allowlist 并调用内部 task；最终 TOOL MESSAGE 冻结 rendererKey=task 与 <task id> envelope；id 对应子 Thread ROOT.subagentContext(parent/root/taskInvocation/depth=2)',
  async run(ctx) {
    const minimaxModel = await requireRealMiniMaxM3(ctx)
    const suffix = cid().slice(0, 8)
    const marker = `SUBAGENT-E2E-${process.hrtime.bigint()}`
    let childAgent = null
    let parentAgent = null
    let chat = null
    try {
      childAgent = envelopeData(
        (
          await ctx.call('POST', '/api/ai/catalog/agents', {
            name: `e2e-task-child-${suffix}`,
            description: 'Return the requested marker without using tools.',
            systemPrompt:
              'You are an E2E subagent. Follow the delegated prompt exactly and return only its requested marker.',
            model: `${minimaxModel.providerName}/${minimaxModel.modelName}`,
            variant: minimaxModel.variant,
            config: { toolIds: [], skills: [], subagents: [] },
          })
        ).json,
      )
      parentAgent = envelopeData(
        (
          await ctx.call('POST', '/api/ai/catalog/agents', {
            name: `e2e-task-parent-${suffix}`,
            description: 'Always delegates once to the configured child.',
            systemPrompt:
              `For every user message, call task exactly once with subagent_type="${childAgent.name}". `
              + `Delegate the instruction "Return exactly ${marker}". After the task result, answer with the same marker.`,
            model: `${minimaxModel.providerName}/${minimaxModel.modelName}`,
            variant: minimaxModel.variant,
            config: { toolIds: [], skills: [], subagents: [childAgent.name] },
          })
        ).json,
      )
      assert(
        JSON.stringify(parentAgent.config?.subagents) === JSON.stringify([childAgent.name]),
        safeDiagnosticJson(parentAgent),
      )
      chat = await createChat(ctx, {
        title: `e2e-task-${suffix}`,
        agentName: parentAgent.name,
        yoloEnabled: false,
      })
      const accepted = await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId: cid(),
        threadId: cid(),
        rootSettings: branchSettingsOf(
          parentAgent,
          {
            providerName: minimaxModel.providerName,
            modelName: minimaxModel.modelName,
            variant: minimaxModel.variant,
          },
        ),
        yoloEnabled: false,
        commands: [
          userMessageCommand(
            `Use task with subagent_type "${childAgent.name}" and ask it to return exactly ${marker}.`,
            cid(),
          ),
        ],
      })
      const parentThreadId = String(accepted.thread.threadId)
      const finalThread = await waitForQuiescentThread(ctx, parentThreadId, {
        timeoutMs: 300_000,
        intervalMs: 500,
      })
      assert(finalThread.status === 'IDLE', safeDiagnosticJson(finalThread))
      const parentSnapshot = await getThreadSnapshot(ctx, parentThreadId)
      const taskResults = []
      for (const entry of parentSnapshot.entries || []) {
        if (entryType(entry) !== 'MESSAGE') continue
        const message = parseEntryPayload(entry).message
        if (message?.role !== 'TOOL') continue
        for (const content of message.contents || []) {
          if (content?.type === 'tool_result' && content.toolName === 'task') {
            taskResults.push(content)
          }
        }
      }
      assert(taskResults.length === 1, `expected one task result: ${safeDiagnosticJson(taskResults)}`)
      const taskResult = taskResults[0]
      assert(taskResult.rendererKey === 'task', safeDiagnosticJson(taskResult))
      const taskText = (taskResult.contents || [])
        .filter((content) => content?.type === 'text')
        .map((content) => String(content.text || ''))
        .join('')
      const taskId = /<task id="([^"]+)" state="completed">/.exec(taskText)?.[1]
      assert(taskId, `completed task envelope missing: ${taskText}`)
      canonicalUuid(taskId, 'task envelope thread id')
      assert(taskText.includes(marker), `subagent report missing marker: ${taskText}`)

      const childSnapshot = await getThreadSnapshot(ctx, taskId)
      const childRoot = (childSnapshot.entries || [])[0]
      assert(entryType(childRoot) === 'ROOT', safeDiagnosticJson(childSnapshot.entries))
      const context = parseEntryPayload(childRoot).subagentContext
      assert(
        String(context?.parentThreadId) === parentThreadId
          && String(context?.rootThreadId) === parentThreadId
          && canonicalUuid(context?.taskInvocationId, 'subagentContext.taskInvocationId')
          && context?.depth === 2,
        `invalid child ROOT subagentContext: ${safeDiagnosticJson(context)}`,
      )
      ctx.writeArtifact(
        'task-delegation.json',
        JSON.stringify(sanitizeArtifact({ parentSnapshot, childSnapshot, taskResult }), null, 2),
      )
    } finally {
      await cleanupChat(ctx, chat)
      await cleanupAgent(ctx, parentAgent)
      await cleanupAgent(ctx, childAgent)
    }
  },
})

registerCase({
  id: 'real.stop_partial_continue',
  level: 'L2',
  title: '真实流式 /stop 持久化 partial、exact replay 并继续新一轮',
  requires: ['real'],
  docs: '使用 minimax-anthropic/MiniMax-M3：bootstrap 用 missing Agent 确定性 PLANNING_FAILED 创建空闲 Thread（不调用真实 Provider）；随后 THREAD batch SET_AGENT/SET_MODEL + initialPrompt 启动真实 turn；首个非空 text/thinking delta 后 stop（stopRequestId + version CAS）=> status STOPPED、version+1、stoppedTurnEndEntryId 非空、durable ASSISTANT_ABORTED 关闭旧 turn；同 stopRequestId + 原 expectedVersion exact replay => status REPLAYED、同 stoppedTurnEndEntryId、version 不再变化；真实 turn 区间（initialMarker 之后）无 ASSISTANT_ERROR/无 normal assistant；follow-up 位于 barrier 后并仅产生一个新 assistant MESSAGE',
  async run(ctx) {
    const minimaxModel = await requireRealMiniMaxM3(ctx)

    const initialMarker = `STOP-PARTIAL-${cid()}`
    const followUpMarker = `FOLLOW-UP-${cid()}`
    const initialPrompt =
      `${initialMarker}\n`
      + '不要调用任何工具。请立即开始逐行输出 120 行短句，每行都以“流式验证”开头并带连续编号；'
      + '不要总结，不要提前结束。'
    const followUpPrompt =
      `${followUpMarker}\n只回复单词 CONTINUED，不要调用工具，不要解释。`

    const suffix = cid().slice(0, 8)
    let tempAgent = null
    let chat = null
    try {
      const { json: agentJson } = await ctx.call('POST', '/api/ai/catalog/agents', {
        name: `e2e-stop-agent-${suffix}`,
        description: 'Temporary agent for stop partial test.',
        systemPrompt: 'Follow instructions strictly.',
        model: `${minimaxModel.providerName}/${minimaxModel.modelName}`,
        variant: minimaxModel.variant,
        config: { toolIds: [], skills: [], subagents: [] },
      })
      tempAgent = envelopeData(agentJson)

      chat = await createChat(ctx, {
        title: `e2e-stop-partial-${suffix}`,
        agentName: tempAgent.name,
        yoloEnabled: false,
      })
      const sessionId = cid()
      const tid = cid()
      await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId,
        threadId: tid,
        rootSettings: branchSettingsOf(
          { name: `e2e-stop-partial-missing-${suffix}` },
          {
            providerName: minimaxModel.providerName,
            modelName: minimaxModel.modelName,
            variant: minimaxModel.variant,
          },
        ),
        yoloEnabled: false,
        commands: [userMessageCommand(`stop partial materialize ${suffix}`, cid())],
      })
      const idle = await waitForQuiescentThread(ctx, tid, { timeoutMs: 60_000, intervalMs: 100 })

      const { signal: firstDelta, startResult } =
        await waitForModelContentDeltaAfterEventSubscribed(
          ctx,
          tid,
          () =>
            acceptCommandBatch(ctx, {
              owner: chatOwner(chat.id),
              target: threadTarget({
                threadId: tid,
                expectedHeadEntryId: idle.headEntryId,
                expectedNextCommandSequence: idle.nextCommandSequence,
              }),
              commands: [
                setAgentCommand(tempAgent.name, cid()),
                setModelCommand(
                  {
                    providerName: minimaxModel.providerName,
                    modelName: minimaxModel.modelName,
                    variant: minimaxModel.variant,
                  },
                  cid(),
                ),
                userMessageCommand(initialPrompt, cid()),
              ],
            }),
          { timeoutMs: 90_000 },
        )
      assert(
        Array.isArray(startResult?.acceptedCommands)
          && startResult.acceptedCommands.at(-1).type === 'USER_MESSAGE',
        `initial message batch: ${safeDiagnosticJson(startResult)}`,
      )
      assert(firstDelta.text.trim(), `expected non-empty text delta: ${safeDiagnosticJson(firstDelta)}`)

      const beforeStop = await getThread(ctx, tid)
      const stopRequestId = cid()
      const expectedVersion = beforeStop.version
      const stop = await stopThread(ctx, tid, {
        stopRequestId,
        expectedVersion,
      })
      assert(stop.status === 'STOPPED', safeDiagnosticJson(stop))
      assert(
        stop.stoppedTurnEndEntryId != null,
        safeDiagnosticJson(stop),
      )
      assert(
        Number(stop.thread.version) === Number(beforeStop.version) + 1,
        `active stop must bump version by one: ${safeDiagnosticJson({ beforeStop, stop })}`,
      )

      const entriesAfterStop = await snapshotEntries(ctx, tid)
      const abortedEntries = entriesAfterStop.filter((entry) => entryType(entry) === 'ASSISTANT_ABORTED')
      assert(
        abortedEntries.length === 1,
        `expected exactly one ASSISTANT_ABORTED: ${safeDiagnosticJson(entriesAfterStop)}`,
      )
      const abortedEntry = abortedEntries[0]
      assertAssistantAbortedEntry(abortedEntry)
      const initialUserIndex = findUserEntryIndex(entriesAfterStop, initialMarker)
      assert(initialUserIndex >= 0, `initial USER entry missing: ${safeDiagnosticJson(entriesAfterStop)}`)
      const realTurnEntries = entriesAfterStop.slice(initialUserIndex)
      assert(
        !realTurnEntries.some((entry) => entryType(entry) === 'ASSISTANT_ERROR'),
        `expected partial aborted barrier, not ASSISTANT_ERROR: ${safeDiagnosticJson(realTurnEntries)}`,
      )
      assert(
        normalAssistantEntries(realTurnEntries).length === 0,
        `stopped invocation must not materialize a normal assistant MESSAGE: ${safeDiagnosticJson(realTurnEntries)}`,
      )
      const abortedIndex = entriesAfterStop.findIndex(
        (entry) => String(entry.entryId) === String(abortedEntry.entryId),
      )
      assert(
        abortedIndex > initialUserIndex,
        `ASSISTANT_ABORTED must follow initial USER: ${safeDiagnosticJson(entriesAfterStop)}`,
      )

      const replay = await stopThread(ctx, tid, {
        stopRequestId,
        expectedVersion,
      })
      assert(replay.status === 'REPLAYED', safeDiagnosticJson(replay))
      assert(
        String(replay.stoppedTurnEndEntryId) === String(stop.stoppedTurnEndEntryId),
        `replay must identify the same stopped TURN_END: ${safeDiagnosticJson({ stop, replay })}`,
      )
      assert(replay.cancelledCommandCount === 0, safeDiagnosticJson(replay))
      assert(
        String(replay.thread.headEntryId) === String(stop.thread.headEntryId)
          && String(replay.thread.version) === String(stop.thread.version),
        `replay must not mutate the Thread: ${safeDiagnosticJson({ stop, replay })}`,
      )
      ctx.writeArtifact(
        'stop-partial-after-stop.json',
        JSON.stringify(
          sanitizeArtifact({ firstDelta, beforeStop, stop, replay, entriesAfterStop }),
          null,
          2,
        ),
      )

      const followUp = await acceptCommandBatch(ctx, {
        owner: chatOwner(chat.id),
        target: threadTarget({
          threadId: tid,
          expectedHeadEntryId: stop.thread.headEntryId,
          expectedNextCommandSequence: stop.thread.nextCommandSequence,
        }),
        commands: [userMessageCommand(followUpPrompt, cid())],
      })
      assert(followUp.acceptedCommands.length === 1, `follow-up batch: ${safeDiagnosticJson(followUp)}`)
      const finalThread = await waitForQuiescentThread(ctx, tid, {
        timeoutMs: 120_000,
        intervalMs: 500,
      })
      const finalEntries = await snapshotEntries(ctx, tid)
      const finalAbortedEntries = finalEntries.filter((entry) => entryType(entry) === 'ASSISTANT_ABORTED')
      assert(
        finalAbortedEntries.length === 1
          && String(finalAbortedEntries[0].entryId) === String(abortedEntry.entryId),
        `aborted barrier changed after follow-up: ${safeDiagnosticJson(finalEntries)}`,
      )

      const finalInitialUserIndex = findUserEntryIndex(finalEntries, initialMarker)
      assert(
        finalInitialUserIndex >= 0,
        `initial USER entry missing after follow-up: ${safeDiagnosticJson(finalEntries)}`,
      )
      const finalAbortedIndex = finalEntries.findIndex(
        (entry) => String(entry.entryId) === String(abortedEntry.entryId),
      )
      const followUpIndex = findUserEntryIndex(finalEntries, followUpMarker)
      const finalEntriesAfterInitial = finalEntries.slice(finalInitialUserIndex)
      const finalRealTurnAssistants = normalAssistantEntries(finalEntriesAfterInitial)
      assert(
        finalRealTurnAssistants.length === 1,
        `expected exactly one normal assistant for follow-up: ${safeDiagnosticJson(finalEntries)}`,
      )
      const finalAssistantIndex = finalEntries.findIndex(
        (entry) => String(entry.entryId) === String(finalRealTurnAssistants[0].entryId),
      )
      assert(
        finalInitialUserIndex < finalAbortedIndex
          && finalAbortedIndex < followUpIndex
          && followUpIndex < finalAssistantIndex,
        `expected USER -> ASSISTANT_ABORTED -> follow-up USER -> assistant MESSAGE: ${safeDiagnosticJson(finalEntries)}`,
      )
      assert(
        !finalEntriesAfterInitial.some((entry) => entryType(entry) === 'ASSISTANT_ERROR'),
        `unexpected cancellation barrier after durable partial: ${safeDiagnosticJson(finalEntriesAfterInitial)}`,
      )
      ctx.writeArtifact(
        'stop-partial-continue.json',
        JSON.stringify(
          sanitizeArtifact({
            firstDelta,
            beforeStop,
            stop,
            replay,
            entriesAfterStop,
            finalThread,
            finalEntries,
          }),
          null,
          2,
        ),
      )
    } finally {
      await cleanupChat(ctx, chat)
      await cleanupAgent(ctx, tempAgent)
    }
  },
})

registerCase({
  id: 'branch.same_session_new_thread',
  level: 'L3',
  title: 'NEW_THREAD 同 Session 分支创建（真实分支 turn）',
  requires: ['real', 'branch'],
  docs: '使用 minimax-anthropic/MiniMax-M3：同一 Session 历史 assistant Entry 下 NEW_THREAD target 开新 Thread（不复制 Entry）：sessionId 不变、新 Thread 默认名 branch-<threadId 前 8 位>、root-to-head 路径包含 startEntry 与分支 USER、分支 turn 继续产生独立 assistant；原 Thread head/version/nextCommandSequence 不变；同一 batch 精确重放返回原 branch Thread 且不产生第二个 branch',
  async run(ctx) {
    const minimaxModel = await requireRealMiniMaxM3(ctx)
    const suffix = cid().slice(0, 8)
    let agent = null
    let chat = null
    try {
      const { json: agentJson } = await ctx.call('POST', '/api/ai/catalog/agents', {
        name: `e2e-branch-agent-${suffix}`,
        description: 'Temporary agent for branch entry thread testing.',
        systemPrompt: 'Follow user instructions strictly.',
        model: `${minimaxModel.providerName}/${minimaxModel.modelName}`,
        variant: minimaxModel.variant,
        config: { toolIds: [], skills: [], subagents: [] },
      })
      agent = envelopeData(agentJson)
      chat = await createChat(ctx, {
        title: `e2e-branch-chat-${suffix}`,
        agentName: agent.name,
        yoloEnabled: false,
      })

      const sessionId = cid()
      const mainTid = cid()
      const mainMarker = `MAIN-BRANCH-SEED-${cid().slice(0, 8)}`
      const accepted = await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId,
        threadId: mainTid,
        rootSettings: branchSettingsOf(agent, {
          providerName: minimaxModel.providerName,
          modelName: minimaxModel.modelName,
          variant: minimaxModel.variant,
        }),
        yoloEnabled: false,
        commands: [userMessageCommand(`Reply with word OK: ${mainMarker}`, cid())],
      })
      assert(accepted.acceptedCommands.length === 1, safeDiagnosticJson(accepted.acceptedCommands))
      const mainQuiescent = await waitForQuiescentThread(ctx, mainTid, {
        timeoutMs: 180_000,
        intervalMs: 500,
      })
      assert(mainQuiescent.status === 'IDLE', safeDiagnosticJson(mainQuiescent))
      const mainSnapshot = await getThreadSnapshot(ctx, mainTid)
      const mainAssistants = normalAssistantEntries(mainSnapshot.entries || [])
      assert(mainAssistants.length > 0, 'missing main assistant entry')
      const assistantEntryId = String(mainAssistants.at(-1).entryId)

      const current = await getThread(ctx, mainTid)
      assert(String(current.sessionId) === String(sessionId), safeDiagnosticJson(current))
      const mainBefore = {
        headEntryId: current.headEntryId,
        version: current.version,
        nextCommandSequence: current.nextCommandSequence,
      }
      const branchThreadId = cid()
      const branchUserText = '在分支上只回复单词 BRANCH，不要调用工具。'
      const branchCommand = userMessageCommand(branchUserText, cid())
      const branchTarget = {
        owner: chatOwner(chat.id),
        sessionId,
        startEntryId: assistantEntryId,
        threadId: branchThreadId,
        yoloEnabled: current.yoloEnabled,
        commands: [branchCommand],
      }
      const branched = await createNewThread(ctx, branchTarget)
      assert(String(branched.thread.sessionId) === String(sessionId), safeDiagnosticJson(branched.thread))
      assert(
        branched.thread.name === `branch-${String(branchThreadId).slice(0, 8)}`,
        safeDiagnosticJson(branched.thread),
      )
      assert(
        String(branched.thread.threadId) === branchThreadId,
        safeDiagnosticJson(branched.thread),
      )
      assert(
        String(branched.acceptedCommands[0].sequence) === '1'
          && branched.acceptedCommands[0].type === 'USER_MESSAGE'
          && branched.replayed === false,
        safeDiagnosticJson(branched),
      )
      // 同一 batch 精确重放（同 id/payload/threadId）：返回既有 branch Thread，不新增 Session/Thread。
      const replay = await createNewThread(ctx, branchTarget)
      assert(replay.replayed === true, safeDiagnosticJson(replay))
      assert(
        String(replay.thread.threadId) === branchThreadId
          && String(replay.session?.sessionId) === String(sessionId)
          && replay.acceptedCommands[0].type === 'USER_MESSAGE',
        safeDiagnosticJson(replay),
      )
      const mainAfter = await getThread(ctx, mainTid)
      assert(
        String(mainAfter.headEntryId) === String(mainBefore.headEntryId)
          && String(mainAfter.version) === String(mainBefore.version)
          && String(mainAfter.nextCommandSequence) === String(mainBefore.nextCommandSequence),
        safeDiagnosticJson({ before: mainBefore, after: mainAfter }),
      )

      const finalThread = await waitForQuiescentThread(ctx, branchThreadId, {
        timeoutMs: 180_000,
        intervalMs: 500,
      })
      assert(finalThread.status === 'IDLE', safeDiagnosticJson(finalThread))
      const entries = await snapshotEntries(ctx, branchThreadId)
      assert(
        entries.some((entry) => String(entry.entryId) === String(assistantEntryId)),
        `branch path must include the startEntry: ${safeDiagnosticJson(entries)}`,
      )
      const branchUserIndex = findUserEntryIndex(entries, branchUserText)
      const assistantIndex = entries.findIndex(
        (entry) =>
          String(entry.entryId) === String(normalAssistantEntries(entries).at(-1)?.entryId),
      )
      assert(
        branchUserIndex >= 0 && branchUserIndex < assistantIndex,
        `branch turn must follow the branch head: ${safeDiagnosticJson(entries)}`,
      )
      const branchAssistant = normalAssistantEntries(entries).at(-1)
      const text = messageText(branchAssistant)
      assert(/\bBRANCH\b/i.test(text), `expected BRANCH reply, got: ${text}`)
      assert(
        String(entries[0].sessionId) === String(sessionId),
        `session must stay unchanged: ${safeDiagnosticJson(entries[0])}`,
      )
      ctx.writeArtifact(
        'branch-snapshot.json',
        JSON.stringify(sanitizeArtifact({ finalThread, entries }), null, 2),
      )
    } finally {
      await cleanupChat(ctx, chat)
      await cleanupAgent(ctx, agent)
    }
  },
})

registerCase({
  id: 'daemon.ready',
  level: 'L4',
  title: 'Environment GET capability 投影与 canonical 路由名称',
  requires: ['tools'],
  docs: 'Environment READY；Card UUID id 是 canonical 路由身份，name 是 display name，ready 是统一可用性标记；投影固定 10 个原子 capabilities + skills + rootPath（daemon canonical Environment Root），coding/process/LSP 为 workdir 版（version=2）、skill.load 保持 version=1，且不公开旧 tools 或 READY environment metadata',
  async run(ctx) {
    const environments = await listEnvironments(ctx)
    const match = environments.find((environment) => environment.name === ctx.daemonEnv)
    assert(match?.status === 'READY', safeDiagnosticJson(match))
    canonicalUuid(match.id, 'match.id')
    assert(
      typeof match.rootPath === 'string' && match.rootPath.length > 0,
      safeDiagnosticJson(match),
    )
    // 具体工具 arguments 必须携带显式绝对 workdir，因此 coding/process/LSP 能力版本升为 2；
    // 目录浏览能力已随产品 Workspace 删除，不再是 catalog 的一部分。
    const workdirCapabilityIds = [
      'fs.read',
      'fs.write',
      'fs.apply-edit',
      'process.exec',
      'fs.search',
      'fs.find',
      'lsp.goto-definition',
      'lsp.workspace-symbols',
      'lsp.java-decompile',
    ]
    const expectedCapabilityIds = [...workdirCapabilityIds, 'skill.load']
    const actualCapabilities = match.capabilities || []
    const ids = actualCapabilities.map((capability) => capability.id)
    assert(
      ids.length === expectedCapabilityIds.length
        && expectedCapabilityIds.every((id) => ids.includes(id))
        && actualCapabilities.every((capability) =>
          capability.version === (capability.id === 'skill.load' ? '1' : '2')),
      safeDiagnosticJson({ expectedCapabilityIds, workdirCapabilityIds, actualCapabilities }),
    )
    assert(!ids.includes('fs.list-directory'), safeDiagnosticJson(actualCapabilities))
    assert(Array.isArray(match.skills), safeDiagnosticJson(match))
    assert(
      !Object.hasOwn(match, 'tools')
        && !Object.hasOwn(match, 'mcpServers')
        && !Object.hasOwn(match, 'operatingSystem')
        && !Object.hasOwn(match, 'workingDirectory')
        && !Object.hasOwn(match, 'timeZone')
        && !Object.hasOwn(match, 'note'),
      safeDiagnosticJson(match),
    )
    assert(match.ready === true, safeDiagnosticJson(match))
    ctx.vars.daemonEnvironment = match
  },
})

registerCase({
  id: 'tool.read_turn',
  level: 'L4',
  title: '非 YOLO tool turn：WAITING_APPROVAL、ALLOW 后 Resource 外部化',
  requires: ['real', 'tools', 'canvas-storage'],
  docs: '使用 minimax-anthropic/MiniMax-M3 + backend S3 enabled（GlobalStorageToolResultHistoryMaterializer bean，否则 Resource 引用 fail-closed 无法进入 durable history）：yolo=false 时 read tool 进入 TOOL_WAITING_APPROVAL（ToolInvocationDTO 只暴露扁平 environmentId，无 location/environment wrapper）；approval ALLOW（decisionId 幂等）后执行；daemon 读取 >8KB fixture，Tool Result Entry 写入前摄入全局 Blob；durable tool_result.contents 只携带 resource(blobId,name,preview)，再通过 Blob 原件预签名下载验证字节；后续模型轮次在嵌套 Resource fallback/materialization 后成功返回非空 Assistant 回复并以 TURN_END(COMPLETED, continueModel=false) 收束',
  async run(ctx) {
    await getCase('daemon.ready').run(ctx)
    const minimaxModel = await requireRealMiniMaxM3(ctx)
    const suffix = cid().slice(0, 8)
    const { json: agentJson } = await ctx.call('POST', '/api/ai/catalog/agents', {
      name: `e2e-tool-agent-${suffix}`,
      description: 'Temporary E2E agent with the daemon read tool.',
      systemPrompt:
        'You are an E2E tool agent. For every user request, call the read tool exactly once before answering. '
        + 'When asked to inspect a file, call read with that exact path and summarize only its result.',
      model: `${minimaxModel.providerName}/${minimaxModel.modelName}`,
      variant: minimaxModel.variant,
      environmentId: ctx.vars.daemonEnvironment.id,
      config: {
        toolIds: ['base.read'],
        skills: [],
        subagents: [],
      },
    })
    const toolAgent = envelopeData(agentJson)
    assert(toolAgent?.name, safeDiagnosticJson(agentJson))
    assert(toolAgent?.environmentId === ctx.vars.daemonEnvironment.id, safeDiagnosticJson(agentJson))
    let chat = null
    try {
      const agentConfig = toolAgent.config
      assert(
        agentConfig
          && Object.keys(agentConfig).sort().join(',') === 'skills,subagents,toolIds'
          && JSON.stringify(agentConfig.toolIds) === JSON.stringify(['base.read'])
          && JSON.stringify(agentConfig.skills) === JSON.stringify([])
          && JSON.stringify(agentConfig.subagents) === JSON.stringify([]),
        `temporary tool Agent config must be exactly toolIds=[base.read], skills=[], subagents=[]: ${safeDiagnosticJson(toolAgent)}`,
      )
      const envRoot = process.env.DAEMON_ENV_ROOT
      assert(envRoot, 'DAEMON_ENV_ROOT must be exported by scripts/e2e/lib.sh')
      // 具体工具 arguments 必须携带目标 Daemon 上的显式绝对 workdir；Daemon 的 environment root
      // 就是 e2e fixture 所在目录，因此该绝对路径同时是 fixture 位置与调用 workdir。
      const workdir = path.resolve(envRoot)
      assert(path.isAbsolute(workdir), `workdir must be absolute: ${workdir}`)
      const fixturePath = path.join(envRoot, 'e2e-resource.txt')
      const fixtureLines = Array.from(
        { length: 32 },
        (_, index) => `E2E-RESOURCE-FIXTURE-${String(index).padStart(2, '0')} ${'x'.repeat(512)}`,
      )
      const fixtureContent = `${fixtureLines.join('\n')}\n`
      const expectedReadOutput = [
        'path: e2e-resource.txt',
        'ends_with_newline: yes',
        'lsp: unsupported',
        '',
        ...fixtureLines.map(
          (line, index) => `${String(index + 1).padStart(2, ' ')}|${line}`,
        ),
      ].join('\n')
      writeFileSync(fixturePath, fixtureContent)
      chat = await createChat(ctx, {
        title: `e2e-tool-chat-${suffix}`,
        agentName: toolAgent.name,
        yoloEnabled: false,
      })
      const accepted = await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId: cid(),
        threadId: cid(),
        rootSettings: branchSettingsOf(toolAgent, {
          providerName: minimaxModel.providerName,
          modelName: minimaxModel.modelName,
          variant: minimaxModel.variant,
        }),
        yoloEnabled: false,
        commands: [
          userMessageCommand(
            `必须调用 read 工具读取文件 e2e-resource.txt，使用参数 `
              + `{"path":"e2e-resource.txt","workdir":"${workdir}"}，`
              + '不要猜测或跳过工具，然后用一句话总结读取结果。',
            cid(),
          ),
        ],
      })
      const tid = accepted.thread.threadId
      assert(
        !Object.hasOwn(accepted.thread.branchSettings, 'workspacePath'),
        safeDiagnosticJson(accepted.thread.branchSettings),
      )
      let waiting = null
      let terminal = null
      for (let i = 0; i < 120; i++) {
        const current = await getThreadSnapshot(ctx, tid)
        if (current.thread.status === 'TOOL_WAITING_APPROVAL') {
          waiting = current
          break
        }
        if (
          current.thread.status === 'FAILED'
          || (
            current.thread.status === 'IDLE'
            && (current.entries || []).some((entry) => entryType(entry) === 'TURN_END')
          )
        ) {
          terminal = current
          break
        }
        await sleep(1000)
      }
      assert(
        waiting,
        `never reached TOOL_WAITING_APPROVAL on thread ${tid}: ${safeDiagnosticJson(terminal)}`,
      )
      const readInvocation = waiting.toolInvocations.find(
        (invocation) => invocation.toolName === 'read',
      )
      assert(readInvocation, `no WAITING_APPROVAL read invocation: ${safeDiagnosticJson(waiting)}`)
      assert(readInvocation.status === 'WAITING_APPROVAL', safeDiagnosticJson(readInvocation))
      assert(
        readInvocation.toolId === 'base.read',
        `WAITING read invocation must identify base.read: ${safeDiagnosticJson(readInvocation)}`,
      )
      assert(
        !Object.hasOwn(readInvocation, 'toolBackend'),
        `ToolInvocationDTO must not expose toolBackend: ${safeDiagnosticJson(readInvocation)}`,
      )
      assert(
        readInvocation.environmentId === ctx.vars.daemonEnvironment.id,
        `read invocation must freeze the Agent-owned EnvironmentId: ${safeDiagnosticJson({
          readInvocation,
          expectedEnvironmentId: ctx.vars.daemonEnvironment.id,
        })}`,
      )
      assert(
        !Object.hasOwn(readInvocation, 'environment'),
        `ToolInvocationDTO must not expose the removed EnvironmentBinding wrapper: ${safeDiagnosticJson(readInvocation)}`,
      )
      assert(
        !Object.hasOwn(readInvocation, 'location'),
        `ToolInvocationDTO must not expose location: ${safeDiagnosticJson(readInvocation)}`,
      )
      assert(
        readInvocation.toolCallId
          && Number.isSafeInteger(readInvocation.attempt)
          && readInvocation.attempt === 0,
        safeDiagnosticJson(readInvocation),
      )
      // workdir 只存在于具体工具 arguments：durable frozen arguments 必须携带显式绝对目录。
      const readArguments = JSON.parse(readInvocation.argumentsJson || '{}')
      assert(
        readArguments.path === 'e2e-resource.txt' && readArguments.workdir === workdir,
        `read arguments must carry the explicit absolute workdir: ${safeDiagnosticJson(readArguments)}`,
      )
      const approvalJson = JSON.parse(readInvocation.approvalJson || '{}')
      assert(
        approvalJson.required === true && approvalJson.decision == null,
        `approval must be required and undecided: ${safeDiagnosticJson(approvalJson)}`,
      )
      ctx.writeArtifact(
        'waiting-approval.json',
        JSON.stringify(sanitizeArtifact({ waiting, readInvocation }), null, 2),
      )

      const decisionId = cid()
      const decided = await approveToolInvocation(ctx, tid, readInvocation.id, {
        decision: 'ALLOW',
        decisionId,
        actor: 'web',
        reason: null,
      })
      assert(String(decided.id) === String(readInvocation.id), safeDiagnosticJson(decided))
      assert(decided.status === 'READY', safeDiagnosticJson(decided))
      const decidedApproval = JSON.parse(decided.approvalJson || '{}')
      assert(
        decidedApproval.decision === 'ALLOWED' && decidedApproval.decisionId === decisionId,
        `durable decision is ALLOWED (input is ALLOW): ${safeDiagnosticJson(decidedApproval)}`,
      )
      const replay = await approveToolInvocation(ctx, tid, readInvocation.id, {
        decision: 'ALLOW',
        decisionId,
        actor: 'web',
        reason: null,
      })
      const replayApproval = JSON.parse(replay.approvalJson || '{}')
      assert(
        replayApproval.decision === 'ALLOWED'
          && replayApproval.decidedAt === decidedApproval.decidedAt,
        `approval replay must keep the original decision: ${safeDiagnosticJson(replayApproval)}`,
      )

      const finalThread = await waitForQuiescentThread(ctx, tid, {
        timeoutMs: 180_000,
        intervalMs: 500,
      })
      assert(finalThread.status === 'IDLE', safeDiagnosticJson(finalThread))
      const finalSnapshot = await getThreadSnapshot(ctx, tid)
      const finalEntries = finalSnapshot.entries || []
      assert(
        !finalEntries.some((entry) => entryType(entry) === 'ASSISTANT_ERROR'),
        `completed tool turn must not contain ASSISTANT_ERROR: ${safeDiagnosticJson(finalEntries)}`,
      )
      const assistantEntries = normalAssistantEntries(finalEntries)
      const finalAssistant = assistantEntries.at(-1)
      assert(
        finalAssistant && messageText(finalAssistant).trim().length > 0,
        `completed tool turn must contain a non-empty final assistant reply: ${safeDiagnosticJson(finalEntries)}`,
      )
      const turnEndEntries = finalEntries.filter((entry) => entryType(entry) === 'TURN_END')
      assert(
        turnEndEntries.length > 0,
        `completed tool turn must contain TURN_END: ${safeDiagnosticJson(finalEntries)}`,
      )
      const lastTurnEnd = parseEntryPayload(turnEndEntries.at(-1))
      assert(
        lastTurnEnd.outcome === 'COMPLETED'
          && lastTurnEnd.continueModel === false
          && lastTurnEnd.reason == null
          && lastTurnEnd.closeRequestId == null,
        `expected final COMPLETED TURN_END: ${safeDiagnosticJson(lastTurnEnd)}`,
      )
      assert(
        finalSnapshot.toolInvocations.length === 0,
        `IDLE snapshot exposes no tool siblings: ${safeDiagnosticJson(finalSnapshot.toolInvocations)}`,
      )
      const toolEntries = finalEntries.filter((entry) => {
        if (entryType(entry) !== 'MESSAGE') return false
        const payload = parseEntryPayload(entry)
        return payload.message?.role === 'TOOL'
      })
      assert(
        toolEntries.length > 0,
        `no durable TOOL MESSAGE entry: ${safeDiagnosticJson(finalSnapshot.entries)}`,
      )
      const resources = []
      const toolResultContents = []
      for (const entry of toolEntries) {
        const contents = parseEntryPayload(entry).message?.contents || []
        for (const content of contents) {
          if (content?.type !== 'tool_result') continue
          toolResultContents.push(content)
          for (const child of content.contents || []) {
            if (child?.type === 'resource') resources.push(child)
          }
        }
      }
      assert(
        toolResultContents.length > 0,
        `tool_result contents missing: ${safeDiagnosticJson(finalSnapshot.entries)}`,
      )
      assert(
        resources.length >= 1,
        `expected at least one externalized resource: ${safeDiagnosticJson(toolResultContents)}`,
      )
      for (const resource of resources) {
        assert(
          /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
            String(resource.blobId || ''),
          ),
          `durable resource must carry a blobId: ${safeDiagnosticJson(resource)}`,
        )
        assert(
          typeof resource.name === 'string' && resource.name.trim(),
          `durable resource name must be present: ${safeDiagnosticJson(resource)}`,
        )
        assert(
          resource.preview == null || typeof resource.preview === 'string',
          `durable resource preview must be null or text: ${safeDiagnosticJson(resource)}`,
        )
        assert(
          !Object.hasOwn(resource, 'uri')
            && !Object.hasOwn(resource, 'mediaType')
            && !Object.hasOwn(resource, 'size')
            && !Object.hasOwn(resource, 'sha256'),
          `durable history must not copy transient ResourceRef facts: ${safeDiagnosticJson(resource)}`,
        )
      }
      const managed = resources[0]
      const { json: signedJson } = await ctx.call(
        'POST',
        `/api/storage/blobs/${managed.blobId}/download-url`,
      )
      const signed = envelopeData(signedJson)
      assert(signed.method === 'GET', safeDiagnosticJson(signed))
      assert(
        /^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/.test(String(signed.mediaType || '')),
        `blob mediaType must be canonical: ${safeDiagnosticJson(signed)}`,
      )
      assertDecimalVersion(signed.sizeBytes, 'blob.sizeBytes')
      const signedSizeBytes = Number(signed.sizeBytes)
      assert(
        Number.isSafeInteger(signedSizeBytes) && signedSizeBytes > 0,
        `blob sizeBytes must be a positive safe decimal: ${safeDiagnosticJson(signed)}`,
      )
      assert(
        signedSizeBytes === Buffer.byteLength(expectedReadOutput),
        `blob size ${signedSizeBytes} != formatted read output ${Buffer.byteLength(expectedReadOutput)}`,
      )
      const download = await fetch(signed.url, { headers: signed.headers || {} })
      assert(download.status === 200, `blob resource download status ${download.status}`)
      assert(
        String(download.headers.get('content-type') || '').startsWith(signed.mediaType),
        `managed resource media type mismatch: ${download.headers.get('content-type')}`,
      )
      const bytes = Buffer.from(await download.arrayBuffer())
      assert(
        bytes.length === signedSizeBytes,
        `download size ${bytes.length} != ${signedSizeBytes}`,
      )
      assert(
        bytes.equals(Buffer.from(expectedReadOutput)),
        'blob resource download bytes differ from the formatted read result',
      )
      ctx.writeArtifact(
        'tool-turn-final.json',
        JSON.stringify(sanitizeArtifact({ finalThread, finalSnapshot }), null, 2),
      )
    } finally {
      await cleanupChat(ctx, chat)
      await cleanupAgent(ctx, toolAgent)
    }
  },
})

// ---------------------------------------------------------------------------
// 4. 辅助函数
// ---------------------------------------------------------------------------

function assertAssistantAbortedEntry(entry) {
  const payload = parseEntryPayload(entry)
  assert(
    Object.keys(payload).length === 1 && Object.hasOwn(payload, 'message'),
    `ASSISTANT_ABORTED must contain only message: ${safeDiagnosticJson(payload)}`,
  )
  assert(
    !Object.hasOwn(payload, 'assistantMetadata'),
    `ASSISTANT_ABORTED must not carry assistantMetadata: ${safeDiagnosticJson(payload)}`,
  )
  assert(
    payload.message?.role === 'ASSISTANT' && Array.isArray(payload.message?.contents),
    `invalid ASSISTANT_ABORTED message: ${safeDiagnosticJson(payload)}`,
  )
  const contents = payload.message.contents
  assert(contents.length > 0, `ASSISTANT_ABORTED contents must not be empty: ${safeDiagnosticJson(payload)}`)
  for (const content of contents) {
    assert(
      content
      && typeof content === 'object'
      && !Array.isArray(content)
      && (content.type === 'text' || content.type === 'thinking')
      && typeof content.text === 'string',
      `ASSISTANT_ABORTED contains unsafe content: ${safeDiagnosticJson(content)}`,
    )
    assert(
      Object.keys(content).sort().join(',') === 'text,type',
      `ASSISTANT_ABORTED content has unexpected fields: ${safeDiagnosticJson(content)}`,
    )
  }
  assert(
    contents.some((content) => content.text.trim()),
    `expected non-empty durable partial content: ${safeDiagnosticJson(payload)}`,
  )
}

function entryType(entry) {
  return String(entry?.entryType || '').toUpperCase()
}

function normalAssistantEntries(entries) {
  return entries.filter((entry) => {
    if (entryType(entry) !== 'MESSAGE') return false
    return parseEntryPayload(entry).message?.role === 'ASSISTANT'
  })
}

function findUserEntryIndex(entries, marker) {
  return entries.findIndex((entry) => {
    if (entryType(entry) !== 'MESSAGE') return false
    const message = parseEntryPayload(entry).message
    if (message?.role !== 'USER' || !Array.isArray(message.contents)) return false
    return message.contents.some(
      (content) => content?.type === 'text' && String(content.text || '').includes(marker),
    )
  })
}

function messageText(entry) {
  const payload = parseEntryPayload(entry)
  return (payload.message?.contents || [])
    .filter((content) => content?.type === 'text')
    .map((content) => String(content.text || ''))
    .join('\n')
}

function parseEntryPayload(entry) {
  try {
    const payload = JSON.parse(entry?.payloadJson || '{}')
    assert(
      payload && typeof payload === 'object' && !Array.isArray(payload),
      `expected entry payload object: ${safeDiagnosticJson(entry)}`,
    )
    return payload
  } catch (error) {
    throw new Error(`invalid entry payload for ${entry?.entryId}: ${error.message}`)
  }
}
