import assert from 'node:assert/strict'
import dns from 'node:dns/promises'
import { once } from 'node:events'
import net from 'node:net'
import test from 'node:test'

// 实际入口仍负责授权和pipe；只把固定容器端点接到本测试的隔离TCP服务。
test('daemon ingress forwards bytes sent while authorization is pending', { timeout: 5000 }, async (t) => {
  const payload = Buffer.from('before authorization\n'.repeat(1000))
  const delivered = Promise.withResolvers()
  const authorizationStarted = Promise.withResolvers()
  const authorization = Promise.withResolvers()
  const sockets = new Set()
  let upstreamConnections = 0
  const target = net.createServer((socket) => {
    upstreamConnections++
    sockets.add(socket)
    socket.on('error', () => {})
    const chunks = []
    let length = 0
    socket.on('data', (chunk) => {
      chunks.push(chunk)
      length += chunk.length
      if (length >= payload.length) delivered.resolve(Buffer.concat(chunks))
    })
  })
  await new Promise((resolve) => target.listen(0, '127.0.0.1', resolve))
  const createServer = net.createServer
  const connect = net.createConnection
  let ingress
  let listening
  const previousPort = process.env.LISTEN_PORT
  const signalListeners = new Map(['SIGINT', 'SIGTERM'].map((signal) => [signal, process.listeners(signal)]))
  t.after(async () => {
    for (const socket of sockets) socket.destroy()
    if (previousPort === undefined) delete process.env.LISTEN_PORT
    else process.env.LISTEN_PORT = previousPort
    for (const [signal, previous] of signalListeners) {
      for (const listener of process.listeners(signal)) {
        if (!previous.includes(listener)) process.off(signal, listener)
      }
    }
    await Promise.all([target, ingress].filter(Boolean).map((server) =>
      new Promise((resolve) => server.close(resolve)),
    ))
  })
  t.mock.method(dns, 'lookup', async (host) => {
    assert.equal(host, 'daemon-c')
    authorizationStarted.resolve()
    return authorization.promise
  })
  t.mock.method(net, 'createServer', (handleClient) => {
    ingress = createServer((socket) => {
      sockets.add(socket)
      Object.defineProperty(socket, 'remoteAddress', { value: '192.0.2.45' })
      handleClient(socket)
    })
    listening = once(ingress, 'listening')
    return ingress
  })
  t.mock.method(net, 'createConnection', (options) => {
    assert.deepEqual(options, { host: 'app-a', port: 8080 })
    const socket = connect({ host: '127.0.0.1', port: target.address().port })
    sockets.add(socket)
    return socket
  })
  process.env.LISTEN_PORT = '0'
  await import('../../../../../deploy/distributed/daemon-ingress.mjs')
  await listening
  const client = connect({ host: '127.0.0.1', port: ingress.address().port })
  sockets.add(client)
  await once(client, 'connect')
  await authorizationStarted.promise
  await new Promise((resolve, reject) => client.write(payload, (error) => error ? reject(error) : resolve()))
  assert.equal(upstreamConnections, 0, 'upstream connection must wait for authorization')
  authorization.resolve([{ address: '192.0.2.45', family: 4 }])
  assert.deepEqual(await delivered.promise, payload)
})
