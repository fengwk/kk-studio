import { mkdtemp, readFile, rm, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { PassThrough } from 'node:stream'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ProcessSupervisor } from '../src/contracts.js'
import { getOutboundNetwork, type OutboundResponse } from '../src/network.js'
import { DOWNLOAD_FAILED_MESSAGE, DOWNLOAD_FINALIZE_MESSAGE, DOWNLOAD_IDLE_TIMEOUT_MILLIS, productionDependencies } from '../src/update.js'

const cleanupFailures = vi.hoisted(() => new Set<string>())

vi.mock('node:fs/promises', async importOriginal => {
  const original = await importOriginal<typeof import('node:fs/promises')>()
  return {
    ...original,
    async rm(...args: Parameters<typeof original.rm>) {
      if (typeof args[0] === 'string' && cleanupFailures.has(args[0])) {
        throw new Error('partial file cleanup failed')
      }
      return original.rm(...args)
    },
  }
})

const DOWNLOAD_URL = 'https://github.com/fengwk/kk-studio/releases/download/v1.1.1/artifact.tar.gz'
const REDIRECTED_URL = 'https://github.com/redirected-artifact.tar.gz'
// 固定去敏文案是行为契约：调用方必须能区分停滞超时与外部取消。
const NO_PROGRESS_MESSAGE = 'official artifact download timed out after 300 seconds without progress'
const CANCELLED_MESSAGE = 'official artifact download was cancelled'

const unusedSupervisor: ProcessSupervisor = {
  async start(): Promise<never> {
    throw new Error('process supervisor is not used by download tests')
  },
  async close(): Promise<void> {},
}

type Outcome = { readonly status: 'fulfilled' } | { readonly status: 'rejected'; readonly error: unknown }

function observe(promise: Promise<void>): Promise<Outcome> {
  return promise.then(
    () => ({ status: 'fulfilled' }),
    error => ({ status: 'rejected', error }),
  )
}

async function expectRejected(outcome: Promise<Outcome>, message: string): Promise<void> {
  const result = await outcome
  expect(result.status).toBe('rejected')
  if (result.status === 'rejected') {
    expect(result.error).toBeInstanceOf(Error)
    expect((result.error as Error).message).toBe(message)
  }
}

/** 等待 I/O 与流回调落地；不推进 fake timers，因此不会掩盖真实等待。 */
async function flushIo(): Promise<void> {
  await new Promise<void>(resolve => setImmediate(resolve))
  await new Promise<void>(resolve => setImmediate(resolve))
}

function abortReason(signal: AbortSignal): Error {
  return signal.reason instanceof Error
    ? signal.reason
    : new DOMException('This operation was aborted', 'AbortError')
}

function responseFor(
  body: PassThrough,
  signal: AbortSignal,
  dispose: () => Promise<void>,
  options: { statusCode?: number; headers?: OutboundResponse['headers'] } = {},
): OutboundResponse {
  const abort = (): void => {
    body.destroy(abortReason(signal))
  }
  if (signal.aborted) {
    abort()
  } else {
    signal.addEventListener('abort', abort, { once: true })
  }
  return {
    statusCode: options.statusCode ?? 200,
    statusMessage: options.statusCode === undefined ? 'OK' : 'Redirect',
    headers: options.headers ?? {},
    body,
    async dispose(): Promise<void> {
      signal.removeEventListener('abort', abort)
      body.destroy()
      await dispose()
    },
  }
}

interface ControlledDownload {
  readonly body: PassThrough
  readonly dispose: ReturnType<typeof vi.fn>
  deliver(text: string): void
  finish(): void
}

function controlledDownload(body: PassThrough, dispose: ReturnType<typeof vi.fn>): ControlledDownload {
  return {
    body,
    dispose,
    deliver(text: string): void {
      if (!body.destroyed) {
        body.write(text)
      }
    },
    finish(): void {
      if (!body.destroyed) {
        body.end()
      }
    },
  }
}

function installImmediateDownload(
  disposeFailure?: Error,
  headers: OutboundResponse['headers'] = {},
): ControlledDownload {
  const body = new PassThrough()
  const dispose = vi.fn(async () => {
    if (disposeFailure !== undefined) {
      throw disposeFailure
    }
  })
  vi.spyOn(getOutboundNetwork(), 'openResponse').mockImplementation(async (_url, _init, signal) =>
    responseFor(body, signal, dispose, { headers }),
  )
  return controlledDownload(body, dispose)
}

function installRedirectingDownload(): ControlledDownload {
  const body = new PassThrough()
  const dispose = vi.fn(async () => undefined)
  vi.spyOn(getOutboundNetwork(), 'openResponse').mockImplementation(async (url, _init, signal) => {
    if (url === DOWNLOAD_URL) {
      return responseFor(new PassThrough(), signal, dispose, {
        statusCode: 302,
        headers: { location: '/redirected-artifact.tar.gz' },
      })
    }
    if (url === REDIRECTED_URL) {
      return responseFor(body, signal, dispose)
    }
    throw new Error('unexpected download URL')
  })
  return controlledDownload(body, dispose)
}

interface PendingHeaders {
  readonly body: PassThrough
  readonly dispose: ReturnType<typeof vi.fn>
  release(): void
}

function installPendingHeaders(): PendingHeaders {
  const body = new PassThrough()
  const dispose = vi.fn(async () => undefined)
  let release!: () => void
  const gate = new Promise<void>(resolve => {
    release = resolve
  })
  vi.spyOn(getOutboundNetwork(), 'openResponse').mockImplementation(
    (_url, _init, signal) =>
      new Promise<OutboundResponse>((resolve, reject) => {
        const onAbort = (): void => {
          reject(abortReason(signal))
        }
        if (signal.aborted) {
          onAbort()
          return
        }
        signal.addEventListener('abort', onAbort, { once: true })
        void gate.then(() => {
          signal.removeEventListener('abort', onAbort)
          if (signal.aborted) {
            reject(abortReason(signal))
            return
          }
          resolve(responseFor(body, signal, dispose))
        })
      }),
  )
  return { body, dispose, release: () => release() }
}

describe('official artifact download progress budget', () => {
  let directory: string
  let destination: string

  beforeEach(async () => {
    directory = await mkdtemp(path.join(tmpdir(), 'kk-update-download-'))
    destination = path.join(directory, 'artifact.tar.gz')
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    // Node 的 AbortSignal.timeout 使用 native 时钟，普通 fake timers 无法推进。基线实现依赖它，
    // 这里以同语义的可控时钟替换，确保红测验证 300 秒硬截止而不是真实等待或编译错误。
    vi.spyOn(AbortSignal, 'timeout').mockImplementation(millis => {
      const controller = new AbortController()
      setTimeout(() => {
        controller.abort(new DOMException('The operation was aborted due to timeout', 'TimeoutError'))
      }, millis)
      return controller.signal
    })
  })

  afterEach(async () => {
    vi.clearAllTimers()
    vi.restoreAllMocks()
    vi.useRealTimers()
    cleanupFailures.clear()
    await rm(directory, { recursive: true, force: true })
  })

  it('continues beyond 300 cumulative seconds while every data gap stays below the budget', async () => {
    const download = installImmediateDownload()
    const outcome = observe(
      productionDependencies(unusedSupervisor).fetchTo(
        DOWNLOAD_URL,
        destination,
        new AbortController().signal,
        1024,
      ),
    )
    await flushIo()

    await vi.advanceTimersByTimeAsync(200_000)
    download.deliver('a')
    await flushIo()
    await vi.advanceTimersByTimeAsync(200_000)
    download.deliver('b')
    await flushIo()
    await vi.advanceTimersByTimeAsync(100_000)
    download.deliver('c')
    download.finish()

    await expect(outcome).resolves.toEqual({ status: 'fulfilled' })
    expect(await readFile(destination, 'utf8')).toBe('abc')
    expect(download.dispose).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('follows a redirect and downloads the final response body', async () => {
    const download = installRedirectingDownload()
    const outcome = observe(
      productionDependencies(unusedSupervisor).fetchTo(
        DOWNLOAD_URL,
        destination,
        new AbortController().signal,
        1024,
      ),
    )
    await flushIo()
    download.deliver('redirected-body')
    download.finish()

    await expect(outcome).resolves.toEqual({ status: 'fulfilled' })
    expect(await readFile(destination, 'utf8')).toBe('redirected-body')
    // 每个响应（含重定向响应）都必须独立释放。
    expect(download.dispose).toHaveBeenCalledTimes(2)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('reports only non-empty archive body chunks with a valid declared total', async () => {
    const download = installImmediateDownload(undefined, { 'content-length': '6' })
    const progress: Array<{ downloadedBytes: number; totalBytes: number | null }> = []
    const outcome = observe(
      productionDependencies(unusedSupervisor).fetchTo(
        DOWNLOAD_URL,
        destination,
        new AbortController().signal,
        1024,
        (downloadedBytes, totalBytes) => {
          progress.push({ downloadedBytes, totalBytes })
        },
      ),
    )
    await flushIo()
    download.deliver('ab')
    await flushIo()
    download.deliver('cdef')
    download.finish()

    await expect(outcome).resolves.toEqual({ status: 'fulfilled' })
    expect(progress).toEqual([
      { downloadedBytes: 2, totalBytes: 6 },
      { downloadedBytes: 6, totalBytes: 6 },
    ])
    expect(vi.getTimerCount()).toBe(0)
  })

  it('times out after 300 seconds without body progress, then disposes and removes the partial file', async () => {
    const download = installImmediateDownload()
    const outcome = observe(
      productionDependencies(unusedSupervisor).fetchTo(
        DOWNLOAD_URL,
        destination,
        new AbortController().signal,
        1024,
      ),
    )
    await flushIo()
    download.deliver('partial')
    await flushIo()

    await vi.advanceTimersByTimeAsync(DOWNLOAD_IDLE_TIMEOUT_MILLIS - 1)
    expect(download.dispose).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)

    await expectRejected(outcome, NO_PROGRESS_MESSAGE)
    expect(download.body.destroyed).toBe(true)
    expect(download.dispose).toHaveBeenCalledTimes(1)
    await expect(stat(destination)).rejects.toMatchObject({ code: 'ENOENT' })
    expect(vi.getTimerCount()).toBe(0)
  })

  it('keeps the fixed timeout message when partial cleanup also fails', async () => {
    cleanupFailures.add(destination)
    const download = installImmediateDownload()
    const outcome = observe(
      productionDependencies(unusedSupervisor).fetchTo(
        DOWNLOAD_URL,
        destination,
        new AbortController().signal,
        1024,
      ),
    )
    await flushIo()
    download.deliver('partial')
    await flushIo()
    await vi.advanceTimersByTimeAsync(DOWNLOAD_IDLE_TIMEOUT_MILLIS)

    const result = await outcome
    expect(result.status).toBe('rejected')
    if (result.status === 'rejected') {
      expect(result.error).toBeInstanceOf(Error)
      expect((result.error as Error).message).toBe(NO_PROGRESS_MESSAGE)
      const cause = (result.error as Error).cause
      expect(cause).toBeInstanceOf(AggregateError)
      if (cause instanceof AggregateError) {
        expect(cause.errors[1]).toMatchObject({ message: 'partial file cleanup failed' })
      }
    }
    expect(download.dispose).toHaveBeenCalledTimes(1)
    expect((await stat(destination)).size).toBe(7)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('applies the progress budget while waiting for the first response headers', async () => {
    const pending = installPendingHeaders()
    const outcome = observe(
      productionDependencies(unusedSupervisor).fetchTo(
        DOWNLOAD_URL,
        destination,
        new AbortController().signal,
        1024,
      ),
    )
    await flushIo()

    await vi.advanceTimersByTimeAsync(DOWNLOAD_IDLE_TIMEOUT_MILLIS - 1)
    expect(pending.dispose).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)

    await expectRejected(outcome, NO_PROGRESS_MESSAGE)
    expect(pending.dispose).not.toHaveBeenCalled()
    await expect(stat(destination)).rejects.toMatchObject({ code: 'ENOENT' })
    expect(vi.getTimerCount()).toBe(0)
    pending.release()
  })

  it('reports external cancellation while waiting for headers as cancellation, not timeout', async () => {
    installPendingHeaders()
    const controller = new AbortController()
    const outcome = observe(
      productionDependencies(unusedSupervisor).fetchTo(DOWNLOAD_URL, destination, controller.signal, 1024),
    )
    await flushIo()
    controller.abort()

    await expectRejected(outcome, CANCELLED_MESSAGE)
    await expect(stat(destination)).rejects.toMatchObject({ code: 'ENOENT' })
    expect(vi.getTimerCount()).toBe(0)
  })

  it('reports external cancellation during the body as cancellation and closes the stream', async () => {
    const download = installImmediateDownload()
    const controller = new AbortController()
    const outcome = observe(
      productionDependencies(unusedSupervisor).fetchTo(DOWNLOAD_URL, destination, controller.signal, 1024),
    )
    await flushIo()
    download.deliver('partial')
    await flushIo()
    controller.abort()

    await expectRejected(outcome, CANCELLED_MESSAGE)
    expect(download.body.destroyed).toBe(true)
    expect(download.dispose).toHaveBeenCalledTimes(1)
    await expect(stat(destination)).rejects.toMatchObject({ code: 'ENOENT' })
    expect(vi.getTimerCount()).toBe(0)
  })

  it('preserves a non-cancel body error while still cleaning up', async () => {
    const download = installImmediateDownload()
    const outcome = observe(
      productionDependencies(unusedSupervisor).fetchTo(
        DOWNLOAD_URL,
        destination,
        new AbortController().signal,
        1024,
      ),
    )
    await flushIo()
    download.deliver('partial')
    await flushIo()
    download.body.destroy(new Error('network read failed'))

    await expectRejected(outcome, DOWNLOAD_FAILED_MESSAGE)
    expect(download.dispose).toHaveBeenCalledTimes(1)
    await expect(stat(destination)).rejects.toMatchObject({ code: 'ENOENT' })
    expect(vi.getTimerCount()).toBe(0)
  })

  it('preserves a dispose failure and still removes the downloaded file', async () => {
    const download = installImmediateDownload(new Error('dispatcher close failed'))
    const outcome = observe(
      productionDependencies(unusedSupervisor).fetchTo(
        DOWNLOAD_URL,
        destination,
        new AbortController().signal,
        1024,
      ),
    )
    await flushIo()
    download.deliver('complete')
    download.finish()

    await expectRejected(outcome, DOWNLOAD_FINALIZE_MESSAGE)
    expect(download.dispose).toHaveBeenCalledTimes(1)
    await expect(stat(destination)).rejects.toMatchObject({ code: 'ENOENT' })
    expect(vi.getTimerCount()).toBe(0)
  })

  it('does not turn slow response disposal into a download timeout after the body is complete', async () => {
    const download = installImmediateDownload()
    let releaseDispose!: () => void
    const disposal = new Promise<void>(resolve => {
      releaseDispose = resolve
    })
    let markDisposing!: () => void
    const disposing = new Promise<void>(resolve => {
      markDisposing = resolve
    })
    download.dispose.mockImplementationOnce(async () => {
      markDisposing()
      await disposal
      throw new Error('dispatcher close failed')
    })
    const outcome = observe(
      productionDependencies(unusedSupervisor).fetchTo(
        DOWNLOAD_URL,
        destination,
        new AbortController().signal,
        1024,
      ),
    )
    await flushIo()
    download.deliver('complete')
    download.finish()
    await disposing

    await vi.advanceTimersByTimeAsync(DOWNLOAD_IDLE_TIMEOUT_MILLIS + 1)
    releaseDispose()

    await expectRejected(outcome, DOWNLOAD_FINALIZE_MESSAGE)
    expect(download.dispose).toHaveBeenCalledTimes(1)
    await expect(stat(destination)).rejects.toMatchObject({ code: 'ENOENT' })
    expect(vi.getTimerCount()).toBe(0)
  })
})
