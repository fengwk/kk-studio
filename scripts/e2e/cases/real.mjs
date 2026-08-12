import { writeFileSync } from 'node:fs'
import path from 'node:path'
import { assert, envelopeData, sleep, cid } from '../lib/http.mjs'
import {
  approveToolInvocation,
  branchSettingsOf,
  createChat,
  createChatThread,
  createConfiguredChatThread,
  enqueueCommands,
  getThread,
  getThreadSnapshot,
  listEnvironments,
  snapshotEntries,
  stopThread,
  updateThreadHead,
  userMessageCommand,
  waitForModelTextDeltaAfterSseConnected,
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
    const { snapshot } = await createConfiguredChatThread(ctx, {
      agent: ctx.vars.agent,
      model: modelSelectionOf(ctx),
      title: `e2e-real-${cid().slice(0, 8)}`,
    })
    const tid = snapshot.thread.threadId
    const marker = `只回复单词 OK，不要调用工具，不要解释。${cid().slice(0, 6)}`
    const commandsDto = await enqueueCommands(ctx, tid, {
      expectedHeadEntryId: snapshot.thread.headEntryId,
      expectedNextCommandSequence: snapshot.thread.nextCommandSequence,
      commands: [userMessageCommand(marker, cid())],
    })
    assert(commandsDto[0].type === 'USER_MESSAGE', JSON.stringify(commandsDto))
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
    ctx.vars.realSessionId = snapshot.thread.sessionId
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
    const marker = `SUBAGENT-E2E-${suffix}`
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
            config: { tools: [], skills: [], subagents: [] },
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
            config: { tools: [], skills: [], subagents: [childAgent.name] },
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
      const snapshot = await createChatThread(ctx, chat.id, {
        title: null,
        yoloEnabled: false,
        branchSettings: branchSettingsOf(
          parentAgent,
          {
            providerName: ctx.vars.seedModel.providerName,
            modelName: ctx.vars.seedModel.name,
            variant: ctx.vars.seedModel.config.defaultVariant,
          },
          { environmentName: null, activeTools: ['task'] },
        ),
      })
      const parentThreadId = String(snapshot.thread.threadId)
      await enqueueCommands(ctx, parentThreadId, {
        expectedHeadEntryId: snapshot.thread.headEntryId,
        expectedNextCommandSequence: snapshot.thread.nextCommandSequence,
        commands: [
          userMessageCommand(
            `Use task with subagent_type "${childAgent.name}" and ask it to return exactly ${marker}.`,
            cid(),
          ),
        ],
      })
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
      const taskId = /<task id="([1-9]\d*)" state="completed">/.exec(taskText)?.[1]
      assert(taskId, `completed task envelope missing: ${taskText}`)
      assert(taskText.includes(marker), `subagent report missing marker: ${taskText}`)

      const childSnapshot = await getThreadSnapshot(ctx, taskId)
      const childRoot = (childSnapshot.entries || [])[0]
      assert(entryType(childRoot) === 'ROOT', JSON.stringify(childSnapshot.entries))
      const context = parseEntryPayload(childRoot).subagentContext
      assert(
        String(context?.parentThreadId) === parentThreadId
          && String(context?.rootThreadId) === parentThreadId
          && /^[1-9]\d*$/.test(String(context?.taskInvocationId || ''))
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
  id: 'real.queued_command_batch',
  level: 'L2',
  title: '运行中一次原子 batch 入队两条消息合并收割',
  requires: ['real'],
  docs: '首轮流式执行期间用最新快照 cursor 一次原子 batch 入队两条 USER_MESSAGE（sequence 连续）；下一 turn 收割为两个 USER entry + 一个 assistant MESSAGE；queuedCommands 最终清空',
  async run(ctx) {
    await requireRealMiniMaxM27(ctx)
    assert(
      ctx.vars.provider?.configured && ctx.vars.provider?.baseUrl,
      'minimax requires TEST_MINIMAX_BASE_URL and TEST_MINIMAX_API_KEY',
    )

    const initialMarker = `QUEUE-INITIAL-${cid()}`
    const firstMarker = `QUEUE-FIRST-${cid()}`
    const secondMarker = `QUEUE-SECOND-${cid()}`
    const { snapshot } = await createConfiguredChatThread(ctx, {
      agent: ctx.vars.agent,
      model: modelSelectionOf(ctx),
      title: `e2e-queue-batch-${cid().slice(0, 8)}`,
    })
    const tid = String(snapshot.thread.threadId)
    const { signal: firstDelta, startResult } =
      await waitForModelTextDeltaAfterSseConnected(
        ctx,
        tid,
        () =>
          enqueueCommands(ctx, tid, {
            expectedHeadEntryId: snapshot.thread.headEntryId,
            expectedNextCommandSequence: snapshot.thread.nextCommandSequence,
            commands: [
              userMessageCommand(
                `${initialMarker}\n不要调用工具。立即逐行输出 80 行短句，每行以“批次等待”开头并带连续编号；`
                  + '不要总结，不要提前结束。',
                cid(),
              ),
            ],
          }),
        { timeoutMs: 90_000 },
      )
    assert(
      Array.isArray(startResult) && startResult.length === 1 && startResult[0].type === 'USER_MESSAGE',
      `initial message batch: ${JSON.stringify(startResult)}`,
    )
    assert(firstDelta.text.trim(), `expected non-empty text delta: ${JSON.stringify(firstDelta)}`)

    // 运行中入队：一次原子 batch 两条 USER_MESSAGE。cursor 在入队时推进（消费不推进），
    // 运行中快照 cursor 必然有效；sequence 连续由同一 batch 保证。
    const running = await getThreadSnapshot(ctx, tid)
    const queued = await enqueueCommands(ctx, tid, {
      expectedHeadEntryId: running.thread.headEntryId,
      expectedNextCommandSequence: running.thread.nextCommandSequence,
      commands: [
        userMessageCommand(`${firstMarker}\n这是下一 turn 队列批次的第一条消息。`, cid()),
        userMessageCommand(`${secondMarker}\n结合前一条消息，只回复单词 BATCHED，不要解释。`, cid()),
      ],
    })
    assert(queued.length === 2, JSON.stringify(queued))
    assert(
      Number(queued[1].sequence) === Number(queued[0].sequence) + 1,
      `batch sequences must be contiguous: ${JSON.stringify(queued)}`,
    )

    const finalThread = await waitForQuiescentThread(ctx, tid, {
      timeoutMs: 180_000,
      intervalMs: 500,
    })
    const finalSnapshot = await getThreadSnapshot(ctx, tid)
    const entries = finalSnapshot.entries || []
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
    // 全部命令已消费，queuedCommands 清空。
    assert(
      (finalSnapshot.queuedCommands || []).length === 0,
      `queued commands must be consumed: ${JSON.stringify(finalSnapshot.queuedCommands)}`,
    )
    ctx.writeArtifact(
      'queued-command-batch.json',
      JSON.stringify({ firstDelta, finalThread, entries }, null, 2),
    )
  },
})

registerCase({
  id: 'real.stop_partial_continue',
  level: 'L2',
  title: '真实流式 /stop 持久化 partial、exact replay 并继续新一轮',
  requires: ['real'],
  docs: '仅 minimax/MiniMax-M2.7：首个非空文本 delta 后 stop（stopRequestId + revision CAS）=> status STOPPED、revision+1、stoppedTurnEndEntryId 非空、durable ASSISTANT_ABORTED 关闭旧 turn；同 stopRequestId + 原 expectedRevision exact replay => status REPLAYED、同 stoppedTurnEndEntryId、revision 不再变化；follow-up 位于 barrier 后并仅产生一个新 assistant MESSAGE',
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
    const { snapshot } = await createConfiguredChatThread(ctx, {
      agent: ctx.vars.agent,
      model: modelSelectionOf(ctx),
      title: `e2e-stop-partial-${cid().slice(0, 8)}`,
      yoloEnabled: false,
    })
    const tid = String(snapshot.thread.threadId)

    const { signal: firstDelta, startResult } =
      await waitForModelTextDeltaAfterSseConnected(
        ctx,
        tid,
        () =>
          enqueueCommands(ctx, tid, {
            expectedHeadEntryId: snapshot.thread.headEntryId,
            expectedNextCommandSequence: snapshot.thread.nextCommandSequence,
            commands: [userMessageCommand(initialPrompt, cid())],
          }),
        { timeoutMs: 90_000 },
      )
    // startResult 是 command DTO 数组（202 accepted），不是 {status}。
    assert(
      Array.isArray(startResult) && startResult.length === 1 && startResult[0].type === 'USER_MESSAGE',
      `initial message batch: ${JSON.stringify(startResult)}`,
    )
    assert(firstDelta.text.trim(), `expected non-empty text delta: ${JSON.stringify(firstDelta)}`)

    const beforeStop = await getThread(ctx, tid)
    const stopRequestId = cid()
    const expectedRevision = beforeStop.revision
    const stop = await stopThread(ctx, tid, {
      stopRequestId,
      expectedRevision,
    })
    assert(stop.status === 'STOPPED', JSON.stringify(stop))
    assert(
      stop.stoppedTurnEndEntryId != null && /^[1-9]\d*$/.test(String(stop.stoppedTurnEndEntryId)),
      JSON.stringify(stop),
    )
    assert(
      Number(stop.thread.revision) === Number(beforeStop.revision) + 1,
      `active stop must bump revision by one: ${JSON.stringify({ beforeStop, stop })}`,
    )

    const entriesAfterStop = await snapshotEntries(ctx, tid)
    const abortedEntries = entriesAfterStop.filter((entry) => entryType(entry) === 'ASSISTANT_ABORTED')
    assert(
      abortedEntries.length === 1,
      `expected exactly one ASSISTANT_ABORTED: ${JSON.stringify(entriesAfterStop)}`,
    )
    const abortedEntry = abortedEntries[0]
    assertAssistantAbortedEntry(abortedEntry)
    assert(
      !entriesAfterStop.some((entry) => entryType(entry) === 'ASSISTANT_ERROR'),
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

    // Exact replay：同 stopRequestId + 原 expectedRevision。StopControl.findReplay 在 revision CAS
    // 之前按 durableKey 命中 TURN_END => REPLAYED，返回同一 stoppedTurnEndEntryId、不再 bump。
    const replay = await stopThread(ctx, tid, {
      stopRequestId,
      expectedRevision,
    })
    assert(replay.status === 'REPLAYED', JSON.stringify(replay))
    assert(
      String(replay.stoppedTurnEndEntryId) === String(stop.stoppedTurnEndEntryId),
      `replay must identify the same stopped TURN_END: ${JSON.stringify({ stop, replay })}`,
    )
    assert(replay.cancelledCommandCount === 0, JSON.stringify(replay))
    assert(
      String(replay.thread.headEntryId) === String(stop.thread.headEntryId)
        && String(replay.thread.revision) === String(stop.thread.revision),
      `replay must not mutate the Thread: ${JSON.stringify({ stop, replay })}`,
    )
    ctx.writeArtifact(
      'stop-partial-after-stop.json',
      JSON.stringify({ firstDelta, beforeStop, stop, replay, entriesAfterStop }, null, 2),
    )

    const followUp = await enqueueCommands(ctx, tid, {
      expectedHeadEntryId: stop.thread.headEntryId,
      expectedNextCommandSequence: stop.thread.nextCommandSequence,
      commands: [userMessageCommand(followUpPrompt, cid())],
    })
    assert(followUp.length === 1, `follow-up batch: ${JSON.stringify(followUp)}`)
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
      !finalEntries.some((entry) => entryType(entry) === 'ASSISTANT_ERROR'),
      `unexpected cancellation barrier after durable partial: ${JSON.stringify(finalEntries)}`,
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
  id: 'branch.same_session_move_head',
  level: 'L3',
  title: '同 Session 分支：同一 Thread head 回退到历史 assistant 后继续',
  requires: ['real', 'branch'],
  docs: '在 real.text_turn 的同一 Thread 上，从 TURN_END head 回退到该 Session 内历史 assistant Entry：sessionId 不变、revision+1、root-to-head 路径切换到分支并继续产生分支 turn（不创建另一 Thread/Session）',
  async run(ctx) {
    if (!ctx.vars.realThreadId) await getCase('real.text_turn').run(ctx)
    const mainTid = ctx.vars.realThreadId
    const sessionId = ctx.vars.realSessionId
    const assistantEntryId = ctx.vars.assistantEntryId
    // 同一 Thread：head 当前在 TURN_END，回退到同 Session 历史 assistant MESSAGE Entry。
    const current = await getThread(ctx, mainTid)
    assert(String(current.sessionId) === String(sessionId), JSON.stringify(current))
    const moved = await updateThreadHead(ctx, mainTid, {
      targetEntryId: assistantEntryId,
      expectedRevision: current.revision,
    })
    assert(String(moved.headEntryId) === String(assistantEntryId), JSON.stringify(moved))
    assert(
      String(moved.sessionId) === String(sessionId),
      `branch must stay in the same Session: ${JSON.stringify({ sessionId, moved })}`,
    )
    assert(
      Number(moved.revision) === Number(current.revision) + 1,
      'move head must advance revision',
    )

    await enqueueCommands(ctx, mainTid, {
      expectedHeadEntryId: moved.headEntryId,
      expectedNextCommandSequence: moved.nextCommandSequence,
      commands: [userMessageCommand('在分支上只回复单词 BRANCH，不要调用工具。', cid())],
    })
    const finalThread = await waitForQuiescentThread(ctx, mainTid, {
      timeoutMs: 180_000,
      intervalMs: 500,
    })
    assert(finalThread.status === 'IDLE', JSON.stringify(finalThread))
    assert(
      Number(finalThread.revision) > Number(moved.revision),
      `branch turn must advance revision beyond the move: ${JSON.stringify(finalThread)}`,
    )
    const entries = await snapshotEntries(ctx, mainTid)
    // root-to-head 路径切换：分支 USER 位于历史 assistant 之后，新 assistant 在其后。
    const branchUserIndex = findUserEntryIndex(entries, '在分支上只回复单词 BRANCH')
    const assistantIndex = entries.findIndex(
      (entry) =>
        String(entry.entryId) === String(normalAssistantEntries(entries).at(-1)?.entryId),
    )
    assert(
      branchUserIndex >= 0 && branchUserIndex < assistantIndex,
      `branch turn must follow the moved head: ${JSON.stringify(entries)}`,
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
  title: 'Environment GET 投影与 canonical 路由名称',
  requires: ['tools'],
  docs: 'Environment READY；name 是 canonical bounded 小写路由名称（唯一键），ready 是统一可用性标记；投影固定 11 个 tools（version=1）+ skills + mcpServers 摘要，且不公开 READY environment metadata',
  async run(ctx) {
    const environments = await listEnvironments(ctx)
    const match = environments.find((environment) => environment.name === ctx.daemonEnv)
    assert(match?.status === 'READY', JSON.stringify(match))
    const expectedToolNames = [
      'read',
      'write',
      'edit',
      'bash',
      'grep',
      'find',
      'lsp_goto_definition',
      'lsp_workspace_symbols',
      'lsp_java_decompile',
      'mcp_list_tools',
      'mcp_call_tool',
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
    assert(Array.isArray(match.mcpServers), JSON.stringify(match))
    assert(
      !Object.hasOwn(match, 'operatingSystem')
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
  id: 'tool.read_turn',
  level: 'L4',
  title: '非 YOLO tool turn：WAITING_APPROVAL、ALLOW 后 Resource 外部化',
  requires: ['real', 'tools'],
  docs: '仅 minimax/MiniMax-M2.7：yolo=false 时 read tool 进入 TOOL_WAITING_APPROVAL（frozen environmentName、无 location）；approval ALLOW（decisionId 幂等）后执行；daemon 读取 >8KB fixture，Tool Result Entry 写入前摄入全局 Blob；durable tool_result.contents 只携带 resource(blobId,name,preview)，再通过 Blob 原件预签名下载验证字节',
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
        tools: ['read'],
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
          && Object.keys(agentConfig).sort().join(',') === 'skills,subagents,tools'
          && JSON.stringify(agentConfig.tools) === JSON.stringify(['read'])
          && JSON.stringify(agentConfig.skills) === JSON.stringify([])
          && JSON.stringify(agentConfig.subagents) === JSON.stringify([]),
        `temporary tool Agent config must be exactly tools=[read], skills=[], subagents=[]: ${JSON.stringify(toolAgent)}`,
      )
      const environmentName = ctx.vars.daemonEnvironment.name
      // 固定大文本 fixture（临时 root，不进仓库）：单行 >8KB，core externalizer 内联阈值
      // (INLINE_RESULT_UTF8_BYTES=8KB) 之上、daemon preview 阈值（50KB）之下 => read 返回 Text，
      // Tool Result Entry 写入前由 history materializer 摄入全局 Blob，durable history 不保留 file URI。
      const envRoot = process.env.DAEMON_ENV_ROOT || '/tmp/kk-studio-e2e-env'
      const fixturePath = path.join(envRoot, 'e2e-resource.txt')
      const fixtureContent = `E2E-RESOURCE-FIXTURE ${'x'.repeat(16 * 1024)}\n`
      writeFileSync(fixturePath, fixtureContent)
      chat = await createChat(ctx, {
        title: `e2e-tool-chat-${suffix}`,
        agentName: toolAgent.name,
        yoloEnabled: false,
      })
      const snapshot = await createChatThread(ctx, chat.id, {
        title: null,
        yoloEnabled: false,
        branchSettings: branchSettingsOf(
          toolAgent,
          {
            providerName: ctx.vars.seedModel.providerName,
            modelName: ctx.vars.seedModel.name,
            variant: ctx.vars.seedModel.config.defaultVariant,
          },
          { environmentName, activeTools: ['read'] },
        ),
      })
      const tid = snapshot.thread.threadId
      assert(
        snapshot.thread.branchSettings.environmentName === environmentName,
        JSON.stringify(snapshot.thread.branchSettings),
      )
      await enqueueCommands(ctx, tid, {
        expectedHeadEntryId: snapshot.thread.headEntryId,
        expectedNextCommandSequence: snapshot.thread.nextCommandSequence,
        commands: [
          userMessageCommand(
            '必须调用 read 工具读取文件 e2e-resource.txt，使用参数 {"path":"e2e-resource.txt"}，'
              + '不要猜测或跳过工具，然后用一句话总结读取结果。',
            cid(),
          ),
        ],
      })
      // 非 YOLO：等待 durable TOOL_WAITING_APPROVAL 状态（快照 classifier 投影）。
      let waiting = null
      for (let i = 0; i < 120; i++) {
        const current = await getThreadSnapshot(ctx, tid)
        if (current.thread.status === 'TOOL_WAITING_APPROVAL') {
          waiting = current
          break
        }
        if (current.thread.status === 'IDLE' || current.thread.status === 'FAILED') {
          break
        }
        await sleep(1000)
      }
      assert(waiting, `never reached TOOL_WAITING_APPROVAL on thread ${tid}`)
      const readInvocation = waiting.toolInvocations.find(
        (invocation) => invocation.toolName === 'read',
      )
      assert(readInvocation, `no WAITING_APPROVAL read invocation: ${JSON.stringify(waiting)}`)
      assert(readInvocation.status === 'WAITING_APPROVAL', JSON.stringify(readInvocation))
      assert(
        readInvocation.environmentName === environmentName,
        `read invocation must freeze the Environment route name: ${JSON.stringify({
          readInvocation,
          environmentName,
        })}`,
      )
      assert(
        !Object.hasOwn(readInvocation, 'location'),
        `ToolInvocationDTO must not expose location: ${JSON.stringify(readInvocation)}`,
      )
      assert(
        readInvocation.toolCallId && Number.isSafeInteger(readInvocation.attempt) && readInvocation.attempt >= 1,
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
      assert(
        finalSnapshot.toolInvocations.length === 0,
        `IDLE snapshot exposes no tool siblings: ${JSON.stringify(finalSnapshot.toolInvocations)}`,
      )
      const toolEntries = (finalSnapshot.entries || []).filter((entry) => {
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
      assert(
        typeof signed.sizeBytes === 'number' && signed.sizeBytes > 0,
        `blob sizeBytes must be present: ${JSON.stringify(signed)}`,
      )
      const download = await fetch(signed.url, { headers: signed.headers || {} })
      assert(download.status === 200, `blob resource download status ${download.status}`)
      assert(
        String(download.headers.get('content-type') || '').startsWith(signed.mediaType),
        `managed resource media type mismatch: ${download.headers.get('content-type')}`,
      )
      const bytes = Buffer.from(await download.arrayBuffer())
      assert(
        bytes.length === signed.sizeBytes,
        `download size ${bytes.length} != ${signed.sizeBytes}`,
      )
      assert(
        bytes.equals(Buffer.from(fixtureContent)),
        'blob resource download bytes differ from the fixture',
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
    contents.some((content) => content.type === 'text' && content.text.trim()),
    `expected non-empty durable partial text: ${JSON.stringify(payload)}`,
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
