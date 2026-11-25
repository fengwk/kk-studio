import assert from 'node:assert/strict'
import test from 'node:test'
import { getCase } from '../run-matrix.mjs'

const identity = {
  type: 'connected',
  eventNodeId: '11111111-1111-4111-8111-111111111111',
  connectionId: '22222222-2222-4222-8222-222222222222',
  heartbeatIntervalMillis: '20000',
}

// 执行真实 case，仅替换同步 workspace WebSocket contract；帧形状与原生 Node WebSocket 一致。
async function heartbeat(frames) {
  let requestedPath
  const socket = new EventTarget()
  socket.readyState = 1
  socket.close = () => { socket.readyState = 3 }
  const ctx = {
    openWebSocket(path) {
      requestedPath = path
      queueMicrotask(() => {
        for (const frame of frames) socket.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(frame) }))
      })
      return socket
    },
    writeArtifact() {},
  }
  await getCase('events.heartbeat_keepalive').run(ctx)
  assert.equal(requestedPath, '/ws/events')
}

test('heartbeat case accepts production connected identity followed by heartbeat', async (t) => {
  await heartbeat([identity, { type: 'heartbeat' }])
})

test('heartbeat case rejects unexpected fields and malformed connected identity', async (t) => {
  await assert.rejects(heartbeat([{ ...identity, version: 2 }]), /connected fields must be/)
  await assert.rejects(heartbeat([{ ...identity, connectionId: 'invalid' }]), /invalid connected/)
})

test('heartbeat case rejects heartbeat with unexpected fields after a valid identity', async (t) => {
  await assert.rejects(heartbeat([identity, { version: 2, type: 'heartbeat' }]), /invalid heartbeat/)
})

const ids = [
  'fs.read', 'fs.write', 'fs.edit', 'fs.edit.preview', 'fs.write.preview',
  'process.exec', 'fs.grep', 'fs.find-files', 'lsp.goto-definition', 'lsp.workspace-symbols',
  'lsp.java-decompile', 'skill.install',
]
const environment = {
  id: identity.eventNodeId, name: 'fixture', status: 'READY', ready: true,
  daemonConfig: {
    studioUrl: 'http://127.0.0.1:18081',
    bashExecutable: null,
    lsp: null,
    proxyUrl: null,
  },
  userName: 'test', homeDirectory: '/home/test', statusExpiresAt: Date.parse('2099-01-01T00:00:00Z'),
  version: '0', createdAt: Date.parse('2026-01-01T00:00:00Z'), updatedAt: Date.parse('2026-01-01T00:00:00Z'),
  capabilities: ids.map((id) => ({ id })),
}

test('daemon.ready accepts valid capability catalog including internal preview and rejects incomplete catalog', async () => {
  const run = (environment) => getCase('daemon.ready').run({
    daemonEnv: 'fixture', vars: {},
    call: async () => ({ json: [environment] }),
  })
  await run(environment)
  await assert.rejects(run({
    ...environment, capabilities: ids.filter((id) => id !== 'fs.read').map((id) => ({ id })),
  }))
  await assert.rejects(run({ ...environment, unexpected: true }), /environment fields/)
})

test('tool.read_turn executes the daemon.ready prerequisite', async () => {
  // 在后续模型查询前中断，验证跨 case 调用确实到达 daemon.ready。
  const stopped = new Error('daemon prerequisite reached')
  await assert.rejects(getCase('tool.read_turn').run({
    daemonEnv: 'fixture', vars: {},
    call: async () => { throw stopped },
  }), (error) => error === stopped)
})

test('holder-scoped attachment cases are registered in the default L1 matrix', () => {
  for (const id of ['session.attachment_sign_and_stream', 'canvas.storage_upload_contract']) {
    const entry = getCase(id)
    assert.equal(entry.level, 'L1')
    assert.equal(entry.requires.size, 0)
  }
})

test('distributed lease projection requires complete capability catalog across both nodes', async () => {
  const run = (caps) => getCase('distributed.lease_routing').run({
    baseUrls: { a: 'http://127.0.0.1:1', b: 'http://127.0.0.1:2' },
    callNode: async (_node, _method, path) => ({
      json: { ...environment, id: path.split('/').at(-1), capabilities: caps },
    }),
    writeArtifact() {},
  })
  await run(ids.map((id) => ({ id })))
  await assert.rejects(run(ids.filter((id) => id !== 'fs.read').map((id) => ({ id }))), /fs.read/)
})
