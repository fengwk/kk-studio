import type { ProcessSupervisor } from '../contracts.js'
import { LspClient } from './lsp-client.js'
import type { LspDiscovery, ResolvedServerConfig } from './lsp-discovery.js'

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
  abort: AbortController
  client?: LspClient
  published: boolean
  cancelled: boolean
  waiters: number
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

function abortError(): Error {
  const error = new Error('Operation cancelled')
  error.name = 'AbortError'
  return error
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError'
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
    return Promise.reject(abortError())
  }
  return new Promise<T>((resolve, reject) => {
    const onAbort = (): void => {
      reject(abortError())
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
    this.initializeTimeout = options.initializeTimeout ?? 60_000
  }

  async lease(file: string, signal?: AbortSignal): Promise<PoolEntry> {
    const server = this.discovery.requireServer(file)
    const executable = this.discovery.requireExecutable(server)
    const root = this.discovery.workspaceRoot(server, file)
    const key = this.key(root, server)

    while (true) {
      if (this.closed) {
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
      if (boot && boot.cancelled) {
        // A cancelled boot is converging; wait for it to clear, then start a fresh one.
        await boot.promise.catch(() => {})
        continue
      }
      if (!boot) {
        boot = this.createBoot()
        this.boots.set(key, boot)
        boot.waiters = 1
        void this.startBoot(key, boot, server, root, executable)
      } else {
        boot.waiters++
      }

      let client: LspClient
      try {
        client = await raceAbort(boot.promise, signal)
      } catch (error) {
        if (isAbortError(error)) {
          boot.waiters--
          if (boot.waiters <= 0 && !boot.published) {
            // No remaining waiter: cancel the boot so the late process is cleaned up.
            boot.cancelled = true
            boot.abort.abort()
          }
        }
        throw error
      }

      if (this.closed) {
        throw new Error('LSP service is closed')
      }
      const entry = this.clients.get(key)
      if (!entry || entry.client !== client) {
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

  activeCount(): number {
    return this.clients.size
  }

  async close(): Promise<void> {
    if (this.closed) {
      return
    }
    this.closed = true
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
    for (const boot of pendingBoots) {
      boot.cancelled = true
      boot.abort.abort()
      boot.reject(new Error('LSP service is closed'))
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
    await Promise.all(tasks)

    if (this.cleanupFailures.length > 0) {
      throw new Error(
        `LSP client pool failed to release all resources (${this.cleanupFailures.length} cleanup failure(s))`,
      )
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
      abort: new AbortController(),
      published: false,
      cancelled: false,
      waiters: 0,
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
      this.requireUsable(key, boot)

      await client.initialize(this.initializeTimeout, boot.abort.signal)
      this.requireUsable(key, boot)

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
    } catch (error) {
      if (client) {
        try {
          await client.stop(this.shutdownGrace)
        } catch (cleanupError) {
          // Preserve the release failure; surface it at close and on the rejected boot. The cleanup could not
          // be proven converged, so the old instance may still be alive: keep the boot registration so that a
          // later lease for the same key shares this failure instead of launching a second server.
          const failure = toError(cleanupError)
          this.cleanupFailures.push(failure)
          boot.reject(
            new AggregateError([error, failure], 'LSP client startup failed and cleanup failed'),
          )
          return
        }
      }
      if (this.boots.get(key) === boot) {
        this.boots.delete(key)
      }
      boot.reject(error)
    }
  }

  /** Throws when the boot may no longer publish; the caller removes the registration and stops. */
  private requireUsable(key: string, boot: Boot): void {
    if (this.closed || boot.generation !== this.generation || boot.cancelled) {
      if (this.boots.get(key) === boot) {
        this.boots.delete(key)
      }
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

  private async stopClient(client: LspClient): Promise<void> {
    try {
      await client.stop(this.shutdownGrace)
    } catch (error) {
      this.cleanupFailures.push(toError(error))
    }
  }

  private async stopRetirement(retirement: Retiring): Promise<void> {
    if (retirement.claimed) {
      return
    }
    retirement.claimed = true
    try {
      await retirement.entry.client.stop(this.shutdownGrace)
      this.retiring.delete(retirement.entry.key)
    } catch (error) {
      // 退休没能证明收敛：旧实例可能仍然存活，因此保留登记并共享同一条失败，同 key 绝不重新启动。
      const failure = toError(error)
      retirement.failure = failure
      this.cleanupFailures.push(failure)
    } finally {
      // 等待者必须被唤醒：它们随后看到失败的登记并以同一条失败终止，而不是重新启动一个服务器。
      retirement.resolve()
    }
  }

  private key(root: string, server: ResolvedServerConfig): string {
    return poolKey(root, server)
  }
}
