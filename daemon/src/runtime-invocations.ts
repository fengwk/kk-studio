import { create } from '@bufbuild/protobuf'

import type {
  CapabilityCall,
  CapabilityContext,
  CapabilityExecutor,
  CapabilityResult,
  DaemonConfig,
  ProcessSupervisor,
  ResourceUploader,
} from './contracts.js'
import { isResourceUploadError, type ResourceUploadError } from './contracts.js'
import {
  CancelledSchema,
  MessageType,
  type Envelope,
  type Invoke,
} from './generated/control_pb.js'
import {
  isTerminalState,
  type InvocationJournal,
  type JournalEntry,
  type TerminalMessage,
  type TerminalPayload,
} from './journal.js'
import { MAX_ARGUMENTS_JSON_UTF8_BYTES } from './protocol/control/codec.js'
import { parseStrictJson, type StrictLimits } from './protocol.js'
import type { ActiveConnection } from './runtime-connection.js'
import {
  encodeCompletedProto,
  encodeProgressProto,
  failedPayload,
  requireInvocationId,
  safeMessage,
} from './runtime-encoding.js'
import type { OutboundControl } from './runtime-outbound.js'
import { boundaryUpload, describeUploadFailure } from './runtime-upload-boundary.js'

type InvocationTerminationCause = 'TIMED_OUT' | 'CANCELLED' | 'SHUTDOWN'

/** 协议层已用同一字节上限校验 argumentsJson；这里复用共享严格解析，不额外收紧结构维度。 */
const ARGUMENTS_JSON_LIMITS: StrictLimits = {
  maxBytes: MAX_ARGUMENTS_JSON_UTF8_BYTES,
  maxNestingDepth: Number.MAX_SAFE_INTEGER,
  maxNumberLength: Number.MAX_SAFE_INTEGER,
}

export interface InvocationRuntimeOptions {
  journal: InvocationJournal
  capabilities: Record<string, CapabilityExecutor>
  /** 当前已应用配置；INVOKE 接受时冻结本次调用使用的快照。 */
  config(): DaemonConfig
  processes: ProcessSupervisor
  outbound: OutboundControl
  maxConcurrentInvocations: number
  /** 资源上传端口；binary 内容与退出确认都依赖它。 */
  uploader(): ResourceUploader | null
  /** 停机开始后不再受理新 INVOKE。 */
  isStarted(): boolean
  isUpdateHeld(): boolean
  trackProtected(promise: Promise<void>): void
  release(phase: string, action: () => void): void
}

/**
 * Agent invocation 执行域：journal 去重/回放、执行器与超时、终态仲裁、进度和退出确认。
 *
 * <p>journal 是进程内执行事实源：连接只是消息管道，因此 REPLAY 只重放已有事实；RUNNING 条目重放 STARTED 后继续由本类
 * 持有执行，终态只在 journal 的 RUNNING→终态跃迁成功时发送一次。停机只中止执行体，终态仍由执行器 cleanup 后交付。
 */
export class InvocationRuntime {
  private readonly running = new Map<string, RunningInvocation>()

  constructor(private readonly options: InvocationRuntimeOptions) {}

  get journal(): InvocationJournal {
    return this.options.journal
  }

  get runningCount(): number {
    return this.running.size
  }

  handleInvoke(connection: ActiveConnection, envelope: Envelope): void {
    if (!this.options.isStarted()) {
      return
    }
    const invocationId = requireInvocationId(envelope)
    const invoke = envelope.payload.value as Invoke
    const start = this.options.journal.start(invocationId)
    if (!start.created) {
      this.replay(start.entry)
      return
    }
    try {
      if (this.options.isUpdateHeld()) {
        throw new Error('environment update admission is held')
      }
      const capabilityId = invoke.capabilityId!
      if (invoke.expectedFile !== undefined && capabilityId !== 'fs.edit') {
        throw new Error('expectedFile is only valid for fs.edit')
      }
      const executor = this.options.capabilities[capabilityId]
      if (executor === undefined) {
        throw new Error(`unknown Environment capability: ${capabilityId}`)
      }
      if (this.running.size >= this.options.maxConcurrentInvocations) {
        throw new Error('too many concurrent capability invocations')
      }
      const parsedArgs = parseStrictJson(invoke.argumentsJson, 'invoke.argumentsJson', ARGUMENTS_JSON_LIMITS)
      if (typeof parsedArgs !== 'object' || parsedArgs === null || Array.isArray(parsedArgs)) {
        throw new Error('arguments must be a JSON object')
      }
      const callArguments = parsedArgs as Record<string, unknown>

      const controller = new AbortController()
      const maxOutputBytes = Number(invoke.maxOutputBytes)
      const invocation = new RunningInvocation(invocationId, controller, maxOutputBytes, this.options.config())
      this.running.set(invocationId, invocation)
      this.options.outbound.sendStarted(connection, invocationId, false)
      const timeoutMillis = Number(invoke.timeoutMillis)
      this.scheduleTimeout(invocation, timeoutMillis)

      const expectedFile =
        invoke.expectedFile !== undefined
          ? { path: invoke.expectedFile.path!, sha256: invoke.expectedFile.sha256! }
          : undefined

      const call: CapabilityCall = {
        invocationId,
        capabilityId,
        arguments: callArguments,
        timeoutMillis,
        expectedFile,
      }
      this.options.trackProtected(this.execute(invocation, executor, call))
    } catch (error) {
      this.confirmRejectedBeforeExecution(invocationId)
      this.terminal(invocationId, MessageType.FAILED, failedPayload(safeMessage(error)), null)
    }
  }

  handleCancel(envelope: Envelope): void {
    const invocationId = requireInvocationId(envelope)
    const entry = this.options.journal.find(invocationId)
    if (entry === undefined) {
      return
    }
    const invocation = this.running.get(invocationId)
    if (invocation !== undefined) {
      // 本进程确实在途的执行允许在未 READY 时中止，由其自身 cleanup 完成收尾。
      invocation.controller.abort('CANCELLED')
      return
    }
    // 未 READY 的连接不允许重放终态事实；等 READY 后服务端再取回。
    if (!this.options.outbound.isReady) {
      return
    }
    if (isTerminalState(entry.state)) {
      this.replay(entry)
    }
  }

  /**
   * 同实例重连后的取回：只重放 journal 已有的事实，绝不终止执行。运行中的调用回放 STARTED 并继续执行，结果经当前连接交付；
   * 已终结的调用重放终态与独立退出确认；从未收到的调用保持静默。
   *
   * <p>未 READY 时只处理本进程确实在途的执行；未知调用与终态事实必须等 READY 后再取回，不能借握手阶段恢复未知工作。
   */
  handleRecover(envelope: Envelope): void {
    const invocationId = requireInvocationId(envelope)
    const entry = this.options.journal.find(invocationId)
    if (!this.options.outbound.isReady) {
      if (entry !== undefined && this.running.has(invocationId)) {
        this.replay(entry)
      }
      return
    }
    if (entry !== undefined) {
      this.replay(entry)
    } else {
      this.options.outbound.sendRecoverNotFound(invocationId)
    }
  }

  /** 资源上传控制面只在该调用仍 RUNNING 且未被终态抢占时放行。 */
  sendTransferControl(envelope: Envelope): boolean {
    if (!this.options.outbound.canSend) {
      return false
    }
    const invocationId = envelope.invocationId ?? ''
    const invocation = this.running.get(invocationId)
    if (invocation === undefined) {
      return false
    }
    return invocation.trySendControl(() => this.isRunning(invocationId) && this.options.outbound.sendBound(envelope))
  }

  /** 停机：中止并封存全部在途执行的终态；真实终态仍由各自 cleanup 交付。 */
  cancelForShutdown(): void {
    for (const invocation of this.running.values()) {
      invocation.claimTerminal()
      invocation.controller.abort('SHUTDOWN')
      invocation.clearDeadline()
      this.options.release('invocation journal complete', () => {
        this.options.journal.complete(invocation.invocationId, {
          messageType: MessageType.CANCELLED,
          payload: {
            case: 'cancelled',
            value: create(CancelledSchema, { reason: 'daemon shutdown' }),
          },
        })
      })
    }
    this.running.clear()
  }

  /** 执行器确定未启动时记录并发送退出确认；重复确认由 journal 吸收。 */
  private confirmRejectedBeforeExecution(invocationId: string): void {
    if (this.options.journal.confirmExit(invocationId)) {
      this.options.outbound.sendExecutionExited(invocationId)
    }
  }

  /** 上传失败的安全诊断：最接近失败的边界记录一次，不重复堆栈，不含 URL/headers/令牌。 */
  private reportUploadFailure(phase: string, error: ResourceUploadError): void {
    try {
      process.stderr.write(`daemon ${phase}: ${describeUploadFailure(error)}\n`)
    } catch {
      // 诊断输出失败不影响失败事实的交付。
    }
  }

  private async execute(
    invocation: RunningInvocation,
    executor: CapabilityExecutor,
    call: CapabilityCall,
  ): Promise<void> {
    try {
      const result = await executor(call, this.capabilityContext(invocation))
      invocation.markExecutorReturned()
      // 上传和终态仍是本次拥有的收尾，完成前不能发送退出确认。
      if (invocation.terminalClaimed) {
        invocation.finishOwnedCleanup()
        return
      }
      if (result.callId !== invocation.invocationId) {
        this.terminal(
          invocation.invocationId,
          MessageType.FAILED,
          failedPayload('capability result callId does not match invocationId'),
          null,
        )
        invocation.finishOwnedCleanup()
        return
      }
      const completed = await encodeCompletedProto(result, invocation.maxOutputBytes, (content) => {
        const resources = this.options.uploader()
        if (resources === null) {
          throw new Error('resource upload is not configured')
        }
        return boundaryUpload(resources, invocation.invocationId, content, invocation.controller.signal)
      })
      this.terminal(
        invocation.invocationId,
        MessageType.COMPLETED,
        { case: 'completed', value: completed },
        null,
      )
      invocation.finishOwnedCleanup()
    } catch (error) {
      const executorFailed = !invocation.executorReturned
      // 收尾失败事实只来自明确的资源上传边界：cleanupConfirmed=false 时退出仍未知；
      // 普通失败（含存储拒绝、控制面不可用）在完成实际释放后按已确认处理。
      const uploadFailure = isResourceUploadError(error) ? error : null
      const cleanupUnconfirmed = uploadFailure !== null && !uploadFailure.cleanupConfirmed
      if (uploadFailure !== null) {
        this.reportUploadFailure(
          cleanupUnconfirmed ? 'invocation upload cleanup unconfirmed' : 'invocation upload failed',
          uploadFailure,
        )
      }
      if (invocation.terminalClaimed) {
        if (executorFailed) {
          invocation.finishOwnedCleanup()
        }
        return
      }
      const reason = invocation.controller.signal.reason
      if (reason === 'CANCELLED' || reason === 'SHUTDOWN') {
        const cancelledReason = reason === 'SHUTDOWN' ? 'daemon shutdown' : 'cancelled'
        this.terminal(
          invocation.invocationId,
          MessageType.CANCELLED,
          {
            case: 'cancelled',
            value: create(CancelledSchema, { reason: cancelledReason }),
          },
          reason,
        )
        if (executorFailed) {
          invocation.finishOwnedCleanup()
        }
        return
      }
      if (reason === 'TIMED_OUT') {
        this.terminal(
          invocation.invocationId,
          MessageType.FAILED,
          failedPayload(`capability execution timed out after ${call.timeoutMillis}ms`),
          'TIMED_OUT',
        )
        if (executorFailed) {
          invocation.finishOwnedCleanup()
        }
        return
      }
      this.terminal(
        invocation.invocationId,
        MessageType.FAILED,
        failedPayload(safeMessage(error)),
        null,
      )
      // executor 抛错仍可能已有正向范围证据；收尾失败（cleanupConfirmed=false）则退出事实保持未知。
      if (executorFailed || !cleanupUnconfirmed) {
        invocation.finishOwnedCleanup()
      }
    }
  }

  private capabilityContext(invocation: RunningInvocation): CapabilityContext {
    const resources = this.options.uploader()
    return {
      config: invocation.config,
      maxOutputBytes: invocation.maxOutputBytes,
      signal: invocation.controller.signal,
      processes: this.options.processes,
      resources: {
        upload: (invocationId, content, signal) => {
          if (resources === null) {
            return Promise.reject(new Error('resource upload is not configured'))
          }
          return boundaryUpload(resources, invocationId, content, signal)
        },
      },
      progress: async (result: CapabilityResult) => {
        this.progress(invocation, result)
      },
      confirmExecutionExit: () => {
        // 只记录证据。发送必须等 executor 与本次拥有的收尾都结束，不能在 body 内提前 ACK。
        invocation.noteExitEvidence((current) => this.publishExecutionExit(current))
      },
    }
  }

  /**
   * executor 与本次拥有的收尾都结束后，才把已记录的正向证据写入 journal 并发送一次。
   *
   * <p>终态会把 invocation 移出 running，因此这里持有对象本身，不回查 map。
   * 尚未开始执行器的确定拒绝由调用方直接确认；已经开始但没有正向证据时这里不补发。
   * 结果终态、超时和取消本身都不是退出证据。
   */
  private publishExecutionExit(invocation: RunningInvocation): void {
    if (!invocation.executorFinished || !invocation.exitConfirmed || invocation.exitPublished) {
      return
    }
    invocation.exitPublished = true
    if (!this.options.journal.confirmExit(invocation.invocationId)) {
      return
    }
    this.options.outbound.sendExecutionExited(invocation.invocationId)
  }

  private progress(invocation: RunningInvocation, result: CapabilityResult): void {
    if (invocation.terminalClaimed || !this.isRunning(invocation.invocationId) || !this.options.outbound.canSend) {
      return
    }
    try {
      const progress = encodeProgressProto(result)
      this.options.outbound.sendProgress(invocation.invocationId, progress)
    } catch (error) {
      // 对外保留 FAILED 诊断，但封存失败的同时必须把停止意图交给仍持有执行体的调用。
      this.terminal(
        invocation.invocationId,
        MessageType.FAILED,
        failedPayload(`cannot encode partial capability result: ${safeMessage(error)}`),
        'CANCELLED',
      )
    }
  }

  private replay(entry: JournalEntry): void {
    if (entry.state === 'RUNNING') {
      this.options.outbound.sendStarted(null, entry.invocationId, true)
    } else {
      const terminal = entry.terminalMessage
      if (terminal !== null) {
        this.options.outbound.sendTerminal(entry.invocationId, terminal)
      }
    }
    if (entry.exitConfirmed) {
      this.options.outbound.sendExecutionExited(entry.invocationId)
    }
  }

  /**
   * 原子终态仲裁与结果派发：journal 的 RUNNING→终态跃迁是唯一提交点，只有跃迁成功者发送终态报文。
   */
  private terminal(
    invocationId: string,
    messageType: TerminalMessage['messageType'],
    payload: TerminalPayload,
    cause: InvocationTerminationCause | null,
  ): void {
    const invocation = this.running.get(invocationId)
    if (invocation !== undefined) {
      if (!invocation.claimTerminal()) {
        return
      }
      invocation.clearDeadline()
      if (cause !== null) {
        invocation.controller.abort(cause)
      }
    }
    const terminal: TerminalMessage = { messageType, payload }
    if (!this.options.journal.complete(invocationId, terminal)) {
      return
    }
    if (invocation !== undefined) {
      this.running.delete(invocationId)
    }
    this.options.outbound.sendTerminal(invocationId, terminal)
  }

  private scheduleTimeout(invocation: RunningInvocation, timeoutMillis: number): void {
    if (!this.isRunning(invocation.invocationId) || invocation.terminalClaimed) {
      return
    }
    // timeout 为 0 表示「没有 deadline」：绝不调度一个立即触发的定时终态。
    if (timeoutMillis === 0) {
      return
    }
    invocation.deadlineTimer = setTimeout(() => {
      if (!this.isRunning(invocation.invocationId) || invocation.terminalClaimed) {
        return
      }
      invocation.controller.abort('TIMED_OUT')
    }, timeoutMillis)
  }

  private isRunning(invocationId: string): boolean {
    const entry = this.options.journal.find(invocationId)
    return entry !== undefined && entry.state === 'RUNNING'
  }
}

class RunningInvocation {
  terminalClaimed = false
  executorReturned = false
  executorFinished = false
  exitConfirmed = false
  exitPublished = false
  private exitPublisher: ((invocation: RunningInvocation) => void) | null = null
  deadlineTimer: NodeJS.Timeout | null = null

  constructor(
    readonly invocationId: string,
    readonly controller: AbortController,
    readonly maxOutputBytes: number,
    readonly config: DaemonConfig,
  ) {}

  markExecutorReturned(): void {
    this.executorReturned = true
    this.exitPublisher?.(this)
  }

  finishOwnedCleanup(): void {
    this.executorReturned = true
    this.executorFinished = true
    this.exitPublisher?.(this)
  }

  noteExitEvidence(publisher: (invocation: RunningInvocation) => void): void {
    this.exitConfirmed = true
    this.exitPublisher = publisher
    if (this.executorReturned) {
      publisher(this)
    }
  }

  claimTerminal(): boolean {
    if (this.terminalClaimed) {
      return false
    }
    this.terminalClaimed = true
    return true
  }

  clearDeadline(): void {
    if (this.deadlineTimer !== null) {
      clearTimeout(this.deadlineTimer)
      this.deadlineTimer = null
    }
  }

  trySendControl(sender: () => boolean): boolean {
    if (this.terminalClaimed) {
      return false
    }
    return sender()
  }
}
