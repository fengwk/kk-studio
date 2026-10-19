import type { ProcessSupervisor } from '../contracts.js'
import { LspDiscovery, type LspSupport } from './lsp-discovery.js'
import { LspClientPool, type LspPoolOptions } from './lsp-pool.js'

export class LspService {
  private readonly discovery: LspDiscovery
  private readonly pool: LspClientPool

  constructor(
    discovery: LspDiscovery,
    processes: ProcessSupervisor,
    options: LspPoolOptions = {},
  ) {
    this.discovery = discovery
    this.pool = new LspClientPool(discovery, processes, options)
  }

  support(file: string): LspSupport {
    return this.discovery.support(file)
  }

  async gotoDefinition(
    file: string,
    line: number,
    character: number,
    signal?: AbortSignal,
  ): Promise<string> {
    const entry = await this.pool.lease(file, signal)
    try {
      const locations = await entry.client.definition(file, line, character, signal)
      return locations.join('\n')
    } finally {
      this.pool.release(entry)
    }
  }

  async workspaceSymbols(
    file: string,
    query: string,
    limit: number,
    signal?: AbortSignal,
  ): Promise<string> {
    const entry = await this.pool.lease(file, signal)
    try {
      const symbols = await entry.client.workspaceSymbols(query, limit, signal)
      return symbols.join('\n')
    } finally {
      this.pool.release(entry)
    }
  }

  async javaDecompile(
    file: string,
    target: string,
    signal?: AbortSignal,
  ): Promise<string> {
    const entry = await this.pool.lease(file, signal)
    try {
      return await entry.client.javaDecompile(target, signal)
    } finally {
      this.pool.release(entry)
    }
  }

  fileChanged(file: string): void {
    try {
      const client = this.pool.client(file)
      if (client) {
        client.refresh(file)
      }
    } catch {}
  }

  async close(): Promise<void> {
    await this.pool.close()
  }

  activeClientCount(): number {
    return this.pool.activeCount()
  }
}
