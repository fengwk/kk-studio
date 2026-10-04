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
