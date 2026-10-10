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
  client?: LspClient
  reject: (err: unknown) => void
}

interface Retiring {
  entry: PoolEntry
  promise: Promise<void>
  claimed: boolean
  resolve: () => void
}

export interface LspPoolOptions {
  idleTimeout?: number
  shutdownGrace?: number
  initializeTimeout?: number
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

  async lease(file: string): Promise<PoolEntry> {
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
        await retirement.promise
        continue
      }

      let boot = this.boots.get(key)
      if (!boot) {
        let resolveClient!: (client: LspClient) => void
        let rejectClient!: (err: unknown) => void
        const bootPromise = new Promise<LspClient>((resolve, reject) => {
          resolveClient = resolve
          rejectClient = reject
        })

        boot = {
          generation: this.generation,
          promise: bootPromise,
          reject: rejectClient,
        }
        this.boots.set(key, boot)

        this.startBoot(key, boot, server, root, executable, resolveClient, rejectClient)
      }

      const client = await boot.promise

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
      boot.reject(new Error('LSP service is closed'))
      if (boot.client) {
        stopping.push(boot.client)
      }
    }

    const retirements = Array.from(this.retiring.values())
    const stopPromises: Promise<void>[] = []
    for (const r of retirements) {
      stopPromises.push(this.stopRetirement(r))
    }

    for (const client of stopping) {
      stopPromises.push(client.stop(this.shutdownGrace).catch(() => {}))
    }

    await Promise.all(stopPromises)
  }

  private async startBoot(
    key: string,
    boot: Boot,
    server: ResolvedServerConfig,
    root: string,
    executable: string,
    resolve: (client: LspClient) => void,
    reject: (err: unknown) => void,
  ): Promise<void> {
    let client: LspClient | null = null
    let published = false
    try {
      client = await LspClient.launch(server, root, executable, this.processes)
      boot.client = client

      if (this.closed || boot.generation !== this.generation) {
        this.boots.delete(key)
        throw new Error('LSP client startup cancelled')
      }

      await client.initialize(this.initializeTimeout)

      if (this.closed || boot.generation !== this.generation) {
        this.boots.delete(key)
        throw new Error('LSP client startup cancelled')
      }

      const entry: PoolEntry = {
        key,
        client,
        inFlight: 0,
        lastUsedAt: Date.now(),
      }
      this.clients.set(key, entry)
      this.boots.delete(key)
      published = true
      resolve(client)
    } catch (error) {
      if (client && !published) {
        await client.stop(this.shutdownGrace).catch(() => {})
      }
      this.boots.delete(key)
      reject(error)
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
        }
        expired.push(retirement)
        this.clients.delete(entry.key)
        this.retiring.set(entry.key, retirement)
      }
    }

    for (const retirement of expired) {
      this.stopRetirement(retirement)
    }

    this.armIdleCheck()
  }

  private async stopRetirement(retirement: Retiring): Promise<void> {
    if (retirement.claimed) {
      return
    }
    retirement.claimed = true
    try {
      await retirement.entry.client.stop(this.shutdownGrace)
    } catch {
    } finally {
      this.retiring.delete(retirement.entry.key)
      retirement.resolve()
    }
  }

  private key(root: string, server: ResolvedServerConfig): string {
    return (
      root +
      '\n' +
      server.id +
      '\n' +
      server.command.join(',') +
      server.extensions.join(',') +
      server.rootMarkers.join(',') +
      server.firstMatchMarkers.join(',')
    )
  }
}
