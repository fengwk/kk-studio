import { existsSync } from 'node:fs'
import { cp } from 'node:fs/promises'
import { join } from 'node:path'
import { createServer } from 'node:net'
import type { AddressInfo } from 'node:net'
import { randomBytes } from 'node:crypto'
import type { Readable } from 'node:stream'
import type { DaemonConfig, ManagedProcess, ProcessSupervisor } from '../contracts.js'
import { ensurePrivateDirectory, writePrivateFile } from '../native/index.js'
import { IdeError, safeMessage } from './errors.js'

/**
 * Manages the single code-server process for one environment.
 *
 * The frozen `runtime/code-server` distribution is launched through its bundled Node
 * (`lib/node[.exe] out/node/entry.js`) so no system Node is needed and no `.cmd`/shell
 * wrapper is involved. Password authentication uses a random per-service secret stored in
 * a private config file; the daemon logs in once for a private session cookie that is only
 * injected on its own upstream requests. The secret never reaches the browser.
 */

export interface IdePaths {
  ideDir: string
  codeServerDir: string
  userDataDir: string
  extensionsDir: string
  configFile: string
  viewsDir: string
}

export interface CodeServerSession {
  host: string
  port: number
  cookie: string
  pid: number
}

export interface CodeServerManager {
  ensureStarted(signal: AbortSignal): Promise<CodeServerSession>
  session(): CodeServerSession | undefined
  /** Bounded tail of recent process output for diagnostics; never logged verbatim. */
  diagnostics(): string
  close(): Promise<void>
}

export interface BridgeCredentials {
  url: string
  token: string
}

const STARTUP_TIMEOUT_MILLIS = 30_000
const HEALTH_POLL_MILLIS = 50
const HEALTH_FETCH_TIMEOUT_MILLIS = 2_000
const LOGIN_TIMEOUT_MILLIS = 5_000
const DIAGNOSTIC_TAIL_BYTES = 4 * 1024

export function idePaths(config: DaemonConfig): IdePaths {
  const ideDir = join(config.dataDir, 'ide')
  const codeServerDir = join(ideDir, 'code-server')
  return {
    ideDir,
    codeServerDir,
    userDataDir: join(codeServerDir, 'user-data'),
    extensionsDir: join(codeServerDir, 'extensions'),
    configFile: join(codeServerDir, 'config.yaml'),
    viewsDir: join(ideDir, 'views'),
  }
}

interface Launch {
  executable: string
  args: string[]
}

/** Resolves the fixed, bundled Node CLI of the frozen distribution. */
export function resolveCodeServerLaunch(distributionDir: string): Launch {
  const root = join(distributionDir, 'runtime', 'code-server')
  const nodeName = process.platform === 'win32' ? 'node.exe' : 'node'
  const executable = join(root, 'lib', nodeName)
  const entry = join(root, 'out', 'node', 'entry.js')
  if (!existsSync(executable) || !existsSync(entry)) {
    throw new IdeError('code-server runtime is not installed')
  }
  return { executable, args: [entry] }
}

async function installBridgeExtension(distributionDir: string, extensionsDir: string): Promise<void> {
  const target = join(extensionsDir, 'kk-studio-ide-bridge')
  if (existsSync(target)) {
    return
  }
  const source = [join(distributionDir, 'ide-extension'), join(distributionDir, 'runtime', 'ide-extension')].find(
    (candidate) => existsSync(candidate),
  )
  if (source === undefined) {
    throw new IdeError('bundled ide bridge extension is missing')
  }
  await cp(source, target, { recursive: true })
}

/** Keeps only the last N characters of process output for diagnostics; never logged verbatim. */
class DiagnosticTail {
  private chunks: string[] = []
  private size = 0

  constructor(private readonly limit = DIAGNOSTIC_TAIL_BYTES) {}

  push(text: string): void {
    this.chunks.push(text)
    this.size += text.length
    while (this.size > this.limit && this.chunks.length > 1) {
      this.size -= (this.chunks.shift() as string).length
    }
    if (this.size > this.limit) {
      // A single oversized chunk is trimmed so the tail is always bounded.
      const head = this.chunks[0] as string
      this.chunks[0] = head.slice(this.size - this.limit)
      this.size = this.limit
    }
  }

  value(): string {
    return this.chunks.join('')
  }
}

/** Continuously drains a child stream so a full OS pipe can never block code-server. */
function drain(stream: Readable, tail: DiagnosticTail): void {
  stream.setEncoding('utf8')
  stream.on('data', (chunk: string) => tail.push(chunk))
  stream.on('error', () => undefined)
  stream.resume()
}

function allocateLoopbackPort(): Promise<number> {
  return new Promise<number>((resolve, reject) => {
    const probe = createServer()
    probe.once('error', reject)
    probe.listen(0, '127.0.0.1', () => {
      const port = (probe.address() as AddressInfo).port
      probe.close(() => resolve(port))
    })
  })
}

function delay(millis: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, millis))
}

function abortError(): Error {
  const error = new Error('ide startup aborted')
  error.name = 'AbortError'
  return error
}

function boundedSignal(parent: AbortSignal, millis: number): AbortSignal {
  return AbortSignal.any([parent, AbortSignal.timeout(millis)])
}

async function waitForHealth(port: number, managed: ManagedProcess, signal: AbortSignal): Promise<void> {
  let exited = false
  void managed.exit.then(() => {
    exited = true
  })
  const deadline = Date.now() + STARTUP_TIMEOUT_MILLIS
  while (Date.now() < deadline) {
    if (signal.aborted) {
      throw abortError()
    }
    if (exited) {
      throw new IdeError('code-server exited during startup')
    }
    try {
      const response = await fetch(`http://127.0.0.1:${port}/healthz`, {
        signal: boundedSignal(signal, HEALTH_FETCH_TIMEOUT_MILLIS),
      })
      if (response.ok) {
        return
      }
    } catch (error) {
      if (signal.aborted) {
        throw abortError()
      }
    }
    await delay(HEALTH_POLL_MILLIS)
  }
  throw new IdeError('code-server did not become ready')
}

async function login(port: number, secret: string, signal: AbortSignal): Promise<string> {
  if (signal.aborted) {
    throw abortError()
  }
  let response: Response
  try {
    response = await fetch(`http://127.0.0.1:${port}/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ password: secret }).toString(),
      redirect: 'manual',
      signal: boundedSignal(signal, LOGIN_TIMEOUT_MILLIS),
    })
  } catch (error) {
    if (signal.aborted) {
      throw abortError()
    }
    throw new IdeError('code-server login failed')
  }
  const session = response.headers
    .getSetCookie()
    .map((cookie) => cookie.split(';')[0])
    .find((cookie) => cookie?.startsWith('code-server-session='))
  if (session === undefined) {
    throw new IdeError('code-server login failed')
  }
  return session
}

export function createCodeServerManager(
  config: DaemonConfig,
  processes: ProcessSupervisor,
  bridge: BridgeCredentials,
): CodeServerManager {
  const paths = idePaths(config)
  const diagnostics = new DiagnosticTail()
  let session: CodeServerSession | undefined
  let managed: ManagedProcess | undefined
  let starting: Promise<CodeServerSession> | undefined

  const start = async (signal: AbortSignal): Promise<CodeServerSession> => {
    const launch = resolveCodeServerLaunch(config.distributionDir)
    await ensurePrivateDirectory(paths.codeServerDir)
    await ensurePrivateDirectory(paths.userDataDir)
    await ensurePrivateDirectory(paths.extensionsDir)
    await ensurePrivateDirectory(paths.viewsDir)
    await installBridgeExtension(config.distributionDir, paths.extensionsDir)

    const port = await allocateLoopbackPort()
    const secret = randomBytes(32).toString('base64url')
    await writePrivateFile(
      paths.configFile,
      [`bind-addr: 127.0.0.1:${port}`, 'auth: password', `password: ${secret}`, 'cert: false', ''].join('\n'),
    )

    const process_ = await processes.start({
      executable: launch.executable,
      args: [
        ...launch.args,
        '--config',
        paths.configFile,
        '--bind-addr',
        `127.0.0.1:${port}`,
        '--auth',
        'password',
        '--user-data-dir',
        paths.userDataDir,
        '--extensions-dir',
        paths.extensionsDir,
        '--disable-telemetry',
        '--disable-update-check',
        '--disable-workspace-trust',
        '--disable-getting-started-override',
      ],
      cwd: paths.codeServerDir,
      env: {
        ...process.env,
        KK_STUDIO_IDE_BRIDGE_URL: bridge.url,
        KK_STUDIO_IDE_BRIDGE_TOKEN: bridge.token,
      },
      signal,
    })
    managed = process_
    drain(process_.stdout, diagnostics)
    drain(process_.stderr, diagnostics)
    // If the process dies after startup, forget the session so the next OPEN_VIEW restarts it.
    void process_.exit.then(() => {
      if (managed === process_) {
        managed = undefined
        session = undefined
      }
    })

    try {
      await waitForHealth(port, process_, signal)
      const cookie = await login(port, secret, signal)
      session = { host: '127.0.0.1', port, cookie, pid: process_.pid }
      return session
    } catch (error) {
      let terminateFailure: unknown
      try {
        await process_.terminate()
      } catch (terminateError) {
        terminateFailure = terminateError
      }
      if (managed === process_) {
        managed = undefined
        session = undefined
      }
      if (terminateFailure !== undefined) {
        throw new IdeError(`code-server startup failed (${safeMessage(error)}) and could not be terminated`)
      }
      throw error
    }
  }

  return {
    async ensureStarted(signal) {
      if (session) {
        return session
      }
      if (!starting) {
        starting = start(signal).finally(() => {
          starting = undefined
        })
      }
      return starting
    },
    session() {
      return session
    },
    async close() {
      session = undefined
      const current = managed
      managed = undefined
      if (current) {
        // A confinement failure must surface instead of being swallowed.
        await current.terminate(5000)
      }
    },
    diagnostics() {
      return diagnostics.value()
    },
  }
}
