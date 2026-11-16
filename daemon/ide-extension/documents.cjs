'use strict'

/**
 * Document-state predicates for one code-server window.
 *
 * Each predicate answers a question the bridge must settle before it may claim the window is
 * clean, back up ordinary text, or close a view. They read only the public VS Code document and
 * tab APIs, so no renderer-internal state is assumed.
 */

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

// Only ordinary on-disk text documents visible through the public textDocument API.
// Renderer-only buffers and custom editors are not observable and are never claimed clean.
const ordinaryDirtyDocuments = (vscode) => {
  const documents = vscode.workspace.textDocuments || []
  return documents.filter((document) => {
    if (!document || document.isDirty !== true || document.isClosed === true) {
      return false
    }
    const uri = document.uri
    return Boolean(uri && uri.scheme === 'file' && typeof uri.fsPath === 'string' && uri.fsPath.length > 0)
  })
}

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
  ordinaryDirtyDocuments,
  hasHiddenDirtyDocuments,
}
