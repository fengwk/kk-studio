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
  createEntryThread,
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
    `provider ${modelDef.providerName} not found in catalog: ${JSON.stringify(providers.map((p) => p.name))}`,
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
    `model ${modelDef.providerName}/${modelDef.modelName} does not declare variant ${modelDef.variant}: ${JSON.stringify(variants)}`,
  )

  return {
    providerName: modelDef.providerName,
    modelName: modelDef.modelName,
    variant: modelDef.variant,
    provider,
    model,
  }
}

/** 共享安全解析 minimax-anthropic/MiniMax-M3。 */
export async function requireRealMiniMaxM3(ctx) {
  return await resolveRealModel(ctx, MINIMAX_ANTHROPIC_M3)
}

/**
 * 严格断言 Assistant Metadata Usage 七字段均为非负安全整数，且当 requirePositiveIO 为 true 时校验事实有效。
 */
export function assertAssistantUsage(usage, { requirePositiveIO = true } = {}) {
  assert(usage && typeof usage === 'object', `usage must be an object: ${JSON.stringify(usage)}`)
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
      `usage.${field} must be a non-negative safe integer, got: ${value} in ${JSON.stringify(usage)}`,
    )
  }
  if (requirePositiveIO) {
    assert(
      usage.inputTokens > 0,
      `usage.inputTokens must be > 0: ${JSON.stringify(usage)}`,
    )
    assert(
      usage.outputTokens > 0,
      `usage.outputTokens must be > 0: ${JSON.stringify(usage)}`,
    )
    assert(
      usage.providerTotalTokens > 0,
      `usage.providerTotalTokens must be > 0: ${JSON.stringify(usage)}`,
    )
  }
}

/**
 * 脱敏写入 artifact 的数据：过滤所有凭证、密钥与 base URL。
 */
export function sanitizeArtifact(data) {
  if (data === null || data === undefined) return data
  if (typeof data === 'string') {
    return data
      .replace(/https?:\/\/[^\s"']+/g, '[REDACTED_URL]')
      .replace(/\bBearer\s+[^\s"']+/gi, 'Bearer [REDACTED]')
      .replace(
        /\b(TEST_[A-Z0-9_]*(?:API_KEY|BASE_URL)|apiKey|credential|authorization|secret|password)=([^\s&]+)/gi,
        '$1=[REDACTED]',
      )
  }
  if (Array.isArray(data)) {
    return data.map((item) => sanitizeArtifact(item))
  }
  if (typeof data === 'object') {
    const result = {}
    for (const [key, value] of Object.entries(data)) {
      const lower = key.toLowerCase()
      if (
        lower.includes('key')
        || lower.includes('secret')
        || lower.includes('password')
        || lower.includes('credential')
        || lower.includes('authorization')
        || lower === 'baseurl'
        || (lower.includes('token') && !lower.includes('tokens'))
      ) {
        result[key] = '[REDACTED]'
      } else if (lower === 'url' && typeof value === 'string') {
        result[key] = '[REDACTED_URL]'
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
    docs: `验证 ${def.providerName}/${def.modelName}（variant ${def.variant}）：创建临时无工具 Agent，system prompt 包含 >=16KiB 确定性前缀；首轮验证 durable TURN_START -> USER -> normal ASSISTANT -> TURN_END(COMPLETED)、IDLE、无 active invocation、usage 七字段合法且 input/output/providerTotal 事实有效；同 Thread 发 1~3 个短 follow-up，逐轮断言非空/marker 回复与 usage，最终要求至少一个 follow-up cacheReadTokens > 0，写脱敏 artifact`,
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
          `expected USER_MESSAGE command: ${JSON.stringify(accepted.acceptedCommands)}`,
        )

        const firstQuiescent = await waitForQuiescentThread(ctx, threadId, {
          timeoutMs: 180_000,
          intervalMs: 500,
        })
        assert(firstQuiescent.status === 'IDLE', `thread not IDLE: ${JSON.stringify(firstQuiescent)}`)

        const firstSnapshot = await getThreadSnapshot(ctx, threadId)
        assert(
          firstSnapshot.modelInvocation === null,
          `IDLE snapshot must expose no active model invocation: ${JSON.stringify(firstSnapshot.modelInvocation)}`,
        )
        assert(
          !('modelInvocations' in firstSnapshot),
          `snapshot DTO has no modelInvocations[]: ${JSON.stringify(Object.keys(firstSnapshot))}`,
        )
        assert(
          (firstSnapshot.queuedCommands || []).length === 0,
          `queued commands must be empty: ${JSON.stringify(firstSnapshot.queuedCommands)}`,
        )

        const firstEntries = firstSnapshot.entries || []
        const firstUserIndex = findUserEntryIndex(firstEntries, firstMarker)
        const firstTurnStartIndex = firstEntries.findIndex((entry) => entryType(entry) === 'TURN_START')
        const firstAssistants = normalAssistantEntries(firstEntries)
        assert(firstAssistants.length > 0, `no normal assistant entry found: ${JSON.stringify(firstEntries)}`)
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
          `expected TURN_START -> USER -> normal ASSISTANT MESSAGE -> TURN_END(COMPLETED): ${JSON.stringify(firstEntries)}`,
        )

        const firstTurnEndPayload = parseEntryPayload(firstEntries[firstTurnEndIndex])
        assert(
          firstTurnEndPayload.outcome === 'COMPLETED' && firstTurnEndPayload.continueModel === false,
          `expected COMPLETED TURN_END: ${JSON.stringify(firstTurnEndPayload)}`,
        )

        const firstText = messageText(firstAssistantEntry)
        assert(
          firstText.trim().length > 0 && firstText.includes(firstMarker),
          `first assistant reply must contain marker ${firstMarker}: ${firstText}`,
        )

        const firstAssistantPayload = parseEntryPayload(firstAssistantEntry)
        assertAssistantUsage(firstAssistantPayload.assistantMetadata?.usage, { requirePositiveIO: true })

        let cacheHit = false
        const followUpRecords = []
        for (let round = 1; round <= 3; round++) {
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
            `thread not IDLE after follow-up round ${round}: ${JSON.stringify(quiescent)}`,
          )

          const roundSnapshot = await getThreadSnapshot(ctx, threadId)
          const roundEntries = roundSnapshot.entries || []
          const roundUserIndex = findUserEntryIndex(roundEntries, followUpMarker)
          assert(
            roundUserIndex >= 0,
            `follow-up USER entry missing in round ${round}: ${JSON.stringify(roundEntries)}`,
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
          assertAssistantUsage(usage, { requirePositiveIO: true })

          followUpRecords.push({
            round,
            usage,
            reply: roundText,
          })

          if (usage.cacheReadTokens > 0) {
            cacheHit = true
            break
          }
        }

        assert(
          cacheHit,
          `expected at least one follow-up turn to have cacheReadTokens > 0 for ${def.title}. Usages: ${JSON.stringify(followUpRecords)}`,
        )

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
              firstRound: {
                usage: firstAssistantPayload.assistantMetadata?.usage,
                reply: firstText,
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
// 2. 四指定模型工具 case 注册 (L4, requires: ['real', 'tools'])
// ---------------------------------------------------------------------------
for (const def of REAL_MODEL_DEFINITIONS) {
  registerCase({
    id: `real.tool.${def.idSuffix}`,
    level: 'L4',
    title: `${def.title} 真实工具调用与 terminal replay`,
    requires: ['real', 'tools'],
    docs: `验证 ${def.providerName}/${def.modelName}（variant ${def.variant}）：创建临时 Agent，仅启用无需 Environment 的 base.goal.get；提示模型必须调用 get_goal 恰好一次、参数 {}，收到结果后回复唯一 marker；等待 IDLE 后严格断言：恰好一个 durable ASSISTANT tool_call（name get_goal，argumentsJson 是空 Object）、一个匹配 callId 的 TOOL tool_result 且文本含“no current branch goal”，随后 normal final Assistant 含 marker，Turn COMPLETED；两次模型 Assistant metadata usage 合法，写脱敏 artifact`,
    async run(ctx) {
      const resolved = await resolveRealModel(ctx, def)
      const suffix = cid().slice(0, 8)
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
          variant: resolved.variant,
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
            variant: resolved.variant,
          }),
          yoloEnabled: false,
          commands: [userMessageCommand(userPrompt, cid())],
        })
        assert(
          accepted.acceptedCommands[0]?.type === 'USER_MESSAGE',
          `expected USER_MESSAGE command: ${JSON.stringify(accepted.acceptedCommands)}`,
        )

        const finalThread = await waitForQuiescentThread(ctx, threadId, {
          timeoutMs: 180_000,
          intervalMs: 500,
        })
        assert(finalThread.status === 'IDLE', `thread not IDLE: ${JSON.stringify(finalThread)}`)

        const snapshot = await getThreadSnapshot(ctx, threadId)
        const entries = snapshot.entries || []
        assert(
          !entries.some((entry) => entryType(entry) === 'ASSISTANT_ERROR'),
          `unexpected ASSISTANT_ERROR in tool turn: ${JSON.stringify(entries)}`,
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
          `expected exactly one durable ASSISTANT tool_call, got ${toolCalls.length}: ${JSON.stringify(toolCalls.map((t) => t.content))}`,
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
        assert(callId && typeof callId === 'string', `toolCallId missing: ${JSON.stringify(toolCallContent)}`)

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
          `expected exactly one final assistant message after tool, got ${normalAssistantsAfterTool.length}: ${JSON.stringify(normalAssistantsAfterTool)}`,
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
        assert(turnEndEntries.length >= 1, `expected at least one TURN_END: ${JSON.stringify(entries)}`)
        const lastTurnEndPayload = parseEntryPayload(turnEndEntries.at(-1))
        assert(
          lastTurnEndPayload.outcome === 'COMPLETED' && lastTurnEndPayload.continueModel === false,
          `expected COMPLETED TURN_END: ${JSON.stringify(lastTurnEndPayload)}`,
        )

        // 两次模型 Assistant metadata usage 合法
        assertAssistantUsage(toolCallPayload.assistantMetadata?.usage, { requirePositiveIO: true })
        assertAssistantUsage(finalAssistantPayload.assistantMetadata?.usage, { requirePositiveIO: true })

        ctx.writeArtifact(
          `real-tool-${def.idSuffix}.json`,
          JSON.stringify(
            sanitizeArtifact({
              modelDef: {
                providerName: def.providerName,
                modelName: def.modelName,
                variant: def.variant,
                providerType: def.providerType,
              },
              threadId,
              toolCall: {
                callId,
                toolName: toolCallContent.toolName,
                argumentsJson: toolCallContent.argumentsJson,
                usage: toolCallPayload.assistantMetadata?.usage,
              },
              toolResult: {
                resultText,
              },
              finalAssistant: {
                reply: finalText,
                usage: finalAssistantPayload.assistantMetadata?.usage,
              },
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
        JSON.stringify(parentAgent),
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
      assert(finalThread.status === 'IDLE', JSON.stringify(finalThread))
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
      assert(taskResults.length === 1, `expected one task result: ${JSON.stringify(taskResults)}`)
      const taskResult = taskResults[0]
      assert(taskResult.rendererKey === 'task', JSON.stringify(taskResult))
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
      assert(entryType(childRoot) === 'ROOT', JSON.stringify(childSnapshot.entries))
      const context = parseEntryPayload(childRoot).subagentContext
      assert(
        String(context?.parentThreadId) === parentThreadId
          && String(context?.rootThreadId) === parentThreadId
          && canonicalUuid(context?.taskInvocationId, 'subagentContext.taskInvocationId')
          && context?.depth === 2,
        `invalid child ROOT subagentContext: ${JSON.stringify(context)}`,
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
        `initial message batch: ${JSON.stringify(startResult)}`,
      )
      assert(firstDelta.text.trim(), `expected non-empty text delta: ${JSON.stringify(firstDelta)}`)

      const beforeStop = await getThread(ctx, tid)
      const stopRequestId = cid()
      const expectedVersion = beforeStop.version
      const stop = await stopThread(ctx, tid, {
        stopRequestId,
        expectedVersion,
      })
      assert(stop.status === 'STOPPED', JSON.stringify(stop))
      assert(
        stop.stoppedTurnEndEntryId != null,
        JSON.stringify(stop),
      )
      assert(
        Number(stop.thread.version) === Number(beforeStop.version) + 1,
        `active stop must bump version by one: ${JSON.stringify({ beforeStop, stop })}`,
      )

      const entriesAfterStop = await snapshotEntries(ctx, tid)
      const abortedEntries = entriesAfterStop.filter((entry) => entryType(entry) === 'ASSISTANT_ABORTED')
      assert(
        abortedEntries.length === 1,
        `expected exactly one ASSISTANT_ABORTED: ${JSON.stringify(entriesAfterStop)}`,
      )
      const abortedEntry = abortedEntries[0]
      assertAssistantAbortedEntry(abortedEntry)
      const initialUserIndex = findUserEntryIndex(entriesAfterStop, initialMarker)
      assert(initialUserIndex >= 0, `initial USER entry missing: ${JSON.stringify(entriesAfterStop)}`)
      const realTurnEntries = entriesAfterStop.slice(initialUserIndex)
      assert(
        !realTurnEntries.some((entry) => entryType(entry) === 'ASSISTANT_ERROR'),
        `expected partial aborted barrier, not ASSISTANT_ERROR: ${JSON.stringify(realTurnEntries)}`,
      )
      assert(
        normalAssistantEntries(realTurnEntries).length === 0,
        `stopped invocation must not materialize a normal assistant MESSAGE: ${JSON.stringify(realTurnEntries)}`,
      )
      const abortedIndex = entriesAfterStop.findIndex(
        (entry) => String(entry.entryId) === String(abortedEntry.entryId),
      )
      assert(
        abortedIndex > initialUserIndex,
        `ASSISTANT_ABORTED must follow initial USER: ${JSON.stringify(entriesAfterStop)}`,
      )

      const replay = await stopThread(ctx, tid, {
        stopRequestId,
        expectedVersion,
      })
      assert(replay.status === 'REPLAYED', JSON.stringify(replay))
      assert(
        String(replay.stoppedTurnEndEntryId) === String(stop.stoppedTurnEndEntryId),
        `replay must identify the same stopped TURN_END: ${JSON.stringify({ stop, replay })}`,
      )
      assert(replay.cancelledCommandCount === 0, JSON.stringify(replay))
      assert(
        String(replay.thread.headEntryId) === String(stop.thread.headEntryId)
          && String(replay.thread.version) === String(stop.thread.version),
        `replay must not mutate the Thread: ${JSON.stringify({ stop, replay })}`,
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
      assert(followUp.acceptedCommands.length === 1, `follow-up batch: ${JSON.stringify(followUp)}`)
      const finalThread = await waitForQuiescentThread(ctx, tid, {
        timeoutMs: 120_000,
        intervalMs: 500,
      })
      const finalEntries = await snapshotEntries(ctx, tid)
      const finalAbortedEntries = finalEntries.filter((entry) => entryType(entry) === 'ASSISTANT_ABORTED')
      assert(
        finalAbortedEntries.length === 1
          && String(finalAbortedEntries[0].entryId) === String(abortedEntry.entryId),
        `aborted barrier changed after follow-up: ${JSON.stringify(finalEntries)}`,
      )

      const finalInitialUserIndex = findUserEntryIndex(finalEntries, initialMarker)
      assert(
        finalInitialUserIndex >= 0,
        `initial USER entry missing after follow-up: ${JSON.stringify(finalEntries)}`,
      )
      const finalAbortedIndex = finalEntries.findIndex(
        (entry) => String(entry.entryId) === String(abortedEntry.entryId),
      )
      const followUpIndex = findUserEntryIndex(finalEntries, followUpMarker)
      const finalEntriesAfterInitial = finalEntries.slice(finalInitialUserIndex)
      const finalRealTurnAssistants = normalAssistantEntries(finalEntriesAfterInitial)
      assert(
        finalRealTurnAssistants.length === 1,
        `expected exactly one normal assistant for follow-up: ${JSON.stringify(finalEntries)}`,
      )
      const finalAssistantIndex = finalEntries.findIndex(
        (entry) => String(entry.entryId) === String(finalRealTurnAssistants[0].entryId),
      )
      assert(
        finalInitialUserIndex < finalAbortedIndex
          && finalAbortedIndex < followUpIndex
          && followUpIndex < finalAssistantIndex,
        `expected USER -> ASSISTANT_ABORTED -> follow-up USER -> assistant MESSAGE: ${JSON.stringify(finalEntries)}`,
      )
      assert(
        !finalEntriesAfterInitial.some((entry) => entryType(entry) === 'ASSISTANT_ERROR'),
        `unexpected cancellation barrier after durable partial: ${JSON.stringify(finalEntriesAfterInitial)}`,
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
  id: 'branch.same_session_entry_thread',
  level: 'L3',
  title: 'ENTRY 同 Session 分支创建（真实分支 turn）',
  requires: ['real', 'branch'],
  docs: '使用 minimax-anthropic/MiniMax-M3：在同一 Session 历史 assistant Entry 下用 ENTRY target 开新 Thread（不复制 Entry）：sessionId 不变、新 Thread root-to-head 路径包含 startEntry 与分支 USER、分支 turn 继续产生独立 assistant；原 Thread head/version/nextCommandSequence 不变',
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
      assert(accepted.acceptedCommands.length === 1, JSON.stringify(accepted.acceptedCommands))
      const mainQuiescent = await waitForQuiescentThread(ctx, mainTid, {
        timeoutMs: 180_000,
        intervalMs: 500,
      })
      assert(mainQuiescent.status === 'IDLE', JSON.stringify(mainQuiescent))
      const mainSnapshot = await getThreadSnapshot(ctx, mainTid)
      const mainAssistants = normalAssistantEntries(mainSnapshot.entries || [])
      assert(mainAssistants.length > 0, 'missing main assistant entry')
      const assistantEntryId = String(mainAssistants.at(-1).entryId)

      const current = await getThread(ctx, mainTid)
      assert(String(current.sessionId) === String(sessionId), JSON.stringify(current))
      const mainBefore = {
        headEntryId: current.headEntryId,
        version: current.version,
        nextCommandSequence: current.nextCommandSequence,
      }
      const branchThreadId = cid()
      const branchUserText = '在分支上只回复单词 BRANCH，不要调用工具。'
      const branched = await createEntryThread(ctx, {
        owner: chatOwner(chat.id),
        sessionId,
        startEntryId: assistantEntryId,
        threadId: branchThreadId,
        yoloEnabled: current.yoloEnabled,
        commands: [userMessageCommand(branchUserText, cid())],
      })
      assert(String(branched.thread.sessionId) === String(sessionId), JSON.stringify(branched.thread))
      assert(
        String(branched.thread.threadId) === branchThreadId,
        JSON.stringify(branched.thread),
      )
      assert(
        String(branched.acceptedCommands[0].sequence) === '1'
          && branched.acceptedCommands[0].type === 'USER_MESSAGE'
          && branched.replayed === false,
        JSON.stringify(branched),
      )
      const mainAfter = await getThread(ctx, mainTid)
      assert(
        String(mainAfter.headEntryId) === String(mainBefore.headEntryId)
          && String(mainAfter.version) === String(mainBefore.version)
          && String(mainAfter.nextCommandSequence) === String(mainBefore.nextCommandSequence),
        JSON.stringify({ before: mainBefore, after: mainAfter }),
      )

      const finalThread = await waitForQuiescentThread(ctx, branchThreadId, {
        timeoutMs: 180_000,
        intervalMs: 500,
      })
      assert(finalThread.status === 'IDLE', JSON.stringify(finalThread))
      const entries = await snapshotEntries(ctx, branchThreadId)
      assert(
        entries.some((entry) => String(entry.entryId) === String(assistantEntryId)),
        `branch path must include the startEntry: ${JSON.stringify(entries)}`,
      )
      const branchUserIndex = findUserEntryIndex(entries, branchUserText)
      const assistantIndex = entries.findIndex(
        (entry) =>
          String(entry.entryId) === String(normalAssistantEntries(entries).at(-1)?.entryId),
      )
      assert(
        branchUserIndex >= 0 && branchUserIndex < assistantIndex,
        `branch turn must follow the branch head: ${JSON.stringify(entries)}`,
      )
      const branchAssistant = normalAssistantEntries(entries).at(-1)
      const text = messageText(branchAssistant)
      assert(/\bBRANCH\b/i.test(text), `expected BRANCH reply, got: ${text}`)
      assert(
        String(entries[0].sessionId) === String(sessionId),
        `session must stay unchanged: ${JSON.stringify(entries[0])}`,
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
  docs: 'Environment READY；Card UUID id 是 canonical 路由身份，name 是 display name，ready 是统一可用性标记；投影固定 12 个原子 capabilities（version=1）+ skills + rootPath（daemon canonical Environment Root），且不公开旧 tools 或 READY environment metadata',
  async run(ctx) {
    const environments = await listEnvironments(ctx)
    const match = environments.find((environment) => environment.name === ctx.daemonEnv)
    assert(match?.status === 'READY', JSON.stringify(match))
    canonicalUuid(match.id, 'match.id')
    assert(
      typeof match.rootPath === 'string' && match.rootPath.length > 0,
      JSON.stringify(match),
    )
    const expectedCapabilityIds = [
      'fs.read',
      'fs.write',
      'fs.apply-edit',
      'fs.apply-patch',
      'process.exec',
      'fs.search',
      'fs.find',
      'fs.list-directory',
      'lsp.goto-definition',
      'lsp.workspace-symbols',
      'lsp.java-decompile',
      'skill.load',
    ]
    const actualCapabilities = match.capabilities || []
    const ids = actualCapabilities.map((capability) => capability.id)
    assert(
      ids.length === expectedCapabilityIds.length
        && expectedCapabilityIds.every((id) => ids.includes(id))
        && actualCapabilities.every((capability) => capability.version === '1'),
      JSON.stringify({ expectedCapabilityIds, actualCapabilities }),
    )
    assert(Array.isArray(match.skills), JSON.stringify(match))
    assert(
      !Object.hasOwn(match, 'tools')
        && !Object.hasOwn(match, 'mcpServers')
        && !Object.hasOwn(match, 'operatingSystem')
        && !Object.hasOwn(match, 'workingDirectory')
        && !Object.hasOwn(match, 'timeZone')
        && !Object.hasOwn(match, 'note'),
      JSON.stringify(match),
    )
    assert(match.ready === true, JSON.stringify(match))
    ctx.vars.daemonEnvironment = match
  },
})

registerCase({
  id: 'daemon.directories',
  level: 'L4',
  title: 'Environment Root 单层目录浏览 API',
  requires: ['tools'],
  docs: 'GET /api/harness/environments/{id}/directories（control-plane 只读）：缺省 path="." 浏览 root——canonical 相对 wire path、displayPath 等于请求 path 的最后一段（root 为 "."，只作展示、绝不暴露 daemon 本地绝对路径）、root 的 parentPath="."、truncated 布尔、gitBranch 可空、entries 只含直属子目录（{name,path}：name 是目录名且等于 path 最后一段，path 是请求目录的直接子路径）；显式 path="." 与缺省一致；".." 段 400 INVALID_PATH、不存在目录 404 NOT_FOUND、非法环境 ID 400',
  async run(ctx) {
    const envId = ctx.vars.daemonEnvironment?.id
    assert(envId, 'daemonEnvironment must have canonical UUID id')
    const base = `/api/harness/environments/${encodeURIComponent(envId)}/directories`
    const { json } = await ctx.call('GET', base)
    const dir = envelopeData(json)
    assert(dir?.path === '.', JSON.stringify(json))
    assert(dir.displayPath === '.', JSON.stringify(json))
    assert(dir.parentPath === '.', JSON.stringify(json))
    assert(typeof dir.truncated === 'boolean', JSON.stringify(json))
    assert(
      !Object.hasOwn(dir, 'gitBranch') || dir.gitBranch === null || typeof dir.gitBranch === 'string',
      JSON.stringify(json),
    )
    assert(Array.isArray(dir.entries), JSON.stringify(json))
    for (const entry of dir.entries) {
      assert(
        typeof entry.name === 'string'
          && entry.name.length > 0
          && typeof entry.path === 'string'
          && entry.path.length > 0,
        JSON.stringify(entry),
      )
      assert(!Object.hasOwn(entry, 'displayPath'), JSON.stringify(entry))
      const prefix = dir.path === '.' ? '' : `${dir.path}/`
      assert(entry.path.startsWith(prefix), JSON.stringify(entry))
      assert(!entry.path.slice(prefix.length).includes('/'), JSON.stringify(entry))
      assert(entry.name === entry.path.split('/').at(-1), JSON.stringify(entry))
    }
    const explicit = envelopeData(
      (await ctx.call('GET', `${base}?path=${encodeURIComponent('.')}`)).json,
    )
    assert(
      explicit.path === '.' && explicit.displayPath === '.' && explicit.parentPath === '.',
      JSON.stringify(explicit),
    )
    const invalid = await expectHttpError(
      () => ctx.call('GET', `${base}?path=${encodeURIComponent('../escape')}`),
      { status: 400 },
    )
    assert(String(invalid.body).includes('INVALID_PATH'), invalid.body)
    const missing = await expectHttpError(
      () => ctx.call('GET', `${base}?path=${encodeURIComponent('no-such-dir-zz')}`),
      { status: 404 },
    )
    assert(String(missing.body).includes('NOT_FOUND'), missing.body)
    await expectHttpError(
      () => ctx.call('GET', '/api/harness/environments/Not-Canonical/directories'),
      { status: 400 },
    )
  },
})

registerCase({
  id: 'tool.read_turn',
  level: 'L4',
  title: '非 YOLO tool turn：WAITING_APPROVAL、ALLOW 后 Resource 外部化',
  requires: ['real', 'tools', 'canvas-storage'],
  docs: '使用 minimax-anthropic/MiniMax-M3 + backend S3 enabled（GlobalStorageToolResultHistoryMaterializer bean，否则 Resource 引用 fail-closed 无法进入 durable history）：yolo=false 时 read tool 进入 TOOL_WAITING_APPROVAL（冻结 EnvironmentBinding、无 location）；approval ALLOW（decisionId 幂等）后执行；daemon 读取 >8KB fixture，Tool Result Entry 写入前摄入全局 Blob；durable tool_result.contents 只携带 resource(blobId,name,preview)，再通过 Blob 原件预签名下载验证字节；后续模型轮次在嵌套 Resource fallback/materialization 后成功返回非空 Assistant 回复并以 TURN_END(COMPLETED, continueModel=false) 收束',
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
    assert(toolAgent?.name, JSON.stringify(agentJson))
    assert(toolAgent?.environmentId === ctx.vars.daemonEnvironment.id, JSON.stringify(agentJson))
    let chat = null
    try {
      const agentConfig = toolAgent.config
      assert(
        agentConfig
          && Object.keys(agentConfig).sort().join(',') === 'skills,subagents,toolIds'
          && JSON.stringify(agentConfig.toolIds) === JSON.stringify(['base.read'])
          && JSON.stringify(agentConfig.skills) === JSON.stringify([])
          && JSON.stringify(agentConfig.subagents) === JSON.stringify([]),
        `temporary tool Agent config must be exactly toolIds=[base.read], skills=[], subagents=[]: ${JSON.stringify(toolAgent)}`,
      )
      const expectedEnvironment = {
        environmentId: ctx.vars.daemonEnvironment.id,
        workspacePath: '.',
      }
      const envRoot = process.env.DAEMON_ENV_ROOT
      assert(envRoot, 'DAEMON_ENV_ROOT must be exported by scripts/e2e/lib.sh')
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
        workspacePath: '.',
      })
      const accepted = await createNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId: cid(),
        threadId: cid(),
        rootSettings: branchSettingsOf(
          toolAgent,
          {
            providerName: minimaxModel.providerName,
            modelName: minimaxModel.modelName,
            variant: minimaxModel.variant,
          },
          { workspacePath: '.' },
        ),
        yoloEnabled: false,
        commands: [
          userMessageCommand(
            '必须调用 read 工具读取文件 e2e-resource.txt，使用参数 {"path":"e2e-resource.txt"}，'
              + '不要猜测或跳过工具，然后用一句话总结读取结果。',
            cid(),
          ),
        ],
      })
      const tid = accepted.thread.threadId
      assert(
        accepted.thread.branchSettings.workspacePath === '.',
        JSON.stringify(accepted.thread.branchSettings),
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
        `never reached TOOL_WAITING_APPROVAL on thread ${tid}: ${JSON.stringify(terminal)}`,
      )
      const readInvocation = waiting.toolInvocations.find(
        (invocation) => invocation.toolName === 'read',
      )
      assert(readInvocation, `no WAITING_APPROVAL read invocation: ${JSON.stringify(waiting)}`)
      assert(readInvocation.status === 'WAITING_APPROVAL', JSON.stringify(readInvocation))
      assert(
        readInvocation.toolId === 'base.read',
        `WAITING read invocation must identify base.read: ${JSON.stringify(readInvocation)}`,
      )
      assert(
        !Object.hasOwn(readInvocation, 'toolBackend'),
        `ToolInvocationDTO must not expose toolBackend: ${JSON.stringify(readInvocation)}`,
      )
      assert(
        JSON.stringify(readInvocation.environment) === JSON.stringify(expectedEnvironment),
        `read invocation must freeze the complete Environment binding: ${JSON.stringify({
          readInvocation,
          expectedEnvironment,
        })}`,
      )
      assert(
        !Object.hasOwn(readInvocation, 'location'),
        `ToolInvocationDTO must not expose location: ${JSON.stringify(readInvocation)}`,
      )
      assert(
        readInvocation.toolCallId
          && Number.isSafeInteger(readInvocation.attempt)
          && readInvocation.attempt === 0,
        JSON.stringify(readInvocation),
      )
      const approvalJson = JSON.parse(readInvocation.approvalJson || '{}')
      assert(
        approvalJson.required === true && approvalJson.decision == null,
        `approval must be required and undecided: ${JSON.stringify(approvalJson)}`,
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
      assert(String(decided.id) === String(readInvocation.id), JSON.stringify(decided))
      assert(decided.status === 'READY', JSON.stringify(decided))
      const decidedApproval = JSON.parse(decided.approvalJson || '{}')
      assert(
        decidedApproval.decision === 'ALLOWED' && decidedApproval.decisionId === decisionId,
        `durable decision is ALLOWED (input is ALLOW): ${JSON.stringify(decidedApproval)}`,
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
        `approval replay must keep the original decision: ${JSON.stringify(replayApproval)}`,
      )

      const finalThread = await waitForQuiescentThread(ctx, tid, {
        timeoutMs: 180_000,
        intervalMs: 500,
      })
      assert(finalThread.status === 'IDLE', JSON.stringify(finalThread))
      const finalSnapshot = await getThreadSnapshot(ctx, tid)
      const finalEntries = finalSnapshot.entries || []
      assert(
        !finalEntries.some((entry) => entryType(entry) === 'ASSISTANT_ERROR'),
        `completed tool turn must not contain ASSISTANT_ERROR: ${JSON.stringify(finalEntries)}`,
      )
      const assistantEntries = normalAssistantEntries(finalEntries)
      const finalAssistant = assistantEntries.at(-1)
      assert(
        finalAssistant && messageText(finalAssistant).trim().length > 0,
        `completed tool turn must contain a non-empty final assistant reply: ${JSON.stringify(finalEntries)}`,
      )
      const turnEndEntries = finalEntries.filter((entry) => entryType(entry) === 'TURN_END')
      assert(
        turnEndEntries.length > 0,
        `completed tool turn must contain TURN_END: ${JSON.stringify(finalEntries)}`,
      )
      const lastTurnEnd = parseEntryPayload(turnEndEntries.at(-1))
      assert(
        lastTurnEnd.outcome === 'COMPLETED'
          && lastTurnEnd.continueModel === false
          && lastTurnEnd.reason == null
          && lastTurnEnd.closeRequestId == null,
        `expected final COMPLETED TURN_END: ${JSON.stringify(lastTurnEnd)}`,
      )
      assert(
        finalSnapshot.toolInvocations.length === 0,
        `IDLE snapshot exposes no tool siblings: ${JSON.stringify(finalSnapshot.toolInvocations)}`,
      )
      const toolEntries = finalEntries.filter((entry) => {
        if (entryType(entry) !== 'MESSAGE') return false
        const payload = parseEntryPayload(entry)
        return payload.message?.role === 'TOOL'
      })
      assert(
        toolEntries.length > 0,
        `no durable TOOL MESSAGE entry: ${JSON.stringify(finalSnapshot.entries)}`,
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
        `tool_result contents missing: ${JSON.stringify(finalSnapshot.entries)}`,
      )
      assert(
        resources.length >= 1,
        `expected at least one externalized resource: ${JSON.stringify(toolResultContents)}`,
      )
      for (const resource of resources) {
        assert(
          /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
            String(resource.blobId || ''),
          ),
          `durable resource must carry a blobId: ${JSON.stringify(resource)}`,
        )
        assert(
          typeof resource.name === 'string' && resource.name.trim(),
          `durable resource name must be present: ${JSON.stringify(resource)}`,
        )
        assert(
          resource.preview == null || typeof resource.preview === 'string',
          `durable resource preview must be null or text: ${JSON.stringify(resource)}`,
        )
        assert(
          !Object.hasOwn(resource, 'uri')
            && !Object.hasOwn(resource, 'mediaType')
            && !Object.hasOwn(resource, 'size')
            && !Object.hasOwn(resource, 'sha256'),
          `durable history must not copy transient ResourceRef facts: ${JSON.stringify(resource)}`,
        )
      }
      const managed = resources[0]
      const { json: signedJson } = await ctx.call(
        'POST',
        `/api/storage/blobs/${managed.blobId}/download-url`,
      )
      const signed = envelopeData(signedJson)
      assert(signed.method === 'GET', JSON.stringify(signed))
      assert(
        /^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/.test(String(signed.mediaType || '')),
        `blob mediaType must be canonical: ${JSON.stringify(signed)}`,
      )
      assertDecimalVersion(signed.sizeBytes, 'blob.sizeBytes')
      const signedSizeBytes = Number(signed.sizeBytes)
      assert(
        Number.isSafeInteger(signedSizeBytes) && signedSizeBytes > 0,
        `blob sizeBytes must be a positive safe decimal: ${JSON.stringify(signed)}`,
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
    `ASSISTANT_ABORTED must contain only message: ${JSON.stringify(payload)}`,
  )
  assert(
    !Object.hasOwn(payload, 'assistantMetadata'),
    `ASSISTANT_ABORTED must not carry assistantMetadata: ${JSON.stringify(payload)}`,
  )
  assert(
    payload.message?.role === 'ASSISTANT' && Array.isArray(payload.message?.contents),
    `invalid ASSISTANT_ABORTED message: ${JSON.stringify(payload)}`,
  )
  const contents = payload.message.contents
  assert(contents.length > 0, `ASSISTANT_ABORTED contents must not be empty: ${JSON.stringify(payload)}`)
  for (const content of contents) {
    assert(
      content
      && typeof content === 'object'
      && !Array.isArray(content)
      && (content.type === 'text' || content.type === 'thinking')
      && typeof content.text === 'string',
      `ASSISTANT_ABORTED contains unsafe content: ${JSON.stringify(content)}`,
    )
    assert(
      Object.keys(content).sort().join(',') === 'text,type',
      `ASSISTANT_ABORTED content has unexpected fields: ${JSON.stringify(content)}`,
    )
  }
  assert(
    contents.some((content) => content.text.trim()),
    `expected non-empty durable partial content: ${JSON.stringify(payload)}`,
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
      `expected entry payload object: ${JSON.stringify(entry)}`,
    )
    return payload
  } catch (error) {
    throw new Error(`invalid entry payload for ${entry?.entryId}: ${error.message}`)
  }
}
