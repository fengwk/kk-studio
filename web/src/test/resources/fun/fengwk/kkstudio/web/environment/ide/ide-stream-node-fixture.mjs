import { createServer } from 'node:http'
import { createHash } from 'node:crypto'
import { existsSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import { once } from 'node:events'
import { createBrotliCompress, constants as zlibConstants } from 'node:zlib'

const nodeMajor = process.versions.node.split('.')[0]
if (nodeMajor !== '24') {
  throw new Error(`Node major version 24 is required, but found ${process.versions.node}`)
}

function findRepoRoot() {
  if (process.env.REPO_ROOT && existsSync(path.join(process.env.REPO_ROOT, 'daemon/dist/network.js'))) {
    return process.env.REPO_ROOT
  }
  let dir = path.dirname(fileURLToPath(import.meta.url))
  while (dir !== path.dirname(dir)) {
    if (existsSync(path.join(dir, 'daemon/dist/network.js'))) {
      return dir
    }
    dir = path.dirname(dir)
  }
  dir = process.cwd()
  while (dir !== path.dirname(dir)) {
    if (existsSync(path.join(dir, 'daemon/dist/network.js'))) {
      return dir
    }
    dir = path.dirname(dir)
  }
  throw new Error('cannot find repo root containing daemon/dist/network.js')
}

const repoRoot = findRepoRoot()
const daemonDir = path.join(repoRoot, 'daemon')

const RAW_CHUNK = 16 * 1024

const mode = process.argv[2] ?? 'identity'
const streamUrl = process.argv[3]
const requestPath = process.argv[4] ?? '/out/vs/code/browser/workbench/workbench.css'
const targetOrFile = process.argv[5] ?? '204k'

if (!['identity', 'br'].includes(mode)) {
  throw new Error(`unknown mode: ${mode}`)
}
if (!streamUrl) {
  throw new Error('streamUrl is required')
}

const requireFromDaemon = createRequire(path.join(daemonDir, 'package.json'))
const wsPkg = requireFromDaemon('ws')

const startTime = performance.now()
const events = []
let closeObserved = null

const origSend = wsPkg.prototype.send
wsPkg.prototype.send = function (data, ...args) {
  const t = Math.round(performance.now() - startTime)
  if (typeof data === 'string') {
    try {
      const parsed = JSON.parse(data)
      events.push({ type: 'SEND_TEXT', frameType: parsed.type, t })
    } catch {
      events.push({ type: 'SEND_TEXT', t })
    }
  } else if (Buffer.isBuffer(data) || ArrayBuffer.isView(data)) {
    const buf = Buffer.isBuffer(data) ? data : Buffer.from(data.buffer, data.byteOffset, data.byteLength)
    const opcode = buf[0]
    if (opcode === 1) {
      events.push({ type: 'BODY', bytes: buf.length - 1, t })
    } else if (opcode === 2) {
      events.push({ type: 'END', t })
    } else if (opcode === 3) {
      const ackBytes = buf.readUInt32BE(1)
      events.push({ type: 'ACK', ackBytes, t })
    } else {
      events.push({ type: `SEND_OPCODE_${opcode}`, bytes: buf.length, t })
    }
  }
  return origSend.apply(this, [data, ...args])
}

const origEmit = wsPkg.prototype.emit
wsPkg.prototype.emit = function (event, ...args) {
  const t = Math.round(performance.now() - startTime)
  if (event === 'close') {
    closeObserved = { code: args[0], reason: args[1]?.toString() ?? '', t }
    events.push({ type: 'close', code: args[0], reason: args[1]?.toString() ?? '', t })
  } else if (event === 'message') {
    const data = args[0]
    if (Buffer.isBuffer(data) || ArrayBuffer.isView(data)) {
      const buf = Buffer.isBuffer(data) ? data : Buffer.from(data.buffer, data.byteOffset, data.byteLength)
      if (buf[0] === 3 && buf.length >= 5) {
        const ackBytes = buf.readUInt32BE(1)
        events.push({ type: 'RECV_ACK', ackBytes, t })
      }
    }
  }
  return origEmit.apply(this, [event, ...args])
}

const { installOutboundNetwork } = await import(pathToFileURL(path.join(daemonDir, 'dist/network.js')).href)
await installOutboundNetwork({}, { async resolve() { return null }, async close() {} })

const { openStreamExchange } = await import(pathToFileURL(path.join(daemonDir, 'dist/transport/stream.js')).href)

function sha256(buffer) {
  return createHash('sha256').update(buffer).digest('hex')
}

function generateDeterministicCss(target) {
  let count = 14300
  if (typeof target === 'string') {
    const lower = target.toLowerCase()
    if (lower.includes('307')) {
      count = 21500
    } else if (lower.includes('204')) {
      count = 14300
    } else if (/^\d+$/.test(lower)) {
      count = parseInt(lower, 10)
    }
  } else if (typeof target === 'number') {
    count = target
  }
  const parts = []
  for (let i = 0; i < count; i++) {
    parts.push(
      `.rule-${i} { color: #${((i * 1234567) % 16777215).toString(16).padStart(6, '0')}; margin: ${i % 50}px; padding: ${i % 20}px; font-size: ${10 + (i % 15)}px; content: "label-${i}"; }\n`
    )
  }
  return Buffer.from(parts.join(''), 'utf8')
}

let css
if (existsSync(targetOrFile)) {
  css = await readFile(targetOrFile)
} else {
  css = generateDeterministicCss(targetOrFile)
}
const rawCssHash = sha256(css)

async function writeChunks(stream, buffer, chunkSize, onChunk) {
  for (let offset = 0; offset < buffer.length; offset += chunkSize) {
    const end = Math.min(offset + chunkSize, buffer.length)
    const chunk = buffer.subarray(offset, end)
    onChunk?.(chunk)
    if (!stream.write(chunk)) {
      await once(stream, 'drain')
    }
  }
}

const sentChunks = []
const chunkSizes = []
let fixtureFinished = false

const fixtureServer = createServer((req, res) => {
  try {
    if (mode === 'identity') {
      res.writeHead(200, {
        'content-type': 'text/css',
        'content-length': String(css.length),
        'cache-control': 'no-cache',
      })
      void writeChunks(res, css, RAW_CHUNK, (chunk) => {
        sentChunks.push(Buffer.from(chunk))
        chunkSizes.push(chunk.length)
      })
        .then(() => {
          fixtureFinished = true
          res.end()
        })
        .catch((error) => {
          res.destroy(error)
        })
      return
    }

    // Brotli q4:
    res.writeHead(200, {
      'content-type': 'text/css',
      'content-encoding': 'br',
      vary: 'Accept-Encoding',
      'cache-control': 'no-cache',
    })
    const brotli = createBrotliCompress({
      params: { [zlibConstants.BROTLI_PARAM_QUALITY]: 4 },
    })
    brotli.on('data', (chunk) => {
      sentChunks.push(Buffer.from(chunk))
      chunkSizes.push(chunk.length)
    })
    brotli.on('end', () => {
      fixtureFinished = true
    })
    brotli.pipe(res)
    void writeChunks(brotli, css, RAW_CHUNK)
      .then(() => brotli.end())
      .catch((error) => {
        brotli.destroy(error)
        res.destroy(error)
      })
  } catch (error) {
    res.destroy(error)
  }
})

await new Promise((resolve) => fixtureServer.listen(0, '127.0.0.1', resolve))
const fixturePort = fixtureServer.address().port

const controller = new AbortController()
let exchangeOk = false
let exchangeError = null

try {
  await openStreamExchange({
    streamUrl,
    request: {
      mode: 'HTTP',
      method: 'GET',
      path: requestPath,
      headers: [
        ['accept', 'text/css,*/*;q=0.1'],
        ...(mode === 'br' ? [['accept-encoding', 'br']] : []),
      ],
    },
    upstream: { host: '127.0.0.1', port: fixturePort },
    signal: controller.signal,
    connectTimeoutMillis: 15_000,
  })
  exchangeOk = true
} catch (error) {
  exchangeOk = false
  exchangeError = { name: error.name, message: error.message, stack: error.stack }
} finally {
  fixtureServer.closeAllConnections?.()
  fixtureServer.close()
}

if (!closeObserved) {
  const closeDeadline = Date.now() + 3000
  while (!closeObserved && Date.now() < closeDeadline) {
    await new Promise((r) => setTimeout(r, 10))
  }
}

const sent = Buffer.concat(sentChunks)
const sentHash = sha256(sent)

const bodyEvents = events.filter((e) => e.type === 'BODY')
const endEvent = events.find((e) => e.type === 'END')
const closeEvent = events.find((e) => e.type === 'close')

const timing = {
  firstBodyMs: bodyEvents[0]?.t ?? null,
  lastBodyMs: bodyEvents[bodyEvents.length - 1]?.t ?? null,
  endMs: endEvent?.t ?? null,
  closeMs: closeEvent?.t ?? null,
  events,
}

const report = {
  mode,
  exchangeOk,
  exchangeError,
  rawCssBytes: css.length,
  rawCssSha256: rawCssHash,
  sentBytes: sent.length,
  sentSha256: sentHash,
  chunkCount: chunkSizes.length,
  fixtureFinished,
  closeObserved,
  timing,
}

console.error(
  `[fixture] sentBytes=${sent.length} sentSha256=${sentHash} rawCssBytes=${css.length} rawCssSha256=${rawCssHash} timing: firstBody=${timing.firstBodyMs}ms lastBody=${timing.lastBodyMs}ms end=${timing.endMs}ms close=${timing.closeMs}ms`
)

process.stdout.write(JSON.stringify(report) + '\n')
if (!exchangeOk) {
  process.exit(1)
}
