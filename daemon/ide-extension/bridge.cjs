'use strict'

/**
 * Loopback bridge host for one code-server window.
 *
 * The daemon exposes a private loopback HTTP endpoint. This host authenticates with a random
 * per-service secret delivered through the code-server process environment (never the browser)
 * using an `Authorization: Bearer` header, registers the exact assigned workspace file plus its
 * live folders, receives commands over a long-lived NDJSON stream and uses only public VS Code
 * APIs for workspace changes, opened-file watching, native tab closing and terminal supervision.
 * The managed `.code-workspace` file is persisted by the daemon.
 *
 * The mobile layout uses public workbench commands; no private SDK or DOM hacks are used.
 */

const { INVALID, LineFramer } = require('./framing.cjs')
const { createHostActions } = require('./host-actions.cjs')

const DEFAULT_RECONNECT_MILLIS = 500
/**
 * A command line is a bounded JSON object. Snapshot/restore carry ordinary dirty text,
 * so the line budget matches the daemon's fixed recovery quota.
 */
const MAX_LINE_BYTES = 8 * 1024 * 1024
/** Bounds how long a bridge POST may occupy the extension host. */
const POST_TIMEOUT_MILLIS = 5_000

function createBridgeHost(vscode, options) {
  const config = options || {}
  const url = config.url || process.env.KK_STUDIO_IDE_BRIDGE_URL
  const token = config.token || process.env.KK_STUDIO_IDE_BRIDGE_TOKEN
  const viewId = config.viewId || process.env.KK_STUDIO_IDE_VIEW_ID
  const bindingEpoch = config.bindingEpoch || process.env.KK_STUDIO_IDE_BINDING_EPOCH
  const hostId = require('node:crypto').randomUUID()
  const epochText = String(bindingEpoch)
  const validIdentity =
    typeof viewId === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(viewId) &&
    /^[1-9][0-9]*$/.test(epochText) &&
    BigInt(epochText) <= 9223372036854775807n
  const http = config.http || require('node:http')
  const reconnectMillis = config.reconnectMillis || DEFAULT_RECONNECT_MILLIS

  const framer = new LineFramer(MAX_LINE_BYTES)
  let disposed = false
  let retired = false
  let streamRequest = null
  let reconnectTimer = null
  const pendingPosts = new Set()

  // Watching an exact file URI with a non-recursive pattern lets VS Code's file service deliver
  // changes to the editor, including its native dirty-file conflict handling. It also works for
  // files outside the workspace and avoids recursively watching large workspace roots.
  const fileWatchers = new Map()
  const watchDocument = (document) => {
    const uri = document.uri
    if (document.isClosed || !uri || uri.scheme !== 'file') {
      return
    }
    const key = uri.toString()
    if (!fileWatchers.has(key)) {
      fileWatchers.set(key, vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(uri, '*')))
    }
  }
  const unwatchDocument = (document) => {
    const key = document.uri.toString()
    const watcher = fileWatchers.get(key)
    if (watcher) {
      watcher.dispose()
      fileWatchers.delete(key)
    }
  }
  const openSubscription = vscode.workspace.onDidOpenTextDocument(watchDocument)
  const closeSubscription = vscode.workspace.onDidCloseTextDocument(unwatchDocument)
  for (const document of vscode.workspace.textDocuments) {
    watchDocument(document)
  }

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

  const isInactive = () => disposed || retired
  const canReachDaemon = () => !isInactive() && Boolean(url) && Boolean(token) && validIdentity

  // Resolves with the HTTP status code, or 0 when the bridge is unreachable or times out.
  const post = (path, payload) => {
    return new Promise((resolve) => {
      if (!canReachDaemon()) {
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

  const reply = (id, result) =>
    post('/bridge/result', Object.assign({ id, viewId, bindingEpoch: epochText, hostId }, result))

  const clearReconnectTimer = () => {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
  }

  const destroyStreamRequest = () => {
    if (!streamRequest) {
      return
    }
    try {
      streamRequest.destroy()
    } catch {
      // already destroyed
    }
    streamRequest = null
  }

  const destroyPendingPosts = () => {
    for (const request of pendingPosts) {
      try {
        request.destroy()
      } catch {
        // already closed
      }
    }
    pendingPosts.clear()
  }

  // Retire only the control host; code-server retains ownership of its terminals.
  const retire = () => {
    if (retired) {
      return
    }
    retired = true
    folderSubscription.dispose()
    framer.reset()
    clearReconnectTimer()
    destroyStreamRequest()
    destroyPendingPosts()
    actions.disposeAttachments()
  }

  const actions = createHostActions({
    vscode,
    post,
    reply,
    retire,
    isInactive,
    folderPaths,
    workspacePath,
    viewId,
    bindingEpoch,
    hostId,
  })

  const folderSubscription = vscode.workspace.onDidChangeWorkspaceFolders(() => {
    if (!isInactive() && !actions.onWorkspaceFoldersChanged()) {
      // Outside a managed change, refresh the registration to describe the live folders.
      failStream()
    }
  })

  const handleCommand = async (message) => {
    if (isInactive()) {
      return
    }
    try {
      await actions.execute(message)
    } catch {
      await reply(message.id, { ok: false, message: 'command failed' })
    }
  }

  const failStream = () => {
    framer.reset()
    destroyStreamRequest()
    scheduleReconnect()
  }

  const onData = (chunk) => {
    const outcome = framer.push(chunk, (message) => {
      if (message && message.type === 'retired') {
        retire()
        return false
      }
      if (message && message.type === 'command' && typeof message.id === 'string') {
        void handleCommand(message)
      }
      return true
    })
    if (outcome === INVALID) {
      failStream()
    }
  }

  const scheduleReconnect = () => {
    if (isInactive() || reconnectTimer) {
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

  const streamTarget = () => {
    const params = new URLSearchParams()
    params.set('viewId', viewId)
    params.set('bindingEpoch', epochText)
    params.set('hostId', hostId)
    for (const folder of folderPaths()) {
      params.append('folder', folder)
    }
    return `${url}/bridge/stream?${params.toString()}`
  }

  const onStreamResponse = (request, response) => {
    if (streamRequest !== request || isInactive()) {
      response.resume()
      return
    }
    if (response.statusCode !== 200) {
      response.resume()
      if (response.statusCode === 410) {
        // A retired host stops only its control channel; the managed process owns terminals.
        retire()
      } else {
        scheduleReconnect()
      }
      return
    }
    response.setEncoding('utf8')
    response.on('data', (chunk) => {
      if (streamRequest === request) {
        onData(chunk)
      }
    })
    const disconnected = () => {
      if (streamRequest === request) {
        streamRequest = null
        scheduleReconnect()
      }
    }
    response.on('end', disconnected)
    response.on('error', disconnected)
  }

  const connect = () => {
    if (!canReachDaemon()) {
      return
    }
    // Drop any partial line from a previous connection.
    framer.reset()
    const request = http.get(
      streamTarget(),
      { headers: { authorization: `Bearer ${token}` } },
      (response) => onStreamResponse(request, response),
    )
    streamRequest = request
    request.on('error', () => {
      if (streamRequest === request) {
        streamRequest = null
        scheduleReconnect()
      }
    })
  }

  return {
    start() {
      connect()
    },
    dispose() {
      disposed = true
      if (!retired) {
        folderSubscription.dispose()
      }
      openSubscription.dispose()
      closeSubscription.dispose()
      actions.disposeAttachments()
      for (const watcher of fileWatchers.values()) {
        watcher.dispose()
      }
      fileWatchers.clear()
      clearReconnectTimer()
      destroyStreamRequest()
      destroyPendingPosts()
      // The managed code-server scope owns terminals; retirement must not tear them down,
      // including when VS Code subsequently deactivates this superseded extension host.
      if (!retired) {
        actions.disposeTerminals()
      }
    },
  }
}

module.exports = { createBridgeHost }
