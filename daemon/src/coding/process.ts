import { realpath, stat } from 'node:fs/promises'
import type { Readable } from 'node:stream'
import type { CapabilityExecutor, CapabilityResult, ManagedProcess } from '../contracts.js'
import { errorResult } from '../contracts.js'
import {
  ToolInputRejectedError,
  ToolRunFailureError,
  displayPath,
  guide,
  isAbsolute,
  normalize,
  requiredString,
  toErrorResult,
} from './support.js'
import { OutputSpool, alignToCharacterBoundary, textOutputStoreFor } from './output.js'

/**
 * 进程能力（process.exec）：在调用方显式提供的 workdir 中执行 Platform 授权的 shell 命令。
 *
 * <p>与 Java 参考实现对齐：命令以参数传入（不读 stdin），stdout/stderr 合并后持续排空，读取永不因输出体积停止；超过内联阈值时落本地文件，超出捕获预算时只停止文件捕获并继续
 * 计数，两种情况都不终止进程。超时与取消都终止执行范围并保留已捕获输出，终态用 {@code details.process.outcome} 与文本说明区分三种失败。live 阶段按块或时间合并发出
 * {@code APPEND} partial，捕获被截断时补发一条 {@code SNAPSHOT}。
 *
 * <p>收敛语义：终止请求只发起一次并缓存为同一个 Promise（发起时即挂拒绝处理，绝无 unhandled rejection）；主路径与 {@code finally} 都 await 同一 Promise，
 * 因此返回前执行范围一定已收敛。收敛失败是必须显式暴露的事实：返回安全的 FAILED 结果，绝不报告成功。取消/超时在 shell 放行前与启动 await 之后都设围栏。
 */

/** 单条 live partial 的文本上界：远低于 256 KiB 的单条 partial 协议上限。 */
export const LIVE_PARTIAL_UTF8_BYTES = 64 * 1024
/** 慢速输出下的合并间隔：小于该间隔的多次读取合并为一条 partial。 */
export const LIVE_FLUSH_INTERVAL_MILLIS = 150

const PARTIAL_KIND = 'process.output'
const PARTIAL_MODE_APPEND = 'APPEND'
const PARTIAL_MODE_SNAPSHOT = 'SNAPSHOT'

type ProcessOutcome = 'EXITED' | 'TIMED_OUT' | 'CANCELLED'

const processExecutor: CapabilityExecutor = async (call, context) => {
  let spool: OutputSpool | null = null
  let managed: ManagedProcess | null = null
  let cancelled = false
  let timedOut = false
  let terminationFailure: unknown = null
  let termination: Promise<void> | null = null

  /**
   * 只发起一次终止并缓存；发起即处理拒绝，主路径与 finally await 同一 Promise。
   *
   * <p>尚未启动时（{@code managed === null}）不缓存：取消可能发生在 shell 放行之前，此时没有可终止的范围，必须在启动 await 之后重新收敛。
   */
  const requestTermination = (): Promise<void> => {
    if (termination !== null) {
      return termination
    }
    if (managed === null) {
      return Promise.resolve()
    }
    termination = (async () => {
      try {
        await managed?.terminate()
      } catch (error) {
        terminationFailure = error
      }
    })()
    return termination
  }
  const onAbort = (): void => {
    if (context.signal.reason === 'TIMED_OUT') {
      timedOut = true
    } else {
      cancelled = true
    }
    void requestTermination()
  }

  try {
    const args = call.arguments
    const command = requiredString(args, 'command')
    const workdir = await resolveWorkdir(requiredString(args, 'workdir'))
    const timeoutMillis = call.timeoutMillis
    const store = await textOutputStoreFor(context.config.dataDir)

    context.signal.addEventListener('abort', onAbort, { once: true })
    if (context.signal.aborted) {
      onAbort()
      await requestTermination()
      return terminatedBeforeStart(
        call.invocationId,
        timedOut ? 'TIMED_OUT' : 'CANCELLED',
        timeoutMillis,
      )
    }

    spool = new OutputSpool(store, call.invocationId)
    const emitter = new LiveEmitter(call.invocationId, context, () => cancelled || timedOut)
    const decoder = new ProcessUtf8Decoder()

    if (cancelled || timedOut) {
      await requestTermination()
      return terminatedBeforeStart(
        call.invocationId,
        cancelled ? 'CANCELLED' : 'TIMED_OUT',
        timeoutMillis,
      )
    }

    try {
      managed = await context.processes.start({
        executable: context.config.bashExecutable,
        args: ['-lc', command],
        cwd: workdir,
        signal: context.signal,
      })
    } catch {
      await requestTermination()
      if (cancelled) {
        return terminatedBeforeStart(call.invocationId, 'CANCELLED', timeoutMillis)
      }
      if (timedOut) {
        return terminatedBeforeStart(call.invocationId, 'TIMED_OUT', timeoutMillis)
      }
      return shellStartFailure(call.invocationId, context.config.bashExecutable)
    }

    // 启动 await 之后再次设围栏：取消/超时窗口内绝不继续排空，先收敛再给出明确去向。
    if (cancelled || timedOut) {
      await requestTermination()
      return terminatedBeforeStart(
        call.invocationId,
        cancelled ? 'CANCELLED' : 'TIMED_OUT',
        timeoutMillis,
      )
    }

    // 命令以参数传入，进程不会从 stdin 读取；立刻关闭写端，等待 EOF 的命令才能自然退出。
    managed.stdin.end()
    const running = managed
    await drainMerged(
      [running.stdout, running.stderr],
      async (chunk) => {
        await spool?.write(chunk)
        await emitter.accept(decoder.decode(chunk))
        if (spool !== null && spool.isCaptureTruncated() && !emitter.truncationReported) {
          emitter.truncationReported = true
          await emitter.snapshot(spool)
        }
      },
      // 真实读错误：先让执行范围收敛（幂等），再继续排空其它流。
      () => {
        void requestTermination()
      },
      // 只有取消/超时之后的 premature close / destroyed 才算预期 EOF。
      () => cancelled || timedOut,
    )
    await emitter.accept(decoder.finish())
    await emitter.flush()

    const exit = await running.exit
    // 自然退出时这里是幂等空操作；超时/取消时等到终止完全收敛。
    await requestTermination()
    if (terminationFailure !== null) {
      return convergenceFailure(call.invocationId, spool)
    }

    const outcome: ProcessOutcome = cancelled ? 'CANCELLED' : timedOut ? 'TIMED_OUT' : 'EXITED'
    const naturalExit = exit.exitCode
    const exitCode = naturalExit ?? 0
    const failed = outcome !== 'EXITED' || exitCode !== 0 || naturalExit === null
    const note = terminationNote(outcome, exitCode, timeoutMillis, exit.signal)
    const captured = await spool.finish(failed, note)
    return withProcessOutcome(captured, outcome, naturalExit, exit.signal)
  } catch (error) {
    // 异常路径同样先收敛，再决定终态；收敛失败绝不报告成功。
    await requestTermination()
    if (terminationFailure !== null) {
      return convergenceFailure(call.invocationId, spool)
    }
    // 已捕获输出仍是真实内容：读失败（如 EIO）时以 error 结果保留前缀，绝不声称成功。
    if (spool !== null && spool.totalBytes() > 0 && error instanceof ToolRunFailureError) {
      return spool.finish(true, `[${error.message}]`)
    }
    return toErrorResult(call.invocationId, error)
  } finally {
    context.signal.removeEventListener('abort', onAbort)
    // 兜底：返回前收敛执行范围并释放全部中转资源；收敛是幂等的。
    await requestTermination()
    if (spool !== null) {
      await spool.close()
    }
  }
}

/** 解析并校验 workdir：必须显式提供、绝对、现存且为目录。 */
async function resolveWorkdir(raw: string): Promise<string> {
  if (raw.trim().length === 0) {
    throw new ToolInputRejectedError('workdir is required')
  }
  if (!isAbsolute(raw)) {
    throw new ToolInputRejectedError(
      'workdir must be an absolute path (a relative or non-absolute value was given)',
    )
  }
  let real: string
  try {
    real = await realpath(normalize(raw))
  } catch {
    throw new ToolInputRejectedError(`workdir does not exist: ${displayPath(null, raw)}`, 'NOT_FOUND')
  }
  const info = await stat(real)
  if (!info.isDirectory()) {
    throw new ToolInputRejectedError(`workdir is not a directory: ${displayPath(real, raw)}`)
  }
  return real
}

/**
 * 真正 Readable 背压合并：每个流最多保留一个在途读取，`onChunk` 变慢时不会把整段 stdout 积进内存。
 *
 * <p>读错误分两类：取消/超时之后的 premature close / destroyed 是预期 EOF（`terminationExpected`）；其余（例如真实 EIO）是读失败——先触发
 * `onReadFailure`（让执行范围收敛），再继续排空其它流，最后把安全错误抛给外层。生成器**不**在 finally 里 await `iterator.return()`，避免在外层终止之前死锁。
 */
async function* mergeStreams(
  streams: Readable[],
  onReadFailure: () => void,
  terminationExpected: () => boolean,
): AsyncGenerator<Uint8Array> {
  type MergeEvent =
    | { kind: 'chunk'; index: number; value: Uint8Array }
    | { kind: 'end'; index: number }
    | { kind: 'error'; index: number; error: unknown }
  const iterators = streams.map(
    (stream) => stream[Symbol.asyncIterator]() as AsyncIterator<Uint8Array>,
  )
  const step = (index: number): Promise<MergeEvent> =>
    (iterators[index] as AsyncIterator<Uint8Array>).next().then(
      (result) =>
        result.done
          ? ({ kind: 'end', index } as MergeEvent)
          : ({ kind: 'chunk', index, value: result.value } as MergeEvent),
      (error) =>
        terminationExpected()
          ? ({ kind: 'end', index } as MergeEvent)
          : ({ kind: 'error', index, error } as MergeEvent),
    )
  const pending = new Map<number, Promise<MergeEvent>>()
  for (let index = 0; index < iterators.length; index++) {
    pending.set(index, step(index))
  }
  let failure: unknown = null
  while (pending.size > 0) {
    const event = await Promise.race(pending.values())
    pending.delete(event.index)
    if (event.kind === 'end') {
      continue
    }
    if (event.kind === 'error') {
      if (failure === null) {
        failure = event.error
        onReadFailure()
      }
      // 不重新请求该流，但继续排空其它流（有界内存），最后统一抛出安全错误。
      continue
    }
    yield event.value
    // 消费完当前 chunk 才请求下一块：形成真实背压。
    pending.set(event.index, step(event.index))
  }
  if (failure !== null) {
    throw new ToolRunFailureError('the process output stream failed while reading', 'FAILED')
  }
}

/** 持续排空合并输出：读取永不停止，输出体积与本地磁盘状态都不构成终止进程的理由。 */
async function drainMerged(
  streams: Readable[],
  onChunk: (chunk: Uint8Array) => Promise<void>,
  onReadFailure: () => void,
  terminationExpected: () => boolean,
): Promise<void> {
  for await (const chunk of mergeStreams(streams, onReadFailure, terminationExpected)) {
    await onChunk(chunk)
  }
}

/** 宽松的流式 UTF-8 解码：保留不完整的后缀字节，与 Java {@code Utf8StreamDecoder} 一致。 */
class ProcessUtf8Decoder {
  private pending: Uint8Array = new Uint8Array(0)

  decode(bytes: Uint8Array): string {
    const combined = Uint8Array.from(Buffer.concat([Buffer.from(this.pending), Buffer.from(bytes)]))
    const suffix = combined.length - alignToCharacterBoundary(combined, combined.length)
    const decoded = Buffer.from(combined.subarray(0, combined.length - suffix)).toString('utf8')
    this.pending = Uint8Array.from(combined.subarray(combined.length - suffix))
    return decoded
  }

  finish(): string {
    const result = Buffer.from(this.pending).toString('utf8')
    this.pending = new Uint8Array(0)
    return result
  }
}

/**
 * live partial 合并器：按时间与块大小两个维度合并，保证单条 partial 有界、区间精确且不丢字节。
 *
 * <p>{@code APPEND} 模式承载连续新区间；捕获被截断时用 {@code SNAPSHOT} 携带最新有界尾部，明确提示全文不完整。progress 投递失败只关闭后续 live
 * 通知，绝不因此停止读取流或伪造 deadline。
 */
class LiveEmitter {
  private pending: number[] = []
  private observedBytes = 0
  private nextOffset = 0
  private lastFlush = Date.now()
  private disabled = false
  truncationReported = false

  constructor(
    private readonly callId: string,
    private readonly context: { progress(result: CapabilityResult): Promise<void> },
    private readonly stopped: () => boolean,
  ) {}

  async accept(text: string): Promise<void> {
    if (text.length === 0) {
      return
    }
    const bytes = Buffer.from(text, 'utf8')
    this.observedBytes += bytes.length
    for (const value of bytes) {
      this.pending.push(value)
    }
    if (
      this.pending.length >= LIVE_PARTIAL_UTF8_BYTES ||
      Date.now() - this.lastFlush >= LIVE_FLUSH_INTERVAL_MILLIS
    ) {
      await this.flush()
    }
  }

  async flush(): Promise<void> {
    while (this.pending.length > 0) {
      const all = Uint8Array.from(this.pending)
      let take = Math.min(all.length, LIVE_PARTIAL_UTF8_BYTES)
      take = alignPartialBoundary(all, take)
      if (take <= 0) {
        return
      }
      const text = Buffer.from(all.subarray(0, take)).toString('utf8')
      const start = this.nextOffset
      const end = this.nextOffset + take
      this.nextOffset = end
      this.pending = Array.from(all.subarray(take))
      await this.emit(text, PARTIAL_MODE_APPEND, start, end)
    }
    this.lastFlush = Date.now()
  }

  async snapshot(spool: OutputSpool): Promise<void> {
    await this.flush()
    await this.emit(
      spool.captureTruncatedTailPreview(),
      PARTIAL_MODE_SNAPSHOT,
      this.nextOffset,
      this.nextOffset,
    )
  }

  private async emit(text: string, mode: string, start: number, end: number): Promise<void> {
    if (this.disabled || text.length === 0 || this.stopped()) {
      return
    }
    try {
      await this.context.progress({
        callId: this.callId,
        error: false,
        details: {
          kind: PARTIAL_KIND,
          mode,
          startOffset: start,
          endOffset: end,
          observedBytes: this.observedBytes,
        },
        contents: [{ type: 'text', text }],
      })
    } catch {
      // progress 通道失败只抑制后续 live 通知：流仍继续读取，命令事实由终态与 durable 全文决定。
      this.disabled = true
    }
  }
}

/** 不切断 UTF-8 多字节字符：回退到最后一个完整字符边界；整个前缀都是续字节时按原样切分。 */
function alignPartialBoundary(bytes: Uint8Array, limit: number): number {
  let index = limit
  while (index > 0 && ((bytes[index - 1] as number) & 0xc0) === 0x80) {
    index--
  }
  if (index === 0) {
    return limit
  }
  const lead = index - 1
  const expected = sequenceLength(bytes[lead] as number)
  return lead + expected <= limit ? limit : lead
}

function sequenceLength(value: number): number {
  const unsigned = value & 0xff
  if ((unsigned & 0x80) === 0) {
    return 1
  }
  if ((unsigned & 0xe0) === 0xc0) {
    return 2
  }
  if ((unsigned & 0xf0) === 0xe0) {
    return 3
  }
  if ((unsigned & 0xf8) === 0xf0) {
    return 4
  }
  return 1
}

/** 终态说明：区分成功、非零退出、超时与取消，并明确取消或失败不代表副作用回滚。 */
function terminationNote(
  outcome: ProcessOutcome,
  exitCode: number,
  timeoutMillis: number,
  signal: string | null,
): string {
  if (outcome === 'CANCELLED') {
    return (
      '[Operation cancelled by the caller. Captured output above may be incomplete; side effects already performed are not rolled back.]'
    )
  }
  if (outcome === 'TIMED_OUT') {
    return (
      `[Command timed out after ${timeoutMillis} ms and was terminated. Captured output above may be incomplete; side effects already performed are not rolled back.]`
    )
  }
  if (signal !== null) {
    return `[Command was terminated by signal ${signal}.]`
  }
  return exitCode === 0 ? '' : `[Command exited with code ${exitCode}.]`
}

/** 在捕获事实之上补充机器可判定的进程收尾结果；退出码只在自然退出时报告。 */
function withProcessOutcome(
  captured: CapabilityResult,
  outcome: ProcessOutcome,
  naturalExit: number | null,
  signal: string | null,
): CapabilityResult {
  const process: Record<string, unknown> = { outcome }
  if (outcome === 'EXITED' && naturalExit !== null) {
    process.exitCode = naturalExit
  }
  if (signal !== null) {
    process.signal = signal
  }
  return { ...captured, details: { ...captured.details, process } }
}

/** 启动前已被取消或超时的收尾：不启动 shell、不创建任何本地文件，直接给出明确的进程去向。 */
function terminatedBeforeStart(
  callId: string,
  outcome: ProcessOutcome,
  timeoutMillis: number,
): CapabilityResult {
  return {
    callId,
    error: true,
    details: { process: { outcome } },
    contents: [{ type: 'text', text: terminationNote(outcome, 0, timeoutMillis, null) }],
  }
}

/** shell 无法启动的收尾：命令从未执行，声明 NOT_EXECUTED 而非含混失败。 */
function shellStartFailure(callId: string, bashExecutable: string): CapabilityResult {
  return errorResult(
    callId,
    guide(
      `the shell could not be started: ${bashExecutable}`,
      'NOT_EXECUTED',
      'Review the command and the reported process state before deciding what to do next',
    ),
    'FAILED',
  )
}

/**
 * 执行范围未能收敛的收尾：绝不报告成功。
 *
 * <p>已捕获输出仍是真实内容，因此以 error 结果保留（有内容时落盘并附说明），但绝不能声称调用已正常完成。
 */
async function convergenceFailure(
  callId: string,
  spool: OutputSpool | null,
): Promise<CapabilityResult> {
  const note =
    '[The process scope did not converge; processes may still be running. Captured output above may be incomplete; side effects already performed are not rolled back.]'
  if (spool !== null && spool.totalBytes() > 0) {
    return spool.finish(true, note)
  }
  return errorResult(
    callId,
    guide(
      'the process scope did not converge; processes may still be running',
      'UNCERTAIN',
      'Verify whether the command and its descendants have stopped before deciding what to do next',
    ),
    'FAILED',
  )
}

export const processCapability: CapabilityExecutor = processExecutor

/** 供 Runtime 注册的单项能力表。 */
export const processCapabilities: Record<string, CapabilityExecutor> = {
  'process.exec': processExecutor,
}
