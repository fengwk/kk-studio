import { afterEach, describe, expect, it } from 'vitest'
import { spawn, type ChildProcess } from 'node:child_process'
import { chmod, mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
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

function delay(millis: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, millis))
}

class TestSupervisor implements ProcessSupervisor {
  readonly children: ChildProcess[] = []
  readonly options: { terminateError?: Error } = {}

  async start(spec: ProcessSpec): Promise<ManagedProcess> {
    const child = spawn(spec.executable, spec.args, { cwd: spec.cwd, env: spec.env, stdio: ['pipe', 'pipe', 'pipe'] })
    this.children.push(child)
    const exit = new Promise<ProcessExit>((resolve) => {
      child.on('exit', (code, signal) => resolve({ exitCode: code, signal }))
    })
    return {
      pid: child.pid as number,
      stdin: child.stdin as ManagedProcess['stdin'],
      stdout: child.stdout as ManagedProcess['stdout'],
      stderr: child.stderr as ManagedProcess['stderr'],
      exit,
      terminate: async () => {
        // Read the flag lazily so a test can fail termination after startup.
        if (this.options.terminateError) {
          throw this.options.terminateError
        }
        if (child.exitCode === null && child.signalCode === null) {
          child.kill('SIGKILL')
        }
        await exit
      },
    }
  }

  async trackDescendant(): Promise<void> {}

  async close(): Promise<void> {
    for (const child of this.children) {
      if (child.exitCode === null && child.signalCode === null) {
        child.kill('SIGKILL')
      }
    }
  }
}

async function distribution(script: string): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), 'kk-ide-manager-'))
  cleanups.push(() => rm(root, { recursive: true, force: true }))
  const codeServerRoot = join(root, 'runtime', 'code-server')
  await mkdir(join(codeServerRoot, 'lib'), { recursive: true })
  await mkdir(join(codeServerRoot, 'out', 'node'), { recursive: true })
  const launcher = join(codeServerRoot, 'lib', 'node')
  await writeFile(launcher, '#!/bin/sh\nexec node "$@"\n')
  await chmod(launcher, 0o755)
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
    await delay(50)
    await manager.close()
    expect(manager.session()).toBeUndefined()
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    for (const child of supervisor.children) {
      expect(child.exitCode !== null || child.signalCode !== null).toBe(true)
    }
    await expect(manager.ensureStarted(signal())).rejects.toThrow('ide service is closed')
  })

  it('surfaces a termination failure on close', async () => {
    const root = await distribution(READY_SERVER)
    const supervisor = new TestSupervisor()
    cleanups.push(() => supervisor.close())
    const manager = createCodeServerManager(config(root), supervisor, credentials)
    await manager.ensureStarted(signal())

    supervisor.options.terminateError = new Error('cannot terminate')
    await expect(manager.close()).rejects.toThrow('cannot terminate')
  })
})
