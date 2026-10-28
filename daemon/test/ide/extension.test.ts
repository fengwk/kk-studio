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
    let reportStatus: (status: number) => void = () => {}
    const oldEnded = new Promise<void>((resolve) => {
      endOld = resolve
    })
    const retiredStatus = new Promise<number>((resolve) => {
      reportStatus = resolve
    })
    const observedHttp = {
      request: http.request,
      get(url: string, options: http.RequestOptions, callback: (response: http.IncomingMessage) => void) {
        const attempt = ++attempts
        return http.get(url, options, (response) => {
          if (attempt === 1) {
            response.once('end', endOld)
          } else if (attempt === 2) {
            reportStatus(response.statusCode ?? 0)
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
      await vi.advanceTimersByTimeAsync(10)
      expect(await retiredStatus).toBe(410)
      await vi.advanceTimersByTimeAsync(100)
      expect(attempts).toBe(2)
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
    const changed = bridge.request(viewId, { op: 'changeDirectory', directory: '/target' }, signal(), {
      expectedDirectory: '/target',
    })
    await updateCalled
    // A public folder-change event that still reports the old folder is not confirmation.
    fake.fireFolders()
    expect(await changed).toMatchObject({ ok: false })
    expect(fake.folderListeners.size).toBe(1)
  })

  it('confirms an accepted change only after the public folder event reports the target', async () => {
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
    const changed = bridge.request(viewId, { op: 'changeDirectory', directory: '/target' }, signal(), {
      expectedDirectory: '/target',
    })
    await updateObserved
    expect(fake.folderListeners.size).toBe(1)
    fake.vscode.workspace.workspaceFolders = [{ uri: { fsPath: '/target' } }]
    fake.fireFolders()
    expect(await changed).toMatchObject({ ok: true, directory: '/target' })
    expect(fake.folderListeners.size).toBe(1)
  })

  it('times out an accepted update with no folder observation instead of reporting success', async () => {
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
      await vi.advanceTimersByTimeAsync(14_999)
      expect(settled).toBe(false)
      await vi.advanceTimersByTimeAsync(1)
      expect(await changed).toMatchObject({ ok: false, message: 'directory change was not applied' })
      expect(fake.folderListeners.size).toBe(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('revalidates a later folder event after a cancelled change, even after an old-folder reconnect', async () => {
    const bridge: IdeBridge = await createIdeBridge()
    cleanups.push(() => bridge.close())
    const viewId = '11111111-1111-4111-8111-111111111111'
    const workspace = '/managed/one.code-workspace'
    bridge.assign(viewId, workspace, '1', '/old')
    let updateCalled: () => void = () => {}
    let firstEnded: () => void = () => {}
    let secondConnected: (status: number) => void = () => {}
    let thirdConnected: (status: number) => void = () => {}
    const updateObserved = new Promise<void>((resolve) => {
      updateCalled = resolve
    })
    const oldEnded = new Promise<void>((resolve) => {
      firstEnded = resolve
    })
    const secondStatus = new Promise<number>((resolve) => {
      secondConnected = resolve
    })
    const thirdStatus = new Promise<number>((resolve) => {
      thirdConnected = resolve
    })
    const fake = fakeVscode(workspace, ['/old'], {
      updateWorkspaceFolders: () => {
        updateCalled()
        return true
      },
    })
    let attempts = 0
    const observedHttp = {
      request: http.request,
      get(url: string, options: http.RequestOptions, callback: (response: http.IncomingMessage) => void) {
        const attempt = ++attempts
        return http.get(url, options, (response) => {
          if (attempt === 1) {
            response.once('end', firstEnded)
          } else if (attempt === 2) {
            secondConnected(response.statusCode ?? 0)
          } else if (attempt === 3) {
            thirdConnected(response.statusCode ?? 0)
          }
          callback(response)
        })
      },
    }
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      const host = createBridgeHost(fake.vscode, {
        url: bridge.url,
        token: bridge.secret,
        reconnectMillis: 10,
        http: observedHttp,
      })
      cleanups.push(() => host.dispose())
      const connected = bridge.waitForConnection(viewId, signal())
      host.start()
      await connected
      const controller = new AbortController()
      const changed = bridge.request(viewId, { op: 'changeDirectory', directory: '/target' }, controller.signal, {
        expectedDirectory: '/target',
      })
      await updateObserved
      controller.abort()
      expect(await changed).toMatchObject({ ok: false })
      await oldEnded
      await vi.advanceTimersByTimeAsync(10)
      expect(await secondStatus).toBe(200)
      expect(bridge.isConnected(viewId, '1')).toBe(true)

      // The accepted API call applies after cancellation; its old registration must not survive.
      fake.vscode.workspace.workspaceFolders = [{ uri: { fsPath: '/target' } }]
      fake.fireFolders()
      await vi.advanceTimersByTimeAsync(10)
      expect(await thirdStatus).toBe(410)
      expect(bridge.isConnected(viewId, '1')).toBe(false)
      await vi.advanceTimersByTimeAsync(100)
      expect(attempts).toBe(3)
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

  it('switches directory through the public updateWorkspaceFolders API', async () => {
    const { bridge, viewId, fake } = await setup('/managed/one.code-workspace')

    // The daemon only accepts a change the window confirms with the expected directory.
    const result = await bridge.request(viewId, { op: 'changeDirectory', directory: '/home/kkdaemon' }, signal(), {
      expectedDirectory: '/home/kkdaemon',
    })
    expect(result.ok).toBe(true)
    expect(result.directory).toBe('/home/kkdaemon')
    expect(fake.calls.updateWorkspaceFolders).toEqual([[0, 1, [{ uri: { fsPath: '/home/kkdaemon', scheme: 'file' } }]]])
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
