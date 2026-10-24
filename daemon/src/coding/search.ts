import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { Worker } from 'node:worker_threads'
import type { CapabilityContext, CapabilityExecutor, CapabilityResult } from '../contracts.js'
import { errorResult, textResult } from '../contracts.js'
import {
  CancelledError,
  ToolInputRejectedError,
  ToolRunFailureError,
  optionalBoolean,
  optionalPositiveInt,
  optionalString,
  requiredString,
  resolveExistingPath,
  toErrorResult,
} from './support.js'
import { OutputSpool, textOutputStoreFor } from './output.js'
import {
  MAX_SKIP_DETAIL,
  STOP_CANCELLED,
  STOP_TIMED_OUT,
  type SearchJob,
  type SearchReply,
  type SearchStopReason,
  type SkippedEntry,
} from './search-core.js'

/**
 * 搜索能力（fs.find / fs.grep）。
 *
 * <p>Worker 在扫描检查点观察调用停止信号，保留已收集结果并自然退出；只有停止后的清理宽限耗尽才强制终止。
 * 父线程等待退出后再交付结果，输出 spool 与持久化状态只在主线程共享一份。
 */

export { matchCenteredExcerpt, MAX_SKIP_DETAIL } from './search-core.js'

function stopNoticeFor(reason: SearchStopReason): string {
  return reason === 'TIMED_OUT'
    ? '[Search timed out; results are incomplete.]'
    : '[Search cancelled; results are incomplete.]'
}

function applyStoppedDetails(result: CapabilityResult, reason: SearchStopReason): void {
  result.details = {
    ...result.details,
    code: reason === 'TIMED_OUT' ? 'TIMEOUT' : 'CANCELLED',
    search: { outcome: reason, incomplete: true },
  }
}

function searchFailure(callId: string, error: unknown, signal: AbortSignal): CapabilityResult {
  const result = toErrorResult(callId, error)
  if (signal.aborted) {
    const reason = signal.reason === 'TIMED_OUT' ? 'TIMED_OUT' : 'CANCELLED'
    result.details = { ...result.details, search: { outcome: reason, incomplete: true } }
    result.contents.push({ type: 'text', text: stopNoticeFor(reason) })
  }
  return result
}

const findExecutor: CapabilityExecutor = async (call, context) => {
  let workerStarted = false
  let workerExited = false
  let spoolClosed = true
  try {
    const args = call.arguments
    const pattern = requiredString(args, 'pattern')
    const path = await resolveExistingPath(requiredString(args, 'path'))
    const limit = optionalPositiveInt(args, 'limit', 1000, 100000)
    const stopBuffer = new SharedArrayBuffer(4)
    const job: SearchJob = { kind: 'find', pattern, path, limit, stopBuffer }

    const reply = await runSearchInWorker(job, context, {
      onWorkerStarted: () => {
        workerStarted = true
      },
      onWorkerExited: () => {
        workerExited = true
      },
    })
    if (!reply.ok) {
      throw replyError(reply)
    }
    if (reply.kind !== 'find') {
      throw new ToolRunFailureError('search worker returned a mismatched result', 'INTERNAL')
    }

    const stopReason = reply.stopReason
    const isStopped = stopReason !== null
    if (!isStopped && reply.matches.length === 0) {
      if (reply.skipped.length > 0) {
        return errorResult(
          call.invocationId,
          `No files found matching pattern, but ${reply.skipped.length} path(s) could not be searched: [${describeSkipped(reply.skipped)}]`,
        )
      }
      return textResult(call.invocationId, 'No files found matching pattern')
    }

    const displayCount = Math.min(limit, reply.matches.length)
    const store = await textOutputStoreFor(context.config.dataDir)
    const spool = new OutputSpool(store, call.invocationId)
    spoolClosed = false
    try {
      for (let index = 0; index < displayCount; index++) {
        if (index > 0) {
          await spool.write(utf8('\n'))
        }
        await spool.write(utf8(reply.matches[index] as string))
      }
      if (reply.limitReached) {
        const prefix = displayCount > 0 ? '\n\n' : ''
        await spool.write(
          utf8(`${prefix}[${limit} results limit reached. Refine the pattern or raise limit.]`),
        )
      }
      if (reply.skipped.length > 0) {
        const prefix = displayCount > 0 || reply.limitReached ? '\n\n' : ''
        await spool.write(
          utf8(
            `${prefix}[${reply.skipped.length} path(s) could not be searched: ${describeSkipped(reply.skipped)}]`,
          ),
        )
      }
      if (stopReason !== null) {
        const hasPrecedingText = displayCount > 0 || reply.limitReached || reply.skipped.length > 0
        const prefix = hasPrecedingText ? '\n\n' : ''
        await spool.write(utf8(`${prefix}${stopNoticeFor(stopReason)}`))
      }
      const finishResult = await spool.finish(isStopped)
      if (stopReason !== null) {
        applyStoppedDetails(finishResult, stopReason)
      }
      return finishResult
    } finally {
      await spool.close()
      spoolClosed = true
    }
  } catch (error) {
    return searchFailure(call.invocationId, error, context.signal)
  } finally {
    // 无结果与前置拒绝同样收敛；若已创建 spool，必须成功关闭后才能确认。
    if ((!workerStarted || workerExited) && spoolClosed) {
      context.confirmExecutionExit()
    }
  }
}

const grepExecutor: CapabilityExecutor = async (call, context) => {
  let workerStarted = false
  let workerExited = false
  let spoolClosed = true
  try {
    const args = call.arguments
    const pattern = requiredString(args, 'pattern')
    const path = await resolveExistingPath(requiredString(args, 'path'))
    const limit = optionalPositiveInt(args, 'limit', 100, 100000)
    const multiline = optionalBoolean(args, 'multiline')
    const literal = optionalBoolean(args, 'literal')
    const ignoreCase = optionalBoolean(args, 'ignore_case')
    const include = optionalString(args, 'include')
    const stopBuffer = new SharedArrayBuffer(4)
    const job: SearchJob = {
      kind: 'grep',
      pattern,
      path,
      limit,
      multiline,
      literal,
      ignoreCase,
      include,
      stopBuffer,
    }

    const reply = await runSearchInWorker(job, context, {
      onWorkerStarted: () => {
        workerStarted = true
      },
      onWorkerExited: () => {
        workerExited = true
      },
    })
    if (!reply.ok) {
      throw replyError(reply)
    }
    if (reply.kind !== 'grep') {
      throw new ToolRunFailureError('search worker returned a mismatched result', 'INTERNAL')
    }

    const stopReason = reply.stopReason
    const isStopped = stopReason !== null
    if (!isStopped && reply.lines.length === 0) {
      if (reply.skipped.length > 0) {
        return errorResult(
          call.invocationId,
          `No matches found, but ${reply.skipped.length} path(s) could not be searched: [${describeSkipped(reply.skipped)}]`,
        )
      }
      return textResult(call.invocationId, 'No matches found')
    }

    const store = await textOutputStoreFor(context.config.dataDir)
    const spool = new OutputSpool(store, call.invocationId)
    spoolClosed = false
    try {
      for (let index = 0; index < reply.lines.length; index++) {
        if (index > 0) {
          await spool.write(utf8('\n'))
        }
        await spool.write(utf8(reply.lines[index] as string))
      }
      if (reply.moreMatches) {
        const prefix = reply.lines.length > 0 ? '\n\n' : ''
        await spool.write(
          utf8(`${prefix}[${limit} results limit reached. Refine the pattern or raise limit.]`),
        )
      }
      if (reply.skipped.length > 0) {
        const prefix = reply.lines.length > 0 || reply.moreMatches ? '\n\n' : ''
        await spool.write(
          utf8(
            `${prefix}[${reply.skipped.length} path(s) could not be searched: ${describeSkipped(reply.skipped)}]`,
          ),
        )
      }
      if (stopReason !== null) {
        const hasPrecedingText = reply.lines.length > 0 || reply.moreMatches || reply.skipped.length > 0
        const prefix = hasPrecedingText ? '\n\n' : ''
        await spool.write(utf8(`${prefix}${stopNoticeFor(stopReason)}`))
      }
      const finishResult = await spool.finish(isStopped)
      if (stopReason !== null) {
        applyStoppedDetails(finishResult, stopReason)
      }
      return finishResult
    } finally {
      await spool.close()
      spoolClosed = true
    }
  } catch (error) {
    return searchFailure(call.invocationId, error, context.signal)
  } finally {
    if ((!workerStarted || workerExited) && spoolClosed) {
      context.confirmExecutionExit()
    }
  }
}

/** 把 Worker 的稳定失败类别重建为主线程可映射的受控错误。 */
function replyError(reply: Extract<SearchReply, { ok: false }>): Error {
  if (reply.error === 'input') {
    return new ToolInputRejectedError(reply.message, reply.code)
  }
  if (reply.error === 'cancelled') {
    return new CancelledError()
  }
  return new ToolRunFailureError(reply.message, reply.code)
}

function describeSkipped(skipped: SkippedEntry[]): string {
  const parts: string[] = []
  let shown = 0
  for (const entry of skipped) {
    if (shown === MAX_SKIP_DETAIL) {
      parts.push('...')
      break
    }
    parts.push(`${entry.path} (${entry.reason})`)
    shown++
  }
  return parts.join(', ')
}

const CLEANUP_GRACE_MILLIS = 5000

/**
 * 在 Worker 线程执行搜索作业。
 *
 * <p>唯一执行 timer 来自 Runtime invocation timer；主线程通过 signal.abort 事件向 SharedArrayBuffer
 * 写入原子 stop 标记。Worker 协作式停止并回传已收集结果，然后 port.close()；父线程等待 Worker exit 事件再交付。
 * 收到 stop 后启动单个 5s 清理宽限，5s 仍无响应才 await terminate() 并返回明确错误。
 */
interface SearchWorkerExit {
  onWorkerStarted(): void
  onWorkerExited(): void
}

function runSearchInWorker(
  job: SearchJob,
  context: CapabilityContext,
  exit: SearchWorkerExit,
): Promise<SearchReply> {
  const signal = context.signal
  return new Promise<SearchReply>((resolve, reject) => {
    const worker = createSearchWorker()
    exit.onWorkerStarted()
    let settled = false
    let graceTimer: NodeJS.Timeout | null = null
    let receivedReply: SearchReply | null = null
    let workerExited = false

    const clearGraceTimer = (): void => {
      if (graceTimer !== null) {
        clearTimeout(graceTimer)
        graceTimer = null
      }
    }

    const confirmIfExited = (): void => {
      // terminate() 成功或 exit 事件才证明这次 worker 已离开；取消本身不是退出证据。
      if (workerExited) {
        exit.onWorkerExited()
      }
    }

    const failAfterCleanup = async (error: unknown): Promise<void> => {
      if (settled) {
        return
      }
      settled = true
      clearGraceTimer()
      signal.removeEventListener('abort', triggerStop)
      try {
        await worker.terminate()
        workerExited = true
      } catch {
        reject(new ToolRunFailureError('search worker cleanup failed; results incomplete', 'INTERNAL'))
        return
      }
      confirmIfExited()
      reject(error)
    }

    const triggerStop = (): void => {
      const code = signal.reason === 'TIMED_OUT' ? STOP_TIMED_OUT : STOP_CANCELLED
      Atomics.store(new Int32Array(job.stopBuffer), 0, code)
      if (graceTimer === null && !settled) {
        graceTimer = setTimeout(() => {
          void failAfterCleanup(
            new ToolRunFailureError(
              'search worker did not respond within cleanup grace period; results incomplete',
              code === STOP_TIMED_OUT ? 'TIMEOUT' : 'CANCELLED',
            ),
          )
        }, CLEANUP_GRACE_MILLIS)
      }
    }

    if (signal.aborted) {
      triggerStop()
    } else {
      signal.addEventListener('abort', triggerStop, { once: true })
    }

    worker.once('message', (reply: SearchReply) => {
      receivedReply = reply
    })

    worker.once('error', (error: Error) => {
      void failAfterCleanup(error)
    })

    worker.once('exit', (code: number) => {
      if (settled) {
        return
      }
      settled = true
      clearGraceTimer()
      signal.removeEventListener('abort', triggerStop)
      workerExited = true
      exit.onWorkerExited()

      if (code === 0 && receivedReply !== null) {
        resolve(receivedReply)
        return
      }
      reject(
        new ToolRunFailureError(
          `the search worker exited unexpectedly (code ${code}); results incomplete`,
          'INTERNAL',
        ),
      )
    })

    try {
      worker.postMessage(job)
    } catch (postError) {
      void failAfterCleanup(postError)
    }
  })
}

/**
 * 搜索 Worker 的定位契约：Worker 只加载编译后的 JS 入口。
 *
 * <p>只按模块入口位置定位同一个编译产物——编译后的 {@code search.js} 与 {@code search-worker.js} 同目录，默认引用同目录
 * {@code ./search-worker.js}；Vitest 源码态回落到本项目 {@code dist/coding/search-worker.js}。没有编译产物即 fail-closed 并提示先构建，不使用
 * loader hooks，也不做源码兼容。
 */
export function resolveSearchWorkerEntry(base: URL = new URL(import.meta.url)): URL | null {
  const sibling = new URL('./search-worker.js', base)
  if (existsSync(fileURLToPath(sibling))) {
    return sibling
  }
  const fromSource = new URL('../../dist/coding/search-worker.js', base)
  if (existsSync(fileURLToPath(fromSource))) {
    return fromSource
  }
  return null
}

function createSearchWorker(): Worker {
  const entry = resolveSearchWorkerEntry()
  if (entry === null) {
    throw new ToolRunFailureError(
      'the compiled search worker is missing; build the daemon so dist/coding/search-worker.js exists',
      'INTERNAL',
    )
  }
  return new Worker(entry)
}

function utf8(value: string): Uint8Array {
  return new Uint8Array(Buffer.from(value, 'utf8'))
}

export const searchCapabilities: Record<string, CapabilityExecutor> = {
  'fs.find': findExecutor,
  'fs.grep': grepExecutor,
}
