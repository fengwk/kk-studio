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
  'integrations',
  'storageMedia',
  'advanced',
]

function updateBody(settings, expectedVersion = settings.version) {
  return {
    tool: settings.tool,
    aiRuntime: settings.aiRuntime,
    environment: settings.environment,
    integrations: settings.integrations,
    storageMedia: settings.storageMedia,
    advanced: settings.advanced,
    expectedVersion,
  }
}

registerCase({
  id: 'settings.system_contract_cas',
  level: 'L1',
  title: 'SystemSettings 完整聚合、严格校验、CAS 与恢复',
  docs:
    'GET 六 section + decimal-string Long/version；PUT 完整聚合推进版本；首尾空白权限 400、stale version 409 且不推进；finally 用最新版本恢复原值',
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

    const original = before.aiRuntime.retryBaseDelayMillis
    const changed = original === '2501' ? '2502' : '2501'
    try {
      const update = updateBody(structuredClone(before))
      update.aiRuntime.retryBaseDelayMillis = changed
      const { json } = await ctx.call('PUT', '/api/settings', update)
      const saved = envelopeData(json)
      assertDecimalVersion(saved.version, 'saved system settings version')
      assert(saved.version !== before.version, 'SystemSettings CAS PUT must advance version')
      assert(
        saved.aiRuntime.retryBaseDelayMillis === changed,
        'SystemSettings change was not persisted',
      )

      const badPermission = updateBody(structuredClone(saved))
      badPermission.tool.permission[' write'] = badPermission.tool.permission.write
      delete badPermission.tool.permission.write
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
        if (latest.aiRuntime.retryBaseDelayMillis === original) {
          break
        }
        const restore = updateBody(structuredClone(latest))
        restore.aiRuntime.retryBaseDelayMillis = original
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
    }
  },
})
