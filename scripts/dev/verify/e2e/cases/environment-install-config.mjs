import { assert, envelopeData, expectHttpError, HttpError, cid } from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

/** Canonical non-trivial install settings shared by environment/configsync matrix cases. */
export function linuxInstallConfig() {
  return {
    operatingSystem: 'linux',
    javaHome: null,
    daemon: {
      studioUrl: 'http://127.0.0.1:18081',
      note: 'e2e install config',
      lsp: {
        servers: {
          jdtls: {
            command: ['/usr/bin/jdtls'],
            extensions: ['.java'],
            rootMarkers: ['pom.xml'],
            firstMatchMarkers: ['.git'],
          },
        },
      },
    },
  }
}

export function assertInstallConfig(config) {
  assert(config && typeof config === 'object', `installConfig must be an object: ${JSON.stringify(config)}`)
  assert(config.operatingSystem === 'linux', `operatingSystem must be linux: ${config.operatingSystem}`)
  assert(config.javaHome == null, `javaHome must stay unset: ${config.javaHome}`)
  assert(
    config.daemon?.studioUrl === 'http://127.0.0.1:18081',
    `studioUrl must round-trip: ${config.daemon?.studioUrl}`,
  )
  assert(config.daemon?.note === 'e2e install config', `note must round-trip: ${config.daemon?.note}`)
  const jdtls = config.daemon?.lsp?.servers?.jdtls
  assert(jdtls, `jdtls server must round-trip: ${JSON.stringify(config.daemon?.lsp)}`)
  assert(
    Array.isArray(jdtls.command)
      && jdtls.command.length === 1
      && jdtls.command[0] === '/usr/bin/jdtls',
    `command must round-trip: ${JSON.stringify(jdtls.command)}`,
  )
  assert(
    jdtls.extensions?.[0] === '.java'
      && jdtls.rootMarkers?.[0] === 'pom.xml'
      && jdtls.firstMatchMarkers?.[0] === '.git',
    `nested marker lists must round-trip: ${JSON.stringify(jdtls)}`,
  )
}

registerCase({
  id: 'environment.install_config_cas_roundtrip',
  level: 'L1',
  title: 'Environment installConfig PUT CAS 持久化与往返',
  docs: 'PUT /api/harness/environments/{id}/install-config 用 expectedVersion CAS 保存嵌套 installConfig； stale 版本 409、相同配置 no-op 不推进版本；硬非法 studioUrl/未知字段/缺 operatingSystem/null 配置 400 且不写入、不回显提交值，随后 GET 仍为已保存配置',
  async run(ctx) {
    const name = `e2e-install-cfg-${cid().slice(0, 8)}`
    const { json: createJson } = await ctx.call('POST', '/api/harness/environments', { name })
    const created = envelopeData(createJson)
    const environmentId = created.id
    assert(environmentId, `created environment must have id: ${JSON.stringify(createJson)}`)
    assert(
      created.installConfig == null,
      `new environment must not preconfigure install settings: ${JSON.stringify(created.installConfig)}`,
    )
    let version = String(created.version)
    const path = `/api/harness/environments/${encodeURIComponent(environmentId)}/install-config`

    try {
      // stale expectedVersion must conflict before any write
      await expectHttpError(
        () => ctx.call('PUT', path, { expectedVersion: '999999', installConfig: linuxInstallConfig() }),
        { status: 409 },
      )

      const { json: putJson } = await ctx.call('PUT', path, {
        expectedVersion: version,
        installConfig: linuxInstallConfig(),
      })
      const saved = envelopeData(putJson)
      assertInstallConfig(saved.installConfig)
      assert(
        String(saved.version) !== version,
        `persisting config must advance version (was ${version}, got ${saved.version})`,
      )
      version = String(saved.version)

      // re-saving the identical config is a CAS-checked no-op
      const { json: noopJson } = await ctx.call('PUT', path, {
        expectedVersion: version,
        installConfig: linuxInstallConfig(),
      })
      const noop = envelopeData(noopJson)
      assertInstallConfig(noop.installConfig)
      assert(
        String(noop.version) === version,
        `identical config must not advance version (was ${version}, got ${noop.version})`,
      )

      const { json: getJson } = await ctx.call(
        'GET',
        `/api/harness/environments/${encodeURIComponent(environmentId)}`,
      )
      assertInstallConfig(envelopeData(getJson).installConfig)

      const invalidBodies = [
        { expectedVersion: version, installConfig: { operatingSystem: 'linux', daemon: { studioUrl: 'ftp://nope' } } },
        {
          expectedVersion: version,
          installConfig: { operatingSystem: 'linux', daemon: { studioUrl: 'http://127.0.0.1:18081', unknown: 1 } },
        },
        { expectedVersion: version, installConfig: { javaHome: '/opt/jdk', daemon: { studioUrl: 'http://127.0.0.1:18081' } } },
        { expectedVersion: version, installConfig: null },
      ]
      for (const body of invalidBodies) {
        const error = await expectHttpError(() => ctx.call('PUT', path, body), { status: 400 })
        assert(!String(error.body).includes('ftp://nope'), 'validation error must not echo submitted values')
        assert(!String(error.body).includes('/opt/jdk'), 'validation error must not echo submitted values')
      }

      const { json: afterJson } = await ctx.call(
        'GET',
        `/api/harness/environments/${encodeURIComponent(environmentId)}`,
      )
      const after = envelopeData(afterJson)
      assert(
        String(after.version) === version,
        `invalid PUT must not advance version (was ${version}, got ${after.version})`,
      )
      assertInstallConfig(after.installConfig)

      // stale expectedVersion after a successful write also conflicts
      await expectHttpError(
        () => ctx.call('PUT', path, { expectedVersion: '0', installConfig: linuxInstallConfig() }),
        { status: 409 },
      )
    } finally {
      const { json } = await ctx.call(
        'GET',
        `/api/harness/environments/${encodeURIComponent(environmentId)}`,
      )
      const current = envelopeData(json)
      await ctx.call(
        'DELETE',
        `/api/harness/environments/${encodeURIComponent(environmentId)}?expectedVersion=${encodeURIComponent(String(current.version))}`,
      )
    }
  },
})

const INSTALL_CODE_TTL_MS = 5 * 60 * 1000
const INSTALL_CODE_SKEW_MS = 30 * 1000

function headerValue(headers, name) {
  if (!headers) return undefined
  if (typeof headers.get === 'function') return headers.get(name)
  const found = Object.keys(headers).find((key) => key.toLowerCase() === name.toLowerCase())
  return found == null ? undefined : headers[found]
}

function assertNoStore(headers, label) {
  assert(
    String(headerValue(headers, 'cache-control') ?? '').toLowerCase().includes('no-store'),
    `${label} must be Cache-Control no-store`,
  )
}

/** 失败消息只描述缺失项，避免把脚本、token 或 code 写进报告 artifact。 */
function assertScriptContains(script, needle, label) {
  assert(typeof script === 'string' && script.includes(needle), `${label} must appear in the script`)
}

function assertScriptOmits(script, needle, label) {
  assert(typeof script === 'string' && !script.includes(needle), `${label} must not appear in the script`)
}

function assertInstallScript(script, { token, config }) {
  assert(typeof script === 'string' && script.length > 0, 'install script must be non-empty text')
  assert(!script.trim().startsWith('{'), 'install script must not be a Result envelope')
  assertScriptContains(script, config.daemon.studioUrl, 'saved studioUrl')
  assertScriptContains(script, config.daemon.note, 'saved note')
  assertScriptContains(script, config.daemon.lsp.servers.jdtls.command[0], 'saved LSP command')
  assertScriptContains(script, token, 'current registration token')
  assertScriptContains(script, 'install', 'install action')
}

function assertUninstallScript(script, secrets) {
  assert(typeof script === 'string' && script.length > 0, 'uninstall script must be non-empty text')
  assert(!script.trim().startsWith('{'), 'uninstall script must not be a Result envelope')
  assertScriptContains(script, 'uninstall', 'uninstall action')
  for (const secret of secrets) {
    assertScriptOmits(script, secret, 'environment token or saved config')
  }
  assertScriptOmits(script, 'studioUrl', 'saved config field')
  assertScriptOmits(script, 'jdtls', 'saved config field')
}

/** 只报告状态，避免 expectHttpError 把可能含 code 的响应正文写进失败 artifact。 */
async function expectStatus(fn, status) {
  try {
    await fn()
  } catch (error) {
    assert(error instanceof HttpError, `expected HTTP ${status}`)
    assert(error.status === status, `expected HTTP ${status}, got ${error.status}`)
    return
  }
  assert(false, `expected HTTP ${status}`)
}

function assertExpiresNearFiveMinutes(expiresAt, issuedAtMs) {
  const expiresMs = Date.parse(expiresAt)
  assert(Number.isFinite(expiresMs), 'install code expiresAt must be parseable')
  const delta = Math.abs(expiresMs - (issuedAtMs + INSTALL_CODE_TTL_MS))
  assert(
    delta <= INSTALL_CODE_SKEW_MS,
    `install code must expire about five minutes after issue, deltaMs=${delta}`,
  )
}

registerCase({
  id: 'environment.install_script_code_roundtrip',
  level: 'L1',
  title: 'Environment 五分钟安装 code 签发、脚本下载与 token 轮换失效',
  docs: 'POST /api/harness/environments/{id}/install-code 用 expectedVersion 签发 {code,expiresAt}（no-store、无 registrationToken、约 5 分钟到期且不改 version/配置，stale 版本 409）；GET /{id}/install?code= 返回含已保存配置与当前 token 的 UTF-8 纯文本（no-store、nosniff，不执行）；缺 code/篡改 code 400；POST /{id}/registration-token 轮换后旧 code 400、新 code 脚本含新 token 且环境 id 不变；GET /uninstall/{linux,windows} 无环境 token/配置且不需要 code，非法 OS 400',
  async run(ctx) {
    const name = `e2e-install-code-${cid().slice(0, 8)}`
    const { json: createJson } = await ctx.call('POST', '/api/harness/environments', { name })
    const created = envelopeData(createJson)
    const environmentId = created.id
    const token = created.registrationToken
    assert(environmentId, 'created environment must have id')
    assert(typeof token === 'string' && token.length > 0, 'create must return a registration token')
    let version = String(created.version)
    const cardPath = `/api/harness/environments/${encodeURIComponent(environmentId)}`
    const config = linuxInstallConfig()

    try {
      const { json: putJson } = await ctx.call('PUT', `${cardPath}/install-config`, {
        expectedVersion: version,
        installConfig: config,
      })
      const saved = envelopeData(putJson)
      assertInstallConfig(saved.installConfig)
      assert(String(saved.version) !== version, 'persisting config must advance version')
      assert(!Object.hasOwn(saved, 'registrationToken'), 'install-config response must not carry registrationToken')
      version = String(saved.version)

      await expectHttpError(
        () => ctx.call('POST', `${cardPath}/install-code`, { expectedVersion: '999999' }),
        { status: 409 },
      )

      const issuedAtMs = Date.now()
      const { json: codeJson, headers: codeHeaders } = await ctx.call('POST', `${cardPath}/install-code`, {
        expectedVersion: version,
      })
      assertNoStore(codeHeaders, 'install code')
      const issued = envelopeData(codeJson)
      assert(
        issued && typeof issued === 'object' && !Array.isArray(issued),
        'install code response must be an object',
      )
      assert(!Object.hasOwn(issued, 'registrationToken'), 'install code must not include registrationToken')
      const code = issued.code
      assert(typeof code === 'string' && code.length > 0, 'install code must be non-empty text')
      assertExpiresNearFiveMinutes(issued.expiresAt, issuedAtMs)

      const { json: afterIssueJson } = await ctx.call('GET', cardPath)
      const afterIssue = envelopeData(afterIssueJson)
      assert(String(afterIssue.version) === version, 'issuing a code must not advance version')
      assertInstallConfig(afterIssue.installConfig)
      assert(
        !Object.hasOwn(afterIssue, 'registrationToken'),
        'environment detail must not include registrationToken',
      )

      const { json: script, headers: scriptHeaders } = await ctx.call(
        'GET',
        `${cardPath}/install?code=${encodeURIComponent(code)}`,
      )
      assert(typeof script === 'string', 'install download must be text, not a JSON envelope')
      assert(
        String(headerValue(scriptHeaders, 'content-type') ?? '').toLowerCase().includes('text/plain'),
        'install download must be text/plain',
      )
      assert(
        /utf-8/i.test(String(headerValue(scriptHeaders, 'content-type') ?? '')),
        'install download must declare UTF-8',
      )
      assertNoStore(scriptHeaders, 'install download')
      assert(
        String(headerValue(scriptHeaders, 'x-content-type-options') ?? '').toLowerCase() === 'nosniff',
        'install download must set nosniff',
      )
      assertInstallScript(script, { token, config })

      await expectStatus(() => ctx.call('GET', `${cardPath}/install`), 400)
      await expectStatus(
        () => ctx.call('GET', `${cardPath}/install?code=${encodeURIComponent(`${code}x`)}`),
        400,
      )

      const { json: rotatedJson, headers: rotatedHeaders } = await ctx.call(
        'POST',
        `${cardPath}/registration-token`,
        { expectedVersion: version },
      )
      assertNoStore(rotatedHeaders, 'registration token rotation')
      const rotated = envelopeData(rotatedJson)
      const rotatedToken = rotated.registrationToken
      assert(rotated.id === environmentId, 'token rotation must keep the environment id')
      assert(typeof rotatedToken === 'string' && rotatedToken.length > 0 && rotatedToken !== token, 'rotation must return a new token')
      assert(String(rotated.version) !== version, 'token rotation must advance version')
      version = String(rotated.version)
      assertInstallConfig(rotated.installConfig)

      await expectStatus(
        () => ctx.call('GET', `${cardPath}/install?code=${encodeURIComponent(code)}`),
        400,
      )

      const { json: refreshedJson, headers: refreshedHeaders } = await ctx.call(
        'POST',
        `${cardPath}/install-code`,
        { expectedVersion: version },
      )
      assertNoStore(refreshedHeaders, 'refreshed install code')
      const refreshed = envelopeData(refreshedJson)
      const refreshedCode = refreshed.code
      assert(typeof refreshedCode === 'string' && refreshedCode.length > 0 && refreshedCode !== code, 'rotation must require a new install code')
      assertExpiresNearFiveMinutes(refreshed.expiresAt, Date.now())

      const { json: refreshedScript, headers: refreshedScriptHeaders } = await ctx.call(
        'GET',
        `${cardPath}/install?code=${encodeURIComponent(refreshedCode)}`,
      )
      assertNoStore(refreshedScriptHeaders, 'refreshed install download')
      assert(
        String(headerValue(refreshedScriptHeaders, 'x-content-type-options') ?? '').toLowerCase() === 'nosniff',
        'refreshed install download must set nosniff',
      )
      assertInstallScript(refreshedScript, { token: rotatedToken, config })
      assertScriptOmits(refreshedScript, token, 'previous registration token')

      const secrets = [token, rotatedToken, code, refreshedCode, config.daemon.studioUrl, config.daemon.note]
      for (const operatingSystem of ['linux', 'windows']) {
        const { json: uninstall, headers: uninstallHeaders } = await ctx.call(
          'GET',
          `/api/harness/environments/uninstall/${operatingSystem}`,
        )
        assert(typeof uninstall === 'string', 'uninstall download must be text')
        assert(
          String(headerValue(uninstallHeaders, 'content-type') ?? '').toLowerCase().includes('text/plain'),
          'uninstall download must be text/plain',
        )
        assert(
          /utf-8/i.test(String(headerValue(uninstallHeaders, 'content-type') ?? '')),
          'uninstall download must declare UTF-8',
        )
        assertNoStore(uninstallHeaders, 'uninstall download')
        assert(
          String(headerValue(uninstallHeaders, 'x-content-type-options') ?? '').toLowerCase() === 'nosniff',
          'uninstall download must set nosniff',
        )
        assertUninstallScript(uninstall, secrets)
      }
      await expectHttpError(
        () => ctx.call('GET', '/api/harness/environments/uninstall/solaris'),
        { status: 400 },
      )
    } finally {
      const { json } = await ctx.call('GET', cardPath)
      const current = envelopeData(json)
      await ctx.call(
        'DELETE',
        `${cardPath}?expectedVersion=${encodeURIComponent(String(current.version))}`,
      )
    }
  },
})
