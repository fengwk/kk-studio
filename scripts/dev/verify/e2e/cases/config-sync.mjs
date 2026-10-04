import { assert, envelopeData, assertExactFields, cid } from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'
import { providerCreateBody } from '../lib/fixtures.mjs'

registerCase({
  id: 'config_sync.inventory_contract',
  level: 'L1',
  title: '配置同步 inventory 契约校验',
  docs: 'GET /api/settings/sync 返回仅含引用（kind/name）与依赖闭包（dependencies）的 inventory；每项严格限定字段，且响应中绝不包含凭据、token、headers、url 等敏感配置值',
  async run(ctx) {
    const { json } = await ctx.call('GET', '/api/settings/sync')
    const data = envelopeData(json)
    const rawJson = JSON.stringify(data)

    const prohibitedKeyPattern = /"(credential|registrationToken|headers|url)"\s*:/i
    assert(
      !prohibitedKeyPattern.test(rawJson),
      'inventory response must not contain prohibited secret-bearing keys',
    )

    const items = data?.items
    assert(Array.isArray(items), 'inventory items must be an array')
    for (const item of items) {
      assertExactFields(item, ['dependencies', 'kind', 'name'], 'inventory item')
      assert(Array.isArray(item.dependencies), 'item dependencies must be an array')
      for (const dep of item.dependencies) {
        assertExactFields(dep, ['kind', 'name'], 'dependency ref')
      }
    }
  },
})

registerCase({
  id: 'config_sync.provider_roundtrip_same_name',
  level: 'L1',
  title: '配置同步 Provider 同名导出导入往返',
  docs: '验证 Provider 导出产生非空包含名称的 YAML，导入同名配置成功写入 imported 且不被 skipped，最后以最新 CAS 版本清理',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const body = providerCreateBody(suffix)
    let providerName = null
    let latestVersion = null

    const { json: createJson } = await ctx.call('POST', '/api/ai/catalog/providers', body)
    const created = envelopeData(createJson)
    providerName = created.name
    latestVersion = String(created.version)
    assert(providerName, 'created provider must have name')
    assert(latestVersion, 'created provider must have version')

    try {
      const { json: exportJson } = await ctx.call('POST', '/api/settings/sync/export', {
        items: [{ kind: 'providers', name: providerName }],
      })
      const exportData = envelopeData(exportJson)
      const yaml = exportData?.yaml
      assert(typeof yaml === 'string' && yaml.trim().length > 0, 'export yaml must be non-empty string')
      assert(yaml.includes(providerName), `export yaml must contain provider name ${providerName}`)

      const { json: importJson } = await ctx.call('POST', '/api/settings/sync/import', { yaml })
      const importResult = envelopeData(importJson)
      assert(Array.isArray(importResult?.imported), 'import result must contain imported array')
      const importedProvider = importResult.imported.find(
        (item) => item.kind === 'providers' && item.name === providerName,
      )
      assert(importedProvider, `imported must contain {kind:'providers',name:${providerName}}`)

      const skippedList = Array.isArray(importResult?.skipped) ? importResult.skipped : []
      const skippedProvider = skippedList.find(
        (item) => item.name === providerName && (item.kind === 'providers' || item.kind === 'PROVIDERS'),
      )
      assert(!skippedProvider, `skipped must not contain provider ${providerName}`)
    } finally {
      if (providerName) {
        try {
          let versionToDelete = latestVersion
          try {
            const { json } = await ctx.call('GET', '/api/ai/catalog/providers?pageNumber=1&pageSize=100')
            const listData = envelopeData(json)
            const list = Array.isArray(listData?.results)
              ? listData.results
              : Array.isArray(listData)
                ? listData
                : []
            const found = list.find((p) => p.name === providerName)
            if (found?.version != null) {
              versionToDelete = String(found.version)
            }
          } catch {
            // fallback to latestVersion
          }
          if (versionToDelete) {
            await ctx.call(
              'DELETE',
              `/api/ai/catalog/providers/${encodeURIComponent(providerName)}?expectedVersion=${encodeURIComponent(versionToDelete)}`,
            )
          }
        } catch {
          // best-effort cleanup
        }
      }
    }
  },
})

registerCase({
  id: 'config_sync.environment_identity_and_token',
  level: 'L1',
  title: '配置同步 Environment 身份稳定性与凭据保留',
  docs: '同名 Environment 导出包含 registrationToken；再次导入不生成新 UUID 且保留原有 registrationToken 与单行唯一性',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const envName = `e2e-sync-env-${suffix}`
    let envId = null
    let latestVersion = null

    const { json: createJson } = await ctx.call('POST', '/api/harness/environments', {
      name: envName,
    })
    const created = envelopeData(createJson)
    envId = created.id
    const originalName = created.name
    const originalToken = created.registrationToken
    latestVersion = String(created.version)

    assert(envId, 'created environment must have id')
    assert(originalName === envName, `created environment name mismatch: expected ${envName}, got ${originalName}`)
    assert(originalToken, 'created environment must have registrationToken')
    assert(latestVersion, 'created environment must have version')

    try {
      const { json: exportJson } = await ctx.call('POST', '/api/settings/sync/export', {
        items: [{ kind: 'environments', name: envName }],
      })
      const exportData = envelopeData(exportJson)
      const yaml = exportData?.yaml
      assert(typeof yaml === 'string' && yaml.trim().length > 0, 'export yaml must be non-empty string')
      assert(yaml.includes(envName), `export yaml must contain environment name ${envName}`)
      assert(yaml.includes(originalToken), 'export yaml must contain environment registrationToken')

      const { json: importJson } = await ctx.call('POST', '/api/settings/sync/import', { yaml })
      const importResult = envelopeData(importJson)
      assert(Array.isArray(importResult?.imported), 'import result must contain imported array')
      const importedEnv = importResult.imported.find(
        (item) => item.kind === 'environments' && item.name === envName,
      )
      assert(importedEnv, `imported must contain {kind:'environments',name:${envName}}`)

      const { json: tokenJson } = await ctx.call(
        'GET',
        `/api/harness/environments/${encodeURIComponent(envId)}/token`,
      )
      const tokenData = envelopeData(tokenJson)
      assert(
        tokenData?.id === envId,
        `environment id must match original id: expected ${envId}, got ${tokenData?.id}`,
      )
      assert(
        tokenData?.registrationToken === originalToken,
        'environment registrationToken must equal original token',
      )

      const { json: listJson } = await ctx.call('GET', '/api/harness/environments')
      const envList = envelopeData(listJson)
      assert(Array.isArray(envList), 'environment list must be an array')
      const matching = envList.filter((e) => e.name === envName)
      assert(matching.length === 1, `expected exactly one environment with name ${envName}, got ${matching.length}`)
    } finally {
      if (envId) {
        try {
          let versionToDelete = latestVersion
          try {
            const { json } = await ctx.call(
              'GET',
              `/api/harness/environments/${encodeURIComponent(envId)}`,
            )
            const card = envelopeData(json)
            if (card?.version != null) {
              versionToDelete = String(card.version)
            }
          } catch {
            // fallback to latestVersion
          }
          if (versionToDelete) {
            await ctx.call(
              'DELETE',
              `/api/harness/environments/${encodeURIComponent(envId)}?expectedVersion=${encodeURIComponent(versionToDelete)}`,
            )
          }
        } catch {
          // best-effort cleanup
        }
      }
    }
  },
})
