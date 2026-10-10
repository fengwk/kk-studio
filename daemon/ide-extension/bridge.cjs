'use strict'

/**
 * Loopback bridge host for one code-server window.
 *
 * The daemon exposes a private loopback HTTP endpoint guarded by a random per-service
 * secret delivered through the code-server process environment. This host registers the
 * exact assigned workspace file, receives commands over a long-lived NDJSON stream and
 * uses only public VS Code APIs: `workspace.updateWorkspaceFolders`,
 * `window.createTerminal`, `Terminal.show` and `Terminal.processId`. The managed
 * `.code-workspace` file is persisted by the daemon, which owns the durable workspace.
 *
 * No `executeCommand`, private SDK or DOM workbench hacks are used.
 */

const DEFAULT_RECONNECT_MILLIS = 500

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

  const workspacePath = () => {
    const file = vscode.workspace.workspaceFile
    if (file) {
      return file.fsPath || file.path
    }
    const folders = vscode.workspace.workspaceFolders
    if (folders && folders.length > 0) {
      return folders[0].uri.fsPath
    }
    return undefined
  }

  // Resolves with the HTTP status code, or 0 when the bridge is unreachable.
  const post = (path, payload) => {
    return new Promise((resolve) => {
      if (!url || !token) {
        resolve(0)
        return
      }
      const body = Buffer.from(JSON.stringify(payload))
      const request = http.request(
        `${url}${path}?token=${encodeURIComponent(token)}`,
        {
          method: 'POST',
          headers: { 'content-type': 'application/json', 'content-length': body.length },
        },
        (response) => {
          response.resume()
          response.on('end', () => resolve(response.statusCode || 0))
          response.on('error', () => resolve(0))
        },
      )
      request.on('error', () => resolve(0))
      request.end(body)
    })
  }

  const reply = (id, result) => post('/bridge/result', Object.assign({ id }, result))

  const disposeTerminal = () => {
    if (terminal) {
      try {
        terminal.dispose()
      } catch {
        // terminal already gone
      }
      terminal = null
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
    // The daemon owns the durable `.code-workspace` file; switching the live window through
    // the public API may restart the extension host, which is why the daemon treats a
    // delivered change as success rather than waiting for a reply that may never arrive.
    const updated = vscode.workspace.updateWorkspaceFolders(0, folders.length, { uri })
    if (updated === false) {
      await reply(message.id, { ok: false, message: 'directory change was rejected' })
      return
    }
    await reply(message.id, { ok: true })
  }

  const focusTerminal = async (message) => {
    const terminals = vscode.window.terminals || []
    if (!terminal || terminals.indexOf(terminal) === -1) {
      const folders = vscode.workspace.workspaceFolders || []
      const cwd = folders.length > 0 ? folders[0].uri.fsPath : undefined
      terminal = vscode.window.createTerminal({ name: 'kk-studio', cwd })
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
      disposeTerminal()
      await reply(message.id, { ok: false, message: 'terminal supervision is unavailable' })
      return
    }
    const status = await post('/bridge/pty', { workspacePath: path, pid })
    if (status >= 400 || status === 0) {
      disposeTerminal()
      await reply(message.id, { ok: false, message: 'terminal supervision failed' })
      return
    }
    await reply(message.id, { ok: true })
  }

  const closeView = async (message) => {
    disposeTerminal()
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

  const onData = (chunk) => {
    buffer += chunk
    let index = buffer.indexOf('\n')
    while (index >= 0) {
      const line = buffer.slice(0, index)
      buffer = buffer.slice(index + 1)
      index = buffer.indexOf('\n')
      if (line.trim().length === 0) {
        continue
      }
      let message
      try {
        message = JSON.parse(line)
      } catch {
        continue
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
    const target = `${url}/bridge/stream?token=${encodeURIComponent(token)}&workspacePath=${encodeURIComponent(path)}`
    streamRequest = http.get(target, (response) => {
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
      if (terminal) {
        try {
          terminal.dispose()
        } catch {
          // already closed
        }
        terminal = null
      }
    },
  }
}

module.exports = { createBridgeHost }
