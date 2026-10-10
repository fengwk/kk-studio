import { afterEach, describe, expect, it } from 'vitest'
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
  }
  const terminals: FakeTerminal[] = []
  let nextPid = 4242

  const vscode = {
    Uri: { file: (path: string) => ({ fsPath: path, scheme: 'file' }) },
    workspace: {
      workspaceFile: workspaceFile === undefined ? undefined : { fsPath: workspaceFile },
      workspaceFolders: folders.map((path) => ({ uri: { fsPath: path } })),
      textDocuments: [] as { isDirty: boolean }[],
      updateWorkspaceFolders(start: number, remove: number, ...added: { uri: { fsPath: string } }[]): boolean {
        calls.updateWorkspaceFolders.push([start, remove, added])
        if (options.updateWorkspaceFolders) {
          return options.updateWorkspaceFolders()
        }
        vscode.workspace.workspaceFolders = added.map((entry) => ({ uri: entry.uri }))
        return true
      },
    },
    window: {
      terminals,
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
  return { vscode, calls, terminals }
}

async function setupWith(fake: ReturnType<typeof fakeVscode>, workspacePath: string) {
  const bridge: IdeBridge = await createIdeBridge()
  cleanups.push(() => bridge.close())
  const viewId = '11111111-1111-4111-8111-111111111111'
  bridge.assign(viewId, workspacePath)
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

    const result = await bridge.request(viewId, { op: 'changeDirectory', directory: '/home/kkdaemon' }, signal())
    expect(result.ok).toBe(true)
    expect(fake.calls.updateWorkspaceFolders).toEqual([[0, 1, [{ uri: { fsPath: '/home/kkdaemon', scheme: 'file' } }]]])
  })

  it('refuses to switch directory while documents are dirty', async () => {
    const { bridge, viewId, fake } = await setup('/managed/one.code-workspace')
    fake.vscode.workspace.textDocuments.push({ isDirty: true })

    const result = await bridge.request(viewId, { op: 'changeDirectory', directory: '/home/kkdaemon' }, signal())
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
    const failed = await second.bridge.request(second.viewId, { op: 'changeDirectory', directory: '/home/kkdaemon' }, signal())
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
    bridge.assign(viewId, '/managed/one.code-workspace')
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
