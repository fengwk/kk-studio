import { randomUUID } from 'node:crypto'
import { create } from '@bufbuild/protobuf'

import type { DaemonConfig, IdeService } from './contracts.js'
import {
  UpdatePhase,
  UpdateResultSchema,
  UpdateSchema,
  type Envelope,
  type RuntimeSnapshot,
  type Update,
  type UpdateResult,
} from './generated/control_pb.js'
import type { OutboundControl } from './runtime-outbound.js'
import { safeMessage } from './runtime-encoding.js'
import {
  RuntimeStatusTracker,
  type RuntimeStatus,
  type RuntimeStatusUpdate,
  type UpdateByteProgress,
} from './runtime-status.js'
import type {
  ReleasePermit,
  UpdateOutcomeLike,
  UpdatePort,
  UpdateProgressReporter,
} from './runtime-ports.js'

/** 内存中保留的受管更新回执条数上界：足够覆盖少量重发与重连重放，不无界累积。 */
const MAX_TRACKED_UPDATE_OPERATIONS = 8

/**
 * 一个已准入的更新：从 ACCEPTED 起持有到明确失败或进程退出。
 *
 * <p>PREPARED 只表示独立更新器已接受交接，因此 controller 可以结束，但准入门闩与目标快照必须继续保留。
 */
export interface ActiveUpdate {
  operationId: string
  commandKey: string
  targetVersion: string
  controller: AbortController | null
  receipt: UpdateReceipt
  prepared: boolean
  stopWatching?: () => void
}

/**
 * 一条已完成 operation 的冻结事实：回执结果，加上「确实已被独立 OS 更新器接受」时才存在的许可释放步骤。
 *
 * <p>重放与重连重发只需要这一份事实：不再准备、下载或启动，但仍幂等重试许可释放。
 */
export interface UpdateReceipt {
  commandKey: string
  result: UpdateResult
  releasePermit?: ReleasePermit
}

type UpdateDecision =
  | { kind: 'replay-active'; active: ActiveUpdate }
  | { kind: 'status' }
  | { kind: 'replay-cached'; receipt: UpdateReceipt }
  | { kind: 'reject'; receipt: UpdateReceipt }
  | { kind: 'admit'; active: ActiveUpdate; command: Update }

/** 幂等身份只比较命令参数；operationId 是请求关联键，不是参数的一部分。 */
export function updateCommandKey(command: Update): string {
  return JSON.stringify([command.targetVersion])
}

export function acceptedReceipt(command: Update): UpdateReceipt {
  return {
    commandKey: updateCommandKey(command),
    result: create(UpdateResultSchema, {
      operationId: command.operationId,
      phase: UpdatePhase.UPDATE_ACCEPTED,
    }),
  }
}

export function failedReceipt(command: Update, message: string): UpdateReceipt {
  return {
    commandKey: updateCommandKey(command),
    result: create(UpdateResultSchema, {
      operationId: command.operationId,
      phase: UpdatePhase.UPDATE_FAILED,
      message,
    }),
  }
}

export function preparedReceipt(active: ActiveUpdate, command: Update, prepared: UpdateOutcomeLike): UpdateReceipt {
  return {
    commandKey: active.commandKey,
    result: create(UpdateResultSchema, {
      operationId: command.operationId || active.operationId,
      phase: UpdatePhase.UPDATE_PREPARED,
      message: prepared.message ?? undefined,
    }),
    releasePermit: prepared.releasePermit,
  }
}

export function executionFailureReceipt(
  active: ActiveUpdate,
  command: Update,
  failure: Error | null,
): UpdateReceipt {
  return {
    commandKey: active.commandKey,
    result: create(UpdateResultSchema, {
      operationId: command.operationId,
      phase: UpdatePhase.UPDATE_FAILED,
      message: safeMessage(failure ?? new Error('update failed')),
    }),
  }
}

/**
 * 决定一条 UPDATE 在当前门闩下做什么。
 *
 * <p>同 operation 参数漂移直接抛协议错误；同目标的新请求与已经是目标版本都只刷新状态，不重复执行。
 */
export function decideUpdate(input: {
  command: Update
  active: ActiveUpdate | null
  cached: UpdateReceipt | undefined
  currentVersion: string
  busyReason: string | null
  configured: boolean
}): UpdateDecision {
  const command = input.command
  const commandKey = updateCommandKey(command)
  const active = input.active
  if (active !== null) {
    if (active.operationId === command.operationId) {
      if (active.commandKey !== commandKey) {
        throw new Error('the same update operationId must not change its arguments')
      }
      if (active.prepared) {
        return { kind: 'replay-active', active }
      }
      return { kind: 'status' }
    }
    if (active.targetVersion === command.targetVersion) {
      return { kind: 'status' }
    }
    return { kind: 'reject', receipt: failedReceipt(command, `update is busy for target ${active.targetVersion}`) }
  }
  if (input.cached !== undefined) {
    if (input.cached.commandKey !== commandKey) {
      throw new Error('the same update operationId must not change its arguments')
    }
    return { kind: 'replay-cached', receipt: input.cached }
  }
  if (input.currentVersion === command.targetVersion) {
    return { kind: 'status' }
  }
  if (input.busyReason !== null) {
    return { kind: 'reject', receipt: failedReceipt(command, `update is busy: ${input.busyReason}`) }
  }
  if (!input.configured) {
    return { kind: 'reject', receipt: failedReceipt(command, 'managed update is not configured') }
  }
  const receipt = acceptedReceipt(command)
  return {
    kind: 'admit',
    command,
    active: {
      operationId: command.operationId ?? '',
      commandKey,
      targetVersion: command.targetVersion ?? '',
      controller: new AbortController(),
      receipt,
      prepared: false,
    },
  }
}

export function rememberUpdateResult(
  results: Map<string, UpdateReceipt>,
  operationId: string,
  receipt: UpdateReceipt,
): void {
  results.delete(operationId)
  results.set(operationId, receipt)
  // 超出上界时按插入顺序淘汰最旧回执；Map 迭代删除当前项不影响后续键。
  for (const oldest of results.keys()) {
    if (results.size <= MAX_TRACKED_UPDATE_OPERATIONS) {
      break
    }
    results.delete(oldest)
  }
}

/** 重连后优先重发仍在门闩中的回执，否则重发最近一条已完成回执。 */
export function receiptToResend(active: ActiveUpdate | null, results: Map<string, UpdateReceipt>): UpdateReceipt | null {
  if (active !== null) {
    return active.receipt
  }
  let last: UpdateReceipt | undefined
  for (const receipt of results.values()) {
    last = receipt
  }
  return last ?? null
}

export interface UpdateExecution {
  failure: Error | null
  prepared: UpdateOutcomeLike | null
}

/**
 * 在已经建立的门闩上执行一次更新：先停 IDE，再交给更新器。
 */
export async function executeUpdateAttempt(
  active: ActiveUpdate,
  command: Update,
  update: UpdatePort,
  ide: { stopForUpdate(signal: AbortSignal): Promise<void> } | null,
  config: Parameters<UpdatePort['handleUpdate']>[1],
  reportProgress: UpdateProgressReporter,
): Promise<UpdateExecution> {
  try {
    const signal = active.controller?.signal ?? AbortSignal.abort('SHUTDOWN')
    if (ide !== null) {
      await ide.stopForUpdate(signal)
    }
    const plainCommand: Record<string, unknown> = {
      operationId: command.operationId,
      targetVersion: command.targetVersion,
    }
    const raw = await update.handleUpdate(
      plainCommand,
      config,
      signal,
      reportProgress,
    )
    if (raw.phase === 'FAILED') {
      return { failure: new Error(raw.message ?? 'managed update failed'), prepared: null }
    }
    return { failure: null, prepared: raw }
  } catch (error) {
    return { failure: error instanceof Error ? error : new Error(String(error)), prepared: null }
  }
}

export interface UpdateRuntimeOptions {
  update: UpdatePort | null
  ide: IdeService | null
  config: DaemonConfig
  daemonVersion: string
  outbound: OutboundControl
  /** 普通/恢复执行及其拥有的上传收尾尚未结束。 */
  hasProtectedExecutions(): boolean
  ideCommandCount(): number
  track(promise: Promise<void>): void
  collectDiagnostic(phase: string, error: unknown): void
}

/**
 * 受管更新会话：唯一准入、回执缓存、字节进度与运行状态发布。
 *
 * <p>更新与普通调用互斥：准入后新 INVOKE 与 OPEN_VIEW/IDE_LEASE 失败关闭，普通调用必须等受保护执行全部结束。
 * PREPARED 表示独立更新器已接受交接，门闩继续持有到进程退出或明确失败；回执按 operation 幂等重放。
 */
export class UpdateRuntime {
  private readonly status: RuntimeStatusTracker
  private readonly results = new Map<string, UpdateReceipt>()
  private active: ActiveUpdate | null = null

  constructor(private readonly options: UpdateRuntimeOptions) {
    this.status = new RuntimeStatusTracker(options.daemonVersion)
  }

  get configured(): boolean {
    return this.options.update !== null
  }

  get isAdmissionHeld(): boolean {
    return this.active !== null
  }

  /** 当前完整状态快照；拉取一次更新器进度后再冻结 revision。 */
  snapshot(): RuntimeSnapshot {
    this.refreshProgress()
    return this.status.snapshot()
  }

  plainSnapshot(): RuntimeStatus {
    this.refreshProgress()
    return this.status.plainSnapshot()
  }

  updateProgress(): RuntimeStatusUpdate | null {
    this.refreshProgress()
    return this.status.getUpdate()
  }

  handle(envelope: Envelope): void {
    if (!this.options.outbound.isReady || !envelope.environmentId) {
      throw new Error('UPDATE requires a READY bound connection')
    }
    const command = envelope.payload.value as Update
    const decision = decideUpdate({
      command,
      active: this.active,
      cached: this.results.get(command.operationId ?? ''),
      currentVersion: this.options.daemonVersion,
      busyReason: this.busyReason(),
      configured: this.options.update !== null,
    })
    if (decision.kind === 'status') {
      this.publishStatus()
      return
    }
    if (decision.kind === 'replay-active') {
      if (decision.active.prepared) {
        this.deliver(decision.active.receipt)
      } else {
        this.publishStatus()
      }
      return
    }
    if (decision.kind === 'replay-cached') {
      this.deliver(decision.receipt)
      this.publishStatus()
      return
    }
    if (decision.kind === 'reject') {
      this.remember(command.operationId ?? '', decision.receipt)
      this.deliver(decision.receipt)
      return
    }
    this.active = decision.active
    this.status.beginUpdate(command.targetVersion ?? '')
    this.publishStatus()
    this.options.outbound.sendUpdateResult(decision.active.receipt.result)
    this.options.track(this.execute(decision.active, decision.command))
  }

  /** 426 触发的受管更新：没有入站 UPDATE 信封，但走同一准入与回执事实。 */
  beginUpgrade(targetVersion: string, onUnprepared: () => void): void {
    const operationId = randomUUID()
    const command = create(UpdateSchema, { operationId, targetVersion })
    const receipt = acceptedReceipt(command)
    const active: ActiveUpdate = {
      operationId,
      commandKey: receipt.commandKey,
      targetVersion,
      controller: new AbortController(),
      receipt,
      prepared: false,
    }
    this.active = active
    this.status.beginUpdate(targetVersion)
    this.options.track(
      (async () => {
        await this.execute(active, command)
        if (!active.prepared) {
          onUnprepared()
        }
      })(),
    )
  }

  /** 重连后重发仍在门闩中或最近一条已完成回执。 */
  resendResults(): void {
    const receipt = receiptToResend(this.active, this.results)
    if (receipt !== null) {
      this.deliver(receipt)
    }
  }

  /** 停机：中止更新器并停止观察；回执保留为已发生事实。 */
  abortForShutdown(): void {
    this.active?.controller?.abort('SHUTDOWN')
    if (this.active !== null) {
      this.active.stopWatching?.()
      this.active.controller = null
    }
  }

  private async execute(active: ActiveUpdate, command: Update): Promise<void> {
    const update = this.options.update
    const attempt =
      update === null
        ? { failure: new Error('managed update is not configured'), prepared: null }
        : await executeUpdateAttempt(
            active,
            command,
            update,
            this.options.ide,
            this.options.config,
            (progress) => this.reportProgress(active, progress),
          )
    active.controller = null
    if (attempt.failure !== null || attempt.prepared === null) {
      if (this.active === active) {
        this.active = null
      }
      this.status.clearUpdate()
      this.publishStatus()
      const receipt = executionFailureReceipt(active, command, attempt.failure)
      this.remember(command.operationId ?? '', receipt)
      this.deliver(receipt)
      return
    }
    this.refreshProgress()
    const receipt = preparedReceipt(active, command, attempt.prepared)
    active.receipt = receipt
    active.prepared = true
    this.status.beginUpdate(command.targetVersion ?? '')
    this.publishStatus()
    this.remember(command.operationId ?? '', receipt)
    const stopWatching = attempt.prepared.watchFailure?.((message) => {
      if (this.active !== active) {
        return
      }
      active.stopWatching?.()
      this.active = null
      this.status.clearUpdate()
      this.publishStatus()
      const failed = executionFailureReceipt(active, command, new Error(message))
      this.remember(command.operationId ?? '', failed)
      this.deliver(failed)
    })
    if (stopWatching !== undefined) {
      active.stopWatching = stopWatching
    }
    if (this.active !== active) {
      stopWatching?.()
      return
    }
    this.deliver(receipt)
  }

  private reportProgress(active: ActiveUpdate, progress: UpdateByteProgress): void {
    if (this.active !== active) {
      return
    }
    this.status.reportProgress(progress)
  }

  /** 更新器可推送或拉取进度；拉取失败只留下诊断，不改变更新事实。 */
  private refreshProgress(): void {
    const active = this.active
    const getProgress = this.options.update?.getProgress
    if (active === null || getProgress === undefined) {
      return
    }
    try {
      const progress = getProgress.call(this.options.update)
      if (progress !== null) {
        this.reportProgress(active, progress)
      }
    } catch (error) {
      this.options.collectDiagnostic('update progress', error)
    }
  }

  private publishStatus(): void {
    if (!this.options.outbound.canSend) {
      return
    }
    this.options.outbound.sendRuntimeStatus(this.snapshot())
  }

  private busyReason(): string | null {
    if (this.options.hasProtectedExecutions()) {
      return 'protected execution is still running'
    }
    if (this.options.ideCommandCount() > 0) {
      return 'IDE command is in progress'
    }
    if (this.options.ide !== null && !this.options.ide.canStartUpdate()) {
      return 'IDE views, cleanup or data streams are active'
    }
    return null
  }

  private remember(operationId: string, receipt: UpdateReceipt): void {
    rememberUpdateResult(this.results, operationId, receipt)
  }

  private deliver(receipt: UpdateReceipt): void {
    this.options.outbound.sendUpdateResult(receipt.result)
    const releasePermit = receipt.releasePermit
    if (releasePermit === undefined) {
      return
    }
    this.options.track(
      releasePermit().catch((error: unknown) => {
        this.options.collectDiagnostic('update permit release', error)
      }),
    )
  }
}
