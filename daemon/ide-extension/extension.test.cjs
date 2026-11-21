'use strict'

const assert = require('node:assert/strict')
const { EventEmitter } = require('node:events')
const { PassThrough } = require('node:stream')
const { test } = require('node:test')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const { createBridgeHost } = require('./bridge.cjs')

const VIEW_ID = '11111111-1111-4111-8111-111111111111'
const BINDING_EPOCH = '7'
const WORKSPACE = '/managed/one.code-workspace'

class FakeTabInputText {
  constructor(uri) { this.uri = uri }
}
class FakeTabInputTextDiff {
  constructor(original, modified) { this.original = original; this.modified = modified }
}
class FakePosition {
  constructor(line, character) { this.line = line; this.character = character }
}
class FakeRange {
  constructor(start, end) { this.start = start; this.end = end }
}
class FakeWorkspaceEdit {
  constructor() { this.edits = [] }
  replace(uri, range, text) { this.edits.push({ uri, range, text }) }
}

function fakeVscode(workspaceFile = WORKSPACE, folders = ['/home/user'], options = {}) {
  const calls = { updateWorkspaceFolders: [], createTerminal: [], applyEdit: [], closeTabs: [], commands: [], shownDocuments: [] }
  const terminals = []
  const openedDocuments = new Map()
  const folderListeners = new Set()
  const openListeners = new Set()
  const closeListeners = new Set()
  const watchers = []
  const providers = new Map()
  const subscriptions = (listeners, listener) => ({ dispose: () => listeners.delete(listener) })
  const fileUri = (fsPath) => ({ scheme: 'file', fsPath, toString: () => `file://${fsPath}` })
  const parsedUri = (value) => {
    const parsed = new URL(value)
    return { scheme: parsed.protocol.slice(0, -1), path: parsed.pathname, toString: () => value }
  }

  class RelativePattern {
    constructor(base, pattern) { this.base = base; this.pattern = pattern }
  }

  const vscode = {
    Uri: { file: fileUri, parse: parsedUri },
    RelativePattern,
    Position: FakePosition,
    Range: FakeRange,
    WorkspaceEdit: FakeWorkspaceEdit,
    TabInputText: FakeTabInputText,
    TabInputTextDiff: FakeTabInputTextDiff,
    workspace: {
      workspaceFile: workspaceFile === undefined ? undefined : { fsPath: workspaceFile },
      workspaceFolders: folders.map((fsPath) => ({ uri: fileUri(fsPath) })),
      textDocuments: [],
      onDidChangeWorkspaceFolders(listener) { folderListeners.add(listener); return subscriptions(folderListeners, listener) },
      onDidOpenTextDocument(listener) { openListeners.add(listener); return subscriptions(openListeners, listener) },
      onDidCloseTextDocument(listener) { closeListeners.add(listener); return subscriptions(closeListeners, listener) },
      registerTextDocumentContentProvider(scheme, provider) {
        providers.set(scheme, provider)
        return { dispose() { providers.delete(scheme) } }
      },
      createFileSystemWatcher(pattern) {
        const watcher = { pattern, disposed: false, dispose() { watcher.disposed = true } }
        watchers.push(watcher)
        return watcher
      },
      updateWorkspaceFolders(start, remove, ...added) {
        calls.updateWorkspaceFolders.push([start, remove, added])
        if (options.updateWorkspaceFolders) { return options.updateWorkspaceFolders(start, remove, ...added) }
        // A workspace-file window applies folder changes in place: the live list changes and the
        // workbench reports it, which is what the managed directory change waits for.
        const next = vscode.workspace.workspaceFolders.slice()
        next.splice(start, remove, ...added.map((folder) => ({ uri: folder.uri })))
        vscode.workspace.workspaceFolders = next
        setImmediate(() => { for (const listener of [...folderListeners]) { listener() } })
        return true
      },
      async openTextDocument(uri) {
        if (uri.scheme !== 'file') {
          const provider = providers.get(uri.scheme)
          if (!provider) { throw new Error('no content provider') }
          return { uri, isDirty: false, isClosed: false, getText: () => provider.provideTextDocumentContent(uri) }
        }
        return openedDocuments.get(uri.fsPath) || {
          uri,
          isDirty: false,
          isClosed: false,
          getText: () => '',
          positionAt: (offset) => new FakePosition(0, offset),
        }
      },
      async applyEdit(edit) {
        calls.applyEdit.push(edit)
        return options.applyEdit ? options.applyEdit(edit) : true
      },
    },
    commands: {
      async executeCommand(command) {
        calls.commands.push(command)
        if (options.executeCommand) { await options.executeCommand(command) }
      },
    },
    window: {
      terminals,
      tabGroups: {
        all: options.tabGroups || [],
        async close(tabs) {
          calls.closeTabs.push(tabs)
          if (options.closeTabs) { return options.closeTabs(tabs) }
          for (const group of this.all) { group.tabs = [] }
          return true
        },
      },
      createTerminal(terminalOptions) {
        calls.createTerminal.push(terminalOptions)
        const terminal = {
          name: terminalOptions.name,
          cwd: terminalOptions.cwd,
          shown: false,
          disposed: false,
          processId: options.processId ? options.processId() : Promise.resolve(4242),
          show() { terminal.shown = true },
          dispose() {
            terminal.disposed = true
            const index = terminals.indexOf(terminal)
            if (index >= 0) { terminals.splice(index, 1) }
          },
        }
        terminals.push(terminal)
        return terminal
      },
      async showTextDocument(document) {
        calls.shownDocuments.push(document)
        if (options.showTextDocument) { await options.showTextDocument(document) }
      },
    },
  }
  return {
    vscode, calls, terminals, openedDocuments, folderListeners, openListeners, closeListeners, watchers, providers,
    fireFolders() { for (const listener of [...folderListeners]) { listener() } },
    fireOpen(document) { for (const listener of [...openListeners]) { listener(document) } },
    fireClose(document) { for (const listener of [...closeListeners]) { listener(document) } },
    fileUri,
  }
}

function fakeHttp(options = {}) {
  const streams = []
  const posts = []
  const streamStatuses = [...(options.streamStatuses || [])]
  const postStatuses = [...(options.postStatuses || [])]
  const heldPaths = new Set(options.heldPaths || [])

  const http = {
    get(url, requestOptions, callback) {
      const request = new EventEmitter()
      const status = streamStatuses.length ? streamStatuses.shift() : 200
      const response = new PassThrough()
      response.statusCode = status
      const stream = { url, requestOptions, request, response, status }
      streams.push(stream)
      request.destroyed = false
      request.destroy = () => {
        if (request.destroyed) { return request }
        request.destroyed = true
        if (!response.writableEnded) { response.end() }
        request.emit('close')
        return request
      }
      setImmediate(() => {
        callback(response)
        if (status !== 200 && !response.writableEnded) { response.end() }
      })
      return request
    },
    request(url, requestOptions, callback) {
      const request = new EventEmitter()
      const pathname = new URL(url).pathname
      let settled = false
      request.destroyed = false
      request.end = (body) => {
        const payload = body ? JSON.parse(Buffer.from(body).toString('utf8')) : {}
        const post = { url, pathname, requestOptions, payload, request, status: undefined }
        posts.push(post)
        if (heldPaths.has(pathname)) { return request }
        const status = postStatuses.length ? postStatuses.shift() : 200
        post.status = status
        settled = true
        const response = new PassThrough()
        response.statusCode = status
        setImmediate(() => { callback(response); response.end() })
        return request
      }
      request.destroy = () => {
        if (!request.destroyed) {
          request.destroyed = true
          if (!settled) { request.emit('close') }
        }
        return request
      }
      return request
    },
  }
  return {
    http, streams, posts,
    streamStatuses,
    pushStreamStatus(status) { streamStatuses.push(status) },
    pushPostStatus(status) { postStatuses.push(status) },
    hold(pathname) { heldPaths.add(pathname) },
  }
}

async function waitFor(predicate, timeoutMillis = 1500) {
  const deadline = Date.now() + timeoutMillis
  while (!predicate()) {
    if (Date.now() >= deadline) { throw new Error('condition not met before timeout') }
    await new Promise((resolve) => setImmediate(resolve))
  }
}

function setup(fake = fakeVscode(), options = {}) {
  const network = options.network || fakeHttp(options.httpOptions)
  const host = createBridgeHost(fake.vscode, {
    url: options.url || 'http://127.0.0.1:43210',
    token: options.token || 'bridge-secret',
    viewId: options.viewId || VIEW_ID,
    bindingEpoch: options.bindingEpoch || BINDING_EPOCH,
    reconnectMillis: options.reconnectMillis === undefined ? 5 : options.reconnectMillis,
    directoryConfirmMillis: options.directoryConfirmMillis === undefined ? 10 : options.directoryConfirmMillis,
    http: network.http,
  })
  host.start()
  return { fake, host, network }
}

async function connected(context) {
  await waitFor(() => context.network.streams.length > 0)
  await waitFor(() => context.network.streams[0].status === 200)
}

async function command(context, op, fields = {}) {
  await connected(context)
  const before = context.network.posts.filter((post) => post.pathname === '/bridge/result').length
  const stream = context.network.streams.at(-1)
  stream.response.write(`${JSON.stringify({ type: 'command', id: `cmd-${before + 1}`, op, ...fields })}\n`)
  await waitFor(() => context.network.posts.filter((post) => post.pathname === '/bridge/result').length > before)
  return context.network.posts.filter((post) => post.pathname === '/bridge/result').at(-1).payload
}

async function focus(context) { return command(context, 'focusTerminal') }

// A directory change answers nothing on this channel when control moves to the restarted host, so
// these tests observe the side effects instead of a reply.
async function changeDirectory(context, fields) {
  await connected(context)
  context.network.streams.at(-1).response.write(`${JSON.stringify({ type: 'command', id: 'change', op: 'changeDirectory', ...fields })}\n`)
  await waitFor(() => context.fake.calls.updateWorkspaceFolders.length > 0)
  await waitFor(() => context.fake.calls.commands.length > 0 || context.network.streams.at(-1).request.destroyed)
  return context.fake.calls.updateWorkspaceFolders.at(-1)
}

test('openAttachment displays immutable virtual text and releases it on close or host disposal', async (t) => {
  const context = setup()
  t.after(() => context.host.dispose())
  const id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
  const text = 'read-only attachment text'
  const result = await command(context, 'openAttachment', { attachmentId: id, attachmentText: text, path: '/tmp/forbidden.txt', command: 'workbench.action.reloadWindow' })
  assert.equal(result.ok, true)
  assert.equal(context.fake.providers.size, 1)
  const document = context.fake.calls.shownDocuments[0]
  assert.equal(document.uri.scheme, 'kk-studio-attachment')
  assert.equal(document.uri.path, `/attachment-${id}.txt`)
  assert.equal(document.getText(), text)
  assert.equal(context.fake.calls.applyEdit.length, 0)
  assert.equal(context.fake.calls.commands.length, 0)
  context.fake.fireClose(document)
  assert.equal(context.fake.providers.get('kk-studio-attachment').provideTextDocumentContent(document.uri), undefined)
  await command(context, 'openAttachment', { attachmentId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', attachmentText: '' })
  const other = context.fake.calls.shownDocuments.at(-1)
  const provider = context.fake.providers.get('kk-studio-attachment')
  context.host.dispose()
  assert.equal(context.fake.providers.size, 0)
  assert.equal(context.fake.closeListeners.size, 0)
  assert.equal(provider.provideTextDocumentContent(other.uri), undefined)
})

test('openAttachment reports editor errors and releases the in-memory document', async (t) => {
  const fake = fakeVscode(undefined, ['/home/user'], { showTextDocument: () => { throw new Error('private editor error') } })
  const context = setup(fake)
  t.after(() => context.host.dispose())
  const id = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
  const result = await command(context, 'openAttachment', { attachmentId: id, attachmentText: 'text' })
  assert.equal(result.ok, false)
  assert.equal(result.message, 'attachment could not be opened')
  const uri = fake.calls.shownDocuments[0].uri
  assert.equal(fake.providers.get('kk-studio-attachment').provideTextDocumentContent(uri), undefined)
})

test('refreshExplorer invokes only the fixed Explorer refresh command', async (t) => {
  const context = setup()
  t.after(() => context.host.dispose())
  const result = await command(context, 'refreshExplorer', { path: '/outside/file.txt', command: 'workbench.action.reloadWindow' })
  assert.equal(result.ok, true)
  assert.deepEqual(context.fake.calls.commands, ['kk-studio.refreshExplorer'])
})

function expectedReply(context, id, result) {
  const hostId = new URL(context.network.streams[0].url).searchParams.get('hostId')
  return Object.assign({ id, viewId: VIEW_ID, bindingEpoch: BINDING_EPOCH, hostId }, result)
}

test('refreshExplorer adds no root to a zero-root window', async (t) => {
  const fake = fakeVscode(WORKSPACE, [])
  const context = setup(fake)
  t.after(() => context.host.dispose())
  const result = await command(context, 'refreshExplorer', { path: '/anywhere/file.txt' })
  assert.deepEqual(result, expectedReply(context, 'cmd-1', { ok: true }))
  assert.deepEqual(fake.calls.commands, ['kk-studio.refreshExplorer'])
  // Refreshing is never a way to acquire a root, and a window without one has nothing to refresh.
  assert.deepEqual(fake.calls.updateWorkspaceFolders, [])
  assert.deepEqual(fake.vscode.workspace.workspaceFolders, [])
})

test('refreshExplorer failure is reported instead of acknowledged as success', async (t) => {
  const fake = fakeVscode(undefined, ['/home/user'], { executeCommand: () => { throw new Error('not available') } })
  const context = setup(fake)
  t.after(() => context.host.dispose())
  const result = await command(context, 'refreshExplorer')
  assert.equal(result.ok, false)
  assert.equal(result.message, 'command failed')
  assert.deepEqual(fake.calls.commands, ['kk-studio.refreshExplorer'])
})

test('bundled manifest activates the bridge and contributes IDE defaults', () => {
  const manifest = JSON.parse(readFileSync(path.join(__dirname, 'package.json'), 'utf8'))
  assert.equal(manifest.main, './extension.cjs')
  assert.deepEqual(manifest.activationEvents, ['*'])
  assert.equal(manifest.contributes.configurationDefaults['workbench.colorTheme'], 'Dark Modern')
  assert.equal(manifest.contributes.configurationDefaults['files.watcherExclude']['**'], true)
})

test('watches only open local file documents and releases subscriptions and watchers', () => {
  const fake = fakeVscode()
  const initial = { uri: fake.fileUri('/workspace/a[1].txt'), isClosed: false }
  const outside = { uri: fake.fileUri('/elsewhere/b.txt'), isClosed: false }
  const virtual = { uri: { scheme: 'untitled', toString: () => 'untitled:1' }, isClosed: false }
  fake.vscode.workspace.textDocuments.push(initial, virtual)
  const host = createBridgeHost(fake.vscode, {})
  assert.equal(fake.watchers.length, 1)
  assert.equal(fake.watchers[0].pattern.pattern, '*')
  assert.equal(fake.watchers[0].pattern.base, initial.uri)
  fake.fireOpen(initial)
  fake.fireOpen(outside)
  fake.fireOpen(virtual)
  assert.equal(fake.watchers.length, 2)
  fake.fireClose(initial)
  assert.equal(fake.watchers[0].disposed, true)
  host.dispose()
  assert.ok(fake.watchers.every((watcher) => watcher.disposed))
  assert.equal(fake.openListeners.size, 0)
  assert.equal(fake.closeListeners.size, 0)
  assert.equal(fake.folderListeners.size, 0)
})

test('registers the assigned workspace, live folders, and bearer credential', async () => {
  const fake = fakeVscode(WORKSPACE, ['/home/user', '/workspace'])
  const context = setup(fake)
  try {
    await connected(context)
    const stream = context.network.streams[0]
    const url = new URL(stream.url)
    assert.equal(url.pathname, '/bridge/stream')
    assert.equal(url.searchParams.get('viewId'), VIEW_ID)
    assert.equal(url.searchParams.get('bindingEpoch'), BINDING_EPOCH)
    assert.equal(url.searchParams.has('workspacePath'), false)
    assert.deepEqual(url.searchParams.getAll('folder'), ['/home/user', '/workspace'])
    assert.match(url.searchParams.get('hostId'), /^[0-9a-f-]{36}$/)
    assert.equal(stream.requestOptions.headers.authorization, 'Bearer bridge-secret')
  } finally { context.host.dispose() }
})

test('does not connect without a valid trusted managed view identity', async () => {
  for (const identity of [{ viewId: 'not-a-view', bindingEpoch: '7' }, { viewId: VIEW_ID, bindingEpoch: '07' }]) {
    const context = setup(fakeVscode(), identity)
    try {
      await new Promise((resolve) => setImmediate(resolve))
      assert.equal(context.network.streams.length, 0)
    } finally { context.host.dispose() }
  }
})

test('registers the first workspace folder when no workspace file is open', async () => {
  const fake = fakeVscode()
  fake.vscode.workspace.workspaceFile = undefined
  const context = setup(fake)
  try {
    await connected(context)
    assert.equal(new URL(context.network.streams[0].url).searchParams.get('viewId'), VIEW_ID)
    assert.equal((await focus(context)).ok, true)
    assert.deepEqual(context.fake.calls.createTerminal, [{ name: 'kk-studio', cwd: '/home/user' }])
  } finally { context.host.dispose() }
})

test('creates and focuses a terminal through public APIs and reports its pid for supervision', async () => {
  const context = setup()
  try {
    const result = await focus(context)
    assert.equal(result.ok, true)
    assert.deepEqual(context.fake.calls.createTerminal, [{ name: 'kk-studio', cwd: '/home/user' }])
    assert.equal(context.fake.terminals[0].shown, true)
    const hostId = new URL(context.network.streams[0].url).searchParams.get('hostId')
    const pty = context.network.posts.find((post) => post.pathname === '/bridge/pty')
    assert.deepEqual(pty.payload, { viewId: VIEW_ID, bindingEpoch: BINDING_EPOCH, hostId, pid: 4242 })
    const postedResult = context.network.posts.find((post) => post.pathname === '/bridge/result')
    assert.equal(postedResult.payload.viewId, VIEW_ID)
    assert.equal(postedResult.payload.bindingEpoch, BINDING_EPOCH)
    assert.equal(postedResult.payload.hostId, hostId)
    assert.equal(pty.requestOptions.headers.authorization, 'Bearer bridge-secret')
  } finally { context.host.dispose() }
})

test('reuses the existing terminal when focus is requested repeatedly', async () => {
  const context = setup()
  try {
    assert.equal((await focus(context)).ok, true)
    assert.equal((await focus(context)).ok, true)
    assert.equal(context.fake.calls.createTerminal.length, 1)
    assert.equal(context.fake.terminals.length, 1)
  } finally { context.host.dispose() }
})

test('disposes an unsupervised terminal when its process id is unavailable', async () => {
  const context = setup(fakeVscode(WORKSPACE, ['/home/user'], { processId: () => Promise.reject(new Error('no pid')) }))
  try {
    const result = await focus(context)
    assert.equal(result.ok, false)
    assert.equal(result.message, 'terminal supervision is unavailable')
    assert.equal(context.network.posts.some((post) => post.pathname === '/bridge/pty'), false)
    assert.equal(context.fake.terminals.length, 0)
  } finally { context.host.dispose() }
})

test('disposes a terminal when daemon-side process supervision rejects it', async () => {
  const network = fakeHttp({ postStatuses: [403] })
  const context = setup(fakeVscode(), { network })
  try {
    const result = await focus(context)
    assert.equal(result.ok, false)
    assert.equal(result.message, 'terminal supervision failed')
    assert.equal(context.fake.terminals.length, 0)
  } finally { context.host.dispose() }
})

test('confirms a no-op directory change without closing editors or restarting', async () => {
  const fake = fakeVscode(WORKSPACE, ['/home/user'], { tabGroups: [{ tabs: [{}] }] })
  const context = setup(fake)
  try {
    const result = await command(context, 'changeDirectory', { directory: '/home/user' })
    assert.deepEqual(result, expectedReply(context, 'cmd-1', { ok: true, directory: '/home/user' }))
    assert.deepEqual(fake.calls.updateWorkspaceFolders, [])
    assert.deepEqual(fake.calls.closeTabs, [])
    assert.deepEqual(fake.calls.commands, [])
    assert.equal(context.network.streams[0].request.destroyed, false)
  } finally { context.host.dispose() }
})

test('leaves a cancelled native prompt in control of the current root', async () => {
  const fake = fakeVscode(WORKSPACE, ['/home/user'], { tabGroups: [{ tabs: [{}] }], closeTabs: () => false })
  const context = setup(fake)
  try {
    const result = await command(context, 'changeDirectory', { directory: '/target' })
    assert.deepEqual(
      result,
      expectedReply(context, 'cmd-1', { ok: false, code: 'CANCELLED', message: 'directory change was cancelled' }),
    )
    assert.equal(fake.calls.closeTabs.length, 1)
    assert.deepEqual(fake.calls.updateWorkspaceFolders, [])
    assert.deepEqual(fake.calls.commands, [])
    await new Promise((resolve) => setTimeout(resolve, 25))
    assert.equal(context.network.streams.length, 1)
    assert.equal(context.network.streams[0].request.destroyed, false)
  } finally { context.host.dispose() }
})

test('refuses a directory change when an unrepresented dirty document survives', async () => {
  // The native prompts only cover editors they can see; a hidden draft keeps the old root.
  const context = setup()
  try {
    context.fake.vscode.workspace.textDocuments.push({ isDirty: true })
    const result = await command(context, 'changeDirectory', { directory: '/target' })
    assert.deepEqual(
      result,
      expectedReply(context, 'cmd-1', {
        ok: false,
        code: 'DIRTY',
        message: 'save or discard unsaved changes before switching directory',
      }),
    )
    assert.deepEqual(context.fake.calls.updateWorkspaceFolders, [])
    assert.equal(context.network.streams[0].request.destroyed, false)
  } finally { context.host.dispose() }
})

test('gives a zero-root window its first managed root through a restarted host', async () => {
  const fake = fakeVscode(WORKSPACE, [])
  const context = setup(fake)
  try {
    await connected(context)
    // A zero-root window registers no folder at all; it is not a folder that failed to resolve.
    assert.deepEqual(new URL(context.network.streams[0].url).searchParams.getAll('folder'), [])
    const [start, remove, added] = await changeDirectory(context, { directory: '/target' })
    assert.deepEqual([start, remove], [0, 0])
    assert.equal(added.length, 1)
    assert.equal(added[0].uri.fsPath, '/target')
    // The folder list is live here, so this window restarts and its fresh host reports the root.
    assert.deepEqual(fake.calls.commands, ['workbench.action.reloadWindow'])
    assert.equal(context.network.streams[0].request.destroyed, true)
    assert.equal(context.network.posts.some((post) => post.payload.id === 'change'), false)
  } finally { context.host.dispose() }
})

test('replaces every live folder before the window restarts into the new root', async () => {
  const fake = fakeVscode(WORKSPACE, ['/first', '/second'])
  const context = setup(fake)
  try {
    const [start, remove] = await changeDirectory(context, { directory: '/target' })
    assert.deepEqual([start, remove], [0, 2])
    assert.deepEqual(fake.vscode.workspace.workspaceFolders.map((folder) => folder.uri.fsPath), ['/target'])
    assert.deepEqual(fake.calls.commands, ['workbench.action.reloadWindow'])
  } finally { context.host.dispose() }
})

test('does not restart a window the workbench replaces for the new root', async () => {
  // Without a workspace file a folder swap opens a replacement window, whose fresh host confirms
  // the target root; asking this window to reload as well would restart it for nothing.
  const fake = fakeVscode(WORKSPACE, ['/home/user'])
  fake.vscode.workspace.workspaceFile = undefined
  const context = setup(fake)
  try {
    const [start, remove, added] = await changeDirectory(context, { directory: '/target' })
    assert.deepEqual([start, remove], [0, 1])
    assert.equal(added[0].uri.fsPath, '/target')
    assert.deepEqual(fake.calls.commands, [])
    assert.equal(context.network.streams[0].request.destroyed, true)
  } finally { context.host.dispose() }
})

test('keeps the old root when the workbench never shows the applied folder change', async () => {
  // Fail closed: without evidence that the new root is live, no restart is asked for and no
  // confirmation is claimed, so the daemon keeps the change unknown instead of trusting it.
  const fake = fakeVscode(WORKSPACE, ['/home/user'], { updateWorkspaceFolders: () => true })
  const context = setup(fake, { directoryConfirmMillis: 5 })
  try {
    await connected(context)
    context.network.streams[0].response.write(`${JSON.stringify({ type: 'command', id: 'change', op: 'changeDirectory', directory: '/target' })}\n`)
    await waitFor(() => fake.calls.updateWorkspaceFolders.length === 1)
    await waitFor(() => context.network.streams[0].request.destroyed)
    assert.deepEqual(fake.calls.commands, [])
    assert.equal(context.network.posts.some((post) => post.payload.id === 'change'), false)
  } finally { context.host.dispose() }
})

test('refuses a directory the daemon cannot have registered', async () => {
  const directories = ['relative/dir', `/home/user/${'a'.repeat(4096)}`, '/home/user/\u0000bad', null, 42]
  for (const directory of directories) {
    const fake = fakeVscode()
    const context = setup(fake)
    try {
      const result = await command(context, 'changeDirectory', { directory })
      assert.equal(result.ok, false)
      assert.equal(result.message, 'directory change was rejected')
      assert.deepEqual(fake.calls.updateWorkspaceFolders, [])
      assert.deepEqual(fake.calls.closeTabs, [])
      assert.deepEqual(fake.vscode.workspace.workspaceFolders.map((folder) => folder.uri.fsPath), ['/home/user'])
    } finally { context.host.dispose() }
  }
})

test('reports a rejected public workspace-folder update safely', async () => {
  const context = setup(fakeVscode(WORKSPACE, ['/home/user'], { updateWorkspaceFolders: () => false }))
  try {
    const result = await command(context, 'changeDirectory', { directory: '/target' })
    assert.equal(result.ok, false)
    assert.equal(result.message, 'directory change was rejected')
    assert.equal(context.network.streams[0].request.destroyed, false)
    assert.deepEqual(context.fake.calls.commands, [])
  } finally { context.host.dispose() }
})

test('uses native commands to collapse both mobile workbench panels in order', async () => {
  const context = setup()
  try {
    assert.equal((await command(context, 'collapseMobile')).ok, true)
    assert.deepEqual(context.fake.calls.commands, ['workbench.action.closeSidebar', 'workbench.action.closePanel'])
  } finally { context.host.dispose() }
})

test('waits for native close across all groups and preserves terminals when cancelled', async () => {
  let decide
  const closeDecision = new Promise((resolve) => { decide = resolve })
  const first = {}, second = {}
  const context = setup(fakeVscode(WORKSPACE, ['/home/user'], { tabGroups: [{ tabs: [first] }, { tabs: [second] }], closeTabs: () => closeDecision }))
  try {
    await focus(context)
    await connected(context)
    const before = context.network.posts.filter((post) => post.pathname === '/bridge/result').length
    context.network.streams[0].response.write(`${JSON.stringify({ type: 'command', id: 'close', op: 'closeView' })}\n`)
    await waitFor(() => context.fake.calls.closeTabs.length === 1)
    assert.deepEqual(context.fake.calls.closeTabs[0], [first, second])
    decide(false)
    await waitFor(() => context.network.posts.filter((post) => post.pathname === '/bridge/result').length > before)
    assert.equal(context.network.posts.filter((post) => post.pathname === '/bridge/result').at(-1).payload.code, 'CANCELLED')
    assert.equal(context.fake.terminals.length, 1)
  } finally { context.host.dispose() }
})

test('leaves terminal ownership to the managed view after a successful native close', async () => {
  const context = setup()
  try {
    await focus(context)
    const result = await command(context, 'closeView')
    assert.equal(result.ok, true)
    assert.equal(context.fake.terminals.length, 1)
  } finally { context.host.dispose() }
})

test('rejects native close when an unrepresented dirty document remains', async () => {
  const context = setup()
  try {
    context.fake.vscode.workspace.textDocuments.push({ isDirty: true })
    const result = await command(context, 'closeView')
    assert.equal(result.code, 'CANCELLED')
  } finally { context.host.dispose() }
})

test('accepts a committed native close despite a lagging dirty mirror for a closed text tab', async () => {
  const uri = contextUri('/home/user/editable.txt')
  const context = setup(fakeVscode(WORKSPACE, ['/home/user'], { tabGroups: [{ tabs: [{ input: new FakeTabInputText(uri) }] }] }))
  try {
    context.fake.vscode.workspace.textDocuments.push({ uri, isDirty: true, isClosed: false })
    assert.equal((await command(context, 'closeView')).ok, true)
  } finally { context.host.dispose() }
})

function contextUri(fsPath) { return { scheme: 'file', fsPath, toString: () => `file://${fsPath}` } }

test('rejects close when another tab appears while native close is pending', async () => {
  let fake
  fake = fakeVscode(WORKSPACE, ['/home/user'], {
    tabGroups: [{ tabs: [{}] }],
    closeTabs: async () => { fake.vscode.window.tabGroups.all[0].tabs.push({}); return true },
  })
  const context = setup(fake)
  try { assert.equal((await command(context, 'closeView')).code, 'CANCELLED') }
  finally { context.host.dispose() }
})

test('explicit discard skips interactive tab closing and disposes owned terminals', async () => {
  const context = setup()
  try {
    await focus(context)
    context.fake.vscode.workspace.textDocuments.push({ isDirty: true })
    const result = await command(context, 'closeView', { discard: true })
    assert.equal(result.ok, true)
    assert.deepEqual(context.fake.calls.closeTabs, [])
    assert.equal(context.fake.terminals.length, 0)
  } finally { context.host.dispose() }
})

test('does not prove a custom editor clean', async () => {
  class TabInputCustom {}
  const context = setup(fakeVscode(WORKSPACE, ['/home/user'], { tabGroups: [{ tabs: [{ input: new TabInputCustom() }] }] }))
  context.fake.vscode.TabInputCustom = TabInputCustom
  try {
    assert.deepEqual(await command(context, 'inspect'), expectedReply(context, 'cmd-1', { ok: true, clean: false }))
  } finally { context.host.dispose() }
})

test('snapshots ordinary dirty text and reports opaque editor state as incomplete', async () => {
  class TabInputCustom {}
  const context = setup(fakeVscode(WORKSPACE, ['/home/user'], { tabGroups: [{ tabs: [] }] }))
  context.fake.vscode.workspace.textDocuments.push({
    uri: contextUri('/home/user/draft.txt'), isDirty: true, isClosed: false, getText: () => 'unsaved text',
  })
  try {
    const snapshot = await command(context, 'snapshot', { maxBytes: 1024 })
    assert.deepEqual(snapshot.documents, [{ path: '/home/user/draft.txt', text: 'unsaved text' }])
    assert.equal(snapshot.complete, true)
    context.fake.vscode.TabInputCustom = TabInputCustom
    context.fake.vscode.window.tabGroups.all = [{ tabs: [{ input: new TabInputCustom() }] }]
    const opaque = await command(context, 'snapshot', { maxBytes: 1024 })
    assert.equal(opaque.complete, false)
    assert.ok(opaque.omitted > 0)
  } finally { context.host.dispose() }
})

test('omits snapshot documents that exceed the requested byte budget', async () => {
  const context = setup()
  context.fake.vscode.workspace.textDocuments.push({
    uri: contextUri('/home/user/large.txt'), isDirty: true, isClosed: false, getText: () => '12345',
  })
  try {
    const snapshot = await command(context, 'snapshot', { maxBytes: 4 })
    assert.deepEqual(snapshot.documents, [])
    assert.equal(snapshot.omitted, 1)
    assert.equal(snapshot.complete, false)
  } finally { context.host.dispose() }
})

test('omits every dirty buffer a path-keyed backup cannot hold', async () => {
  const context = setup()
  const untitled = { scheme: 'untitled', fsPath: 'Untitled-1', toString: () => 'untitled:Untitled-1' }
  const virtual = {
    scheme: 'kk-studio-attachment', path: '/attachment-x.txt', toString: () => 'kk-studio-attachment:/attachment-x.txt',
  }
  context.fake.vscode.workspace.textDocuments.push(
    { uri: contextUri('/home/user/draft.txt'), isDirty: true, isClosed: false, getText: () => 'unsaved text' },
    { uri: untitled, isDirty: true, isClosed: false, getText: () => 'scratch notes' },
    { uri: virtual, isDirty: true, isClosed: false, getText: () => 'virtual text' },
    { uri: contextUri('relative/draft.txt'), isDirty: true, isClosed: false, getText: () => 'relative text' },
    { uri: contextUri('/home/user/saved.txt'), isDirty: false, isClosed: false, getText: () => 'clean text' },
  )
  try {
    // The window is not provably clean, so an explicit close must still leave the decision to the user.
    assert.equal((await command(context, 'inspect')).clean, false)
    const snapshot = await command(context, 'snapshot', { maxBytes: 1024 })
    assert.deepEqual(snapshot.documents, [{ path: '/home/user/draft.txt', text: 'unsaved text' }])
    assert.equal(snapshot.omitted, 3)
    assert.equal(snapshot.complete, false)
  } finally { context.host.dispose() }
})

test('reports an unreadable dirty text as an omission instead of a complete backup', async () => {
  const context = setup()
  context.fake.vscode.workspace.textDocuments.push({
    uri: contextUri('/home/user/locked.txt'),
    isDirty: true,
    isClosed: false,
    getText: () => { throw new Error('private read failure') },
  })
  try {
    const snapshot = await command(context, 'snapshot', { maxBytes: 1024 })
    assert.deepEqual(snapshot.documents, [])
    assert.equal(snapshot.omitted, 1)
    assert.equal(snapshot.complete, false)
    assert.equal(JSON.stringify(snapshot).includes('private read failure'), false)
  } finally { context.host.dispose() }
})

test('reports dirty documents beyond the per-snapshot limit as omissions', async () => {
  const context = setup()
  for (let index = 0; index < 257; index += 1) {
    context.fake.vscode.workspace.textDocuments.push({
      uri: contextUri(`/home/user/doc-${index}.txt`), isDirty: true, isClosed: false, getText: () => 'x',
    })
  }
  try {
    const snapshot = await command(context, 'snapshot', { maxBytes: 4 * 1024 * 1024 })
    assert.equal(snapshot.documents.length, 256)
    assert.equal(snapshot.omitted, 1)
    assert.equal(snapshot.complete, false)
  } finally { context.host.dispose() }
})

test('omits a dirty document the workbench already closed', async () => {
  const context = setup()
  context.fake.vscode.workspace.textDocuments.push({
    uri: contextUri('/home/user/closed.txt'), isDirty: true, isClosed: true, getText: () => 'stale mirror',
  })
  try {
    const snapshot = await command(context, 'snapshot', { maxBytes: 1024 })
    assert.deepEqual(snapshot.documents, [])
    assert.equal(snapshot.omitted, 1)
    assert.equal(snapshot.complete, false)
  } finally { context.host.dispose() }
})

test('omits a dirty document whose path the daemon would reject', async () => {
  const context = setup()
  context.fake.vscode.workspace.textDocuments.push({
    uri: contextUri(`/home/user/${'a'.repeat(4096)}.txt`), isDirty: true, isClosed: false, getText: () => 'x',
  })
  try {
    const snapshot = await command(context, 'snapshot', { maxBytes: 4 * 1024 * 1024 })
    assert.deepEqual(snapshot.documents, [])
    assert.equal(snapshot.omitted, 1)
    assert.equal(snapshot.complete, false)
  } finally { context.host.dispose() }
})

test('does not claim a snapshot complete when the workbench cannot enumerate tabs', async () => {
  const fake = fakeVscode()
  fake.vscode.window.tabGroups = undefined
  const context = setup(fake)
  try {
    const snapshot = await command(context, 'snapshot', { maxBytes: 1024 })
    assert.deepEqual(snapshot.documents, [])
    assert.equal(snapshot.omitted, 1)
    assert.equal(snapshot.complete, false)
  } finally { context.host.dispose() }
})

test('counts a dirty text tab not yet mirrored by the extension host as omitted', async () => {
  const uri = { toString: () => 'file:///home/user/draft.txt' }
  const context = setup(fakeVscode(WORKSPACE, ['/home/user'], {
    tabGroups: [{ tabs: [{ input: new FakeTabInputText(uri), isDirty: true }] }],
  }))
  try {
    const snapshot = await command(context, 'snapshot', { maxBytes: 1024 })
    assert.deepEqual(snapshot.documents, [])
    assert.equal(snapshot.omitted, 1)
    assert.equal(snapshot.complete, false)
  } finally { context.host.dispose() }
})

test('a clean window snapshots as an explicit complete and empty backup', async () => {
  const context = setup()
  context.fake.vscode.workspace.textDocuments.push({
    uri: contextUri('/home/user/saved.txt'), isDirty: false, isClosed: false, getText: () => 'saved text',
  })
  try {
    // `complete` and `omitted` are always present, so an expiry decision never has to guess a default.
    assert.deepEqual(
      await command(context, 'snapshot', { maxBytes: 1024 }),
      expectedReply(context, 'cmd-1', { ok: true, documents: [], bytes: 0, omitted: 0, complete: true }),
    )
  } finally { context.host.dispose() }
})

test('backs up and restores an absolute draft in a zero-root window', async () => {
  // A view without a root still holds drafts; recovery keys them by absolute path, not by folder.
  const fake = fakeVscode(WORKSPACE, [])
  fake.openedDocuments.set('/outside/draft.txt', {
    uri: contextUri('/outside/draft.txt'), isDirty: false, isClosed: false,
    getText: () => 'current', positionAt: (offset) => new FakePosition(0, offset),
  })
  fake.vscode.workspace.textDocuments.push({
    uri: contextUri('/outside/draft.txt'), isDirty: true, isClosed: false, getText: () => 'unsaved',
  })
  const context = setup(fake)
  try {
    assert.deepEqual(
      await command(context, 'snapshot', { maxBytes: 1024 }),
      expectedReply(context, 'cmd-1', {
        ok: true, documents: [{ path: '/outside/draft.txt', text: 'unsaved' }], bytes: 7, omitted: 0, complete: true,
      }),
    )
    const restored = await command(context, 'restore', { documents: [{ path: '/outside/draft.txt', text: 'recovered' }] })
    assert.deepEqual(restored, expectedReply(context, 'cmd-2', { ok: true, restored: 1, skipped: 0 }))
    assert.equal(fake.calls.applyEdit[0].edits[0].text, 'recovered')
  } finally { context.host.dispose() }
})

test('explicit discard still closes a view whose untitled buffer cannot be backed up', async () => {
  const context = setup()
  await focus(context)
  context.fake.vscode.workspace.textDocuments.push({
    uri: { scheme: 'untitled', fsPath: 'Untitled-1', toString: () => 'untitled:Untitled-1' },
    isDirty: true,
    isClosed: false,
    getText: () => 'scratch notes',
  })
  try {
    assert.equal((await command(context, 'snapshot', { maxBytes: 1024 })).complete, false)
    const closed = await command(context, 'closeView', { discard: true })
    assert.equal(closed.ok, true)
    assert.deepEqual(context.fake.calls.closeTabs, [])
    assert.equal(context.fake.terminals.length, 0)
  } finally { context.host.dispose() }
})

test('restores only clean absolute text documents and never saves them', async () => {
  const context = setup()
  context.fake.openedDocuments.set('/home/user/clean.txt', {
    uri: contextUri('/home/user/clean.txt'), isDirty: false, isClosed: false,
    getText: () => 'original', positionAt: (offset) => new FakePosition(0, offset),
  })
  context.fake.openedDocuments.set('/home/user/dirty.txt', {
    uri: contextUri('/home/user/dirty.txt'), isDirty: true, isClosed: false,
    getText: () => 'current', positionAt: (offset) => new FakePosition(0, offset),
  })
  try {
    const result = await command(context, 'restore', { documents: [
      { path: '/home/user/clean.txt', text: 'recovered' },
      { path: '/home/user/dirty.txt', text: 'must not overwrite' },
      { path: 'relative.txt', text: 'invalid' },
    ] })
    assert.equal(result.restored, 1)
    assert.equal(result.skipped, 2)
    assert.equal(context.fake.calls.applyEdit.length, 1)
    assert.equal(context.fake.calls.applyEdit[0].edits[0].text, 'recovered')
    assert.equal(context.fake.calls.shownDocuments.length, 1)
  } finally { context.host.dispose() }
})

test('reports unsupported commands without exposing internal errors', async () => {
  const context = setup()
  try {
    const result = await command(context, 'unknown')
    assert.deepEqual(result, expectedReply(context, 'cmd-1', { ok: false, message: 'unsupported command' }))
  } finally { context.host.dispose() }
})

test('reports unexpected VS Code API failures as safe command errors', async () => {
  const context = setup(fakeVscode(WORKSPACE, ['/home/user'], {
    updateWorkspaceFolders() { throw new Error('private stack detail') },
  }))
  try {
    const result = await command(context, 'changeDirectory', { directory: '/target' })
    assert.equal(result.message, 'command failed')
    assert.equal(JSON.stringify(result).includes('private stack detail'), false)
  } finally { context.host.dispose() }
})

test('reconnects after an ordinary network disconnect with the same host identity', async () => {
  const context = setup(undefined, { reconnectMillis: 1 })
  try {
    await focus(context)
    const terminal = context.fake.terminals[0]
    const first = context.network.streams[0]
    const firstHostId = new URL(first.url).searchParams.get('hostId')
    first.response.end()
    await waitFor(() => context.network.streams.length >= 2)
    assert.equal(new URL(context.network.streams[1].url).searchParams.get('hostId'), firstHostId)
    assert.equal(terminal.disposed, false)
    assert.equal((await command(context, 'inspect')).clean, true)
  } finally { context.host.dispose() }
})

test('cancels a pending control POST on retirement without disposing the terminal', async () => {
  const network = fakeHttp()
  const context = setup(undefined, { network })
  try {
    await focus(context)
    const terminal = context.fake.terminals[0]
    context.network.hold('/bridge/result')
    const before = context.network.posts.filter((post) => post.pathname === '/bridge/result').length
    context.network.streams[0].response.write(`${JSON.stringify({ type: 'command', id: 'inspect-held', op: 'inspect' })}\n`)
    await waitFor(() => context.network.posts.filter((post) => post.pathname === '/bridge/result').length > before)
    const pending = context.network.posts.filter((post) => post.pathname === '/bridge/result').at(-1)
    context.network.streams[0].response.write(`${JSON.stringify({ type: 'retired' })}\n`)
    await waitFor(() => context.network.streams[0].request.destroyed && pending.request.destroyed)
    assert.equal(terminal.disposed, false)
    context.host.dispose()
    assert.equal(terminal.disposed, false)
  } finally { context.host.dispose() }
})

test('a retired control host does not reconnect or dispose its terminal', async () => {
  const context = setup(undefined, { reconnectMillis: 1 })
  try {
    await focus(context)
    const terminal = context.fake.terminals[0]
    context.network.streams[0].response.write(`${JSON.stringify({ type: 'retired' })}\n`)
    await waitFor(() => context.network.streams[0].request.destroyed)
    assert.equal(terminal.disposed, false)
    assert.equal(context.network.streams.length, 1)
  } finally { context.host.dispose() }
})

test('dispatches commands decoded before a bad line in the same chunk', async () => {
  const context = setup(undefined, { reconnectMillis: 1 })
  try {
    await focus(context)
    const results = () => context.network.posts.filter((post) => post.pathname === '/bridge/result')
    const before = results().length
    // Both lines arrive together: the valid command still runs, then the bad line fails the stream.
    context.network.streams[0].response.write(
      `${JSON.stringify({ type: 'command', id: 'cmd-before-bad-line', op: 'inspect' })}\n{not json\n`,
    )
    await waitFor(() => results().length > before)
    assert.equal(results().at(-1).payload.ok, true)
    await waitFor(() => context.network.streams.length >= 2)
  } finally { context.host.dispose() }
})

test('retirement in a chunk stops parsing before a later bad line', async () => {
  const context = setup(undefined, { reconnectMillis: 1 })
  try {
    await focus(context)
    const terminal = context.fake.terminals[0]
    // The trailing bad line never counts: retirement already ended consumption.
    context.network.streams[0].response.write(`${JSON.stringify({ type: 'retired' })}\n{not json\n`)
    await waitFor(() => context.network.streams[0].request.destroyed)
    await new Promise((resolve) => setTimeout(resolve, 25))
    assert.equal(context.network.streams.length, 1)
    assert.equal(terminal.disposed, false)
  } finally { context.host.dispose() }
})

test('retries a rejected handshake without crashing the extension host', async () => {
  const context = setup(undefined, { httpOptions: { streamStatuses: [401, 200] }, reconnectMillis: 1 })
  try {
    await waitFor(() => context.network.streams.length >= 2)
    assert.equal(context.network.streams[0].status, 401)
    assert.equal(context.network.streams[1].status, 200)
    assert.equal(new URL(context.network.streams[1].url).searchParams.get('viewId'), VIEW_ID)
  } finally { context.host.dispose() }
})

test('stops reconnecting when the daemon returns 410 for a superseded host', async () => {
  const context = setup(undefined, { httpOptions: { streamStatuses: [200, 410] }, reconnectMillis: 1 })
  try {
    await connected(context)
    const first = context.network.streams[0]
    first.response.end()
    await waitFor(() => context.network.streams.length >= 2)
    assert.equal(context.network.streams[1].status, 410)
    await new Promise((resolve) => setTimeout(resolve, 25))
    assert.equal(context.network.streams.length, 2)
  } finally { context.host.dispose() }
})

test('restarts registration when the workbench changes folders without a managed command', async () => {
  const context = setup(undefined, { reconnectMillis: 1 })
  try {
    await connected(context)
    context.fake.fireFolders()
    await waitFor(() => context.network.streams.length >= 2)
    assert.deepEqual(new URL(context.network.streams[1].url).searchParams.getAll('folder'), ['/home/user'])
  } finally { context.host.dispose() }
})

test('gives up control when a folder change leaves the managed binding', async () => {
  const network = fakeHttp({ streamStatuses: [200, 410] })
  const context = setup(undefined, { network, reconnectMillis: 1 })
  try {
    await connected(context)
    context.fake.vscode.workspace.workspaceFolders = [{ uri: context.fake.fileUri('/elsewhere') }]
    context.fake.fireFolders()
    await waitFor(() => context.network.streams.length >= 2)
    assert.deepEqual(new URL(context.network.streams[1].url).searchParams.getAll('folder'), ['/elsewhere'])
    // The daemon refuses a window that is no longer the managed root, and a refused host stays gone.
    await waitFor(() => context.network.streams[1].request.destroyed)
    await new Promise((resolve) => setTimeout(resolve, 25))
    assert.equal(context.network.streams.length, 2)
  } finally { context.host.dispose() }
})

test('normal disposal releases the stream and all owned terminals', async () => {
  const context = setup()
  await focus(context)
  const terminal = context.fake.terminals[0]
  context.host.dispose()
  assert.equal(terminal.disposed, true)
  assert.equal(context.fake.folderListeners.size, 0)
  assert.equal(context.network.streams[0].request.destroyed, true)
})
