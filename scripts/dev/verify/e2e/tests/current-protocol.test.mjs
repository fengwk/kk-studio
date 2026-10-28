import assert from 'node:assert/strict'
import test from 'node:test'
import '../cases/app-events.mjs'
import '../cases/real.mjs'
import '../cases/distributed.mjs'
import { getCase } from '../lib/registry.mjs'

const identity = {
  version: 2, type: 'connected',
  eventNodeId: '11111111-1111-4111-8111-111111111111',
  connectionId: '22222222-2222-4222-8222-222222222222',
}

// 执行真实 case，仅替换物理 WS；首帧与 heartbeat 使用生产 v2 形状。
async function heartbeat(t, frames) {
  const original = globalThis.WebSocket
  globalThis.WebSocket = class extends EventTarget {
    static OPEN = 1
    readyState = 1
    constructor(url) {
      super()
      assert.equal(new URL(url).pathname, '/api/events/v2')
      queueMicrotask(() => {
        for (const frame of frames) {
          this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(frame) }))
        }
      })
    }
    close() { this.readyState = 3 }
  }
  try {
    await getCase('events.heartbeat_keepalive').run({
      baseUrl: 'http://127.0.0.1:18081', writeArtifact() {},
    })
  } finally {
    globalThis.WebSocket = original
  }
}

test('heartbeat case accepts production connected identity followed by v2 heartbeat', async (t) => {
  await heartbeat(t, [identity, { version: 2, type: 'heartbeat' }])
})

test('heartbeat case rejects old version and malformed connected identity', async (t) => {
  await assert.rejects(heartbeat(t, [{ ...identity, version: 1 }]), /invalid connected/)
  await assert.rejects(heartbeat(t, [{ ...identity, connectionId: 'invalid' }]), /invalid connected/)
})

test('heartbeat case rejects old heartbeat after a valid identity', async (t) => {
  await assert.rejects(heartbeat(t, [identity, { version: 1, type: 'heartbeat' }]), /invalid heartbeat/)
})

const ids = [
  'fs.read', 'fs.write', 'fs.edit', 'fs.edit.preview', 'process.exec', 'fs.grep', 'fs.find',
  'lsp.goto-definition', 'lsp.workspace-symbols', 'lsp.java-decompile', 'skill.sync',
]
const card = {
  id: identity.eventNodeId, name: 'fixture', status: 'READY', ready: true,
  userName: 'test', homeDirectory: '/home/test', statusExpiresAt: '2099-01-01T00:00:00Z',
  capabilities: ids.map((id) => ({ id, version: '3' })),
}

test('daemon.ready accepts catalog 3 including internal preview and rejects catalog 2', async () => {
  const run = (environment) => getCase('daemon.ready').run({
    daemonEnv: 'fixture', vars: {},
    call: async () => ({ json: { data: [environment] } }),
  })
  await run(card)
  await assert.rejects(run({
    ...card, capabilities: ids.map((id) => ({ id, version: '2' })),
  }))
})

test('distributed lease projection requires catalog 3 across both nodes', async () => {
  const run = (version) => getCase('distributed.lease_routing').run({
    baseUrls: { a: 'http://127.0.0.1:1', b: 'http://127.0.0.1:2' },
    callNode: async (_node, _method, path) => ({
      json: { data: { ...card, id: path.split('/').at(-1),
        capabilities: ids.map((id) => ({ id, version })) } },
    }),
    writeArtifact() {},
  })
  await run('3')
  await assert.rejects(run('2'), /fs.read@3/)
})
