/**
 * TerminalController 单元测试（fake events + fake timers）。
 *
 * 覆盖：新流基线/VIEW_APPLIED 后的控制、跨连接 CLAIM.recovery 顺序、发送先登记后
 * 发送的围栏、输入总预算、seq/epoch、隐藏/停止保留恢复证据、观察者心跳与秘密不外泄。
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  ApplicationEventConnectionStatus,
  ApplicationEventTerminalListener,
} from '@/shared/app-events'
import type {
  AdmissionResult,
  ControlResult,
  ErrorCode,
  ErrorDisposition,
  OperationOutcome,
  TerminalAttachedEvent,
  TerminalCommand,
  TerminalEvent,
  TerminalErrorEvent,
  TerminalExitedEvent,
  TerminalIdentity,
  TerminalOpAckEvent,
  TerminalViewUpdate,
  TerminalViewUpdateEvent,
  TerminalWriterChangedEvent,
  WriterGrant,
  WriterState,
} from '@/features/shell/terminal-control-codec'
import {
  MAX_PENDING_INPUT_BYTES,
  MAX_TERMINAL_SESSIONS,
  TERMINAL_REQUEST_DEADLINE_MS,
  TerminalController,
} from '@/features/shell/terminal-controller'
import {
  inputOperationDigest,
  resizeOperationDigest,
} from '@/features/shell/terminal-operation-digest'
import { decodeTerminalViewUpdate } from '@/features/shell/terminal-view-codec'
import resetFixture from './__fixtures__/reset-update.json'

const ENV_A = 'aaaaaaaa-0000-4000-8000-000000000001'
const ENV_B = 'aaaaaaaa-0000-4000-8000-000000000002'
const VIEWER = 'bbbbbbbb-0000-4000-8000-000000000001'
const DAEMON = 'cccccccc-0000-4000-8000-000000000001'
const TERMINAL = 'dddddddd-0000-4000-8000-000000000001'
const STREAM_1 = 'eeeeeeee-0000-4000-8000-000000000001'
const STREAM_2 = 'eeeeeeee-0000-4000-8000-000000000002'
const EPOCH_1 = 'ffffffff-0000-4000-8000-000000000001'
const TOKEN_1 = '99999999-0000-4000-8000-000000000001'
const EPOCH_2 = 'ffffffff-0000-4000-8000-000000000002'
const TOKEN_2 = '99999999-0000-4000-8000-000000000002'

const IDENTITY: TerminalIdentity = { daemonInstanceId: DAEMON, terminalId: TERMINAL }
const OTHER_IDENTITY: TerminalIdentity = {
  daemonInstanceId: DAEMON,
  terminalId: 'dddddddd-0000-4000-8000-000000000002',
}
const GRANT: WriterGrant = { epoch: EPOCH_1, token: TOKEN_1 }

class FakeEvents {
  status: ApplicationEventConnectionStatus = 'open'
  accept = true
  /** 允许测试在 send 内同步回执（重入）或观察命令。 */
  onSend: ((command: TerminalCommand) => void) | null = null
  readonly sent: TerminalCommand[] = []
  private listener: ApplicationEventTerminalListener | null = null

  getStatus = (): ApplicationEventConnectionStatus => this.status

  sendTerminal = (command: TerminalCommand): boolean => {
    this.sent.push(command)
    this.onSend?.(command)
    return this.accept
  }

  subscribeTerminal = (
    _viewerId: string,
    listener: ApplicationEventTerminalListener,
  ): (() => void) => {
    this.listener = listener
    listener.onStatusChange?.(this.status)
    return () => {
      this.listener = null
    }
  }

  emit(event: TerminalEvent): void {
    this.listener?.onEvent?.(event)
  }

  setStatus(status: ApplicationEventConnectionStatus): void {
    this.status = status
    this.listener?.onStatusChange?.(status)
  }
}

function writerState(writerEpoch: string | null, frozen = false): WriterState {
  return {
    writerEpoch,
    lastWrittenSeq: 0,
    lastWrittenDigest: null,
    lastResolvedSeq: 0,
    lastResolvedDigest: null,
    lastResolvedOutcome: null,
    pendingSeq: 0,
    pendingDigest: null,
    frozen,
  }
}

function attachedEvent(
  requestId: string | null,
  identity: TerminalIdentity,
  streamId: string,
  inputModeRevision = 1,
  environmentId = ENV_A,
): TerminalAttachedEvent {
  return {
    version: 1,
    requestId,
    environmentId,
    viewerId: VIEWER,
    identity,
    type: 'ATTACHED',
    payload: {
      streamId,
      executable: '/bin/sh',
      status: 'RUNNING',
      exitCode: null,
      inputModeRevision,
      writer: writerState(null),
    },
  }
}

function writerChangedEvent(
  requestId: string | null,
  result: ControlResult | null,
  writer = writerState(EPOCH_1),
  identity: TerminalIdentity = IDENTITY,
): TerminalWriterChangedEvent {
  return {
    version: 1,
    requestId,
    environmentId: ENV_A,
    viewerId: VIEWER,
    identity,
    type: 'WRITER_CHANGED',
    payload: { writer, result },
  }
}

function opAckEvent(
  writerEpoch: string,
  result: AdmissionResult,
  code: ErrorCode | null = null,
  environmentId = ENV_A,
  identity: TerminalIdentity = IDENTITY,
): TerminalOpAckEvent {
  return {
    version: 1,
    requestId: null,
    environmentId,
    viewerId: VIEWER,
    identity,
    type: 'OP_ACK',
    payload: { writerEpoch, result, code },
  }
}

function viewUpdateEvent(
  update: TerminalViewUpdate,
  environmentId = ENV_A,
  identity: TerminalIdentity = IDENTITY,
): TerminalViewUpdateEvent {
  return {
    version: 1,
    requestId: null,
    environmentId,
    viewerId: VIEWER,
    identity,
    type: 'VIEW_UPDATE',
    payload: { update },
  }
}

function exitedEvent(
  status: 'EXITED' | 'FAILED',
  exitCode: number | null,
  identity: TerminalIdentity = IDENTITY,
): TerminalExitedEvent {
  return {
    version: 1,
    requestId: null,
    environmentId: ENV_A,
    viewerId: VIEWER,
    identity,
    type: 'EXITED',
    payload: { status, exitCode },
  }
}

function errorEvent(
  code: ErrorCode,
  disposition: ErrorDisposition = 'NOT_EXECUTED',
  requestId: string | null = null,
  environmentId = ENV_A,
  identity: TerminalIdentity | null = IDENTITY,
): TerminalErrorEvent {
  return {
    version: 1,
    requestId,
    environmentId,
    viewerId: VIEWER,
    identity,
    type: 'ERROR',
    payload: { code, disposition },
  }
}

function granted(recovered: OperationOutcome | null = null, grant: WriterGrant = GRANT): ControlResult {
  return { status: 'GRANTED', grant, recovered, reason: null }
}

function confirmed(seq: number, digest: string, outcome: OperationOutcome): AdmissionResult {
  return { kind: 'CONFIRMED', seq, digest, outcome, reason: null }
}

function makeReset(
  streamId = STREAM_1,
  version = 1,
  inputModeRevision = 1,
): ReturnType<typeof decodeTerminalViewUpdate> {
  const fixture = JSON.parse(JSON.stringify(resetFixture)) as Record<string, unknown>
  fixture.terminalId = TERMINAL
  fixture.streamId = streamId
  fixture.version = version
  fixture.inputModeRevision = inputModeRevision
  return decodeTerminalViewUpdate(fixture)
}

function findCommand<T extends TerminalCommand['type']>(
  events: FakeEvents,
  type: T,
): Extract<TerminalCommand, { type: T }> | undefined {
  for (let i = events.sent.length - 1; i >= 0; i--) {
    const command = events.sent[i]
    if (command.type === type) {
      return command as Extract<TerminalCommand, { type: T }>
    }
  }
  return undefined
}

function countType(events: FakeEvents, type: TerminalCommand['type']): number {
  return events.sent.filter((command) => command.type === type).length
}

function createController(events: FakeEvents): TerminalController {
  return new TerminalController({ events, viewerId: VIEWER })
}

/** show + 首次 OPEN + ATTACHED（不应用基线）。 */
function attach(controller: TerminalController, events: FakeEvents, streamId = STREAM_1): void {
  controller.show(ENV_A)
  const open = findCommand(events, 'OPEN')!
  events.emit(attachedEvent(open.requestId, IDENTITY, streamId))
}

/** 在既有 stream 上发送 RESET 并完成真实 DOM commit。 */
function applyBaseline(
  controller: TerminalController,
  events: FakeEvents,
  streamId = STREAM_1,
  version = 1,
  revision = 1,
): void {
  events.emit(viewUpdateEvent(makeReset(streamId, version, revision)))
  controller.applied(streamId, version)
}

/** attach + 基线。 */
function establish(
  controller: TerminalController,
  events: FakeEvents,
  streamId = STREAM_1,
  revision = 1,
): void {
  attach(controller, events, streamId)
  applyBaseline(controller, events, streamId, 1, revision)
}

/** 显式取得控制权并确认 GRANTED。 */
function grantControl(controller: TerminalController, events: FakeEvents): void {
  controller.claim()
  const claim = findCommand(events, 'CLAIM')!
  events.emit(writerChangedEvent(claim.requestId, granted()))
}

function session(controller: TerminalController, env = ENV_A) {
  return controller.getSnapshot().sessions.get(env)!
}

function uuidFrom(n: number): string {
  return `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`
}

describe('TerminalController', () => {
  let events: FakeEvents

  beforeEach(() => {
    vi.useFakeTimers()
    events = new FakeEvents()
  })

  afterEach(() => {
    vi.clearAllTimers()
    vi.useRealTimers()
  })

  describe('构造与快照', () => {
    it('构造无网络/定时器副作用，初始快照稳定且为空', () => {
      const controller = createController(events)
      expect(events.sent).toHaveLength(0)
      const snapshot = controller.getSnapshot()
      expect(snapshot.visible).toBe(false)
      expect(snapshot.activeEnvironmentId).toBeNull()
      expect(snapshot.connectionStatus).toBe('open')
      expect(snapshot.sessions.size).toBe(0)
      expect(controller.getSnapshot()).toBe(snapshot)
    })

    it('subscribe 在状态变化时通知并可取消', () => {
      const controller = createController(events)
      const listener = vi.fn()
      const unsubscribe = controller.subscribe(listener)
      controller.show(ENV_A)
      expect(listener).toHaveBeenCalled()
      const count = listener.mock.calls.length
      unsubscribe()
      controller.hide()
      expect(listener.mock.calls.length).toBe(count)
    })

    it('start/stop 幂等，未 start 时 stop 安全', () => {
      const controller = createController(events)
      controller.stop()
      controller.start()
      controller.start()
      expect(events.sent).toHaveLength(0)
      controller.stop()
    })

    it('未建立活动会话时所有操作是 no-op', () => {
      const controller = createController(events)
      controller.refresh()
      controller.claim()
      controller.takeover()
      controller.release()
      controller.restart()
      controller.terminate()
      expect(events.sent).toHaveLength(0)
      expect(controller.sendInput(new Uint8Array([1]))).toBe(false)
      expect(controller.resize(80, 24)).toBe(false)
    })
  })

  describe('OPEN/ATTACH 与首流基线', () => {
    it('首次可见选环境只 OPEN 一次，重复选择不重复 OPEN', () => {
      const controller = createController(events)
      controller.start()
      controller.show(ENV_A)
      expect(countType(events, 'OPEN')).toBe(1)
      controller.show(ENV_A)
      controller.selectEnvironment(ENV_A)
      expect(countType(events, 'OPEN')).toBe(1)
    })

    it('基线未就绪前不能 CLAIM/TAKEOVER/INPUT/RESIZE', () => {
      const controller = createController(events)
      controller.start()
      attach(controller, events)
      controller.claim()
      controller.takeover()
      expect(countType(events, 'CLAIM')).toBe(0)
      expect(countType(events, 'TAKEOVER')).toBe(0)
      expect(controller.sendInput(new Uint8Array([1]))).toBe(false)
      expect(controller.resize(80, 24)).toBe(false)
      expect(countType(events, 'INPUT')).toBe(0)
      expect(countType(events, 'RESIZE')).toBe(0)
    })

    it('RESET 原子发布 view 且 applied 发送 VIEW_APPLIED，重复 applied 不重复 ACK', () => {
      const controller = createController(events)
      controller.start()
      attach(controller, events)
      events.emit(viewUpdateEvent(makeReset()))
      expect(session(controller).view?.version).toBe(1)
      expect(session(controller).viewApplied).toBe(false)
      expect(countType(events, 'VIEW_APPLIED')).toBe(0)
      controller.applied(STREAM_1, 1)
      expect(countType(events, 'VIEW_APPLIED')).toBe(1)
      expect(session(controller).viewApplied).toBe(true)
      controller.applied(STREAM_1, 1)
      controller.applied(STREAM_1, 1)
      expect(countType(events, 'VIEW_APPLIED')).toBe(1)
    })

    it('applied 只接受当前 stream/version', () => {
      const controller = createController(events)
      controller.start()
      attach(controller, events)
      events.emit(viewUpdateEvent(makeReset()))
      controller.applied(STREAM_2, 1)
      controller.applied(STREAM_1, 99)
      expect(countType(events, 'VIEW_APPLIED')).toBe(0)
    })

    it('已知 identity 时 refresh 只 ATTACH，不自动 OPEN', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      events.sent.length = 0
      controller.refresh()
      expect(events.sent.map((command) => command.type)).toEqual(['ATTACH'])
    })

    it('无 identity 的 OPEN 截止后只提示，需明确 refresh 才再次 OPEN', () => {
      const controller = createController(events)
      controller.start()
      controller.show(ENV_A)
      vi.advanceTimersByTime(10000)
      expect(session(controller).notice).toBe('unavailable')
      controller.show(ENV_A)
      expect(countType(events, 'OPEN')).toBe(1)
      controller.refresh()
      expect(countType(events, 'OPEN')).toBe(2)
    })

    it('start-stop-start 不发送第二个 OPEN 且重新武装 pending 归属', () => {
      const controller = createController(events)
      controller.start()
      controller.show(ENV_A)
      const open = findCommand(events, 'OPEN')!
      controller.stop()
      controller.start()
      expect(countType(events, 'OPEN')).toBe(1)
      events.emit(attachedEvent(open.requestId, IDENTITY, STREAM_1))
      expect(session(controller).identity).toEqual(IDENTITY)
      expect(session(controller).streamId).toBe(STREAM_1)
    })

    it('迟到/错 env/错 viewer 的 ATTACHED 被围栏丢弃', () => {
      const controller = createController(events)
      controller.start()
      controller.show(ENV_A)
      const open = findCommand(events, 'OPEN')!
      events.emit(attachedEvent('00000000-0000-4000-8000-0000000000ff', IDENTITY, STREAM_1))
      events.emit(attachedEvent(open.requestId, IDENTITY, STREAM_1, 1, ENV_B))
      events.emit({ ...attachedEvent(open.requestId, IDENTITY, STREAM_1), viewerId: 'x' })
      expect(session(controller).streamId).toBeNull()
      events.emit(attachedEvent(open.requestId, IDENTITY, STREAM_1))
      expect(session(controller).streamId).toBe(STREAM_1)
    })

    it('ATTACH 只接受 expected identity', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      controller.refresh()
      const attachCmd = findCommand(events, 'ATTACH')!
      events.emit(attachedEvent(attachCmd.requestId, OTHER_IDENTITY, STREAM_2))
      expect(session(controller).identity).toEqual(IDENTITY)
      expect(session(controller).streamId).toBeNull()
    })

    it('非规范 environment id 提示 unavailable 且不发命令', () => {
      const controller = createController(events)
      controller.start()
      controller.show('not-a-uuid')
      expect(events.sent).toHaveLength(0)
      expect(controller.getSnapshot().sessions.get('not-a-uuid')?.notice).toBe('unavailable')
    })
  })

  describe('控制权与心跳', () => {
    it('claim 在基线上发送 CLAIM；GRANTED 使 hasControl 为真', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      expect(session(controller).hasControl).toBe(false)
      controller.claim()
      const claim = findCommand(events, 'CLAIM')!
      expect(claim.payload.recovery).toBeNull()
      events.emit(writerChangedEvent(claim.requestId, granted()))
      expect(session(controller).hasControl).toBe(true)
    })

    it('普通 RESET 发布不使 hasControl 失效', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      expect(session(controller).hasControl).toBe(true)
      events.emit(viewUpdateEvent(makeReset(STREAM_1, 2, 1)))
      expect(session(controller).hasControl).toBe(true)
    })

    it('观察者每 5s 发送 grant:null 心跳；有控制时携带 grant', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      vi.advanceTimersByTime(5000)
      const observerHeartbeat = findCommand(events, 'KEEPALIVE')!
      expect(observerHeartbeat.payload.grant).toBeNull()
      expect(observerHeartbeat.payload.streamId).toBe(STREAM_1)
      grantControl(controller, events)
      vi.advanceTimersByTime(5000)
      expect(findCommand(events, 'KEEPALIVE')!.payload.grant).toEqual(GRANT)
    })

    it('takeover 携带观察到的 epoch；release 发送 RELEASE 并在 RELEASED 后失权', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      events.emit(writerChangedEvent(null, null, writerState(EPOCH_1)))
      controller.takeover()
      expect(findCommand(events, 'TAKEOVER')!.payload.expectedWriterEpoch).toBe(EPOCH_1)
      grantControl(controller, events)
      controller.release()
      const release = findCommand(events, 'RELEASE')!
      events.emit(
        writerChangedEvent(release.requestId, {
          status: 'RELEASED',
          grant: null,
          recovered: null,
          reason: null,
        }),
      )
      expect(session(controller).hasControl).toBe(false)
    })

    it('恢复 BUSY 只提示不重放', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      controller.claim()
      const claim = findCommand(events, 'CLAIM')!
      events.emit(
        writerChangedEvent(claim.requestId, {
          status: 'BUSY',
          grant: null,
          recovered: null,
          reason: null,
        }),
      )
      expect(session(controller).notice).toBe('control-rejected')
    })

    it('异步 WRITER_CHANGED 广播在 epoch 变化时明确失去控制', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      events.emit(writerChangedEvent(null, null, writerState(EPOCH_2)))
      expect(session(controller).hasControl).toBe(false)
      expect(session(controller).notice).toBe('control-rejected')
    })

    it('claim 截止后提示并触发一次恢复 ATTACH', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      controller.claim()
      vi.advanceTimersByTime(10000)
      expect(session(controller).notice).toBe('control-rejected')
      expect(countType(events, 'ATTACH')).toBe(1)
    })

    it('hide/stop 清除心跳', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      controller.hide()
      const sent = events.sent.length
      vi.advanceTimersByTime(20000)
      expect(events.sent.length).toBe(sent)
    })
  })

  describe('输入、预算与 seq', () => {
    function withControl(controller: TerminalController): void {
      establish(controller, events)
      grantControl(controller, events)
    }

    it('单包输入携带正确 seq/摘要；在途时缓冲不重复发送', () => {
      const controller = createController(events)
      controller.start()
      withControl(controller)
      const bytes = new Uint8Array([0x61, 0x62, 0x63])
      expect(controller.sendInput(bytes)).toBe(true)
      const input = findCommand(events, 'INPUT')!
      expect(input.payload.seq).toBe(1)
      expect(input.payload.bytes).toEqual(bytes)
      expect(controller.sendInput(new Uint8Array([0x64]))).toBe(true)
      expect(countType(events, 'INPUT')).toBe(1)
      expect(session(controller).pending).toBe(true)
      events.emit(opAckEvent(EPOCH_1, confirmed(1, inputOperationDigest(bytes, 1), 'WRITTEN')))
      expect(countType(events, 'INPUT')).toBe(2)
      expect(findCommand(events, 'INPUT')!.payload.bytes).toEqual(new Uint8Array([0x64]))
    })

    it('输入总预算包含在途 INPUT 字节', () => {
      const controller = createController(events)
      controller.start()
      withControl(controller)
      expect(controller.sendInput(new Uint8Array(4096))).toBe(true)
      expect(controller.sendInput(new Uint8Array(MAX_PENDING_INPUT_BYTES - 4096))).toBe(true)
      expect(controller.sendInput(new Uint8Array(1))).toBe(false)
      expect(session(controller).notice).toBe('backpressure')
    })

    it('超过单包上限时按 4096 字节切块', () => {
      const controller = createController(events)
      controller.start()
      withControl(controller)
      expect(controller.sendInput(new Uint8Array(4106).fill(7))).toBe(true)
      const first = findCommand(events, 'INPUT')!
      expect(first.payload.bytes.length).toBe(4096)
      events.emit(
        opAckEvent(EPOCH_1, confirmed(1, inputOperationDigest(first.payload.bytes, 1), 'WRITTEN')),
      )
      const second = findCommand(events, 'INPUT')!
      expect(second.payload.bytes.length).toBe(10)
      expect(second.payload.seq).toBe(2)
    })

    it('INPUT 与 RESIZE 共享单调 seq', () => {
      const controller = createController(events)
      controller.start()
      withControl(controller)
      const bytes = new Uint8Array([0x61])
      controller.sendInput(bytes)
      events.emit(opAckEvent(EPOCH_1, confirmed(1, inputOperationDigest(bytes, 1), 'WRITTEN')))
      expect(controller.resize(80, 24)).toBe(true)
      const resize = findCommand(events, 'RESIZE')!
      expect(resize.payload.seq).toBe(2)
      expect(resizeOperationDigest(80, 24)).toHaveLength(64)
    })

    it('无效尺寸与无控制权时拒绝 resize/input', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      expect(controller.resize(80, 24)).toBe(false)
      expect(controller.sendInput(new Uint8Array([1]))).toBe(false)
      grantControl(controller, events)
      expect(controller.resize(4, 24)).toBe(false)
      expect(controller.resize(80, 1)).toBe(false)
      expect(controller.sendInput(new Uint8Array(0))).toBe(false)
      expect(countType(events, 'RESIZE')).toBe(0)
    })

    it('模式变化丢弃尚未发送的旧模式字节且不后续重放', () => {
      const controller = createController(events)
      controller.start()
      withControl(controller)
      controller.sendInput(new Uint8Array([0x61]))
      controller.sendInput(new Uint8Array([0x62, 0x63]))
      events.emit(viewUpdateEvent(makeReset(STREAM_1, 2, 2)))
      expect(session(controller).notice).toBe('stale-mode')
      events.emit(
        opAckEvent(EPOCH_1, confirmed(1, inputOperationDigest(new Uint8Array([0x61]), 1), 'WRITTEN')),
      )
      expect(countType(events, 'INPUT')).toBe(1)
    })
  })

  describe('发送先登记后发送', () => {
    it('OPEN 的同步 ATTACHED 回执也能匹配', () => {
      const controller = createController(events)
      controller.start()
      events.onSend = (command) => {
        if (command.type === 'OPEN') {
          events.onSend = null
          events.emit(attachedEvent(command.requestId, IDENTITY, STREAM_1))
        }
      }
      controller.show(ENV_A)
      expect(session(controller).identity).toEqual(IDENTITY)
      expect(session(controller).streamId).toBe(STREAM_1)
    })

    it('INPUT 的同步 OP_ACK 回执也能匹配在途操作', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      const bytes = new Uint8Array([0x61])
      events.onSend = (command) => {
        if (command.type === 'INPUT') {
          events.onSend = null
          events.emit(
            opAckEvent(
              EPOCH_1,
              confirmed(1, inputOperationDigest(command.payload.bytes, 1), 'WRITTEN'),
            ),
          )
        }
      }
      expect(controller.sendInput(bytes)).toBe(true)
      expect(session(controller).pending).toBe(false)
    })

    it('控制请求 send false 保留尝试登记且不自动重复', () => {
      const controller = createController(events)
      controller.start()
      events.accept = false
      controller.show(ENV_A)
      expect(countType(events, 'OPEN')).toBe(1)
      expect(session(controller).notice).toBe('unavailable')
      events.accept = true
      controller.show(ENV_A)
      expect(countType(events, 'OPEN')).toBe(1)
    })

    it('输入 send false 冻结为 unknown 且不重发', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      events.accept = false
      controller.sendInput(new Uint8Array([0x61]))
      expect(session(controller).pending).toBe(true)
      expect(session(controller).notice).toBe('outcome-unknown')
      expect(countType(events, 'INPUT')).toBe(1)
      expect(controller.sendInput(new Uint8Array([0x62]))).toBe(false)
      expect(countType(events, 'INPUT')).toBe(1)
    })
  })

  describe('操作 ACK 决议', () => {
    function setup(): { controller: TerminalController; digest: string } {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      const bytes = new Uint8Array([0x61])
      controller.sendInput(bytes)
      return { controller, digest: inputOperationDigest(bytes, 1) }
    }

    it('PENDING 不是完成', () => {
      const { controller, digest } = setup()
      events.emit(
        opAckEvent(EPOCH_1, { kind: 'PENDING', seq: 1, digest, outcome: null, reason: null }),
      )
      expect(session(controller).pending).toBe(true)
    })

    it('NOT_WRITTEN 提示且不自动重发', () => {
      const { controller, digest } = setup()
      events.emit(opAckEvent(EPOCH_1, confirmed(1, digest, 'NOT_WRITTEN')))
      expect(session(controller).pending).toBe(false)
      expect(session(controller).notice).toBe('not-written')
      expect(countType(events, 'INPUT')).toBe(1)
    })

    it('OUTCOME_UNKNOWN 冻结', () => {
      const { controller, digest } = setup()
      events.emit(opAckEvent(EPOCH_1, confirmed(1, digest, 'OUTCOME_UNKNOWN')))
      expect(session(controller).notice).toBe('outcome-unknown')
      expect(controller.sendInput(new Uint8Array([0x62]))).toBe(false)
    })

    it('epoch/seq/digest 不匹配的 ACK 被忽略', () => {
      const { controller, digest } = setup()
      events.emit(opAckEvent(EPOCH_2, confirmed(1, digest, 'WRITTEN')))
      events.emit(opAckEvent(EPOCH_1, confirmed(2, digest, 'WRITTEN')))
      events.emit(opAckEvent(EPOCH_1, confirmed(1, 'a'.repeat(64), 'WRITTEN')))
      expect(session(controller).pending).toBe(true)
    })

    it('REJECTED STALE_MODE 丢弃未发送字节', () => {
      const { controller, digest } = setup()
      events.emit(
        opAckEvent(
          EPOCH_1,
          { kind: 'REJECTED', seq: 1, digest, outcome: null, reason: 'INVALID' },
          'STALE_MODE',
        ),
      )
      expect(session(controller).notice).toBe('stale-mode')
    })

    it('REJECTED NOT_OWNER/FROZEN/SEQ_GAP/未知原因分别映射', () => {
      const { controller, digest } = setup()
      events.emit(
        opAckEvent(EPOCH_1, {
          kind: 'REJECTED',
          seq: 1,
          digest,
          outcome: null,
          reason: 'NOT_OWNER',
        }),
      )
      expect(session(controller).notice).toBe('control-rejected')

      const events2 = new FakeEvents()
      const second = new TerminalController({ events: events2, viewerId: VIEWER })
      second.start()
      establish(second, events2)
      grantControl(second, events2)
      const bytes2 = new Uint8Array([0x62])
      second.sendInput(bytes2)
      const digest2 = inputOperationDigest(bytes2, 1)
      events2.emit(
        opAckEvent(EPOCH_1, {
          kind: 'REJECTED',
          seq: 1,
          digest: digest2,
          outcome: null,
          reason: 'FROZEN',
        }),
      )
      expect(session(second).notice).toBe('outcome-unknown')

      const third = createController(events)
      third.start()
      establish(third, events)
      grantControl(third, events)
      const bytes3 = new Uint8Array([0x63])
      third.sendInput(bytes3)
      events.emit(
        opAckEvent(EPOCH_1, {
          kind: 'REJECTED',
          seq: 1,
          digest: inputOperationDigest(bytes3, 1),
          outcome: null,
          reason: 'SEQ_GAP',
        }),
      )
      expect(session(third).notice).toBe('failed')
    })

    it('操作截止冻结并触发一次恢复 ATTACH', () => {
      const { controller } = setup()
      vi.advanceTimersByTime(10000)
      expect(session(controller).notice).toBe('outcome-unknown')
      expect(countType(events, 'ATTACH')).toBe(1)
      expect(controller.sendInput(new Uint8Array([0x62]))).toBe(false)
    })
  })

  describe('跨连接恢复与顺序', () => {
    function seedInflight(): { controller: TerminalController; digest: string } {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      const bytes = new Uint8Array([0x61])
      controller.sendInput(bytes)
      return { controller, digest: inputOperationDigest(bytes, 1) }
    }

    it('重连严格在 ATTACHED→RESET→applied 之后才 CLAIM.recovery', () => {
      const { controller, digest } = seedInflight()
      expect(countType(events, 'CLAIM')).toBe(1)
      events.setStatus('backoff')
      events.setStatus('open')
      expect(countType(events, 'CLAIM')).toBe(1)
      const attachCmd = findCommand(events, 'ATTACH')!
      events.emit(attachedEvent(attachCmd.requestId, IDENTITY, STREAM_2))
      expect(countType(events, 'CLAIM')).toBe(1)
      events.emit(viewUpdateEvent(makeReset(STREAM_2, 1, 1)))
      expect(countType(events, 'CLAIM')).toBe(1)
      controller.applied(STREAM_2, 1)
      expect(countType(events, 'CLAIM')).toBe(2)
      expect(findCommand(events, 'CLAIM')!.payload.recovery).toEqual({
        previous: GRANT,
        seq: 1,
        digest,
      })
    })

    it('重连无在途操作也用 seq 0 恢复旧 grant', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      events.setStatus('backoff')
      events.setStatus('open')
      const attachCmd = findCommand(events, 'ATTACH')!
      events.emit(attachedEvent(attachCmd.requestId, IDENTITY, STREAM_2))
      events.emit(viewUpdateEvent(makeReset(STREAM_2, 1, 1)))
      controller.applied(STREAM_2, 1)
      expect(findCommand(events, 'CLAIM')!.payload.recovery).toEqual({
        previous: GRANT,
        seq: 0,
        digest: null,
      })
    })

    it('恢复结果 WRITTEN/NOT_WRITTEN/OUTCOME_UNKNOWN 映射且不重放', () => {
      const { controller, digest } = seedInflight()
      events.setStatus('backoff')
      events.setStatus('open')
      const attachCmd = findCommand(events, 'ATTACH')!
      events.emit(attachedEvent(attachCmd.requestId, IDENTITY, STREAM_2))
      events.emit(viewUpdateEvent(makeReset(STREAM_2, 1, 1)))
      controller.applied(STREAM_2, 1)
      const claim = findCommand(events, 'CLAIM')!
      expect(claim.payload.recovery).toEqual({ previous: GRANT, seq: 1, digest })
      events.emit(writerChangedEvent(claim.requestId, granted('NOT_WRITTEN')))
      expect(session(controller).notice).toBe('not-written')
      expect(countType(events, 'INPUT')).toBe(1)
    })

    it('坏版恢复 ATTACH 有界，不被自身 pending 截止无限重试', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      events.emit(viewUpdateEvent(makeReset(STREAM_1, 1, 1)))
      expect(session(controller).notice).toBe('failed')
      expect(countType(events, 'ATTACH')).toBe(1)
      vi.advanceTimersByTime(100000)
      expect(countType(events, 'ATTACH')).toBe(1)
    })

    it('身份变化的 OPEN 清理旧操作且不重放', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      controller.sendInput(new Uint8Array([0x61]))
      events.emit(exitedEvent('EXITED', 0))
      controller.restart()
      const open = findCommand(events, 'OPEN')!
      expect(open.payload.expectedExited).toEqual(IDENTITY)
      events.emit(attachedEvent(open.requestId, OTHER_IDENTITY, STREAM_2))
      expect(session(controller).identity).toEqual(OTHER_IDENTITY)
      expect(session(controller).hasControl).toBe(false)
      expect(session(controller).notice).toBe('failed')
    })
  })

  describe('隐藏/切换/停止保留恢复证据', () => {
    it('hide 无未决操作时 RELEASE/DETACH，不发 CLOSE', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      events.sent.length = 0
      controller.hide()
      expect(events.sent.map((command) => command.type)).toEqual(['RELEASE', 'DETACH'])
      expect(countType(events, 'CLOSE')).toBe(0)
      expect(controller.getSnapshot().visible).toBe(false)
    })

    it('hide 遇未决操作保留唯一 grant+seq+digest，重新 show 在基线后恢复', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      const bytes = new Uint8Array([0x61])
      controller.sendInput(bytes)
      const digest = inputOperationDigest(bytes, 1)
      events.sent.length = 0
      controller.hide()
      expect(countType(events, 'RELEASE')).toBe(0)
      expect(countType(events, 'DETACH')).toBe(1)
      expect(session(controller).pending).toBe(true)
      expect(session(controller).hasControl).toBe(false)
      controller.show(ENV_A)
      const attachCmd = findCommand(events, 'ATTACH')!
      events.emit(attachedEvent(attachCmd.requestId, IDENTITY, STREAM_2))
      events.emit(viewUpdateEvent(makeReset(STREAM_2, 1, 1)))
      controller.applied(STREAM_2, 1)
      expect(findCommand(events, 'CLAIM')!.payload.recovery).toEqual({
        previous: GRANT,
        seq: 1,
        digest,
      })
    })

    it('切换环境释放旧流并 OPEN 新环境', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      events.sent.length = 0
      controller.show(ENV_B)
      const types = events.sent.map((command) => command.type)
      expect(types).toContain('RELEASE')
      expect(types).toContain('DETACH')
      expect(types).toContain('OPEN')
      expect(controller.getSnapshot().activeEnvironmentId).toBe(ENV_B)
    })

    it('非活动环境自己的 ATTACHED 被接受，旧环境事件被丢弃', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      const openA = findCommand(events, 'OPEN')!
      controller.show(ENV_B)
      const openB = findCommand(events, 'OPEN')!
      events.emit(attachedEvent(openA.requestId, IDENTITY, STREAM_1))
      expect(session(controller, ENV_B).streamId).toBeNull()
      events.emit(viewUpdateEvent(makeReset(), ENV_A))
      expect(session(controller, ENV_B).view).toBeNull()
      events.emit(attachedEvent(openB.requestId, IDENTITY, STREAM_1, 1, ENV_B))
      expect(session(controller, ENV_B).streamId).toBe(STREAM_1)
    })

    it('stop 保留未决证据且之后不再发送；重新 start 只 ATTACH', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      controller.sendInput(new Uint8Array([0x61]))
      controller.stop()
      expect(session(controller).pending).toBe(true)
      const sent = events.sent.length
      vi.advanceTimersByTime(60000)
      expect(events.sent.length).toBe(sent)
      controller.start()
      expect(countType(events, 'ATTACH')).toBe(1)
      expect(countType(events, 'OPEN')).toBe(1)
    })
  })

  describe('终止、重启与错误', () => {
    it('terminate 锁定输入、清未发送字节并等待明确结果', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      controller.terminate()
      expect(findCommand(events, 'CLOSE')!.payload.expectedWriterEpoch).toBe(EPOCH_1)
      expect(session(controller).hasControl).toBe(false)
      expect(controller.sendInput(new Uint8Array([0x61]))).toBe(false)
      vi.advanceTimersByTime(10000)
      expect(session(controller).notice).toBe('outcome-unknown')
    })

    it('terminate send false 提示 unavailable', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      events.accept = false
      controller.terminate()
      expect(session(controller).notice).toBe('unavailable')
    })

    it('restart 仅在 EXITED/FAILED 时可用', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      controller.restart()
      expect(countType(events, 'OPEN')).toBe(1)
      events.emit(exitedEvent('FAILED', null))
      controller.restart()
      expect(countType(events, 'OPEN')).toBe(2)
      expect(findCommand(events, 'OPEN')!.payload.expectedExited).toEqual(IDENTITY)
    })

    it('EXITED 记录状态并清除控制权', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      events.emit(exitedEvent('EXITED', 0))
      expect(session(controller).status).toBe('EXITED')
      expect(session(controller).exitCode).toBe(0)
      expect(session(controller).hasControl).toBe(false)
    })

    it('ERROR 映射为对应提示且不建立新 identity', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      events.emit(errorEvent('ROUTE_UNAVAILABLE'))
      expect(session(controller).notice).toBe('unavailable')
      events.emit(errorEvent('BACKPRESSURE'))
      expect(session(controller).notice).toBe('backpressure')
      events.emit(errorEvent('DAEMON_MISMATCH'))
      expect(session(controller).notice).toBe('failed')
      expect(session(controller).identity).toEqual(IDENTITY)
      events.emit(errorEvent('OUTCOME_UNKNOWN', 'OUTCOME_UNKNOWN'))
      expect(session(controller).notice).toBe('outcome-unknown')
    })

    it('非当前请求关联的迟到 ERROR 被丢弃', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      events.emit(
        errorEvent('ROUTE_UNAVAILABLE', 'NOT_EXECUTED', '00000000-0000-4000-8000-0000000000ff'),
      )
      expect(session(controller).notice).toBeNull()
    })

    it('最多保留 64 个会话，超出提示 scope-limit', () => {
      const controller = createController(events)
      controller.start()
      for (let i = 0; i < MAX_TERMINAL_SESSIONS; i++) {
        controller.show(uuidFrom(i))
      }
      expect(controller.getSnapshot().sessions.size).toBe(MAX_TERMINAL_SESSIONS)
      controller.show(uuidFrom(MAX_TERMINAL_SESSIONS))
      expect(controller.getSnapshot().sessions.size).toBe(MAX_TERMINAL_SESSIONS)
      const activeId = controller.getSnapshot().activeEnvironmentId!
      expect(controller.getSnapshot().sessions.get(activeId)?.notice).toBe('scope-limit')
    })
  })

  describe('会话上限、epoch 与秘密', () => {
    it('同 epoch 重复 CLAIM 不重置 seq', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      const bytes = new Uint8Array([0x61])
      controller.sendInput(bytes)
      controller.claim()
      const claim = findCommand(events, 'CLAIM')!
      events.emit(writerChangedEvent(claim.requestId, granted()))
      events.emit(opAckEvent(EPOCH_1, confirmed(1, inputOperationDigest(bytes, 1), 'WRITTEN')))
      controller.sendInput(new Uint8Array([0x62]))
      expect(findCommand(events, 'INPUT')!.payload.seq).toBe(2)
    })

    it('新 epoch 清除未发送字节并从 seq 1 开始', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      controller.sendInput(new Uint8Array([0x61]))
      controller.sendInput(new Uint8Array([0x62]))
      controller.claim()
      const claim = findCommand(events, 'CLAIM')!
      events.emit(
        writerChangedEvent(
          claim.requestId,
          granted('NOT_WRITTEN', { epoch: EPOCH_2, token: TOKEN_2 }),
          writerState(EPOCH_2),
        ),
      )
      controller.sendInput(new Uint8Array([0x63]))
      const input = findCommand(events, 'INPUT')!
      expect(input.payload.seq).toBe(1)
      expect(input.payload.grant.epoch).toBe(EPOCH_2)
    })

    it('快照不包含 grant/token/待发字节/摘要', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      controller.sendInput(new Uint8Array([0x61]))
      const snap = session(controller)
      expect(Object.keys(snap).sort()).toEqual([
        'environmentId',
        'executable',
        'exitCode',
        'hasControl',
        'identity',
        'notice',
        'pending',
        'status',
        'streamId',
        'view',
        'viewApplied',
        'writer',
      ])
      const serialized = JSON.stringify(snap)
      expect(serialized).not.toContain(TOKEN_1)
      expect(serialized).not.toContain('grant')
      expect(serialized).not.toContain('pendingInput')
    })
  })

  describe('围栏与边界分支', () => {
    /** 重连并完成新流基线（触发恢复 CLAIM）。 */
    function reconnectAndBaseline(controller: TerminalController, streamId = STREAM_2): void {
      events.setStatus('backoff')
      events.setStatus('open')
      const attachCmd = findCommand(events, 'ATTACH')!
      events.emit(attachedEvent(attachCmd.requestId, IDENTITY, streamId))
      events.emit(viewUpdateEvent(makeReset(streamId, 1, 1)))
      controller.applied(streamId, 1)
    }

    /** 建立控制并在途一个 INPUT。 */
    function seedInflight(): TerminalController {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      controller.sendInput(new Uint8Array([0x61]))
      return controller
    }

    it('show 无参数且无活动环境时不发送命令', () => {
      const controller = createController(events)
      controller.start()
      controller.show()
      expect(controller.getSnapshot().visible).toBe(true)
      expect(events.sent).toHaveLength(0)
    })

    it('show 已连接活动环境时复用现有流', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      events.sent.length = 0
      controller.show(ENV_A)
      expect(events.sent).toHaveLength(0)
    })

    it('show 无参数时激活既有选择', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      events.sent.length = 0
      controller.show()
      expect(controller.getSnapshot().visible).toBe(true)
      expect(events.sent).toHaveLength(0)
    })

    it('RESIZE 截止冻结并触发恢复', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      expect(controller.resize(80, 24)).toBe(true)
      vi.advanceTimersByTime(TERMINAL_REQUEST_DEADLINE_MS)
      expect(session(controller).notice).toBe('outcome-unknown')
      expect(countType(events, 'ATTACH')).toBe(1)
    })

    it('不可见时 terminate 只提示 unavailable', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      controller.hide()
      events.sent.length = 0
      controller.terminate()
      expect(session(controller).notice).toBe('unavailable')
      expect(countType(events, 'CLOSE')).toBe(0)
    })

    it('输入被接受后清除背压提示', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      expect(controller.sendInput(new Uint8Array(MAX_PENDING_INPUT_BYTES + 1))).toBe(false)
      expect(session(controller).notice).toBe('backpressure')
      expect(controller.sendInput(new Uint8Array([0x61]))).toBe(true)
      expect(session(controller).notice).toBeNull()
    })

    it('无在途且无需恢复时 CLAIM.recovery 为 null', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      controller.claim()
      expect(findCommand(events, 'CLAIM')!.payload.recovery).toBeNull()
    })

    it('控制请求 send false 时提示 unavailable', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      events.accept = false
      controller.claim()
      expect(session(controller).notice).toBe('unavailable')
    })

    it('resize send false 冻结为 unknown', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      events.accept = false
      expect(controller.resize(80, 24)).toBe(true)
      expect(session(controller).notice).toBe('outcome-unknown')
      expect(session(controller).pending).toBe(true)
    })

    it('无 identity 断开时提示 unavailable', () => {
      const controller = createController(events)
      controller.start()
      controller.show(ENV_A)
      expect(session(controller).identity).toBeNull()
      events.setStatus('backoff')
      expect(session(controller).notice).toBe('unavailable')
    })

    it('stop-start 重新武装 pending 截止', () => {
      const controller = createController(events)
      controller.start()
      controller.show(ENV_A)
      controller.stop()
      controller.start()
      vi.advanceTimersByTime(TERMINAL_REQUEST_DEADLINE_MS)
      expect(session(controller).notice).toBe('unavailable')
    })

    it('WRITER_CHANGED 广播围栏：错身份/迟到请求被丢弃', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      events.emit(writerChangedEvent(null, null, writerState(EPOCH_1)))
      expect(session(controller).writer?.writerEpoch).toBe(EPOCH_1)
      events.emit(writerChangedEvent(null, null, writerState(EPOCH_2), OTHER_IDENTITY))
      expect(session(controller).writer?.writerEpoch).toBe(EPOCH_1)
      events.emit(writerChangedEvent('00000000-0000-4000-8000-0000000000ff', granted()))
      expect(session(controller).hasControl).toBe(false)
      events.emit(writerChangedEvent(null, null, writerState(null, true)))
      expect(session(controller).writer?.frozen).toBe(true)
    })

    it('WRITER_CHANGED 无当前流时被丢弃', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      controller.hide()
      events.emit(writerChangedEvent(null, null, writerState(EPOCH_2)))
      expect(session(controller).writer?.writerEpoch).not.toBe(EPOCH_2)
    })

    it('恢复 REJECTED（不可核对）冻结为 unknown', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      controller.claim()
      const claim = findCommand(events, 'CLAIM')!
      events.emit(
        writerChangedEvent(claim.requestId, {
          status: 'REJECTED',
          grant: null,
          recovered: null,
          reason: 'RECOVERY_MISMATCH',
        }),
      )
      expect(session(controller).notice).toBe('outcome-unknown')
      expect(controller.sendInput(new Uint8Array([0x61]))).toBe(false)
    })

    it('恢复结果 WRITTEN 清除提示', () => {
      const controller = seedInflight()
      reconnectAndBaseline(controller)
      const claim = findCommand(events, 'CLAIM')!
      events.emit(writerChangedEvent(claim.requestId, granted('WRITTEN')))
      expect(session(controller).notice).toBeNull()
      expect(session(controller).pending).toBe(false)
    })

    it('恢复结果 OUTCOME_UNKNOWN 冻结', () => {
      const controller = seedInflight()
      reconnectAndBaseline(controller)
      const claim = findCommand(events, 'CLAIM')!
      events.emit(writerChangedEvent(claim.requestId, granted('OUTCOME_UNKNOWN')))
      expect(session(controller).notice).toBe('outcome-unknown')
      expect(controller.sendInput(new Uint8Array([0x61]))).toBe(false)
    })

    it('恢复 CLAIM 发出后新的 applied 不重复恢复', () => {
      const controller = seedInflight()
      reconnectAndBaseline(controller)
      expect(countType(events, 'CLAIM')).toBe(2)
      events.emit(viewUpdateEvent(makeReset(STREAM_2, 2, 1)))
      controller.applied(STREAM_2, 2)
      expect(countType(events, 'CLAIM')).toBe(2)
    })

    it('OP_ACK 围栏：错环境/错身份/摘要不符/无在途被丢弃', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      const bytes = new Uint8Array([0x61])
      controller.sendInput(bytes)
      const digest = inputOperationDigest(bytes, 1)
      events.emit(opAckEvent(EPOCH_1, confirmed(1, digest, 'WRITTEN'), null, ENV_B))
      events.emit(
        opAckEvent(EPOCH_1, confirmed(1, digest, 'WRITTEN'), null, ENV_A, OTHER_IDENTITY),
      )
      events.emit(opAckEvent(EPOCH_1, confirmed(1, 'b'.repeat(64), 'WRITTEN')))
      events.emit(
        opAckEvent(EPOCH_1, {
          kind: 'REJECTED',
          seq: 1,
          digest: 'c'.repeat(64),
          outcome: null,
          reason: 'INVALID',
        }),
      )
      expect(session(controller).pending).toBe(true)
      events.emit(opAckEvent(EPOCH_1, confirmed(1, digest, 'WRITTEN')))
      expect(session(controller).pending).toBe(false)
      events.emit(opAckEvent(EPOCH_1, confirmed(1, digest, 'WRITTEN')))
      expect(session(controller).pending).toBe(false)
    })

    it('CONFIRMED STALE_MODE 丢弃未发送字节', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      controller.sendInput(new Uint8Array([0x61]))
      controller.sendInput(new Uint8Array([0x62]))
      events.emit(
        opAckEvent(
          EPOCH_1,
          confirmed(1, inputOperationDigest(new Uint8Array([0x61]), 1), 'WRITTEN'),
          'STALE_MODE',
        ),
      )
      expect(session(controller).notice).toBe('stale-mode')
      expect(countType(events, 'INPUT')).toBe(1)
    })

    it('REJECTED 未知原因提示失败并丢弃未发送字节', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      controller.sendInput(new Uint8Array([0x61]))
      controller.sendInput(new Uint8Array([0x62]))
      events.emit(
        opAckEvent(EPOCH_1, {
          kind: 'REJECTED',
          seq: 1,
          digest: inputOperationDigest(new Uint8Array([0x61]), 1),
          outcome: null,
          reason: 'INVALID',
        }),
      )
      expect(session(controller).notice).toBe('failed')
      expect(countType(events, 'INPUT')).toBe(1)
    })

    it('VIEW_UPDATE 围栏：错身份/错流被丢弃', () => {
      const controller = createController(events)
      controller.start()
      attach(controller, events)
      events.emit(viewUpdateEvent(makeReset(), ENV_A, OTHER_IDENTITY))
      expect(session(controller).view).toBeNull()
      events.emit(viewUpdateEvent(makeReset(STREAM_2)))
      expect(session(controller).view).toBeNull()
    })

    it('EXITED 围栏：错身份/无流被丢弃', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      events.emit(exitedEvent('EXITED', 0, OTHER_IDENTITY))
      expect(session(controller).status).toBe('RUNNING')
      controller.hide()
      events.emit(exitedEvent('EXITED', 0))
      expect(session(controller).status).toBe('RUNNING')
    })

    it('ERROR 围栏：错环境/错身份被丢弃', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      events.emit(errorEvent('ROUTE_UNAVAILABLE', 'NOT_EXECUTED', null, ENV_B))
      events.emit(
        errorEvent('ROUTE_UNAVAILABLE', 'NOT_EXECUTED', null, ENV_A, OTHER_IDENTITY),
      )
      expect(session(controller).notice).toBeNull()
      events.emit(errorEvent('ROUTE_UNAVAILABLE', 'NOT_EXECUTED', null, ENV_A, null))
      expect(session(controller).notice).toBe('unavailable')
    })

    it('ERROR 关联当前请求时清理 pending 截止', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      controller.claim()
      const claim = findCommand(events, 'CLAIM')!
      events.emit(errorEvent('ROUTE_UNAVAILABLE', 'NOT_EXECUTED', claim.requestId))
      expect(session(controller).notice).toBe('unavailable')
      vi.advanceTimersByTime(TERMINAL_REQUEST_DEADLINE_MS)
      expect(countType(events, 'ATTACH')).toBe(0)
    })

    it('ERROR STALE_MODE 丢弃未发送字节并清除在途', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      controller.sendInput(new Uint8Array([0x61]))
      controller.sendInput(new Uint8Array([0x62]))
      events.emit(errorEvent('STALE_MODE'))
      expect(session(controller).notice).toBe('stale-mode')
      expect(session(controller).pending).toBe(false)
    })

    it('ERROR 控制冲突与未知码分别映射', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      events.emit(errorEvent('VIEW_NOT_APPLIED'))
      expect(session(controller).notice).toBe('control-rejected')
      events.emit(errorEvent('INVALID_REQUEST'))
      expect(session(controller).notice).toBe('failed')
    })

    it('terminate 后 EXITED 清除终止截止', () => {
      const controller = createController(events)
      controller.start()
      establish(controller, events)
      grantControl(controller, events)
      controller.terminate()
      events.emit(exitedEvent('EXITED', 0))
      expect(session(controller).status).toBe('EXITED')
      expect(session(controller).notice).toBeNull()
      const sent = events.sent.length
      vi.advanceTimersByTime(20000)
      expect(events.sent.length).toBe(sent)
    })
  })
})
