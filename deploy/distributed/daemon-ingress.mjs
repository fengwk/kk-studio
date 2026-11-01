#!/usr/bin/env node
/**
 * Minimal L4 TCP ingress proxy for daemon-c failover in the distributed topology.
 *
 * Invariant: App-A and App-B must have no direct or indirect path between them.
 * To enforce this, daemon-ingress only accepts inbound connections from daemon-c
 * (verified by resolving daemon-c IP at connection time).
 *
 * Inbound connections try app-a:8080 first; if connection fails, fall back to app-b:8080.
 */

import dns from 'node:dns/promises'
import net from 'node:net'

const LISTEN_PORT = Number.parseInt(process.env.LISTEN_PORT || '8080', 10)
const TARGETS = [
  { host: 'app-a', port: 8080 },
  { host: 'app-b', port: 8080 },
]
const CONNECT_TIMEOUT_MS = 1000

function normalizeIp(ip) {
  if (!ip) return ''
  return ip.startsWith('::ffff:') ? ip.slice(7) : ip
}

async function isAllowedClient(remoteAddress) {
  const normRemote = normalizeIp(remoteAddress)
  if (normRemote === '127.0.0.1' || normRemote === '::1') {
    return true
  }
  try {
    const results = await dns.lookup('daemon-c', { all: true })
    const allowedIps = results.map((r) => normalizeIp(r.address))
    return allowedIps.includes(normRemote)
  } catch (err) {
    console.warn(`[daemon-ingress] DNS lookup for daemon-c failed: ${err.message}`)
    return false
  }
}

function tryConnect(target, timeoutMs) {
  return new Promise((resolve, reject) => {
    const s = net.createConnection({ host: target.host, port: target.port })
    let timer = setTimeout(() => {
      s.destroy(new Error(`Connection to ${target.host}:${target.port} timed out`))
    }, timeoutMs)

    s.once('connect', () => {
      clearTimeout(timer)
      resolve(s)
    })
    s.once('error', (err) => {
      clearTimeout(timer)
      s.destroy()
      reject(err)
    })
  })
}

const server = net.createServer((clientSocket) => {
  clientSocket.pause()
  const buffered = []
  const onData = (chunk) => buffered.push(chunk)
  clientSocket.on('data', onData)

  let cleanedUp = false
  const cleanup = () => {
    if (cleanedUp) return
    cleanedUp = true
    clientSocket.off('data', onData)
  }

  isAllowedClient(clientSocket.remoteAddress)
    .then(async (allowed) => {
      if (!allowed) {
        console.warn(
          `[daemon-ingress] Rejected connection from unauthorized source: ${clientSocket.remoteAddress}`,
        )
        cleanup()
        clientSocket.destroy()
        return
      }

      let targetSocket = null
      for (const target of TARGETS) {
        if (clientSocket.destroyed) break
        try {
          targetSocket = await tryConnect(target, CONNECT_TIMEOUT_MS)
          if (targetSocket) break
        } catch {
          // target unreachable, try next candidate
        }
      }

      cleanup()

      if (!targetSocket) {
        clientSocket.destroy()
        return
      }

      if (clientSocket.destroyed) {
        targetSocket.destroy()
        return
      }

      clientSocket.setKeepAlive(true, 1000)
      targetSocket.setKeepAlive(true, 1000)

      for (const chunk of buffered) {
        targetSocket.write(chunk)
      }

      clientSocket.resume()
      clientSocket.pipe(targetSocket)
      targetSocket.pipe(clientSocket)

      const closeBoth = () => {
        if (!clientSocket.destroyed) clientSocket.destroy()
        if (!targetSocket.destroyed) targetSocket.destroy()
      }

      clientSocket.on('error', closeBoth)
      clientSocket.on('close', closeBoth)
      targetSocket.on('error', closeBoth)
      targetSocket.on('close', closeBoth)
    })
    .catch(() => {
      cleanup()
      clientSocket.destroy()
    })
})

server.listen(LISTEN_PORT, '0.0.0.0', () => {
  console.log(`[daemon-ingress] Listening on 0.0.0.0:${LISTEN_PORT}`)
})

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    server.close(() => process.exit(0))
  })
}
