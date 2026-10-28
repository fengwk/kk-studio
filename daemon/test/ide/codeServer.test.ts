import { afterEach, describe, expect, it } from 'vitest'
import { spawn, type ChildProcess } from 'node:child_process'
import { copyFile, mkdtemp, mkdir, rm, stat, symlink, writeFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { request } from 'node:http'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import type { DaemonConfig, ManagedProcess, ProcessExit, ProcessSpec, ProcessSupervisor } from '../../src/contracts.js'
import { createCodeServerManager, idePaths, resolveCodeServerLaunch } from '../../src/ide/codeServer.js'

/**
 * Focused tests for the code-server process manager: bundled Node launch resolution,
 * bounded output draining, abort handling and non-swallowed termination failures.
 */

const cleanups: (() => Promise<void> | void)[] = []
afterEach(async () => {
  for (const cleanup of cleanups.splice(0).reverse()) {
    await cleanup()
  }
})

const READY_SERVER = `#!/usr/bin/env node
const http = require('node:http')
const fs = require('node:fs')
const args = process.argv.slice(2)
function arg(name) { const index = args.indexOf(name); return index >= 0 ? args[index + 1] : undefined }
let bind = '127.0.0.1:0'
let password = 'unset'
const configPath = arg('--config')
if (configPath && fs.existsSync(configPath)) {
  for (const line of fs.readFileSync(configPath, 'utf8').split('\\n')) {
    if (line.startsWith('password:')) password = line.slice(9).trim()
    if (line.startsWith('bind-addr:')) bind = line.slice(10).trim()
  }
}
const [host, portText] = bind.split(':')
for (let index = 0; index < 20; index += 1) { process.stdout.write('d'.repeat(1000) + '\\n') }
const server = http.createServer((req, res) => {
  if (req.url === '/healthz') { res.writeHead(200); res.end('{}'); return }
  if (req.url === '/login' && req.method === 'POST') {
    let body = ''
    req.on('data', (chunk) => { body += chunk })
    req.on('end', () => {
      if (new URLSearchParams(body).get('password') === password) {
        res.writeHead(302, { 'set-cookie': 'code-server-session=ok; Path=/' })
        res.end()
      } else { res.writeHead(200); res.end('nope') }
    })
    return
  }
  res.writeHead(404); res.end()
})
server.listen(Number(portText), host)
const shutdown = () => { server.close(() => process.exit(0)); setTimeout(() => process.exit(0), 200) }
process.on('SIGTERM', shutdown)
process.on('SIGINT', shutdown)
`

const NO_COOKIE_SERVER = READY_SERVER.replace(
  "res.writeHead(302, { 'set-cookie': 'code-server-session=ok; Path=/' })",
  'res.writeHead(200)',
)
const RESET_LOGIN_SERVER = READY_SERVER.replace(
  "if (req.url === '/login' && req.method === 'POST') {",
  "if (req.url === '/login') { req.socket.destroy(); return }\nif (false) {",
)
/** Delays listening so tests can observe a startup that is genuinely in flight. */
const SLOW_READY_SERVER = READY_SERVER.replace(
  'server.listen(Number(portText), host)',
  'setTimeout(function () { server.listen(Number(portText), host) }, 300)',
)
const IPC_READY_SERVER = READY_SERVER.replace(
  'const shutdown = () =>',
  `const ipc = http.createServer((req, res) => { res.end('private-session') })
ipc.listen(arg('--session-socket'))
const shutdown = () =>`,
)

function delay(millis: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, millis))
}

class TestSupervisor implements ProcessSupervisor {
  readonly children: ChildProcess[] = []
  private readonly childCloses: Promise<void>[] = []
  readonly specs: ProcessSpec[] = []
  readonly terminatedPids: number[] = []
  readonly options: { terminateError?: Error } = {}

  async start(spec: ProcessSpec): Promise<ManagedProcess> {
    this.specs.push(spec)
    const child = spawn(spec.executable, spec.args, { cwd: spec.cwd, env: spec.env, stdio: ['pipe', 'pipe', 'pipe'] })
    this.children.push(child)
    const exit = new Promise<ProcessExit>((resolve) => {
      child.on('exit', (code, signal) => resolve({ exitCode: code, signal }))
    })
    // Registered at spawn time: close() can only prove stdio teardown by awaiting 'close', and a
    // listener attached later could miss an event that already fired.
    const closed = new Promise<void>((resolve) => {
      child.on('close', () => resolve())
    })
    this.childCloses.push(closed)
    return {
      pid: child.pid as number,
      stdin: child.stdin as ManagedProcess['stdin'],
      stdout: child.stdout as ManagedProcess['stdout'],
      stderr: child.stderr as ManagedProcess['stderr'],
      exit,
      terminate: async () => {
        this.terminatedPids.push(child.pid as number)
        // Read the flag lazily so a test can fail termination after startup.
        if (this.options.terminateError) {
          throw this.options.terminateError
        }
        if (child.exitCode === null && child.signalCode === null) {
          child.kill('SIGKILL')
        }
        // 'exit' precedes stdio teardown; wait for 'close' so a deleted fixture is not still busy.
        await closed
      },
    }
  }

  async trackDescendant(): Promise<void> {}

  async close(): Promise<void> {
    await Promise.all(
      this.children.map(async (child, index) => {
        if (child.exitCode === null && child.signalCode === null) {
          child.kill('SIGKILL')
        }
        // Await every registered close, including children that exited but are still tearing down.
        await this.childCloses[index]!
      }),
    )
    for (const spec of this.specs) {
      const index = spec.args.indexOf('--session-socket')
      if (index >= 0) {
        await rm(dirname(spec.args[index + 1]!), { recursive: true, force: true })
      }
    }
  }
}

async function distribution(script: string): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), 'kk-ide-manager-'))
  cleanups.push(() => rm(root, { recursive: true, force: true }))
  const codeServerRoot = join(root, 'runtime', 'code-server')
  await mkdir(join(codeServerRoot, 'out', 'node'), { recursive: true })
  if (process.platform === 'win32') {
    // Windows launches lib/node.exe through a junction to the installed Node, so removing the
    // fixture deletes only the link instead of a copied image the running process holds open.
    await symlink(dirname(process.execPath), join(codeServerRoot, 'lib'), 'junction')
  } else {
    const launcher = join(codeServerRoot, 'lib', 'node')
    await mkdir(dirname(launcher), { recursive: true })
    await copyFile(process.execPath, launcher)
  }
  await writeFile(join(codeServerRoot, 'out', 'node', 'entry.js'), script)
  const extensionDir = join(root, 'ide-extension')
  await mkdir(extensionDir, { recursive: true })
  await writeFile(join(extensionDir, 'package.json'), '{"name":"kk-studio-ide-bridge"}')
  return root
}

function config(distributionDir: string): DaemonConfig {
  return {
    configPath: join(distributionDir, 'daemon.json'),
    dataDir: join(distributionDir, 'data'),
    tokenPath: join(distributionDir, 'daemon.token'),
    studioUrl: 'http://127.0.0.1:9999',
    gatewayUri: 'ws://127.0.0.1:1/api/harness/environment-daemon/v1',
    bashExecutable: 'bash',
    note: null,
    lsp: {},
    distributionDir,
  }
}

const credentials = { url: 'http://127.0.0.1:1', token: 'secret' }
const signal = (): AbortSignal => new AbortController().signal

describe('code-server process manager', () => {
  it.runIf(process.platform !== 'win32')('keeps IPC private and short even with a long persistent data path', async () => {
    const root = await distribution(IPC_READY_SERVER)
    const cfg = config(root)
    cfg.dataDir = join(root, '目录'.repeat(35))
    const supervisor = new TestSupervisor()
    cleanups.push(() => supervisor.close())
    const manager = createCodeServerManager(cfg, supervisor, credentials)
    await manager.ensureStarted(signal())
    const args = supervisor.specs[0]!.args
    const socket = args[args.indexOf('--session-socket') + 1]!
    expect(Buffer.byteLength(join(idePaths(cfg).userDataDir, 'code-server-ipc.sock'))).toBeGreaterThan(107)
    expect(Buffer.byteLength(socket)).toBeLessThanOrEqual(107)
    const info = await stat(dirname(socket))
    expect(info.mode & 0o777).toBe(0o700)
    expect(info.uid).toBe(process.getuid!())
    expect((await stat(socket)).isSocket()).toBe(true)
    const response = await new Promise<string>((resolve, reject) => {
      const req = request({ socketPath: socket, path: '/', agent: false }, (res) => {
        let body = ''
        res.setEncoding('utf8')
        res.on('data', (chunk: string) => { body += chunk })
        res.on('end', () => resolve(body))
        res.on('error', reject)
      })
      req.on('error', reject)
      req.end()
    })
    expect(response).toBe('private-session')
    const close = manager.close()
    expect(manager.close()).toBe(close)
    await close
    expect(existsSync(dirname(socket))).toBe(false)
  })

  it('derives private paths and resolves the bundled Node launcher', async () => {
    const root = await distribution(READY_SERVER)
    const paths = idePaths(config(root))
    expect(paths.codeServerDir).toBe(join(root, 'data', 'ide', 'code-server'))
    expect(paths.configFile).toBe(join(root, 'data', 'ide', 'code-server', 'config.yaml'))

    const launch = resolveCodeServerLaunch(root)
    expect(launch.executable).toBe(join(root, 'runtime', 'code-server', 'lib', process.platform === 'win32' ? 'node.exe' : 'node'))
    expect(launch.args).toEqual([join(root, 'runtime', 'code-server', 'out', 'node', 'entry.js')])

    expect(() => resolveCodeServerLaunch(join(root, 'absent'))).toThrow('code-server runtime is not installed')
  })

  it('starts once, keeps a bounded diagnostic tail and closes the process', async () => {
    const root = await distribution(READY_SERVER)
    const supervisor = new TestSupervisor()
    cleanups.push(() => supervisor.close())
    const manager = createCodeServerManager(config(root), supervisor, credentials)

    const session = await manager.ensureStarted(signal())
    expect(session.pid).toBeGreaterThan(0)
    expect(manager.session()).toBe(session)
    // Output is drained and retained only within the bounded tail.
    expect(manager.diagnostics().length).toBeGreaterThan(0)
    expect(manager.diagnostics().length).toBeLessThanOrEqual(4 * 1024)

    // A second call reuses the running session.
    expect(await manager.ensureStarted(signal())).toBe(session)
    await manager.close()
    expect(manager.session()).toBeUndefined()
  })

  it('aggregates a startup failure with a failed termination', async () => {
    const root = await distribution(NO_COOKIE_SERVER)
    const supervisor = new TestSupervisor()
    supervisor.options.terminateError = new Error('cannot terminate')
    cleanups.push(() => supervisor.close())
    const manager = createCodeServerManager(config(root), supervisor, credentials)

    await expect(manager.ensureStarted(signal())).rejects.toThrow(/could not be terminated/)
    await expect(manager.close()).rejects.toThrow('cannot terminate')
  })

  it('fails the login when the upstream connection resets', async () => {
    const root = await distribution(RESET_LOGIN_SERVER)
    const supervisor = new TestSupervisor()
    cleanups.push(() => supervisor.close())
    const manager = createCodeServerManager(config(root), supervisor, credentials)

    await expect(manager.ensureStarted(signal())).rejects.toThrow('code-server login failed')
  })

  it('aborts startup when the signal is already aborted', async () => {
    const root = await distribution(READY_SERVER)
    const supervisor = new TestSupervisor()
    cleanups.push(() => supervisor.close())
    const manager = createCodeServerManager(config(root), supervisor, credentials)

    const controller = new AbortController()
    controller.abort()
    await expect(manager.ensureStarted(controller.signal)).rejects.toMatchObject({ name: 'AbortError' })
    // A caller that gave up must not have started a process at all.
    expect(supervisor.children).toHaveLength(0)
  })

  it('keeps a shared startup running when one waiting caller cancels', async () => {
    const root = await distribution(SLOW_READY_SERVER)
    const supervisor = new TestSupervisor()
    cleanups.push(() => supervisor.close())
    const manager = createCodeServerManager(config(root), supervisor, credentials)

    const controller = new AbortController()
    const cancelled = manager.ensureStarted(controller.signal)
    controller.abort()
    await expect(cancelled).rejects.toMatchObject({ name: 'AbortError' })

    // The startup is owned by the manager, so a later caller joins the same in-flight attempt.
    const session = await manager.ensureStarted(signal())
    expect(session.pid).toBeGreaterThan(0)
    expect(manager.session()).toBe(session)
    expect(supervisor.children).toHaveLength(1)
    await manager.close()
  })

  it('closes cleanly while a startup is still in flight', async () => {
    const root = await distribution(SLOW_READY_SERVER)
    const supervisor = new TestSupervisor()
    cleanups.push(() => supervisor.close())
    const manager = createCodeServerManager(config(root), supervisor, credentials)

    const pending = manager.ensureStarted(signal())
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    await delay(50)
    await manager.close()
    expect(manager.session()).toBeUndefined()
    await rejected
    for (const child of supervisor.children) {
      expect(child.exitCode !== null || child.signalCode !== null).toBe(true)
    }
    await expect(manager.ensureStarted(signal())).rejects.toThrow('ide service is closed')
  })

  it('converges an exited scope before starting a replacement', async () => {
    const root = await distribution(READY_SERVER)
    const supervisor = new TestSupervisor()
    cleanups.push(() => supervisor.close())
    const manager = createCodeServerManager(config(root), supervisor, credentials)
    const first = await manager.ensureStarted(signal())
    const child = supervisor.children[0]!
    child.kill('SIGKILL')
    await new Promise<void>((resolve) => child.once('exit', () => resolve()))
    expect(manager.session()).toBeUndefined()
    const replacement = await manager.ensureStarted(signal())
    expect(replacement.pid).not.toBe(first.pid)
    expect(supervisor.terminatedPids).toContain(first.pid)
    await manager.close()
  })

  it('reports only unexpected exits, not intentional idle/update stops', async () => {
    const root = await distribution(READY_SERVER)
    const supervisor = new TestSupervisor()
    cleanups.push(() => supervisor.close())
    const manager = createCodeServerManager(config(root), supervisor, credentials)
    const exited: number[] = []
    manager.onUnexpectedExit((generation) => exited.push(generation))
    const first = await manager.ensureStarted(signal())

    await manager.stop()
    await delay(0)
    expect(exited).toEqual([])

    const replacement = await manager.ensureStarted(signal())
    expect(replacement.generation).toBeGreaterThan(first.generation)
    const child = supervisor.children[1]!
    child.kill('SIGKILL')
    await new Promise<void>((resolve) => child.once('exit', () => resolve()))
    await delay(0)
    expect(exited.length).toBe(1)
    await manager.close()
  })

  it('surfaces a termination failure on close', async () => {
    const root = await distribution(READY_SERVER)
    const supervisor = new TestSupervisor()
    cleanups.push(() => supervisor.close())
    const manager = createCodeServerManager(config(root), supervisor, credentials)
    await manager.ensureStarted(signal())

    supervisor.options.terminateError = new Error('cannot terminate')
    await expect(manager.close()).rejects.toThrow('cannot terminate')
    const args = supervisor.specs[0]!.args
    if (process.platform !== 'win32') {
      expect(existsSync(dirname(args[args.indexOf('--session-socket') + 1]!))).toBe(true)
    }
  })

  // Regression for the Windows EBUSY cleanup race: the fixture must prove stdio teardown before a
  // deleted distribution can be reported released.
  it('awaits stdio close before termination and supervisor close resolve', async () => {
    const supervisor = new TestSupervisor()
    const spawnSpec = (): ProcessSpec => ({
      executable: process.execPath,
      args: ['-e', 'setInterval(() => {}, 1000)'],
      cwd: tmpdir(),
    })

    const terminated = await supervisor.start(spawnSpec())
    const terminatedChild = supervisor.children[0]!
    let terminatedClosed = false
    terminatedChild.on('close', () => {
      terminatedClosed = true
    })
    await terminated.terminate()
    expect(terminatedChild.exitCode !== null || terminatedChild.signalCode !== null).toBe(true)
    expect(terminatedClosed).toBe(true)

    await supervisor.start(spawnSpec())
    const closedChild = supervisor.children[1]!
    let closed = false
    closedChild.on('close', () => {
      closed = true
    })
    await supervisor.close()
    expect(closed).toBe(true)
  })
})
