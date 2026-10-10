import fs from 'node:fs'
import { spawn } from 'node:child_process'

const args = process.argv.slice(2)
const mode = args[0] || 'normal'
const transcriptPath = args[1] || '/dev/null'
const childPidFile = args[2] || null

function record(entry) {
  try {
    fs.appendFileSync(transcriptPath, JSON.stringify(entry) + '\n', 'utf8')
  } catch {}
}

record({
  event: 'start',
  mode,
  pid: process.pid,
  cwd: process.cwd(),
})

if (mode === 'dead') {
  process.exit(7)
}

if (mode === 'stubborn') {
  process.on('SIGTERM', () => {
    // Ignore SIGTERM
  })
  if (childPidFile) {
    const child = spawn('/bin/sh', ['-c', 'exec sleep 600'])
    record({ event: 'child', pid: child.pid })
    fs.writeFileSync(childPidFile, String(child.pid), 'utf8')
  }
}

if (mode === 'garbage') {
  process.stdout.write('stray server banner\r\n\r\n')
}

let outboundId = 900

function writeMessage(message) {
  const payload = Buffer.from(JSON.stringify(message), 'utf8')
  record({ event: 'send', message })
  process.stdout.write(`Content-Length: ${payload.length}\r\n\r\n`)
  process.stdout.write(payload)
}

function sendResult(id, result) {
  writeMessage({
    jsonrpc: '2.0',
    id,
    result,
  })
}

function sendError(id, code, message) {
  writeMessage({
    jsonrpc: '2.0',
    id,
    error: { code, message },
  })
}

function sendServerRequest(method, params) {
  const id = outboundId++
  writeMessage({
    jsonrpc: '2.0',
    id,
    method,
    params,
  })
}

function position(line, character) {
  return { line, character }
}

function range(line, character, endCharacter) {
  return {
    start: position(line, character),
    end: position(line, endCharacter),
  }
}

function symbol(name, kind, uri, line) {
  return {
    name,
    kind,
    location: {
      uri,
      range: range(line, 0, 1),
    },
  }
}

function decompiledSource() {
  if (mode === 'large-decompile') {
    return '// decompiled line\n'.repeat(3000)
  }
  if (mode === 'empty-decompile') {
    return ''
  }
  return 'class Decompiled {}\n'
}

function initializeResult() {
  if (mode === 'no-capabilities') {
    return {}
  }
  const capabilities = {
    positionEncoding: mode === 'utf8' ? 'utf-8' : mode === 'utf32' ? 'utf-32' : 'utf-16',
    definitionProvider: mode !== 'no-definition',
    workspaceSymbolProvider: mode !== 'no-symbols',
    textDocumentSync: {
      openClose: true,
      change: 1,
      save: true,
    },
  }
  return {
    capabilities,
    serverInfo: {
      name: 'fake-lsp',
      version: '1',
    },
  }
}

function sendServerRequests() {
  sendServerRequest('workspace/configuration', { items: [{ section: 'fake' }] })
  sendServerRequest('window/workDoneProgress/create', { token: 'progress' })
  sendServerRequest('client/registerCapability', { registrations: [] })
  sendServerRequest('workspace/workspaceFolders', {})
  sendServerRequest('custom/unknown', {})
  writeMessage({
    jsonrpc: '2.0',
    method: 'window/logMessage',
    params: { type: 3, message: 'hello' },
  })
}

function sendClientRequests() {
  sendServerRequest('window/showMessageRequest', {
    type: 1,
    message: 'pick one',
    actions: [],
  })
  sendServerRequest('workspace/applyEdit', { edit: {} })
  writeMessage({
    jsonrpc: '2.0',
    method: 'window/showMessage',
    params: { type: 2, message: 'hi' },
  })
  writeMessage({
    jsonrpc: '2.0',
    method: 'telemetry/event',
    params: { event: 'run' },
  })
  writeMessage({
    jsonrpc: '2.0',
    method: 'textDocument/publishDiagnostics',
    params: { uri: 'file:///tmp/alpha.java', diagnostics: [] },
  })
}

function handleMessage(message) {
  const method = message.method
  const id = message.id

  if (method === undefined) {
    // Response from client
    return
  }

  if (id === undefined) {
    // Notification from client
    if (method === 'exit') {
      if (mode === 'stubborn') {
        return
      }
      process.exit(0)
    }
    if (method === 'initialized') {
      if (mode === 'server-requests') {
        sendServerRequests()
      }
      if (mode === 'client-requests') {
        sendClientRequests()
      }
    }
    return
  }

  // Request from client
  switch (method) {
    case 'initialize': {
      if (mode === 'slow-init') {
        setTimeout(() => {
          sendResult(id, initializeResult())
        }, 3000)
        return
      }
      if (mode === 'no-init') {
        return
      }
      sendResult(id, initializeResult())
      break
    }
    case 'shutdown': {
      if (mode !== 'stubborn') {
        sendResult(id, null)
      }
      break
    }
    case 'textDocument/definition': {
      if (mode === 'slow-definition' || mode === 'cancel') {
        // Never reply
        return
      }
      if (mode === 'crash-on-definition') {
        process.stderr.write('fake-lsp: crashing during definition\n')
        process.exit(9)
      }
      if (mode === 'crash-noisy') {
        process.stderr.write('fake-lsp: ' + 'x'.repeat(2048) + '\n')
        process.exit(11)
      }
      if (mode === 'error-definition') {
        sendError(id, -32603, 'boom')
        return
      }
      if (mode === 'null-definition') {
        sendResult(id, null)
        return
      }
      if (mode === 'weird-locations') {
        sendResult(id, [{ uri: 'jdt://contents/pkg/Thing.class' }, { uri: null }])
        return
      }
      if (mode === 'link-definition') {
        const docUri = message.params?.textDocument?.uri || ''
        sendResult(id, [
          {
            targetUri: docUri,
            targetRange: range(9, 5, 3),
            targetSelectionRange: range(9, 5, 7),
          },
        ])
        return
      }
      const docUri = message.params?.textDocument?.uri || ''
      sendResult(id, [{ uri: docUri, range: range(9, 5, 3) }])
      break
    }
    case 'workspace/symbol': {
      if (mode === 'null-symbols') {
        sendResult(id, null)
        return
      }
      if (mode === 'empty-symbols') {
        sendResult(id, [])
        return
      }
      if (mode === 'workspace-symbols') {
        sendResult(id, [{ name: 'Delta', kind: 5, location: { uri: 'file:///tmp/delta.java' } }])
        return
      }
      if (mode === 'odd-symbols') {
        sendResult(id, [{ name: null, kind: null, location: null }])
        return
      }
      sendResult(id, [
        symbol('Alpha', 5, 'file:///tmp/alpha.java', 0),
        symbol('Beta', 12, 'file:///tmp/beta.java', 1),
        symbol('Gamma', 26, 'file:///tmp/gamma.java', 2),
      ])
      break
    }
    case 'java/classFileContents': {
      sendResult(id, decompiledSource())
      break
    }
    case 'workspace/executeCommand': {
      sendResult(id, decompiledSource())
      break
    }
    default: {
      sendError(id, -32601, 'Method not found: ' + method)
      break
    }
  }
}

let buffer = Buffer.alloc(0)
process.stdin.on('data', (chunk) => {
  buffer = Buffer.concat([buffer, chunk])
  while (true) {
    const headerEnd = buffer.indexOf('\r\n\r\n')
    const headerEnd2 = buffer.indexOf('\n\n')
    let sepIndex = -1
    let sepLen = 0
    if (headerEnd !== -1 && (headerEnd2 === -1 || headerEnd <= headerEnd2)) {
      sepIndex = headerEnd
      sepLen = 4
    } else if (headerEnd2 !== -1) {
      sepIndex = headerEnd2
      sepLen = 2
    }
    if (sepIndex === -1) {
      return
    }

    const headersStr = buffer.subarray(0, sepIndex).toString('utf8')
    const match = headersStr.match(/content-length:\s*(\d+)/i)
    if (!match) {
      buffer = buffer.subarray(sepIndex + sepLen)
      continue
    }
    const contentLength = parseInt(match[1], 10)
    const bodyStart = sepIndex + sepLen
    if (buffer.length < bodyStart + contentLength) {
      return
    }
    const bodyBuf = buffer.subarray(bodyStart, bodyStart + contentLength)
    buffer = buffer.subarray(bodyStart + contentLength)
    try {
      const message = JSON.parse(bodyBuf.toString('utf8'))
      record({ event: 'recv', message })
      handleMessage(message)
    } catch {}
  }
})
