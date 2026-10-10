import { realpath, stat } from 'node:fs/promises'
import type { Readable } from 'node:stream'
import type { CapabilityExecutor, CapabilityResult, ManagedProcess } from '../contracts.js'
import { errorResult } from '../contracts.js'
import {
  ToolInputRejectedError,
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
  let timer: NodeJS.Timeout | null = null
  let cancelled = false
  let timedOut = false
  const onAbort = (): void => {
    cancelled = true
    void managed?.terminate()
  }
  try {
    const args = call.arguments
    const command = requiredString(args, 'command')
    const workdir = await resolveWorkdir(requiredString(args, 'workdir'))
    const timeoutMillis = call.timeoutMillis

    if (context.signal.aborted) {
      // 取消早于执行线程开始：不启动 helper、不产生任何本地文件。
      return terminatedBeforeStart(call.invocationId, 'CANCELLED', timeoutMillis)
    }

    const store = await textOutputStoreFor(context.config.dataDir)
    spool = new OutputSpool(store, call.invocationId)
    const emitter = new LiveEmitter(call.invocationId, context, () => cancelled || timedOut)
    const decoder = new ProcessUtf8Decoder()

    try {
      managed = await context.processes.start({
        executable: context.config.bashExecutable,
        args: ['-lc', command],
        cwd: workdir,
      })
    } catch {
      return errorResult(
        call.invocationId,
        guide(
          `the shell could not be started: ${context.config.bashExecutable}`,
          'NOT_EXECUTED',
          'Review the command and the reported process state before deciding what to do next',
        ),
        'FAILED',
      )
    }
    // 命令以参数传入，进程不会从 stdin 读取；立刻关闭写端，等待 EOF 的命令才能自然退出。
    managed.stdin.end()
    context.signal.addEventListener('abort', onAbort, { once: true })
    if (timeoutMillis > 0) {
      timer = setTimeout(() => {
        timedOut = true
        void managed?.terminate()
      }, timeoutMillis)
    }

    const running = managed
    await drainMerged([running.stdout, running.stderr], async (chunk) => {
      await spool?.write(chunk)
      await emitter.accept(decoder.decode(chunk))
      if (spool !== null && spool.isCaptureTruncated() && !emitter.truncationReported) {
        emitter.truncationReported = true
        await emitter.snapshot(spool)
      }
    })
    await emitter.accept(decoder.finish())
    await emitter.flush()

    const exit = await running.exit
    const outcome: ProcessOutcome = cancelled ? 'CANCELLED' : timedOut ? 'TIMED_OUT' : 'EXITED'
    const naturalExit = exit.exitCode
    const exitCode = naturalExit ?? 0
    const failed = outcome !== 'EXITED' || exitCode !== 0 || naturalExit === null
    const note = terminationNote(outcome, exitCode, timeoutMillis, exit.signal)
    const captured = await spool.finish(failed, note)
    return withProcessOutcome(captured, outcome, naturalExit, exit.signal)
  } catch (error) {
    return toErrorResult(call.invocationId, error)
  } finally {
    if (timer !== null) {
      clearTimeout(timer)
    }
    context.signal.removeEventListener('abort', onAbort)
    // 兜底：参数校验失败、资源只创建了一部分等未走到上面路径的情况都不能遗留进程或未收尾的中转文件。
    void managed?.terminate()
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

/** 持续排空合并输出：读取永不停止，输出体积与本地磁盘状态都不构成终止进程的理由。 */
async function drainMerged(
  streams: Readable[],
  onChunk: (chunk: Uint8Array) => Promise<void>,
): Promise<void> {
  const queue: Uint8Array[] = []
  let ended = 0
  let notify: (() => void) | null = null
  const wake = (): void => {
    if (notify !== null) {
      const resolve = notify
      notify = null
      resolve()
    }
  }
  for (const stream of streams) {
    stream.on('data', (chunk: Buffer | string) => {
      queue.push(typeof chunk === 'string' ? Buffer.from(chunk) : chunk)
      wake()
    })
    stream.on('end', () => {
      ended++
      wake()
    })
    // 终止导致的流异常关闭：已读取的字节仍然有效，不能因此丢弃已捕获输出。
    stream.on('error', () => {
      ended++
      wake()
    })
  }
  while (ended < streams.length || queue.length > 0) {
    if (queue.length === 0) {
      await new Promise<void>((resolve) => {
        notify = resolve
      })
      continue
    }
    const chunk = queue.shift() as Uint8Array
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
 * <p>{@code APPEND} 模式承载连续新区间；捕获被截断时用 {@code SNAPSHOT} 携带最新有界尾部，明确提示全文不完整。
 */
class LiveEmitter {
  private pending: number[] = []
  private observedBytes = 0
  private nextOffset = 0
  private lastFlush = Date.now()
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
    if (text.length === 0 || this.stopped()) {
      return
    }
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

/** 启动前已被取消的收尾：不启动 shell、不创建任何本地文件，直接给出明确的进程去向。 */
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

export const processCapability: CapabilityExecutor = processExecutor

/** 供 Runtime 注册的单项能力表。 */
export const processCapabilities: Record<string, CapabilityExecutor> = {
  'process.exec': processExecutor,
}
