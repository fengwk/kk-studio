import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import test from 'node:test'

// 导入即注册 real.* case；本测试直接运行注册项，只替换 HTTP/事件边界。
import '../cases/real.mjs'
import { getCase } from '../lib/registry.mjs'

const CASE_ID = 'real.stop_partial_continue'
const MODEL = {
  providerName: 'minimax-anthropic',
  modelName: 'MiniMax-M3',
  variant: 'off',
  variants: ['off', 'minimal', 'low', 'medium', 'high'],
  providerType: 'anthropic',
}

/**
 * 应用事件 WebSocket 的最小假实现：只实现 harness 使用的 EventTarget 子集。
 * 订阅 ack 与 realtime frame 都走真实 frame 形状，避免测试绕过事件解析。
 */
class FakeApplicationEventSocket {
  static instances = []

  constructor(url) {
    this.url = url
    this.listeners = new Map()
    this.sent = []
    FakeApplicationEventSocket.instances.push(this)
    queueMicrotask(() => this.dispatch('open', {}))
  }

  static latest() {
    return FakeApplicationEventSocket.instances.at(-1)
  }

  static reset() {
    FakeApplicationEventSocket.instances = []
  }

  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set())
    this.listeners.get(type).add(listener)
  }

  removeEventListener(type, listener) {
    this.listeners.get(type)?.delete(listener)
  }

  send(text) {
    const frame = JSON.parse(text)
    this.sent.push(frame)
    if (frame.type === 'subscribe') {
      const ack = {
        version: 1,
        type: 'subscribed',
        resource: frame.resource,
        cursor: '0',
      }
      queueMicrotask(() => this.dispatch('message', { data: JSON.stringify(ack) }))
    }
  }

  emit(frame) {
    this.dispatch('message', { data: JSON.stringify(frame) })
  }

  close() {}

  dispatch(type, event) {
    for (const listener of [...(this.listeners.get(type) || [])]) listener(event)
  }
}

function installFakeApplicationEvents(t) {
  const original = globalThis.WebSocket
  // 恢复不依赖 case Promise 成功结束；超时或断言失败也由测试生命周期清理。
  t.after(() => {
    globalThis.WebSocket = original
    FakeApplicationEventSocket.reset()
  })
  globalThis.WebSocket = FakeApplicationEventSocket
}

/**
 * 模拟真实 wiring 的异步退出：首次 Stop 返回 STOPPED 控制但仍 RUNNING 的 Thread；
 * 后续 ledger confirmation 才把同一 head 推进为 IDLE + STOPPED 并 touch version。
 */
function createStopCaseContext({ exitConverges = true } = {}) {
  const chatId = randomUUID()
  let sessionId = randomUUID()
  let threadId = randomUUID()
  const rootEntryId = randomUUID()
  const artifacts = []
  const deleted = []
  const snapshots = []
  const stopRequests = []

  let thread = {
    threadId,
    sessionId,
    headEntryId: rootEntryId,
    nextCommandSequence: '1',
    version: '0',
    status: 'IDLE',
    executionControl: 'RUNNABLE',
    parentThreadId: null,
    rootThreadId: threadId,
    yoloEnabled: false,
    name: 'stop-partial-mock',
  }
  let entries = [sessionEntry('ROOT', {})]
  let phase = 'idle'
  let firstStopRequest = null
  let stoppedTurnEndEntryId = null
  let abortedEntryId = null
  let postStopReads = 0
  let followUpMarker = null
  let followUpAfterConvergence = false
  let replayVersion = null

  function sessionEntry(entryType, payload) {
    return {
      entryId: randomUUID(),
      sessionId,
      parentEntryId: null,
      entryType,
      payloadJson: JSON.stringify(payload),
      usageCost: null,
    }
  }

  function messageEntry(role, text) {
    return sessionEntry('MESSAGE', {
      message: { role, contents: [{ type: 'text', text }] },
    })
  }

  function snapshotResponse() {
    snapshots.push(phase)
    return {
      status: 200,
      json: {
        data: {
          thread: { ...thread },
          entries: entries.map((entry) => ({ ...entry })),
          queuedCommands: [],
          modelInvocation: null,
          toolInvocations: [],
        },
      },
    }
  }

  function acceptedResponse(commands, status = 202) {
    return {
      status,
      json: {
        data: {
          session: { sessionId, name: 'stop-partial-session' },
          rootEntry: { entryId: rootEntryId, sessionId, entryType: 'ROOT' },
          thread: { ...thread },
          acceptedCommands: commands.map((command, index) => ({
            type: command.type,
            idempotencyKey: command.idempotencyKey,
            threadId,
            sequence: String(index + 1),
          })),
          replayed: false,
        },
      },
    }
  }

  function stopReceipt() {
    return {
      threadId,
      stopRequestId: firstStopRequest.stopRequestId,
      stoppedTurnEndEntryId,
      cancelledCommandCount: 0,
      cancelledInputs: [],
    }
  }

  function confirmExit() {
    if (!exitConverges || phase !== 'stopped-unconfirmed') return false
    thread = {
      ...thread,
      status: 'IDLE',
      version: String(Number(thread.version) + 1),
    }
    phase = 'stopped-confirmed'
    return true
  }

  function completeFollowUp() {
    const assistant = messageEntry('ASSISTANT', 'CONTINUED')
    const turnEnd = sessionEntry('TURN_END', { outcome: 'COMPLETED' })
    entries.push(assistant, turnEnd)
    thread = {
      ...thread,
      headEntryId: turnEnd.entryId,
      nextCommandSequence: String(Number(thread.nextCommandSequence) + 1),
      status: 'IDLE',
      version: String(Number(thread.version) + 1),
    }
    phase = 'followup-idle'
  }

  return {
    baseUrl: 'http://mock.invalid',
    artifacts,
    deleted,
    snapshots,
    stopRequests,
    get replayCalls() {
      return stopRequests.filter((request) => request.kind === 'replay').length
    },
    get followUpCalls() {
      return followUpMarker === null ? 0 : 1
    },
    get followUpAfterConvergence() {
      return followUpAfterConvergence
    },
    get replayVersion() {
      return replayVersion
    },
    writeArtifact(name, text) {
      artifacts.push({ name, data: JSON.parse(text) })
    },
    async call(method, url, body) {
      if (method === 'DELETE') {
        deleted.push(url)
        return { status: 200, json: { data: null } }
      }
      if (method === 'GET' && url.startsWith('/api/providers?')) {
        return {
          status: 200,
          json: {
            data: [{
              name: MODEL.providerName,
              providerType: MODEL.providerType,
              configured: true,
              baseUrl: 'https://mock.invalid',
            }],
          },
        }
      }
      if (method === 'GET' && url.startsWith('/api/models?')) {
        return {
          status: 200,
          json: {
            data: [{
              name: MODEL.modelName,
              providerName: MODEL.providerName,
              config: { variants: MODEL.variants.map((id) => ({ id })) },
            }],
          },
        }
      }
      if (method === 'POST' && url === '/api/agents') {
        return { status: 201, json: { data: { name: body.name, version: '0' } } }
      }
      if (method === 'POST' && url === '/api/chats') {
        return {
          status: 201,
          json: {
            data: {
              id: chatId,
              agentName: body.agentName,
              environmentName: null,
              version: '0',
            },
          },
        }
      }
      if (method === 'POST' && url === '/api/command-batches') {
        assert.equal(body.target.type, 'NEW_SESSION')
        // case 在请求中预分配 Session/Thread 身份，mock 采用这些身份以匹配后续路由。
        sessionId = body.target.sessionId
        threadId = body.target.threadId
        thread = {
          ...thread,
          threadId,
          sessionId,
          rootThreadId: threadId,
        }
        return acceptedResponse(body.commands)
      }
      if (method === 'POST' && url === `/api/threads/${threadId}/command-batches`) {
        if (phase === 'idle') {
          assert.deepEqual(Object.keys(body).sort(), ['commands', 'expectedHeadEntryId', 'expectedNextCommandSequence'])
          assert.equal(body.expectedHeadEntryId, thread.headEntryId)
          assert.equal(body.expectedNextCommandSequence, thread.nextCommandSequence)
          const user = messageEntry('USER', body.commands.at(-1).contents[0].text)
          entries.push(user)
          thread = {
            ...thread,
            headEntryId: user.entryId,
            nextCommandSequence: '2',
            version: '1',
            status: 'RUNNING',
          }
          phase = 'initial-running'
          FakeApplicationEventSocket.latest().emit({
            version: 1,
            type: 'event',
            name: 'realtime',
            resource: { kind: 'thread', id: threadId },
            data: {
              type: 'MODEL_DELTA',
              subjectKind: 'MODEL_INVOCATION',
              subjectId: randomUUID(),
              threadId,
              attempt: 1,
              createdAt: '2026-10-26T00:00:00.000Z',
              payload: { kind: 'TEXT_DELTA', text: 'streaming partial' },
            },
          })
          return acceptedResponse(body.commands)
        }
        if (phase === 'stopped-confirmed' && followUpMarker === null) {
          assert.deepEqual(Object.keys(body), ['commands'])
          assert.equal(body.commands.length, 1)
          followUpMarker = body.commands[0].contents[0].text
          followUpAfterConvergence = true
          const user = messageEntry('USER', followUpMarker)
          entries.push(user)
          thread = {
            ...thread,
            headEntryId: user.entryId,
            executionControl: 'RUNNABLE',
            status: 'RUNNING',
            version: String(Number(thread.version) + 1),
          }
          phase = 'followup-running'
          return acceptedResponse(body.commands)
        }
        throw new Error(`unexpected command batch in phase ${phase}`)
      }
      if (method === 'GET' && url === `/api/threads/${threadId}`) {
        if (phase === 'stopped-unconfirmed') {
          postStopReads += 1
          // 首次 stop 后的即时 durable 读取仍看到未确认退出；下一次读取才模拟 ledger confirmation。
          if (postStopReads > 1) confirmExit()
        } else if (phase === 'followup-running') {
          completeFollowUp()
        }
        return snapshotResponse()
      }
      if (method === 'POST' && url === `/api/threads/${threadId}/stop`) {
        if (firstStopRequest === null) {
          assert.equal(body.expectedVersion, thread.version)
          firstStopRequest = {
            kind: 'stop',
            stopRequestId: body.stopRequestId,
            expectedVersion: body.expectedVersion,
          }
          stopRequests.push(firstStopRequest)
          abortedEntryId = randomUUID()
          stoppedTurnEndEntryId = randomUUID()
          entries.push(
            {
              ...sessionEntry('ASSISTANT_ABORTED', {
                message: {
                  role: 'ASSISTANT',
                  contents: [{ type: 'text', text: 'durable partial' }],
                },
              }),
              entryId: abortedEntryId,
            },
            {
              ...sessionEntry('TURN_END', { outcome: 'STOPPED' }),
              entryId: stoppedTurnEndEntryId,
            },
          )
          thread = {
            ...thread,
            headEntryId: stoppedTurnEndEntryId,
            nextCommandSequence: String(Number(thread.nextCommandSequence) + 1),
            version: String(Number(thread.version) + 1),
            executionControl: 'STOPPED',
            status: 'RUNNING',
          }
          phase = 'stopped-unconfirmed'
          return {
            status: 200,
            json: {
              data: { status: 'STOPPED', thread: { ...thread }, stoppedThreads: [stopReceipt()] },
            },
          }
        }
        assert.equal(body.stopRequestId, firstStopRequest.stopRequestId)
        assert.equal(body.expectedVersion, firstStopRequest.expectedVersion)
        stopRequests.push({
          kind: 'replay',
          stopRequestId: body.stopRequestId,
          expectedVersion: body.expectedVersion,
        })
        // 真实 replay 返回 lockedTarget 当前状态；它可能与首次 Stop 响应之间存在退出确认推进。
        if (phase === 'stopped-unconfirmed') confirmExit()
        replayVersion = thread.version
        return {
          status: 200,
          json: {
            data: { status: 'REPLAYED', thread: { ...thread }, stoppedThreads: [stopReceipt()] },
          },
        }
      }
      throw new Error(`unexpected mock call: ${method} ${url}`)
    },
  }
}

test('registered stop case waits for the stable exit baseline before replay and follow-up', { timeout: 5_000 }, async (t) => {
  // 意图：首次 Stop 只写入 STOPPED 控制并留下 RUNNING；实际 case 必须先等到 IDLE+STOPPED 双观测，
  // 再用原 stopRequestId/expectedVersion 重放，对照收敛基线而不是首次 Stop 响应。
  const ctx = createStopCaseContext()
  installFakeApplicationEvents(t)
  await getCase(CASE_ID).run(ctx)

  assert.equal(ctx.stopRequests.length, 2)
  assert.equal(ctx.stopRequests[0].kind, 'stop')
  assert.equal(ctx.stopRequests[1].kind, 'replay')
  assert.equal(ctx.stopRequests[1].stopRequestId, ctx.stopRequests[0].stopRequestId)
  assert.equal(ctx.stopRequests[1].expectedVersion, ctx.stopRequests[0].expectedVersion)
  assert.equal(ctx.followUpCalls, 1)
  assert.equal(ctx.followUpAfterConvergence, true)
  assert.equal(ctx.deleted.length, 2)

  const afterStop = ctx.artifacts.find((artifact) => artifact.name === 'stop-partial-after-stop.json')
  assert.ok(afterStop, `missing after-stop artifact: ${JSON.stringify(ctx.artifacts.map((a) => a.name))}`)
  const baseline = afterStop.data.exitBaseline
  assert.ok(baseline, `artifact must capture the converged exit baseline: ${JSON.stringify(afterStop.data)}`)
  assert.equal(baseline.status, 'IDLE')
  assert.equal(baseline.executionControl, 'STOPPED')
  assert.equal(baseline.version, String(Number(afterStop.data.stop.thread.version) + 1))
  assert.equal(baseline.headEntryId, afterStop.data.stop.thread.headEntryId)
  assert.equal(afterStop.data.replay.status, 'REPLAYED')
  assert.equal(afterStop.data.replay.thread.version, baseline.version)
  assert.equal(afterStop.data.replay.thread.headEntryId, baseline.headEntryId)
  assert.equal(ctx.replayVersion, baseline.version)

  const continued = ctx.artifacts.find((artifact) => artifact.name === 'stop-partial-continue.json')
  assert.ok(continued, `missing continue artifact: ${JSON.stringify(ctx.artifacts.map((a) => a.name))}`)
  assert.equal(continued.data.exitBaseline.version, baseline.version)
  assert.equal(continued.data.exitBaseline.headEntryId, baseline.headEntryId)
})

test('registered stop case times out instead of replaying or resuming an unconfirmed exit', { timeout: 5_000 }, async (t) => {
  // 意图：退出确认永远不收敛时，case 只能超时失败，不能把 STOPPED 控制当作可续写，也不能重放或发 follow-up。
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'] })
  installFakeApplicationEvents(t)
  const ctx = createStopCaseContext({ exitConverges: false })
  let failure = null
  let settled = false
  const run = getCase(CASE_ID).run(ctx)
    .then(
      () => {
        settled = true
      },
      (error) => {
        failure = error
        settled = true
      },
    )

  // bootstrap 与 stop 后轮询都依赖 sleep；假时钟必须从 case 启动后持续推进。
  let ticks = 0
  while (!settled && ticks < 1200) {
    await new Promise((resolve) => setImmediate(resolve))
    t.mock.timers.tick(100)
    ticks += 1
  }
  assert.ok(settled, 'case must settle after the bounded quiescent wait')
  await run

  assert.ok(failure, 'unconfirmed exit must fail the case')
  assert.match(failure.message, /thread did not become quiescent/)
  assert.equal(ctx.replayCalls, 0)
  assert.equal(ctx.followUpCalls, 0)
  assert.equal(ctx.deleted.length, 2)
})
