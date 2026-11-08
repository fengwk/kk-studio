import { assert, assertExactFields, expectHttpError, HttpError, cid } from '../lib/http.mjs'
import { assertDaemonConfig, daemonConfigFixture, environmentCreateBody } from '../lib/fixtures.mjs'
import { createCaseAdder } from '../lib/registry.mjs'

export const cases = []
const registerCase = createCaseAdder(cases)

registerCase({
  id: 'environment.card_config_cas_roundtrip',
  level: 'L1',
  title: 'Environment 卡片 daemonConfig PUT CAS 持久化与往返',
  docs: 'POST /api/platform/environments 必须携带 name/note/完整 daemonConfig；PUT /api/platform/environments/{id} 用 expectedVersion 整体替换 note+daemonConfig，stale 版本 409，显式成功 PUT（含相同内容）推进版本；非法/缺失 daemonConfig、未知字段、非法 studioUrl 400 且不写入、不回显提交值；GET 返回同一卡片配置且无旧 installConfig',
  async run(ctx) {
    const name = `e2e-card-cfg-${cid().slice(0, 8)}`
    const note = 'e2e environment note'
    const { json: created } = await ctx.call(
      'POST',
      '/api/platform/environments',
      environmentCreateBody(name, { note }),
    )
    const environmentId = created.id
    assert(environmentId, `created environment must have id: ${JSON.stringify(created)}`)
    assert(created.name === name, `created environment name mismatch: ${JSON.stringify(created)}`)
    assert(created.note === note, `created environment note mismatch: ${JSON.stringify(created)}`)
    assertDaemonConfig(created.daemonConfig)
    assert(
      !Object.hasOwn(created, 'installConfig'),
      `new environment must not expose removed installConfig: ${JSON.stringify(created)}`,
    )
    let version = String(created.version)
    const path = `/api/platform/environments/${encodeURIComponent(environmentId)}`

    try {
      // stale expectedVersion must conflict before any write
      await expectHttpError(
        () =>
          ctx.call('PUT', path, {
            expectedVersion: '999999',
            note,
            daemonConfig: daemonConfigFixture(),
          }),
        { status: 409, code: 'version_conflict' },
      )

      const { json: putJson } = await ctx.call('PUT', path, {
        expectedVersion: version,
        note,
        daemonConfig: daemonConfigFixture(),
      })
      const saved = putJson
      assert(saved.id === environmentId, 'PUT must keep the card identity')
      assertDaemonConfig(saved.daemonConfig)
      assert(
        String(saved.version) !== version,
        `persisting config must advance version (was ${version}, got ${saved.version})`,
      )
      version = String(saved.version)

      // Every explicit successful PUT advances the version, even when note/config are unchanged.
      const { json: repeatedJson } = await ctx.call('PUT', path, {
        expectedVersion: version,
        note,
        daemonConfig: daemonConfigFixture(),
      })
      const repeated = repeatedJson
      assertDaemonConfig(repeated.daemonConfig)
      assert(
        String(repeated.version) === String(BigInt(version) + 1n),
        `explicit PUT must advance version exactly once (was ${version}, got ${repeated.version})`,
      )
      version = String(repeated.version)

      const { json: getJson } = await ctx.call('GET', path)
      assertDaemonConfig(getJson.daemonConfig)
      assert(getJson.note === note, `note must round-trip: ${JSON.stringify(getJson)}`)

      const invalidBodies = [
        { expectedVersion: version, note, daemonConfig: null },
        { expectedVersion: version, note },
        {
          expectedVersion: version,
          note,
          daemonConfig: daemonConfigFixture({ studioUrl: 'ftp://nope' }),
        },
        {
          expectedVersion: version,
          note,
          daemonConfig: daemonConfigFixture({ unknown: '/opt/jdk' }),
        },
        {
          expectedVersion: version,
          note,
          daemonConfig: daemonConfigFixture(),
          unknownField: '/opt/jdk',
        },
      ]
      for (const body of invalidBodies) {
        const error = await expectHttpError(() => ctx.call('PUT', path, body), {
          status: 400,
          code: 'validation',
        })
        assert(!String(error.body).includes('ftp://nope'), 'validation error must not echo submitted values')
        assert(!String(error.body).includes('/opt/jdk'), 'validation error must not echo submitted values')
      }

      const { json: afterJson } = await ctx.call('GET', path)
      const after = afterJson
      assert(
        String(after.version) === version,
        `invalid PUT must not advance version (was ${version}, got ${after.version})`,
      )
      assertDaemonConfig(after.daemonConfig)

      // stale expectedVersion after successful writes also conflicts
      await expectHttpError(
        () =>
          ctx.call('PUT', path, {
            expectedVersion: '0',
            note,
            daemonConfig: daemonConfigFixture(),
          }),
        { status: 409, code: 'version_conflict' },
      )
    } finally {
      const { json } = await ctx.call('GET', path)
      const current = json
      await ctx.call(
        'DELETE',
        `${path}?expectedVersion=${encodeURIComponent(String(current.version))}`,
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

/** 从安装脚本中提取唯一写入 daemon.json 的 canonical 本地缓存信封。 */
function installEnvelope(script) {
  const match = /^builtin printf '%s' '(.*)' > "\$stage\/daemon\.json"$/m.exec(script)
  assert(match, 'install script must stage one daemon.json envelope')
  try {
    return JSON.parse(match[1])
  } catch {
    assert(false, 'install script daemon.json must be valid JSON')
  }
}

function assertInstallScript(
  script,
  { previousTokens, environmentId, expectedVersion, note },
) {
  assert(typeof script === 'string' && script.length > 0, 'install script must be non-empty text')
  assert(!script.trim().startsWith('{'), 'install script must not be a Result envelope')
  const envelope = installEnvelope(script)
  assertExactFields(envelope, ['environmentId', 'version', 'daemonConfig'], 'daemon.json envelope')
  assert(envelope.environmentId === environmentId, 'daemon.json must carry the card UUID')
  assert(
    envelope.version === expectedVersion,
    `daemon.json version mismatch: expected ${expectedVersion}, got ${envelope.version}`,
  )
  assertDaemonConfig(envelope.daemonConfig)
  assert(
    !Object.hasOwn(envelope.daemonConfig, 'note'),
    'card note must not be duplicated into daemonConfig',
  )
  assertScriptOmits(script, note, 'card note')
  assertScriptContains(script, 'install --environment-id', 'install action')
  assertScriptContains(script, environmentId, 'card UUID installer argument')
  const tokens = [...script.matchAll(/^builtin printf '%s' '([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})' > "\$stage\/daemon\.token"$/gm)]
  assert(tokens.length === 1, 'install script must stage exactly one UUID registration token')
  const token = tokens[0][1]
  assert(!previousTokens.includes(token), 'redemption must generate a fresh registration token')
  return token
}

export function assertUninstallScript(script, { environmentId, secrets, operatingSystem }) {
  assert(typeof script === 'string' && script.length > 0, 'uninstall script must be non-empty text')
  assert(!script.trim().startsWith('{'), 'uninstall script must not be a Result envelope')
  assert(
    operatingSystem === 'linux' || operatingSystem === 'macos' || operatingSystem === 'windows',
    `unsupported uninstall operatingSystem: ${operatingSystem}`,
  )
  const action = operatingSystem === 'windows'
    ? `& powershell -NoProfile -ExecutionPolicy Bypass -File $installer -Command uninstall -EnvironmentId '${environmentId}'`
    : `bash "$stage/install.sh" uninstall --environment-id '${environmentId}'`
  assertScriptContains(script, action, `${operatingSystem} uninstall action with card UUID`)
  assertScriptContains(script, environmentId, 'uninstall card UUID')
  for (const secret of secrets) {
    assertScriptOmits(script, secret, 'environment token, note or saved config')
  }
  assertScriptOmits(script, 'studioUrl', 'saved config field')
  assertScriptOmits(script, 'jdtls', 'saved config field')
}

/** 下载失败只保留状态与 problem 机器字段，避免 code、响应正文进入失败报告。 */
async function downloadInstallationScript(ctx, path) {
  try {
    return await ctx.call('GET', path)
  } catch (error) {
    if (error instanceof HttpError) {
      let problem = null
      try {
        problem = JSON.parse(error.body)
      } catch {
        problem = null
      }
      const sanitized = JSON.stringify({
        type: 'about:blank',
        status: error.status,
        code: problem?.code ?? null,
        reason: problem?.reason ?? null,
      })
      throw new HttpError(error.status, sanitized, path.split('?')[0])
    }
    throw new Error('install script download failed before a usable HTTP response')
  }
}

/** 锁定失败状态与 problem code；失败消息不回显响应正文。 */
async function expectProblem(fn, { status, code }) {
  try {
    await fn()
  } catch (error) {
    assert(error instanceof HttpError, `expected HTTP ${status}`)
    assert(error.status === status, `expected HTTP ${status}, got ${error.status}`)
    let problem = null
    try {
      problem = JSON.parse(error.body)
    } catch {
      problem = null
    }
    assert(problem?.code === code, `expected problem code ${code}, got ${problem?.code}`)
    return
  }
  assert(false, `expected HTTP ${status}`)
}

/**
 * 解析 install code 的真实 wire 时刻：全局 Jackson 开启 `WRITE_DATES_AS_TIMESTAMPS` 且写毫秒，`Instant`
 * 序列化为 epoch 毫秒整数。
 */
function instantMillis(value) {
  assert(
    typeof value === 'number' && Number.isInteger(value) && value >= 0,
    `invalid epoch-millisecond instant: ${value}`,
  )
  return value
}

/** 校验 code 严格在签发后约五分钟过期（有限时刻 + ±skew）。 */
export function assertExpiresNearFiveMinutes(expiresAt, issuedAtMs) {
  const expiresMs = instantMillis(expiresAt)
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
  title: 'Environment 五分钟安装 code 签发、按卡片脚本下载与 token 轮换失效',
  docs: 'POST /api/platform/environments/{id}/install-codes 用 expectedVersion 签发一次性 {code,expiresAt}（no-store、无 registrationToken、约 5 分钟到期且不改 version/配置，stale 版本 409），重签覆盖旧 code；GET /{id}/install-script 必须同时带 code 与 operatingSystem，兑换生成 fresh UUID token 并推进 version，保留卡片 note+daemonConfig 且 detail 无 token；Linux UTF-8 纯文本含 {environmentId,version,daemonConfig} 本地缓存信封与按卡片 install 命令（no-store、nosniff，只下载不执行）；重复/缺失/篡改 code、缺失/非法 OS 400；POST /{id}/rotate-token 使未消费 code 失效，重签兑换 token 与此前均不同；GET /{id}/uninstall-script 带 operatingSystem，无 secret/配置且不需要 code，非法 OS 400',
  async run(ctx) {
    const name = `e2e-install-code-${cid().slice(0, 8)}`
    const note = 'e2e environment note'
    const config = daemonConfigFixture()
    const { json: created } = await ctx.call(
      'POST',
      '/api/platform/environments',
      environmentCreateBody(name, { note }),
    )
    const environmentId = created.id
    const token = created.registrationToken
    assert(environmentId, 'created environment must have id')
    assert(typeof token === 'string' && token.length > 0, 'create must return a registration token')
    assertDaemonConfig(created.daemonConfig)
    let version = String(created.version)
    const environmentPath = `/api/platform/environments/${encodeURIComponent(environmentId)}`

    try {
      const { json: putJson } = await ctx.call('PUT', environmentPath, {
        expectedVersion: version,
        note,
        daemonConfig: config,
      })
      const saved = putJson
      assertDaemonConfig(saved.daemonConfig)
      assert(saved.note === note, 'card note must round-trip through PUT')
      assert(String(saved.version) !== version, 'persisting config must advance version')
      assert(!Object.hasOwn(saved, 'registrationToken'), 'card update response must not carry registrationToken')
      version = String(saved.version)

      await expectHttpError(
        () => ctx.call('POST', `${environmentPath}/install-codes`, { expectedVersion: '999999' }),
        { status: 409, code: 'version_conflict' },
      )

      const issuedAtMs = Date.now()
      const { json: codeJson, headers: codeHeaders } = await ctx.call('POST', `${environmentPath}/install-codes`, {
        expectedVersion: version,
      })
      assertNoStore(codeHeaders, 'install code')
      const issued = codeJson
      assert(
        issued && typeof issued === 'object' && !Array.isArray(issued),
        'install code response must be an object',
      )
      assert(!Object.hasOwn(issued, 'registrationToken'), 'install code must not include registrationToken')
      const code = issued.code
      assert(typeof code === 'string' && code.length > 0, 'install code must be non-empty text')
      assertExpiresNearFiveMinutes(issued.expiresAt, issuedAtMs)

      const { json: afterIssueJson } = await ctx.call('GET', environmentPath)
      const afterIssue = afterIssueJson
      assert(String(afterIssue.version) === version, 'issuing a code must not advance version')
      assertDaemonConfig(afterIssue.daemonConfig)
      assert(
        !Object.hasOwn(afterIssue, 'registrationToken'),
        'environment detail must not include registrationToken',
      )

      // operatingSystem is a mandatory install-command parameter and is validated before redemption.
      await expectProblem(
        () =>
          downloadInstallationScript(
            ctx,
            `${environmentPath}/install-script?code=${encodeURIComponent(code)}`,
          ),
        { status: 400, code: 'validation' },
      )
      await expectProblem(
        () =>
          downloadInstallationScript(
            ctx,
            `${environmentPath}/install-script?code=${encodeURIComponent(code)}&operatingSystem=solaris`,
          ),
        { status: 400, code: 'validation' },
      )

      const redeemedVersion = String(BigInt(version) + 1n)
      const { json: script, headers: scriptHeaders } = await downloadInstallationScript(
        ctx,
        `${environmentPath}/install-script?code=${encodeURIComponent(code)}&operatingSystem=linux`,
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
      const downloadedToken = assertInstallScript(script, {
        previousTokens: [token],
        environmentId,
        expectedVersion: redeemedVersion,
        note,
      })
      const checkRedemption = async () => {
        const { json } = await ctx.call('GET', environmentPath)
        const detail = json
        assert(
          BigInt(detail.version) === BigInt(version) + 1n,
          `redemption must advance version once (was ${version}, got ${detail.version})`,
        )
        assertDaemonConfig(detail.daemonConfig)
        assert(detail.note === note, 'redemption must keep the card note')
        assert(!Object.hasOwn(detail, 'registrationToken'), 'detail must not carry registrationToken')
        version = String(detail.version)
      }
      await checkRedemption()
      await expectProblem(
        () =>
          downloadInstallationScript(
            ctx,
            `${environmentPath}/install-script?code=${encodeURIComponent(code)}&operatingSystem=linux`,
          ),
        { status: 400, code: 'validation' },
      )

      await expectProblem(
        () =>
          downloadInstallationScript(
            ctx,
            `${environmentPath}/install-script?operatingSystem=linux`,
          ),
        { status: 400, code: 'validation' },
      )
      await expectProblem(
        () =>
          downloadInstallationScript(
            ctx,
            `${environmentPath}/install-script?code=${encodeURIComponent(`${code}x`)}&operatingSystem=linux`,
          ),
        { status: 400, code: 'validation' },
      )

      const issueCode = async () => {
        const { json, headers } = await ctx.call('POST', `${environmentPath}/install-codes`, { expectedVersion: version })
        assertNoStore(headers, 'install code')
        const issued = json
        assert(typeof issued.code === 'string' && issued.code.length > 0, 'install code must be non-empty')
        assertExpiresNearFiveMinutes(issued.expiresAt, Date.now())
        return issued.code
      }
      const replacedCode = await issueCode()
      const pendingCode = await issueCode()
      assert(pendingCode !== replacedCode, 'reissue must replace the pending code')
      await expectProblem(
        () =>
          downloadInstallationScript(
            ctx,
            `${environmentPath}/install-script?code=${encodeURIComponent(replacedCode)}&operatingSystem=linux`,
          ),
        { status: 400, code: 'validation' },
      )

      const { json: rotatedJson, headers: rotatedHeaders } = await ctx.call(
        'POST',
        `${environmentPath}/rotate-token`,
        { expectedVersion: version },
      )
      assertNoStore(rotatedHeaders, 'registration token rotation')
      const rotated = rotatedJson
      const rotatedToken = rotated.registrationToken
      assert(rotated.id === environmentId, 'token rotation must keep the environment id')
      assert(typeof rotatedToken === 'string' && rotatedToken.length > 0 && rotatedToken !== token, 'rotation must return a new token')
      assert(String(rotated.version) !== version, 'token rotation must advance version')
      version = String(rotated.version)
      assertDaemonConfig(rotated.daemonConfig)
      assert(rotated.note === note, 'token rotation must keep the card note')

      await expectProblem(
        () =>
          downloadInstallationScript(
            ctx,
            `${environmentPath}/install-script?code=${encodeURIComponent(pendingCode)}&operatingSystem=linux`,
          ),
        { status: 400, code: 'validation' },
      )

      const { json: refreshedJson, headers: refreshedHeaders } = await ctx.call(
        'POST',
        `${environmentPath}/install-codes`,
        { expectedVersion: version },
      )
      assertNoStore(refreshedHeaders, 'refreshed install code')
      const refreshed = refreshedJson
      const refreshedCode = refreshed.code
      assert(typeof refreshedCode === 'string' && refreshedCode.length > 0 && refreshedCode !== code, 'rotation must require a new install code')
      assertExpiresNearFiveMinutes(refreshed.expiresAt, Date.now())

      const refreshedVersion = String(BigInt(version) + 1n)
      const { json: refreshedScript, headers: refreshedScriptHeaders } = await downloadInstallationScript(
        ctx,
        `${environmentPath}/install-script?code=${encodeURIComponent(refreshedCode)}&operatingSystem=linux`,
      )
      assertNoStore(refreshedScriptHeaders, 'refreshed install download')
      assert(
        String(headerValue(refreshedScriptHeaders, 'x-content-type-options') ?? '').toLowerCase() === 'nosniff',
        'refreshed install download must set nosniff',
      )
      const refreshedToken = assertInstallScript(refreshedScript, {
        previousTokens: [token, rotatedToken, downloadedToken],
        environmentId,
        expectedVersion: refreshedVersion,
        note,
      })
      await checkRedemption()
      assertScriptOmits(refreshedScript, token, 'previous registration token')

      const secrets = [
        token,
        rotatedToken,
        downloadedToken,
        refreshedToken,
        code,
        replacedCode,
        pendingCode,
        refreshedCode,
        config.studioUrl,
        note,
      ]
      for (const operatingSystem of ['linux', 'windows']) {
        const { json: uninstall, headers: uninstallHeaders } = await ctx.call(
          'GET',
          `${environmentPath}/uninstall-script?operatingSystem=${operatingSystem}`,
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
        assertUninstallScript(uninstall, { environmentId, secrets, operatingSystem })
      }
      await expectHttpError(
        () => ctx.call('GET', `${environmentPath}/uninstall-script?operatingSystem=solaris`),
        { status: 400, code: 'validation' },
      )
    } finally {
      const { json } = await ctx.call('GET', environmentPath)
      const current = json
      await ctx.call(
        'DELETE',
        `${environmentPath}?expectedVersion=${encodeURIComponent(String(current.version))}`,
      )
    }
  },
})
