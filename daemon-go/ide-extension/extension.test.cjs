'use strict'

const assert = require('node:assert/strict')
const { test } = require('node:test')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const { createBridgeHost } = require('./bridge.cjs')

function fakeVscode() {
  const opens = new Set()
  const closes = new Set()
  const folders = new Set()
  const watchers = []
  const subscriptions = (listeners) => ({ dispose: () => listeners.clear() })
  class RelativePattern {
    constructor(base, pattern) {
      this.base = base
      this.pattern = pattern
    }
  }
  const vscode = {
    RelativePattern,
    workspace: {
      textDocuments: [],
      workspaceFolders: [],
      onDidOpenTextDocument(listener) {
        opens.add(listener)
        return subscriptions(opens)
      },
      onDidCloseTextDocument(listener) {
        closes.add(listener)
        return subscriptions(closes)
      },
      onDidChangeWorkspaceFolders(listener) {
        folders.add(listener)
        return subscriptions(folders)
      },
      createFileSystemWatcher(pattern) {
        const watcher = { pattern, disposed: false, dispose() { watcher.disposed = true } }
        watchers.push(watcher)
        return watcher
      },
    },
  }
  return { vscode, opens, closes, folders, watchers }
}

test('bundled extension manifest activates the bridge and contributes IDE defaults', () => {
  const manifest = JSON.parse(readFileSync(path.join(__dirname, 'package.json'), 'utf8'))
  assert.equal(manifest.main, './extension.cjs')
  assert.deepEqual(manifest.activationEvents, ['*'])
  assert.equal(manifest.contributes.configurationDefaults['workbench.colorTheme'], 'Dark Modern')
  assert.equal(manifest.contributes.configurationDefaults['files.watcherExclude']['**'], true)
})

test('bridge watches opened file documents and releases watchers and subscriptions on disposal', () => {
  const fake = fakeVscode()
  const document = {
    uri: { scheme: 'file', fsPath: '/workspace/file.txt', toString: () => 'file:///workspace/file.txt' },
    isClosed: false,
  }
  fake.vscode.workspace.textDocuments.push(document)
  const host = createBridgeHost(fake.vscode)

  assert.equal(fake.watchers.length, 1)
  assert.equal(fake.watchers[0].pattern.pattern, '*')
  assert.equal(fake.watchers[0].pattern.base, document.uri)
  fake.opens.values().next().value({ uri: { scheme: 'file', fsPath: '/workspace/other.txt', toString: () => 'file:///workspace/other.txt' }, isClosed: false })
  assert.equal(fake.watchers.length, 2)
  host.dispose()
  assert.ok(fake.watchers.every((watcher) => watcher.disposed))
  assert.equal(fake.opens.size, 0)
  assert.equal(fake.closes.size, 0)
  assert.equal(fake.folders.size, 0)
})
