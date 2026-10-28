import { afterEach, describe, expect, it } from 'vitest'
import { randomUUID } from 'node:crypto'
import { mkdtemp, mkdir, readFile, realpath, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, sep } from 'node:path'
import type {
  DaemonConfig,
  IdeCommand,
  IdeStreamRequest,
  ProcessSupervisor,
} from '../../src/contracts.js'
import type { IdeBridge, BridgeCommand, BridgeRequestOptions, BridgeResult } from '../../src/ide/bridge.js'
import type {
  CodeServerManager,
  CodeServerSession,
  ManagedViewCloseMode,
  ManagedViewCloseResult,
  ManagedViewInspection,
} from '../../src/ide/codeServer.js'
import {
  IDE_IDLE_STOP_MILLIS,
  IDE_PROOF_EXCLUSIVITY_MILLIS,
  IDE_VIEW_EXPIRY_MILLIS,
  createIdeService,
  type IdeScheduler,
  type IdeServiceEvent,
} from '../../src/ide/service.js'
import { listViews, openCommand, signal } from './lifecycle-fixtures.js'
import { decodeIdeReply, encodeIdeReply } from '../../src/protocol.js'

const cleanups: Array<() => Promise<void> | void> = []
afterEach(async () => {
  for (const cleanup of cleanups.splice(0).reverse()) {
    await cleanup()
  }
})

async function flush(): Promise<void> {
  for (let index = 0; index < 40; index++) {
    await Promise.resolve()
  }
}

/** Wait for a real async filesystem closure without using a timing sleep. */
async function waitFor(predicate: () => boolean): Promise<void> {
  const deadline = Date.now() + 5_000
  while (Date.now() < deadline) {
    await new Promise<void>((resolve) => setImmediate(resolve))
    if (predicate()) {
      return
    }
  }
  throw new Error('condition was not met')
}

class TestScheduler implements IdeScheduler {
  private current = 0
  private nextId = 1
  private readonly timers = new Map<number, { at: number; callback: () => void }>()

  now(): number {
    return this.current
  }

  setTimeout(callback: () => void, delayMillis: number): unknown {
    const id = this.nextId++
    this.timers.set(id, { at: this.current + Math.max(0, delayMillis), callback })
    return id
  }

  clearTimeout(handle: unknown): void {
    this.timers.delete(handle as number)
  }

  debugTimers(): Array<{ id: number; at: number }> {
    return [...this.timers.entries()].map(([id, timer]) => ({ id, at: timer.at }))
  }

  async advance(millis: number): Promise<void> {
    const target = this.current + millis
    for (;;) {
      let selected: [number, { at: number; callback: () => void }] | undefined
      for (const entry of this.timers) {
        if (entry[1].at <= target && (selected === undefined || entry[1].at < selected[1].at)) {
          selected = entry
        }
      }
      if (selected === undefined) {
        break
      }
      this.timers.delete(selected[0])
      this.current = selected[1].at
      selected[1].callback()
      await flush()
    }
    this.current = target
    await flush()
  }
}

class FakeManager implements CodeServerManager {
  readonly registers: Array<{ viewId: string; bindingEpoch: string; workspacePath: string }> = []
  readonly closes: Array<{ viewId: string; bindingEpoch: string; mode: ManagedViewCloseMode }> = []
  closeFail = false
  stopCalls = 0
  stopError: Error | undefined
  registerGate: Promise<void> | undefined
  stopGate: Promise<void> | undefined
  private generation = 1
  private current: CodeServerSession | undefined = {
    host: '127.0.0.1',
    port: 4100,
    cookie: 'code-server-session=test',
    pid: 4242,
    generation: 1,
    managementToken: 'test-management-token',
  }
  private readonly listeners = new Set<(generation: number) => void>()

  async ensureStarted(_signal: AbortSignal): Promise<CodeServerSession> {
    if (this.current === undefined) {
      this.generation += 1
      this.current = {
        host: '127.0.0.1',
        port: 4100,
        cookie: 'code-server-session=test',
        pid: 4242 + this.generation,
        generation: this.generation,
        managementToken: 'test-management-token',
      }
    }
    return this.current
  }

  session(): CodeServerSession | undefined {
    return this.current
  }

  diagnostics(): string {
    return ''
  }

  async stop(): Promise<void> {
    this.stopCalls++
    if (this.stopGate !== undefined) {
      await this.stopGate
    }
    if (this.stopError !== undefined) {
      throw this.stopError
    }
    this.current = undefined
  }

  async close(): Promise<void> {
    await this.stop()
  }

  async registerView(viewId: string, bindingEpoch: string, workspacePath: string): Promise<ManagedViewInspection> {
    if (this.registerGate !== undefined) {
      await this.registerGate
    }
    this.registers.push({ viewId, bindingEpoch, workspacePath })
    return { viewId, bindingEpoch, workspacePath, state: 'OPEN' }
  }

  async inspectView(viewId: string, bindingEpoch: string, _signal: AbortSignal): Promise<ManagedViewInspection> {
    return { viewId, bindingEpoch, workspacePath: '/managed', state: 'OPEN' }
  }

  async closeView(
    viewId: string,
    bindingEpoch: string,
    mode: ManagedViewCloseMode,
    _signal: AbortSignal,
  ): Promise<ManagedViewCloseResult> {
    this.closes.push({ viewId, bindingEpoch, mode })
    if (this.closeFail) {
      return {
        viewId,
        bindingEpoch,
        workspacePath: '/managed',
        state: 'CLOSING',
        mode,
        cleanup: { complete: false },
        failures: ['synthetic cleanup failure'],
      }
    }
    return {
      viewId,
      bindingEpoch,
      workspacePath: '/managed',
      state: 'CLOSED',
      mode,
      cleanup: { complete: true },
    }
  }

  async listViews(_signal: AbortSignal): Promise<ManagedViewInspection[]> {
    return []
  }

  onUnexpectedExit(listener: (generation: number) => void): void {
    this.listeners.add(listener)
  }

  crash(): void {
    const generation = this.current?.generation ?? this.generation
    this.current = undefined
    for (const listener of [...this.listeners]) {
      listener(generation)
    }
  }
}

class FakeBridge implements IdeBridge {
  readonly url = 'http://127.0.0.1:1'
  readonly secret = 'secret'
  readonly assignments = new Map<string, { workspacePath: string; bindingEpoch: string; directory: string }>()
  readonly commands: Array<{ viewId: string; command: BridgeCommand; options?: BridgeRequestOptions }> = []
  snapshotDocuments: Array<{ path: string; text: string }> = []
  snapshotComplete = true
  connected = true
  dirty = true
  closeViewResult?: BridgeResult
  closeViewError?: Error

  assign(viewId: string, workspacePath: string, bindingEpoch: string, directory: string): void {
    this.assignments.set(viewId, { workspacePath, bindingEpoch, directory })
  }

  revoke(viewId: string, expectedEpoch?: string): void {
    const current = this.assignments.get(viewId)
    if (current !== undefined && expectedEpoch !== undefined && current.bindingEpoch !== expectedEpoch) {
      return
    }
    this.assignments.delete(viewId)
  }

  isConnected(viewId: string, bindingEpoch?: string): boolean {
    const current = this.assignments.get(viewId)
    return this.connected && current !== undefined && (bindingEpoch === undefined || current.bindingEpoch === bindingEpoch)
  }

  async waitForConnection(): Promise<void> {}

  onPty(): void {}

  async request(
    viewId: string,
    command: BridgeCommand,
    _signal: AbortSignal,
    options?: BridgeRequestOptions,
  ): Promise<BridgeResult> {
    this.commands.push({ viewId, command, ...(options === undefined ? {} : { options }) })
    if (command.op === 'inspect') {
      return { ok: true, clean: !this.dirty }
    }
    if (command.op === 'closeView') {
      if (this.closeViewError !== undefined) {
        throw this.closeViewError
      }
      return this.closeViewResult ?? { ok: true }
    }
    if (command.op === 'snapshot') {
      return {
        ok: true,
        documents: this.snapshotDocuments,
        bytes: this.snapshotDocuments.reduce((total, document) => total + Buffer.byteLength(document.text), 0),
        omitted: this.snapshotComplete ? 0 : 1,
        complete: this.snapshotComplete,
      }
    }
    if (command.op === 'restore') {
      return { ok: true, restored: command.documents?.length ?? 0, skipped: 0 }
    }
    if (command.op === 'changeDirectory' && command.directory !== undefined) {
      const assignment = this.assignments.get(viewId)
      if (assignment !== undefined) {
        assignment.directory = command.directory
      }
    }
    return { ok: true, ...(command.op === 'changeDirectory' ? { directory: command.directory } : {}) }
  }

  async close(): Promise<void> {}
}

const processes: ProcessSupervisor = {
  async start() {
    throw new Error('fake manager does not start processes')
  },
  async close() {},
  async trackDescendant() {},
}

async function setup(options: { maxViews?: number } = {}) {
  const dataDir = await mkdtemp(join(tmpdir(), 'kk-ide-lifecycle-'))
  const project = join(dataDir, 'project')
  await mkdir(project)
  cleanups.push(() => rm(dataDir, { recursive: true, force: true }))
  const config: DaemonConfig = {
    configPath: join(dataDir, 'config.json'),
    dataDir,
    tokenPath: join(dataDir, 'token'),
    studioUrl: 'https://studio.example',
    gatewayUri: 'wss://studio.example/api/harness/environment-daemon/v1',
    bashExecutable: '/bin/bash',
    note: null,
    lsp: {},
    distributionDir: join(dataDir, 'distribution'),
  }
  const scheduler = new TestScheduler()
  const manager = new FakeManager()
  const bridge = new FakeBridge()
  const events: IdeServiceEvent[] = []
  const service = await createIdeService(config, processes, {
    scheduler,
    manager,
    bridge,
    onEvent: (event) => events.push(event),
    ...(options.maxViews === undefined ? {} : { maxViews: options.maxViews }),
  })
  cleanups.push(() => service.close())
  return { service, manager, bridge, scheduler, events, project, config }
}

function open(
  viewId: string,
  documentId: string,
  claimId: string,
  directory: string | undefined,
  mode: 'create' | 'resume' = 'create',
): IdeCommand {
  return openCommand(viewId, documentId, claimId, directory, mode)
}

function boundCommand(
  action: 'CHANGE_DIRECTORY' | 'FOCUS_TERMINAL' | 'CLOSE_VIEW' | 'DETACH_VIEW',
  state: { viewId: string; documentId: string; bindingEpoch: string },
  extra: Partial<IdeCommand> = {},
): IdeCommand {
  return {
    action,
    viewId: state.viewId,
    documentId: state.documentId,
    bindingEpoch: state.bindingEpoch,
    ...extra,
  }
}

/** Models the data adapter: observing the abort releases the upstream after the stream stops. */
function releaseWhenAborted(upstream: { signal: AbortSignal; release: () => void }): void {
  upstream.signal.addEventListener(
    'abort',
    () => {
      queueMicrotask(() => upstream.release())
    },
    { once: true },
  )
}

async function openStreamingView() {
  const context = await setup()
  const viewId = randomUUID()
  const documentId = randomUUID()
  const opened = await context.service.handle(open(viewId, documentId, randomUUID(), context.project), signal())
  expect(opened.ok).toBe(true)
  const state = { viewId, documentId, bindingEpoch: opened.bindingEpoch as string }
  const upstream = await context.service.resolveView(viewId, state.bindingEpoch, {
    mode: 'HTTP',
    method: 'GET',
    path: '/',
    headers: [],
  })
  releaseWhenAborted(upstream)
  return { ...context, viewId, documentId, state, opened, upstream }
}

describe('managed IDE claim and tenant lifecycle', () => {
  it('assigns the confirmed directory to the bridge on create and resume', async () => {
    const { service, bridge, project } = await setup()
    const nextDirectory = join(project, 'next')
    await mkdir(nextDirectory)
    const viewId = randomUUID()
    const documentId = randomUUID()
    const opened = await service.handle(open(viewId, documentId, randomUUID(), project), signal())
    expect(bridge.assignments.get(viewId)).toMatchObject({
      bindingEpoch: opened.bindingEpoch,
      directory: await realpath(project),
    })
    const changed = await service.handle(
      boundCommand('CHANGE_DIRECTORY', { viewId, documentId, bindingEpoch: opened.bindingEpoch as string }, {
        directory: nextDirectory,
      }),
      signal(),
    )
    expect(changed.ok).toBe(true)
    const resumed = await service.handle(open(viewId, documentId, randomUUID(), undefined, 'resume'), signal())
    expect(resumed.ok).toBe(true)
    expect(bridge.assignments.get(viewId)).toMatchObject({
      bindingEpoch: resumed.bindingEpoch,
      directory: await realpath(nextDirectory),
    })
  })

  it('round-trips actual OPEN and RESUME service replies without list-only state', async () => {
    const { service, project } = await setup()
    const viewId = randomUUID()
    const documentId = randomUUID()
    for (const mode of ['create', 'resume'] as const) {
      const reply = await service.handle(open(viewId, documentId, randomUUID(), project, mode), signal())
      expect(reply.ok).toBe(true)
      expect(decodeIdeReply(JSON.stringify(reply))).toEqual(reply)
      expect(decodeIdeReply(encodeIdeReply(reply))).toEqual(reply)
      expect(reply).not.toHaveProperty('state')
    }
    const listed = await listViews(service)
    expect(listed.views?.[0]?.state).toBe('OPEN')
    expect(decodeIdeReply(JSON.stringify(listed))).toEqual(listed)
    expect(decodeIdeReply(encodeIdeReply(listed))).toEqual(listed)
  })

  it('round-trips the actual CHANGE_DIRECTORY service reply with its confirmed canonical directory', async () => {
    const { service, bridge, project } = await setup()
    const viewId = randomUUID()
    const documentId = randomUUID()
    const opened = await service.handle(open(viewId, documentId, randomUUID(), project), signal())
    expect(opened.ok).toBe(true)
    const requested = `${project}${sep}.`
    const confirmed = await realpath(project)
    expect(requested).not.toBe(confirmed)
    const reply = await service.handle(
      boundCommand('CHANGE_DIRECTORY', { viewId, documentId, bindingEpoch: opened.bindingEpoch as string }, {
        directory: requested,
      }),
      signal(),
    )
    // The service sends the realpath to the bridge, not the browser-supplied spelling.
    expect(bridge.commands.at(-1)?.command).toEqual({ op: 'changeDirectory', directory: confirmed })
    const encoded = encodeIdeReply(reply)
    expect(decodeIdeReply(encoded)).toEqual({ ok: true, viewId, directory: confirmed })
    expect(JSON.parse(encoded)).toEqual({ ok: true, viewId, directory: confirmed })
    expect(service.getViewState(viewId)?.directory).toBe(confirmed)
  })

  it.each(['FOCUS_TERMINAL', 'DETACH_VIEW', 'CLOSE_VIEW'] as const)(
    'round-trips the actual %s service command acknowledgement',
    async (action) => {
      const { service, project } = await setup()
      const viewId = randomUUID()
      const documentId = randomUUID()
      const opened = await service.handle(open(viewId, documentId, randomUUID(), project), signal())
      const state = { viewId, documentId, bindingEpoch: opened.bindingEpoch as string }
      const reply = await service.handle(boundCommand(action, state, action === 'CLOSE_VIEW' ? { discard: true } : {}), signal())
      expect(reply).toEqual({ ok: true, viewId })
      expect(decodeIdeReply(JSON.stringify(reply))).toEqual(reply)
      expect(decodeIdeReply(encodeIdeReply(reply))).toEqual(reply)
    },
  )

  it('keeps a view recoverable at 599s and releases it at 600s from the accepted round start', async () => {
    const { service, scheduler, manager } = await setup()
    const viewId = randomUUID()
    const documentId = randomUUID()
    const opened = await service.handle(open(viewId, documentId, randomUUID(), undefined), signal())
    expect(opened.ok).toBe(true)
    const state = service.getViewState(viewId)
    expect(state?.state).toBe('OPEN')
    await scheduler.advance(1_000)
    service.apply(
      { round: '1', bindings: [{ viewId, documentId, bindingEpoch: state?.bindingEpoch as string }] },
      scheduler.now(),
    )
    await scheduler.advance(599_000)
    expect(service.getViewState(viewId)?.state).toBe('OPEN')
    expect(manager.closes).toHaveLength(0)
    await scheduler.advance(1_000)
    await waitFor(() => service.getViewState(viewId) === null)
    expect(manager.closes[0]?.mode).toBe('expired')
  })

  it('replays the same claim exactly once and rejects drift', async () => {
    const { service, manager, project } = await setup()
    const viewId = randomUUID()
    const documentId = randomUUID()
    const claimId = randomUUID()
    const command = open(viewId, documentId, claimId, project)
    const [first, second] = await Promise.all([service.handle(command, signal()), service.handle(command, signal())])
    expect(first.ok).toBe(true)
    expect(second).toEqual(first)
    expect(manager.registers).toHaveLength(1)
    const drift = await service.handle({ ...command, directory: `${project}/.` }, signal())
    expect(drift).toMatchObject({ ok: false, code: 'CLAIM_CONFLICT' })
  })

  it('resumes with a higher epoch and lets an old close/proof fail without touching the new binding', async () => {
    const { service, scheduler, manager, project } = await setup()
    const viewId = randomUUID()
    const documentId = randomUUID()
    const opened = await service.handle(open(viewId, documentId, randomUUID(), project), signal())
    const oldEpoch = opened.bindingEpoch as string
    const resumed = await service.handle(open(viewId, documentId, randomUUID(), undefined, 'resume'), signal())
    expect(resumed.ok).toBe(true)
    expect(BigInt(resumed.bindingEpoch as string)).toBeGreaterThan(BigInt(oldEpoch))
    const staleClose = await service.handle(
      boundCommand('CLOSE_VIEW', { viewId, documentId, bindingEpoch: oldEpoch }, { discard: true }),
      signal(),
    )
    expect(staleClose).toMatchObject({ ok: false, code: 'VIEW_GONE' })
    expect(manager.closes).toHaveLength(0)
    service.apply({ round: '2', bindings: [{ viewId, documentId, bindingEpoch: oldEpoch }] }, scheduler.now())
    await scheduler.advance(IDE_VIEW_EXPIRY_MILLIS)
    await waitFor(() => service.getViewState(viewId) === null)
    expect(manager.closes[0]?.mode).toBe('expired')
  })

  it('enforces 45s document exclusivity while allowing same-document rebind', async () => {
    const { service, scheduler, project } = await setup()
    const viewId = randomUUID()
    const documentA = randomUUID()
    const documentB = randomUUID()
    await service.handle(open(viewId, documentA, randomUUID(), project), signal())
    await scheduler.advance(IDE_PROOF_EXCLUSIVITY_MILLIS - 1)
    const busy = await service.handle(open(viewId, documentB, randomUUID(), undefined, 'resume'), signal())
    expect(busy).toMatchObject({ ok: false, code: 'VIEW_IN_USE' })
    await scheduler.advance(1)
    const taken = await service.handle(open(viewId, documentB, randomUUID(), undefined, 'resume'), signal())
    expect(taken.ok).toBe(true)
    await service.handle(open(viewId, documentA, randomUUID(), undefined, 'resume'), signal())
  })

  it('detach freezes the old epoch and late proof cannot revive it', async () => {
    const { service, scheduler, project, manager } = await setup()
    const viewId = randomUUID()
    const documentId = randomUUID()
    const opened = await service.handle(open(viewId, documentId, randomUUID(), project), signal())
    const state = { viewId, documentId, bindingEpoch: opened.bindingEpoch as string }
    expect((await service.handle(boundCommand('DETACH_VIEW', state), signal())).ok).toBe(true)
    service.apply({ round: '9', bindings: [state] }, scheduler.now())
    await scheduler.advance(IDE_VIEW_EXPIRY_MILLIS)
    await waitFor(() => service.getViewState(viewId) === null)
    expect(manager.closes).toHaveLength(1)
  })

  it('retains CLOSING and quota after cleanup failure, then permits an explicit retry', async () => {
    const { service, manager, project } = await setup()
    const viewId = randomUUID()
    const documentId = randomUUID()
    const opened = await service.handle(open(viewId, documentId, randomUUID(), project), signal())
    const state = { viewId, documentId, bindingEpoch: opened.bindingEpoch as string }
    manager.closeFail = true
    const failed = await service.handle(boundCommand('CLOSE_VIEW', state, { discard: true }), signal())
    expect(failed).toMatchObject({ ok: false, code: 'CLEANUP_FAILED' })
    expect(service.getViewState(viewId)?.state).toBe('CLOSING')
    expect(service.hasActiveViews()).toBe(true)
    expect(service.canStartUpdate()).toBe(false)
    manager.closeFail = false
    const retried = await service.handle(boundCommand('CLOSE_VIEW', state, { discard: true }), signal())
    expect(retried.ok).toBe(true)
    expect(service.getViewState(viewId)).toBeNull()
  })

  it('invalidates every view on process exit and converges before accepting a new view', async () => {
    const { service, manager, project } = await setup()
    const first = randomUUID()
    const second = randomUUID()
    await service.handle(open(first, randomUUID(), randomUUID(), project), signal())
    await service.handle(open(second, randomUUID(), randomUUID(), project), signal())
    let releaseStop: () => void = () => {}
    const stopGate = new Promise<void>((resolve) => {
      releaseStop = resolve
    })
    manager.stopGate = stopGate
    manager.crash()
    expect(service.getViewState(first)?.state).toBe('CLOSING')
    expect(service.getViewState(second)?.state).toBe('CLOSING')
    expect(service.canStartUpdate()).toBe(false)
    releaseStop()
    await waitFor(() => service.getViewState(first) === null && service.getViewState(second) === null)
    const replacement = await service.handle(open(randomUUID(), randomUUID(), randomUUID(), project), signal())
    expect(replacement.ok).toBe(true)
  })

  it('stops idle code-server only after the last view has been fully closed for 60s', async () => {
    const { service, scheduler, manager, project } = await setup()
    const viewId = randomUUID()
    const documentId = randomUUID()
    const opened = await service.handle(open(viewId, documentId, randomUUID(), project), signal())
    const state = { viewId, documentId, bindingEpoch: opened.bindingEpoch as string }
    expect((await service.handle(boundCommand('CLOSE_VIEW', state, { discard: true }), signal())).ok).toBe(true)
    await scheduler.advance(IDE_IDLE_STOP_MILLIS - 1)
    expect(manager.stopCalls).toBe(0)
    await scheduler.advance(1)
    expect(manager.stopCalls).toBe(1)
  })

  it('bounds concurrent create reservations before process work reaches the 64-view limit', async () => {
    const { service, manager } = await setup()
    let releaseRegistrations: () => void = () => {}
    manager.registerGate = new Promise<void>((resolve) => {
      releaseRegistrations = resolve
    })
    const commands = Array.from({ length: 64 }, () => open(randomUUID(), randomUUID(), randomUUID(), undefined))
    const pending = commands.map((command) => service.handle(command, signal()))
    const overflow = await service.handle(open(randomUUID(), randomUUID(), randomUUID(), undefined), signal())
    expect(overflow).toMatchObject({ ok: false, code: 'BUSY' })
    releaseRegistrations()
    const results = await Promise.all(pending)
    expect(results.every((reply) => reply.ok)).toBe(true)
  })

  it('rejects update while a view or data stream exists and stops idle code-server when empty', async () => {
    const { service, manager, project } = await setup()
    const viewId = randomUUID()
    const documentId = randomUUID()
    const opened = await service.handle(open(viewId, documentId, randomUUID(), project), signal())
    const epoch = opened.bindingEpoch as string
    expect(service.canStartUpdate()).toBe(false)
    const request: IdeStreamRequest = { mode: 'HTTP', method: 'GET', path: '/', headers: [] }
    const upstream = await service.resolveView(viewId, epoch, request)
    expect(service.activeStreamCount()).toBe(1)
    expect(upstream.path).toBe(`/__kk/ide/view/${viewId}/${epoch}/`)
    upstream.release()
    expect(service.activeStreamCount()).toBe(0)
    const state = { viewId, documentId, bindingEpoch: epoch }
    await service.handle(boundCommand('CLOSE_VIEW', state, { discard: true }), signal())
    expect(service.canStartUpdate()).toBe(true)
    await service.stopForUpdate(signal())
    expect(manager.stopCalls).toBe(1)
  })

  it('returns owner identity from LIST_VIEWS', async () => {
    const { service, project } = await setup()
    const viewId = randomUUID()
    const documentId = randomUUID()
    const eventNodeId = randomUUID()
    const connectionId = randomUUID()
    const claimId = randomUUID()
    const opened = await service.handle(
      {
        action: 'OPEN_VIEW',
        viewId,
        documentId,
        eventNodeId,
        connectionId,
        claimId,
        mode: 'create',
        directory: project,
      },
      signal(),
    )
    expect(opened.ok).toBe(true)
    const listed = await listViews(service)
    expect(listed.ok).toBe(true)
    expect(listed.views).toEqual([
      {
        viewId: opened.viewId,
        documentId: opened.documentId,
        eventNodeId: opened.eventNodeId,
        connectionId: opened.connectionId,
        claimId: opened.claimId,
        bindingEpoch: opened.bindingEpoch,
        directory: opened.directory,
        workspacePath: opened.workspacePath,
        state: 'OPEN',
      },
    ])
  })

  it('rejects duplicate-tab create and confirms resume as a higher-epoch rebind', async () => {
    const { service, scheduler, manager, project } = await setup()
    const viewId = randomUUID()
    const documentId = randomUUID()
    const opened = await service.handle(open(viewId, documentId, randomUUID(), project), signal())
    expect(opened.ok).toBe(true)

    const duplicate = await service.handle(open(viewId, randomUUID(), randomUUID(), undefined), signal())
    expect(duplicate).toMatchObject({ ok: false, code: 'VIEW_IN_USE' })
    await scheduler.advance(IDE_PROOF_EXCLUSIVITY_MILLIS - 1)
    const otherDocument = await service.handle(
      open(viewId, randomUUID(), randomUUID(), undefined, 'resume'),
      signal(),
    )
    expect(otherDocument).toMatchObject({ ok: false, code: 'VIEW_IN_USE' })

    const resumed = await service.handle(open(viewId, documentId, randomUUID(), undefined, 'resume'), signal())
    expect(resumed.ok).toBe(true)
    expect(BigInt(resumed.bindingEpoch as string)).toBeGreaterThan(BigInt(opened.bindingEpoch as string))
    // Resume must rebind the existing managed view, never close and recreate it.
    expect(manager.closes).toHaveLength(0)
    expect(manager.registers).toHaveLength(2)
    expect(manager.registers[1]?.workspacePath).toBe(opened.workspacePath)
  })

  it('serialises one claimId across different viewIds and rejects the drift', async () => {
    const { service, manager, project } = await setup()
    const claimId = randomUUID()
    const firstView = randomUUID()
    const secondView = randomUUID()
    const [first, second] = await Promise.all([
      service.handle(open(firstView, randomUUID(), claimId, project), signal()),
      service.handle(open(secondView, randomUUID(), claimId, project), signal()),
    ])
    expect([first.ok, second.ok].filter(Boolean)).toHaveLength(1)
    expect([first, second].find((reply) => !reply.ok)?.code).toBe('CLAIM_CONFLICT')
    expect(manager.registers).toHaveLength(1)
    const listed = await listViews(service)
    expect(listed.views).toHaveLength(1)
  })

  it('uses the round send time, not arrival time, for the 600s proof window', async () => {
    const { service, scheduler, project } = await setup()
    const viewId = randomUUID()
    const documentId = randomUUID()
    const opened = await service.handle(open(viewId, documentId, randomUUID(), project), signal())
    await scheduler.advance(120_000)
    service.apply(
      {
        round: '1',
        bindings: [{ viewId, documentId, bindingEpoch: opened.bindingEpoch as string }],
      },
      0,
    )
    // A proof sent at t=0 retains the t=600000 deadline; arriving at t=120000 must not extend it.
    await scheduler.advance(479_999)
    expect(service.getViewState(viewId)?.state).toBe('OPEN')
    await scheduler.advance(1)
    await waitFor(() => service.getViewState(viewId) === null)
  })

  it('refuses a normal close while dirty and closes clean through bridge plus managed discard', async () => {
    const { service, manager, bridge, project } = await setup()
    const viewId = randomUUID()
    const documentId = randomUUID()
    const opened = await service.handle(open(viewId, documentId, randomUUID(), project), signal())
    const state = { viewId, documentId, bindingEpoch: opened.bindingEpoch as string }

    bridge.dirty = true
    const dirty = await service.handle(boundCommand('CLOSE_VIEW', state, { discard: false }), signal())
    expect(dirty).toMatchObject({ ok: false, code: 'DIRTY' })
    expect(service.getViewState(viewId)?.state).toBe('OPEN')
    expect(manager.closes).toHaveLength(0)

    bridge.dirty = false
    const closed = await service.handle(boundCommand('CLOSE_VIEW', state, { discard: false }), signal())
    expect(closed.ok).toBe(true)
    expect(manager.closes[0]?.mode).toBe('discard')
    const bridgeClose = bridge.commands.find((entry) => entry.command.op === 'closeView')
    expect(bridgeClose?.command.discard).toBeUndefined()
  })

  it('keeps streams and binding when an inspection is clean but the final bridge close rejects', async () => {
    const { service, manager, bridge, viewId, state, opened, upstream } = await openStreamingView()
    bridge.dirty = false
    bridge.closeViewResult = {
      ok: false,
      code: 'DIRTY',
      message: 'save or discard unsaved changes before closing the view',
    }

    const rejected = await service.handle(boundCommand('CLOSE_VIEW', state, { discard: false }), signal())

    expect(rejected).toMatchObject({ ok: false, code: 'DIRTY' })
    expect(bridge.commands.filter((entry) => entry.command.op === 'inspect')).toHaveLength(1)
    expect(service.getViewState(viewId)).toMatchObject({ state: 'OPEN', bindingEpoch: state.bindingEpoch })
    expect(upstream.signal.aborted).toBe(false)
    expect(service.activeStreamCount()).toBe(1)
    expect(manager.closes).toHaveLength(0)
    expect(bridge.isConnected(viewId, state.bindingEpoch)).toBe(true)
    expect(await readFile(opened.workspacePath as string, 'utf8')).toContain('folders')

    // The view stays admissible on the same epoch, while the original stream remains usable.
    const reopened = await service.resolveView(viewId, state.bindingEpoch, {
      mode: 'HTTP',
      method: 'GET',
      path: '/',
      headers: [],
    })
    expect(reopened.signal.aborted).toBe(false)
    reopened.release()
    expect(service.activeStreamCount()).toBe(1)
    upstream.release()
    expect(service.activeStreamCount()).toBe(0)
  })

  it('preserves streams when the final bridge close throws or reports an unknown result', async () => {
    const scenarios: Array<{ configure: (bridge: FakeBridge) => void }> = [
      {
        configure: (bridge) => {
          bridge.closeViewError = new Error('bridge close timed out')
        },
      },
      {
        configure: (bridge) => {
          bridge.closeViewResult = { ok: false, code: 'UNCONFIRMED', message: 'window state is unknown' }
        },
      },
    ]
    for (const scenario of scenarios) {
      const { service, manager, bridge, viewId, state, opened, upstream } = await openStreamingView()
      bridge.dirty = false
      scenario.configure(bridge)

      const rejected = await service.handle(boundCommand('CLOSE_VIEW', state, { discard: false }), signal())

      expect(rejected).toMatchObject({ ok: false, code: 'UNAVAILABLE' })
      expect(service.getViewState(viewId)).toMatchObject({ state: 'OPEN', bindingEpoch: state.bindingEpoch })
      expect(upstream.signal.aborted).toBe(false)
      expect(service.activeStreamCount()).toBe(1)
      expect(manager.closes).toHaveLength(0)
      expect(bridge.isConnected(viewId, state.bindingEpoch)).toBe(true)
      expect(await readFile(opened.workspacePath as string, 'utf8')).toContain('folders')
      upstream.release()
    }
  })

  it('aborts only the accepted view stream and removes only its managed resources', async () => {
    const { service, manager, bridge, project } = await setup()
    bridge.dirty = false
    const targetId = randomUUID()
    const targetDocument = randomUUID()
    const otherId = randomUUID()
    const targetOpened = await service.handle(open(targetId, targetDocument, randomUUID(), project), signal())
    const otherOpened = await service.handle(open(otherId, randomUUID(), randomUUID(), project), signal())
    const targetState = { viewId: targetId, documentId: targetDocument, bindingEpoch: targetOpened.bindingEpoch as string }
    const otherEpoch = otherOpened.bindingEpoch as string
    const targetStream = await service.resolveView(targetId, targetState.bindingEpoch, {
      mode: 'HTTP',
      method: 'GET',
      path: '/',
      headers: [],
    })
    const otherStream = await service.resolveView(otherId, otherEpoch, {
      mode: 'HTTP',
      method: 'GET',
      path: '/',
      headers: [],
    })
    releaseWhenAborted(targetStream)

    const closed = await service.handle(boundCommand('CLOSE_VIEW', targetState, { discard: false }), signal())

    expect(closed).toMatchObject({ ok: true, viewId: targetId })
    expect(targetStream.signal.aborted).toBe(true)
    expect(otherStream.signal.aborted).toBe(false)
    expect(service.activeStreamCount()).toBe(1)
    expect(service.getViewState(targetId)).toBeNull()
    expect(service.getViewState(otherId)).toMatchObject({ state: 'OPEN', bindingEpoch: otherEpoch })
    expect(manager.closes).toEqual([{ viewId: targetId, bindingEpoch: targetState.bindingEpoch, mode: 'discard' }])
    expect(bridge.isConnected(targetId, targetState.bindingEpoch)).toBe(false)
    expect(bridge.isConnected(otherId, otherEpoch)).toBe(true)
    expect(await readFile(targetOpened.workspacePath as string, 'utf8').catch(() => null)).toBeNull()
    expect(await readFile(otherOpened.workspacePath as string, 'utf8')).toContain('folders')
    otherStream.release()
    expect(service.activeStreamCount()).toBe(0)
  })

  it('snapshots ordinary dirty text privately on expiry and restores it into a new binding', async () => {
    const { service, scheduler, bridge, project, config } = await setup()
    const viewId = randomUUID()
    const documentId = randomUUID()
    bridge.snapshotDocuments = [{ path: join(project, 'draft.txt'), text: 'unsaved text' }]
    await service.handle(open(viewId, documentId, randomUUID(), project), signal())
    await scheduler.advance(IDE_VIEW_EXPIRY_MILLIS)
    await waitFor(() => service.getViewState(viewId) === null)
    expect(bridge.commands.some((entry) => entry.command.op === 'snapshot')).toBe(true)

    const backupFile = join(config.dataDir, 'ide', 'recovery', `${viewId}.json`)
    const backup = JSON.parse(await readFile(backupFile, 'utf8')) as { complete: boolean; documents: unknown[] }
    expect(backup.complete).toBe(true)
    expect(backup.documents).toEqual([{ path: join(project, 'draft.txt'), text: 'unsaved text' }])

    const reopened = await service.handle(open(viewId, randomUUID(), randomUUID(), project), signal())
    expect(reopened.ok).toBe(true)
    expect(await service.restoreBackup(viewId, signal())).toEqual({ restored: 1, skipped: 0 })
    expect(bridge.commands.some((entry) => entry.command.op === 'restore')).toBe(true)
  })

  it('keeps every view CLOSING and refuses a replacement when crash cleanup cannot be proven', async () => {
    const { service, manager, project } = await setup()
    const first = randomUUID()
    const second = randomUUID()
    await service.handle(open(first, randomUUID(), randomUUID(), project), signal())
    await service.handle(open(second, randomUUID(), randomUUID(), project), signal())

    manager.stopError = new Error('cannot terminate')
    manager.crash()
    await waitFor(() => manager.stopCalls > 0)
    expect(service.getViewState(first)?.state).toBe('CLOSING')
    expect(service.getViewState(second)?.state).toBe('CLOSING')
    expect(service.canStartUpdate()).toBe(false)

    const blocked = await service.handle(open(randomUUID(), randomUUID(), randomUUID(), project), signal())
    expect(blocked).toMatchObject({ ok: false, code: 'CLEANUP_FAILED' })
    expect(manager.registers).toHaveLength(2)

    manager.stopError = undefined
    const replacement = await service.handle(open(randomUUID(), randomUUID(), randomUUID(), project), signal())
    expect(replacement.ok).toBe(true)
    expect(service.getViewState(first)).toBeNull()
    expect(service.getViewState(second)).toBeNull()
  })

  it('does not idle-stop while a stream is active and arms only once after release', async () => {
    const { service, scheduler, manager, project } = await setup()
    const viewId = randomUUID()
    const documentId = randomUUID()
    const opened = await service.handle(open(viewId, documentId, randomUUID(), project), signal())
    const state = { viewId, documentId, bindingEpoch: opened.bindingEpoch as string }
    const upstream = await service.resolveView(viewId, state.bindingEpoch, {
      mode: 'HTTP',
      method: 'GET',
      path: '/healthz',
      headers: [],
    })
    await scheduler.advance(IDE_IDLE_STOP_MILLIS)
    expect(manager.stopCalls).toBe(0)

    upstream.release()
    expect((await service.handle(boundCommand('CLOSE_VIEW', state, { discard: true }), signal())).ok).toBe(true)
    await scheduler.advance(IDE_IDLE_STOP_MILLIS - 1)
    expect(manager.stopCalls).toBe(0)
    await scheduler.advance(1)
    expect(manager.stopCalls).toBe(1)
    // A completed idle stop is not rearmed into an endless stop loop.
    await scheduler.advance(IDE_IDLE_STOP_MILLIS * 2)
    expect(manager.stopCalls).toBe(1)
  })

  it('resolves only the frozen view path, filters private headers and aborts old streams on resume', async () => {
    const { service, manager, project } = await setup()
    const viewId = randomUUID()
    const documentId = randomUUID()
    const opened = await service.handle(open(viewId, documentId, randomUUID(), project), signal())
    const epoch = opened.bindingEpoch as string
    const upstream = await service.resolveView(viewId, epoch, {
      mode: 'WS',
      method: 'GET',
      path: '/ws?x=1',
      headers: [
        ['cookie', 'browser-cookie'],
        ['authorization', 'browser-auth'],
        ['host', 'evil.example'],
        ['sec-websocket-protocol', 'kk.a, bad token'],
        ['x-test', 'ok'],
      ],
    })
    expect(upstream).toMatchObject({
      host: '127.0.0.1',
      port: 4100,
      path: `/__kk/ide/view/${viewId}/${epoch}/ws?x=1`,
      cookie: 'code-server-session=test',
      headers: [['x-test', 'ok']],
      protocols: ['kk.a'],
    })
    expect(service.activeStreamCount()).toBe(1)
    await expect(
      service.resolveView(viewId, epoch, { mode: 'HTTP', method: 'GET', path: '/../etc/passwd', headers: [] }),
    ).rejects.toThrow(/path is invalid/)
    await expect(
      service.resolveView(viewId, epoch, {
        mode: 'HTTP',
        method: 'GET',
        path: '/',
        headers: [['x-test', 'a\r\nb']],
      }),
    ).rejects.toThrow(/headers are invalid/)

    const resumed = await service.handle(open(viewId, documentId, randomUUID(), undefined, 'resume'), signal())
    expect(resumed.ok).toBe(true)
    expect(upstream.signal.aborted).toBe(true)
    upstream.release()
    expect(service.activeStreamCount()).toBe(0)
    expect(manager.closes).toHaveLength(0)
  })
})

export { TestScheduler, FakeBridge, FakeManager, boundCommand, open }
