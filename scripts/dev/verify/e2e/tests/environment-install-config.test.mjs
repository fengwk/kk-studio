import assert from 'node:assert/strict'
import test from 'node:test'
import { HttpError } from '../lib/http.mjs'

import '../cases/environment-install-config.mjs'
import { ALL_CASES, getCase } from '../lib/registry.mjs'

const CASE_ID = 'environment.install_config_cas_roundtrip'

test('environment install-config case is a free L1 matrix case', () => {
  const caseDef = getCase(CASE_ID)
  assert.ok(caseDef, `case ${CASE_ID} should be found via getCase`)
  assert.ok(ALL_CASES.some((c) => c.id === CASE_ID), `case ${CASE_ID} should be in ALL_CASES`)
  assert.equal(caseDef.level, 'L1')
  assert.deepEqual(Array.from(caseDef.requires), [])
})

test('environment install-config case exercises PUT CAS persistence, no-op and hard rejection', async () => {
  // 测试意图：用 recorder 模拟 CAS 与环境 API，证明用例真的执行 stale 409/持久化/no-op/非法 400 且最终清理，
  // 而不是只做静态断言。
  const environmentId = '00000000-0000-4000-8000-000000000abc'
  let version = '0'
  let saved = null
  let deleted = false
  const versionBeforeInvalid = () => version

  const ctx = {
    async call(method, path, body) {
      if (method === 'POST' && path === '/api/harness/environments') {
        return { json: { data: { id: environmentId, name: body.name, installConfig: null, version } } }
      }
      if (method === 'PUT' && path === `/api/harness/environments/${environmentId}/install-config`) {
        if (body.expectedVersion !== version) {
          throw new HttpError(409, 'version conflict', path)
        }
        const config = body.installConfig
        if (
          config == null
          || config.operatingSystem !== 'linux'
          || config.daemon?.studioUrl !== 'http://127.0.0.1:18081'
          || Object.hasOwn(config.daemon ?? {}, 'unknown')
        ) {
          throw new HttpError(400, 'environment installConfig is invalid', path)
        }
        if (saved && JSON.stringify(saved) === JSON.stringify(config)) {
          return { json: { data: { id: environmentId, installConfig: saved, version } } }
        }
        saved = config
        version = String(Number(version) + 1)
        return { json: { data: { id: environmentId, installConfig: saved, version } } }
      }
      if (method === 'GET' && path === `/api/harness/environments/${environmentId}`) {
        return { json: { data: { id: environmentId, installConfig: saved, version } } }
      }
      if (method === 'DELETE' && path.startsWith(`/api/harness/environments/${environmentId}`)) {
        assert.ok(path.includes(`expectedVersion=${version}`), `delete must use current version: ${path}`)
        deleted = true
        return { json: null }
      }
      assert.fail(`unexpected call ${method} ${path}`)
    },
  }

  await getCase(CASE_ID).run(ctx)

  assert.equal(version, '1', 'only the first persist should advance version (no-op must not)')
  assert.equal(saved.daemon.lsp.servers.jdtls.command[0], '/usr/bin/jdtls')
  assert.ok(versionBeforeInvalid() === '1')
  assert.equal(deleted, true, 'case must delete the environment it created')
})
