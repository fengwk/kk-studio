import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { Worker } from 'node:worker_threads'
import type { CapabilityExecutor } from '../contracts.js'
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
  STOP_NORMAL,
  STOP_TIMED_OUT,
  type SearchJob,
  type SearchReply,
  type SkippedEntry,
} from './search-core.js'

/**
 * 搜索能力（fs.find / fs.grep）。
 *
 * <p>真实搜索在 Worker 线程执行（RE2/picomatch/ignore 的同步扫描不会阻塞主线程的事件循环），因此 AbortSignal、超时与心跳始终可响应；取消或超时对
 * Worker 执行硬终止（{@code terminate()}），返回稳定的 cancelled/timeout 结果。正常完成也要等 Worker 线程退出后再交付结果，确保搜索线程已释放。Worker
 * 只读文件并回传纯数据，输出 spool 与持久化状态只在主线程共享一份。
 */

export { matchCenteredExcerpt, MAX_SKIP_DETAIL } from './search-core.js'

const findExecutor: CapabilityExecutor = async (call, context) => {
  try {
    const args = call.arguments
    const pattern = requiredString(args, 'pattern')
    const path = await resolveExistingPath(requiredString(args, 'path'))
    const limit = optionalPositiveInt(args, 'limit', 1000, 100000)
    const reply = await runSearchInWorker(
      (stopBuffer) => ({ kind: 'find', pattern, path, limit, stopBuffer }),
      context.signal,
    )
    if (!reply.ok) {
      return toErrorResult(call.invocationId, replyError(reply))
    }
    if (reply.kind !== 'find') {
      throw new ToolRunFailureError('search worker returned a mismatched result', 'INTERNAL')
    }

    const isStopped = reply.stopReason !== null && reply.stopReason !== undefined
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
      if (isStopped) {
        const stopNotice =
          reply.stopReason === 'TIMED_OUT'
            ? '[Search timed out; results are incomplete.]'
            : '[Search cancelled; results are incomplete.]'
        const hasPrecedingText = displayCount > 0 || reply.limitReached || reply.skipped.length > 0
        const prefix = hasPrecedingText ? '\n\n' : ''
        await spool.write(utf8(`${prefix}${stopNotice}`))
      }
      const finishResult = await spool.finish(isStopped)
      if (isStopped) {
        finishResult.details = {
          ...finishResult.details,
          code: reply.stopReason === 'TIMED_OUT' ? 'TIMEOUT' : 'CANCELLED',
          search: { outcome: reply.stopReason, incomplete: true },
        }
      }
      return finishResult
    } finally {
      await spool.close()
    }
  } catch (error) {
    const result = toErrorResult(call.invocationId, error)
    if (context.signal.aborted) {
      const stopReason = context.signal.reason === 'TIMED_OUT' ? 'TIMED_OUT' : 'CANCELLED'
      result.details = {
        ...result.details,
        code: stopReason === 'TIMED_OUT' ? 'TIMEOUT' : 'CANCELLED',
        search: { outcome: stopReason, incomplete: true },
      }
    }
    return result
  }
}

const grepExecutor: CapabilityExecutor = async (call, context) => {
  try {
    const args = call.arguments
    const pattern = requiredString(args, 'pattern')
    const path = await resolveExistingPath(requiredString(args, 'path'))
    const limit = optionalPositiveInt(args, 'limit', 100, 100000)
    const reply = await runSearchInWorker(
      (stopBuffer) => ({
        kind: 'grep',
        pattern,
        path,
        limit,
        multiline: optionalBoolean(args, 'multiline'),
        literal: optionalBoolean(args, 'literal'),
        ignoreCase: optionalBoolean(args, 'ignore_case'),
        include: optionalString(args, 'include'),
        stopBuffer,
      }),
      context.signal,
    )
    if (!reply.ok) {
      return toErrorResult(call.invocationId, replyError(reply))
    }
    if (reply.kind !== 'grep') {
      throw new ToolRunFailureError('search worker returned a mismatched result', 'INTERNAL')
    }

    const isStopped = reply.stopReason !== null && reply.stopReason !== undefined
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
      if (isStopped) {
        const stopNotice =
          reply.stopReason === 'TIMED_OUT'
            ? '[Search timed out; results are incomplete.]'
            : '[Search cancelled; results are incomplete.]'
        const hasPrecedingText = reply.lines.length > 0 || reply.moreMatches || reply.skipped.length > 0
        const prefix = hasPrecedingText ? '\n\n' : ''
        await spool.write(utf8(`${prefix}${stopNotice}`))
      }
      const finishResult = await spool.finish(isStopped)
      if (isStopped) {
        finishResult.details = {
          ...finishResult.details,
          code: reply.stopReason === 'TIMED_OUT' ? 'TIMEOUT' : 'CANCELLED',
          search: { outcome: reply.stopReason, incomplete: true },
        }
      }
      return finishResult
    } finally {
      await spool.close()
    }
  } catch (error) {
    const result = toErrorResult(call.invocationId, error)
    if (context.signal.aborted) {
      const stopReason = context.signal.reason === 'TIMED_OUT' ? 'TIMED_OUT' : 'CANCELLED'
      result.details = {
        ...result.details,
        code: stopReason === 'TIMED_OUT' ? 'TIMEOUT' : 'CANCELLED',
        search: { outcome: stopReason, incomplete: true },
      }
    }
    return result
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
function runSearchInWorker(
  buildJob: (stopBuffer: SharedArrayBuffer) => SearchJob,
  signal: AbortSignal,
): Promise<SearchReply> {
  return new Promise<SearchReply>((resolve, reject) => {
    const stopBuffer = new SharedArrayBuffer(4)
    const stopView = new Int32Array(stopBuffer)
    Atomics.store(stopView, 0, STOP_NORMAL)

    const worker = createSearchWorker()
    let settled = false
    let graceTimer: NodeJS.Timeout | null = null
    let receivedReply: SearchReply | null = null

    const clearGraceTimer = (): void => {
      if (graceTimer !== null) {
        clearTimeout(graceTimer)
        graceTimer = null
      }
    }

    const triggerStop = (): void => {
      const code = signal.reason === 'TIMED_OUT' ? STOP_TIMED_OUT : STOP_CANCELLED
      Atomics.store(stopView, 0, code)
      if (graceTimer === null && !settled) {
        graceTimer = setTimeout(() => {
          void (async () => {
            if (settled) {
              return
            }
            settled = true
            clearGraceTimer()
            signal.removeEventListener('abort', triggerStop)
            try {
              await worker.terminate()
            } catch {
              // 忽略 terminate 自身可能抛出的异常
            }
            const stopReason = code === STOP_TIMED_OUT ? 'TIMED_OUT' : 'CANCELLED'
            reject(
              new ToolRunFailureError(
                `search worker did not respond within cleanup grace period after ${stopReason}`,
                stopReason === 'TIMED_OUT' ? 'TIMEOUT' : 'CANCELLED',
              ),
            )
          })()
        }, CLEANUP_GRACE_MILLIS)
      }
    }

    if (signal.aborted) {
      triggerStop()
    } else {
      signal.addEventListener('abort', triggerStop, { once: true })
    }

    worker.on('message', (reply: SearchReply) => {
      receivedReply = reply
    })

    worker.on('error', (error: Error) => {
      void (async () => {
        if (settled) {
          return
        }
        settled = true
        clearGraceTimer()
        signal.removeEventListener('abort', triggerStop)
        try {
          await worker.terminate()
        } catch {
          // 忽略
        }
        reject(error)
      })()
    })

    worker.on('exit', (code: number) => {
      if (settled) {
        return
      }
      settled = true
      clearGraceTimer()
      signal.removeEventListener('abort', triggerStop)

      if (receivedReply !== null) {
        resolve(receivedReply)
        return
      }
      reject(
        new ToolRunFailureError(
          `the search worker exited unexpectedly (code ${code})`,
          'INTERNAL',
        ),
      )
    })

    const job = buildJob(stopBuffer)
    worker.postMessage(job)
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
