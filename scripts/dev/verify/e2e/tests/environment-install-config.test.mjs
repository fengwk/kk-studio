import assert from 'node:assert/strict'
import test from 'node:test'
import { HttpError } from '../lib/http.mjs'

import { linuxInstallConfig } from '../cases/environment-install-config.mjs'
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

const SCRIPT_CASE_ID = 'environment.install_script_code_roundtrip'

function scriptHeaders() {
  return new Headers({
    'content-type': 'text/plain;charset=UTF-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
  })
}

function installScript(token) {
  return [
    "bash <<'KK'",
    `builtin printf '%s' 'http://127.0.0.1:18081' > "$stage/daemon.json"`,
    `builtin printf '%s' 'e2e install config' >> "$stage/daemon.json"`,
    `builtin printf '%s' '/usr/bin/jdtls' >> "$stage/daemon.json"`,
    `builtin printf '%s' '${token}' > "$stage/daemon.token"`,
    `bash "$stage/install.sh" install`,
    'KK',
    '',
  ].join('\n')
}

test('environment install-script case is a free L1 matrix case', () => {
  const caseDef = getCase(SCRIPT_CASE_ID)
  assert.ok(ALL_CASES.some((item) => item.id === SCRIPT_CASE_ID))
  assert.equal(caseDef.level, 'L1')
  assert.deepEqual(Array.from(caseDef.requires), [])
  assert.match(caseDef.docs, /install-code/)
  assert.match(caseDef.docs, /uninstall/)
})

test('environment install-script case checks code, headers and rotation without executing or storing secrets', async () => {
  // 测试意图：recorder 只模拟 HTTP，证明用例覆盖签发、下载头、非法 code、轮换失效与卸载脚本，
  // 且不执行脚本、不把 code/token/脚本写入 artifact。
  const environmentId = '00000000-0000-4000-8000-000000000def'
  const originalToken = '11111111-1111-4111-8111-111111111111'
  const rotatedToken = '22222222-2222-4222-8222-222222222222'
  let version = '0'
  let saved = null
  let token = originalToken
  let activeCode = null
  let deleted = false
  const calls = []
  const secretWrites = []

  const ctx = {
    writeArtifact(name, content) {
      secretWrites.push({ name, content: String(content) })
    },
    async call(method, path, body) {
      calls.push(`${method} ${path.split('?')[0]}`)
      if (method === 'POST' && path === '/api/harness/environments') {
        return {
          json: {
            data: {
              id: environmentId,
              name: body.name,
              registrationToken: token,
              installConfig: null,
              version,
            },
          },
        }
      }
      if (method === 'PUT' && path === `/api/harness/environments/${environmentId}/install-config`) {
        if (body.expectedVersion !== version) throw new HttpError(409, 'version conflict', path)
        saved = body.installConfig
        version = String(Number(version) + 1)
        return { json: { data: { id: environmentId, installConfig: saved, version } } }
      }
      if (method === 'POST' && path === `/api/harness/environments/${environmentId}/install-code`) {
        if (body.expectedVersion !== version) throw new HttpError(409, 'version conflict', path)
        activeCode = `code-v${version}`
        return {
          json: {
            data: {
              code: activeCode,
              expiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
            },
          },
          headers: new Headers({ 'cache-control': 'no-store' }),
        }
      }
      if (method === 'GET' && path === `/api/harness/environments/${environmentId}`) {
        return { json: { data: { id: environmentId, installConfig: saved, version } } }
      }
      if (method === 'GET' && path.startsWith(`/api/harness/environments/${environmentId}/install`)) {
        const code = new URL(path, 'http://matrix.local').searchParams.get('code')
        if (!code || code !== activeCode) throw new HttpError(400, 'install code is invalid', path)
        return { json: installScript(token), headers: scriptHeaders() }
      }
      if (method === 'POST' && path === `/api/harness/environments/${environmentId}/registration-token`) {
        if (body.expectedVersion !== version) throw new HttpError(409, 'version conflict', path)
        token = rotatedToken
        activeCode = null
        version = String(Number(version) + 1)
        return {
          json: {
            data: { id: environmentId, registrationToken: token, installConfig: saved, version },
          },
          headers: new Headers({ 'cache-control': 'no-store' }),
        }
      }
      if (method === 'GET' && path === '/api/harness/environments/uninstall/linux') {
        return { json: "bash <<'KK'\nbash \"$stage/install.sh\" uninstall\nKK\n", headers: scriptHeaders() }
      }
      if (method === 'GET' && path === '/api/harness/environments/uninstall/windows') {
        return { json: "powershell -File $installer uninstall\n", headers: scriptHeaders() }
      }
      if (method === 'GET' && path === '/api/harness/environments/uninstall/solaris') {
        throw new HttpError(400, 'operatingSystem must be linux, macos or windows', path)
      }
      if (method === 'DELETE' && path.startsWith(`/api/harness/environments/${environmentId}`)) {
        assert.ok(path.includes(`expectedVersion=${version}`), 'delete must use current version')
        deleted = true
        return { json: null }
      }
      assert.fail(`unexpected call ${method} ${path}`)
    },
  }

  await getCase(SCRIPT_CASE_ID).run(ctx)

  assert.equal(deleted, true, 'case must delete the environment it created')
  assert.equal(version, '2', 'config save and token rotation should each advance version once')
  assert.equal(token, rotatedToken)
  assert.deepEqual(secretWrites, [], 'case must not write script, token or code artifacts')
  assert.ok(calls.includes('GET /api/harness/environments/uninstall/linux'))
  assert.ok(calls.includes('GET /api/harness/environments/uninstall/windows'))
  assert.ok(calls.includes('GET /api/harness/environments/uninstall/solaris'))
  assert.equal(calls.filter((call) => call.endsWith('/install-code')).length, 3)
  assert.equal(
    calls.filter((call) => call === `GET /api/harness/environments/${environmentId}/install`).length,
    5,
    'case must download once, reject a missing code, a tampered code and the rotated code, then download the new script',
  )
  assert.ok(!calls.some((call) => call.includes('spawn') || call.includes('exec')))
})

for (const failure of ['http', 'timeout']) {
  test(`install-script ${failure} failure omits code and response credentials and still cleans up`, async () => {
    const card = {
      id: '00000000-0000-4000-8000-000000000def',
      version: '0',
      installConfig: null,
    }
    const token = 'synthetic-registration-token'
    const code = 'synthetic-install-code'
    const cardPath = `/api/harness/environments/${card.id}`
    let deleted = false
    const ctx = {
      async call(method, path, body) {
        if (method === 'POST' && path === '/api/harness/environments') {
          return { json: { data: { ...card, registrationToken: token } } }
        }
        if (method === 'PUT' && path === `${cardPath}/install-config`) {
          card.version = '1'
          card.installConfig = linuxInstallConfig()
          return { json: { data: card } }
        }
        if (method === 'POST' && path === `${cardPath}/install-code`) {
          if (body.expectedVersion !== card.version) throw new HttpError(409, 'version conflict', path)
          return {
            json: { data: { code, expiresAt: new Date(Date.now() + 300_000).toISOString() } },
            headers: new Headers({ 'cache-control': 'no-store' }),
          }
        }
        if (method === 'GET' && path === cardPath) return { json: { data: card } }
        if (method === 'GET' && path.startsWith(`${cardPath}/install?`)) {
          if (failure === 'http') throw new HttpError(500, token, path)
          throw new Error(`timeout GET ${path}`)
        }
        if (method === 'DELETE' && path.startsWith(`${cardPath}?`)) {
          deleted = true
          return { json: null }
        }
        assert.fail(`unexpected call ${method} ${path.split('?')[0]}`)
      },
    }
    await assert.rejects(() => getCase(SCRIPT_CASE_ID).run(ctx), error => {
      if (failure === 'http') assert.equal(error.status, 500)
      const report = JSON.stringify({ message: error.message, stack: error.stack, body: error.body, path: error.path })
      assert.equal(report.includes(code), false)
      assert.equal(report.includes(token), false)
      return true
    })
    assert.equal(deleted, true)
  })
}
