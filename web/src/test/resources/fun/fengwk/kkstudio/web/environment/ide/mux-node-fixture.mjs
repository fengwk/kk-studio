import { createHash } from 'node:crypto'
import { createServer as createHttpServer } from 'node:http'
import { createServer as createTcpServer } from 'node:net'
import path from 'node:path'
import { createInterface } from 'node:readline'
import { pathToFileURL } from 'node:url'

/**
 * Cross-process fixture for the Java/Node mux v2 integration test.
 *
 * <p>The Java test owns the physical Studio WebSocket. This process runs the built
 * `daemon/dist/transport/mux-upstream.js` service exactly as the daemon does, and resolves every
 * accepted view stream to private loopback HTTP/WebSocket fixtures created below. Configuration is
 * read from stdin so the one-use ticket never appears in argv or diagnostics. Snapshots never contain
 * the ticket, stream URL or upstream payloads.
 */

const HANDSHAKE_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
const OPCODE_CONTINUATION = 0x0
const OPCODE_TEXT = 0x1
const OPCODE_BINARY = 0x2
const OPCODE_CLOSE = 0x8
const OPCODE_PING = 0x9
const OPCODE_PONG = 0xa

let config
let service
let installedNetwork
let stopping = false
let httpFixture
let wsFixture
let wsPort
let httpPort

const state = {
  bindCalls: 0,
  bindingError: null,
  dataCloseCount: 0,
  dataCloseError: null,
  idleCount: 0,
  releases: 0,
  resolved: [],
  errors: [],
  httpRequests: [],
  wsConnections: [],
  wsSockets: new Set(),
  pendingResolvers: new Set(),
}

function sanitize(value) {
  let text = String(value)
  if (config?.ticket) {
    text = text.split(config.ticket).join('<redacted>')
  }
  if (config?.streamUrl) {
    text = text.split(config.streamUrl).join('<redacted>')
  }
  return text
}

function safeError(error) {
  return {
    name: error instanceof Error ? error.name : 'Error',
    message: sanitize(error instanceof Error ? error.message : error),
  }
}

function recordError(error) {
  const safe = safeError(error)
  state.errors.push(safe)
  return safe
}

function writeLine(value) {
  process.stdout.write(`${JSON.stringify(value)}\n`)
}

function failStartup(error, exitCode = 1) {
  const safe = safeError(error)
  process.stderr.write(`${JSON.stringify({ type: 'error', ...safe })}\n`)
  process.exitCode = exitCode
  process.exit()
}

function report(type) {
  return {
    type,
    node: process.versions.node,
    viewId: config?.viewId ?? null,
    bindingEpoch: config?.bindingEpoch ?? null,
    physical: {
      bindCalls: state.bindCalls,
      bindingError: state.bindingError,
      dataCloseCount: state.dataCloseCount,
      dataCloseError: state.dataCloseError,
      activeStreams: service === undefined ? 0 : service.activeStreamCount(),
    },
    resolved: state.resolved,
    releases: state.releases,
    idleCount: state.idleCount,
    http: { requests: state.httpRequests },
    ws: { connections: state.wsConnections },
    errors: state.errors,
  }
}

function sha256(buffer) {
  return createHash('sha256').update(buffer).digest('hex')
}

function frameBytes(fin, opcode, payload) {
  const length = payload.byteLength
  let header
  if (length < 126) {
    header = Buffer.allocUnsafe(2)
    header[0] = (fin ? 0x80 : 0) | opcode
    header[1] = length
  } else if (length <= 0xffff) {
    header = Buffer.allocUnsafe(4)
    header[0] = (fin ? 0x80 : 0) | opcode
    header[1] = 126
    header.writeUInt16BE(length, 2)
  } else {
    header = Buffer.allocUnsafe(10)
    header[0] = (fin ? 0x80 : 0) | opcode
    header[1] = 127
    header.writeBigUInt64BE(BigInt(length), 2)
  }
  return Buffer.concat([header, payload])
}

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      server.off('error', reject)
      resolve(server.address().port)
    })
  })
}

function closeServer(server) {
  if (server === undefined) {
    return Promise.resolve()
  }
  server.closeAllConnections?.()
  return new Promise((resolve) => server.close(() => resolve()))
}

function httpFixtureRequest(request, response) {
  const pathName = (request.url ?? '').split('?')[0]
  const record = {
    path: pathName,
    method: request.method ?? '',
    bytes: 0,
    sha256: null,
    ended: false,
    aborted: false,
    responseFirstChunkBeforeEnd: null,
  }
  state.httpRequests.push(record)
  const digest = createHash('sha256')
  let requestEnded = false

  request.on('data', (chunk) => {
    record.bytes += chunk.byteLength
    digest.update(chunk)
  })
  request.on('aborted', () => {
    record.aborted = true
  })
  request.on('error', () => {
    record.aborted = true
  })
  request.on('end', () => {
    requestEnded = true
    record.ended = true
    record.sha256 = digest.digest('hex')
    if (pathName.includes('/half-close')) {
      response.end(`after-end:${record.bytes}:${record.sha256}`)
      return
    }
    if (pathName.includes('/hash')) {
      response.writeHead(200, {
        'content-type': 'text/plain',
        'x-kk-request-sha256': record.sha256,
      })
      response.end(`${record.sha256}:${record.bytes}`)
      return
    }
    response.writeHead(404, { 'content-type': 'text/plain' })
    response.end('unknown fixture path')
  })

  if (pathName.includes('/half-close')) {
    response.writeHead(200, { 'content-type': 'text/plain' })
    response.write('before-end:')
    record.responseFirstChunkBeforeEnd = !requestEnded
    return
  }
  if (!pathName.includes('/hash')) {
    request.resume()
  }
}

function parseClientFrames(connection, socket, chunk) {
  connection.buffer = Buffer.concat([connection.buffer, chunk])
  if (!connection.upgraded) {
    const end = connection.buffer.indexOf('\r\n\r\n')
    if (end < 0) {
      return
    }
    const head = connection.buffer.subarray(0, end).toString('latin1')
    connection.buffer = connection.buffer.subarray(end + 4)
    const lines = head.split('\r\n')
    const requestLine = lines[0]?.split(' ') ?? []
    connection.path = requestLine[1] ?? ''
    const headers = new Map()
    for (const line of lines.slice(1)) {
      const separator = line.indexOf(':')
      if (separator > 0) {
        headers.set(line.slice(0, separator).trim().toLowerCase(), line.slice(separator + 1).trim())
      }
    }
    if (headers.get('upgrade')?.toLowerCase() !== 'websocket') {
      connection.protocolError = 'missing websocket upgrade'
      socket.destroy()
      return
    }
    const key = headers.get('sec-websocket-key')
    if (typeof key !== 'string' || key.length === 0) {
      connection.protocolError = 'missing websocket key'
      socket.destroy()
      return
    }
    const accept = createHash('sha1').update(key + HANDSHAKE_GUID).digest('base64')
    socket.write(
      'HTTP/1.1 101 Switching Protocols\r\n' +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
    )
    connection.upgraded = true
  }

  for (;;) {
    if (connection.buffer.length < 2) {
      return
    }
    const first = connection.buffer[0]
    const second = connection.buffer[1]
    const fin = (first & 0x80) !== 0
    const opcode = first & 0x0f
    const masked = (second & 0x80) !== 0
    let payloadLength = second & 0x7f
    let offset = 2
    if (payloadLength === 126) {
      if (connection.buffer.length < 4) {
        return
      }
      payloadLength = connection.buffer.readUInt16BE(2)
      offset = 4
    } else if (payloadLength === 127) {
      if (connection.buffer.length < 10) {
        return
      }
      const high = connection.buffer.readUInt32BE(2)
      const low = connection.buffer.readUInt32BE(6)
      if ((high & 0x8000_0000) !== 0) {
        connection.protocolError = 'frame length is invalid'
        socket.destroy()
        return
      }
      payloadLength = high * 0x1_0000_0000 + low
      offset = 10
    }
    if (!masked) {
      connection.protocolError = 'client frame was not masked'
      socket.destroy()
      return
    }
    if (connection.buffer.length < offset + 4 + payloadLength) {
      return
    }
    const mask = connection.buffer.subarray(offset, offset + 4)
    const source = connection.buffer.subarray(offset + 4, offset + 4 + payloadLength)
    const payload = Buffer.allocUnsafe(payloadLength)
    for (let index = 0; index < payloadLength; index += 1) {
      payload[index] = source[index] ^ mask[index & 3]
    }
    connection.buffer = connection.buffer.subarray(offset + 4 + payloadLength)
    handleClientFrame(connection, socket, fin, opcode, payload)
    if (connection.closed) {
      return
    }
  }
}

function handleClientFrame(connection, socket, fin, opcode, payload) {
  if (opcode === OPCODE_CLOSE) {
    connection.closeReceived = true
    connection.closeCode = payload.length >= 2 ? payload.readUInt16BE(0) : null
    connection.closeReason =
      payload.length > 2 ? payload.subarray(2).toString('utf8') : ''
    connection.frames.push({
      fin,
      opcode,
      bytes: payload.length,
      sha256: sha256(payload),
    })
    socket.write(frameBytes(true, OPCODE_CLOSE, payload), () => socket.end())
    connection.closed = true
    return
  }
  if (opcode === OPCODE_PING) {
    socket.write(frameBytes(true, OPCODE_PONG, payload))
    return
  }
  if (opcode === OPCODE_PONG) {
    return
  }
  if (
    opcode !== OPCODE_CONTINUATION &&
    opcode !== OPCODE_TEXT &&
    opcode !== OPCODE_BINARY
  ) {
    connection.protocolError = `unsupported opcode ${opcode}`
    socket.destroy()
    return
  }
  connection.frames.push({
    fin,
    opcode,
    bytes: payload.length,
    sha256: sha256(payload),
  })
  socket.write(frameBytes(fin, opcode, payload))
  connection.echoedFrames += 1
}

function acceptWebSocket(socket) {
  const connection = {
    path: '',
    buffer: Buffer.alloc(0),
    upgraded: false,
    frames: [],
    echoedFrames: 0,
    closed: false,
    closeReceived: false,
    closeCode: null,
    closeReason: '',
    protocolError: null,
  }
  state.wsConnections.push(connection)
  state.wsSockets.add(socket)
  socket.on('data', (chunk) => {
    try {
      parseClientFrames(connection, socket, chunk)
    } catch (error) {
      connection.protocolError = safeError(error).message
      socket.destroy()
    }
  })
  socket.on('error', () => {
    connection.closed = true
  })
  socket.on('close', () => {
    connection.closed = true
    state.wsSockets.delete(socket)
  })
}

async function startFixtures() {
  httpFixture = createHttpServer(httpFixtureRequest)
  httpPort = await listen(httpFixture)
  wsFixture = createTcpServer(acceptWebSocket)
  wsPort = await listen(wsFixture)
}

function resolveUpstream(viewId, bindingEpoch, request) {
  if (viewId !== config.viewId || bindingEpoch !== config.bindingEpoch) {
    recordError(new Error('resolver received an unexpected view binding'))
    return Promise.reject(new Error('unexpected view binding'))
  }
  const resolved = {
    mode: request.mode,
    method: request.method,
    path: request.path,
    headers: request.headers,
  }
  state.resolved.push(resolved)
  const controller = new AbortController()
  state.pendingResolvers.add(controller)
  return Promise.resolve({
    host: '127.0.0.1',
    port: request.mode === 'HTTP' ? httpPort : wsPort,
    path: request.path,
    headers: request.headers,
    signal: controller.signal,
    release() {
      if (!state.pendingResolvers.delete(controller)) {
        return
      }
      state.releases += 1
    },
  })
}

async function start(configuration) {
  config = configuration
  if (typeof config !== 'object' || config === null) {
    throw new Error('stdin configuration is required')
  }
  if (typeof config.daemonDist !== 'string' || !path.isAbsolute(config.daemonDist)) {
    throw new Error('daemonDist must be an absolute path')
  }
  await startFixtures()

  const networkModule = await import(
    pathToFileURL(path.join(config.daemonDist, 'network.js')).href
  )
  installedNetwork = await networkModule.installOutboundNetwork(
    {},
    { async resolve() { return null }, async close() {} },
  )
  const upstreamModule = await import(
    pathToFileURL(path.join(config.daemonDist, 'transport/mux-upstream.js')).href
  )
  service = upstreamModule.createMuxUpstreamService({
    studioUrl: config.studioUrl,
    resolveView: resolveUpstream,
    onIdle: () => {
      state.idleCount += 1
    },
    onDataClose: (error) => {
      state.dataCloseCount += 1
      state.dataCloseError = error === null ? null : safeError(error)
    },
  })
  state.bindCalls += 1
  void service
    .bind(
      {
        generation: config.generation,
        ticket: config.ticket,
        streamUrl: config.streamUrl,
      },
      new AbortController().signal,
    )
    .catch((error) => {
      state.bindingError = safeError(error)
    })
}

async function shutdown() {
  if (stopping) {
    return
  }
  stopping = true
  try {
    await service?.shutdown()
  } catch (error) {
    recordError(error)
  }
  for (const controller of state.pendingResolvers) {
    controller.abort()
  }
  state.pendingResolvers.clear()
  for (const socket of state.wsSockets) {
    socket.destroy()
  }
  state.wsSockets.clear()
  await closeServer(wsFixture)
  await closeServer(httpFixture)
  try {
    await installedNetwork?.close()
  } catch (error) {
    recordError(error)
  }
  writeLine(report('report'))
  process.exit(0)
}

async function main() {
  if (process.versions.node.split('.')[0] !== '24') {
    throw new Error(`Node major version 24 is required, but found ${process.versions.node}`)
  }
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity })
  let started = false
  for await (const line of lines) {
    if (line.trim().length === 0) {
      continue
    }
    const message = JSON.parse(line)
    if (!started) {
      await start(message)
      started = true
      continue
    }
    if (message.action === 'snapshot') {
      writeLine(report('snapshot'))
      continue
    }
    if (message.action === 'shutdown') {
      await shutdown()
      return
    }
  }
  if (started) {
    await shutdown()
  }
}

process.on('uncaughtException', (error) => {
  failStartup(error)
})
process.on('unhandledRejection', (error) => {
  failStartup(error)
})

main().catch((error) => {
  failStartup(error)
})
