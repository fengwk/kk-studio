import { createHash } from 'node:crypto'
import assert from 'node:assert/strict'
import test from 'node:test'
import { problemError } from './fixtures/problem.mjs'
import { createCaseAdder } from '../lib/registry.mjs'

import { ALL_CASES, getCase } from '../run-matrix.mjs'
import { cases as syncCases } from '../cases/config-sync.mjs'
import { cases as environmentCardCases } from '../cases/environment-install-config.mjs'

const CASE_IDS = [
  'config_sync.inventory_contract',
  'config_sync.provider_roundtrip_same_name',
  'config_sync.environment_identity_and_token',
  'config_sync.environment_daemon_config_roundtrip',
  'config_sync.daemon_config_hard_invalid_precheck',
  'config_sync.import_precheck_and_partial_confirmation',
]


const sha256 = (value) => createHash('sha256').update(value, 'utf8').digest('hex')

test('case adder normalizes requirements without sharing state between arrays', () => {
  // 独立数组和输入 requirements 不应因另一个 case 被添加而发生改变。
  const first = []
  const second = []
  const requires = ['real', 'real', 'tools']
  const addFirst = createCaseAdder(first)
  const addSecond = createCaseAdder(second)
  addFirst({ id: 'first', requires })
  addSecond({ id: 'second' })
  requires.push('other')
  assert.deepEqual(first[0].requires, new Set(['real', 'tools']))
  assert.deepEqual(second[0].requires, new Set())
  assert.deepEqual(first.map((entry) => entry.id), ['first'])
  assert.deepEqual(second.map((entry) => entry.id), ['second'])
})

test('config sync cases are free L1 cases in the matrix', () => {
  // 配置同步用例均无需模型或外部工具，且由矩阵入口注册。
  for (const id of CASE_IDS) {
    const caseDef = getCase(id)
    assert.ok(caseDef, `case ${id} should be found via getCase`)
    assert.ok(
      ALL_CASES.some((c) => c.id === id),
      `case ${id} should be present in ALL_CASES`,
    )
    assert.equal(caseDef.level, 'L1', `case ${id} level must be L1`)
    assert.deepEqual(Array.from(caseDef.requires), [], `case ${id} requires must be empty array`)
  }

  // 两个模块的实际数组在汇总中保持原有先后顺序。
  assert.ok(ALL_CASES.indexOf(environmentCardCases.at(-1)) < ALL_CASES.indexOf(syncCases[0]))
  assert.ok(syncCases.every((entry) => getCase(entry.id) === entry))
  assert.ok(ALL_CASES.every((entry) => entry.requires instanceof Set))
  assert.equal(new Set(ALL_CASES.map((entry) => entry.id)).size, ALL_CASES.length)
  assert.throws(() => getCase('unknown.case'), /unknown case: unknown.case/)
})

test('config_sync.inventory_contract guards secret leakage and validates exact fields', async () => {
  // 测试意图：使用纯净 mock 数据验证 inventory 契约用例正常通过；随后注入带 credential 等敏感键的条目，断言其必然抛出异常拒绝，证明用例有效防范凭据泄漏而非恒真假阳性。
  const cleanInventory = {
    items: [
      {
        kind: 'providers',
        name: 'test-provider',
        dependencies: [
          { kind: 'models', name: 'test-model' },
        ],
      },
      {
        kind: 'environments',
        name: 'test-env',
        dependencies: [],
      },
      {
        kind: 'settings',
        name: 'settings',
        dependencies: [],
      },
    ],
  }

  const cleanCtx = {
    vars: {},
    async call(method, path) {
      if (method === 'GET' && path === '/api/platform/config-sync') {
        return { status: 200, json: cleanInventory }
      }
      assert.fail(`unexpected call ${method} ${path}`)
    },
  }

  await getCase('config_sync.inventory_contract').run(cleanCtx)

  const dirtyInventory = {
    items: [
      {
        kind: 'providers',
        name: 'leaky-provider',
        dependencies: [],
        credential: 'sk-forbidden-secret',
      },
    ],
  }

  const dirtyCtx = {
    vars: {},
    async call(method, path) {
      if (method === 'GET' && path === '/api/platform/config-sync') {
        return { status: 200, json: dirtyInventory }
      }
      assert.fail(`unexpected call ${method} ${path}`)
    },
  }

  await assert.rejects(
    async () => {
      await getCase('config_sync.inventory_contract').run(dirtyCtx)
    },
    /prohibited|fields/,
  )

  for (const forbiddenKey of ['registrationToken', 'headers', 'url']) {
    const forbiddenInventory = {
      items: [
        {
          kind: 'providers',
          name: 'leaky-item',
          dependencies: [],
          [forbiddenKey]: 'forbidden-value',
        },
      ],
    }
    const forbiddenCtx = {
      vars: {},
      async call(method, path) {
        if (method === 'GET' && path === '/api/platform/config-sync') {
          return { status: 200, json: forbiddenInventory }
        }
        assert.fail(`unexpected call ${method} ${path}`)
      },
    }
    await assert.rejects(
      async () => {
        await getCase('config_sync.inventory_contract').run(forbiddenCtx)
      },
      /prohibited|fields/,
    )
  }
})
test('precheck case enforces read-only checking and explicit partial import before cleanup', async () => {
  let provider = null
  const writes = []
  const ctx = {
    async call(method, path, body) {
      if (method === 'GET') {
        assert.equal(path, '/api/platform/providers')
        return { json: provider ? [provider] : [] }
      }
      if (method === 'DELETE') {
        assert.ok(path.includes(encodeURIComponent(provider.name)))
        provider = null
        return { status: 204, json: null }
      }
      const name = body.yaml.match(/name: (.+)/)[1]
      if (!body.yaml.includes('providerType:')) {
        throw problemError(400, 'validation', 'providerType is required', path)
      }
      if (path.endsWith('/check-import')) {
        return {
          headers: new Headers({ 'cache-control': 'no-store' }),
          json: { created: [{ kind: 'providers', name }], updated: [], skipped: [{ name: 'futureConfig', reason: 'unknown config category' }] },
        }
      }
      assert.equal(path, '/api/platform/config-sync/import')
      if (!body.allowPartial) {
        throw problemError(400, 'validation', 'partial import requires explicit confirmation', path)
      }
      writes.push(body)
      provider = { name, version: '0' }
      return { json: { imported: [{ kind: 'providers', name }], skipped: [{ name: 'futureConfig', reason: 'unknown config category' }] } }
    },
  }
  await getCase('config_sync.import_precheck_and_partial_confirmation').run(ctx)
  assert.equal(writes.length, 1)
  assert.equal(writes[0].allowPartial, true)
  assert.equal(provider, null, 'case must clean up its provider')
})

test('daemon config hard-invalid precheck rejects every endpoint without side effects', async () => {
  // 测试意图：以 recorder 证明硬非法 daemonConfig/旧安装形状在 check/import 两个端点都 400，且不创建 Environment。
  const requests = []
  const ctx = {
    async call(method, path, body) {
      if (method === 'GET') {
        assert.equal(path, '/api/platform/environments')
        return { json: [] }
      }
      assert.equal(method, 'POST')
      assert.ok(['/api/platform/config-sync/check-import', '/api/platform/config-sync/import'].includes(path))
      requests.push({ path, yaml: body.yaml, allowPartial: body.allowPartial })
      throw problemError(400, 'validation', 'environment daemonConfig is invalid', path)
    },
  }
  await getCase('config_sync.daemon_config_hard_invalid_precheck').run(ctx)
  // 6 组非法条目 × 2 个端点，全部硬拒绝。
  assert.equal(requests.length, 12)
  assert.equal(
    requests.filter((request) => request.yaml.includes('"installConfig"')).length,
    2,
    'removed installConfig shape must be probed at both check-import and import',
  )
  assert.equal(
    requests.filter((request) => request.yaml.includes('"operatingSystem"')).length,
    4,
    'both old installConfig and flat operatingSystem shapes must be rejected',
  )
  for (const request of requests) {
    assert.equal(request.allowPartial, true)
    assert.ok(request.yaml.includes('environments'), 'probe yaml must target environments')
  }
})

test('environment daemon-config roundtrip case wires PUT/export/import/update/cleanup against real state', async () => {
  // 测试意图：以带持久状态的 recorder 真跑该 case，证明脚本 wiring 正确：
  // PUT 参数顺序、导出往返、新 token+note/config 原子导入、缺失 daemonConfig 硬拒绝且不清空、最后按最新版本清理。
  // 严格限定 method 合法，任何把 path 当 method 的调用会立即失败（回归先前的参数顺序缺陷）。
  const LEGAL_METHODS = new Set(['GET', 'POST', 'PUT', 'DELETE'])
  const environmentId = '00000000-0000-4000-8000-0000000000cf'
  const base = `/api/platform/environments/${environmentId}`
  let state = null
  let deleted = false
  let deleteSnapshot = null
  const calls = []
  let atomicUpdateApplied = false
  let atomicUpdateNote = null

  const parseEntry = (body) => JSON.parse(body.yaml).environments[0]

  const ctx = {
    async call(method, path, body) {
      assert.ok(LEGAL_METHODS.has(method), `illegal method ${JSON.stringify(method)} for ${path}`)
      calls.push({ method, path, body })
      if (method === 'POST' && path === '/api/platform/environments') {
        assert.deepEqual(Object.keys(body).sort(), ['daemonConfig', 'name', 'note'])
        state = {
          id: environmentId,
          name: body.name,
          note: body.note,
          daemonConfig: body.daemonConfig,
          registrationTokenHash: sha256('e2e-token-original'),
          version: '0',
        }
        return { json: { ...state, registrationToken: 'e2e-token-original' } }
      }
      if (method === 'PUT' && path === base) {
        assert.equal(body.expectedVersion, state.version, 'PUT must CAS on the current version')
        assert.deepEqual(Object.keys(body).sort(), ['daemonConfig', 'expectedVersion', 'note'])
        state = {
          ...state,
          note: body.note,
          daemonConfig: body.daemonConfig,
          version: String(BigInt(state.version) + 1n),
        }
        return { json: { ...state } }
      }
      if (method === 'POST' && path === '/api/platform/config-sync/export') {
        return {
          json: {
            yaml: JSON.stringify({
              environments: [
                {
                  name: state.name,
                  note: state.note,
                  daemonConfig: state.daemonConfig,
                  registrationTokenHash: state.registrationTokenHash,
                },
              ],
            }),
          },
        }
      }
      if (method === 'POST' && path === '/api/platform/config-sync/check-import') {
        const entry = parseEntry(body)
        if (entry.daemonConfig == null) {
          throw problemError(400, 'validation', 'daemonConfig is required', path)
        }
        // 预检查只暴露覆盖引用，绝不回显 token 或 daemonConfig 值。
        return {
          json: {
            created: [],
            updated: [{ kind: 'environments', name: state.name }],
            skipped: [],
          },
        }
      }
      if (method === 'POST' && path === '/api/platform/config-sync/import') {
        const entry = parseEntry(body)
        assert.equal(entry.name, state.name)
        if (entry.daemonConfig == null) {
          throw problemError(400, 'validation', 'daemonConfig is required', path)
        }
        if (entry.registrationTokenHash !== state.registrationTokenHash) {
          atomicUpdateApplied = true
          atomicUpdateNote = entry.note
        }
        state = {
          ...state,
          note: entry.note ?? null,
          daemonConfig: entry.daemonConfig,
          registrationTokenHash: entry.registrationTokenHash,
          version: String(BigInt(state.version) + 1n),
        }
        return { json: { imported: [{ kind: 'environments', name: state.name }], skipped: [] } }
      }
      if (method === 'GET' && path === base) {
        return { json: { ...state } }
      }
      if (method === 'DELETE' && path.startsWith(base)) {
        const expected = new URLSearchParams(path.slice(path.indexOf('?') + 1)).get('expectedVersion')
        assert.equal(expected, state.version, 'DELETE must use the freshest persisted version')
        deleteSnapshot = {
          tokenHash: state.registrationTokenHash,
          note: state.note,
          config: state.daemonConfig,
        }
        deleted = true
        return { status: 204, json: null }
      }
      assert.fail(`unexpected call ${method} ${path}`)
    },
  }

  await getCase('config_sync.environment_daemon_config_roundtrip').run(ctx)

  const methods = calls.map((c) => `${c.method} ${c.path.split('?')[0]}`)
  assert.ok(methods.includes(`PUT ${base}`), 'case must persist the card config via PUT')
  assert.ok(methods.includes('POST /api/platform/config-sync/export'), 'case must export the environment')
  assert.equal(methods.filter((m) => m === 'POST /api/platform/config-sync/check-import').length, 3, 'roundtrip, atomic update and missing-config probe')
  assert.equal(methods.filter((m) => m === 'POST /api/platform/config-sync/import').length, 3, 'roundtrip, atomic update and rejected missing-config import')
  assert.equal(methods.filter((m) => m === 'POST /api/platform/config-sync/export').length, 2, 'roundtrip export plus re-export after the atomic update')
  assert.equal(deleted, true, 'case must delete the environment it created')
  assert.equal(atomicUpdateApplied, true, 'new token+config must be applied in a single import entry')
  assert.equal(atomicUpdateNote, 'e2e environment note updated', 'atomic update must use the new top-level card note')
  assert.ok(deleteSnapshot, 'cleanup must have observed persisted state')
  assert.equal(deleteSnapshot.note, 'e2e environment note updated', 'missing daemonConfig must not clear the card note')
  assert.equal(deleteSnapshot.config.proxyUrl, 'http://127.0.0.1:7891', 'missing daemonConfig must not clear the card config')
  assert.notEqual(deleteSnapshot.tokenHash, sha256('e2e-token-original'), 'cleanup must see the replaced token hash')
})
