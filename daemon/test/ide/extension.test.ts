import { afterEach, describe, expect, it, vi } from 'vitest'
import http from 'node:http'
import { createIdeBridge, type IdeBridge } from '../../src/ide/bridge.js'
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-expect-error plain CommonJS extension without type declarations
import { createBridgeHost } from '../../ide-extension/bridge.cjs'

const cleanups: (() => Promise<void> | void)[] = []

afterEach(async () => {
  for (const cleanup of cleanups.splice(0).reverse()) {
    await cleanup()
  }
})

function delay(millis: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, millis))
}

async function waitFor(predicate: () => boolean, timeoutMillis = 5000): Promise<void> {
  const deadline = Date.now() + timeoutMillis
  while (!predicate()) {
    if (Date.now() > deadline) {
      throw new Error('condition not met before timeout')
    }
    await delay(5)
  }
}

interface FakeTerminal {
  name: string
  cwd?: string
  shown: boolean
  disposed: boolean
  processId: Promise<number>
  show(): void
  dispose(): void
}

function fakeVscode(
  workspaceFile: string | undefined,
  folders: string[],
  options: { processId?: () => Promise<number>; updateWorkspaceFolders?: () => boolean } = {},
) {
  const calls = {
    updateWorkspaceFolders: [] as unknown[],
    createTerminal: [] as { name: string; cwd?: string }[],
    applyEdit: [] as unknown[],
  }
  const terminals: FakeTerminal[] = []
  const openedDocuments = new Map<string, Record<string, unknown>>()
  const folderListeners = new Set<() => void>()
  const fireFolders = (): void => {
    for (const listener of [...folderListeners]) {
      listener()
    }
  }
  let nextPid = 4242

  class FakePosition {
    constructor(readonly line: number, readonly character: number) {}
  }
  class FakeRange {
    constructor(readonly start: FakePosition, readonly end: FakePosition) {}
  }
  class FakeWorkspaceEdit {
    readonly edits: Array<{ uri: { fsPath: string }; range: FakeRange; text: string }> = []
    replace(uri: { fsPath: string }, range: FakeRange, text: string): void {
      this.edits.push({ uri, range, text })
    }
  }
  const vscode = {
    Uri: { file: (path: string) => ({ fsPath: path, scheme: 'file' }) },
    Range: FakeRange,
    Position: FakePosition,
    WorkspaceEdit: FakeWorkspaceEdit,
    workspace: {
      workspaceFile: workspaceFile === undefined ? undefined : { fsPath: workspaceFile },
      workspaceFolders: folders.map((path) => ({ uri: { fsPath: path } })),
      onDidChangeWorkspaceFolders(listener: () => void) {
        folderListeners.add(listener)
        return { dispose: () => folderListeners.delete(listener) }
      },
      textDocuments: [] as Array<Record<string, unknown>>,
      async openTextDocument(uri: { fsPath: string }) {
        return (
          openedDocuments.get(uri.fsPath) ?? {
            uri,
            isDirty: false,
            isClosed: false,
            getText: () => '',
            positionAt: (offset: number) => new FakePosition(0, offset),
          }
        )
      },
      async applyEdit(edit: FakeWorkspaceEdit): Promise<boolean> {
        calls.applyEdit.push(edit)
        return true
      },
      updateWorkspaceFolders(start: number, remove: number, ...added: { uri: { fsPath: string } }[]): boolean {
        calls.updateWorkspaceFolders.push([start, remove, added])
        if (options.updateWorkspaceFolders) {
          return options.updateWorkspaceFolders()
        }
        vscode.workspace.workspaceFolders = added.map((entry) => ({ uri: entry.uri }))
        fireFolders()
        return true
      },
    },
    window: {
      terminals,
      async showTextDocument(): Promise<void> {},
      createTerminal(options_: { name: string; cwd?: string }): FakeTerminal {
        calls.createTerminal.push({ name: options_.name, cwd: options_.cwd })
        const terminal: FakeTerminal = {
          name: options_.name,
          cwd: options_.cwd,
          shown: false,
          disposed: false,
          processId: options.processId ? options.processId() : Promise.resolve(nextPid++),
          show() {
            terminal.shown = true
          },
          dispose() {
            terminal.disposed = true
            const index = terminals.indexOf(terminal)
            if (index >= 0) {
              terminals.splice(index, 1)
            }
          },
        }
        terminals.push(terminal)
        return terminal
      },
    },
  }
  return { vscode, calls, terminals, openedDocuments, fireFolders, folderListeners }
}

async function setupWith(fake: ReturnType<typeof fakeVscode>, workspacePath: string) {
  const bridge: IdeBridge = await createIdeBridge()
  cleanups.push(() => bridge.close())
  const viewId = '11111111-1111-4111-8111-111111111111'
  bridge.assign(viewId, workspacePath, '1', fake.vscode.workspace.workspaceFolders[0]!.uri.fsPath)
  const ptyEvents: { viewId: string; pid: number }[] = []
  bridge.onPty((id, pid) => ptyEvents.push({ viewId: id, pid }))
  const host = createBridgeHost(fake.vscode, { url: bridge.url, token: bridge.secret, reconnectMillis: 10 })
  cleanups.push(() => host.dispose())
  host.start()
  await waitFor(() => bridge.isConnected(viewId))
  return { bridge, viewId, fake, ptyEvents }
}

async function setup(workspacePath: string) {
  return setupWith(fakeVscode(workspacePath, ['/home/user']), workspacePath)
}

const signal = (): AbortSignal => new AbortController().signal

describe('VS Code bridge extension host', () => {
  it('cancels pending control POSTs on retirement without disposing the terminal', async () => {
    const bridge = await createIdeBridge()
    cleanups.push(() => bridge.close())
    const viewId = '11111111-1111-4111-8111-111111111111'
    const workspace = '/managed/one.code-workspace'
    bridge.assign(viewId, workspace, '1', '/target')
    const old = fakeVscode(workspace, ['/target'])
    const target = fakeVscode(workspace, ['/target'])
    let postStarted: () => void = () => {}
    let postClosed: () => void = () => {}
    const started = new Promise<void>((resolve) => { postStarted = resolve })
    const closed = new Promise<void>((resolve) => { postClosed = resolve })
    const observedHttp = {
      get: http.get,
      request(url: string, options: http.RequestOptions, callback: (response: http.IncomingMessage) => void) {
        const req = http.request(url, options, callback)
        req.once('close', postClosed)
        // Hold the outbound POST until retirement, without using a timer.
        req.end = (() => { postStarted(); return req }) as typeof req.end
        return req
      },
    }
    const oldHost = createBridgeHost(old.vscode, { url: bridge.url, token: bridge.secret, http: observedHttp })
    const targetHost = createBridgeHost(target.vscode, { url: bridge.url, token: bridge.secret })
    cleanups.push(() => oldHost.dispose(), () => targetHost.dispose())
    const connected = bridge.waitForConnection(viewId, signal())
    oldHost.start()
    await connected
    const focused = bridge.request(viewId, { op: 'focusTerminal' }, signal())
    await started
    targetHost.start()
    expect(await focused).toMatchObject({ ok: false })
    await closed
    expect(old.terminals[0]?.disposed).toBe(false)
    oldHost.dispose()
    expect(old.terminals[0]?.disposed).toBe(false)
  })

  it('honours an explicit retirement frame for a displaced host without disposing its terminal', async () => {
    const bridge = await createIdeBridge()
    cleanups.push(() => bridge.close())
    const viewId = '11111111-1111-4111-8111-111111111111'
    const workspace = '/managed/one.code-workspace'
    bridge.assign(viewId, workspace, '1', '/target')
    let deliverPid: (pid: number) => void = () => {}
    const pid = new Promise<number>((resolve) => { deliverPid = resolve })
    const old = fakeVscode(workspace, ['/target'], { processId: () => pid })
    const target = fakeVscode(workspace, ['/target'])
    target.vscode.workspace.textDocuments.push({ isDirty: true })
    let oldClosed: () => void = () => {}
    const closed = new Promise<void>((resolve) => { oldClosed = resolve })
    let attempts = 0
    let ptyCalls = 0
    bridge.onPty(() => { ptyCalls++ })
    const observedHttp = {
      request: http.request,
      get(url: string, options: http.RequestOptions, callback: (response: http.IncomingMessage) => void) {
        attempts++
        return http.get(url, options, (response) => {
          response.once('close', oldClosed)
          callback(response)
        })
      },
    }
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      const oldHost = createBridgeHost(old.vscode, { url: bridge.url, token: bridge.secret, http: observedHttp, reconnectMillis: 10 })
      const targetHost = createBridgeHost(target.vscode, { url: bridge.url, token: bridge.secret, reconnectMillis: 10 })
      cleanups.push(() => oldHost.dispose(), () => targetHost.dispose())
      const connected = bridge.waitForConnection(viewId, signal())
      oldHost.start()
      await connected
      const focused = bridge.request(viewId, { op: 'focusTerminal' }, signal())
      // Command arrival is observed by public API use, not a timing sleep.
      const terminalCreated = new Promise<void>((resolve) => {
        const create = old.vscode.window.createTerminal
        old.vscode.window.createTerminal = (options) => {
          const terminal = create(options)
          resolve()
          return terminal
        }
      })
      await terminalCreated
      targetHost.start()
      expect(await focused).toMatchObject({ ok: false })
      await closed
      deliverPid(4242)
      await Promise.resolve()
      await vi.advanceTimersByTimeAsync(100)
      expect(attempts).toBe(1)
      expect(ptyCalls).toBe(0)
      expect(old.terminals[0]?.disposed).toBe(false)
      expect(await bridge.request(viewId, { op: 'closeView' }, signal())).toMatchObject({ ok: false, code: 'DIRTY' })
      oldHost.dispose()
      expect(old.terminals[0]?.disposed).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('reconnects the same host identity after an ordinary network disconnect', async () => {
    const bridge = await createIdeBridge()
    cleanups.push(() => bridge.close())
    const viewId = '11111111-1111-4111-8111-111111111111'
    const workspace = '/managed/one.code-workspace'
    bridge.assign(viewId, workspace, '1', '/home/user')
    bridge.onPty(() => {})
    const fake = fakeVscode(workspace, ['/home/user'])
    const hostIds: string[] = []
    let firstRequest: http.ClientRequest | undefined
    let reconnected: (status: number) => void = () => {}
    const second = new Promise<number>((resolve) => { reconnected = resolve })
    const observedHttp = {
      request: http.request,
      get(url: string, options: http.RequestOptions, callback: (response: http.IncomingMessage) => void) {
        hostIds.push(new URL(url).searchParams.get('hostId') ?? '')
        const attempt = hostIds.length
        const req = http.get(url, options, (response) => {
          if (attempt === 2) {
            reconnected(response.statusCode ?? 0)
          }
          callback(response)
        })
        if (attempt === 1) {
          firstRequest = req
        }
        return req
      },
    }
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      const host = createBridgeHost(fake.vscode, {
        url: bridge.url, token: bridge.secret, reconnectMillis: 10, http: observedHttp,
      })
      cleanups.push(() => host.dispose())
      const connected = bridge.waitForConnection(viewId, signal())
      host.start()
      await connected
      expect((await bridge.request(viewId, { op: 'focusTerminal' }, signal())).ok).toBe(true)
      const terminal = fake.terminals[0]!
      const closed = new Promise<void>((resolve) => firstRequest!.once('close', resolve))
      firstRequest!.destroy()
      await closed
      await vi.advanceTimersByTimeAsync(10)
      expect(await second).toBe(200)
      expect(hostIds).toHaveLength(2)
      expect(hostIds[0]).toMatch(/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/)
      expect(hostIds[1]).toBe(hostIds[0])
      expect(terminal.disposed).toBe(false)
      expect(await bridge.request(viewId, { op: 'inspect' }, signal())).toMatchObject({ ok: true, clean: true })
    } finally {
      vi.useRealTimers()
    }
  })

  it('stops retrying on 410 when a disconnected host was superseded', async () => {
    const bridge = await createIdeBridge()
    cleanups.push(() => bridge.close())
    const viewId = '11111111-1111-4111-8111-111111111111'
    const workspace = '/managed/one.code-workspace'
    bridge.assign(viewId, workspace, '1', '/target')
    bridge.onPty(() => {})
    const old = fakeVscode(workspace, ['/target'])
    const target = fakeVscode(workspace, ['/target'])
    target.vscode.workspace.textDocuments.push({ isDirty: true })
    let initial: http.ClientRequest | undefined
    let result: (status: number) => void = () => {}
    const rejected = new Promise<number>((resolve) => { result = resolve })
    let attempts = 0
    const observedHttp = {
      request: http.request,
      get(url: string, options: http.RequestOptions, callback: (response: http.IncomingMessage) => void) {
        const attempt = ++attempts
        const req = http.get(url, options, (response) => {
          if (attempt === 2) {
            result(response.statusCode ?? 0)
          }
          callback(response)
        })
        if (attempt === 1) {
          initial = req
        }
        return req
      },
    }
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      const oldHost = createBridgeHost(old.vscode, { url: bridge.url, token: bridge.secret, http: observedHttp, reconnectMillis: 10 })
      cleanups.push(() => oldHost.dispose())
      const connected = bridge.waitForConnection(viewId, signal())
      oldHost.start()
      await connected
      expect((await bridge.request(viewId, { op: 'focusTerminal' }, signal())).ok).toBe(true)
      const terminal = old.terminals[0]!
      const disconnected = new Promise<void>((resolve) => initial!.once('close', resolve))
      initial!.destroy()
      await disconnected
      // A separate observed registration proves the target took control before retry.
      let targetConnected: () => void = () => {}
      const targetReady = new Promise<void>((resolve) => { targetConnected = resolve })
      const targetHttp = {
        request: http.request,
        get(url: string, options: http.RequestOptions, callback: (response: http.IncomingMessage) => void) {
          return http.get(url, options, (response) => {
            targetConnected()
            callback(response)
          })
        },
      }
      const replacement = createBridgeHost(target.vscode, { url: bridge.url, token: bridge.secret, http: targetHttp })
      cleanups.push(() => replacement.dispose())
      replacement.start()
      await targetReady
      await vi.advanceTimersByTimeAsync(10)
      expect(await rejected).toBe(410)
      await vi.advanceTimersByTimeAsync(100)
      expect(attempts).toBe(2)
      expect(terminal.disposed).toBe(false)
      expect(await bridge.request(viewId, { op: 'closeView' }, signal())).toMatchObject({ ok: false, code: 'DIRTY' })
    } finally {
      vi.useRealTimers()
    }
  })

  it('retires control immediately after the public folder API accepts, before a folder event', async () => {
    let updateCalled: () => void = () => {}
    let streamDestroyed: () => void = () => {}
    const observed = new Promise<void>((resolve) => { updateCalled = resolve })
    const destroyed = new Promise<void>((resolve) => { streamDestroyed = resolve })
    const fake = fakeVscode('/managed/one.code-workspace', ['/home/user'], {
      updateWorkspaceFolders: () => { updateCalled(); return true },
    })
    const bridge = await createIdeBridge()
    cleanups.push(() => bridge.close())
    const viewId = '11111111-1111-4111-8111-111111111111'
    bridge.assign(viewId, '/managed/one.code-workspace', '1', '/home/user')
    const observedHttp = {
      request: http.request,
      get(url: string, options: http.RequestOptions, callback: (response: http.IncomingMessage) => void) {
        const req = http.get(url, options, callback)
        req.once('close', streamDestroyed)
        return req
      },
    }
    const host = createBridgeHost(fake.vscode, { url: bridge.url, token: bridge.secret, http: observedHttp })
    cleanups.push(() => host.dispose())
    const connected = bridge.waitForConnection(viewId, signal())
    host.start()
    await connected
    const controller = new AbortController()
    const changed = bridge.request(viewId, { op: 'changeDirectory', directory: '/target' }, controller.signal, {
      expectedDirectory: '/target',
    })
    await observed
    await destroyed
    controller.abort()
    expect(await changed).toMatchObject({ ok: false })
    expect(bridge.isConnected(viewId)).toBe(false)
  })

  it('retires the old host control reconnect without disposing terminals or closing a dirty target', async () => {
    const bridge: IdeBridge = await createIdeBridge()
    cleanups.push(() => bridge.close())
    const viewId = '11111111-1111-4111-8111-111111111111'
    const workspace = '/managed/one.code-workspace'
    bridge.assign(viewId, workspace, '1', '/old')
    bridge.onPty(() => {})
    let updateCalled: () => void = () => {}
    const updateObserved = new Promise<void>((resolve) => {
      updateCalled = resolve
    })
    const old = fakeVscode(workspace, ['/old'], {
      updateWorkspaceFolders: () => {
        updateCalled()
        return true
      },
    })
    const target = fakeVscode(workspace, ['/target'])
    target.vscode.workspace.textDocuments.push({ isDirty: true })
    let attempts = 0
    let endOld: () => void = () => {}
    const oldEnded = new Promise<void>((resolve) => {
      endOld = resolve
    })
    const observedHttp = {
      request: http.request,
      get(url: string, options: http.RequestOptions, callback: (response: http.IncomingMessage) => void) {
        const attempt = ++attempts
        return http.get(url, options, (response) => {
          if (attempt === 1) {
            response.once('close', endOld)
          }
          callback(response)
        })
      },
    }
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      const oldHost = createBridgeHost(old.vscode, {
        url: bridge.url,
        token: bridge.secret,
        reconnectMillis: 10,
        http: observedHttp,
      })
      const targetHost = createBridgeHost(target.vscode, { url: bridge.url, token: bridge.secret, reconnectMillis: 10 })
      cleanups.push(() => oldHost.dispose(), () => targetHost.dispose())
      const connected = bridge.waitForConnection(viewId, signal())
      oldHost.start()
      await connected
      expect((await bridge.request(viewId, { op: 'focusTerminal' }, signal())).ok).toBe(true)
      const oldTerminal = old.terminals[0]!

      const changed = bridge.request(viewId, { op: 'changeDirectory', directory: '/target' }, signal(), {
        expectedDirectory: '/target',
      })
      await updateObserved
      targetHost.start()
      expect(await changed).toMatchObject({ ok: true, directory: '/target' })
      await oldEnded
      await vi.advanceTimersByTimeAsync(100)
      expect(attempts).toBe(1)
      expect(oldTerminal.disposed).toBe(false)
      expect(old.terminals).toContain(oldTerminal)
      expect(await bridge.request(viewId, { op: 'closeView' }, signal())).toMatchObject({ ok: false, code: 'DIRTY' })
      expect(oldTerminal.disposed).toBe(false)
      expect(bridge.isConnected(viewId, '1')).toBe(true)
      oldHost.dispose()
      expect(oldTerminal.disposed).toBe(false)
      expect(old.folderListeners.size).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not confirm an accepted folder update when the observed folders did not change', async () => {
    let observedUpdate: () => void = () => {}
    const updateCalled = new Promise<void>((resolve) => {
      observedUpdate = resolve
    })
    const fake = fakeVscode('/managed/one.code-workspace', ['/home/user'], {
      updateWorkspaceFolders: () => {
        observedUpdate()
        return true
      },
    })
    const { bridge, viewId } = await setupWith(fake, '/managed/one.code-workspace')
    const controller = new AbortController()
    const changed = bridge.request(viewId, { op: 'changeDirectory', directory: '/target' }, controller.signal, {
      expectedDirectory: '/target',
    })
    await updateCalled
    // A public folder-change event that still reports the old folder is not confirmation.
    fake.fireFolders()
    controller.abort()
    expect(await changed).toMatchObject({ ok: false })
    expect(fake.folderListeners.size).toBe(1)
  })

  it('does not confirm an accepted change from the initiating host folder event alone', async () => {
    let updateCalled: () => void = () => {}
    const updateObserved = new Promise<void>((resolve) => {
      updateCalled = resolve
    })
    const fake = fakeVscode('/managed/one.code-workspace', ['/home/user'], {
      updateWorkspaceFolders: () => {
        updateCalled()
        return true
      },
    })
    const { bridge, viewId } = await setupWith(fake, '/managed/one.code-workspace')
    const controller = new AbortController()
    const changed = bridge.request(viewId, { op: 'changeDirectory', directory: '/target' }, controller.signal, {
      expectedDirectory: '/target',
    })
    await updateObserved
    expect(fake.folderListeners.size).toBe(1)
    fake.vscode.workspace.workspaceFolders = [{ uri: { fsPath: '/target' } }]
    fake.fireFolders()
    controller.abort()
    expect(await changed).toMatchObject({ ok: false })
    expect(fake.folderListeners.size).toBe(1)
  })

  it('times out an accepted update with no replacement host instead of reporting success', async () => {
    const bridge: IdeBridge = await createIdeBridge()
    cleanups.push(() => bridge.close())
    const viewId = '11111111-1111-4111-8111-111111111111'
    const workspace = '/managed/one.code-workspace'
    bridge.assign(viewId, workspace, '1', '/old')
    let updateCalled: () => void = () => {}
    const updateObserved = new Promise<void>((resolve) => {
      updateCalled = resolve
    })
    const fake = fakeVscode(workspace, ['/old'], {
      updateWorkspaceFolders: () => {
        updateCalled()
        return true
      },
    })
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      const host = createBridgeHost(fake.vscode, { url: bridge.url, token: bridge.secret, reconnectMillis: 10 })
      cleanups.push(() => host.dispose())
      const connected = bridge.waitForConnection(viewId, signal())
      host.start()
      await connected
      let settled = false
      const changed = bridge.request(viewId, { op: 'changeDirectory', directory: '/target' }, signal(), {
        expectedDirectory: '/target',
      })
      void changed.then(() => {
        settled = true
      })
      await updateObserved
      await vi.advanceTimersByTimeAsync(19_999)
      expect(settled).toBe(false)
      await vi.advanceTimersByTimeAsync(1)
      expect(await changed).toMatchObject({ ok: false, message: 'ide window did not respond' })
      expect(fake.folderListeners.size).toBe(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('registers the assigned workspace and focuses a terminal via public APIs', async () => {
    const { bridge, viewId, fake, ptyEvents } = await setup('/managed/one.code-workspace')

    const result = await bridge.request(viewId, { op: 'focusTerminal' }, signal())
    expect(result.ok).toBe(true)
    expect(fake.calls.createTerminal).toEqual([{ name: 'kk-studio', cwd: '/home/user' }])
    expect(fake.terminals[0]?.shown).toBe(true)
    expect(ptyEvents).toEqual([{ viewId, pid: 4242 }])
  })

  it('confirms a no-op directory change without restarting the host', async () => {
    const { bridge, viewId, fake } = await setup('/managed/one.code-workspace')
    const result = await bridge.request(viewId, { op: 'changeDirectory', directory: '/home/user' }, signal(), {
      expectedDirectory: '/home/user',
    })
    expect(result).toMatchObject({ ok: true, directory: '/home/user' })
    expect(fake.calls.updateWorkspaceFolders).toEqual([])
  })

  it('refuses to switch directory while documents are dirty', async () => {
    const { bridge, viewId, fake } = await setup('/managed/one.code-workspace')
    fake.vscode.workspace.textDocuments.push({ isDirty: true })

    const result = await bridge.request(viewId, { op: 'changeDirectory', directory: '/home/kkdaemon' }, signal(), {
      expectedDirectory: '/home/kkdaemon',
    })
    expect(result.ok).toBe(false)
    expect(result.code).toBe('DIRTY')
    expect(fake.calls.updateWorkspaceFolders).toHaveLength(0)
  })

  it('disposes its terminal on close view', async () => {
    const { bridge, viewId, fake } = await setup('/managed/one.code-workspace')
    await bridge.request(viewId, { op: 'focusTerminal' }, signal())

    const result = await bridge.request(viewId, { op: 'closeView' }, signal())
    expect(result.ok).toBe(true)
    expect(fake.terminals).toHaveLength(0)
  })

  it('refuses to close the view while documents are dirty and keeps its terminals', async () => {
    const { bridge, viewId, fake } = await setup('/managed/one.code-workspace')
    await bridge.request(viewId, { op: 'focusTerminal' }, signal())
    fake.vscode.workspace.textDocuments.push({ isDirty: true })

    const result = await bridge.request(viewId, { op: 'closeView' }, signal())
    expect(result.ok).toBe(false)
    expect(result.code).toBe('DIRTY')
    // A dirty window must not be torn down or lose its shells.
    expect(fake.terminals).toHaveLength(1)
    expect(fake.terminals[0]?.disposed).toBe(false)
  })

  it('falls back to the workspace folder when no workspace file is open', async () => {
    const fake = fakeVscode(undefined, ['/home/user'])
    const { bridge, viewId } = await setupWith(fake, '/home/user')

    const result = await bridge.request(viewId, { op: 'focusTerminal' }, signal())
    expect(result.ok).toBe(true)
    expect(fake.calls.createTerminal).toEqual([{ name: 'kk-studio', cwd: '/home/user' }])
  })

  it('reports unsupported and failed commands as safe messages', async () => {
    const { bridge, viewId } = await setup('/managed/one.code-workspace')

    const unsupported = await bridge.request(viewId, { op: 'bogus' as never }, signal())
    expect(unsupported).toEqual({ ok: false, code: undefined, message: 'unsupported command' })

    const throwing = fakeVscode('/managed/two.code-workspace', ['/home/user'], {
      updateWorkspaceFolders: () => {
        throw new Error('boom')
      },
    })
    const second = await setupWith(throwing, '/managed/two.code-workspace')
    const failed = await second.bridge.request(
      second.viewId,
      { op: 'changeDirectory', directory: '/home/kkdaemon' },
      signal(),
      { expectedDirectory: '/home/kkdaemon' },
    )
    expect(failed.ok).toBe(false)
    expect(failed.message).toBe('command failed')
  })

  it('reports a rejected directory change', async () => {
    const rejecting = fakeVscode('/managed/one.code-workspace', ['/home/user'], { updateWorkspaceFolders: () => false })
    const rejected = await setupWith(rejecting, '/managed/one.code-workspace')
    const rejectedResult = await rejected.bridge.request(
      rejected.viewId,
      { op: 'changeDirectory', directory: '/home/kkdaemon' },
      signal(),
      { expectedDirectory: '/home/kkdaemon' },
    )
    expect(rejectedResult.ok).toBe(false)
    expect(rejectedResult.message).toBe('directory change was rejected')
  })

  it('fails and disposes the terminal when its pid is unavailable', async () => {
    const fake = fakeVscode('/managed/one.code-workspace', ['/home/user'], {
      processId: () => Promise.reject(new Error('no pid')),
    })
    const { bridge, viewId, ptyEvents } = await setupWith(fake, '/managed/one.code-workspace')

    const result = await bridge.request(viewId, { op: 'focusTerminal' }, signal())
    expect(result.ok).toBe(false)
    expect(result.message).toBe('terminal supervision is unavailable')
    expect(ptyEvents).toHaveLength(0)
    // An unsupervised shell must not be left running.
    expect(fake.terminals).toHaveLength(0)
  })

  it('fails and disposes the terminal when supervision is refused', async () => {
    const bridge: IdeBridge = await createIdeBridge()
    cleanups.push(() => bridge.close())
    const fake = fakeVscode('/managed/one.code-workspace', ['/home/user'])
    const viewId = '11111111-1111-4111-8111-111111111111'
    bridge.assign(viewId, '/managed/one.code-workspace', '1', '/home/user')
    bridge.onPty(() => {
      throw new Error('supervision refused')
    })
    const host = createBridgeHost(fake.vscode, { url: bridge.url, token: bridge.secret, reconnectMillis: 10 })
    cleanups.push(() => host.dispose())
    host.start()
    await waitFor(() => bridge.isConnected(viewId))

    const result = await bridge.request(viewId, { op: 'focusTerminal' }, signal())
    expect(result.ok).toBe(false)
    expect(result.message).toBe('terminal supervision failed')
    expect(fake.terminals).toHaveLength(0)
  })

  it('observes dirty state, snapshots ordinary text and reports opaque editors as incomplete', async () => {
    const { bridge, viewId, fake } = await setup('/managed/one.code-workspace')
    const fileUri = { scheme: 'file', fsPath: '/home/user/draft.txt' }
    fake.vscode.workspace.textDocuments.push({
      uri: fileUri,
      isDirty: true,
      isClosed: false,
      getText: () => 'unsaved text',
    })
    fake.vscode.workspace.textDocuments.push({
      uri: { scheme: 'untitled', fsPath: '' },
      isDirty: true,
      isClosed: false,
      getText: () => 'renderer only',
    })
    const inspected = await bridge.request(viewId, { op: 'inspect' }, signal())
    expect(inspected).toMatchObject({ ok: true, clean: false })

    const snapshot = await bridge.request(viewId, { op: 'snapshot', maxBytes: 1024 }, signal())
    expect(snapshot.ok).toBe(true)
    expect(snapshot.documents).toEqual([{ path: '/home/user/draft.txt', text: 'unsaved text' }])
    expect(snapshot.complete).toBe(true)

    class TabInputCustom {}
    ;(fake.vscode.window as unknown as { tabGroups: unknown }).tabGroups = {
      all: [{ tabs: [{ input: new TabInputCustom() }] }],
    }
    ;(fake.vscode as unknown as { TabInputCustom: unknown }).TabInputCustom = TabInputCustom
    expect(await bridge.request(viewId, { op: 'inspect' }, signal())).toMatchObject({ ok: true, clean: false })
    const opaqueSnapshot = await bridge.request(viewId, { op: 'snapshot', maxBytes: 1024 }, signal())
    expect(opaqueSnapshot.complete).toBe(false)
    expect(opaqueSnapshot.omitted).toBeGreaterThan(0)
    const closeResult = await bridge.request(viewId, { op: 'closeView' }, signal())
    expect(closeResult).toMatchObject({ ok: false, code: 'DIRTY' })
  })

  it('restores only into clean ordinary text buffers and never saves them', async () => {
    const { bridge, viewId, fake } = await setup('/managed/one.code-workspace')
    fake.openedDocuments.set('/home/user/clean.txt', {
      uri: { scheme: 'file', fsPath: '/home/user/clean.txt' },
      isDirty: false,
      isClosed: false,
      getText: () => 'original',
      positionAt: (offset: number) => ({ line: 0, character: offset }),
    })
    fake.openedDocuments.set('/home/user/dirty.txt', {
      uri: { scheme: 'file', fsPath: '/home/user/dirty.txt' },
      isDirty: true,
      isClosed: false,
      getText: () => 'current dirty work',
      positionAt: (offset: number) => ({ line: 0, character: offset }),
    })

    const result = await bridge.request(
      viewId,
      {
        op: 'restore',
        documents: [
          { path: '/home/user/clean.txt', text: 'recovered text' },
          { path: '/home/user/dirty.txt', text: 'must not overwrite' },
          { path: 'relative.txt', text: 'invalid' },
        ],
      },
      signal(),
    )
    expect(result).toMatchObject({ ok: true, restored: 1, skipped: 2 })
    const edits = fake.calls.applyEdit as Array<{ edits: Array<{ uri: { fsPath: string }; text: string }> }>
    expect(edits).toHaveLength(1)
    expect(edits[0]?.edits).toEqual([
      expect.objectContaining({ uri: { scheme: 'file', fsPath: '/home/user/clean.txt' }, text: 'recovered text' }),
    ])
    // Restore only creates unsaved editor state; no file write/save API is called.
    expect(fake.openedDocuments.get('/home/user/clean.txt')?.isDirty).toBe(false)
  })

  it('keeps retrying after a rejected handshake without crashing', async () => {
    const bridge: IdeBridge = await createIdeBridge()
    cleanups.push(() => bridge.close())
    const fake = fakeVscode('/managed/one.code-workspace', ['/home/user'])
    const host = createBridgeHost(fake.vscode, { url: bridge.url, token: 'wrong-token', reconnectMillis: 5 })
    cleanups.push(() => host.dispose())
    host.start()
    await delay(40)
    expect(bridge.isConnected('11111111-1111-4111-8111-111111111111')).toBe(false)
  })
})
