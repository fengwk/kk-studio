'use strict'

/**
 * Document-state predicates for one code-server window.
 *
 * Each predicate answers a question the bridge must settle before it may claim the window is
 * clean, back up ordinary text, or close a view. They read only the public VS Code document and
 * tab APIs, so no renderer-internal state is assumed.
 */

const nodePath = require('node:path')

const hasDirtyDocuments = (vscode) => {
  const documents = vscode.workspace.textDocuments || []
  return documents.some((document) => document.isDirty)
}

/**
 * Custom editors, webviews and notebooks can hold unsaved renderer-side state that the ordinary
 * text-document API cannot inspect. Their presence makes "clean" unprovable, so a normal close
 * must never claim it.
 */
const hasUnknownEditorState = (vscode) => {
  const groups = vscode.window && vscode.window.tabGroups && vscode.window.tabGroups.all
  if (!Array.isArray(groups)) {
    return false
  }
  const opaqueInputs = [
    vscode.TabInputCustom,
    vscode.TabInputWebview,
    vscode.TabInputNotebook,
    vscode.TabInputNotebookDiff,
  ].filter((constructor) => typeof constructor === 'function')
  if (opaqueInputs.length === 0) {
    return false
  }
  return groups.some(
    (group) =>
      Array.isArray(group && group.tabs) &&
      group.tabs.some((tab) => {
        const input = tab && tab.input
        return input !== undefined && opaqueInputs.some((constructor) => input instanceof constructor)
      }),
  )
}

const canProveClean = (vscode) => !hasDirtyDocuments(vscode) && !hasUnknownEditorState(vscode)

// Workbench tabs and extension-host documents are separate observations. A dirty text tab
// missing from the dirty document list (e.g. a lagging mirror) is not proof of a clean editor.
const hasUnobservableSnapshotState = (vscode, dirty) => {
  const groups = vscode.window?.tabGroups?.all
  if (!Array.isArray(groups) || !Array.isArray(vscode.workspace.textDocuments)) {
    return true
  }
  const dirtyUris = new Set(dirty.map((doc) => doc.uri?.toString()))
  const is = (input, type) => typeof type === 'function' && input instanceof type
  return groups.some((group) => !Array.isArray(group?.tabs) || group.tabs.some((tab) => {
    const input = tab?.input
    if (is(input, vscode.TabInputText)) {
      return tab.isDirty === true && !dirtyUris.has(input.uri.toString())
    }
    if (is(input, vscode.TabInputTextDiff)) {
      return tab.isDirty === true && !dirtyUris.has(input.modified.toString())
    }
    return !is(input, vscode.TabInputTerminal) || tab.isDirty === true
  }))
}

// A recovery entry is keyed by an absolute on-disk path, so a dirty buffer is capturable only when
// the restore path would accept exactly the same identity. Renderer-only buffers and custom
// editors are not observable and are never claimed clean.
const PATH_CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/

const isCapturableFileDocument = (document) => {
  // A document the workbench already closed is only a lagging mirror; its text is not live work the
  // recovery record should preserve, but it still keeps the snapshot unproven.
  if (document.isClosed === true) {
    return false
  }
  const uri = document.uri
  return Boolean(
    uri &&
      uri.scheme === 'file' &&
      typeof uri.fsPath === 'string' &&
      uri.fsPath.length > 0 &&
      nodePath.isAbsolute(uri.fsPath) &&
      !PATH_CONTROL_CHARACTERS.test(uri.fsPath),
  )
}

const dirtyDocuments = (vscode) => (vscode.workspace.textDocuments || []).filter((document) => document?.isDirty)

/**
 * True when a dirty document is not represented by any of the tabs the workbench just closed.
 *
 * The extension host mirrors workbench dirty state asynchronously, so a tab the workbench already
 * closed (and whose save/discard decision it already committed) can still be reported as dirty
 * for a few milliseconds. Only documents that never had a closed tab can still hold unsaved work
 * this close did not address.
 */
const hasHiddenDirtyDocuments = (vscode, closedTabs) => {
  const closedUris = new Set()
  for (const tab of closedTabs) {
    const input = tab && tab.input
    if (input === undefined || input === null) {
      continue
    }
    if (input instanceof vscode.TabInputText) {
      closedUris.add(input.uri.toString())
    } else if (input instanceof vscode.TabInputTextDiff) {
      closedUris.add(input.original.toString())
      closedUris.add(input.modified.toString())
    }
  }
  return (vscode.workspace.textDocuments || []).some((document) => {
    if (document.isDirty !== true) {
      return false
    }
    const uri = document.uri
    return !(uri !== undefined && uri !== null && closedUris.has(uri.toString()))
  })
}

module.exports = {
  hasUnknownEditorState,
  canProveClean,
  hasUnobservableSnapshotState,
  isCapturableFileDocument,
  dirtyDocuments,
  hasHiddenDirtyDocuments,
}
