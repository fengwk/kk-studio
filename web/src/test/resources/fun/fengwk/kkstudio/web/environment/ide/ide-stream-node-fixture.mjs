import { createServer } from 'node:http'
import { createHash } from 'node:crypto'
import { existsSync } from 'node:fs'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import path from 'node:path'
import { once } from 'node:events'
import { createBrotliCompress, constants as zlibConstants } from 'node:zlib'

const nodeMajor = process.versions.node.split('.')[0]
if (nodeMajor !== '24') {
  throw new Error(`Node major version 24 is required, but found ${process.versions.node}`)
}

const repoRoot = process.argv[2]
const streamUrl = process.argv[3]
const requestPath = process.argv[4]
const target = process.argv[5]
if (!repoRoot || !existsSync(path.join(repoRoot, 'daemon/dist/network.js'))) {
  throw new Error('repo root containing daemon/dist/network.js is required')
}
if (!streamUrl || !requestPath || !['204k', '307k'].includes(target)) {
  throw new Error('usage: node ide-stream-node-fixture.mjs <repoRoot> <streamUrl> <requestPath> <204k|307k>')
}

const daemonDir = path.join(repoRoot, 'daemon')
const requireFromDaemon = createRequire(path.join(daemonDir, 'package.json'))
const wsPkg = requireFromDaemon('ws')

const startTime = performance.now()
const events = []
let closeObserved = null

const origSend = wsPkg.prototype.send
wsPkg.prototype.send = function (data, ...args) {
  const t = Math.round(performance.now() - startTime)
  if (Buffer.isBuffer(data) || ArrayBuffer.isView(data)) {
    const buf = Buffer.isBuffer(data) ? data : Buffer.from(data.buffer, data.byteOffset, data.byteLength)
    if (buf[0] === 1) {
      events.push({ type: 'BODY', bytes: buf.length - 1, t })
    } else if (buf[0] === 2) {
      events.push({ type: 'END', t })
    }
  }
  return origSend.apply(this, [data, ...args])
}

const origEmit = wsPkg.prototype.emit
wsPkg.prototype.emit = function (event, ...args) {
  if (event === 'close') {
    const t = Math.round(performance.now() - startTime)
    closeObserved = { code: args[0], reason: args[1]?.toString() ?? '', t }
    events.push({ type: 'close', code: args[0], reason: closeObserved.reason, t })
  }
  return origEmit.apply(this, [event, ...args])
}

const { installOutboundNetwork } = await import(pathToFileURL(path.join(daemonDir, 'dist/network.js')).href)
await installOutboundNetwork({}, { async resolve() { return null }, async close() {} })
const { openStreamExchange } = await import(pathToFileURL(path.join(daemonDir, 'dist/transport/stream.js')).href)

function sha256(buffer) {
  return createHash('sha256').update(buffer).digest('hex')
}

function generateDeterministicCss(ruleCount) {
  const parts = []
  for (let i = 0; i < ruleCount; i++) {
    parts.push(
      `.rule-${i} { color: #${((i * 1234567) % 16777215).toString(16).padStart(6, '0')}; margin: ${i % 50}px; padding: ${i % 20}px; font-size: ${10 + (i % 15)}px; content: "label-${i}"; }\n`
    )
  }
  return Buffer.from(parts.join(''), 'utf8')
}

const css = generateDeterministicCss(target === '204k' ? 14300 : 21500)
const rawCssHash = sha256(css)
const sentChunks = []
let fixtureFinished = false

async function writeChunks(stream, buffer) {
  const chunkSize = 16 * 1024
  for (let offset = 0; offset < buffer.length; offset += chunkSize) {
    if (!stream.write(buffer.subarray(offset, Math.min(offset + chunkSize, buffer.length)))) {
      await once(stream, 'drain')
    }
  }
}

const fixtureServer = createServer((req, res) => {
  res.writeHead(200, {
    'content-type': 'text/css',
    'content-encoding': 'br',
    vary: 'Accept-Encoding',
    'cache-control': 'no-cache',
  })
  const brotli = createBrotliCompress({
    params: { [zlibConstants.BROTLI_PARAM_QUALITY]: 4 },
  })
  brotli.on('data', (chunk) => sentChunks.push(Buffer.from(chunk)))
  brotli.on('end', () => {
    fixtureFinished = true
  })
  brotli.pipe(res)
  void writeChunks(brotli, css)
    .then(() => brotli.end())
    .catch((error) => {
      brotli.destroy(error)
      res.destroy(error)
    })
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
        ['accept-encoding', 'br'],
      ],
    },
    upstream: { host: '127.0.0.1', port: fixturePort },
    signal: controller.signal,
    connectTimeoutMillis: 15_000,
  })
  exchangeOk = true
} catch (error) {
  exchangeError = { name: error.name, message: error.message, stack: error.stack }
} finally {
  fixtureServer.closeAllConnections?.()
  fixtureServer.close()
}

const closeDeadline = Date.now() + 3000
while (!closeObserved && Date.now() < closeDeadline) {
  await new Promise((resolve) => setTimeout(resolve, 10))
}

const sent = Buffer.concat(sentChunks)
const bodyEvents = events.filter((event) => event.type === 'BODY')
const endEvent = events.find((event) => event.type === 'END')
const closeEvent = events.find((event) => event.type === 'close')
const report = {
  exchangeOk,
  exchangeError,
  rawCssBytes: css.length,
  rawCssSha256: rawCssHash,
  sentBytes: sent.length,
  sentSha256: sha256(sent),
  fixtureFinished,
  closeObserved,
  timing: {
    firstBodyMs: bodyEvents[0]?.t ?? null,
    lastBodyMs: bodyEvents.at(-1)?.t ?? null,
    endMs: endEvent?.t ?? null,
    closeMs: closeEvent?.t ?? null,
  },
}

console.error(
  `[fixture] sentBytes=${report.sentBytes} sentSha256=${report.sentSha256} rawCssBytes=${css.length} rawCssSha256=${rawCssHash} timing: firstBody=${report.timing.firstBodyMs}ms lastBody=${report.timing.lastBodyMs}ms end=${report.timing.endMs}ms close=${report.timing.closeMs}ms`
)
process.stdout.write(JSON.stringify(report) + '\n')
if (!exchangeOk) {
  process.exit(1)
}
