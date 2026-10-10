'use strict'

/**
 * Loopback bridge host for one code-server window.
 *
 * The daemon exposes a private loopback HTTP endpoint. This host authenticates with a random
 * per-service secret delivered through the code-server process environment (never the browser)
 * using an `Authorization: Bearer` header, registers the exact assigned workspace file plus its
 * live folders, receives commands over a long-lived NDJSON stream and uses only public VS Code
 * APIs: `workspace.updateWorkspaceFolders`, `window.createTerminal`, `Terminal.show` and
 * `Terminal.processId`. The managed `.code-workspace` file is persisted by the daemon.
 *
 * No `executeCommand`, private SDK or DOM workbench hacks are used.
 */

const DEFAULT_RECONNECT_MILLIS = 500
/** A command line is a small JSON object; anything larger is a protocol violation. */
const MAX_LINE_BYTES = 16 * 1024
/** Bounds how long a bridge POST may occupy the extension host. */
const POST_TIMEOUT_MILLIS = 5_000

function createBridgeHost(vscode, options) {
  const config = options || {}
  const url = config.url || process.env.KK_STUDIO_IDE_BRIDGE_URL
  const token = config.token || process.env.KK_STUDIO_IDE_BRIDGE_TOKEN
  const http = config.http || require('node:http')
  const reconnectMillis = config.reconnectMillis || DEFAULT_RECONNECT_MILLIS

  let disposed = false
  let streamRequest = null
  let reconnectTimer = null
  let buffer = ''
  let terminal = null
  const terminals = []
  const pendingPosts = new Set()

  const folderPaths = () => {
    const folders = vscode.workspace.workspaceFolders || []
    return folders.map((folder) => folder.uri.fsPath).filter((path) => typeof path === 'string' && path.length > 0)
  }

  const workspacePath = () => {
    const file = vscode.workspace.workspaceFile
    if (file) {
      return file.fsPath || file.path
    }
    return folderPaths()[0]
  }

  // Resolves with the HTTP status code, or 0 when the bridge is unreachable or times out.
  const post = (path, payload) => {
    return new Promise((resolve) => {
      if (!url || !token) {
        resolve(0)
        return
      }
      const body = Buffer.from(JSON.stringify(payload))
      let settled = false
      let timer = null
      const finish = (status) => {
        if (settled) {
          return
        }
        settled = true
        if (timer) {
          clearTimeout(timer)
        }
        pendingPosts.delete(request)
        resolve(status)
      }
      const request = http.request(
        `${url}${path}`,
        {
          method: 'POST',
          headers: {
            'content-type': 'application/json',
            'content-length': body.length,
            authorization: `Bearer ${token}`,
          },
        },
        (response) => {
          response.resume()
          response.on('end', () => finish(response.statusCode || 0))
          response.on('error', () => finish(0))
        },
      )
      request.on('error', () => finish(0))
      pendingPosts.add(request)
      timer = setTimeout(() => {
        try {
          request.destroy()
        } catch {
          // already destroyed
        }
        finish(0)
      }, POST_TIMEOUT_MILLIS)
      if (timer.unref) {
        timer.unref()
      }
      request.end(body)
    })
  }

  const reply = (id, result) => post('/bridge/result', Object.assign({ id }, result))

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

  const hasDirtyDocuments = () => {
    const documents = vscode.workspace.textDocuments || []
    return documents.some((document) => document.isDirty)
  }

  const changeDirectory = async (message) => {
    if (hasDirtyDocuments()) {
      await reply(message.id, {
        ok: false,
        code: 'DIRTY',
        message: 'save or discard unsaved changes before switching directory',
      })
      return
    }
    const folders = vscode.workspace.workspaceFolders || []
    const uri = vscode.Uri.file(message.directory)
    // The daemon owns the durable `.code-workspace` file. `updateWorkspaceFolders` returning
    // true is the public API's acceptance of the change; a folder change that reloads the
    // extension host never replies here, and the reconnected window confirms instead.
    const updated = vscode.workspace.updateWorkspaceFolders(0, folders.length, { uri })
    if (updated === false) {
      await reply(message.id, { ok: false, message: 'directory change was rejected' })
      return
    }
    await reply(message.id, { ok: true, directory: message.directory })
  }

  const focusTerminal = async (message) => {
    const live = vscode.window.terminals || []
    if (!terminal || live.indexOf(terminal) === -1) {
      const folders = vscode.workspace.workspaceFolders || []
      const cwd = folders.length > 0 ? folders[0].uri.fsPath : undefined
      terminal = vscode.window.createTerminal({ name: 'kk-studio', cwd })
      terminals.push(terminal)
    }
    terminal.show()
    let pid
    try {
      pid = await terminal.processId
    } catch {
      pid = undefined
    }
    const path = workspacePath()
    // A shell that cannot be handed to the daemon for supervision must not be left running.
    if (!(typeof pid === 'number' && pid > 0) || !path) {
      disposeTerminals()
      await reply(message.id, { ok: false, message: 'terminal supervision is unavailable' })
      return
    }
    const status = await post('/bridge/pty', { workspacePath: path, pid })
    if (status >= 400 || status === 0) {
      disposeTerminals()
      await reply(message.id, { ok: false, message: 'terminal supervision failed' })
      return
    }
    await reply(message.id, { ok: true })
  }

  const closeView = async (message) => {
    // Never tear a window down with unsaved work; the daemon keeps the workspace on refusal.
    if (hasDirtyDocuments()) {
      await reply(message.id, {
        ok: false,
        code: 'DIRTY',
        message: 'save or discard unsaved changes before closing the view',
      })
      return
    }
    disposeTerminals()
    await reply(message.id, { ok: true })
  }

  const handleCommand = async (message) => {
    try {
      if (message.op === 'changeDirectory') {
        await changeDirectory(message)
      } else if (message.op === 'focusTerminal') {
        await focusTerminal(message)
      } else if (message.op === 'closeView') {
        await closeView(message)
      } else {
        await reply(message.id, { ok: false, message: 'unsupported command' })
      }
    } catch {
      await reply(message.id, { ok: false, message: 'command failed' })
    }
  }

  const failStream = () => {
    buffer = ''
    if (streamRequest) {
      try {
        streamRequest.destroy()
      } catch {
        // already destroyed
      }
      streamRequest = null
    }
    scheduleReconnect()
  }

  const onData = (chunk) => {
    buffer += chunk
    // An unterminated line beyond the bound is a protocol violation; never carry it forward.
    if (buffer.indexOf('\n') === -1 && buffer.length > MAX_LINE_BYTES) {
      failStream()
      return
    }
    let index = buffer.indexOf('\n')
    while (index >= 0) {
      const line = buffer.slice(0, index)
      buffer = buffer.slice(index + 1)
      index = buffer.indexOf('\n')
      if (line.trim().length === 0) {
        continue
      }
      if (line.length > MAX_LINE_BYTES) {
        failStream()
        return
      }
      let message
      try {
        message = JSON.parse(line)
      } catch {
        failStream()
        return
      }
      if (message && message.type === 'command' && typeof message.id === 'string') {
        void handleCommand(message)
      }
    }
  }

  const scheduleReconnect = () => {
    if (disposed || reconnectTimer) {
      return
    }
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      connect()
    }, reconnectMillis)
    if (reconnectTimer.unref) {
      reconnectTimer.unref()
    }
  }

  const connect = () => {
    if (disposed || !url || !token) {
      return
    }
    const path = workspacePath()
    if (!path) {
      return
    }
    // Drop any partial line from a previous connection.
    buffer = ''
    const params = new URLSearchParams()
    params.set('workspacePath', path)
    for (const folder of folderPaths()) {
      params.append('folder', folder)
    }
    const target = `${url}/bridge/stream?${params.toString()}`
    streamRequest = http.get(target, { headers: { authorization: `Bearer ${token}` } }, (response) => {
      if (response.statusCode !== 200) {
        response.resume()
        scheduleReconnect()
        return
      }
      response.setEncoding('utf8')
      response.on('data', onData)
      response.on('end', scheduleReconnect)
      response.on('error', scheduleReconnect)
    })
    streamRequest.on('error', scheduleReconnect)
  }

  return {
    start() {
      connect()
    },
    dispose() {
      disposed = true
      if (reconnectTimer) {
        clearTimeout(reconnectTimer)
        reconnectTimer = null
      }
      if (streamRequest) {
        try {
          streamRequest.destroy()
        } catch {
          // already closed
        }
        streamRequest = null
      }
      for (const request of pendingPosts) {
        try {
          request.destroy()
        } catch {
          // already closed
        }
      }
      pendingPosts.clear()
      disposeTerminals()
    },
  }
}

module.exports = { createBridgeHost }
