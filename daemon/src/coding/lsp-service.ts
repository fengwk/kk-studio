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
    onExecutionExit?: () => void,
  ): Promise<string> {
    const entry = await this.pool.lease(file, signal, onExecutionExit)
    let requestSent = false
    try {
      const locations = await entry.client.definition(
        file,
        line,
        character,
        signal,
        onExecutionExit,
        () => {
          requestSent = true
        },
      )
      return locations.join('\n')
    } catch (error) {
      if (!requestSent) {
        // 租约已成功，本地失败发生在目标请求发出之前：没有遗留的目标调用。
        onExecutionExit?.()
      }
      throw error
    } finally {
      this.pool.release(entry)
    }
  }

  async workspaceSymbols(
    file: string,
    query: string,
    limit: number,
    signal?: AbortSignal,
    onExecutionExit?: () => void,
  ): Promise<string> {
    const entry = await this.pool.lease(file, signal, onExecutionExit)
    let requestSent = false
    try {
      const symbols = await entry.client.workspaceSymbols(
        query,
        limit,
        signal,
        onExecutionExit,
        () => {
          requestSent = true
        },
      )
      return symbols.join('\n')
    } catch (error) {
      if (!requestSent) {
        onExecutionExit?.()
      }
      throw error
    } finally {
      this.pool.release(entry)
    }
  }

  async javaDecompile(
    file: string,
    target: string,
    signal?: AbortSignal,
    onExecutionExit?: () => void,
  ): Promise<string> {
    const entry = await this.pool.lease(file, signal, onExecutionExit)
    let requestSent = false
    try {
      return await entry.client.javaDecompile(
        target,
        signal,
        onExecutionExit,
        () => {
          requestSent = true
        },
      )
    } catch (error) {
      if (!requestSent) {
        onExecutionExit?.()
      }
      throw error
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
