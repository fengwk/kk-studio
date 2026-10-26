import type { ProcessSupervisor } from '../contracts.js'
import { LspClient } from './lsp-client.js'
import type { LspDiscovery, ResolvedServerConfig } from './lsp-discovery.js'
import { LspBootstrapError, LspCleanupError, LspTimeoutError } from './lsp-error.js'

export interface PoolEntry {
  key: string
  client: LspClient
  inFlight: number
  lastUsedAt: number
}

interface Boot {
  generation: number
  promise: Promise<LspClient>
  resolve: (client: LspClient) => void
  reject: (error: unknown) => void
  /** 引导收尾（成功发布或失败清理）完成；close 必须等它结束才能返回。 */
  startup: Promise<void>
  abort: AbortController
  client?: LspClient
  published: boolean
  cancelled: boolean
  waiters: number
  cleanupFailure: Error | null
  /** 失败启动的受管范围是否已被证明收敛（没有遗留进程）。 */
  converged: boolean
  /**
   * 已取消且不再等待本 boot 的租约。它们只在正向证据出现时确认一次：失败启动清理成功，
   * 或共享 boot 成功发布给其他 owner（目标 RPC 从未发出）。
   */
  exitCallbacks: Array<() => void>
}

interface Retiring {
  entry: PoolEntry
  promise: Promise<void>
  claimed: boolean
  resolve: () => void
  /**
   * 退休没能证明收敛的失败（{@code null} 表示已收敛或尚未结束）。
   *
   * <p>失败时登记必须保留：旧实例可能仍然存活，同 key 的后续租约因此共享同一条失败而绝不重新启动。
   */
  failure: Error | null
}

export interface LspPoolOptions {
  idleTimeout?: number
  shutdownGrace?: number
  initializeTimeout?: number
}

/**
 * Structured reuse fingerprint: a JSON tuple so that array values containing separators can never
 * collide (the previous `,`-join merged distinct configurations).
 */
export function poolKey(root: string, server: ResolvedServerConfig): string {
  return JSON.stringify([
    root,
    server.id,
    server.command,
    server.extensions,
    server.rootMarkers,
    server.firstMatchMarkers,
  ])
}

class WaiterAbortError extends Error {
  readonly isTimeout: boolean
  constructor(reason?: unknown) {
    const isTimeout = reason === 'TIMED_OUT'
    super(isTimeout ? 'Operation timed out' : 'Operation cancelled')
    this.name = isTimeout ? 'TimeoutError' : 'AbortError'
    this.isTimeout = isTimeout
  }
}

function abortError(): Error {
  const error = new Error('Operation cancelled')
  error.name = 'AbortError'
  return error
}

function toError(error: unknown): Error {
  return error instanceof Error ? error : new Error(String(error))
}

/** Race a promise against an AbortSignal, resolving/rejecting with whichever settles first. */
function raceAbort<T>(promise: Promise<T>, signal?: AbortSignal): Promise<T> {
  if (!signal) {
    return promise
  }
  if (signal.aborted) {
    return Promise.reject(new WaiterAbortError(signal.reason))
  }
  return new Promise<T>((resolve, reject) => {
    const onAbort = (): void => {
      reject(new WaiterAbortError(signal.reason))
    }
    signal.addEventListener('abort', onAbort, { once: true })
    promise.then(
      (value) => {
        signal.removeEventListener('abort', onAbort)
        resolve(value)
      },
      (error) => {
        signal.removeEventListener('abort', onAbort)
        reject(error)
      },
    )
  })
}

export class LspClientPool {
  private readonly discovery: LspDiscovery
  private readonly processes: ProcessSupervisor
  private readonly idleTimeout: number
  private readonly shutdownGrace: number
  private readonly initializeTimeout: number

  private readonly clients = new Map<string, PoolEntry>()
  private readonly boots = new Map<string, Boot>()
  private readonly retiring = new Map<string, Retiring>()
  private readonly cleanupFailures: Error[] = []

  private generation = 0
  private closed = false
  private closePromise: Promise<void> | null = null
  private idleTimer: NodeJS.Timeout | null = null

  constructor(
    discovery: LspDiscovery,
    processes: ProcessSupervisor,
    options: LspPoolOptions = {},
  ) {
    this.discovery = discovery
    this.processes = processes
    this.idleTimeout = options.idleTimeout ?? 5 * 60 * 1000
    this.shutdownGrace = options.shutdownGrace ?? 1000
    this.initializeTimeout = options.initializeTimeout ?? 180_000
  }

  async lease(
    file: string,
    signal?: AbortSignal,
    onBootstrapExit?: () => void,
  ): Promise<PoolEntry> {
    let notified = false
    const notifyBootstrapExit = (): void => {
      if (notified) {
        return
      }
      notified = true
      onBootstrapExit?.()
    }
    const registerBootstrapExit = (boot: Boot): void => {
      if (boot.published || boot.converged) {
        // 共享 boot 已成功交付给其他 owner，或失败启动的清理已收敛；本次调用的目标尚未发出。
        notifyBootstrapExit()
      } else if (boot.cleanupFailure === null) {
        // boot 仍在进行或正在清理：等正向证据，不在这里提前确认。
        boot.exitCallbacks.push(notifyBootstrapExit)
      }
    }

    let server: ResolvedServerConfig
    let executable: string
    let root: string
    let key: string
    try {
      server = this.discovery.requireServer(file)
      executable = this.discovery.requireExecutable(server)
      root = this.discovery.workspaceRoot(server, file)
      key = this.key(root, server)
    } catch (error) {
      // 还没进入启动路径，本次调用没有遗留范围。
      notifyBootstrapExit()
      throw error
    }

    while (true) {
      if (signal?.aborted) {
        // 这一轮尚未启动或接管任何进程。
        notifyBootstrapExit()
        throw signal.reason === 'TIMED_OUT'
          ? new LspTimeoutError('Operation timed out')
          : abortError()
      }

      if (this.closed) {
        notifyBootstrapExit()
        throw new Error('LSP service is closed')
      }

      const existing = this.clients.get(key)
      if (existing) {
        if (existing.client.isAlive()) {
          existing.inFlight++
          existing.lastUsedAt = Date.now()
          return existing
        }
        this.clients.delete(key)
      }

      const retirement = this.retiring.get(key)
      if (retirement) {
        if (retirement.failure !== null) {
          // 上次退休没能证明收敛：旧实例可能仍然存活，同 key 绝不重新启动，等待者共享同一条失败。
          throw retirement.failure
        }
        await raceAbort(retirement.promise, signal)
        continue
      }

      let boot = this.boots.get(key)
      if (boot?.cleanupFailure) {
        throw boot.cleanupFailure
      }
      if (boot && boot.cancelled) {
        // A cancelled boot is converging; wait for it to clear, then start a fresh one.
        try {
          await raceAbort(boot.promise.catch(() => {}), signal)
        } catch (error) {
          if (error instanceof WaiterAbortError) {
            // 共享 boot 的清理尚未收敛：现在不能确认退出，只能在清理最终成功时由回调确认。
            registerBootstrapExit(boot)
            throw error.isTimeout
              ? new LspTimeoutError('Operation timed out')
              : abortError()
          }
          throw error
        }
        continue
      }
      if (!boot) {
        boot = this.createBoot()
        this.boots.set(key, boot)
        boot.waiters = 1
        boot.startup = this.startBoot(key, boot, server, root, executable)
      } else {
        boot.waiters++
      }

      let client: LspClient
      try {
        client = await raceAbort(boot.promise, signal)
      } catch (error) {
        if (error instanceof WaiterAbortError) {
          boot.waiters--
          const stopError = error.isTimeout
            ? new LspTimeoutError('Operation timed out')
            : abortError()

          if (boot.waiters <= 0 && !boot.published) {
            // No remaining waiter: cancel the boot so the late process is cleaned up.
            boot.cancelled = true
            boot.abort.abort()
            // 清理最终成功时才确认退出；等待期间清理失败则绝不确认。
            registerBootstrapExit(boot)
            await boot.promise.catch(() => {})
            if (boot.cleanupFailure !== null) {
              throw boot.cleanupFailure
            }
          } else {
            // 共享 boot 可能继续服务其它等待者；在它运行期间不能确认退出。
            registerBootstrapExit(boot)
          }
          throw stopError
        }
        // boot 失败、被 close 拒绝或在并发收尾：只在其正向证据出现时确认，绝不在进行中提前确认。
        registerBootstrapExit(boot)
        throw error
      }

      if (this.closed) {
        // boot 已成功发布，但本次租约在 close 后返回；目标尚未发出，随共享客户端的物理收尾确认。
        this.followClientCleanup(client, notifyBootstrapExit)
        throw new Error('LSP service is closed')
      }
      const entry = this.clients.get(key)
      if (!entry || entry.client !== client) {
        this.followClientCleanup(client, notifyBootstrapExit)
        throw new Error('LSP service is closed')
      }
      entry.inFlight++
      entry.lastUsedAt = Date.now()
      return entry
    }
  }

  client(file: string): LspClient | undefined {
    const server = this.discovery.server(file)
    if (!server) {
      return undefined
    }
    const root = this.discovery.workspaceRoot(server, file)
    const key = this.key(root, server)
    const entry = this.clients.get(key)
    if (entry && entry.client.isAlive()) {
      return entry.client
    }
    return undefined
  }

  release(entry: PoolEntry): void {
    entry.inFlight--
    if (this.clients.get(entry.key) === entry) {
      entry.lastUsedAt = Date.now()
      this.armIdleCheck()
    }
  }

  /**
   * 已发布客户端在 close 期间不再返回租约：目标未发出，只在同一个 cached stop 成功时确认。
   * 失败由 close 的 typed cleanup 错误暴露；这里不能把失败误报成正向证据。
   */
  private followClientCleanup(client: LspClient, onSuccess: () => void): void {
    void client.stop(this.shutdownGrace).then(onSuccess, () => {})
  }

  activeCount(): number {
    return this.clients.size
  }

  close(): Promise<void> {
    if (this.closePromise !== null) {
      return this.closePromise
    }
    this.closed = true
    this.closePromise = this.finishClose()
    return this.closePromise
  }

  private async finishClose(): Promise<void> {
    this.generation++

    if (this.idleTimer) {
      clearTimeout(this.idleTimer)
      this.idleTimer = null
    }

    const stopping: LspClient[] = []
    for (const entry of this.clients.values()) {
      stopping.push(entry.client)
    }
    this.clients.clear()

    const pendingBoots = Array.from(this.boots.values())
    this.boots.clear()
    const bootTasks: Promise<void>[] = []
    for (const boot of pendingBoots) {
      boot.cancelled = true
      boot.abort.abort()
      boot.reject(new Error('LSP service is closed'))
      // launch 可能尚未返回；必须等引导自己的清理结束，不能在进程还会出现时就认为 close 完成。
      bootTasks.push(boot.startup)
      if (boot.client) {
        stopping.push(boot.client)
      }
    }

    const retirements = Array.from(this.retiring.values())
    const tasks: Promise<void>[] = []
    for (const retirement of retirements) {
      tasks.push(this.stopRetirement(retirement))
    }
    for (const client of stopping) {
      tasks.push(this.stopClient(client))
    }
    // 引导的预期失败由 startBoot 自己记录；这里等待完成，并收集任何意外拒绝，避免 stop 错误在 close 返回后才出现。
    const results = await Promise.allSettled([...tasks, ...bootTasks])
    for (const result of results) {
      if (result.status === 'rejected') {
        this.recordCleanupFailure(result.reason)
      }
    }

    if (this.cleanupFailures.length > 0) {
      throw new LspCleanupError(this.cleanupFailures.slice())
    }
  }

  private createBoot(): Boot {
    let resolve!: (client: LspClient) => void
    let reject!: (error: unknown) => void
    const promise = new Promise<LspClient>((res, rej) => {
      resolve = res
      reject = rej
    })
    return {
      generation: this.generation,
      promise,
      resolve,
      reject,
      startup: Promise.resolve(),
      abort: new AbortController(),
      published: false,
      cancelled: false,
      waiters: 0,
      cleanupFailure: null,
      converged: false,
      exitCallbacks: [],
    }
  }

  private async startBoot(
    key: string,
    boot: Boot,
    server: ResolvedServerConfig,
    root: string,
    executable: string,
  ): Promise<void> {
    let client: LspClient | null = null
    try {
      client = await LspClient.launch(server, root, executable, this.processes)
      boot.client = client
      this.requireUsable(boot)

      await client.initialize(this.initializeTimeout, boot.abort.signal)
      this.requireUsable(boot)

      const entry: PoolEntry = {
        key,
        client,
        inFlight: 0,
        lastUsedAt: Date.now(),
      }
      this.clients.set(key, entry)
      if (this.boots.get(key) === boot) {
        this.boots.delete(key)
      }
      boot.published = true
      boot.resolve(client)
      // 对已取消的等待者，成功发布就是正向证据：目标 RPC 从未发出，引导已交付给其他 owner。
      // 立即清空回调，不等待共享 server 停止，也不保留长期 listener。
      this.drainBootExitCallbacks(boot)
    } catch (error) {
      if (client) {
        try {
          await client.stop(this.shutdownGrace)
          this.markBootConverged(boot)
        } catch (cleanupError) {
          // Preserve the release failure; surface it at close and on the rejected boot. The cleanup could not
          // be proven converged, so the old instance may still be alive: keep the boot registration so that a
          // later lease for the same key shares this failure instead of launching a second server.
          const failure = toError(cleanupError)
          this.recordCleanupFailure(failure)
          boot.cleanupFailure = new LspBootstrapError(
            'LSP client startup failed and cleanup failed',
            {
              cause: new AggregateError(
                [error, failure], 'LSP client startup failed and cleanup failed',
              ),
              converged: false,
            },
          )
          boot.exitCallbacks.length = 0
          boot.reject(boot.cleanupFailure)
          return
        }
      } else {
        // 启动在拿到客户端之前失败：只有 launch 明确给出的收敛结论才可作为退出证据。
        const converged = error instanceof LspBootstrapError && error.converged
        if (!converged) {
          const failure =
            error instanceof LspBootstrapError
              ? error
              : new LspBootstrapError('LSP client startup failed', {
                cause: error,
                converged: false,
              })
          this.recordCleanupFailure(failure)
          boot.cleanupFailure = failure
          boot.exitCallbacks.length = 0
          boot.reject(failure)
          return
        }
        this.markBootConverged(boot)
      }
      if (this.boots.get(key) === boot) {
        this.boots.delete(key)
      }
      boot.reject(error)
    }
  }

  /**
   * 记录失败启动的受管范围已被证明收敛，并唤醒已取消的等待者。
   *
   * <p>回调只代表正向证据，不代表本次调用一定已经结束；调用方仍须等自己的执行体与收尾结束。
   */
  private markBootConverged(boot: Boot): void {
    if (boot.converged) {
      return
    }
    boot.converged = true
    this.drainBootExitCallbacks(boot)
  }

  /** 每个等待者自己的 `notified` guard 保证即使 drain 路径重叠也只确认一次。 */
  private drainBootExitCallbacks(boot: Boot): void {
    for (const callback of boot.exitCallbacks.splice(0)) {
      callback()
    }
  }

  /** Startup owns its registration until the process has stopped, including cancellation. */
  private requireUsable(boot: Boot): void {
    if (this.closed || boot.generation !== this.generation || boot.cancelled) {
      throw new Error('LSP client startup cancelled')
    }
  }

  private armIdleCheck(): void {
    if (this.idleTimer) {
      clearTimeout(this.idleTimer)
      this.idleTimer = null
    }
    if (this.closed || this.clients.size === 0) {
      return
    }

    const now = Date.now()
    let earliest = Infinity
    for (const entry of this.clients.values()) {
      if (entry.inFlight === 0) {
        earliest = Math.min(earliest, entry.lastUsedAt + this.idleTimeout)
      }
    }

    if (earliest === Infinity) {
      return
    }

    const delay = Math.max(0, earliest - now)
    this.idleTimer = setTimeout(() => {
      this.evictIdle()
    }, delay)
    this.idleTimer.unref?.()
  }

  private evictIdle(): void {
    if (this.closed) {
      return
    }
    const now = Date.now()
    const expired: Retiring[] = []

    for (const entry of this.clients.values()) {
      if (entry.inFlight === 0 && now - entry.lastUsedAt >= this.idleTimeout) {
        let resolveDone!: () => void
        const donePromise = new Promise<void>((resolve) => {
          resolveDone = resolve
        })
        const retirement: Retiring = {
          entry,
          promise: donePromise,
          claimed: false,
          resolve: resolveDone,
          failure: null,
        }
        expired.push(retirement)
        this.clients.delete(entry.key)
        this.retiring.set(entry.key, retirement)
      }
    }

    for (const retirement of expired) {
      void this.stopRetirement(retirement)
    }

    this.armIdleCheck()
  }

  /** 同一个物理 stop 可能被 boot 与 close 同时观察；按错误对象身份避免重复报告。 */
  private recordCleanupFailure(error: unknown): void {
    const failure = toError(error)
    if (!this.cleanupFailures.includes(failure)) {
      this.cleanupFailures.push(failure)
    }
  }

  private async stopClient(client: LspClient): Promise<void> {
    try {
      await client.stop(this.shutdownGrace)
    } catch (error) {
      this.recordCleanupFailure(error)
    }
  }

  private async stopRetirement(retirement: Retiring): Promise<void> {
    if (retirement.claimed) {
      // 已有调用负责物理 stop；其它关闭者仍须等待它记录失败并完成退休。
      return retirement.promise
    }
    retirement.claimed = true
    try {
      await retirement.entry.client.stop(this.shutdownGrace)
      this.retiring.delete(retirement.entry.key)
    } catch (error) {
      // 退休没能证明收敛：旧实例可能仍然存活，因此保留登记并共享同一条失败，同 key 绝不重新启动。
      const failure = toError(error)
      retirement.failure = failure
      this.recordCleanupFailure(failure)
    } finally {
      // 等待者必须被唤醒：它们随后看到失败的登记并以同一条失败终止，而不是重新启动一个服务器。
      retirement.resolve()
    }
  }

  private key(root: string, server: ResolvedServerConfig): string {
    return poolKey(root, server)
  }
}
