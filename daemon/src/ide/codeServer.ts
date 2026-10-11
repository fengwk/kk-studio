import { existsSync } from 'node:fs'
import { cp, readdir, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { createHash, randomBytes, randomUUID } from 'node:crypto'
import { join, relative } from 'node:path'
import { createServer } from 'node:net'
import type { AddressInfo } from 'node:net'
import type { Readable } from 'node:stream'
import type { DaemonConfig, ManagedProcess, ProcessSupervisor } from '../contracts.js'
import { withLoopbackResponse } from '../network.js'
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

const BRIDGE_EXTENSION_DIR = 'kk-studio-ide-bridge'
const BRIDGE_DIGEST_FILE = '.kk-studio-digest'

/** Stable digest of the extension source so a new daemon always refreshes a stale bridge. */
async function extensionDigest(source: string): Promise<string> {
  const hash = createHash('sha256')
  const entries = await readdir(source, { recursive: true, withFileTypes: true })
  const files = entries
    .filter((entry) => entry.isFile())
    .map((entry) => join(entry.parentPath ?? source, entry.name))
    .sort()
  for (const file of files) {
    hash.update(relative(source, file))
    hash.update(await readFile(file))
  }
  return hash.digest('hex')
}

/**
 * Installs the bundled bridge extension into the private extensions directory.
 *
 * The single real contract path is `<distributionDir>/ide-extension`; there is no runtime
 * fallback. The install is staged and only swapped in once complete, and it is refreshed
 * whenever the source digest changes so an upgraded daemon never keeps an old bridge.
 */
async function installBridgeExtension(distributionDir: string, extensionsDir: string): Promise<void> {
  const source = join(distributionDir, 'ide-extension')
  if (!existsSync(source)) {
    throw new IdeError('bundled ide bridge extension is missing')
  }
  const target = join(extensionsDir, BRIDGE_EXTENSION_DIR)
  const digest = await extensionDigest(source)
  const marker = join(target, BRIDGE_DIGEST_FILE)
  if (existsSync(marker) && (await readFile(marker, 'utf8')) === digest) {
    return
  }
  const staging = join(extensionsDir, `${BRIDGE_EXTENSION_DIR}.staging-${randomUUID()}`)
  await cp(source, staging, { recursive: true })
  await writeFile(join(staging, BRIDGE_DIGEST_FILE), digest)
  await rm(target, { recursive: true, force: true })
  await rename(staging, target)
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
      // 私有 code-server 探活绝不走环境代理，避免 NODE_USE_ENV_PROXY=1 把 loopback 请求泄露给代理。
      const healthy = await withLoopbackResponse(
        `http://127.0.0.1:${port}/healthz`,
        { redirect: 'manual' },
        boundedSignal(signal, HEALTH_FETCH_TIMEOUT_MILLIS),
        async (response) => response.statusCode >= 200 && response.statusCode < 300,
      )
      if (healthy) {
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
  let session: string | undefined
  try {
    session = await withLoopbackResponse(
      `http://127.0.0.1:${port}/login`,
      {
        method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ password: secret }).toString(),
        redirect: 'manual',
      },
      boundedSignal(signal, LOGIN_TIMEOUT_MILLIS),
      async (response) => {
        const header = response.headers['set-cookie']
        const cookies = Array.isArray(header) ? header : header === undefined ? [] : [header]
        return cookies
          .map((cookie) => cookie.split(';')[0])
          .find((cookie) => cookie?.startsWith('code-server-session='))
      },
    )
  } catch (error) {
    if (signal.aborted) {
      throw abortError()
    }
    throw new IdeError('code-server login failed')
  }
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
  /** Owns startup lifetime so one caller aborting never cancels other waiting views. */
  const startup = new AbortController()
  let session: CodeServerSession | undefined
  let managed: ManagedProcess | undefined
  let starting: Promise<CodeServerSession> | undefined
  let closing: Promise<void> | undefined
  let ipcDirectory: string | undefined
  let closed = false

  const start = async (signal: AbortSignal): Promise<CodeServerSession> => {
    // An exited root may still own descendants; converge its scope before restarting.
    if (managed) {
      await managed.terminate(5000)
      managed = undefined
    }
    const launch = resolveCodeServerLaunch(config.distributionDir)
    await ensurePrivateDirectory(paths.codeServerDir)
    await ensurePrivateDirectory(paths.userDataDir)
    await ensurePrivateDirectory(paths.extensionsDir)
    await ensurePrivateDirectory(paths.viewsDir)
    await installBridgeExtension(config.distributionDir, paths.extensionsDir)

    // Linux sun_path is bounded in bytes. Persistent data paths and TMPDIR are not.
    // The private short directory also protects the unauthenticated session IPC endpoint.
    if (process.platform !== 'win32' && ipcDirectory === undefined) {
      ipcDirectory = await ensurePrivateDirectory(join('/tmp', `kk-studio-ide-${randomUUID()}`))
    }
    const ipcArgs = ipcDirectory === undefined ? [] : ['--session-socket', join(ipcDirectory, 'session.sock')]
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
        ...ipcArgs,
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
      if (managed === process_ && terminateFailure === undefined) {
        managed = undefined
        session = undefined
      }
      if (terminateFailure !== undefined) {
        throw new IdeError(`code-server startup failed (${safeMessage(error)}) and could not be terminated`)
      }
      throw error
    }
  }

  const waitForStart = (signal: AbortSignal): Promise<CodeServerSession> => {
    if (signal.aborted) {
      return Promise.reject(abortError())
    }
    return new Promise<CodeServerSession>((resolve, reject) => {
      const onAbort = (): void => reject(abortError())
      signal.addEventListener('abort', onAbort, { once: true })
      starting?.then(
        (value) => {
          signal.removeEventListener('abort', onAbort)
          resolve(value)
        },
        (error: unknown) => {
          signal.removeEventListener('abort', onAbort)
          reject(error)
        },
      )
    })
  }

  return {
    ensureStarted(signal) {
      if (closed) {
        return Promise.reject(new IdeError('ide service is closed'))
      }
      if (session) {
        return Promise.resolve(session)
      }
      if (signal.aborted) {
        // Nothing to wait for and no reason to spawn a process for a caller that gave up.
        return Promise.reject(abortError())
      }
      if (!starting) {
        // Startup is shared; only the manager's own signal can abort it.
        starting = start(startup.signal).finally(() => {
          starting = undefined
        })
      }
      // A caller that aborts stops waiting for itself but does not cancel the shared startup.
      return waitForStart(signal)
    },
    session() {
      return session
    },
    close() {
      if (closing === undefined) {
        closed = true
        startup.abort()
        closing = (async () => {
          const pending = starting
          if (pending) {
            // Startup may fail normally; retained scopes still require convergence below.
            await pending.catch(() => undefined)
          }
          session = undefined
          if (managed) {
            await managed.terminate(5000)
            managed = undefined
          }
          // Never remove IPC state before proving the whole process scope converged.
          if (ipcDirectory !== undefined) {
            await rm(ipcDirectory, { recursive: true, force: true })
            ipcDirectory = undefined
          }
        })()
      }
      return closing
    },
    diagnostics() {
      return diagnostics.value()
    },
  }
}
