'use strict'

const assert = require('node:assert/strict')
const { EventEmitter } = require('node:events')
const { PassThrough } = require('node:stream')
const { test } = require('node:test')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const { createBridgeHost } = require('./bridge.cjs')

const VIEW_ID = '11111111-1111-4111-8111-111111111111'
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
  const subscriptions = (listeners, listener) => ({ dispose: () => listeners.delete(listener) })
  const fileUri = (fsPath) => ({ scheme: 'file', fsPath, toString: () => `file://${fsPath}` })

  class RelativePattern {
    constructor(base, pattern) { this.base = base; this.pattern = pattern }
  }

  const vscode = {
    Uri: { file: fileUri },
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
      createFileSystemWatcher(pattern) {
        const watcher = { pattern, disposed: false, dispose() { watcher.disposed = true } }
        watchers.push(watcher)
        return watcher
      },
      updateWorkspaceFolders(start, remove, ...added) {
        calls.updateWorkspaceFolders.push([start, remove, added])
        return options.updateWorkspaceFolders ? options.updateWorkspaceFolders(start, remove, ...added) : true
      },
      async openTextDocument(uri) {
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
      async showTextDocument(document) { calls.shownDocuments.push(document) },
    },
  }
  return {
    vscode, calls, terminals, openedDocuments, folderListeners, openListeners, closeListeners, watchers,
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
    reconnectMillis: options.reconnectMillis === undefined ? 5 : options.reconnectMillis,
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
    assert.equal(url.searchParams.get('workspacePath'), WORKSPACE)
    assert.deepEqual(url.searchParams.getAll('folder'), ['/home/user', '/workspace'])
    assert.match(url.searchParams.get('hostId'), /^[0-9a-f-]{36}$/)
    assert.equal(stream.requestOptions.headers.authorization, 'Bearer bridge-secret')
  } finally { context.host.dispose() }
})

test('registers the first workspace folder when no workspace file is open', async () => {
  const fake = fakeVscode()
  fake.vscode.workspace.workspaceFile = undefined
  const context = setup(fake)
  try {
    await connected(context)
    assert.equal(new URL(context.network.streams[0].url).searchParams.get('workspacePath'), '/home/user')
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
    const pty = context.network.posts.find((post) => post.pathname === '/bridge/pty')
    assert.deepEqual(pty.payload, { workspacePath: WORKSPACE, pid: 4242 })
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

test('refuses a directory change while text documents are dirty', async () => {
  const context = setup()
  try {
    context.fake.vscode.workspace.textDocuments.push({ isDirty: true })
    const result = await command(context, 'changeDirectory', { directory: '/target' })
    assert.equal(result.code, 'DIRTY')
    assert.deepEqual(context.fake.calls.updateWorkspaceFolders, [])
  } finally { context.host.dispose() }
})

test('confirms a no-op directory change without restarting the extension host', async () => {
  const context = setup()
  try {
    const result = await command(context, 'changeDirectory', { directory: '/home/user' })
    assert.deepEqual(result, { id: 'cmd-1', ok: true, directory: '/home/user' })
    assert.deepEqual(context.fake.calls.updateWorkspaceFolders, [])
    assert.equal(context.network.streams[0].request.destroyed, false)
  } finally { context.host.dispose() }
})

test('reports a rejected public workspace-folder update safely', async () => {
  const context = setup(fakeVscode(WORKSPACE, ['/home/user'], { updateWorkspaceFolders: () => false }))
  try {
    const result = await command(context, 'changeDirectory', { directory: '/target' })
    assert.equal(result.ok, false)
    assert.equal(result.message, 'directory change was rejected')
    assert.equal(context.network.streams[0].request.destroyed, false)
  } finally { context.host.dispose() }
})

test('retires control immediately after a public folder update is accepted', async () => {
  const context = setup()
  try {
    await connected(context)
    context.network.streams[0].response.write(`${JSON.stringify({ type: 'command', id: 'change', op: 'changeDirectory', directory: '/target' })}\n`)
    await waitFor(() => context.fake.calls.updateWorkspaceFolders.length === 1)
    await waitFor(() => context.network.streams[0].request.destroyed)
    assert.equal(context.network.posts.some((post) => post.payload.id === 'change'), false)
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
    assert.deepEqual(await command(context, 'inspect'), { id: 'cmd-1', ok: true, clean: false })
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
    assert.deepEqual(result, { id: 'cmd-1', ok: false, message: 'unsupported command' })
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

test('retries a rejected handshake without crashing the extension host', async () => {
  const context = setup(undefined, { httpOptions: { streamStatuses: [401, 200] }, reconnectMillis: 1 })
  try {
    await waitFor(() => context.network.streams.length >= 2)
    assert.equal(context.network.streams[0].status, 401)
    assert.equal(context.network.streams[1].status, 200)
    assert.equal(new URL(context.network.streams[1].url).searchParams.get('workspacePath'), WORKSPACE)
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

test('does not re-register when workspace folders change during an active session', async () => {
  const context = setup(undefined, { reconnectMillis: 1 })
  try {
    await connected(context)
    context.fake.fireFolders()
    await waitFor(() => context.network.streams.length >= 2)
    assert.deepEqual(new URL(context.network.streams[1].url).searchParams.getAll('folder'), ['/home/user'])
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
