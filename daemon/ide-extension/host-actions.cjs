'use strict'

/**
 * Command handlers for one bridge host.
 *
 * The host owns the control channel and the terminal list; this module owns what each daemon
 * command does to the window. Every handler replies exactly once unless the host has already been
 * disposed or retired, in which case the reply is abandoned because no channel remains.
 */

const nodePath = require('node:path')
const documents = require('./documents.cjs')

const MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024
const MAX_SNAPSHOT_DOCUMENTS = 256
/** Matches the daemon's per-path limit; a longer path would reject the whole reply. */
const MAX_SNAPSHOT_PATH_CHARS = 4096

function createHostActions(host) {
  const { vscode, post, reply, retire, isInactive, folderPaths, workspacePath, viewId, bindingEpoch, hostId } = host

  let terminal = null
  const terminals = []

  const disposeTerminals = () => {
    terminal = null
    while (terminals.length > 0) {
      const item = terminals.pop()
      try {
        item.dispose()
      } catch {
        // terminal already gone
      }
    }
  }

  const inspectView = async (message) => {
    await reply(message.id, { ok: true, clean: documents.canProveClean(vscode) })
  }

  const refreshExplorer = async (message) => {
    await vscode.commands.executeCommand('kk-studio.refreshExplorer')
    await reply(message.id, { ok: true })
  }

  const snapshot = async (message) => {
    const maxBytes =
      typeof message.maxBytes === 'number' && Number.isSafeInteger(message.maxBytes) && message.maxBytes > 0
        ? Math.min(message.maxBytes, MAX_SNAPSHOT_BYTES)
        : MAX_SNAPSHOT_BYTES
    const collected = []
    // Bound the encoded documents sent to the bridge; the daemon checks the full on-disk quota.
    let bytes = 0
    let encodedBytes = 0
    // Every dirty state this backup cannot represent is an omission, never a complete backup: an
    // editor whose content this extension cannot observe, and any dirty buffer whose path cannot
    // hold recovery text (untitled, virtual, closed, or a non-absolute/over-long path).
    const dirty = documents.dirtyDocuments(vscode)
    let omitted = documents.hasUnobservableSnapshotState(vscode, dirty) ? 1 : 0
    for (const document of dirty) {
      if (!documents.isCapturableFileDocument(document) || collected.length >= MAX_SNAPSHOT_DOCUMENTS) {
        omitted += 1
        continue
      }
      // JS string length counts UTF-16 code units, the same unit the daemon bounds paths with.
      const documentPath = document.uri.fsPath
      if (documentPath.length > MAX_SNAPSHOT_PATH_CHARS) {
        omitted += 1
        continue
      }
      let text
      try {
        text = document.getText()
      } catch {
        omitted += 1
        continue
      }
      // The encoded form includes the path and JSON escaping, so a pathological text cannot expand
      // past the quota after this check.
      const encoded = Buffer.byteLength(JSON.stringify({ path: documentPath, text }), 'utf8')
      if (encodedBytes + encoded > maxBytes) {
        omitted += 1
        continue
      }
      collected.push({ path: documentPath, text })
      bytes += Buffer.byteLength(text, 'utf8')
      encodedBytes += encoded
    }
    // Only an explicit, complete empty snapshot can prove there are no drafts.
    await reply(message.id, { ok: true, documents: collected, bytes, omitted, complete: omitted === 0 })
  }

  const restoreEntry = async (entry, editing) => {
    const { workspaceEdit, range, position } = editing
    if (
      !entry ||
      typeof entry.path !== 'string' ||
      typeof entry.text !== 'string' ||
      entry.path.length === 0 ||
      !nodePath.isAbsolute(entry.path) ||
      /[\u0000-\u001f\u007f]/.test(entry.path)
    ) {
      return 'skipped'
    }
    const uri = vscode.Uri.file(entry.path)
    const document = await vscode.workspace.openTextDocument(uri)
    if (isInactive()) {
      return 'abandoned'
    }
    // Never overwrite unsaved work that already exists in the window.
    if (document.isDirty === true) {
      return 'skipped'
    }
    const edit = new workspaceEdit()
    const end = document.positionAt(document.getText().length)
    edit.replace(uri, new range(new position(0, 0), end), entry.text)
    const applied = await vscode.workspace.applyEdit(edit)
    if (isInactive()) {
      return 'abandoned'
    }
    if (applied === false) {
      return 'skipped'
    }
    if (typeof vscode.window.showTextDocument === 'function') {
      await vscode.window.showTextDocument(document, { preview: false })
    }
    if (isInactive()) {
      return 'abandoned'
    }
    return 'restored'
  }

  const restore = async (message) => {
    const entries = Array.isArray(message.documents) ? message.documents : []
    if (entries.length > MAX_SNAPSHOT_DOCUMENTS) {
      await reply(message.id, { ok: false, code: 'UNAVAILABLE', message: 'too many recovery documents' })
      return
    }
    const editing = { workspaceEdit: vscode.WorkspaceEdit, range: vscode.Range, position: vscode.Position }
    if (
      !editing.workspaceEdit ||
      !editing.range ||
      !editing.position ||
      typeof vscode.workspace.openTextDocument !== 'function'
    ) {
      await reply(message.id, { ok: false, code: 'UNAVAILABLE', message: 'text restore is unavailable' })
      return
    }
    let restored = 0
    let skipped = 0
    for (const entry of entries) {
      if (isInactive()) {
        return
      }
      let outcome
      try {
        outcome = await restoreEntry(entry, editing)
      } catch {
        outcome = 'skipped'
      }
      if (outcome === 'abandoned') {
        return
      }
      if (outcome === 'restored') {
        restored += 1
      } else {
        skipped += 1
      }
    }
    await reply(message.id, { ok: true, restored, skipped })
  }

  const sameDirectory = (actual, expected) => {
    const normalize = (path) => {
      const resolved = nodePath.resolve(path)
      return process.platform === 'win32' ? resolved.toLowerCase() : resolved
    }
    return actual.length === 1 && normalize(actual[0]) === normalize(expected)
  }

  const changeDirectory = async (message) => {
    if (!documents.canProveClean(vscode)) {
      await reply(message.id, {
        ok: false,
        code: 'DIRTY',
        message: 'save or discard unsaved changes before switching directory',
      })
      return
    }
    const folders = vscode.workspace.workspaceFolders || []
    const uri = vscode.Uri.file(message.directory)
    if (sameDirectory(folderPaths(), message.directory)) {
      await reply(message.id, { ok: true, directory: folderPaths()[0] })
      return
    }
    // Replacing the first folder restarts extension hosts; only the new host can confirm it.
    const updated = vscode.workspace.updateWorkspaceFolders(0, folders.length, { uri })
    if (updated === false) {
      await reply(message.id, { ok: false, message: 'directory change was rejected' })
      return
    }
    retire()
  }

  const ensureTerminal = () => {
    const live = vscode.window.terminals || []
    if (terminal && live.indexOf(terminal) !== -1) {
      return terminal
    }
    const folders = vscode.workspace.workspaceFolders || []
    const cwd = folders.length > 0 ? folders[0].uri.fsPath : undefined
    terminal = vscode.window.createTerminal({ name: 'kk-studio', cwd })
    terminals.push(terminal)
    return terminal
  }

  const focusTerminal = async (message) => {
    const current = ensureTerminal()
    current.show()
    let pid
    try {
      pid = await current.processId
    } catch {
      pid = undefined
    }
    if (isInactive()) {
      return
    }
    const path = workspacePath()
    // A shell that cannot be handed to the daemon for supervision must not be left running.
    if (!(typeof pid === 'number' && pid > 0) || !path) {
      disposeTerminals()
      await reply(message.id, { ok: false, message: 'terminal supervision is unavailable' })
      return
    }
    const status = await post('/bridge/pty', { viewId, bindingEpoch, hostId, pid })
    if (isInactive()) {
      return
    }
    if (status >= 400 || status === 0) {
      disposeTerminals()
      await reply(message.id, { ok: false, message: 'terminal supervision failed' })
      return
    }
    await reply(message.id, { ok: true })
  }

  const attachmentDocuments = new Map()
  const attachmentProvider = {
    provideTextDocumentContent(uri) {
      return attachmentDocuments.get(uri.toString())
    },
  }
  const attachmentProviderRegistration = vscode.workspace.registerTextDocumentContentProvider(
    'kk-studio-attachment', attachmentProvider,
  )
  const releaseAttachment = (uri) => {
    if (uri && uri.scheme === 'kk-studio-attachment') {
      attachmentDocuments.delete(uri.toString())
    }
  }
  const attachmentCloseSubscription = vscode.workspace.onDidCloseTextDocument((document) => releaseAttachment(document.uri))

  const openAttachment = async (message) => {
    const id = message.attachmentId
    if (typeof id !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id) ||
      typeof message.attachmentText !== 'string' || Buffer.byteLength(message.attachmentText, 'utf8') > 64 * 1024 ||
      message.attachmentText.indexOf('\0') !== -1 || attachmentDocuments.size >= 32) {
      await reply(message.id, { ok: false, code: 'UNAVAILABLE', message: 'attachment could not be opened' })
      return
    }
    const uri = vscode.Uri.parse(`kk-studio-attachment:/attachment-${id}.txt`)
    const key = uri.toString()
    if (attachmentDocuments.has(key)) {
      await reply(message.id, { ok: false, code: 'UNAVAILABLE', message: 'attachment could not be opened' })
      return
    }
    attachmentDocuments.set(key, message.attachmentText)
    try {
      const document = await vscode.workspace.openTextDocument(uri)
      if (isInactive()) {
        releaseAttachment(uri)
        return
      }
      await vscode.window.showTextDocument(document, { preview: false })
      if (isInactive()) {
        releaseAttachment(uri)
        return
      }
      await reply(message.id, { ok: true })
    } catch {
      releaseAttachment(uri)
      await reply(message.id, { ok: false, code: 'UNAVAILABLE', message: 'attachment could not be opened' })
    }
  }

  const disposeAttachments = () => {
    attachmentCloseSubscription.dispose()
    attachmentProviderRegistration.dispose()
    attachmentDocuments.clear()
  }

  const collapseMobile = async (message) => {
    await vscode.commands.executeCommand('workbench.action.closeSidebar')
    await vscode.commands.executeCommand('workbench.action.closePanel')
    await reply(message.id, { ok: true })
  }

  const closeView = async (message) => {
    // Explicit discard/expiry follows the daemon's backup attempt and cannot await an interactive window.
    if (message.discard !== true) {
      // Let the workbench handle dirty and custom editors, including save, discard and cancel.
      const tabs = vscode.window.tabGroups.all.flatMap((group) => group.tabs)
      const closed = await vscode.window.tabGroups.close(tabs)
      // A hidden dirty document or a newly opened tab must not be destroyed with the view.
      if (
        !closed ||
        documents.hasHiddenDirtyDocuments(vscode, tabs) ||
        vscode.window.tabGroups.all.some((group) => group.tabs.length > 0)
      ) {
        await reply(message.id, {
          ok: false,
          code: 'CANCELLED',
          message: 'closing the view was cancelled or editors remain open',
        })
        return
      }
    }
    // A normal result may arrive after the daemon lost its controlling connection.
    // The managed view owns terminals and disposes them only once close is committed.
    if (message.discard === true) {
      disposeTerminals()
    }
    await reply(message.id, { ok: true })
  }

  const handlers = {
    refreshExplorer,
    changeDirectory,
    focusTerminal,
    openAttachment,
    inspect: inspectView,
    closeView,
    collapseMobile,
    snapshot,
    restore,
  }

  const execute = async (message) => {
    const handler = handlers[message.op]
    if (!handler) {
      await reply(message.id, { ok: false, message: 'unsupported command' })
      return
    }
    await handler(message)
  }

  return { execute, disposeTerminals, disposeAttachments }
}

module.exports = { createHostActions }
