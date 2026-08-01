import { assert, envelopeData, pageResults, sleep, cid } from '../lib/http.mjs'
import {
  createChatThread,
  createBootstrappedThread,
  getThread,
  getThreadSnapshot,
  snapshotEntries,
  rebindWhenQuiescent,
  waitForModelTextDeltaAfterSseConnected,
  waitForThreadInputApplied,
  waitForQuiescentThread,
} from '../lib/harness.mjs'
import { registerCase, getCase } from '../lib/registry.mjs'

registerCase({
  id: 'real.text_turn',
  level: 'L2',
  title: '真实 Provider 文本轮次成功并记账',
  requires: ['real'],
  docs: '仅 minimax/MiniMax-M2.7：bootstrap 后发消息等到 IDLE；assistant entry；usage>0',
  async run(ctx) {
    await requireRealMiniMaxM27(ctx)
    assert(
      ctx.vars.provider?.configured && ctx.vars.provider?.baseUrl,
      'minimax requires TEST_MINIMAX_BASE_URL and TEST_MINIMAX_API_KEY',
    )
    const { session, thread } = await createBootstrappedThread(ctx, {
      agentDefinitionId: ctx.vars.agent.id,
      title: `e2e-real-${cid().slice(0, 8)}`,
    })
    const tid = thread.threadId
    await ctx.call('POST', `/api/ai/runtime/threads/${tid}/messages`, {
      content: '只回复单词 OK，不要调用工具，不要解释。',
      clientMessageId: cid(),
      expectedExecutionEpoch: Number(thread.executionEpoch),
    })
    let finalStatus = null
    for (let i = 0; i < 90; i++) {
      const { json } = await ctx.call('GET', `/api/ai/runtime/threads/${tid}`)
      const thread = envelopeData(json)
      finalStatus = thread.status
      if ((finalStatus === 'IDLE' || finalStatus === 'FAILED') && !thread.processing) break
      await sleep(1000)
    }
    assert(finalStatus === 'IDLE', `expected IDLE, got ${finalStatus}`)
    const threadSnapshot = await getThreadSnapshot(ctx, tid)
    const assistantEntries = []
    for (const entry of threadSnapshot.entries || []) {
      if (String(entry.entryType || '').toUpperCase() !== 'MESSAGE') continue
      const payload = JSON.parse(entry.payloadJson || '{}')
      if (String(payload.message?.role || '').toUpperCase() === 'ASSISTANT') assistantEntries.push(entry)
    }
    assert(assistantEntries.length > 0, 'no assistant entry')
    const usage = threadSnapshot.usage
    assert(Number(usage.recordCount || 0) >= 1, JSON.stringify(usage))
    ctx.vars.realThreadId = tid
    ctx.vars.realSessionId = session.sessionId
    ctx.vars.assistantEntryId = String(assistantEntries.at(-1).entryId)
    ctx.writeArtifact('usage.json', JSON.stringify(usage, null, 2))
  },
})

registerCase({
  id: 'real.queued_input_batch',
  level: 'L2',
  title: '运行中连续入队消息在下一 turn 合并收割',
  requires: ['real'],
  docs:
    '首轮流式执行期间连续入队两条 USER_MESSAGE；下一 turn 同批 APPLIED，只创建一个 ModelInvocation 和一个 assistant MESSAGE',
  async run(ctx) {
    await requireRealMiniMaxM27(ctx)
    assert(
      ctx.vars.provider?.configured && ctx.vars.provider?.baseUrl,
      'minimax requires TEST_MINIMAX_BASE_URL and TEST_MINIMAX_API_KEY',
    )

    const initialMarker = `QUEUE-INITIAL-${cid()}`
    const firstMarker = `QUEUE-FIRST-${cid()}`
    const secondMarker = `QUEUE-SECOND-${cid()}`
    const { thread } = await createBootstrappedThread(ctx, {
      agentDefinitionId: ctx.vars.agent.id,
      title: `e2e-queue-batch-${cid().slice(0, 8)}`,
    })
    const tid = String(thread.threadId)
    const epoch = Number(thread.executionEpoch)
    const { signal: firstDelta, startResult } =
      await waitForModelTextDeltaAfterSseConnected(
        ctx,
        tid,
        () =>
          ctx.call('POST', `/api/ai/runtime/threads/${tid}/messages`, {
            content:
              `${initialMarker}\n不要调用工具。立即逐行输出 80 行短句，每行以“批次等待”开头并带连续编号；`
              + '不要总结，不要提前结束。',
            clientMessageId: cid(),
            expectedExecutionEpoch: epoch,
          }),
        { timeoutMs: 90_000 },
      )
    assert(startResult.status === 202, `initial message status ${startResult.status}`)
    assert(firstDelta.text.trim(), `expected non-empty text delta: ${JSON.stringify(firstDelta)}`)

    const firstQueued = await ctx.call('POST', `/api/ai/runtime/threads/${tid}/messages`, {
      content: `${firstMarker}\n这是下一 turn 队列批次的第一条消息。`,
      clientMessageId: cid(),
      expectedExecutionEpoch: epoch,
    })
    const secondQueued = await ctx.call('POST', `/api/ai/runtime/threads/${tid}/messages`, {
      content: `${secondMarker}\n结合前一条消息，只回复单词 BATCHED，不要解释。`,
      clientMessageId: cid(),
      expectedExecutionEpoch: epoch,
    })
    assert(firstQueued.status === 202, `first queued message status ${firstQueued.status}`)
    assert(secondQueued.status === 202, `second queued message status ${secondQueued.status}`)

    const finalThread = await waitForQuiescentThread(ctx, tid, {
      timeoutMs: 180_000,
      intervalMs: 500,
    })
    const snapshot = await getThreadSnapshot(ctx, tid)
    const entries = snapshot.entries || []
    const initialUserIndex = findUserEntryIndex(entries, initialMarker)
    const firstUserIndex = findUserEntryIndex(entries, firstMarker)
    const secondUserIndex = findUserEntryIndex(entries, secondMarker)
    const assistants = normalAssistantEntries(entries)
    assert(
      assistants.length === 2,
      `expected initial assistant plus one batched assistant: ${JSON.stringify(entries)}`,
    )
    const initialAssistantIndex = entries.findIndex(
      (entry) => String(entry.entryId) === String(assistants[0].entryId),
    )
    const batchedAssistantIndex = entries.findIndex(
      (entry) => String(entry.entryId) === String(assistants[1].entryId),
    )
    assert(
      initialUserIndex >= 0
      && initialUserIndex < initialAssistantIndex
      && initialAssistantIndex < firstUserIndex
      && firstUserIndex < secondUserIndex
      && secondUserIndex < batchedAssistantIndex,
      `expected initial USER -> assistant -> queued USER -> queued USER -> one assistant: ${JSON.stringify(entries)}`,
    )

    const inputs = snapshot.inputs || []
    const queuedBatchInputs = inputs.filter((input) => {
      const payload = String(input.payloadJson || '')
      return payload.includes(firstMarker) || payload.includes(secondMarker)
    })
    assert(
      queuedBatchInputs.length === 2
      && queuedBatchInputs.every((input) => input.status === 'APPLIED'),
      `queued batch inputs were not both APPLIED: ${JSON.stringify(inputs)}`,
    )
    const modelInvocations = snapshot.modelInvocations || []
    assert(
      modelInvocations.length === 2,
      `expected one initial and one batched ModelInvocation: ${JSON.stringify(modelInvocations)}`,
    )
    const secondUserEntry = entries[secondUserIndex]
    assert(
      modelInvocations.some(
        (invocation) =>
          String(invocation.sourceHeadEntryId) === String(secondUserEntry.entryId),
      ),
      `batched invocation must use the final queued USER as source head: ${JSON.stringify(modelInvocations)}`,
    )
    ctx.writeArtifact(
      'queued-input-batch.json',
      JSON.stringify(
        { firstDelta, finalThread, entries, inputs, modelInvocations },
        null,
        2,
      ),
    )
  },
})

registerCase({
  id: 'real.stop_partial_continue',
  level: 'L2',
  title: '真实流式 /stop 持久化 partial 并继续新一轮',
  requires: ['real'],
  docs:
    '仅 minimax/MiniMax-M2.7：首个非空文本 delta 后 stop；durable ASSISTANT_ABORTED 关闭旧 debt；follow-up 位于 barrier 后并仅产生一个新 assistant MESSAGE',
  async run(ctx) {
    await requireRealMiniMaxM27(ctx)
    assert(
      ctx.vars.provider?.configured && ctx.vars.provider?.baseUrl,
      'minimax requires TEST_MINIMAX_BASE_URL and TEST_MINIMAX_API_KEY',
    )

    const initialMarker = `STOP-PARTIAL-${cid()}`
    const followUpMarker = `FOLLOW-UP-${cid()}`
    const initialPrompt =
      `${initialMarker}\n`
      + '不要调用任何工具。请立即开始逐行输出 120 行短句，每行都以“流式验证”开头并带连续编号；'
      + '不要总结，不要提前结束。'
    const followUpPrompt =
      `${followUpMarker}\n只回复单词 CONTINUED，不要调用工具，不要解释。`
    const { thread } = await createBootstrappedThread(ctx, {
      agentDefinitionId: ctx.vars.agent.id,
      title: `e2e-stop-partial-${cid().slice(0, 8)}`,
      yoloEnabled: false,
    })
    const tid = String(thread.threadId)

    const { signal: firstDelta, startResult } =
      await waitForModelTextDeltaAfterSseConnected(
        ctx,
        tid,
        () =>
          ctx.call('POST', `/api/ai/runtime/threads/${tid}/messages`, {
            content: initialPrompt,
            clientMessageId: cid(),
            expectedExecutionEpoch: Number(thread.executionEpoch),
          }),
        { timeoutMs: 90_000 },
      )
    assert(startResult.status === 202, `initial message status ${startResult.status}`)
    assert(firstDelta.text.trim(), `expected non-empty text delta: ${JSON.stringify(firstDelta)}`)

    const beforeStop = await getThread(ctx, tid)
    const { status: stopStatus, json: stopJson } = await ctx.call(
      'POST',
      `/api/ai/runtime/threads/${tid}/stop`,
      { expectedExecutionEpoch: Number(beforeStop.executionEpoch) },
    )
    assert(stopStatus === 200, `stop status ${stopStatus}: ${JSON.stringify(stopJson)}`)
    const stop = envelopeData(stopJson)
    assert(
      Number(stop.executionEpoch) === Number(beforeStop.executionEpoch) + 1,
      JSON.stringify({ beforeStop, stop }),
    )

    const stopped = await getThread(ctx, tid)
    const entriesAfterStop = await snapshotEntries(ctx, tid)
    const abortedEntries = entriesAfterStop.filter(
      (entry) => String(entry.entryType || '').toUpperCase() === 'ASSISTANT_ABORTED',
    )
    assert(
      abortedEntries.length === 1,
      `expected exactly one ASSISTANT_ABORTED: ${JSON.stringify(entriesAfterStop)}`,
    )
    const abortedEntry = abortedEntries[0]
    assertAssistantAbortedEntry(abortedEntry)
    assert(
      !entriesAfterStop.some(
        (entry) => String(entry.entryType || '').toUpperCase() === 'ASSISTANT_ERROR',
      ),
      `expected partial aborted barrier, not ASSISTANT_ERROR: ${JSON.stringify(entriesAfterStop)}`,
    )
    assert(
      normalAssistantEntries(entriesAfterStop).length === 0,
      `stopped invocation must not materialize a normal assistant MESSAGE: ${JSON.stringify(entriesAfterStop)}`,
    )
    const initialUserIndex = findUserEntryIndex(entriesAfterStop, initialMarker)
    const abortedIndex = entriesAfterStop.findIndex(
      (entry) => String(entry.entryId) === String(abortedEntry.entryId),
    )
    assert(initialUserIndex >= 0, `initial USER entry missing: ${JSON.stringify(entriesAfterStop)}`)
    assert(
      abortedIndex > initialUserIndex,
      `ASSISTANT_ABORTED must follow initial USER: ${JSON.stringify(entriesAfterStop)}`,
    )
    ctx.writeArtifact(
      'stop-partial-after-stop.json',
      JSON.stringify({ firstDelta, beforeStop, stop, stopped, entriesAfterStop }, null, 2),
    )

    const { status: followUpStatus } = await ctx.call('POST', `/api/ai/runtime/threads/${tid}/messages`, {
      content: followUpPrompt,
      clientMessageId: cid(),
      expectedExecutionEpoch: Number(stopped.executionEpoch),
    })
    assert(followUpStatus === 202, `follow-up status ${followUpStatus}`)
    const finalThread = await waitForQuiescentThread(ctx, tid, {
      timeoutMs: 120_000,
      intervalMs: 500,
    })
    const finalEntries = await snapshotEntries(ctx, tid)
    const finalAbortedEntries = finalEntries.filter(
      (entry) => String(entry.entryType || '').toUpperCase() === 'ASSISTANT_ABORTED',
    )
    assert(
      finalAbortedEntries.length === 1
      && String(finalAbortedEntries[0].entryId) === String(abortedEntry.entryId),
      `aborted barrier changed after follow-up: ${JSON.stringify(finalEntries)}`,
    )

    const finalInitialUserIndex = findUserEntryIndex(finalEntries, initialMarker)
    const finalAbortedIndex = finalEntries.findIndex(
      (entry) => String(entry.entryId) === String(abortedEntry.entryId),
    )
    const followUpIndex = findUserEntryIndex(finalEntries, followUpMarker)
    const normalAssistants = normalAssistantEntries(finalEntries)
    assert(
      normalAssistants.length === 1,
      `expected exactly one normal assistant for follow-up: ${JSON.stringify(finalEntries)}`,
    )
    const finalAssistantIndex = finalEntries.findIndex(
      (entry) => String(entry.entryId) === String(normalAssistants[0].entryId),
    )
    assert(
      finalInitialUserIndex >= 0
      && finalInitialUserIndex < finalAbortedIndex
      && finalAbortedIndex < followUpIndex
      && followUpIndex < finalAssistantIndex,
      `expected USER -> ASSISTANT_ABORTED -> follow-up USER -> assistant MESSAGE: ${JSON.stringify(finalEntries)}`,
    )
    assert(
      !finalEntries.some(
        (entry) => String(entry.entryType || '').toUpperCase() === 'ASSISTANT_ERROR',
      ),
      `unexpected cancellation barrier after durable partial: ${JSON.stringify(finalEntries)}`,
    )
    ctx.writeArtifact(
      'stop-partial-continue.json',
      JSON.stringify(
        { firstDelta, beforeStop, stop, stopped, entriesAfterStop, finalThread, finalEntries },
        null,
        2,
      ),
    )
  },
})

registerCase({
  id: 'branch.path_usage',
  level: 'L3',
  title: '分支 Thread usage 路径语义',
  requires: ['real', 'branch'],
  docs: '另一条 Thread rebind 到历史 assistant Entry 后再发一轮；session 去重 vs thread 可重复计共享前缀',
  async run(ctx) {
    if (!ctx.vars.realThreadId) await getCase('real.text_turn').run(ctx)
    const mainTid = ctx.vars.realThreadId
    const sessionId = ctx.vars.realSessionId
    // Branching is just another Thread whose head is relocated onto a historical Entry.
    const { thread: spare } = await createBootstrappedThread(ctx, {
      agentDefinitionId: ctx.vars.agent.id,
      title: `e2e-branch-${cid().slice(0, 8)}`,
    })
    const branched = await rebindWhenQuiescent(ctx, spare.threadId, ctx.vars.assistantEntryId)
    const branchTid = branched.threadId
    assert(branched.headEntryId === String(ctx.vars.assistantEntryId), JSON.stringify(branched))
    await ctx.call('POST', `/api/ai/runtime/threads/${branchTid}/messages`, {
      content: '在分支上只回复单词 BRANCH，不要调用工具。',
      clientMessageId: cid(),
      expectedExecutionEpoch: Number(branched.executionEpoch),
    })
    let finalStatus = null
    for (let i = 0; i < 90; i++) {
      const { json } = await ctx.call('GET', `/api/ai/runtime/threads/${branchTid}`)
      const thread = envelopeData(json)
      finalStatus = thread.status
      if ((finalStatus === 'IDLE' || finalStatus === 'FAILED') && !thread.processing) break
      await sleep(1000)
    }
    assert(finalStatus === 'IDLE', `branch expected IDLE, got ${finalStatus}`)
    const mainU = (await getThreadSnapshot(ctx, mainTid)).usage
    const branchU = (await getThreadSnapshot(ctx, branchTid)).usage
    const sessionU = envelopeData((await ctx.call('GET', `/api/ai/runtime/usage/sessions/${sessionId}`)).json)
    const mainN = Number(mainU.recordCount || 0)
    const branchN = Number(branchU.recordCount || 0)
    const sessionN = Number(sessionU.recordCount || 0)
    assert(mainN >= 1 && branchN >= 1, JSON.stringify({ mainU, branchU }))
    assert(sessionN <= mainN + branchN, JSON.stringify({ sessionN, mainN, branchN }))
    assert(sessionN >= Math.max(mainN, branchN), JSON.stringify({ sessionN, mainN, branchN }))
    ctx.writeArtifact('usage-compare.json', JSON.stringify({ mainU, branchU, sessionU }, null, 2))
  },
})

registerCase({
  id: 'daemon.ready',
  level: 'L4',
  title: 'Environment GET projection',
  requires: ['tools'],
  docs: 'Environment READY；GET /api/ai/environment 投影固定10个 tools（version=1）+ skills',
  async run(ctx) {
    const { json } = await ctx.call('GET', '/api/ai/environment')
    const match = (envelopeData(json) || []).find((e) => e.name === ctx.daemonEnv)
    assert(match?.status === 'READY', JSON.stringify(match))
    const expectedToolNames = [
      'read',
      'write',
      'edit',
      'apply_patch',
      'bash',
      'grep',
      'find',
      'lsp_goto_definition',
      'lsp_workspace_symbols',
      'lsp_java_decompile',
    ]
    const actualTools = match.tools || []
    const names = actualTools.map((tool) => tool.name)
    assert(
      names.length === expectedToolNames.length
        && expectedToolNames.every((name) => names.includes(name))
        && actualTools.every((tool) => tool.version === '1'),
      JSON.stringify({ expectedToolNames, actualTools }),
    )
    assert(Array.isArray(match.skills), JSON.stringify(match))
  },
})

registerCase({
  id: 'tool.read_turn',
  level: 'L4',
  title: 'YOLO 下 tool invocation',
  requires: ['real', 'tools'],
  docs: '仅 minimax/MiniMax-M2.7：临时 Agent config exact tools=[read], skills=[]；Chat defaultEnvironmentName 路由；断言 invocation.environmentName 且 SUCCEEDED',
  async run(ctx) {
    await getCase('daemon.ready').run(ctx)
    await requireRealMiniMaxM27(ctx)
    const suffix = cid().slice(0, 8)
    const { json: agentJson } = await ctx.call('POST', '/api/ai/catalog/agents', {
      name: `e2e-tool-agent-${suffix}`,
      description: 'Temporary E2E agent with the daemon read tool.',
      systemPrompt:
        'You are an E2E tool agent. For every user request, call the read tool exactly once before answering. '
        + 'When asked to inspect the environment root, call read with path "." and summarize only its result.',
      modelId: String(ctx.vars.seedModel.id),
      variant: ctx.vars.seedModel.config.defaultVariant,
      config: {
        tools: ['read'],
        skills: [],
      },
    })
    const toolAgent = envelopeData(agentJson)
    assert(toolAgent?.id, JSON.stringify(agentJson))
    let chat = null
    try {
      const agentConfig = toolAgent.config
      assert(
        agentConfig
          && Object.keys(agentConfig).sort().join(',') === 'skills,tools'
          && JSON.stringify(agentConfig.tools) === JSON.stringify(['read'])
          && JSON.stringify(agentConfig.skills) === JSON.stringify([]),
        `temporary tool Agent config must be exactly tools=[read], skills=[]: ${JSON.stringify(toolAgent)}`,
      )
      const { json: chatJson } = await ctx.call('POST', '/api/ai/chat', {
        title: `e2e-tool-chat-${suffix}`,
        defaultAgentId: String(toolAgent.id),
        defaultEnvironmentName: ctx.daemonEnv,
      })
      chat = envelopeData(chatJson)
      const thread = await createChatThread(ctx, chat.id)
      assert(thread.activeEnvironmentName === ctx.daemonEnv, JSON.stringify(thread))
      const tid = thread.threadId
      const { status: yoloStatus, json: yoloJson } = await ctx.call(
        'PUT',
        `/api/ai/runtime/threads/${tid}/yolo`,
        {
          yoloEnabled: true,
          clientMessageId: cid(),
          expectedExecutionEpoch: Number(thread.executionEpoch),
        },
      )
      assert(yoloStatus === 202, JSON.stringify(yoloJson))
      const yoloInput = envelopeData(yoloJson)
      await waitForThreadInputApplied(ctx, tid, yoloInput.inputId)
      await ctx.call('POST', `/api/ai/runtime/threads/${tid}/messages`, {
        content:
          '必须调用 read 工具读取环境根目录。请使用参数 {"path":"."}，不要猜测或跳过工具，'
          + '然后用一句话总结读取结果。',
        clientMessageId: cid(),
        expectedExecutionEpoch: Number(thread.executionEpoch),
      })
      let finalStatus = null
      for (let i = 0; i < 120; i++) {
        const { json } = await ctx.call('GET', `/api/ai/runtime/threads/${tid}`)
        const current = envelopeData(json)
        finalStatus = current.status
        if ((finalStatus === 'IDLE' || finalStatus === 'FAILED') && !current.processing) break
        await sleep(1000)
      }
      const snapshot = await getThreadSnapshot(ctx, tid)
      const invocations = snapshot.toolInvocations || []
      ctx.writeArtifact('thread-snapshot.json', JSON.stringify(snapshot, null, 2))
      ctx.writeArtifact('tool-invocations.json', JSON.stringify(invocations, null, 2))
      const readInvocation = invocations.find((invocation) => invocation.toolName === 'read')
      assert(readInvocation, `no read tool invocation; status=${finalStatus}`)
      assert(
        readInvocation.environmentName === ctx.daemonEnv,
        `read invocation used unexpected environment: ${JSON.stringify(readInvocation)}`,
      )
      assert(
        !Object.hasOwn(readInvocation, 'location'),
        `ToolInvocationDTO must not expose location: ${JSON.stringify(readInvocation)}`,
      )
      assert(
        readInvocation.status === 'SUCCEEDED',
        `read invocation did not succeed: ${JSON.stringify(readInvocation)}`,
      )
    } finally {
      if (chat?.id) {
        try {
          await ctx.call(
            'DELETE',
            `/api/ai/chat/${chat.id}?expectedVersion=${encodeURIComponent(chat.version)}`,
          )
        } catch {
          // Preserve the primary assertion failure.
        }
      }
      try {
        await ctx.call(
          'DELETE',
          `/api/ai/catalog/agents/${toolAgent.id}?expectedVersion=${encodeURIComponent(toolAgent.version)}`,
        )
      } catch {
        // Preserve the primary assertion failure; matrix runs use an isolated E2E database.
      }
    }
  },
})

async function requireRealMiniMaxM27(ctx) {
  if (!ctx.vars.seedModel) await getCase('seed.structured_model_config').run(ctx)
  if (!ctx.vars.agent || !ctx.vars.provider) await getCase('seed.agent_and_provider').run(ctx)
  const model = ctx.vars.seedModel
  const agent = ctx.vars.agent
  const provider = ctx.vars.provider
  assert(provider?.name === 'minimax', `real provider must be minimax: ${JSON.stringify(provider)}`)
  assert(
    Number(model?.id) === 1 && model?.name === 'MiniMax-M2.7',
    `real model must be minimax/MiniMax-M2.7: ${JSON.stringify(model)}`,
  )
  assert(
    String(agent?.modelId) === String(model.id),
    `real agent must use minimax/MiniMax-M2.7: ${JSON.stringify({ agent, model })}`,
  )
}

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
    contents.some((content) => content.type === 'text' && content.text.trim()),
    `expected non-empty durable partial text: ${JSON.stringify(payload)}`,
  )
}

function normalAssistantEntries(entries) {
  return entries.filter((entry) => {
    if (String(entry.entryType || '').toUpperCase() !== 'MESSAGE') return false
    return parseEntryPayload(entry).message?.role === 'ASSISTANT'
  })
}

function findUserEntryIndex(entries, marker) {
  return entries.findIndex((entry) => {
    if (String(entry.entryType || '').toUpperCase() !== 'MESSAGE') return false
    const message = parseEntryPayload(entry).message
    if (message?.role !== 'USER' || !Array.isArray(message.contents)) return false
    return message.contents.some(
      (content) => content?.type === 'text' && String(content.text || '').includes(marker),
    )
  })
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

// silence unused
void pageResults
