import {
  HttpError,
  assert,
  assertDecimalVersion,
  envelopeData,
  expectHttpError,
} from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

const SECTION_NAMES = [
  'tool',
  'aiRuntime',
  'environment',
  'network',
  'integrations',
  'storageMedia',
  'advanced',
]

function updateBody(settings, expectedVersion = settings.version) {
  return {
    tool: settings.tool,
    aiRuntime: settings.aiRuntime,
    environment: settings.environment,
    network: settings.network,
    integrations: settings.integrations,
    storageMedia: settings.storageMedia,
    advanced: settings.advanced,
    expectedVersion,
  }
}

/** Long 字段 wire 形态为十进制字符串：TTL/扫描间隔必须是正整数字符串（非 0，也绝不是 number）。 */
function assertPositiveDecimal(value, label) {
  assert(
    typeof value === 'string' && /^[1-9][0-9]*$/.test(value),
    `${label} must be a positive decimal string: ${JSON.stringify(value)}`,
  )
}

/** 空名单允许禁用 HTTP 重试，非空项必须是互不重复的 400–599 整数。 */
function assertRetryStatusCodes(list, label) {
  assert(
    Array.isArray(list),
    `${label} must be an array: ${JSON.stringify(list)}`,
  )
  assert(new Set(list).size === list.length, `${label} must not contain duplicates`)
  for (const code of list) {
    assert(
      Number.isInteger(code) && code >= 400 && code <= 599,
      `${label} must contain only 400-599 integers: ${JSON.stringify(list)}`,
    )
  }
}

registerCase({
  id: 'settings.system_contract_cas',
  level: 'L1',
  title: 'SystemSettings 完整聚合、严格校验、CAS 与恢复',
  docs:
    'GET 七 section + decimal-string Long/version，aiRuntime.modelHttpRetryStatusCodes 为 400–599 整数数组，storageMedia.tmp TTL/扫描间隔为正整数字符串；PUT 全局代理与重试名单 roundtrip；非法代理/凭据/重试名单形状和缺 network 400 fail-closed；严格权限和 CAS；finally 用最新版本恢复原值',
  async run(ctx) {
    const readSettings = async () => {
      const { json } = await ctx.call('GET', '/api/settings')
      return envelopeData(json)
    }

    const before = await readSettings()
    for (const section of SECTION_NAMES) {
      assert(before[section] != null, `missing SystemSettings section ${section}`)
    }
    assertDecimalVersion(before.version, 'system settings version')
    assertDecimalVersion(
      before.aiRuntime.retryBaseDelayMillis,
      'aiRuntime.retryBaseDelayMillis',
    )
    assert(
      !Object.hasOwn(before.integrations.minimaxH3, 'presignExpirySeconds'),
      'dead minimaxH3.presignExpirySeconds must not remain on the wire',
    )
    assert(
      !Object.hasOwn(before.storageMedia, 'canvasUploadExpiryMillis'),
      'dead storageMedia.canvasUploadExpiryMillis must not remain on the wire',
    )
    // 系统默认值必须存在且形状合法：HTTP 重试白名单是 400–599 整数数组，tmp TTL/扫描间隔是正整数字符串。
    assertRetryStatusCodes(
      before.aiRuntime.modelHttpRetryStatusCodes,
      'aiRuntime.modelHttpRetryStatusCodes',
    )
    assertPositiveDecimal(
      before.storageMedia.temporaryResourceTtlSeconds,
      'storageMedia.temporaryResourceTtlSeconds',
    )
    assertPositiveDecimal(
      before.storageMedia.temporaryResourceCleanupIntervalSeconds,
      'storageMedia.temporaryResourceCleanupIntervalSeconds',
    )

    const original = before.aiRuntime.retryBaseDelayMillis
    const changed = original === '2501' ? '2502' : '2501'
    const originalNetwork = structuredClone(before.network)
    const originalRetryCodes = before.aiRuntime.modelHttpRetryStatusCodes.slice()
    // 选择一份与当前默认不同的合法名单，确保 CAS roundtrip 真正证明持久化。
    const changedRetryCodes = JSON.stringify(originalRetryCodes) === JSON.stringify([429, 503])
      ? [429, 503, 504]
      : [429, 503]
    try {
      const update = updateBody(structuredClone(before))
      update.aiRuntime.retryBaseDelayMillis = changed
      update.aiRuntime.modelHttpRetryStatusCodes = changedRetryCodes
      // 仅保存重启生效配置，不连接代理、不更改运行期客户端。
      update.network = { proxyUrl: 'http://proxy.example.test:3128', noProxyHosts: '' }
      const { json } = await ctx.call('PUT', '/api/settings', update)
      const saved = envelopeData(json)
      assertDecimalVersion(saved.version, 'saved system settings version')
      assert(saved.version !== before.version, 'SystemSettings CAS PUT must advance version')
      assert(
        saved.aiRuntime.retryBaseDelayMillis === changed,
        'SystemSettings change was not persisted',
      )
      const reread = await readSettings()
      for (const actual of [saved, reread]) {
        assert(
          actual.network.proxyUrl === update.network.proxyUrl && actual.network.noProxyHosts === '',
          'global network proxy did not roundtrip',
        )
        assert(
          JSON.stringify(actual.aiRuntime.modelHttpRetryStatusCodes) ===
            JSON.stringify(changedRetryCodes),
          'aiRuntime.modelHttpRetryStatusCodes did not roundtrip',
        )
      }
      // 非法 URL / 凭据 / 缺 section 都不得推进版本或改变已保存代理。
      for (const proxyUrl of [
        'http://proxy.example.test',
        'https://proxy.example.test:3128',
        'socks5://proxy.example.test:1080',
        'http://synthetic-user:synthetic-password@proxy.example.test:3128',
        'http://proxy.example.test:3128/path',
      ]) {
        const invalid = updateBody(structuredClone(saved))
        invalid.network.proxyUrl = proxyUrl
        const error = await expectHttpError(() => ctx.call('PUT', '/api/settings', invalid), { status: 400 })
        assert(
          !String(error.body).includes('synthetic-user') &&
            !String(error.body).includes('synthetic-password'),
          'network validation error must not echo proxy credentials',
        )
      }
      const missingNetwork = updateBody(structuredClone(saved))
      delete missingNetwork.network
      await expectHttpError(() => ctx.call('PUT', '/api/settings', missingNetwork), { status: 400 })
      const afterInvalid = await readSettings()
      assert(
        afterInvalid.version === saved.version &&
          afterInvalid.network.proxyUrl === saved.network.proxyUrl &&
          afterInvalid.network.noProxyHosts === saved.network.noProxyHosts,
        'invalid network update must leave persisted settings unchanged',
      )
      // 重试名单严格整数形状：非数组/小数/字符串/越界/重复/null 元素一律 400，且不推进版本、不改已保存名单。
      for (const invalidList of [
        429,
        '429',
        429.5,
        [429.5],
        ['429'],
        [399],
        [600],
        [429, 429],
        [null],
        {},
      ]) {
        const invalid = updateBody(structuredClone(saved))
        invalid.aiRuntime.modelHttpRetryStatusCodes = invalidList
        await expectHttpError(() => ctx.call('PUT', '/api/settings', invalid), { status: 400 })
      }
      const afterInvalidRetry = await readSettings()
      assert(
        afterInvalidRetry.version === saved.version &&
          JSON.stringify(afterInvalidRetry.aiRuntime.modelHttpRetryStatusCodes) ===
            JSON.stringify(changedRetryCodes),
        'invalid retry status list must leave persisted settings unchanged',
      )

      const badPermission = updateBody(structuredClone(saved))
      badPermission.tool.permission[' write'] = badPermission.tool.permission['write']
      delete badPermission.tool.permission['write']
      await expectHttpError(() => ctx.call('PUT', '/api/settings', badPermission), {
        status: 400,
      })
      assert(
        (await readSettings()).version === saved.version,
        'validation failure must not advance SystemSettings version',
      )

      await expectHttpError(
        () =>
          ctx.call(
            'PUT',
            '/api/settings',
            updateBody(structuredClone(saved), before.version),
          ),
        { status: 409 },
      )
      assert(
        (await readSettings()).version === saved.version,
        'CAS conflict must not advance SystemSettings version',
      )
    } finally {
      for (let attempt = 0; attempt < 5; attempt++) {
        const latest = await readSettings()
        if (
          latest.aiRuntime.retryBaseDelayMillis === original &&
          JSON.stringify(latest.aiRuntime.modelHttpRetryStatusCodes) ===
            JSON.stringify(originalRetryCodes) &&
          (latest.network.proxyUrl ?? null) === (originalNetwork.proxyUrl ?? null) &&
          latest.network.noProxyHosts === originalNetwork.noProxyHosts
        ) {
          break
        }
        const restore = updateBody(structuredClone(latest))
        restore.aiRuntime.retryBaseDelayMillis = original
        restore.aiRuntime.modelHttpRetryStatusCodes = originalRetryCodes
        restore.network = originalNetwork
        try {
          await ctx.call('PUT', '/api/settings', restore)
          break
        } catch (error) {
          if (!(error instanceof HttpError) || error.status !== 409) {
            throw error
          }
        }
      }
      const restored = await readSettings()
      assert(
        restored.aiRuntime.retryBaseDelayMillis === original,
        `SystemSettings restore retries exhausted: ${restored.aiRuntime.retryBaseDelayMillis}`,
      )
      assert(
        JSON.stringify(restored.aiRuntime.modelHttpRetryStatusCodes) ===
          JSON.stringify(originalRetryCodes),
        `SystemSettings retry status restore retries exhausted: ${restored.aiRuntime.modelHttpRetryStatusCodes}`,
      )
      assert(
        (restored.network.proxyUrl ?? null) === (originalNetwork.proxyUrl ?? null) &&
          restored.network.noProxyHosts === originalNetwork.noProxyHosts,
        'SystemSettings network restore retries exhausted',
      )
    }
  },
})
