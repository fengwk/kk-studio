import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import '../cases/config-sync.mjs'
import { ALL_CASES, getCase } from '../lib/registry.mjs'

const CASE_IDS = [
  'config_sync.inventory_contract',
  'config_sync.provider_roundtrip_same_name',
  'config_sync.environment_identity_and_token',
]

test('config sync cases are registered as free L1 cases and run-matrix imports the module', () => {
  // 测试意图：验证三个配置同步用例已成功注册为无需外部依赖的 L1 用例（requires 为空），且 run-matrix.mjs 源码包含 exact import 语句。
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
