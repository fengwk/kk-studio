import fs from 'node:fs'
import path from 'node:path'
import { Transform } from 'node:stream'
import { fileURLToPath, pathToFileURL } from 'node:url'
import {
  CancellationTokenSource,
  createMessageConnection,
  StreamMessageReader,
  StreamMessageWriter,
  type MessageConnection,
} from 'vscode-jsonrpc/node'
import type { ManagedProcess, ProcessExit, ProcessSupervisor } from '../contracts.js'
import { TextFileCodec } from './lsp-codec.js'
import type { ResolvedServerConfig } from './lsp-discovery.js'
import { LspError, LspTimeoutError } from './lsp-error.js'

/**
 * Pre-header budget: non-LSP banner bytes are tolerated only up to this bound. Past it the stream
 * fails closed instead of buffering unbounded garbage in memory. A fragmented `Content-Length`
 * header arriving across chunks still fits comfortably inside this budget.
 */
export const MAX_BANNER_BYTES = 8 * 1024

export class GarbageBannerFilter extends Transform {
  private foundFirstHeader = false
  private buffer = Buffer.alloc(0)

  override _transform(chunk: Buffer, _encoding: BufferEncoding, callback: (error?: Error) => void): void {
    if (this.foundFirstHeader) {
      this.push(chunk)
      return callback()
    }
    this.buffer = Buffer.concat([this.buffer, chunk])
    const str = this.buffer.toString('binary')
    const match = str.search(/content-length:/i)
    if (match !== -1) {
      this.foundFirstHeader = true
      const valid = this.buffer.subarray(match)
      this.buffer = Buffer.alloc(0)
      this.push(valid)
      return callback()
    }
    if (this.buffer.length > MAX_BANNER_BYTES) {
      this.buffer = Buffer.alloc(0)
      callback(
        new Error(
          `LSP server produced more than ${MAX_BANNER_BYTES} bytes of non-protocol output before the first message`,
        ),
      )
      return
    }
    callback()
  }

  override _flush(callback: () => void): void {
    if (!this.foundFirstHeader && this.buffer.length > 0) {
      this.push(this.buffer)
    }
    callback()
  }
}

export function isJdtlsExecutable(value: string): boolean {
  let name = value.replaceAll('\\', '/')
  const slash = name.lastIndexOf('/')
  if (slash >= 0) {
    name = name.slice(slash + 1)
  }
  let lower = name.toLowerCase()
  for (const suffix of ['.exe', '.cmd', '.bat']) {
    if (lower.endsWith(suffix)) {
      lower = lower.slice(0, -suffix.length)
    }
  }
  return lower === 'jdtls'
}

export function isJdtlsCommand(command: string[]): boolean {
  return command.some(isJdtlsExecutable)
}

const LANGUAGE_IDS: Record<string, string> = {
  '.ts': 'typescript',
  '.tsx': 'typescriptreact',
  '.mts': 'typescript',
  '.cts': 'typescript',
  '.js': 'javascript',
  '.jsx': 'javascriptreact',
  '.mjs': 'javascript',
  '.cjs': 'javascript',
  '.java': 'java',
  '.go': 'go',
  '.py': 'python',
  '.pyi': 'python',
  '.c': 'c',
  '.h': 'c',
  '.cpp': 'cpp',
  '.cc': 'cpp',
  '.hpp': 'cpp',
}

const SYMBOL_KIND_NAMES: Record<number, string> = {
  1: 'File',
  2: 'Module',
  3: 'Namespace',
  4: 'Package',
  5: 'Class',
  6: 'Method',
  7: 'Property',
  8: 'Field',
  9: 'Constructor',
  10: 'Enum',
  11: 'Interface',
  12: 'Function',
  13: 'Variable',
  14: 'Constant',
  15: 'String',
  16: 'Number',
  17: 'Boolean',
  18: 'Array',
  19: 'Object',
  20: 'Key',
  21: 'Null',
  22: 'EnumMember',
  23: 'Struct',
  24: 'Event',
  25: 'Operator',
  26: 'TypeParameter',
}

const JDT_URI_REGEX = /jdt:\/\/\S+/

interface DocumentRecord {
  uri: string
  version: number
  text: string
  modified: number
  size: number
}

interface ServerCapabilities {
  positionEncoding?: string
  definitionProvider?: boolean | Record<string, unknown>
  workspaceSymbolProvider?: boolean | Record<string, unknown>
  [key: string]: unknown
}

interface RangePosition {
  line: number
  character: number
}

interface RangeObject {
  start?: RangePosition
  end?: RangePosition
}

interface LocationObject {
  uri?: string
  range?: RangeObject
}

interface LocationLinkObject {
  targetUri?: string
  targetRange?: RangeObject
  targetSelectionRange?: RangeObject
}

export class LspClient {
  private readonly server: ResolvedServerConfig
  private readonly root: string
  private readonly managed: ManagedProcess
  private readonly connection: MessageConnection
  private readonly isJdtls: boolean
  private readonly documents = new Map<string, DocumentRecord>()

  private stopped = false
  /**
   * 这次物理收尾的唯一 Promise：并发与重复调用都等待它，因此必然共享同一次终结和同一个结论（成功或失败），
   * 不会因为「已经停过」而在收尾尚未收敛时提前报告成功。
   */
  private stopPromise: Promise<void> | null = null
  private hasExited = false
  private exitCode: number | null = null
  private capabilities: ServerCapabilities | null = null
  private positionEncoding = 'utf-16'
  private transportFailure: Error | null = null
  private rejectTransport!: (error: Error) => void
  private readonly transportFailurePromise: Promise<never>

  private constructor(
    server: ResolvedServerConfig,
    root: string,
    managed: ManagedProcess,
    connection: MessageConnection,
  ) {
    this.server = server
    this.root = root
    this.managed = managed
    this.connection = connection
    this.isJdtls = isJdtlsCommand(server.command)
    this.transportFailurePromise = new Promise<never>((_resolve, reject) => {
      this.rejectTransport = reject
    })
    // The rejection is raced by in-flight requests; keep it from becoming an unhandled rejection.
    void this.transportFailurePromise.catch(() => {})

    this.managed.exit.then((exit: ProcessExit) => {
      this.hasExited = true
      this.exitCode = exit.exitCode
      this.connection.dispose()
    })
  }

  /** Records a fatal stdio framing/transport failure and converges the underlying process. */
  private reportTransportFailure(error: Error): void {
    if (this.transportFailure) {
      return
    }
    const recorded = error instanceof LspError ? error : new LspError(error.message, { cause: error })
    this.transportFailure = recorded
    void this.managed.terminate(0).catch((cleanupError: unknown) => {
      // Preserve the cleanup failure instead of swallowing it: attach it to the recorded failure.
      if (this.transportFailure === recorded) {
        this.transportFailure = new LspError(recorded.message, {
          cause: new AggregateError([recorded, cleanupError], 'LSP transport failure and cleanup failure'),
        })
      }
    })
    this.rejectTransport(recorded)
  }

  static async launch(
    server: ResolvedServerConfig,
    root: string,
    executable: string,
    processes: ProcessSupervisor,
  ): Promise<LspClient> {
    const command = [...server.command]
    command[0] = executable

    let managed: ManagedProcess
    try {
      managed = await processes.start({
        executable,
        args: command.slice(1),
        cwd: root,
      })
    } catch {
      throw new LspError(`LSP server '${server.id}' cannot be started: ${command[0]}`)
    }

    const STDERR_TAIL_BYTES = 8 * 1024
    let stderrTail = Buffer.alloc(0)
    managed.stderr.on('data', (chunk: Buffer) => {
      stderrTail = Buffer.concat([stderrTail, chunk])
      if (stderrTail.length > STDERR_TAIL_BYTES) {
        stderrTail = stderrTail.subarray(stderrTail.length - STDERR_TAIL_BYTES)
      }
    })

    // Probe 200ms for early exit
    const exitPromise = managed.exit.then((exit) => ({ exited: true as const, exit }))
    const timerPromise = new Promise<{ exited: false }>((resolve) =>
      setTimeout(() => resolve({ exited: false }), 200),
    )

    try {
      const probe = await Promise.race([exitPromise, timerPromise])
      if (probe.exited) {
        const code = probe.exit.exitCode != null ? String(probe.exit.exitCode) : 'unknown'
        throw new LspError(`LSP server '${server.id}' exited before initialization (exit code ${code})`)
      }

      const filter = new GarbageBannerFilter()
      managed.stdout.pipe(filter)

      const reader = new StreamMessageReader(filter)
      const writer = new StreamMessageWriter(managed.stdin)
      const connection = createMessageConnection(reader, writer)

      const client = new LspClient(server, root, managed, connection)
      filter.on('error', (error: Error) => {
        client.reportTransportFailure(error)
      })
      client.listen()
      return client
    } catch (error) {
      let cleanupError: unknown
      try {
        await managed.terminate(0)
      } catch (failure) {
        cleanupError = failure
      }
      if (cleanupError !== undefined) {
        const cause = new AggregateError([error, cleanupError], 'LSP launch failed and cleanup failed')
        if (error instanceof LspError) {
          throw new LspError(error.message, { cause })
        }
        if (error instanceof Error) {
          // Preserve the original error identity and message; record the cleanup failure without masking it.
          error.cause = cause
          throw error
        }
        throw new LspError(`LSP server '${server.id}' failed to start`, { cause })
      }
      throw error
    }
  }

  private listen(): void {
    const rootUri = pathToFileURL(this.root).href

    this.connection.onRequest('workspace/configuration', (params: { items?: unknown[] }) => {
      if (params && Array.isArray(params.items)) {
        return params.items.map(() => ({}))
      }
      return []
    })

    this.connection.onRequest('workspace/workspaceFolders', () => {
      return [{ uri: rootUri, name: 'workspace' }]
    })

    this.connection.onRequest('client/registerCapability', () => null)
    this.connection.onRequest('client/unregisterCapability', () => null)
    this.connection.onRequest('window/workDoneProgress/create', () => null)
    this.connection.onRequest('workspace/applyEdit', () => ({ applied: false }))
    this.connection.onRequest('window/showMessageRequest', () => null)

    this.connection.onNotification('window/showMessage', () => {})
    this.connection.onNotification('window/logMessage', () => {})
    this.connection.onNotification('telemetry/event', () => {})
    this.connection.onNotification('textDocument/publishDiagnostics', () => {})

    this.connection.listen()
  }

  async initialize(timeoutMillis = 180_000, signal?: AbortSignal): Promise<void> {
    const rootUri = pathToFileURL(this.root).href
    const initParams: Record<string, unknown> = {
      processId: this.managed.pid,
      rootUri,
      rootPath: this.root,
      workspaceFolders: [{ uri: rootUri, name: 'workspace' }],
      capabilities: {
        general: {
          positionEncodings: ['utf-16', 'utf-8'],
        },
        workspace: {
          workspaceFolders: true,
          configuration: false,
          applyEdit: false,
        },
        textDocument: {
          synchronization: {
            willSave: false,
            willSaveWaitUntil: false,
            didSave: true,
          },
          definition: {
            linkSupport: true,
          },
        },
      },
    }

    if (this.isJdtls) {
      initParams['initializationOptions'] = {
        extendedClientCapabilities: {
          classFileContentsSupport: true,
        },
      }
    }

    try {
      const result = await this.sendRequest<{
        capabilities?: ServerCapabilities
      }>('initialize', initParams, 'initialize', { timeoutMillis, signal })

      this.capabilities = result?.capabilities ?? null
      const encoding = result?.capabilities?.positionEncoding
      if (encoding && typeof encoding === 'string' && encoding.trim().length > 0) {
        this.positionEncoding = encoding
      }

      await this.connection.sendNotification('initialized', {})
    } catch (error) {
      let cleanupError: unknown
      try {
        await this.stop(0)
      } catch (failure) {
        cleanupError = failure
      }
      if (cleanupError !== undefined) {
        const primary = error instanceof LspError ? error.message : 'LSP initialize failed'
        throw new LspError(primary, {
          cause: new AggregateError([error, cleanupError], 'LSP initialize failed and cleanup failed'),
        })
      }
      throw error
    }
  }

  isAlive(): boolean {
    return !this.stopped && !this.hasExited
  }

  supports(method: string): boolean {
    if (!this.capabilities) {
      return false
    }
    switch (method) {
      case 'textDocument/definition': {
        const prov = this.capabilities.definitionProvider
        return prov != null && prov !== false
      }
      case 'workspace/symbol': {
        const prov = this.capabilities.workspaceSymbolProvider
        return prov != null && prov !== false
      }
      default:
        return this.isJdtls
    }
  }

  sync(filePath: string): void {
    this.requireRunning()
    const target = path.resolve(filePath)
    let stat: fs.Stats
    try {
      stat = fs.statSync(target)
    } catch {
      throw new LspError('LSP request failed: the source file could not be read')
    }

    const modified = stat.mtimeMs
    const size = stat.size
    const document = this.documents.get(target)
    if (document && document.modified === modified && document.size === size) {
      return
    }

    let text: string
    try {
      const bytes = fs.readFileSync(target)
      text = TextFileCodec.decode(bytes).text
    } catch (error) {
      if (error instanceof LspError && error.message.includes('binary')) {
        throw new LspError('LSP request failed: the source file is not valid text')
      }
      throw new LspError('LSP request failed: the source file could not be read')
    }

    const uri = pathToFileURL(target).href
    if (!document) {
      this.connection.sendNotification('textDocument/didOpen', {
        textDocument: {
          uri,
          languageId: this.languageId(target),
          version: 1,
          text,
        },
      })
      this.documents.set(target, { uri, version: 1, text, modified, size })
      return
    }

    if (text === document.text) {
      this.documents.set(target, { uri, version: document.version, text, modified, size })
      return
    }

    const version = document.version + 1
    this.connection.sendNotification('textDocument/didChange', {
      textDocument: { uri, version },
      contentChanges: [{ text }],
    })
    this.connection.sendNotification('textDocument/didSave', {
      textDocument: { uri },
    })
    this.documents.set(target, { uri, version, text, modified, size })
  }

  refresh(filePath: string): void {
    if (!this.isAlive()) {
      return
    }
    const target = path.resolve(filePath)
    if (!this.documents.has(target)) {
      return
    }
    this.sync(target)
  }

  async definition(
    filePath: string,
    line: number,
    character: number,
    signal?: AbortSignal,
  ): Promise<string[]> {
    this.requireRunning()
    if (!this.supports('textDocument/definition')) {
      throw new LspError(`LSP server '${this.server.id}' does not advertise go-to-definition support.`)
    }
    this.sync(filePath)
    const target = path.resolve(filePath)
    const document = this.documents.get(target)
    if (!document) {
      throw new LspError('LSP request failed: the source file could not be read')
    }

    const pos = this.position(document, line, character)
    const result = await this.sendRequest<LocationObject | LocationObject[] | LocationLinkObject[] | null | undefined>(
      'textDocument/definition',
      {
        textDocument: { uri: document.uri },
        position: pos,
      },
      'textDocument/definition',
      { signal },
    )
    return this.formatLocations(result)
  }

  async workspaceSymbols(
    query: string,
    limit: number,
    signal?: AbortSignal,
  ): Promise<string[]> {
    this.requireRunning()
    if (!this.supports('workspace/symbol')) {
      throw new LspError(`LSP server '${this.server.id}' does not advertise workspace symbol support.`)
    }
    const result = await this.sendRequest<unknown[] | null | undefined>(
      'workspace/symbol',
      { query },
      'workspace/symbol',
      { signal },
    )
    return this.formatSymbols(result, limit)
  }

  async javaDecompile(target: string, signal?: AbortSignal): Promise<string> {
    this.requireRunning()
    const jdtMatch = target.match(JDT_URI_REGEX)
    if (jdtMatch) {
      if (!this.supports('java/classFileContents')) {
        throw new LspError('lsp_java_decompile is only supported by jdtls.')
      }
      const uriMatch = jdtMatch[0]
      const result = await this.sendRequest<string>(
        'java/classFileContents',
        { uri: uriMatch },
        'java/classFileContents',
        { signal },
      )
      return this.requireDecompiledSource(result, target)
    }

    const localPath = this.resolveLocalTarget(target)
    let stat: fs.Stats
    try {
      stat = fs.statSync(localPath)
    } catch {
      throw new LspError('target is not a readable class file: ' + localPath)
    }
    if (!stat.isFile()) {
      throw new LspError('target is not a readable class file: ' + localPath)
    }

    const result = await this.sendRequest<string>(
      'workspace/executeCommand',
      {
        command: 'java.decompile',
        arguments: [pathToFileURL(localPath).href],
      },
      'java.decompile',
      { signal },
    )
    return this.requireDecompiledSource(result, target)
  }

  /**
   * 停止客户端：并发调用与重复调用都等待同一次物理收尾，因此必然看到同一个结论，绝不会因为「已经停过」而报告成功。
   *
   * <p>进程终止没能证明收敛时，该失败就是这次停止的结论，后续调用（含各自的清理路径）都会重新抛出同一条失败，
   * 因此池可以据此判定该 key 的旧实例可能仍然存活，绝不重新启动第二个服务器。
   */
  stop(graceMillis = 1000): Promise<void> {
    if (this.stopPromise === null) {
      this.stopped = true
      this.stopPromise = this.finishStop(graceMillis)
    }
    return this.stopPromise
  }

  /**
   * 执行这次停止的物理收尾：连接释放与进程终结各自独立尝试，任何一项失败都不得跳过另一项，
   * 两者的失败都保留在同一个结论里。
   */
  private async finishStop(graceMillis: number): Promise<void> {
    try {
      if (!this.hasExited) {
        const shutdownGrace = Math.max(0, graceMillis)
        if (shutdownGrace > 0) {
          try {
            await this.sendRequest('shutdown', null, 'shutdown', {
              timeoutMillis: shutdownGrace,
              requireAlive: false,
            })
          } catch {}
        }
        try {
          await this.connection.sendNotification('exit', {})
        } catch {}

        if (shutdownGrace > 0) {
          const exitPromise = this.managed.exit
          const timer = new Promise((resolve) => setTimeout(resolve, shutdownGrace))
          await Promise.race([exitPromise, timer])
        }
      }
    } finally {
      this.documents.clear()
      const failures: unknown[] = []
      let cleanupError: unknown
      try {
        this.connection.dispose()
      } catch (disposeError) {
        // 连接释放失败绝不能跳过进程终结：旧实例是否存活必须靠终结来证明。
        cleanupError = disposeError
      }
      if (cleanupError !== undefined) {
        failures.push(
          new LspError('LSP connection disposal failed during shutdown', { cause: cleanupError }),
        )
      }
      cleanupError = undefined
      try {
        await this.managed.terminate(graceMillis)
      } catch (terminateError) {
        cleanupError = terminateError
      }
      if (cleanupError !== undefined) {
        failures.push(
          new LspError('LSP server process termination failed during shutdown', {
            cause: cleanupError,
          }),
        )
      }
      if (failures.length === 1) {
        throw failures[0]
      }
      if (failures.length > 1) {
        throw new AggregateError(failures, 'LSP client shutdown failed')
      }
    }
  }

  private requireRunning(): void {
    if (this.transportFailure) {
      throw new LspError(this.transportFailure.message, { cause: this.transportFailure })
    }
    if (this.stopped) {
      throw new LspError('LSP client stopped')
    }
    if (this.hasExited) {
      const code = this.exitCode != null ? String(this.exitCode) : 'unknown'
      throw new LspError(`LSP server '${this.server.id}' exited (exit code ${code})`)
    }
  }

  private async sendRequest<T>(
    method: string,
    params: unknown,
    description: string,
    options: {
      signal?: AbortSignal
      timeoutMillis?: number
      requireAlive?: boolean
    } = {},
  ): Promise<T> {
    const requireAlive = options.requireAlive ?? true
    if (requireAlive) {
      this.requireRunning()
    }
    const cts = new CancellationTokenSource()

    let timer: NodeJS.Timeout | null = null
    let timeoutPromise: Promise<never> | null = null
    const timeoutMillis = options.timeoutMillis
    if (timeoutMillis !== undefined && timeoutMillis > 0) {
      timeoutPromise = new Promise<never>((_, reject) => {
        timer = setTimeout(() => {
          cts.cancel()
          reject(new LspTimeoutError(`LSP request timed out after ${timeoutMillis}ms (${description}).`))
        }, timeoutMillis)
      })
    }

    const signal = options.signal
    let onAbort: (() => void) | null = null

    const abortPromise = new Promise<never>((_, reject) => {
      const handleAbort = (): void => {
        cts.cancel()
        if (signal?.reason === 'TIMED_OUT') {
          reject(new LspTimeoutError(`LSP request timed out (${description}).`))
        } else {
          const err = new Error('Operation cancelled')
          err.name = 'AbortError'
          reject(err)
        }
      }

      if (signal?.aborted) {
        handleAbort()
        return
      }
      if (signal) {
        onAbort = handleAbort
        signal.addEventListener('abort', onAbort, { once: true })
      }
    })

    const exitPromise = this.managed.exit.then((exit) => {
      this.hasExited = true
      this.exitCode = exit.exitCode
      const code = exit.exitCode != null ? String(exit.exitCode) : 'unknown'
      throw new LspError(`LSP server '${this.server.id}' exited (exit code ${code})`)
    })
    // The race below consumes this rejection; keep a handler so a late exit after the race has already
    // settled does not surface as an unhandled rejection.
    void exitPromise.catch(() => {})

    try {
      const races: Promise<unknown>[] = [
        this.connection.sendRequest(method, params, cts.token) as Promise<T>,
        abortPromise,
        exitPromise,
        this.transportFailurePromise,
      ]
      if (timeoutPromise) {
        races.push(timeoutPromise)
      }
      const result = (await Promise.race(races)) as T
      if (requireAlive) {
        this.requireRunning()
      }
      return result
    } catch (err) {
      if (requireAlive && !this.isAlive()) {
        this.requireRunning()
      }
      if (err instanceof LspError || (err instanceof Error && err.name === 'AbortError')) {
        throw err
      }
      throw new LspError(`LSP request failed (${description})`)
    } finally {
      if (timer) {
        clearTimeout(timer)
      }
      if (signal && onAbort) {
        signal.removeEventListener('abort', onAbort)
      }
      cts.dispose()
    }
  }

  private position(document: DocumentRecord, line: number, character: number): RangePosition {
    const text = document.text
    let start = 0
    for (let current = 1; current < line; current++) {
      const newline = text.indexOf('\n', start)
      if (newline < 0) {
        throw new LspError(`line ${line} is beyond the end of the synchronized document`)
      }
      start = newline + 1
    }

    const newline = text.indexOf('\n', start)
    let lineEnd = newline < 0 ? text.length : newline
    if (lineEnd > start && text.charCodeAt(lineEnd - 1) === 0x0d) {
      lineEnd--
    }

    const lineText = text.substring(start, lineEnd)
    const codePoints = Array.from(lineText)
    if (character > codePoints.length) {
      throw new LspError(
        `character ${character} is beyond line ${line} of the synchronized document (${codePoints.length} code points)`,
      )
    }

    const prefix = codePoints.slice(0, character).join('')
    let encoded = prefix.length
    if (this.positionEncoding === 'utf-8') {
      encoded = Buffer.byteLength(prefix, 'utf8')
    } else if (this.positionEncoding === 'utf-32') {
      encoded = character
    }

    return {
      line: line - 1,
      character: encoded,
    }
  }

  private formatLocations(
    result: LocationObject | LocationObject[] | LocationLinkObject[] | null | undefined,
  ): string[] {
    const formatted: string[] = []
    if (result == null) {
      return ['No results found']
    }
    const items = Array.isArray(result) ? result : [result]
    for (const item of items) {
      if (!item) {
        continue
      }
      if ('targetUri' in item) {
        const link = item as LocationLinkObject
        const range = link.targetSelectionRange ?? link.targetRange
        formatted.push(this.locationText(link.targetUri, range))
      } else if ('uri' in item) {
        const loc = item as LocationObject
        formatted.push(this.locationText(loc.uri, loc.range))
      }
    }
    return formatted.length === 0 ? ['No results found'] : formatted
  }

  private locationText(uri: string | null | undefined, range: RangeObject | null | undefined): string {
    if (uri == null) {
      return 'unknown'
    }
    let location = uri
    if (uri.startsWith('file:')) {
      try {
        location = fileURLToPath(uri)
      } catch {
        location = uri
      }
    }
    if (!range || !range.start) {
      return location
    }
    return `${location}:${range.start.line + 1}:${range.start.character}`
  }

  private formatSymbols(result: unknown[] | null | undefined, limit: number): string[] {
    if (limit <= 0 || !result || !Array.isArray(result) || result.length === 0) {
      return ['No symbols found']
    }
    const symbols: string[] = []
    for (const item of result) {
      if (!item || typeof item !== 'object') {
        continue
      }
      const sym = item as Record<string, unknown>
      const name = sym['name'] != null ? String(sym['name']) : 'unknown'
      const rawKind = sym['kind']
      let kindName = 'unknown'
      if (typeof rawKind === 'number' && SYMBOL_KIND_NAMES[rawKind]) {
        kindName = SYMBOL_KIND_NAMES[rawKind]!
      } else if (typeof rawKind === 'string' && rawKind.length > 0) {
        kindName = rawKind
      }

      let uri: string | null = null
      const loc = sym['location'] as Record<string, unknown> | undefined
      if (loc && typeof loc === 'object') {
        if (typeof loc['uri'] === 'string') {
          uri = loc['uri']
        }
      }
      symbols.push(`${name} (${kindName}) - ${uri ?? 'unknown'}`)
      if (symbols.length === limit) {
        break
      }
    }
    return symbols.length === 0 ? ['No symbols found'] : symbols
  }

  private resolveLocalTarget(target: string): string {
    const trimmed = target.trim()
    if (trimmed.startsWith('file:')) {
      try {
        return path.normalize(fileURLToPath(trimmed))
      } catch {
        throw new LspError('invalid class target URI: ' + target)
      }
    }
    let candidate: string
    try {
      candidate = trimmed
    } catch {
      throw new LspError('invalid class target: ' + target)
    }
    if (!path.isAbsolute(candidate)) {
      throw new LspError('target must be an absolute class path, a file: URI, or a jdt:// URI: ' + target)
    }
    return path.normalize(candidate)
  }

  private requireDecompiledSource(result: unknown, target: string): string {
    if (typeof result === 'string' && result.trim().length > 0) {
      return result
    }
    throw new LspError('Could not load or decompile class for target: ' + target)
  }

  private languageId(filePath: string): string {
    const name = path.basename(filePath)
    const dot = name.lastIndexOf('.')
    if (dot >= 0 && dot < name.length - 1) {
      const ext = name.slice(dot).toLowerCase()
      const lang = LANGUAGE_IDS[ext]
      if (lang) {
        return lang
      }
    }
    return this.server.id
  }
}
