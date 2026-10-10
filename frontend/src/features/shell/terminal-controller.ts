/**
 * 单一浏览器终端控制器（TerminalController）。
 *
 * 唯一 shell 控制/观察协调者：订阅既有 app-events 终端的 `subscribeTerminal`
 * 事件，为每个 environment 维护会话状态机，并复用同一连接发送 OPEN/ATTACH/
 * CLAIM/TAKEOVER/RELEASE/INPUT/RESIZE/VIEW_APPLIED/KEEPALIVE/CLOSE。
 *
 * 关键约束：
 * - 已知 identity 只 ATTACH；首次环境可 OPEN 一次；已尝试的 OPEN 不自动重复。
 * - 新流必须先收到 RESET 且 UI 完成真实 DOM commit（applied）才允许 CLAIM/
 *   TAKEOVER/INPUT/RESIZE；普通 PATCH 不使已建立基线失效。
 * - writer 显式 CLAIM/TAKEOVER；INPUT/RESIZE 共享从 1 起的 seq，单一在途操作。
 * - 待发输入预算含在途 INPUT 字节；超 65536 整体拒绝，不截断。
 * - 10 秒请求/操作截止；跨连接恢复保留唯一旧 grant+seq+digest，用 CLAIM.recovery
 *   核对；BUSY/无法核对只提示，不自动重放操作。
 * - 公开快照只含非敏感状态，绝不包含 writer token、grant、待发字节或操作摘要。
 */

import type {
  ApplicationEventConnectionStatus,
  ApplicationEventManager,
} from '@/shared/app-events'
import { createUuid, isCanonicalUuid } from '@/shared/lib/uuid'
import {
  type TerminalAttachedEvent,
  type TerminalCommand,
  type TerminalErrorEvent,
  type TerminalEvent,
  type TerminalExitedEvent,
  type TerminalIdentity,
  type TerminalOpAckEvent,
  type TerminalStatus,
  type TerminalViewUpdateEvent,
  type TerminalWriterChangedEvent,
  type WriterGrant,
  type WriterState,
} from './terminal-control-codec'
import type { TerminalMirrorState } from './terminal-view-mirror'
import { TerminalViewMirror } from './terminal-view-mirror'
import { MAX_COLUMNS, MAX_ROWS, MIN_COLUMNS, MIN_ROWS } from './terminal-view-codec'
import { inputOperationDigest, resizeOperationDigest } from './terminal-operation-digest'

/** 用户可见的终端提示；只描述结果，不泄露内部秘密。 */
export type TerminalNotice =
  | 'unavailable'
  | 'backpressure'
  | 'not-written'
  | 'outcome-unknown'
  | 'control-rejected'
  | 'stale-mode'
  | 'failed'
  | 'scope-limit'

/** 单会话公开快照：不含 writer token、待发字节或操作摘要。 */
export interface TerminalSessionSnapshot {
  readonly environmentId: string
  readonly identity: TerminalIdentity | null
  readonly streamId: string | null
  readonly executable: string | null
  readonly status: TerminalStatus | null
  readonly exitCode: number | null
  readonly view: TerminalMirrorState | null
  readonly viewApplied: boolean
  readonly writer: WriterState | null
  readonly hasControl: boolean
  readonly pending: boolean
  readonly notice: TerminalNotice | null
}

/** 工作区公开快照。 */
export interface TerminalWorkspaceSnapshot {
  readonly visible: boolean
  readonly activeEnvironmentId: string | null
  readonly connectionStatus: ApplicationEventConnectionStatus
  readonly sessions: ReadonlyMap<string, TerminalSessionSnapshot>
}

export interface TerminalControllerOptions {
  events: Pick<ApplicationEventManager, 'getStatus' | 'sendTerminal' | 'subscribeTerminal'>
  viewerId?: string
}

/** 单个 workspace 内保留的会话上限。 */
export const MAX_TERMINAL_SESSIONS = 64
/** 待发输入 + 在途 INPUT 字节总量上限。 */
export const MAX_PENDING_INPUT_BYTES = 65536
/** 单个 INPUT 包字节上限。 */
export const MAX_INPUT_PACKET_BYTES = 4096
/** 控制请求、操作与终止的统一截止时间。 */
export const TERMINAL_REQUEST_DEADLINE_MS = 10000
/** writer/observer 心跳间隔。 */
export const TERMINAL_KEEPALIVE_INTERVAL_MS = 5000

type ControlRequestKind = 'OPEN' | 'ATTACH' | 'CLAIM' | 'TAKEOVER' | 'RELEASE' | 'CLOSE'

interface PendingRequest {
  kind: ControlRequestKind
  requestId: string
  timer: ReturnType<typeof setTimeout> | null
}

interface InflightOperation {
  kind: 'INPUT' | 'RESIZE'
  /** 发出该操作的请求 id，用于精确关联 gateway pre-native NOT_EXECUTED 错误。 */
  requestId: string
  seq: number
  digest: string
  /** 发出该操作时绑定的 grant（不可变）；绝不用新授权核对旧操作。 */
  grant: WriterGrant
  /** INPUT 的原始字节数（不保留字节本身），用于总预算。 */
  inputBytes: number
  timer: ReturnType<typeof setTimeout> | null
}

interface RecoveryProof {
  previous: WriterGrant
  seq: number
  digest: string | null
}

interface SessionRuntime {
  readonly environmentId: string
  identity: TerminalIdentity | null
  streamId: string | null
  executable: string | null
  status: TerminalStatus | null
  exitCode: number | null
  inputModeRevision: number
  readonly mirror: TerminalViewMirror
  view: TerminalMirrorState | null
  /** 当前 stream 上已 ACK 的最高版本；非 null 表示已有可用基线。 */
  appliedVersion: number | null
  writer: WriterState | null
  /** 私有 writer 授权 secret；绝不进入快照。 */
  grant: WriterGrant | null
  frozen: boolean
  notice: TerminalNotice | null
  openAttempted: boolean
  pendingRequest: PendingRequest | null
  inflight: InflightOperation | null
  nextSeq: number
  pendingInput: Uint8Array
  queuedResize: { cols: number; rows: number } | null
  keepaliveTimer: ReturnType<typeof setInterval> | null
  /** applied 发送重入守卫：正在发送的版本；防止同步回执导致重复 ACK。 */
  applyingVersion: number | null
  /** 曾有一个操作结果无法核对，需持续向用户提示直到显式重新同步。 */
  unverified: boolean
  /** 每个恢复事件只允许一次自动 re-ATTACH。 */
  recoveryAttachIssued: boolean
  /** 每个恢复事件只允许一次自动 CLAIM.recovery。 */
  recoveryClaimIssued: boolean
  /** 断线/重建流之后，需在新基线就绪时核对旧操作。 */
  recoverOnBaseline: boolean
}

function createSession(environmentId: string): SessionRuntime {
  return {
    environmentId,
    identity: null,
    streamId: null,
    executable: null,
    status: null,
    exitCode: null,
    inputModeRevision: 0,
    mirror: new TerminalViewMirror(),
    view: null,
    appliedVersion: null,
    writer: null,
    grant: null,
    frozen: false,
    notice: null,
    openAttempted: false,
    pendingRequest: null,
    inflight: null,
    nextSeq: 0,
    pendingInput: new Uint8Array(0),
    queuedResize: null,
    keepaliveTimer: null,
    applyingVersion: null,
    unverified: false,
    recoveryAttachIssued: false,
    recoveryClaimIssued: false,
    recoverOnBaseline: false,
  }
}

function sameIdentity(a: TerminalIdentity | null, b: TerminalIdentity | null): boolean {
  return (
    a !== null &&
    b !== null &&
    a.daemonInstanceId === b.daemonInstanceId &&
    a.terminalId === b.terminalId
  )
}

/** 单一浏览器终端控制器；构造无网络与定时器副作用。 */
export class TerminalController {
  private readonly events: TerminalControllerOptions['events']
  private readonly viewerId: string
  private readonly sessions = new Map<string, SessionRuntime>()
  private readonly listeners = new Set<() => void>()
  private started = false
  private visible = false
  private activeEnvironmentId: string | null = null
  private connectionStatus: ApplicationEventConnectionStatus
  private unsubscribeEvents: (() => void) | null = null
  private snapshot: TerminalWorkspaceSnapshot | null = null

  constructor(options: TerminalControllerOptions) {
    this.events = options.events
    this.viewerId = options.viewerId ?? createUuid()
    this.connectionStatus = options.events.getStatus()
  }

  /** 开始订阅事件并按当前可见状态激活唯一活动会话。 */
  start(): void {
    if (this.started) {
      return
    }
    this.started = true
    this.connectionStatus = this.events.getStatus()
    this.unsubscribeEvents = this.events.subscribeTerminal(this.viewerId, {
      onEvent: (event) => this.handleEvent(event),
      onStatusChange: (status) => this.handleStatusChange(status),
    })
    if (this.visible && this.activeEnvironmentId !== null) {
      this.activate(this.activeEnvironmentId)
    }
    const active = this.activeSession()
    if (active !== null) {
      this.rearmPendingDeadline(active)
    }
    this.notify()
  }

  /** 释放订阅与定时器；保留 identity/末屏/未决恢复证据，使重新 start 只 ATTACH。 */
  stop(): void {
    if (!this.started) {
      return
    }
    this.started = false
    this.unsubscribeEvents?.()
    this.unsubscribeEvents = null
    const active = this.activeSession()
    for (const session of this.sessions.values()) {
      this.clearKeepalive(session)
      if (session.pendingRequest !== null && session.pendingRequest.timer !== null) {
        clearTimeout(session.pendingRequest.timer)
        session.pendingRequest.timer = null
      }
      if (session.inflight !== null && session.inflight.timer !== null) {
        clearTimeout(session.inflight.timer)
        session.inflight.timer = null
      }
      this.discardUnsent(session)
    }
    if (active !== null) {
      this.teardownActive(active, false)
    }
    this.notify()
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  getSnapshot = (): TerminalWorkspaceSnapshot => {
    if (this.snapshot === null) {
      this.snapshot = this.buildSnapshot()
    }
    return this.snapshot
  }

  /** 展开面板；给定 environmentId 时选择该环境，否则只激活既有选择。 */
  show(environmentId?: string): void {
    this.visible = true
    if (environmentId !== undefined) {
      this.select(environmentId)
      return
    }
    if (this.activeEnvironmentId !== null) {
      this.activate(this.activeEnvironmentId)
    }
    this.notify()
  }

  /** 折叠面板：丢弃未发送字节、尽力 RELEASE/DETACH，不发送 CLOSE。 */
  hide(): void {
    this.visible = false
    const active = this.activeSession()
    if (active !== null) {
      this.teardownActive(active, true)
    }
    this.notify()
  }

  /** 选择活动环境；切换时先释放旧活动流，再按可见性激活新环境。 */
  selectEnvironment(id: string): void {
    this.select(id)
  }

  /**
   * 用户明确重新同步：允许再一次 OPEN（无 identity）或一次新的恢复尝试
   * （有 identity），已知 identity 只 ATTACH。
   */
  refresh(): void {
    const session = this.activeSession()
    if (session === null) {
      return
    }
    session.notice = null
    session.unverified = false
    session.recoveryAttachIssued = false
    session.recoveryClaimIssued = false
    if (session.identity === null) {
      session.openAttempted = false
    }
    this.sendAttach(session)
    this.notify()
  }

  /** 显式获取控制权；有旧 grant proof 时用 CLAIM.recovery 核对。 */
  claim(): void {
    const session = this.activeSession()
    const identity = session?.identity ?? null
    const streamId = session?.streamId ?? null
    if (session === null || identity === null || streamId === null) {
      return
    }
    if (session.pendingRequest !== null) {
      // 已有在途控制请求（例如 ATTACH）：不得被界面操作覆盖。
      return
    }
    if (session.appliedVersion === null) {
      // 新流还没有 RESET 的真实 DOM commit：不能取得/接管控制。
      return
    }
    const recovery = this.buildRecovery(session)
    session.recoveryClaimIssued = recovery !== null
    this.dispatchControl(session, 'CLAIM', {
      version: 1,
      requestId: createUuid(),
      environmentId: session.environmentId,
      viewerId: this.viewerId,
      type: 'CLAIM',
      payload: { identity, streamId, recovery },
    })
  }

  /** 显式接管控制权，携带观察到的 writer epoch 作 CAS。 */
  takeover(): void {
    const session = this.activeSession()
    const identity = session?.identity ?? null
    const streamId = session?.streamId ?? null
    if (session === null || identity === null || streamId === null) {
      return
    }
    if (session.pendingRequest !== null) {
      return
    }
    if (session.appliedVersion === null) {
      return
    }
    this.dispatchControl(session, 'TAKEOVER', {
      version: 1,
      requestId: createUuid(),
      environmentId: session.environmentId,
      viewerId: this.viewerId,
      type: 'TAKEOVER',
      payload: {
        identity,
        streamId,
        expectedWriterEpoch: session.writer?.writerEpoch ?? null,
      },
    })
  }

  /** 显式释放控制权：发出后立即禁止新输入并丢弃未发送缓冲。 */
  release(): void {
    const session = this.activeSession()
    const identity = session?.identity ?? null
    const streamId = session?.streamId ?? null
    const grant = session?.grant ?? null
    if (session === null || identity === null || streamId === null || grant === null) {
      return
    }
    if (session.pendingRequest !== null) {
      return
    }
    this.discardUnsent(session)
    this.dispatchControl(session, 'RELEASE', {
      version: 1,
      requestId: createUuid(),
      environmentId: session.environmentId,
      viewerId: this.viewerId,
      type: 'RELEASE',
      payload: { identity, streamId, grant },
    })
  }

  /** 显式重启已退出实例；只接受 EXITED/FAILED 的现有 identity。 */
  restart(): void {
    const session = this.activeSession()
    const identity = session?.identity ?? null
    if (
      session === null ||
      identity === null ||
      (session.status !== 'EXITED' && session.status !== 'FAILED')
    ) {
      return
    }
    // 新实例：旧 stream/旧未发送字节/旧恢复证据全部失效。
    this.invalidateStream(session)
    this.clearInflight(session)
    session.grant = null
    session.frozen = false
    session.nextSeq = 0
    session.recoverOnBaseline = false
    session.recoveryClaimIssued = false
    session.unverified = false
    session.applyingVersion = null
    session.notice = null
    this.dispatchControl(session, 'OPEN', {
      version: 1,
      requestId: createUuid(),
      environmentId: session.environmentId,
      viewerId: this.viewerId,
      type: 'OPEN',
      payload: { expectedExited: identity },
    })
  }

  /** 显式终止活动实例：锁定输入、清未发送字节，并等待明确结果。 */
  terminate(): void {
    const session = this.activeSession()
    const identity = session?.identity ?? null
    if (session === null || identity === null) {
      return
    }
    if (session.pendingRequest !== null) {
      // 已有在途控制请求：不覆盖。
      return
    }
    this.discardUnsent(session)
    session.frozen = true
    session.notice = null
    const command: TerminalCommand = {
      version: 1,
      requestId: createUuid(),
      environmentId: session.environmentId,
      viewerId: this.viewerId,
      type: 'CLOSE',
      payload: {
        identity,
        expectedWriterEpoch: session.writer?.writerEpoch ?? null,
      },
    }
    if (!this.canAttemptVisible()) {
      session.notice = 'unavailable'
      this.notify()
      return
    }
    // send 前登记 CLOSE pending，复用统一截止（不新增第二个 timer）。
    this.setPendingRequest(session, 'CLOSE', command.requestId)
    const sent = this.events.sendTerminal(command)
    if (!sent) {
      // native false：命令未确认送达，结果未知；保留 pending 供迟到错误关联。
      session.notice = 'outcome-unknown'
    }
    this.notify()
  }

  /** 追加用户输入字节；超预算整体拒绝。返回是否被接受（不代表已写入 PTY）。 */
  sendInput(bytes: Uint8Array): boolean {
    const session = this.activeSession()
    if (session === null || !this.visible) {
      return false
    }
    if (!(bytes instanceof Uint8Array) || bytes.length === 0) {
      return false
    }
    if (!this.controlUsable(session)) {
      return false
    }
    if (this.pendingBytes(session) + bytes.length > MAX_PENDING_INPUT_BYTES) {
      session.notice = 'backpressure'
      this.notify()
      return false
    }
    if (session.notice === 'backpressure' || session.notice === 'stale-mode') {
      session.notice = null
    }
    const merged = new Uint8Array(session.pendingInput.length + bytes.length)
    merged.set(session.pendingInput, 0)
    merged.set(bytes, session.pendingInput.length)
    session.pendingInput = merged
    this.pump(session)
    return true
  }

  /** 请求 PTY 尺寸（仅可用 writer）。合法 5..300 x 2..100；与 INPUT 共享 seq。 */
  resize(cols: number, rows: number): boolean {
    const session = this.activeSession()
    if (session === null || !this.visible) {
      return false
    }
    if (
      !Number.isSafeInteger(cols) ||
      cols < MIN_COLUMNS ||
      cols > MAX_COLUMNS ||
      !Number.isSafeInteger(rows) ||
      rows < MIN_ROWS ||
      rows > MAX_ROWS
    ) {
      return false
    }
    if (!this.controlUsable(session)) {
      return false
    }
    session.queuedResize = { cols, rows }
    this.pump(session)
    return true
  }

  /** UI 完成当前 stream/version 的真实 DOM commit 后回报并发送 VIEW_APPLIED。 */
  applied(streamId: string, version: number): void {
    const session = this.activeSession()
    const identity = session?.identity ?? null
    if (session === null || identity === null || session.streamId !== streamId) {
      return
    }
    const view = session.view
    if (view === null || view.streamId !== streamId || view.version !== version) {
      return
    }
    if (session.appliedVersion !== null && version <= session.appliedVersion) {
      // 重复 applied 不发送重复 ACK。
      return
    }
    if (session.applyingVersion === version) {
      // 同步回执重入守卫：正在发送同一版本，不重复 ACK。
      return
    }
    if (!this.canAttemptVisible()) {
      return
    }
    session.applyingVersion = version
    const sent = this.events.sendTerminal({
      version: 1,
      requestId: createUuid(),
      environmentId: session.environmentId,
      viewerId: this.viewerId,
      type: 'VIEW_APPLIED',
      payload: { identity, streamId, version },
    })
    session.applyingVersion = null
    if (sent) {
      // 基线资格只能在真实 ACK 被本地 channel 接受后建立。
      session.appliedVersion = version
      this.maybeRecoverOnBaseline(session)
    } else {
      // ACK 未能发出：不得放开 control/recovery，冻结并走一次 ATTACH。
      session.frozen = true
      session.notice = 'outcome-unknown'
      this.requestRecoveryAttach(session)
    }
    this.notify()
  }

  // ---------------------------------------------------------------- 状态查询

  private activeSession(): SessionRuntime | null {
    if (this.activeEnvironmentId === null) {
      return null
    }
    return this.sessions.get(this.activeEnvironmentId) ?? null
  }

  private canAttemptVisible(): boolean {
    return this.started && this.visible && this.connectionStatus === 'open'
  }

  private pendingBytes(session: SessionRuntime): number {
    return session.pendingInput.length + (session.inflight?.inputBytes ?? 0)
  }

  /** 可用控制资格：无在途控制请求，持有匹配 epoch 的 grant，且基线就绪、运行中、未冻结、在线。 */
  private controlUsable(session: SessionRuntime): boolean {
    return (
      this.started &&
      this.connectionStatus === 'open' &&
      session.pendingRequest === null &&
      session.grant !== null &&
      session.streamId !== null &&
      session.appliedVersion !== null &&
      session.status === 'RUNNING' &&
      !session.frozen &&
      session.writer !== null &&
      session.writer.writerEpoch === session.grant.epoch
    )
  }

  /**
   * 构造旧操作核对证据。未决证据必须绑定原始 epoch/grant：只有当前授权仍属于
   * 同一 epoch 时才能用它核对旧操作，绝不拿新授权为旧操作假确认。
   */
  private buildRecovery(session: SessionRuntime): RecoveryProof | null {
    const inflight = session.inflight
    if (inflight !== null) {
      if (session.grant === null || session.grant.epoch !== inflight.grant.epoch) {
        return null
      }
      return { previous: session.grant, seq: inflight.seq, digest: inflight.digest }
    }
    if (session.recoverOnBaseline && session.grant !== null) {
      return { previous: session.grant, seq: 0, digest: null }
    }
    return null
  }

  private select(id: string): void {
    if (id === this.activeEnvironmentId) {
      if (this.visible) {
        this.activate(id)
      }
      this.notify()
      return
    }
    if (!this.sessions.has(id)) {
      if (this.sessions.size >= MAX_TERMINAL_SESSIONS) {
        const active = this.activeSession()
        if (active !== null) {
          active.notice = 'scope-limit'
        }
        this.notify()
        return
      }
      this.sessions.set(id, createSession(id))
    }
    const previous = this.activeSession()
    if (previous !== null) {
      this.teardownActive(previous, true)
    }
    this.activeEnvironmentId = id
    if (this.visible) {
      this.activate(id)
    }
    this.notify()
  }

  private activate(environmentId: string): void {
    const session = this.sessions.get(environmentId)
    if (session === undefined) {
      return
    }
    this.activeEnvironmentId = environmentId
    if (session.streamId !== null) {
      return
    }
    session.notice = null
    this.sendAttach(session)
  }

  /** 停止观察活动会话：丢弃未发送字节；有未决操作时保留恢复证据。 */
  private teardownActive(session: SessionRuntime, clearPending: boolean): void {
    this.discardUnsent(session)
    this.clearKeepalive(session)
    const identity = session.identity
    const streamId = session.streamId
    if (session.inflight !== null) {
      // 未知结果：保留唯一 grant+seq+digest，绝不当成已完成。
      session.frozen = true
      session.unverified = true
      if (session.notice === null) {
        session.notice = 'outcome-unknown'
      }
      session.recoverOnBaseline = session.grant !== null
      session.recoveryClaimIssued = false
      if (session.inflight.timer !== null) {
        clearTimeout(session.inflight.timer)
        session.inflight.timer = null
      }
      if (identity !== null && streamId !== null) {
        this.events.sendTerminal({
          version: 1,
          requestId: createUuid(),
          environmentId: session.environmentId,
          viewerId: this.viewerId,
          type: 'DETACH',
          payload: { identity, streamId },
        })
      }
    } else {
      if (session.grant !== null && identity !== null && streamId !== null) {
        this.events.sendTerminal({
          version: 1,
          requestId: createUuid(),
          environmentId: session.environmentId,
          viewerId: this.viewerId,
          type: 'RELEASE',
          payload: { identity, streamId, grant: session.grant },
        })
      }
      if (identity !== null && streamId !== null) {
        this.events.sendTerminal({
          version: 1,
          requestId: createUuid(),
          environmentId: session.environmentId,
          viewerId: this.viewerId,
          type: 'DETACH',
          payload: { identity, streamId },
        })
      }
      session.grant = null
      session.recoverOnBaseline = false
    }
    this.invalidateStream(session)
    if (clearPending && session.pendingRequest !== null) {
      if (session.pendingRequest.timer !== null) {
        clearTimeout(session.pendingRequest.timer)
      }
      session.pendingRequest = null
    }
  }

  /**
   * 使当前 stream/基线失效，保留 identity/grant/inflight 证据与末屏 view。
   * 只作废 stream 与基线；view 仍可作为离线只读快照展示，且不会用于 ACK/输入。
   */
  private invalidateStream(session: SessionRuntime): void {
    this.clearKeepalive(session)
    session.streamId = null
    session.appliedVersion = null
    if (session.inflight !== null) {
      session.frozen = true
    }
  }

  /** 按 identity/openAttempted 发送 ATTACH 或首次 OPEN。 */
  private sendAttach(session: SessionRuntime): void {
    if (!this.canAttemptVisible()) {
      return
    }
    if (!isCanonicalUuid(session.environmentId)) {
      session.notice = 'unavailable'
      return
    }
    let command: TerminalCommand
    if (session.identity !== null) {
      command = {
        version: 1,
        requestId: createUuid(),
        environmentId: session.environmentId,
        viewerId: this.viewerId,
        type: 'ATTACH',
        payload: { identity: session.identity },
      }
    } else if (!session.openAttempted) {
      command = {
        version: 1,
        requestId: createUuid(),
        environmentId: session.environmentId,
        viewerId: this.viewerId,
        type: 'OPEN',
        payload: { expectedExited: null },
      }
    } else {
      // 无 identity 且已尝试过 OPEN：不自动重复。
      return
    }
    // 新流立即作废旧 stream/旧待绘；保留 identity/grant/inflight。
    this.invalidateStream(session)
    if (command.type === 'OPEN') {
      session.openAttempted = true
    }
    this.setPendingRequest(session, command.type, command.requestId)
    const sent = this.events.sendTerminal(command)
    if (!sent) {
      session.notice = 'unavailable'
    }
    this.notify()
  }

  private dispatchControl(
    session: SessionRuntime,
    kind: ControlRequestKind,
    command: TerminalCommand,
  ): void {
    if (!this.canAttemptVisible()) {
      return
    }
    if (kind === 'OPEN') {
      session.openAttempted = true
    }
    this.setPendingRequest(session, kind, command.requestId)
    const sent = this.events.sendTerminal(command)
    if (!sent) {
      session.notice = 'unavailable'
    }
    this.notify()
  }

  private setPendingRequest(
    session: SessionRuntime,
    kind: ControlRequestKind,
    requestId: string,
  ): void {
    if (session.pendingRequest !== null && session.pendingRequest.timer !== null) {
      clearTimeout(session.pendingRequest.timer)
    }
    session.pendingRequest = {
      kind,
      requestId,
      timer: this.armDeadline(() => this.handleControlDeadline(session, requestId)),
    }
  }

  private rearmPendingDeadline(session: SessionRuntime): void {
    const pending = session.pendingRequest
    if (pending === null || pending.timer !== null) {
      return
    }
    const requestId = pending.requestId
    pending.timer = this.armDeadline(() => this.handleControlDeadline(session, requestId))
  }

  private armDeadline(callback: () => void): ReturnType<typeof setTimeout> {
    return setTimeout(callback, TERMINAL_REQUEST_DEADLINE_MS)
  }

  private handleControlDeadline(session: SessionRuntime, requestId: string): void {
    if (session.pendingRequest === null || session.pendingRequest.requestId !== requestId) {
      return
    }
    const kind = session.pendingRequest.kind
    session.pendingRequest = null
    if (kind === 'CLOSE') {
      // 未确认的终止：结果未知，绝不当作正常完成。
      session.frozen = true
      session.notice = 'outcome-unknown'
      this.notify()
      return
    }
    session.notice = session.identity === null ? 'unavailable' : 'control-rejected'
    this.requestRecoveryAttach(session)
    this.notify()
  }

  private requestRecoveryAttach(session: SessionRuntime): void {
    if (
      session.recoveryAttachIssued ||
      session.identity === null ||
      !this.canAttemptVisible()
    ) {
      return
    }
    session.recoveryAttachIssued = true
    this.sendAttach(session)
  }

  private maybeRecoverOnBaseline(session: SessionRuntime): void {
    if (!session.recoverOnBaseline) {
      return
    }
    if (session.recoveryClaimIssued) {
      return
    }
    const recovery = this.buildRecovery(session)
    if (recovery === null) {
      // 无可用旧授权证据：不得用新授权假确认；保留 unknown。
      session.recoverOnBaseline = false
      if (session.inflight !== null || session.unverified) {
        session.frozen = true
        session.unverified = true
        session.notice = 'outcome-unknown'
      }
      return
    }
    session.recoveryClaimIssued = true
    this.dispatchControl(session, 'CLAIM', {
      version: 1,
      requestId: createUuid(),
      environmentId: session.environmentId,
      viewerId: this.viewerId,
      type: 'CLAIM',
      payload: {
        identity: session.identity!,
        streamId: session.streamId!,
        recovery,
      },
    })
  }

  // ---------------------------------------------------------------- 发送操作

  /** 顺序发出一条在途操作：优先 resize，其次输入分块；每 writer 单一在途。 */
  private pump(session: SessionRuntime): void {
    if (session !== this.activeSession()) {
      return
    }
    if (!this.canAttemptVisible() || !this.controlUsable(session)) {
      return
    }
    if (session.inflight !== null) {
      return
    }
    if (session.queuedResize !== null) {
      this.pumpResize(session)
      return
    }
    if (session.pendingInput.length === 0) {
      return
    }
    this.pumpInput(session)
  }

  private pumpResize(session: SessionRuntime): void {
    const resize = session.queuedResize
    const identity = session.identity
    const streamId = session.streamId
    const grant = session.grant
    if (resize === null || identity === null || streamId === null || grant === null) {
      return
    }
    const { cols, rows } = resize
    const seq = session.nextSeq + 1
    const digest = resizeOperationDigest(cols, rows)
    const requestId = createUuid()
    session.queuedResize = null
    session.nextSeq = seq
    session.inflight = {
      kind: 'RESIZE',
      requestId,
      seq,
      digest,
      grant,
      inputBytes: 0,
      timer: this.armDeadline(() => this.handleOperationDeadline(session, seq)),
    }
    const sent = this.events.sendTerminal({
      version: 1,
      requestId,
      environmentId: session.environmentId,
      viewerId: this.viewerId,
      type: 'RESIZE',
      payload: { identity, streamId, grant, seq, cols, rows },
    })
    if (!sent) {
      session.frozen = true
      session.notice = 'outcome-unknown'
    }
    this.notify()
  }

  private pumpInput(session: SessionRuntime): void {
    const identity = session.identity
    const streamId = session.streamId
    const grant = session.grant
    if (identity === null || streamId === null || grant === null) {
      return
    }
    const chunk = session.pendingInput.subarray(0, MAX_INPUT_PACKET_BYTES)
    const seq = session.nextSeq + 1
    const digest = inputOperationDigest(chunk, session.inputModeRevision)
    const requestId = createUuid()
    session.pendingInput = session.pendingInput.subarray(chunk.length)
    session.nextSeq = seq
    session.inflight = {
      kind: 'INPUT',
      requestId,
      seq,
      digest,
      grant,
      inputBytes: chunk.length,
      timer: this.armDeadline(() => this.handleOperationDeadline(session, seq)),
    }
    const sent = this.events.sendTerminal({
      version: 1,
      requestId,
      environmentId: session.environmentId,
      viewerId: this.viewerId,
      type: 'INPUT',
      payload: {
        identity,
        streamId,
        grant,
        seq,
        inputModeRevision: session.inputModeRevision,
        bytes: chunk,
      },
    })
    if (!sent) {
      session.frozen = true
      session.notice = 'outcome-unknown'
    }
    this.notify()
  }

  private handleOperationDeadline(session: SessionRuntime, seq: number): void {
    if (session.inflight === null || session.inflight.seq !== seq) {
      return
    }
    session.frozen = true
    session.notice = 'outcome-unknown'
    this.requestRecoveryAttach(session)
    this.notify()
  }

  private discardUnsent(session: SessionRuntime): void {
    session.pendingInput = new Uint8Array(0)
    session.queuedResize = null
  }

  private clearInflight(session: SessionRuntime): void {
    if (session.inflight !== null) {
      if (session.inflight.timer !== null) {
        clearTimeout(session.inflight.timer)
      }
      session.inflight = null
    }
  }

  private startKeepalive(session: SessionRuntime): void {
    this.clearKeepalive(session)
    session.keepaliveTimer = setInterval(() => {
      if (
        !this.started ||
        this.connectionStatus !== 'open' ||
        session.identity === null ||
        session.streamId === null
      ) {
        return
      }
      this.events.sendTerminal({
        version: 1,
        requestId: createUuid(),
        environmentId: session.environmentId,
        viewerId: this.viewerId,
        type: 'KEEPALIVE',
        payload: {
          identity: session.identity,
          streamId: session.streamId,
          grant: session.grant,
        },
      })
    }, TERMINAL_KEEPALIVE_INTERVAL_MS)
  }

  private clearKeepalive(session: SessionRuntime): void {
    if (session.keepaliveTimer !== null) {
      clearInterval(session.keepaliveTimer)
      session.keepaliveTimer = null
    }
  }

  // ---------------------------------------------------------------- 事件处理

  private handleStatusChange(status: ApplicationEventConnectionStatus): void {
    const previous = this.connectionStatus
    this.connectionStatus = status
    if (previous === status) {
      return
    }
    if (status === 'open') {
      // 新物理连接：重新允许一次自动恢复尝试。
      const session = this.activeSession()
      if (session !== null) {
        session.recoveryAttachIssued = false
        session.recoveryClaimIssued = false
        if (this.visible && session.streamId === null) {
          this.sendAttach(session)
        }
      }
    } else if (previous === 'open') {
      this.handleDisconnect()
    }
    this.notify()
  }

  private handleDisconnect(): void {
    const session = this.activeSession()
    if (session === null) {
      return
    }
    this.discardUnsent(session)
    this.clearKeepalive(session)
    if (session.grant !== null) {
      // 保留唯一旧 grant proof 供新连接核对。
      session.recoverOnBaseline = true
      session.recoveryClaimIssued = false
    }
    if (session.inflight !== null) {
      session.frozen = true
    }
    if (session.pendingRequest !== null && session.pendingRequest.kind === 'CLOSE') {
      // 断线时终止结果未知，绝不当作正常完成。
      session.frozen = true
      session.notice = 'outcome-unknown'
    }
    if (session.identity === null) {
      // 无 identity 的不确定 OPEN：只提示，等用户明确操作。
      session.notice = 'unavailable'
    }
    this.invalidateStream(session)
    if (session.pendingRequest !== null) {
      if (session.pendingRequest.timer !== null) {
        clearTimeout(session.pendingRequest.timer)
      }
      session.pendingRequest = null
    }
  }

  private fence(event: TerminalEvent, session: SessionRuntime): boolean {
    return event.environmentId === session.environmentId && event.viewerId === this.viewerId
  }

  private handleEvent(event: TerminalEvent): void {
    switch (event.type) {
      case 'ATTACHED':
        this.handleAttached(event)
        break
      case 'WRITER_CHANGED':
        this.handleWriterChanged(event)
        break
      case 'OP_ACK':
        this.handleOpAck(event)
        break
      case 'VIEW_UPDATE':
        this.handleViewUpdate(event)
        break
      case 'EXITED':
        this.handleExited(event)
        break
      case 'ERROR':
        this.handleError(event)
        break
    }
  }

  private handleAttached(event: TerminalAttachedEvent): void {
    const session = this.activeSession()
    if (session === null || !this.fence(event, session)) {
      return
    }
    const pending = session.pendingRequest
    if (pending === null || event.requestId !== pending.requestId) {
      // 迟到或错请求的 ATTACHED。
      return
    }
    const identity = event.identity
    if (
      pending.kind === 'ATTACH' &&
      session.identity !== null &&
      !sameIdentity(identity, session.identity)
    ) {
      // ATTACH 只接受 expected identity。
      return
    }
    if (pending.timer !== null) {
      clearTimeout(pending.timer)
    }
    session.pendingRequest = null
    if (session.identity !== null && !sameIdentity(identity, session.identity)) {
      // OPEN 带来新 terminal：旧操作/旧控制/旧未核对提示绝不重放。
      this.clearInflight(session)
      session.grant = null
      session.nextSeq = 0
      session.recoverOnBaseline = false
      session.frozen = false
      session.unverified = false
      session.notice = 'failed'
    }
    session.identity = identity
    session.executable = event.payload.executable
    session.status = event.payload.status
    session.exitCode = event.payload.exitCode
    session.inputModeRevision = event.payload.inputModeRevision
    session.writer = event.payload.writer
    session.mirror.beginStream(identity.terminalId, event.payload.streamId)
    session.streamId = event.payload.streamId
    session.view = null
    session.appliedVersion = null
    session.recoveryAttachIssued = false
    if (session.inflight !== null) {
      session.frozen = true
      session.recoverOnBaseline = session.grant !== null
    } else {
      session.frozen = false
    }
    if (this.started && this.visible) {
      this.startKeepalive(session)
    }
    this.notify()
  }

  private handleWriterChanged(event: TerminalWriterChangedEvent): void {
    const session = this.activeSession()
    if (session === null || !this.fence(event, session) || session.streamId === null) {
      return
    }
    if (session.identity === null || !sameIdentity(event.identity, session.identity)) {
      return
    }
    session.writer = event.payload.writer
    const result = event.payload.result
    if (result === null) {
      const epoch = event.payload.writer.writerEpoch
      if (session.grant !== null && epoch !== session.grant.epoch) {
        // 当前授权已被新 epoch 取代。
        session.grant = null
        session.recoverOnBaseline = false
        if (session.inflight !== null) {
          // 旧操作结果不可核对：明确 unknown、丢旧未发字节；绝不用新授权谎报未写。
          session.frozen = true
          session.unverified = true
          session.notice = 'outcome-unknown'
          session.nextSeq = 0
          this.clearInflight(session)
          this.discardUnsent(session)
        } else {
          session.frozen = false
          session.notice = 'control-rejected'
        }
      }
      if (event.payload.writer.frozen) {
        session.frozen = true
      }
      this.notify()
      return
    }
    const pending = session.pendingRequest
    if (pending === null || event.requestId !== pending.requestId) {
      return
    }
    if (pending.timer !== null) {
      clearTimeout(pending.timer)
    }
    session.pendingRequest = null
    if (result.status === 'GRANTED' && result.grant !== null) {
      const newEpoch = session.grant === null || session.grant.epoch !== result.grant.epoch
      if (newEpoch) {
        // 全新 epoch：seq 从 1 起，旧未发送字节清除，不自动重放。
        session.nextSeq = 0
        this.discardUnsent(session)
      }
      session.grant = result.grant
      session.frozen = false
      session.recoverOnBaseline = false
      session.recoveryClaimIssued = true
      if (result.recovered !== null) {
        this.applyRecovered(session, result.recovered)
      } else if (session.unverified) {
        // 旧操作结果仍未知：保留提示，不以新授权+旧 seq 假确认。
        session.notice = 'outcome-unknown'
      } else {
        session.notice = null
      }
      this.pump(session)
    } else if (result.status === 'RELEASED') {
      session.grant = null
      session.frozen = false
      session.recoverOnBaseline = false
      session.recoveryClaimIssued = false
      session.unverified = false
      this.discardUnsent(session)
      session.notice = null
    } else if (result.status === 'BUSY') {
      session.notice = 'control-rejected'
    } else {
      session.notice = 'control-rejected'
      if (result.reason === 'RECOVERY_UNVERIFIABLE' || result.reason === 'RECOVERY_MISMATCH') {
        session.frozen = true
        session.notice = 'outcome-unknown'
      }
    }
    this.notify()
  }

  private applyRecovered(
    session: SessionRuntime,
    outcome: 'WRITTEN' | 'NOT_WRITTEN' | 'OUTCOME_UNKNOWN',
  ): void {
    this.clearInflight(session)
    if (outcome === 'WRITTEN') {
      session.unverified = false
      session.notice = null
    } else if (outcome === 'NOT_WRITTEN') {
      session.unverified = false
      session.notice = 'not-written'
    } else {
      session.frozen = true
      session.unverified = true
      session.notice = 'outcome-unknown'
    }
  }

  private handleOpAck(event: TerminalOpAckEvent): void {
    const session = this.activeSession()
    if (session === null || !this.fence(event, session)) {
      return
    }
    if (session.identity === null || !sameIdentity(event.identity, session.identity)) {
      return
    }
    const inflight = session.inflight
    if (inflight === null || session.grant === null) {
      return
    }
    if (event.payload.writerEpoch !== session.grant.epoch) {
      return
    }
    const result = event.payload.result
    if (result.seq !== inflight.seq) {
      return
    }
    const invalidReject = result.kind === 'REJECTED' && result.reason === 'INVALID'
    if (!invalidReject && result.digest !== inflight.digest) {
      return
    }
    if (invalidReject && result.digest !== null && result.digest !== inflight.digest) {
      return
    }
    if (result.kind === 'PENDING') {
      return
    }
    this.clearInflight(session)
    if (result.kind === 'CONFIRMED') {
      if (event.payload.code === 'STALE_MODE') {
        session.notice = 'stale-mode'
        this.discardUnsent(session)
      } else if (result.outcome === 'WRITTEN') {
        // 曾有一个旧操作结果未知：保留提示，直到显式重新同步。
        session.notice = session.unverified ? 'outcome-unknown' : null
      } else if (result.outcome === 'NOT_WRITTEN') {
        session.notice = session.unverified
          ? 'outcome-unknown'
          : event.payload.code === 'RUNTIME_FAILED'
            ? 'failed'
            : 'not-written'
      } else {
        session.frozen = true
        session.unverified = true
        session.notice = 'outcome-unknown'
      }
      this.notify()
      if (!session.frozen) {
        this.pump(session)
      }
      return
    }
    this.handleRejected(session, result.reason, event.payload.code)
    this.notify()
    if (!session.frozen) {
      this.pump(session)
    }
  }

  private handleRejected(
    session: SessionRuntime,
    reason: string | null,
    code: string | null,
  ): void {
    if (code === 'STALE_MODE') {
      session.notice = 'stale-mode'
      this.discardUnsent(session)
      return
    }
    if (reason === 'NOT_OWNER' || reason === 'LEASE_EXPIRED') {
      session.grant = null
      session.frozen = false
      session.recoverOnBaseline = false
      session.notice = 'control-rejected'
      this.discardUnsent(session)
      return
    }
    if (reason === 'FROZEN') {
      session.frozen = true
      session.notice = 'outcome-unknown'
      return
    }
    if (reason === 'SEQ_GAP' || reason === 'SEQ_CONFLICT' || reason === 'UNVERIFIABLE') {
      session.frozen = true
      session.notice = 'failed'
      this.discardUnsent(session)
      return
    }
    session.notice = 'failed'
    this.discardUnsent(session)
  }

  private handleViewUpdate(event: TerminalViewUpdateEvent): void {
    const session = this.activeSession()
    if (session === null || !this.fence(event, session)) {
      return
    }
    if (session.identity === null || !sameIdentity(event.identity, session.identity)) {
      return
    }
    const update = event.payload.update
    if (update.streamId !== session.streamId) {
      return
    }
    let state: TerminalMirrorState
    try {
      state = session.mirror.apply(update).state
    } catch {
      session.notice = 'failed'
      session.frozen = true
      this.requestRecoveryAttach(session)
      this.notify()
      return
    }
    if (update.inputModeRevision > session.inputModeRevision) {
      if (session.pendingInput.length > 0 || session.queuedResize !== null) {
        this.discardUnsent(session)
        session.notice = 'stale-mode'
      }
    }
    session.inputModeRevision = update.inputModeRevision
    session.view = state
    this.notify()
  }

  private handleExited(event: TerminalExitedEvent): void {
    const session = this.activeSession()
    if (session === null || !this.fence(event, session) || session.streamId === null) {
      return
    }
    if (session.identity === null || !sameIdentity(event.identity, session.identity)) {
      return
    }
    session.status = event.payload.status
    session.exitCode = event.payload.exitCode
    this.clearKeepalive(session)
    if (session.pendingRequest !== null) {
      if (session.pendingRequest.timer !== null) {
        clearTimeout(session.pendingRequest.timer)
      }
      session.pendingRequest = null
    }
    session.grant = null
    session.frozen = false
    session.recoverOnBaseline = false
    session.recoveryClaimIssued = false
    session.unverified = false
    session.nextSeq = 0
    this.clearInflight(session)
    this.discardUnsent(session)
    session.notice = event.payload.status === 'FAILED' ? 'failed' : null
    this.notify()
  }

  private handleError(event: TerminalErrorEvent): void {
    const session = this.activeSession()
    if (session === null || !this.fence(event, session)) {
      return
    }
    const pending = session.pendingRequest
    const inflight = session.inflight
    if (event.requestId !== null) {
      const matchesPending = pending !== null && event.requestId === pending.requestId
      const matchesInflight = inflight !== null && event.requestId === inflight.requestId
      if (!matchesPending && !matchesInflight) {
        // 非当前请求关联的迟到错误：丢弃。
        return
      }
    } else if (event.identity !== null) {
      // 无请求关联的广播错误仍需 identity 围栏，避免串到其他实例。
      if (session.identity === null || !sameIdentity(event.identity, session.identity)) {
        return
      }
    }
    // 关联错误允许在 identity 不同（例如 DAEMON_MISMATCH 返回新 daemon identity）时展示，
    // 但绝不据此建立/改写 session.identity。
    const matchedClose = event.requestId !== null && pending !== null && pending.kind === 'CLOSE'
    if (event.requestId !== null && pending !== null && event.requestId === pending.requestId) {
      if (pending.timer !== null) {
        clearTimeout(pending.timer)
      }
      session.pendingRequest = null
    }
    const code = event.payload.code
    const disposition = event.payload.disposition
    if (event.requestId !== null && inflight !== null && event.requestId === inflight.requestId) {
      if (disposition === 'NOT_EXECUTED' && code !== 'OUTCOME_UNKNOWN') {
        // gateway pre-native：确定未执行，完成本地 pending，绝不谎报已写。
        this.resolveInflightNotExecuted(session)
        this.notify()
        if (!session.frozen) {
          this.pump(session)
        }
        return
      }
    }
    if (matchedClose) {
      if (disposition === 'NOT_EXECUTED' && code !== 'OUTCOME_UNKNOWN') {
        // 明确拒绝终止：解除本次冻结，提示控制被拒。
        session.frozen = false
        session.notice = 'control-rejected'
        this.notify()
        return
      }
    }
    if (disposition === 'OUTCOME_UNKNOWN' || code === 'OUTCOME_UNKNOWN') {
      session.frozen = true
      session.notice = 'outcome-unknown'
    } else if (code === 'STALE_MODE') {
      session.notice = 'stale-mode'
      this.discardUnsent(session)
      this.clearInflight(session)
    } else if (code === 'BACKPRESSURE') {
      session.notice = 'backpressure'
    } else if (code === 'ROUTE_UNAVAILABLE') {
      session.notice = 'unavailable'
    } else if (code === 'BUSY' || code === 'VIEW_NOT_APPLIED' || code === 'REQUEST_CONFLICT') {
      session.notice = 'control-rejected'
    } else if (
      code === 'TERMINAL_NOT_FOUND' ||
      code === 'DAEMON_MISMATCH' ||
      code === 'RUNTIME_FAILED'
    ) {
      // 不因错误消息建立新 identity；仅显示失败。
      session.notice = 'failed'
      session.grant = null
      session.frozen = false
      session.recoverOnBaseline = false
    } else {
      session.notice = 'failed'
    }
    this.notify()
  }

  /** 确定未执行的 INPUT/RESIZE：完成本地在途、回退 seq，绝不当作已写。 */
  private resolveInflightNotExecuted(session: SessionRuntime): void {
    const inflight = session.inflight
    if (inflight === null) {
      return
    }
    if (inflight.timer !== null) {
      clearTimeout(inflight.timer)
    }
    session.inflight = null
    session.frozen = false
    session.nextSeq = inflight.seq - 1
    session.notice = 'not-written'
  }

  // ---------------------------------------------------------------- 快照

  private buildSnapshot(): TerminalWorkspaceSnapshot {
    const sessions = new Map<string, TerminalSessionSnapshot>()
    for (const [environmentId, session] of this.sessions) {
      sessions.set(environmentId, {
        environmentId,
        identity: session.identity,
        streamId: session.streamId,
        executable: session.executable,
        status: session.status,
        exitCode: session.exitCode,
        view: session.view,
        viewApplied: session.view !== null && session.appliedVersion === session.view.version,
        writer: session.writer,
        hasControl: this.controlUsable(session),
        pending: session.inflight !== null || session.pendingRequest !== null,
        notice: session.notice,
      })
    }
    return Object.freeze({
      visible: this.visible,
      activeEnvironmentId: this.activeEnvironmentId,
      connectionStatus: this.connectionStatus,
      sessions,
    })
  }

  private notify(): void {
    this.snapshot = null
    for (const listener of this.listeners) {
      listener()
    }
  }
}
