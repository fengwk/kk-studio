import { afterEach, describe, expect, it } from 'vitest'
import { spawn, type ChildProcess } from 'node:child_process'
import { mkdtemp, mkdir, readFile, realpath, rm, stat, symlink, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import http from 'node:http'
import type { AddressInfo } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { WebSocketServer, WebSocket } from 'ws'
import type {
  DaemonConfig,
  ManagedProcess,
  ProcessExit,
  ProcessSpec,
  ProcessSupervisor,
} from '../../src/contracts.js'
import { createIdeService, resolveRelativeStream } from '../../src/ide/service.js'
import { decodeBinaryFrame, decodeTextFrame } from '../../src/ide/wire.js'

const cleanups: (() => Promise<void> | void)[] = []

afterEach(async () => {
  for (const cleanup of cleanups.splice(0).reverse()) {
    await cleanup()
  }
})

function delay(millis: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, millis))
}

async function waitFor(predicate: () => boolean, timeoutMillis = 10_000): Promise<void> {
  const deadline = Date.now() + timeoutMillis
  while (!predicate()) {
    if (Date.now() > deadline) {
      throw new Error('condition not met before timeout')
    }
    await delay(10)
  }
}

/** A code-server look-alike used to exercise the real daemon wiring without vendoring it. */
const FAKE_CODE_SERVER = `#!/usr/bin/env node
const http = require('node:http')
const fs = require('node:fs')
const args = process.argv.slice(2)
function arg(name) { const index = args.indexOf(name); return index >= 0 ? args[index + 1] : undefined }
let bind = arg('--bind-addr') || '127.0.0.1:0'
let password = 'unset'
const configPath = arg('--config')
if (configPath && fs.existsSync(configPath)) {
  const text = fs.readFileSync(configPath, 'utf8')
  const p = /password:\\s*(\\S+)/.exec(text); if (p) password = p[1]
  const b = /bind-addr:\\s*(\\S+)/.exec(text); if (b) bind = b[1]
}
const [host, portText] = bind.split(':')
const port = Number(portText)
const server = http.createServer((req, res) => {
  if (req.url === '/healthz') { res.writeHead(200, { 'content-type': 'application/json' }); res.end('{"status":"alive"}'); return }
  if (req.url === '/login' && req.method === 'POST') {
    let body = ''
    req.on('data', (chunk) => { body += chunk })
    req.on('end', () => {
      const provided = new URLSearchParams(body).get('password')
      if (provided === password) {
        res.writeHead(302, { 'set-cookie': 'code-server-session=fake-session; Path=/; SameSite=Lax', location: '/' })
        res.end()
      } else {
        res.writeHead(200)
        res.end('incorrect')
      }
    })
    return
  }
  if (req.url === '/echo') {
    res.writeHead(200, { 'content-type': 'text/plain', 'x-cookie': req.headers.cookie || 'none' })
    res.end('echo:' + req.method)
    return
  }
  res.writeHead(404)
  res.end('not found')
})
server.listen(port, host)
const shutdown = () => { server.close(() => process.exit(0)); setTimeout(() => process.exit(0), 200) }
process.on('SIGTERM', shutdown)
process.on('SIGINT', shutdown)
`

class FakeSupervisor implements ProcessSupervisor {
  readonly started: { spec: ProcessSpec; child: ChildProcess; closed: Promise<void> }[] = []
  readonly descendants: [number, number][] = []

  async start(spec: ProcessSpec): Promise<ManagedProcess> {
    const child = spawn(spec.executable, spec.args, {
      cwd: spec.cwd,
      env: spec.env,
      stdio: ['pipe', 'pipe', 'pipe'],
    })
    const exit = new Promise<ProcessExit>((resolve) => {
      child.on('exit', (code, signal) => resolve({ exitCode: code, signal }))
    })
    // Registered at spawn time: cleanup can only prove the fixture is fully released (stdio torn
    // down) by awaiting 'close', and a listener attached later could miss an already-emitted event.
    const closed = new Promise<void>((resolve) => {
      child.on('close', () => resolve())
    })
    this.started.push({ spec, child, closed })
    return {
      pid: child.pid as number,
      stdin: child.stdin as ManagedProcess['stdin'],
      stdout: child.stdout as ManagedProcess['stdout'],
      stderr: child.stderr as ManagedProcess['stderr'],
      exit,
      terminate: async (graceMillis = 2000) => {
        if (child.exitCode !== null || child.signalCode !== null) {
          await closed
          return
        }
        child.kill('SIGTERM')
        const killer = setTimeout(() => child.kill('SIGKILL'), graceMillis)
        try {
          await exit
        } finally {
          clearTimeout(killer)
        }
        // 'exit' fires before stdio teardown; Windows keeps the copied node.exe busy until 'close'.
        await closed
      },
    }
  }

  async trackDescendant(rootPid: number, childPid: number): Promise<void> {
    this.descendants.push([rootPid, childPid])
  }

  async close(): Promise<void> {
    await Promise.all(
      this.started.map(async ({ child, closed }) => {
        if (child.exitCode === null && child.signalCode === null) {
          child.kill('SIGKILL')
        }
        // Await every registered close, including children that exited but are still tearing down.
        await closed
      }),
    )
  }
}

const LOGIN_FAILURE_SERVER = `#!/usr/bin/env node
const http = require('node:http')
const args = process.argv.slice(2)
function arg(name) { const index = args.indexOf(name); return index >= 0 ? args[index + 1] : undefined }
const bind = arg('--bind-addr') || '127.0.0.1:0'
const [host, portText] = bind.split(':')
http.createServer((req, res) => {
  if (req.url === '/healthz') { res.writeHead(200); res.end('{}'); return }
  res.writeHead(200); res.end('incorrect')
}).listen(Number(portText), host)
`

async function createDistribution(script: string = FAKE_CODE_SERVER): Promise<string> {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'kk-ide-')))
  cleanups.push(() => rm(root, { recursive: true, force: true }))
  const codeServerRoot = join(root, 'runtime', 'code-server')
  await mkdir(join(codeServerRoot, 'out', 'node'), { recursive: true })
  if (process.platform === 'win32') {
    // Windows launches lib/node.exe. A junction to the Node install directory keeps the launcher a
    // live reference to the current Node, so fixture removal deletes only the link instead of a
    // copied image that the running process still holds open (the Windows EBUSY cleanup race).
    await symlink(dirname(process.execPath), join(codeServerRoot, 'lib'), 'junction')
  } else {
    // Unix keeps the shell launcher named lib/node, which needs no executable image at all.
    const launcher = join(codeServerRoot, 'lib', 'node')
    await mkdir(dirname(launcher), { recursive: true })
    await writeFile(launcher, '#!/bin/sh\nexec node "$@"\n')
    const { chmod } = await import('node:fs/promises')
    await chmod(launcher, 0o755)
  }
  await writeFile(join(codeServerRoot, 'out', 'node', 'entry.js'), script)
  const extensionDir = join(root, 'ide-extension')
  await mkdir(extensionDir, { recursive: true })
  await writeFile(join(extensionDir, 'package.json'), '{"name":"kk-studio-ide-bridge"}')
  return root
}

function makeConfig(distributionDir: string, gatewayHost: string): DaemonConfig {
  const dataDir = join(distributionDir, 'data')
  return {
    configPath: join(distributionDir, 'daemon.json'),
    dataDir,
    tokenPath: join(distributionDir, 'daemon.token'),
    studioUrl: 'http://127.0.0.1:9999',
    gatewayUri: `ws://${gatewayHost}/api/harness/environment-daemon/v1`,
    bashExecutable: 'bash',
    note: null,
    lsp: {},
    distributionDir,
  }
}

/** Minimal extension client that talks to the real loopback bridge. */
class FakeExtension {
  private readonly queue: Record<string, unknown>[] = []
  private readonly waiters: ((command: Record<string, unknown>) => void)[] = []
  private buffer = ''
  private request?: http.ClientRequest
  private response?: http.IncomingMessage

  constructor(
    private readonly base: string,
    private readonly token: string,
    readonly workspacePath: string,
    private folders: string[] = [],
  ) {}

  setFolders(folders: string[]): void {
    this.folders = folders
  }

  connect(): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const params = new URLSearchParams({ workspacePath: this.workspacePath })
      for (const folder of this.folders) {
        params.append('folder', folder)
      }
      const url = `${this.base}/bridge/stream?${params.toString()}`
      this.request = http.get(url, { headers: { authorization: `Bearer ${this.token}` } }, (response) => {
        this.response = response
        if (response.statusCode !== 200) {
          response.resume()
          reject(new Error(`unexpected status ${response.statusCode}`))
          return
        }
        response.setEncoding('utf8')
        response.on('data', (chunk: string) => this.onData(chunk))
        resolve()
      })
      this.request.on('error', reject)
    })
  }

  private onData(chunk: string): void {
    this.buffer += chunk
    let index = this.buffer.indexOf('\n')
    while (index >= 0) {
      const line = this.buffer.slice(0, index)
      this.buffer = this.buffer.slice(index + 1)
      index = this.buffer.indexOf('\n')
      if (line.trim().length === 0) {
        continue
      }
      const message = JSON.parse(line) as Record<string, unknown>
      if (message.type === 'command') {
        const waiter = this.waiters.shift()
        if (waiter) {
          waiter(message)
        } else {
          this.queue.push(message)
        }
      }
    }
  }

  nextCommand(timeoutMillis = 5000): Promise<Record<string, unknown>> {
    const queued = this.queue.shift()
    if (queued) {
      return Promise.resolve(queued)
    }
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('no command received')), timeoutMillis)
      this.waiters.push((command) => {
        clearTimeout(timer)
        resolve(command)
      })
    })
  }

  reply(result: Record<string, unknown>): Promise<void> {
    return this.post('/bridge/result', result)
  }

  pty(pid: number): Promise<void> {
    return this.post('/bridge/pty', { workspacePath: this.workspacePath, pid })
  }

  private post(path: string, payload: unknown): Promise<void> {
    return new Promise<void>((resolve) => {
      const body = Buffer.from(JSON.stringify(payload))
      const request = http.request(
        `${this.base}${path}`,
        {
          method: 'POST',
          headers: {
            'content-type': 'application/json',
            'content-length': body.length,
            authorization: `Bearer ${this.token}`,
          },
        },
        (response) => {
          response.resume()
          response.on('end', resolve)
        },
      )
      request.on('error', resolve)
      request.end(body)
    })
  }

  close(): void {
    this.response?.destroy()
    this.request?.destroy()
  }
}

function bridgeCredentials(supervisor: FakeSupervisor): { url: string; token: string } {
  const env = supervisor.started[0]?.spec.env ?? {}
  return { url: String(env.KK_STUDIO_IDE_BRIDGE_URL), token: String(env.KK_STUDIO_IDE_BRIDGE_TOKEN) }
}

function httpStatus(url: string, token?: string): Promise<number> {
  return new Promise((resolve, reject) => {
    http
      .get(url, { headers: token === undefined ? {} : { authorization: `Bearer ${token}` } }, (response) => {
        response.resume()
        resolve(response.statusCode ?? 0)
      })
      .on('error', reject)
  })
}

const signal = (): AbortSignal => new AbortController().signal

describe('managed code-server IDE service', () => {
  it('opens a view, starts code-server once and writes a private workspace file', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())

    const directory = join(distribution, 'project')
    await mkdir(directory, { recursive: true })
    const viewId = '22222222-2222-4222-8222-222222222222'
    const reply = await service.handle({ action: 'OPEN_VIEW', viewId, directory }, signal())

    expect(reply.ok).toBe(true)
    expect(reply.viewId).toBe(viewId)
    expect(reply.directory).toBe(directory)
    expect(supervisor.started).toHaveLength(1)
    const content = JSON.parse(await readFile(reply.workspacePath as string, 'utf8'))
    expect(content).toEqual({ folders: [{ path: directory }] })
    const ideDir = join(distribution, 'data', 'ide')
    if (process.platform === 'win32') {
      const { verifyPrivateFile, ensurePrivateDirectory } = await import('../../src/native/index.js')
      await expect(verifyPrivateFile(reply.workspacePath as string)).resolves.toBeUndefined()
      await expect(ensurePrivateDirectory(ideDir)).resolves.toBe(ideDir)
      await expect(ensurePrivateDirectory(join(ideDir, 'views'))).resolves.toBe(join(ideDir, 'views'))
    } else {
      expect((await stat(reply.workspacePath as string)).mode & 0o077).toBe(0)
      expect((await stat(ideDir)).mode & 0o777).toBe(0o700)
      expect((await stat(join(ideDir, 'views'))).mode & 0o777).toBe(0o700)
    }

    // Re-opening the same view reuses the already running service.
    const second = await service.handle({ action: 'OPEN_VIEW', viewId, directory }, signal())
    expect(second.ok).toBe(true)
    expect(supervisor.started).toHaveLength(1)
  })

  it('requires an absolute existing directory', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())

    const relative = await service.handle({ action: 'OPEN_VIEW', directory: 'relative/path' }, signal())
    expect(relative.ok).toBe(false)
    expect(relative.message).toBe('directory must be an absolute path')

    const missing = await service.handle({ action: 'OPEN_VIEW', directory: join(distribution, 'nope') }, signal())
    expect(missing.ok).toBe(false)
    expect(missing.message).toBe('directory does not exist')
    expect(supervisor.started).toHaveLength(0)
  })

  it('drives change directory, focus terminal and close view through the bridge', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())

    const directory = join(distribution, 'project')
    const other = join(distribution, 'other')
    await mkdir(directory, { recursive: true })
    await mkdir(other, { recursive: true })
    const viewId = '33333333-3333-4333-8333-333333333333'
    const opened = await service.handle({ action: 'OPEN_VIEW', viewId, directory }, signal())

    const credentials = bridgeCredentials(supervisor)
    const extension = new FakeExtension(credentials.url, credentials.token, opened.workspacePath as string, [directory])
    cleanups.push(() => extension.close())
    await extension.connect()

    // CHANGE_DIRECTORY rejected while dirty.
    const dirtyPending = service.handle({ action: 'CHANGE_DIRECTORY', viewId, directory: other }, signal())
    const dirtyCommand = await extension.nextCommand()
    expect(dirtyCommand.op).toBe('changeDirectory')
    await extension.reply({ id: dirtyCommand.id, ok: false, code: 'DIRTY', message: 'save first' })
    const dirtyReply = await dirtyPending
    expect(dirtyReply.ok).toBe(false)
    expect(dirtyReply.message).toBe('save first')

    // CHANGE_DIRECTORY accepted; the window reports the applied directory.
    const changePending = service.handle({ action: 'CHANGE_DIRECTORY', viewId, directory: other }, signal())
    const changeCommand = await extension.nextCommand()
    await extension.reply({ id: changeCommand.id, ok: true, directory: await realpath(other) })
    const changeReply = await changePending
    expect(changeReply.ok).toBe(true)
    expect(changeReply.directory).toBe(other)
    expect(JSON.parse(await readFile(opened.workspacePath as string, 'utf8'))).toEqual({ folders: [{ path: other }] })

    // FOCUS_TERMINAL registers the PTY pid with the native supervisor.
    const focusPending = service.handle({ action: 'FOCUS_TERMINAL', viewId }, signal())
    const focusCommand = await extension.nextCommand()
    expect(focusCommand.op).toBe('focusTerminal')
    await extension.reply({ id: focusCommand.id, ok: true })
    await extension.pty(7777)
    const focusReply = await focusPending
    expect(focusReply.ok).toBe(true)
    await waitFor(() => supervisor.descendants.length === 1)
    expect(supervisor.descendants[0]).toEqual([supervisor.started[0]?.child.pid, 7777])

    // CLOSE_VIEW asks the connected window to release terminals, then removes the workspace file.
    const closePending = service.handle({ action: 'CLOSE_VIEW', viewId }, signal())
    const closeCommand = await extension.nextCommand()
    expect(closeCommand.op).toBe('closeView')
    await extension.reply({ id: closeCommand.id, ok: true })
    const closed = await closePending
    expect(closed.ok).toBe(true)
    await expect(stat(opened.workspacePath as string)).rejects.toThrow()
    const closedAgain = await service.handle({ action: 'CLOSE_VIEW', viewId }, signal())
    expect(closedAgain.ok).toBe(true)
  })

  it('keeps an open view idempotent and refuses a conflicting directory', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())

    const directory = join(distribution, 'project')
    const other = join(distribution, 'other')
    await mkdir(directory, { recursive: true })
    await mkdir(other, { recursive: true })
    const viewId = '99999999-9999-4999-8999-999999999999'

    const first = await service.handle({ action: 'OPEN_VIEW', viewId, directory }, signal())
    expect(first.ok).toBe(true)

    // Omitting the directory must never reset an open view back to the default HOME.
    const reopened = await service.handle({ action: 'OPEN_VIEW', viewId }, signal())
    expect(reopened.ok).toBe(true)
    expect(reopened.directory).toBe(directory)

    // A different explicit directory conflicts and the existing view is preserved.
    const conflict = await service.handle({ action: 'OPEN_VIEW', viewId, directory: other }, signal())
    expect(conflict.ok).toBe(false)
    expect(conflict.directory).toBe(directory)
    expect(supervisor.started).toHaveLength(1)

    // The same explicit directory is idempotent.
    const same = await service.handle({ action: 'OPEN_VIEW', viewId, directory }, signal())
    expect(same.ok).toBe(true)
    expect(same.directory).toBe(directory)
    expect(supervisor.started).toHaveLength(1)
  })

  it('keeps the workspace when the window refuses to close while dirty', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())

    const directory = join(distribution, 'project')
    await mkdir(directory, { recursive: true })
    const viewId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
    const opened = await service.handle({ action: 'OPEN_VIEW', viewId, directory }, signal())
    const credentials = bridgeCredentials(supervisor)
    const extension = new FakeExtension(credentials.url, credentials.token, opened.workspacePath as string, [directory])
    cleanups.push(() => extension.close())
    await extension.connect()

    const dirtyPending = service.handle({ action: 'CLOSE_VIEW', viewId }, signal())
    const dirtyCommand = await extension.nextCommand()
    expect(dirtyCommand.op).toBe('closeView')
    await extension.reply({ id: dirtyCommand.id, ok: false, code: 'DIRTY', message: 'save first' })
    const dirty = await dirtyPending
    expect(dirty.ok).toBe(false)
    expect(dirty.message).toBe('save first')
    // The durable workspace survives a refused close.
    expect(JSON.parse(await readFile(opened.workspacePath as string, 'utf8'))).toEqual({ folders: [{ path: directory }] })

    // A later accepted close removes it.
    const retryPending = service.handle({ action: 'CLOSE_VIEW', viewId }, signal())
    const retryCommand = await extension.nextCommand()
    await extension.reply({ id: retryCommand.id, ok: true })
    expect((await retryPending).ok).toBe(true)
    await expect(stat(opened.workspacePath as string)).rejects.toThrow()
  })

  it('waits for the bridge to connect before focusing a terminal', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())

    const directory = join(distribution, 'project')
    await mkdir(directory, { recursive: true })
    const viewId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
    const opened = await service.handle({ action: 'OPEN_VIEW', viewId, directory }, signal())

    // The iframe asks for a terminal before its extension host has connected the bridge.
    const focusPending = service.handle({ action: 'FOCUS_TERMINAL', viewId }, signal())
    await delay(30)

    const credentials = bridgeCredentials(supervisor)
    const extension = new FakeExtension(credentials.url, credentials.token, opened.workspacePath as string, [directory])
    cleanups.push(() => extension.close())
    await extension.connect()

    const focusCommand = await extension.nextCommand()
    expect(focusCommand.op).toBe('focusTerminal')
    await extension.reply({ id: focusCommand.id, ok: true })
    await extension.pty(555)
    const focusReply = await focusPending
    expect(focusReply.ok).toBe(true)
    await waitFor(() => supervisor.descendants.length === 1)
    expect(supervisor.descendants[0]).toEqual([supervisor.started[0]?.child.pid, 555])

    // Cancelling the wait surfaces a failure instead of hanging until the command budget.
    const aborted = new AbortController()
    aborted.abort()
    const cancelled = await service.handle({ action: 'FOCUS_TERMINAL', viewId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' }, aborted.signal)
    expect(cancelled.ok).toBe(false)
  })

  it('refuses a bridge stream for an unassigned workspace', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())

    const directory = join(distribution, 'project')
    await mkdir(directory, { recursive: true })
    await service.handle({ action: 'OPEN_VIEW', directory }, signal())
    const credentials = bridgeCredentials(supervisor)

    // A valid secret cannot bind a stream to a workspace it was never assigned.
    const status = await httpStatus(
      `${credentials.url}/bridge/stream?workspacePath=${encodeURIComponent('/not/assigned.code-workspace')}`,
      credentials.token,
    )
    expect(status).toBe(403)

    // Without a valid secret the request never reaches routing.
    const badToken = await httpStatus(
      `${credentials.url}/bridge/stream?workspacePath=${encodeURIComponent('/not/assigned.code-workspace')}`,
      'wrong',
    )
    expect(badToken).toBe(401)
    const noToken = await httpStatus(
      `${credentials.url}/bridge/stream?workspacePath=${encodeURIComponent('/not/assigned.code-workspace')}`,
    )
    expect(noToken).toBe(401)
  })

  it('proxies an HTTP exchange to code-server and rejects a foreign stream url', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())

    // Gateway that speaks the reverse data plane.
    const gatewayServer = createServer()
    await new Promise<void>((resolve) => gatewayServer.listen(0, '127.0.0.1', resolve))
    const gatewayPort = (gatewayServer.address() as AddressInfo).port
    const wss = new WebSocketServer({ server: gatewayServer })
    const frames: string[] = []
    let body = ''
    let ended = false
    wss.on('connection', (socket) => {
      socket.on('message', (data, isBinary) => {
        if (!isBinary) {
          frames.push(decodeTextFrame(data.toString('utf8')).type)
          return
        }
        const frame = decodeBinaryFrame(data as Buffer)
        if (frame.kind === 'http-body') {
          body += frame.payload.toString('utf8')
        } else if (frame.kind === 'http-end') {
          ended = true
        }
      })
    })
    cleanups.push(
      () =>
        new Promise<void>((resolve) => {
          for (const client of wss.clients) {
            client.terminate()
          }
          wss.close(() => gatewayServer.close(() => resolve()))
        }),
    )

    const service = await createIdeService(makeConfig(distribution, `127.0.0.1:${gatewayPort}`), supervisor)
    cleanups.push(() => service.close())

    const directory = join(distribution, 'project')
    await mkdir(directory, { recursive: true })
    await service.handle({ action: 'OPEN_VIEW', directory }, signal())

    const streamUrl = `/api/harness/environment-stream/v1/ticket-1`
    const reply = await service.handle(
      { action: 'OPEN_STREAM', streamUrl, ticket: 'ticket-1', mode: 'HTTP', method: 'GET', path: '/echo', headers: [] },
      signal(),
    )
    expect(reply.ok).toBe(true)
    await waitFor(() => ended)
    expect(frames[0]).toBe('HTTP_RESPONSE')
    expect(body).toBe('echo:GET')
  })

  it('validates stream url credentials, traversal and transport security', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())

    const base = { action: 'OPEN_STREAM' as const, mode: 'HTTP' as const, method: 'GET', path: '/echo', headers: [] }

    // Credentials are never accepted in the stream url.
    const credentialed = await service.handle(
      { ...base, streamUrl: 'ws://user:pass@127.0.0.1:1/api/harness/environment-stream/v1/t' },
      signal(),
    )
    expect(credentialed.ok).toBe(false)
    expect(credentialed.message).toBe('streamUrl is invalid')

    // A traversal-looking path is not a stream endpoint.
    const traversal = await service.handle(
      { ...base, streamUrl: '/api/harness/environment-stream/v1..evil' },
      signal(),
    )
    expect(traversal.ok).toBe(false)
    expect(traversal.message).toBe('streamUrl is not a stream endpoint')

    // Absolute URLs with wrong host are rejected.
    const secureConfig = { ...makeConfig(distribution, '127.0.0.1:1'), gatewayUri: 'wss://gateway.example.com/api/harness/environment-daemon/v1' }
    const secureService = await createIdeService(secureConfig, supervisor)
    cleanups.push(() => secureService.close())
    const downgraded = await secureService.handle(
      { ...base, streamUrl: 'ws://owner-node.example.com/api/harness/environment-stream/v1/t' },
      signal(),
    )
    expect(downgraded.ok).toBe(false)
    expect(downgraded.message).toBe('streamUrl is invalid')
  })

  it('rejects an unsafe IDE path', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())

    const reply = await service.handle(
      { action: 'OPEN_STREAM', streamUrl: '/api/harness/environment-stream/v1/t', mode: 'HTTP', method: 'GET', path: 'http://evil', headers: [] },
      signal(),
    )
    expect(reply.ok).toBe(false)
    expect(reply.message).toBe('invalid request path')
  })

  it('generates a view id and defaults to the host HOME', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())

    const reply = await service.handle({ action: 'OPEN_VIEW' }, signal())
    expect(reply.ok).toBe(true)
    expect(reply.viewId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(reply.directory).toBe(process.env.HOME)
  })

  it('reports a missing runtime and a missing bridge extension', async () => {
    const bare = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())

    const directory = join(bare, 'project')
    await mkdir(directory, { recursive: true })

    // Runtime present but the bundled extension is gone.
    await rm(join(bare, 'ide-extension'), { recursive: true, force: true })
    const service = await createIdeService(makeConfig(bare, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())
    const missingExtension = await service.handle({ action: 'OPEN_VIEW', directory }, signal())
    expect(missingExtension.ok).toBe(false)
    expect(missingExtension.message).toBe('bundled ide bridge extension is missing')

    // Runtime itself is absent.
    const absent = await createIdeService(makeConfig(join(bare, 'absent'), '127.0.0.1:1'), supervisor)
    cleanups.push(() => absent.close())
    const missingRuntime = await absent.handle({ action: 'OPEN_VIEW', directory }, signal())
    expect(missingRuntime.ok).toBe(false)
    expect(missingRuntime.message).toBe('code-server runtime is not installed')
  })

  it('validates command arguments and surfaces bridge failures', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())

    const unknown = await service.handle({ action: 'NOPE' as never }, signal())
    expect(unknown.ok).toBe(false)
    expect(unknown.message).toBe('unknown ide action')

    const noView = await service.handle({ action: 'CLOSE_VIEW' }, signal())
    expect(noView.ok).toBe(false)
    expect(noView.message).toBe('viewId is required')

    const badId = await service.handle({ action: 'OPEN_VIEW', viewId: 'pane-1' }, signal())
    expect(badId.ok).toBe(false)
    expect(badId.message).toBe('viewId must be a UUID')

    const missingView = await service.handle({ action: 'FOCUS_TERMINAL', viewId: '44444444-4444-4444-8444-444444444444' }, signal())
    expect(missingView.ok).toBe(false)
    expect(missingView.message).toBe('view is not open')

    const directory = join(distribution, 'project')
    await mkdir(directory, { recursive: true })
    const viewId = '55555555-5555-4555-8555-555555555555'
    const opened = await service.handle({ action: 'OPEN_VIEW', viewId, directory }, signal())
    const noDirectory = await service.handle({ action: 'CHANGE_DIRECTORY', viewId }, signal())
    expect(noDirectory.ok).toBe(false)
    expect(noDirectory.message).toBe('directory is required')

    const credentials = bridgeCredentials(supervisor)
    const extension = new FakeExtension(credentials.url, credentials.token, opened.workspacePath as string)
    cleanups.push(() => extension.close())
    await extension.connect()

    const focusPending = service.handle({ action: 'FOCUS_TERMINAL', viewId }, signal())
    const focusCommand = await extension.nextCommand()
    await extension.reply({ id: focusCommand.id, ok: false, message: 'terminal unavailable' })
    const focusReply = await focusPending
    expect(focusReply.ok).toBe(false)
    expect(focusReply.message).toBe('terminal unavailable')
  })

  it('rejects OPEN_STREAM with an invalid mode and unsupported headers', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())

    const mode = await service.handle(
      { action: 'OPEN_STREAM', streamUrl: '/api/harness/environment-stream/v1/t', mode: 'FTP' as never, path: '/' },
      signal(),
    )
    expect(mode.ok).toBe(false)
    expect(mode.message).toBe('mode must be HTTP or WS')

    const headers = await service.handle(
      {
        action: 'OPEN_STREAM',
        streamUrl: '/api/harness/environment-stream/v1/t',
        mode: 'HTTP',
        method: 'GET',
        path: '/',
        headers: [['x', 'bad\u0000value']],
      },
      signal(),
    )
    expect(headers.ok).toBe(false)
    expect(headers.message).toBe('invalid request headers')
  })

  it('requires a view id and stream url', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())

    const noView = await service.handle({ action: 'FOCUS_TERMINAL' }, signal())
    expect(noView.ok).toBe(false)
    expect(noView.message).toBe('viewId is required')

    const noUrl = await service.handle({ action: 'OPEN_STREAM', mode: 'HTTP', path: '/' }, signal())
    expect(noUrl.ok).toBe(false)
    expect(noUrl.message).toBe('streamUrl and path are required')
  })

  it('validates stream urls, tickets and headers', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())

    const base = { action: 'OPEN_STREAM' as const, mode: 'HTTP' as const, method: 'GET', path: '/' }
    const stream = '/api/harness/environment-stream/v1/t'
    expect((await service.handle({ ...base, streamUrl: 'not a url' }, signal())).message).toBe('streamUrl is invalid')
    expect((await service.handle({ ...base, streamUrl: 'http://127.0.0.1:1/x' }, signal())).message).toBe(
      'streamUrl is invalid',
    )
    expect((await service.handle({ ...base, streamUrl: '/api/harness/environment-stream/v1/other..bad' }, signal())).message).toBe(
      'streamUrl is not a stream endpoint',
    )
    const tooMany = Array.from({ length: 129 }, (_value, index) => [`x${index}`, 'v'] as [string, string])
    expect((await service.handle({ ...base, streamUrl: stream, headers: tooMany }, signal())).message).toBe(
      'invalid request headers',
    )
    expect(
      (await service.handle({ ...base, streamUrl: stream, headers: [['x'] as unknown as [string, string]] }, signal())).message,
    ).toBe('invalid request headers')
    expect((await service.handle({ ...base, streamUrl: stream, headers: [['', 'v']] }, signal())).message).toBe(
      'invalid request headers',
    )

    // A complete ticket endpoint forwards its subprotocol; the dead gateway fails safely.
    const attempted = await service.handle(
      {
        ...base,
        streamUrl: '/api/harness/environment-stream/v1/tk',
        ticket: 'tk',
        headers: [
          ['sec-websocket-protocol', 'vscode-remote'],
          ['accept', '*/*'],
        ],
      },
      signal(),
    )
    expect(attempted.ok).toBe(false)
  })

  it('resolves relative stream url and validates tls, contextpath, wronghost, credential and fragment', () => {
    const config = makeConfig('/dummy', 'gateway.example.com:8443')
    config.gatewayUri = 'wss://gateway.example.com:8443/api/harness/environment-daemon/v1'

    const relativeStream = '/api/harness/environment-stream/v1/ticket-1?environment=11111111-2222-4333-8444-555555555555'
    // TLS and authority come only from the authenticated control gateway.
    const secureResolved = resolveRelativeStream(config, relativeStream, 'ticket-1')
    expect(secureResolved).toBe(`wss://gateway.example.com:8443${relativeStream}`)

    // validtls (ws)
    const plainConfig = { ...config, gatewayUri: 'ws://127.0.0.1:8080/api/harness/environment-daemon/v1' }
    const plainResolved = resolveRelativeStream(plainConfig, relativeStream)
    expect(plainResolved).toBe(`ws://127.0.0.1:8080${relativeStream}`)

    // 连接地址的部署前缀只保留一次；stream 命令不提供第二份网络权威。
    const ctxConfig = {
      ...config,
      studioUrl: 'https://gateway.example.com:8443/studio/subpath',
      gatewayUri: 'wss://gateway.example.com:8443/studio/subpath/api/harness/environment-daemon/v1',
    }
    const ctxResolved = resolveRelativeStream(ctxConfig, relativeStream)
    expect(ctxResolved).toBe(`wss://gateway.example.com:8443/studio/subpath${relativeStream}`)

    // wronghost: off-origin absolute URL rejected
    expect(() =>
      resolveRelativeStream(config, 'ws://wrong-host.example.com/api/harness/environment-stream/v1/t'),
    ).toThrow('streamUrl is invalid')

    // credential: rejected
    expect(() =>
      resolveRelativeStream(config, 'ws://user:pass@gateway.example.com:8443/api/harness/environment-stream/v1/t'),
    ).toThrow('streamUrl is invalid')
    expect(() =>
      resolveRelativeStream(config, '/api/harness/environment-stream/v1/t@user'),
    ).toThrow('streamUrl is not a stream endpoint')

    // fragment: rejected
    expect(() =>
      resolveRelativeStream(config, '/api/harness/environment-stream/v1/t#section'),
    ).toThrow('streamUrl is not a stream endpoint')

    // network path: rejected
    expect(() =>
      resolveRelativeStream(config, '//gateway.example.com/api/harness/environment-stream/v1/t'),
    ).toThrow('streamUrl is invalid')

    // A command cannot supply a second ticket authority or an incomplete endpoint.
    expect(() => resolveRelativeStream(config, relativeStream, 'different-ticket'))
      .toThrow('streamUrl ticket does not match')
    for (const invalid of [
      '/api/harness/environment-stream/v1/',
      `${relativeStream}&environment=11111111-2222-4333-8444-555555555555`,
      relativeStream.replace('11111111-2222-4333-8444-555555555555', 'not-a-uuid'),
      `${relativeStream}&extra=1`,
    ]) {
      expect(() => resolveRelativeStream(config, invalid, 'ticket-1'))
        .toThrow('streamUrl is not a stream endpoint')
    }
  })

  it('reports code-server startup and login failures', async () => {
    const exiting = await createDistribution('#!/usr/bin/env node\nprocess.exit(3)\n')
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(exiting, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())
    const directory = join(exiting, 'project')
    await mkdir(directory, { recursive: true })
    const exited = await service.handle({ action: 'OPEN_VIEW', directory }, signal())
    expect(exited.ok).toBe(false)
    expect(exited.message).toBe('code-server exited during startup')

    const noLogin = await createDistribution(LOGIN_FAILURE_SERVER)
    const supervisor2 = new FakeSupervisor()
    cleanups.push(() => supervisor2.close())
    const service2 = await createIdeService(makeConfig(noLogin, '127.0.0.1:1'), supervisor2)
    cleanups.push(() => service2.close())
    const directory2 = join(noLogin, 'project')
    await mkdir(directory2, { recursive: true })
    const login = await service2.handle({ action: 'OPEN_VIEW', directory: directory2 }, signal())
    expect(login.ok).toBe(false)
    expect(login.message).toBe('code-server login failed')
  })

  it('requires native supervision before focusing a terminal', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    // A supervisor that cannot confine the PTY must not be used to run a shell.
    ;(supervisor as { trackDescendant?: unknown }).trackDescendant = undefined
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())

    const directory = join(distribution, 'project')
    await mkdir(directory, { recursive: true })
    const viewId = '66666666-6666-4666-8666-666666666666'
    await service.handle({ action: 'OPEN_VIEW', viewId, directory }, signal())
    const reply = await service.handle({ action: 'FOCUS_TERMINAL', viewId }, signal())
    expect(reply.ok).toBe(false)
    expect(reply.message).toBe('terminal supervision is unavailable')
  })

  it('restarts code-server after a natural exit', async () => {
    const distribution = await createDistribution()
    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    cleanups.push(() => service.close())

    const directory = join(distribution, 'project')
    await mkdir(directory, { recursive: true })
    const viewId = '77777777-7777-4777-8777-777777777777'
    const first = await service.handle({ action: 'OPEN_VIEW', viewId, directory }, signal())
    expect(first.ok).toBe(true)
    expect(supervisor.started).toHaveLength(1)

    const child = supervisor.started[0]?.child
    child?.kill('SIGKILL')
    await waitFor(() => child !== undefined && (child.exitCode !== null || child.signalCode !== null))

    // The next OPEN_VIEW observes the exit and lazily starts a fresh process.
    const deadline = Date.now() + 10_000
    while (supervisor.started.length < 2 && Date.now() < deadline) {
      await service.handle({ action: 'OPEN_VIEW', viewId, directory }, signal())
      if (supervisor.started.length < 2) {
        await delay(10)
      }
    }
    expect(supervisor.started).toHaveLength(2)
  })

  it('reuses an already installed bridge extension and stops the process on close', async () => {
    const distribution = await createDistribution()
    const extensionsDir = join(distribution, 'data', 'ide', 'code-server', 'extensions', 'kk-studio-ide-bridge')
    await mkdir(extensionsDir, { recursive: true })

    const supervisor = new FakeSupervisor()
    cleanups.push(() => supervisor.close())
    const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
    const directory = join(distribution, 'project')
    await mkdir(directory, { recursive: true })
    const reply = await service.handle({ action: 'OPEN_VIEW', directory }, signal())
    expect(reply.ok).toBe(true)

    const child = supervisor.started[0]?.child
    await service.close()
    await waitFor(() => child !== undefined && (child.exitCode !== null || child.signalCode !== null))
    expect(child?.exitCode !== null || child?.signalCode !== null).toBe(true)
  })

  // Regression for the Windows EBUSY cleanup race: both closing entrances must prove stdio
  // teardown ('close'), not merely process exit, before the fixture directory is removed.
  describe('process fixture lifecycle', () => {
    const spawnSpec = (): ProcessSpec => ({
      executable: process.execPath,
      args: ['-e', 'setInterval(() => {}, 1000)'],
      cwd: tmpdir(),
    })

    it('awaits stdio close before terminate resolves for a running child', async () => {
      const supervisor = new FakeSupervisor()
      const managed = await supervisor.start(spawnSpec())
      const child = supervisor.started[0]!.child
      let closed = false
      child.on('close', () => {
        closed = true
      })

      await managed.terminate(50)

      expect(child.exitCode !== null || child.signalCode !== null).toBe(true)
      expect(closed).toBe(true)
    })

    it('awaits stdio close before terminate resolves for an already exited child', async () => {
      const supervisor = new FakeSupervisor()
      const managed = await supervisor.start({ ...spawnSpec(), args: ['-e', 'process.exit(0)'] })
      const child = supervisor.started[0]!.child
      let closed = false
      child.on('close', () => {
        closed = true
      })
      await new Promise<void>((resolve) => child.once('exit', () => resolve()))

      await managed.terminate(50)

      expect(closed).toBe(true)
    })

    it('awaits stdio close for running and exited children before supervisor close resolves', async () => {
      const supervisor = new FakeSupervisor()
      await supervisor.start(spawnSpec())
      await supervisor.start(spawnSpec())
      const running = supervisor.started[0]!.child
      const exited = supervisor.started[1]!.child
      let runningClosed = false
      let exitedClosed = false
      running.on('close', () => {
        runningClosed = true
      })
      exited.on('close', () => {
        exitedClosed = true
      })
      exited.kill('SIGKILL')
      await new Promise<void>((resolve) => exited.once('exit', () => resolve()))

      await supervisor.close()

      expect(runningClosed).toBe(true)
      expect(exitedClosed).toBe(true)
    })
  })

  // Regression for the Windows EBUSY cleanup race: the fixture must expose lib/node.exe as a
  // junction to the installed Node, so removing the fixture deletes only the link rather than a
  // copied image the (now closed) process once held open.
  it.runIf(process.platform === 'win32')(
    'keeps the installed Node usable after the fixture launcher junction is removed',
    async () => {
      const distribution = await createDistribution()
      const supervisor = new FakeSupervisor()
      cleanups.push(() => supervisor.close())
      const service = await createIdeService(makeConfig(distribution, '127.0.0.1:1'), supervisor)
      cleanups.push(() => service.close())

      const launcher = join(distribution, 'runtime', 'code-server', 'lib', 'node.exe')
      // The bundled launcher resolves to the current Node, proving nothing was copied into the fixture.
      expect(await realpath(launcher)).toBe(await realpath(process.execPath))

      const directory = join(distribution, 'project')
      await mkdir(directory, { recursive: true })
      const viewId = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'
      const reply = await service.handle({ action: 'OPEN_VIEW', viewId, directory }, signal())
      expect(reply.ok).toBe(true)
      // code-server really booted through the junctioned launcher instead of a bypassed executable.
      expect(supervisor.started).toHaveLength(1)
      expect(supervisor.started[0]!.spec.executable).toBe(launcher)
      const child = supervisor.started[0]!.child
      expect(child.pid).toBeGreaterThan(0)

      // The fixed terminate/close await proves stdio teardown before removal.
      await service.close()
      expect(child.exitCode !== null || child.signalCode !== null).toBe(true)

      // Removing the whole fixture, including the lib junction, must leave the installed Node intact.
      await rm(distribution, { recursive: true, force: true })
      await expect(realpath(process.execPath)).resolves.toBeTruthy()
      const version = await new Promise<number>((resolve, reject) => {
        const probe = spawn(process.execPath, ['--version'], { stdio: ['ignore', 'ignore', 'inherit'] })
        probe.on('error', reject)
        probe.on('close', (code) => resolve(code ?? -1))
      })
      expect(version).toBe(0)
    },
  )
})
