import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { HttpError } from '../lib/http.mjs'

import '../cases/config-sync.mjs'
import { ALL_CASES, getCase } from '../lib/registry.mjs'

const CASE_IDS = [
  'config_sync.inventory_contract',
  'config_sync.provider_roundtrip_same_name',
  'config_sync.environment_identity_and_token',
  'config_sync.environment_install_config_roundtrip',
  'config_sync.install_config_hard_invalid_precheck',
  'config_sync.import_precheck_and_partial_confirmation',
]

test('config sync cases are registered as free L1 cases and run-matrix imports the module', () => {
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

  const runMatrixPath = new URL('../run-matrix.mjs', import.meta.url)
  const runMatrixSource = readFileSync(runMatrixPath, 'utf8')
  assert.ok(
    runMatrixSource.includes("await import('./cases/config-sync.mjs')"),
    "run-matrix.mjs must contain exact import line: await import('./cases/config-sync.mjs')",
  )
  assert.ok(
    runMatrixSource.includes("await import('./cases/environment-install-config.mjs')"),
    "run-matrix.mjs must import the environment install-config cases",
  )
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
      if (method === 'GET' && path === '/api/settings/sync') {
        return { status: 200, json: { data: cleanInventory } }
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
      if (method === 'GET' && path === '/api/settings/sync') {
        return { status: 200, json: { data: dirtyInventory } }
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
        if (method === 'GET' && path === '/api/settings/sync') {
          return { status: 200, json: { data: forbiddenInventory } }
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
        assert.equal(path, '/api/ai/catalog/providers?pageNumber=1&pageSize=100')
        return { json: { data: { results: provider ? [provider] : [] } } }
      }
      if (method === 'DELETE') {
        assert.ok(path.includes(encodeURIComponent(provider.name)))
        provider = null
        return { json: { data: null } }
      }
      const name = body.yaml.match(/name: (.+)/)[1]
      if (!body.yaml.includes('providerType:')) {
        throw new HttpError(400, 'providerType is required', path)
      }
      if (path.endsWith('/check')) {
        return {
          headers: new Headers({ 'cache-control': 'no-store' }),
          json: { data: { created: [{ kind: 'providers', name }], updated: [], skipped: [{ kind: 'futureConfig', name: '', reason: 'unknown config category' }] } },
        }
      }
      assert.equal(path, '/api/settings/sync/import')
      if (!body.allowPartial) {
        throw new HttpError(400, 'partial import requires explicit confirmation', path)
      }
      writes.push(body)
      provider = { name, version: '0' }
      return { json: { data: { imported: [{ kind: 'providers', name }], skipped: [{ kind: 'futureConfig', name: '', reason: 'unknown config category' }] } } }
    },
  }
  await getCase('config_sync.import_precheck_and_partial_confirmation').run(ctx)
  assert.equal(writes.length, 1)
  assert.equal(writes[0].allowPartial, true)
  assert.equal(provider, null, 'case must clean up its provider')
})

test('install config hard-invalid precheck rejects every endpoint without side effects', async () => {
  // 测试意图：以 recorder 证明硬非法 installConfig 在 check/import 两个端点都 400，且不创建 Environment。
  const requests = []
  const ctx = {
    async call(method, path, body) {
      if (method === 'GET') {
        assert.equal(path, '/api/harness/environments')
        return { json: { data: [] } }
      }
      assert.equal(method, 'POST')
      assert.ok(['/api/settings/sync/import/check', '/api/settings/sync/import'].includes(path))
      requests.push({ path, yaml: body.yaml, allowPartial: body.allowPartial })
      throw new HttpError(400, 'environment installConfig.daemon.studioUrl is invalid', path)
    },
  }
  await getCase('config_sync.install_config_hard_invalid_precheck').run(ctx)
  // 3 组非法配置 × 2 个端点，全部硬拒绝。
  assert.equal(requests.length, 6)
  for (const request of requests) {
    assert.equal(request.allowPartial, true)
    assert.ok(request.yaml.includes('environments'), 'probe yaml must target environments')
  }
})

test('environment install-config roundtrip case wires PUT/export/import/clear/cleanup against real state', async () => {
  // 测试意图：以带持久状态的 recorder 真跑该 case，证明脚本 wiring 正确：
  // PUT install-config 参数顺序、导出往返、新 token+config 原子导入、缺失 installConfig 清空、最后按最新版本清理。
  // 严格限定 method 合法，任何把 path 当 method 的调用会立即失败（回归先前的参数顺序缺陷）。
  const LEGAL_METHODS = new Set(['GET', 'POST', 'PUT', 'DELETE'])
  const environmentId = '00000000-0000-4000-8000-0000000000cf'
  const base = `/api/harness/environments/${environmentId}`
  let state = null
  let deleted = false
  let deleteSnapshot = null
  const calls = []
  let atomicUpdateApplied = false

  const parseEntry = (body) => JSON.parse(body.yaml).environments[0]

  const ctx = {
    async call(method, path, body) {
      assert.ok(LEGAL_METHODS.has(method), `illegal method ${JSON.stringify(method)} for ${path}`)
      calls.push({ method, path, body })
      if (method === 'POST' && path === '/api/harness/environments') {
        state = {
          id: environmentId,
          name: body.name,
          registrationToken: 'e2e-token-original',
          installConfig: null,
          version: '0',
        }
        return { json: { data: { ...state } } }
      }
      if (method === 'PUT' && path === `${base}/install-config`) {
        assert.equal(body.expectedVersion, state.version, 'PUT must CAS on the current version')
        state = { ...state, installConfig: body.installConfig, version: String(Number(state.version) + 1) }
        return { json: { data: { id: environmentId, installConfig: state.installConfig, version: state.version } } }
      }
      if (method === 'POST' && path === '/api/settings/sync/export') {
        return {
          json: {
            data: {
              yaml: JSON.stringify({
                environments: [
                  {
                    name: state.name,
                    registrationToken: state.registrationToken,
                    installConfig: state.installConfig,
                  },
                ],
              }),
            },
          },
        }
      }
      if (method === 'POST' && path === '/api/settings/sync/import/check') {
        parseEntry(body)
        // 预检查只暴露覆盖引用，绝不回显 token 或 installConfig 值。
        return {
          json: {
            data: {
              created: [],
              updated: [{ kind: 'environments', name: state.name }],
              skipped: [],
            },
          },
        }
      }
      if (method === 'POST' && path === '/api/settings/sync/import') {
        const entry = parseEntry(body)
        assert.equal(entry.name, state.name)
        const nextConfig = entry.installConfig ?? null
        if (state.installConfig != null && nextConfig != null && entry.registrationToken !== state.registrationToken) {
          atomicUpdateApplied = true
        }
        state = {
          ...state,
          registrationToken: entry.registrationToken,
          installConfig: nextConfig,
          version: String(Number(state.version) + 1),
        }
        return { json: { data: { imported: [{ kind: 'environments', name: state.name }], skipped: [] } } }
      }
      if (method === 'GET' && path === `${base}/token`) {
        return { json: { data: { id: environmentId, registrationToken: state.registrationToken } } }
      }
      if (method === 'GET' && path === base) {
        return { json: { data: { id: environmentId, installConfig: state.installConfig, version: state.version } } }
      }
      if (method === 'DELETE' && path.startsWith(base)) {
        const expected = new URLSearchParams(path.slice(path.indexOf('?') + 1)).get('expectedVersion')
        assert.equal(expected, state.version, 'DELETE must use the freshest persisted version')
        deleteSnapshot = { token: state.registrationToken, config: state.installConfig }
        deleted = true
        return { json: { data: null } }
      }
      assert.fail(`unexpected call ${method} ${path}`)
    },
  }

  await getCase('config_sync.environment_install_config_roundtrip').run(ctx)

  const methods = calls.map((c) => `${c.method} ${c.path.split('?')[0]}`)
  assert.ok(methods.includes(`PUT ${base}/install-config`), 'case must persist installConfig via PUT')
  assert.ok(methods.includes('POST /api/settings/sync/export'), 'case must export the environment')
  assert.equal(methods.filter((m) => m === 'POST /api/settings/sync/import/check').length, 2, 'roundtrip plus atomic update precheck')
  assert.equal(methods.filter((m) => m === 'POST /api/settings/sync/import').length, 3, 'roundtrip, atomic update, and clearing import')
  assert.ok(methods.includes(`GET ${base}/token`), 'case must read the rotated token')
  assert.equal(deleted, true, 'case must delete the environment it created')
  assert.equal(atomicUpdateApplied, true, 'new token+config must be applied in a single import entry')
  assert.ok(deleteSnapshot, 'cleanup must have observed persisted state')
  assert.equal(deleteSnapshot.config, null, 'absent installConfig must clear saved settings before cleanup')
  assert.notEqual(deleteSnapshot.token, 'e2e-token-original', 'cleanup must see the rotated token')
})
