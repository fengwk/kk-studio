import assert from 'node:assert/strict'
import test from 'node:test'
import { getCase } from '../run-matrix.mjs'

const identity = {
  type: 'connected',
  eventNodeId: '11111111-1111-4111-8111-111111111111',
  connectionId: '22222222-2222-4222-8222-222222222222',
}

// 执行真实 case，仅替换物理 WS；首帧与 heartbeat 使用生产形状。
async function heartbeat(t, frames) {
  const original = globalThis.WebSocket
  globalThis.WebSocket = class extends EventTarget {
    static OPEN = 1
    readyState = 1
    constructor(url) {
      super()
      assert.equal(new URL(url).pathname, '/api/events')
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

test('heartbeat case accepts production connected identity followed by heartbeat', async (t) => {
  await heartbeat(t, [identity, { type: 'heartbeat' }])
})

test('heartbeat case rejects unexpected fields and malformed connected identity', async (t) => {
  await assert.rejects(heartbeat(t, [{ ...identity, version: 2 }]), /connected fields must be/)
  await assert.rejects(heartbeat(t, [{ ...identity, connectionId: 'invalid' }]), /invalid connected/)
})

test('heartbeat case rejects heartbeat with unexpected fields after a valid identity', async (t) => {
  await assert.rejects(heartbeat(t, [identity, { version: 2, type: 'heartbeat' }]), /invalid heartbeat/)
})

const ids = [
  'fs.read', 'fs.write', 'fs.edit', 'fs.edit.preview', 'process.exec', 'fs.grep', 'fs.find',
  'lsp.goto-definition', 'lsp.workspace-symbols', 'lsp.java-decompile',
]
const card = {
  id: identity.eventNodeId, name: 'fixture', status: 'READY', ready: true,
  userName: 'test', homeDirectory: '/home/test', statusExpiresAt: '2099-01-01T00:00:00Z',
  version: '0', createTime: '2026-01-01T00:00:00Z', updateTime: '2026-01-01T00:00:00Z',
  capabilities: ids.map((id) => ({ id })),
}

test('daemon.ready accepts valid capability catalog including internal preview and rejects incomplete catalog', async () => {
  const run = (environment) => getCase('daemon.ready').run({
    daemonEnv: 'fixture', vars: {},
    call: async () => ({ json: { data: [environment] } }),
  })
  await run(card)
  await assert.rejects(run({
    ...card, capabilities: ids.filter((id) => id !== 'fs.read').map((id) => ({ id })),
  }))
  await assert.rejects(run({ ...card, unexpected: true }), /environment card fields/)
})

test('tool.read_turn executes the daemon.ready prerequisite', async () => {
  // 在后续模型查询前中断，验证跨 case 调用确实到达 daemon.ready。
  const stopped = new Error('daemon prerequisite reached')
  await assert.rejects(getCase('tool.read_turn').run({
    daemonEnv: 'fixture', vars: {},
    call: async () => { throw stopped },
  }), (error) => error === stopped)
})

test('attachment case resolves seed prerequisites across modules', async () => {
  // 替换前置 case 的边界，验证两个独立分支均能定位到原对象。
  for (const [id, vars] of [
    ['seed.agent_and_provider', {}],
    ['seed.structured_model_config', { agent: {} }],
  ]) {
    const prerequisite = getCase(id)
    const original = prerequisite.run
    const stopped = new Error(id)
    prerequisite.run = async () => { throw stopped }
    try {
      await assert.rejects(getCase('chat.attachment_upload_contract').run({ vars }), (error) => error === stopped)
    } finally {
      prerequisite.run = original
    }
  }
})

test('distributed lease projection requires complete capability catalog across both nodes', async () => {
  const run = (caps) => getCase('distributed.lease_routing').run({
    baseUrls: { a: 'http://127.0.0.1:1', b: 'http://127.0.0.1:2' },
    callNode: async (_node, _method, path) => ({
      json: { data: { ...card, id: path.split('/').at(-1),
        capabilities: caps } },
    }),
    writeArtifact() {},
  })
  await run(ids.map((id) => ({ id })))
  await assert.rejects(run(ids.filter((id) => id !== 'fs.read').map((id) => ({ id }))), /fs.read/)
})
