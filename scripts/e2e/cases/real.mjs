import { assert, envelopeData, pageResults, sleep, cid } from '../lib/http.mjs'
import { createBootstrappedThread, rebindWhenQuiescent } from '../lib/harness.mjs'
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

// silence unused
void pageResults
