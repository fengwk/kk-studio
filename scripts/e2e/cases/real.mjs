import { writeFileSync } from 'node:fs'
import path from 'node:path'
import {
  assert,
  assertDecimalVersion,
  envelopeData,
  expectHttpError,
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
  materializeEntryThread,
  materializeNewSession,
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

registerCase({
  id: 'real.text_turn',
  level: 'L2',
  title: '真实 Provider 文本轮次成功并持久化',
  requires: ['real'],
  docs: '仅 minimax/MiniMax-M2.7：完整 branchSettings 建 Thread 后入队 USER_MESSAGE 等到 IDLE；durable TURN_START -> USER -> assistant MESSAGE -> TURN_END(COMPLETED) 边界；IDLE 后快照 modelInvocation=null（快照只暴露 active invocation，不是历史列表）；queuedCommands 清空',
  async run(ctx) {
    await requireRealMiniMaxM27(ctx)
    assert(
      ctx.vars.provider?.configured && ctx.vars.provider?.baseUrl,
      'minimax requires TEST_MINIMAX_BASE_URL and TEST_MINIMAX_API_KEY',
    )
    const chat = await createChat(ctx, {
      title: `e2e-real-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const sessionId = cid()
    const tid = cid()
    const marker = `只回复单词 OK，不要调用工具，不要解释。${cid().slice(0, 6)}`
    const accepted = await materializeNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId,
      threadId: tid,
      rootSettings: branchSettingsOf(ctx.vars.agent, modelSelectionOf(ctx)),
      yoloEnabled: false,
      commands: [userMessageCommand(marker, cid())],
    })
    assert(accepted.acceptedCommands[0].type === 'USER_MESSAGE', JSON.stringify(accepted.acceptedCommands))
    const finalThread = await waitForQuiescentThread(ctx, tid, {
      timeoutMs: 180_000,
      intervalMs: 500,
    })
    assert(finalThread.status === 'IDLE', JSON.stringify(finalThread))
    const threadSnapshot = await getThreadSnapshot(ctx, tid)
    const entries = threadSnapshot.entries || []
    const assistantEntries = normalAssistantEntries(entries)
    assert(assistantEntries.length > 0, 'no assistant entry')
    const assistantEntry = assistantEntries.at(-1)
    const text = messageText(assistantEntry)
    assert(text.trim().length > 0, `assistant reply empty: ${JSON.stringify(entries)}`)
    // 快照只暴露 active model invocation：IDLE 后必须为 null（不存在 modelInvocations[] 历史列表）。
    assert(
      threadSnapshot.modelInvocation === null,
      `IDLE snapshot must expose no active model invocation: ${JSON.stringify(threadSnapshot.modelInvocation)}`,
    )
    assert(
      !('modelInvocations' in threadSnapshot),
      `snapshot DTO has no modelInvocations[]: ${JSON.stringify(Object.keys(threadSnapshot))}`,
    )
    assert(
      (threadSnapshot.queuedCommands || []).length === 0,
      `commands must be consumed: ${JSON.stringify(threadSnapshot.queuedCommands)}`,
    )
    // Durable turn boundary (TurnPlanBuilder fact): TURN_START(INPUT) is appended FIRST, then
    // the USER/CUSTOM message entries, then the assistant MESSAGE, then TURN_END.
    const userIndex = findUserEntryIndex(entries, marker)
    const turnStartIndex = entries.findIndex((entry) => entryType(entry) === 'TURN_START')
    const assistantIndex = entries.findIndex(
      (entry) => String(entry.entryId) === String(assistantEntry.entryId),
    )
    const turnEndIndex = entries.findIndex((entry) => entryType(entry) === 'TURN_END')
    assert(
      userIndex >= 0
        && turnStartIndex >= 0
        && turnStartIndex < userIndex
        && userIndex < assistantIndex
        && assistantIndex < turnEndIndex,
      `expected TURN_START -> USER -> assistant -> TURN_END: ${JSON.stringify(entries)}`,
    )
    const turnEndPayload = parseEntryPayload(entries[turnEndIndex])
    assert(
      turnEndPayload.outcome === 'COMPLETED'
        && turnEndPayload.continueModel === false
        && turnEndPayload.reason == null
        && turnEndPayload.closeRequestId == null,
      `expected COMPLETED TURN_END: ${JSON.stringify(turnEndPayload)}`,
    )
    ctx.vars.realThreadId = tid
    ctx.vars.realSessionId = accepted.session.sessionId
    ctx.vars.realChatId = chat.id
    ctx.vars.assistantEntryId = String(assistantEntry.entryId)
    ctx.vars.turnEndEntryId = String(entries[turnEndIndex].entryId)
    ctx.writeArtifact('real-turn.json', JSON.stringify(threadSnapshot, null, 2))
  },
})

registerCase({
  id: 'real.task_delegation',
  level: 'L2',
  title: '真实 task 委派创建 durable 子 Thread',
  requires: ['real'],
  docs: '父 ModelInvocation 冻结 subagent allowlist 并调用内部 task；最终 TOOL MESSAGE 冻结 rendererKey=task 与 <task id> envelope；id 对应子 Thread ROOT.subagentContext(parent/root/taskInvocation/depth=2)',
  async run(ctx) {
    await requireRealMiniMaxM27(ctx)
    const suffix = cid().slice(0, 8)
    // 只使用数字，避免模型对十六进制字母做大小写规范化后产生无意义的 exact-marker 失败。
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
            model: `${ctx.vars.seedModel.providerName}/${ctx.vars.seedModel.name}`,
            variant: ctx.vars.seedModel.config.defaultVariant,
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
            model: `${ctx.vars.seedModel.providerName}/${ctx.vars.seedModel.name}`,
            variant: ctx.vars.seedModel.config.defaultVariant,
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
      const accepted = await materializeNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId: cid(),
        threadId: cid(),
        rootSettings: branchSettingsOf(
          parentAgent,
          {
            providerName: ctx.vars.seedModel.providerName,
            modelName: ctx.vars.seedModel.name,
            variant: ctx.vars.seedModel.config.defaultVariant,
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
        JSON.stringify({ parentSnapshot, childSnapshot, taskResult }, null, 2),
      )
    } finally {
      if (chat?.id) {
        try {
          await ctx.call(
            'DELETE',
            `/api/ai/chat/${chat.id}?expectedVersion=${encodeURIComponent(chat.version)}`,
          )
        } catch {
          // 保留主断言失败。
        }
      }
      for (const agent of [parentAgent, childAgent]) {
        if (!agent?.name) continue
        try {
          await ctx.call(
            'DELETE',
            `/api/ai/catalog/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(agent.version)}`,
          )
        } catch {
          // 隔离 E2E schema 会在下一次 rebuild 清理。
        }
      }
    }
  },
})

registerCase({
  id: 'real.stop_partial_continue',
  level: 'L2',
  title: '真实流式 /stop 持久化 partial、exact replay 并继续新一轮',
  requires: ['real'],
  docs: '仅 minimax/MiniMax-M2.7：bootstrap 用 missing Agent 确定性 PLANNING_FAILED 物化空闲 Thread（不调用真实 Provider）；随后 THREAD batch SET_AGENT/SET_MODEL + initialPrompt 启动真实 turn；首个非空 text/thinking delta 后 stop（stopRequestId + version CAS）=> status STOPPED、version+1、stoppedTurnEndEntryId 非空、durable ASSISTANT_ABORTED 关闭旧 turn；同 stopRequestId + 原 expectedVersion exact replay => status REPLAYED、同 stoppedTurnEndEntryId、version 不再变化；真实 turn 区间（initialMarker 之后）无 ASSISTANT_ERROR/无 normal assistant；follow-up 位于 barrier 后并仅产生一个新 assistant MESSAGE',
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
    const chat = await createChat(ctx, {
      title: `e2e-stop-partial-${cid().slice(0, 8)}`,
      agentName: ctx.vars.agent.name,
      yoloEnabled: false,
    })
    const sessionId = cid()
    const tid = cid()
    await materializeNewSession(ctx, {
      owner: chatOwner(chat.id),
      sessionId,
      threadId: tid,
      // bootstrap 用 missing Agent 确定性 PLANNING_FAILED 物化空闲 Thread（不调用真实 Provider）；
      // 真实 turn 由随后的 THREAD batch 显式 SET_AGENT/SET_MODEL + initialPrompt 启动。
      rootSettings: branchSettingsOf(
        { name: `e2e-stop-partial-missing-${cid().slice(0, 8)}` },
        modelSelectionOf(ctx),
      ),
      yoloEnabled: false,
      commands: [userMessageCommand(`stop partial materialize ${cid().slice(0, 8)}`, cid())],
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
              setAgentCommand(ctx.vars.agent.name, cid()),
              setModelCommand(modelSelectionOf(ctx), cid()),
              userMessageCommand(initialPrompt, cid()),
            ],
          }),
        { timeoutMs: 90_000 },
      )
    // startResult 是 accepted envelope（202），acceptedCommands 是 command DTO 数组。
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
    // 全局断言会误包含 bootstrap 的 ASSISTANT_ERROR：所有 stop 语义断言限定在 initialMarker 之后的真实 turn。
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

    // Exact replay：同 stopRequestId + 原 expectedVersion。StopControl.findReplay 在 version CAS
    // 之前按 durableKey 命中 TURN_END => REPLAYED，返回同一 stoppedTurnEndEntryId、不再 bump。
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
      JSON.stringify({ firstDelta, beforeStop, stop, replay, entriesAfterStop }, null, 2),
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
    // 只检查真实 turn 区间：bootstrap 的 ASSISTANT_ERROR 属预期，不得进入该断言。
    assert(
      !finalEntriesAfterInitial.some((entry) => entryType(entry) === 'ASSISTANT_ERROR'),
      `unexpected cancellation barrier after durable partial: ${JSON.stringify(finalEntriesAfterInitial)}`,
    )
    ctx.writeArtifact(
      'stop-partial-continue.json',
      JSON.stringify(
        { firstDelta, beforeStop, stop, replay, entriesAfterStop, finalThread, finalEntries },
        null,
        2,
      ),
    )
  },
})

registerCase({
  id: 'branch.same_session_entry_thread',
  level: 'L3',
  title: 'ENTRY 同 Session 分支物化（真实分支 turn）',
  requires: ['real', 'branch'],
  docs: '在 real.text_turn 的同一 Session 历史 assistant Entry 下用 ENTRY target 开新 Thread（不复制 Entry）：sessionId 不变、新 Thread root-to-head 路径包含 startEntry 与分支 USER、分支 turn 继续产生独立 assistant；原 Thread head/version/nextCommandSequence 不变',
  async run(ctx) {
    if (!ctx.vars.realThreadId) await getCase('real.text_turn').run(ctx)
    const mainTid = ctx.vars.realThreadId
    const sessionId = ctx.vars.realSessionId
    const chatId = ctx.vars.realChatId
    const assistantEntryId = ctx.vars.assistantEntryId
    // 同 Session 分支：ENTRY 在历史 assistant MESSAGE Entry 下开新 Thread（不复制 Entry）。
    const current = await getThread(ctx, mainTid)
    assert(String(current.sessionId) === String(sessionId), JSON.stringify(current))
    const mainBefore = {
      headEntryId: current.headEntryId,
      version: current.version,
      nextCommandSequence: current.nextCommandSequence,
    }
    const branchThreadId = cid()
    const branchUserText = '在分支上只回复单词 BRANCH，不要调用工具。'
    const branched = await materializeEntryThread(ctx, {
      owner: chatOwner(chatId),
      sessionId,
      startEntryId: assistantEntryId,
      threadId: branchThreadId,
      yoloEnabled: current.yoloEnabled,
      commands: [userMessageCommand(branchUserText, cid())],
    })
    // ENTRY accepted 后 processor 可能已消费分支命令：不锁定 response head=startEntry；
    // 只锁定 session/thread 归属与 accepted command sequence=1。
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
    // 原 Thread 不动（head/version/nextCommandSequence 逐字段不变）。
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
    // root-to-head 路径：startEntry 与分支 USER 都必须在 path 上，分支 assistant 在 USER 之后。
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
    ctx.writeArtifact('branch-snapshot.json', JSON.stringify({ finalThread, entries }, null, 2))
  },
})

registerCase({
  id: 'daemon.ready',
  level: 'L4',
  title: 'Environment GET capability 投影与 canonical 路由名称',
  requires: ['tools'],
  docs: 'Environment READY；name 是 canonical bounded 小写路由名称（唯一键），ready 是统一可用性标记；投影固定 14 个原子 capabilities（version=1）+ skills + mcpServers 摘要 + rootPath（daemon canonical Environment Root），且不公开旧 tools 或 READY environment metadata',
  async run(ctx) {
    const environments = await listEnvironments(ctx)
    const match = environments.find((environment) => environment.name === ctx.daemonEnv)
    assert(match?.status === 'READY', JSON.stringify(match))
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
      'mcp.list',
      'mcp.call',
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
    assert(Array.isArray(match.mcpServers), JSON.stringify(match))
    assert(
      !Object.hasOwn(match, 'tools')
        && !Object.hasOwn(match, 'operatingSystem')
        && !Object.hasOwn(match, 'workingDirectory')
        && !Object.hasOwn(match, 'timeZone')
        && !Object.hasOwn(match, 'note'),
      JSON.stringify(match),
    )
    // MCP 摘要只含 name/status/error/tools(name+description)；不暴露命令/headers/URL/完整 schema。
    for (const server of match.mcpServers) {
      assert(typeof server.name === 'string' && server.name.length > 0, JSON.stringify(server))
      assert(server.status === 'READY' || server.status === 'FAILED', JSON.stringify(server))
      if (server.status === 'READY') {
        assert(server.error == null, JSON.stringify(server))
        assert(Array.isArray(server.tools), JSON.stringify(server))
        for (const tool of server.tools) {
          assert(typeof tool.name === 'string' && tool.name.length > 0, JSON.stringify(tool))
          assert(typeof tool.description === 'string' || tool.description == null, JSON.stringify(tool))
        }
      } else {
        assert(Array.isArray(server.tools) && server.tools.length === 0, JSON.stringify(server))
      }
    }
    assert(match.ready === true, JSON.stringify(match))
    ctx.vars.daemonEnvironment = match
  },
})

registerCase({
  id: 'daemon.directories',
  level: 'L4',
  title: 'Environment Root 单层目录浏览 API',
  requires: ['tools'],
  docs: 'GET /api/ai/environments/{name}/directories（control-plane 只读）：缺省 path="." 浏览 root——canonical 相对 wire path、displayPath 等于请求 path 的最后一段（root 为 "."，只作展示、绝不暴露 daemon 本地绝对路径）、root 的 parentPath="."、truncated 布尔、gitBranch 可空、entries 只含直属子目录（{name,path}：name 是目录名且等于 path 最后一段，path 是请求目录的直接子路径）；显式 path="." 与缺省一致；".." 段 400 INVALID_PATH、不存在目录 404 NOT_FOUND、非法环境名 400 INVALID_ENVIRONMENT_NAME',
  async run(ctx) {
    const name = ctx.vars.daemonEnvironment?.name ?? ctx.daemonEnv
    const base = `/api/ai/environments/${encodeURIComponent(name)}/directories`
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
      // entry 只含 {name,path}：name 等于 path 最后一段，path 是请求目录的直接子路径。
      assert(!Object.hasOwn(entry, 'displayPath'), JSON.stringify(entry))
      const prefix = dir.path === '.' ? '' : `${dir.path}/`
      assert(entry.path.startsWith(prefix), JSON.stringify(entry))
      assert(!entry.path.slice(prefix.length).includes('/'), JSON.stringify(entry))
      assert(entry.name === entry.path.split('/').at(-1), JSON.stringify(entry))
    }
    // 显式 path="." 与缺省一致。
    const explicit = envelopeData(
      (await ctx.call('GET', `${base}?path=${encodeURIComponent('.')}`)).json,
    )
    assert(
      explicit.path === '.' && explicit.displayPath === '.' && explicit.parentPath === '.',
      JSON.stringify(explicit),
    )
    // 非法路径段 => 400 INVALID_PATH（失败响应 path 是请求回显归因）。
    const invalid = await expectHttpError(
      () => ctx.call('GET', `${base}?path=${encodeURIComponent('../escape')}`),
      { status: 400 },
    )
    assert(String(invalid.body).includes('INVALID_PATH'), invalid.body)
    // 不存在目录 => 404 NOT_FOUND。
    const missing = await expectHttpError(
      () => ctx.call('GET', `${base}?path=${encodeURIComponent('no-such-dir-zz')}`),
      { status: 404 },
    )
    assert(String(missing.body).includes('NOT_FOUND'), missing.body)
    // 非法环境名 => 400，不进入 daemon。
    await expectHttpError(
      () => ctx.call('GET', '/api/ai/environments/Not-Canonical/directories'),
      { status: 400 },
    )
  },
})

registerCase({
  id: 'tool.read_turn',
  level: 'L4',
  title: '非 YOLO tool turn：WAITING_APPROVAL、ALLOW 后 Resource 外部化',
  requires: ['real', 'tools', 'canvas-storage'],
  docs: '仅 minimax/MiniMax-M2.7 + backend S3 enabled（GlobalStorageToolResultHistoryMaterializer bean，否则 Resource 引用 fail-closed 无法进入 durable history）：yolo=false 时 read tool 进入 TOOL_WAITING_APPROVAL（冻结 EnvironmentBinding、无 location）；approval ALLOW（decisionId 幂等）后执行；daemon 读取 >8KB fixture，Tool Result Entry 写入前摄入全局 Blob；durable tool_result.contents 只携带 resource(blobId,name,preview)，再通过 Blob 原件预签名下载验证字节；后续模型轮次在嵌套 Resource fallback/materialization 后成功返回非空 Assistant 回复并以 TURN_END(COMPLETED, continueModel=false) 收束',
  async run(ctx) {
    await getCase('daemon.ready').run(ctx)
    await requireRealMiniMaxM27(ctx)
    const suffix = cid().slice(0, 8)
    const { json: agentJson } = await ctx.call('POST', '/api/ai/catalog/agents', {
      name: `e2e-tool-agent-${suffix}`,
      description: 'Temporary E2E agent with the daemon read tool.',
      systemPrompt:
        'You are an E2E tool agent. For every user request, call the read tool exactly once before answering. '
        + 'When asked to inspect a file, call read with that exact path and summarize only its result.',
      model: `${ctx.vars.seedModel.providerName}/${ctx.vars.seedModel.name}`,
      variant: ctx.vars.seedModel.config.defaultVariant,
      config: {
        toolIds: ['base.read'],
        skills: [],
        subagents: [],
      },
    })
    const toolAgent = envelopeData(agentJson)
    assert(toolAgent?.name, JSON.stringify(agentJson))
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
      const environment = {
        name: ctx.vars.daemonEnvironment.name,
        workspacePath: '.',
      }
      // 固定大文本 fixture（临时 root，不进仓库）：总量 >8KB、每行低于 read 单行截断阈值，
      // 且整体低于 daemon preview 阈值（50KB）=> read 返回完整 Text，Tool Result Entry 写入前
      // 由 history materializer 摄入全局 Blob，durable history 不保留 file URI。
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
      })
      const accepted = await materializeNewSession(ctx, {
        owner: chatOwner(chat.id),
        sessionId: cid(),
        threadId: cid(),
        rootSettings: branchSettingsOf(
          toolAgent,
          {
            providerName: ctx.vars.seedModel.providerName,
            modelName: ctx.vars.seedModel.name,
            variant: ctx.vars.seedModel.config.defaultVariant,
          },
          { environment },
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
        JSON.stringify(accepted.thread.branchSettings.environment) === JSON.stringify(environment),
        JSON.stringify(accepted.thread.branchSettings),
      )
      // 非 YOLO：等待 durable TOOL_WAITING_APPROVAL 状态（快照 classifier 投影）。
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
        JSON.stringify(readInvocation.environment) === JSON.stringify(environment),
        `read invocation must freeze the complete Environment binding: ${JSON.stringify({
          readInvocation,
          environment,
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
        JSON.stringify({ waiting, readInvocation }, null, 2),
      )

      // approval ALLOW（decisionId 幂等），随后 exact replay 不改变决策。
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
      // 从 durable TOOL MESSAGE entry 的嵌套 tool_result.contents 验证结果（快照 toolInvocations
      // 在 IDLE 后为空：只暴露 classifier-applicable active siblings）。
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
        'GET',
        `/api/storage/blobs/${managed.blobId}/presigned-original`,
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
        JSON.stringify({ finalThread, finalSnapshot }, null, 2),
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
          `/api/ai/catalog/agents/${encodeURIComponent(toolAgent.name)}?expectedVersion=${encodeURIComponent(toolAgent.version)}`,
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
    model?.providerName === 'minimax' && model?.name === 'MiniMax-M2.7',
    `real model must be minimax/MiniMax-M2.7: ${JSON.stringify(model)}`,
  )
  assert(
    agent?.model === `${model.providerName}/${model.name}`,
    `real agent must use minimax/MiniMax-M2.7: ${JSON.stringify({ agent, model })}`,
  )
}

/** 由 seed Agent + seed Model 构造 branchSettings.model 引用（只切第一个 '/'，保留 model name 内 '/'）。 */
function modelSelectionOf(ctx) {
  const agent = ctx.vars.agent
  const model = ctx.vars.seedModel
  const separator = String(agent.model || '').indexOf('/')
  assert(separator > 0, `agent.model must be provider/model: ${JSON.stringify(agent)}`)
  const providerName = String(agent.model).slice(0, separator)
  const modelName = String(agent.model).slice(separator + 1)
  assert(providerName && modelName, `agent.model must be provider/model: ${JSON.stringify(agent)}`)
  return {
    providerName,
    modelName,
    variant: agent.variant || model.config.defaultVariant,
  }
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
