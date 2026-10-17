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
    const job: SearchJob = { kind: 'find', pattern, path, limit, timeoutMillis: call.timeoutMillis }
    const reply = await runSearchInWorker(job, call.timeoutMillis, context.signal)
    if (!reply.ok) {
      return toErrorResult(call.invocationId, replyError(reply))
    }
    if (reply.kind !== 'find') {
      throw new ToolRunFailureError('search worker returned a mismatched result', 'INTERNAL')
    }
    if (reply.matches.length === 0) {
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
        await spool.write(
          utf8(`\n\n[${limit} results limit reached. Refine the pattern or raise limit.]`),
        )
      }
      if (reply.skipped.length > 0) {
        await spool.write(
          utf8(
            `\n\n[${reply.skipped.length} path(s) could not be searched: ${describeSkipped(reply.skipped)}]`,
          ),
        )
      }
      return await spool.finish(false)
    } finally {
      await spool.close()
    }
  } catch (error) {
    return toErrorResult(call.invocationId, error)
  }
}

const grepExecutor: CapabilityExecutor = async (call, context) => {
  try {
    const args = call.arguments
    const pattern = requiredString(args, 'pattern')
    const path = await resolveExistingPath(requiredString(args, 'path'))
    const limit = optionalPositiveInt(args, 'limit', 100, 100000)
    const job: SearchJob = {
      kind: 'grep',
      pattern,
      path,
      limit,
      multiline: optionalBoolean(args, 'multiline'),
      literal: optionalBoolean(args, 'literal'),
      ignoreCase: optionalBoolean(args, 'ignore_case'),
      include: optionalString(args, 'include'),
      timeoutMillis: call.timeoutMillis,
    }
    const reply = await runSearchInWorker(job, call.timeoutMillis, context.signal)
    if (!reply.ok) {
      return toErrorResult(call.invocationId, replyError(reply))
    }
    if (reply.kind !== 'grep') {
      throw new ToolRunFailureError('search worker returned a mismatched result', 'INTERNAL')
    }
    if (reply.lines.length === 0) {
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
        await spool.write(
          utf8(`\n\n[${limit} results limit reached. Refine the pattern or raise limit.]`),
        )
      }
      if (reply.skipped.length > 0) {
        await spool.write(
          utf8(
            `\n\n[${reply.skipped.length} path(s) could not be searched: ${describeSkipped(reply.skipped)}]`,
          ),
        )
      }
      return await spool.finish(false)
    } finally {
      await spool.close()
    }
  } catch (error) {
    return toErrorResult(call.invocationId, error)
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

/**
 * 在 Worker 线程执行搜索作业。
 *
 * <p>取消与超时都不等待同步扫描自行让出：直接终止 Worker，因此结果是稳定的 cancelled/timeout，而不是“未找到匹配”。正常完成时等待 Worker 退出后再交付结果。
 */
function runSearchInWorker(
  job: SearchJob,
  timeoutMillis: number,
  signal: AbortSignal,
): Promise<SearchReply> {
  return new Promise<SearchReply>((resolve, reject) => {
    if (signal.aborted) {
      reject(new CancelledError())
      return
    }
    const worker = createSearchWorker()
    let settled = false
    let timer: NodeJS.Timeout | null = null
    const finish = (action: () => void, hard: boolean): void => {
      if (settled) {
        return
      }
      settled = true
      if (timer !== null) {
        clearTimeout(timer)
        timer = null
      }
      signal.removeEventListener('abort', onAbort)
      if (hard) {
        // 取消/超时/异常：立即交付结果，不等待可能被同步 FS 阻塞的 terminate。
        void worker.terminate()
        action()
        return
      }
      // 正常完成：扫描已结束，等 Worker 退出后再交付结果。
      worker.once('exit', action)
    }
    const onAbort = (): void => finish(() => reject(new CancelledError()), true)
    worker.once('message', (reply: SearchReply) => finish(() => resolve(reply), false))
    worker.once('error', (error: Error) => finish(() => reject(error), true))
    worker.once('exit', (code: number) => {
      finish(
        () =>
          reject(
            new ToolRunFailureError(
              `the ${job.kind} worker exited unexpectedly (code ${code})`,
              'INTERNAL',
            ),
          ),
        true,
      )
    })
    signal.addEventListener('abort', onAbort, { once: true })
    if (timeoutMillis > 0) {
      timer = setTimeout(
        () =>
          finish(
            () =>
              reject(
                new ToolRunFailureError(
                  `${job.kind} timed out after ${timeoutMillis} milliseconds`,
                  'TIMEOUT',
                ),
              ),
            true,
          ),
        timeoutMillis,
      )
    }
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
