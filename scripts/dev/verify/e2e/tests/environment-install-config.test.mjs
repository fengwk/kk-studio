import assert from 'node:assert/strict'
import test from 'node:test'
import { problemError } from './fixtures/problem.mjs'

import { linuxInstallConfig, assertInstallConfig, assertExpiresNearFiveMinutes } from '../cases/environment-install-config.mjs'
import { ALL_CASES, getCase } from '../run-matrix.mjs'

const CASE_ID = 'environment.install_config_cas_roundtrip'

test('environment install-config case is a free L1 matrix case', () => {
  const caseDef = getCase(CASE_ID)
  assert.ok(caseDef, `case ${CASE_ID} should be found via getCase`)
  assert.ok(ALL_CASES.some((c) => c.id === CASE_ID), `case ${CASE_ID} should be in ALL_CASES`)
  assert.equal(caseDef.level, 'L1')
  assert.deepEqual(Array.from(caseDef.requires), [])
})

test('canonical install config has exactly the expected top-level fields', () => {
  // 测试意图：往返断言拒绝包含任意未知字段的配置。
  const config = linuxInstallConfig()
  assert.doesNotThrow(() => assertInstallConfig(config))
  assert.throws(
    () => assertInstallConfig({ ...config, unknownField: null }),
    /installConfig/,
  )
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
      if (method === 'POST' && path === '/api/platform/environments') {
        return { json: { id: environmentId, name: body.name, installConfig: null, version } }
      }
      if (method === 'PUT' && path === `/api/platform/environments/${environmentId}/install-settings`) {
        if (body.expectedVersion !== version) {
          throw problemError(409, 'version_conflict', 'version conflict', path)
        }
        const config = body.installConfig
        if (
          config == null
          || config.operatingSystem !== 'linux'
          || Object.keys(config).some((key) => !['operatingSystem', 'daemon'].includes(key))
          || config.daemon?.studioUrl !== 'http://127.0.0.1:18081'
          || Object.hasOwn(config.daemon ?? {}, 'unknown')
        ) {
          throw problemError(400, 'validation', 'environment installConfig is invalid', path)
        }
        if (saved && JSON.stringify(saved) === JSON.stringify(config)) {
          return { json: { id: environmentId, installConfig: saved, version } }
        }
        saved = config
        version = String(Number(version) + 1)
        return { json: { id: environmentId, installConfig: saved, version } }
      }
      if (method === 'GET' && path === `/api/platform/environments/${environmentId}`) {
        return { json: { id: environmentId, installConfig: saved, version } }
      }
      if (method === 'DELETE' && path.startsWith(`/api/platform/environments/${environmentId}`)) {
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
  assert.match(caseDef.docs, /install-codes/)
  assert.match(caseDef.docs, /daemon-uninstall-scripts/)
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
  let issueSequence = 0
  let consumptions = 0
  let replacedRejections = 0
  let consumedRejections = 0
  let rotatedRejections = 0
  const invalidated = new Map()
  let deleted = false
  const calls = []
  const secretWrites = []

  const ctx = {
    writeArtifact(name, content) {
      secretWrites.push({ name, content: String(content) })
    },
    async call(method, path, body) {
      calls.push(`${method} ${path.split('?')[0]}`)
      if (method === 'POST' && path === '/api/platform/environments') {
        return {
          json: {
            id: environmentId,
            name: body.name,
            registrationToken: token,
            installConfig: null,
            version,
          },
        }
      }
      if (method === 'PUT' && path === `/api/platform/environments/${environmentId}/install-settings`) {
        if (body.expectedVersion !== version) throw problemError(409, 'version_conflict', 'version conflict', path)
        saved = body.installConfig
        version = String(Number(version) + 1)
        return { json: { id: environmentId, installConfig: saved, version } }
      }
      if (method === 'POST' && path === `/api/platform/environments/${environmentId}/install-codes`) {
        if (body.expectedVersion !== version) throw problemError(409, 'version_conflict', 'version conflict', path)
        if (activeCode) invalidated.set(activeCode, 'replaced')
        activeCode = `opaque-code-${++issueSequence}`
        return {
          json: {
            code: activeCode,
            // 真实 wire：全局 Jackson 把 Instant 序列化为 epoch seconds 数字（此处带小数毫秒）。
            expiresAt: (Date.now() + 5 * 60 * 1000) / 1000,
          },
          headers: new Headers({ 'cache-control': 'no-store' }),
        }
      }
      if (method === 'GET' && path === `/api/platform/environments/${environmentId}`) {
        return { json: { id: environmentId, installConfig: saved, version } }
      }
      if (method === 'GET' && path.startsWith(`/api/platform/environments/${environmentId}/install`)) {
        const code = new URL(path, 'http://matrix.local').searchParams.get('code')
        if (!code || code !== activeCode) {
          if (invalidated.get(code) === 'replaced') replacedRejections++
          if (invalidated.get(code) === 'consumed') consumedRejections++
          if (invalidated.get(code) === 'rotated') rotatedRejections++
          throw problemError(400, 'validation', 'install code is invalid', path)
        }
        invalidated.set(activeCode, 'consumed')
        activeCode = null
        consumptions++
        token = consumptions === 1
          ? '33333333-3333-4333-8333-333333333333'
          : '44444444-4444-4444-8444-444444444444'
        version = String(Number(version) + 1)
        return { json: installScript(token), headers: scriptHeaders() }
      }
      if (method === 'POST' && path === `/api/platform/environments/${environmentId}/rotate-token`) {
        if (body.expectedVersion !== version) throw problemError(409, 'version_conflict', 'version conflict', path)
        token = rotatedToken
        assert.ok(activeCode, 'rotation must invalidate an unconsumed code')
        invalidated.set(activeCode, 'rotated')
        activeCode = null
        version = String(Number(version) + 1)
        return {
          json: { id: environmentId, registrationToken: token, installConfig: saved, version },
          headers: new Headers({ 'cache-control': 'no-store' }),
        }
      }
      if (method === 'GET' && path === '/api/platform/daemon-uninstall-scripts/linux') {
        return { json: "bash <<'KK'\nbash \"$stage/install.sh\" uninstall\nKK\n", headers: scriptHeaders() }
      }
      if (method === 'GET' && path === '/api/platform/daemon-uninstall-scripts/windows') {
        return { json: "powershell -File $installer uninstall\n", headers: scriptHeaders() }
      }
      if (method === 'GET' && path === '/api/platform/daemon-uninstall-scripts/solaris') {
        throw problemError(400, 'validation', 'operatingSystem must be linux, macos or windows', path)
      }
      if (method === 'DELETE' && path.startsWith(`/api/platform/environments/${environmentId}`)) {
        assert.ok(path.includes(`expectedVersion=${version}`), 'delete must use current version')
        deleted = true
        return { json: null }
      }
      assert.fail(`unexpected call ${method} ${path}`)
    },
  }

  await getCase(SCRIPT_CASE_ID).run(ctx)

  assert.equal(deleted, true, 'case must delete the environment it created')
  assert.equal(version, '4', 'config save, two redemptions and rotation each advance version')
  assert.equal(consumptions, 2)
  assert.equal(replacedRejections, 1)
  assert.equal(consumedRejections, 1)
  assert.equal(rotatedRejections, 1)
  assert.deepEqual(secretWrites, [], 'case must not write script, token or code artifacts')
  assert.ok(calls.includes('GET /api/platform/daemon-uninstall-scripts/linux'))
  assert.ok(calls.includes('GET /api/platform/daemon-uninstall-scripts/windows'))
  assert.ok(calls.includes('GET /api/platform/daemon-uninstall-scripts/solaris'))
  assert.equal(calls.filter((call) => call.endsWith('/install-codes')).length, 5)
  assert.equal(
    calls.filter((call) => call === `GET /api/platform/environments/${environmentId}/install-script`).length,
    7,
    'case must redeem twice and reject missing, tampered, consumed, replaced and rotated codes',
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
    const cardPath = `/api/platform/environments/${card.id}`
    let deleted = false
    const ctx = {
      async call(method, path, body) {
        if (method === 'POST' && path === '/api/platform/environments') {
          return { json: { ...card, registrationToken: token } }
        }
        if (method === 'PUT' && path === `${cardPath}/install-settings`) {
          card.version = '1'
          card.installConfig = linuxInstallConfig()
          return { json: card }
        }
        if (method === 'POST' && path === `${cardPath}/install-codes`) {
          if (body.expectedVersion !== card.version) throw problemError(409, 'version_conflict', 'version conflict', path)
          return {
            json: { code, expiresAt: (Date.now() + 300_000) / 1000 },
            headers: new Headers({ 'cache-control': 'no-store' }),
          }
        }
        if (method === 'GET' && path === cardPath) return { json: card }
        if (method === 'GET' && path.startsWith(`${cardPath}/install-script?`)) {
          if (failure === 'http') throw problemError(500, 'internal', token, path)
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

const INSTALL_TTL_MS = 5 * 60 * 1000

test('install code expiresAt accepts epoch seconds from the real wire, including fractional values', () => {
  // 测试意图：全局 Jackson 写出的 expiresAt 是 epoch seconds 数字（可为小数），必须按秒换算而非直接 Date.parse(number)。
  const issuedAtMs = 1_700_000_000_000
  assert.doesNotThrow(() => assertExpiresNearFiveMinutes(issuedAtMs / 1000 + 300, issuedAtMs))
  assert.doesNotThrow(() => assertExpiresNearFiveMinutes(issuedAtMs / 1000 + 300.5, issuedAtMs))
})

test('install code expiresAt rejects unparseable instants and out-of-window expiry', () => {
  // 测试意图：非数字/负/非法字符串等不可解析 wire 必须报“无法解析”，偏离五分钟超过 ±skew 的过期值必须报“约五分钟”，不静默通过。
  const issuedAtMs = 1_700_000_000_000
  for (const invalid of [undefined, null, 'not-a-time', -1, Number.NaN, Number.POSITIVE_INFINITY]) {
    assert.throws(
      () => assertExpiresNearFiveMinutes(invalid, issuedAtMs),
      /invalid epoch-second instant/,
    )
  }
  // ±skew（30s）内视为同一约五分钟到期。
  assert.doesNotThrow(() => assertExpiresNearFiveMinutes(issuedAtMs / 1000 + 270, issuedAtMs))
  assert.doesNotThrow(() => assertExpiresNearFiveMinutes(issuedAtMs / 1000 + 330, issuedAtMs))
  // 早于签发时刻（已过期）或超出 ±skew 的到期值都被拒绝。
  assert.throws(
    () => assertExpiresNearFiveMinutes(issuedAtMs / 1000 - 60, issuedAtMs),
    /expire about five minutes/,
  )
  assert.throws(
    () => assertExpiresNearFiveMinutes(issuedAtMs / 1000 + 240, issuedAtMs),
    /expire about five minutes/,
  )
  assert.throws(
    () => assertExpiresNearFiveMinutes(issuedAtMs / 1000 + 360, issuedAtMs),
    /expire about five minutes/,
  )
})
