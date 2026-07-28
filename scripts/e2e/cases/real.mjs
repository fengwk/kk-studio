import { assert, envelopeData, pageResults, sleep, cid } from '../lib/http.mjs'
import {
  createBootstrappedThread,
  getThread,
  listThreadEntries,
  rebindWhenQuiescent,
  waitForModelTextDeltaAfterSseConnected,
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
    await ctx.call('POST', `/api/threads/${tid}/messages`, {
      content: '只回复单词 OK，不要调用工具，不要解释。',
      clientMessageId: cid(),
      expectedExecutionEpoch: Number(thread.executionEpoch),
    })
    let finalStatus = null
    for (let i = 0; i < 90; i++) {
      const { json } = await ctx.call('GET', `/api/threads/${tid}`)
      const thread = envelopeData(json)
      finalStatus = thread.status
      if ((finalStatus === 'IDLE' || finalStatus === 'FAILED') && !thread.processing) break
      await sleep(1000)
    }
    assert(finalStatus === 'IDLE', `expected IDLE, got ${finalStatus}`)
    const { json: entriesJson } = await ctx.call('GET', `/api/threads/${tid}/entries`)
    const assistantEntries = []
    for (const entry of envelopeData(entriesJson) || []) {
      if (String(entry.entryType || '').toUpperCase() !== 'MESSAGE') continue
      const payload = JSON.parse(entry.payloadJson || '{}')
      if (String(payload.message?.role || '').toUpperCase() === 'ASSISTANT') assistantEntries.push(entry)
    }
    assert(assistantEntries.length > 0, 'no assistant entry')
    const { json: usageJson } = await ctx.call('GET', `/api/usage/threads/${tid}`)
    const usage = envelopeData(usageJson)
    assert(Number(usage.recordCount || 0) >= 1, JSON.stringify(usage))
    ctx.vars.realThreadId = tid
    ctx.vars.realSessionId = session.sessionId
    ctx.vars.assistantEntryId = String(assistantEntries.at(-1).entryId)
    ctx.writeArtifact('usage.json', JSON.stringify(usage, null, 2))
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
          ctx.call('POST', `/api/threads/${tid}/messages`, {
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
      `/api/threads/${tid}/stop`,
      { expectedExecutionEpoch: Number(beforeStop.executionEpoch) },
    )
    assert(stopStatus === 200, `stop status ${stopStatus}: ${JSON.stringify(stopJson)}`)
    const stop = envelopeData(stopJson)
    assert(
      Number(stop.executionEpoch) === Number(beforeStop.executionEpoch) + 1,
      JSON.stringify({ beforeStop, stop }),
    )

    const stopped = await getThread(ctx, tid)
    const entriesAfterStop = await listThreadEntries(ctx, tid)
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

    const { status: followUpStatus } = await ctx.call('POST', `/api/threads/${tid}/messages`, {
      content: followUpPrompt,
      clientMessageId: cid(),
      expectedExecutionEpoch: Number(stopped.executionEpoch),
    })
    assert(followUpStatus === 202, `follow-up status ${followUpStatus}`)
    const finalThread = await waitForQuiescentThread(ctx, tid, {
      timeoutMs: 120_000,
      intervalMs: 500,
    })
    const finalEntries = await listThreadEntries(ctx, tid)
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
    await ctx.call('POST', `/api/threads/${branchTid}/messages`, {
      content: '在分支上只回复单词 BRANCH，不要调用工具。',
      clientMessageId: cid(),
      expectedExecutionEpoch: Number(branched.executionEpoch),
    })
    let finalStatus = null
    for (let i = 0; i < 90; i++) {
      const { json } = await ctx.call('GET', `/api/threads/${branchTid}`)
      const thread = envelopeData(json)
      finalStatus = thread.status
      if ((finalStatus === 'IDLE' || finalStatus === 'FAILED') && !thread.processing) break
      await sleep(1000)
    }
    assert(finalStatus === 'IDLE', `branch expected IDLE, got ${finalStatus}`)
    const mainU = envelopeData((await ctx.call('GET', `/api/usage/threads/${mainTid}`)).json)
    const branchU = envelopeData((await ctx.call('GET', `/api/usage/threads/${branchTid}`)).json)
    const sessionU = envelopeData((await ctx.call('GET', `/api/usage/sessions/${sessionId}`)).json)
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
  title: 'Daemon Environment READY',
  requires: ['tools'],
  docs: 'tool-e2e READY 且含 coding tools',
  async run(ctx) {
    const { json } = await ctx.call('GET', '/api/environments')
    const match = (envelopeData(json) || []).find((e) => e.name === ctx.daemonEnv)
    assert(match?.status === 'READY', JSON.stringify(match))
    const names = new Set((match.tools || []).map((t) => t.name))
    assert(['read', 'write', 'edit', 'apply_patch'].some((n) => names.has(n)), JSON.stringify([...names]))
  },
})

registerCase({
  id: 'tool.read_turn',
  level: 'L4',
  title: 'YOLO 下 tool invocation',
  requires: ['real', 'tools'],
  docs: '仅 minimax/MiniMax-M2.7：要求 read；tool-invocations 非空',
  async run(ctx) {
    await getCase('daemon.ready').run(ctx)
    await requireRealMiniMaxM27(ctx)
    const { thread } = await createBootstrappedThread(ctx, {
      agentDefinitionId: ctx.vars.agent.id,
      title: `e2e-tool-${cid().slice(0, 8)}`,
      yoloEnabled: true,
    })
    const tid = thread.threadId
    await ctx.call('POST', `/api/threads/${tid}/messages`, {
      content: '使用 read 工具读取环境根目录，然后用一句话总结文件数量。',
      clientMessageId: cid(),
      expectedExecutionEpoch: Number(thread.executionEpoch),
    })
    let finalStatus = null
    for (let i = 0; i < 120; i++) {
      const { json } = await ctx.call('GET', `/api/threads/${tid}`)
      const thread = envelopeData(json)
      finalStatus = thread.status
      if ((finalStatus === 'IDLE' || finalStatus === 'FAILED') && !thread.processing) break
      await sleep(1000)
    }
    const inv = envelopeData((await ctx.call('GET', `/api/threads/${tid}/tool-invocations`)).json) || []
    ctx.writeArtifact('tool-invocations.json', JSON.stringify(inv, null, 2))
    assert(inv.length > 0, `no tool invocations; status=${finalStatus}`)
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
