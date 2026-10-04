import { assert, envelopeData, assertExactFields, cid, expectHttpError } from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'
import { providerCreateBody } from '../lib/fixtures.mjs'
import { assertInstallConfig, linuxInstallConfig } from './environment-install-config.mjs'

function assertImportCheck(data) {
  assertExactFields(data, ['created', 'updated', 'skipped'], 'import check')
  for (const key of ['created', 'updated', 'skipped']) {
    assert(Array.isArray(data[key]), `import check ${key} must be an array`)
    for (const item of data[key]) {
      assertExactFields(item, key === 'skipped' ? ['kind', 'name', 'reason'] : ['kind', 'name'], key)
    }
  }
}

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
  docs: '验证 Provider 导出产生非空 YAML，预检查列为覆盖且不推进版本，确认导入成功，最后以最新 CAS 版本清理',
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

      const { json: checkJson } = await ctx.call('POST', '/api/settings/sync/import/check', { yaml })
      const check = envelopeData(checkJson)
      assertImportCheck(check)
      assert(check.created.length === 0 && check.skipped.length === 0, 'same-name check must only update')
      assert(check.updated.some((item) => item.kind === 'providers' && item.name === providerName), 'check must list provider overwrite')
      const { json: listJson } = await ctx.call('GET', '/api/ai/catalog/providers?pageNumber=1&pageSize=100')
      const found = envelopeData(listJson).results.find((provider) => provider.name === providerName)
      assert(String(found?.version) === latestVersion, 'precheck must not advance provider version')

      const { json: importJson } = await ctx.call('POST', '/api/settings/sync/import', { yaml, allowPartial: false })
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
  docs: '同名 Environment 导出包含 registrationToken；预检查只列覆盖引用，确认导入不生成新 UUID 且保留原令牌与单行唯一性',
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

      const { json: checkJson } = await ctx.call('POST', '/api/settings/sync/import/check', { yaml })
      const check = envelopeData(checkJson)
      assertImportCheck(check)
      assert(check.updated.some((item) => item.kind === 'environments' && item.name === envName), 'check must list environment overwrite')
      assert(!JSON.stringify(check).includes(originalToken), 'check must not echo registrationToken')

      const { json: importJson } = await ctx.call('POST', '/api/settings/sync/import', { yaml, allowPartial: false })
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
registerCase({
  id: 'config_sync.import_precheck_and_partial_confirmation',
  level: 'L1',
  title: '配置导入预检查、硬错误拒绝与部分导入授权',
  docs: '预检查无写入且只返回新增/覆盖/跳过引用；缺必填字段整份拒绝；存在跳过项而未授权时不写入，明确允许部分导入后只创建可用配置',
  async run(ctx) {
    const name = `e2e-sync-check-${cid().slice(0, 8)}`
    const secret = 'sk-e2e-test'
    const yaml = `providers:\n  - name: ${name}\n    providerType: openai\n    credential: ${secret}\nfutureConfig: []\n`
    const invalidYaml = `providers:\n  - name: ${name}\n    credential: ${secret}\n`
    const providerList = async () => {
      const { json } = await ctx.call('GET', '/api/ai/catalog/providers?pageNumber=1&pageSize=100')
      return envelopeData(json).results
    }
    try {
      const { json, headers } = await ctx.call('POST', '/api/settings/sync/import/check', { yaml })
      const check = envelopeData(json)
      assertImportCheck(check)
      assert(headers.get('cache-control') === 'no-store', 'precheck must be no-store')
      assert(check.created.some((item) => item.kind === 'providers' && item.name === name), 'check must list new provider')
      assert(check.updated.length === 0, 'new provider must not be an overwrite')
      assert(check.skipped.some((item) => item.kind === 'futureConfig'), 'unknown category must be reported')
      assert(!JSON.stringify(check).includes(secret), 'precheck must not echo credential')
      assert(!(await providerList()).some((provider) => provider.name === name), 'precheck must not create provider')

      for (const path of ['/api/settings/sync/import/check', '/api/settings/sync/import']) {
        const error = await expectHttpError(() => ctx.call('POST', path, { yaml: invalidYaml }), { status: 400 })
        assert(!String(error.body).includes(secret), 'hard-error response must not echo credential')
      }
      await expectHttpError(() => ctx.call('POST', '/api/settings/sync/import', { yaml }), { status: 400 })
      assert(!(await providerList()).some((provider) => provider.name === name), 'unconfirmed partial import must not write')

      const { json: importedJson } = await ctx.call('POST', '/api/settings/sync/import', { yaml, allowPartial: true })
      const result = envelopeData(importedJson)
      assert(result.imported.some((item) => item.kind === 'providers' && item.name === name), 'authorized partial import must create provider')
      assert(result.skipped.some((item) => item.kind === 'futureConfig'), 'partial import must report skipped category')
      assert((await providerList()).some((provider) => provider.name === name), 'confirmed provider must exist')
    } finally {
      const provider = (await providerList()).find((item) => item.name === name)
      if (provider) {
        await ctx.call('DELETE', `/api/ai/catalog/providers/${encodeURIComponent(name)}?expectedVersion=${encodeURIComponent(provider.version)}`)
      }
    }
  },
})

registerCase({
  id: 'config_sync.environment_install_config_roundtrip',
  level: 'L1',
  title: '配置同步 Environment installConfig 导出导入往返、原子更新与清除',
  docs: 'Environment 导出在 registrationToken 旁嵌套 installConfig；导入预检查只列覆盖引用且不回显 token；确认导入保留配置；以新 token+新配置的条目可原子更新（token 端点读到新值、Card 读到新配置）；缺少 installConfig 字段的条目清空已保存配置',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const envName = `e2e-sync-cfg-${suffix}`
    const newToken = `e2e-sync-cfg-token-${suffix}`
    const cardPath = (id) => `/api/harness/environments/${encodeURIComponent(id)}`
    let envId = null

    const { json: createJson } = await ctx.call('POST', '/api/harness/environments', { name: envName })
    const created = envelopeData(createJson)
    envId = created.id
    const originalToken = created.registrationToken
    assert(envId && originalToken, `created environment must carry id and token: ${JSON.stringify(created)}`)

    try {
      const { json: putJson } = await ctx.call(`${cardPath(envId)}/install-config`, 'PUT', {
        expectedVersion: String(created.version),
        installConfig: linuxInstallConfig(),
      })
      assertInstallConfig(envelopeData(putJson).installConfig)

      const { json: exportJson } = await ctx.call('POST', '/api/settings/sync/export', {
        items: [{ kind: 'environments', name: envName }],
      })
      const yaml = envelopeData(exportJson)?.yaml
      assert(typeof yaml === 'string' && yaml.trim().length > 0, 'export yaml must be non-empty string')
      assert(yaml.includes(envName), 'export yaml must contain environment name')
      assert(yaml.includes(originalToken), 'export yaml must contain environment registrationToken')
      assert(yaml.includes('installConfig'), 'export yaml must contain nested installConfig')
      assert(yaml.includes('http://127.0.0.1:18081'), 'export yaml must contain studioUrl')
      assert(yaml.includes('jdtls'), 'export yaml must contain nested LSP server')

      const { json: checkJson } = await ctx.call('POST', '/api/settings/sync/import/check', { yaml })
      const check = envelopeData(checkJson)
      assertImportCheck(check)
      assert(
        check.updated.some((item) => item.kind === 'environments' && item.name === envName),
        'check must list environment overwrite',
      )
      assert(!JSON.stringify(check).includes(originalToken), 'check must not echo registrationToken')

      const { json: importJson } = await ctx.call('POST', '/api/settings/sync/import', { yaml, allowPartial: false })
      assert(
        envelopeData(importJson).imported.some((item) => item.kind === 'environments' && item.name === envName),
        'import must report the environment',
      )
      const { json: afterImportJson } = await ctx.call('GET', cardPath(envId))
      assertInstallConfig(envelopeData(afterImportJson).installConfig)

      // 原子更新：新 token + 新配置在同一 environment 条目内一次导入生效。
      const updatedConfig = { ...linuxInstallConfig(), javaHome: '/opt/jdk' }
      const updateYaml = JSON.stringify({
        environments: [{ name: envName, registrationToken: newToken, installConfig: updatedConfig }],
      })
      await ctx.call('POST', '/api/settings/sync/import/check', { yaml: updateYaml })
      await ctx.call('POST', '/api/settings/sync/import', { yaml: updateYaml, allowPartial: false })

      const { json: tokenJson } = await ctx.call('GET', `${cardPath(envId)}/token`)
      assert(
        envelopeData(tokenJson).registrationToken === newToken,
        'atomic sync update must rotate the registration token',
      )
      const { json: updatedCardJson } = await ctx.call('GET', cardPath(envId))
      assert(
        envelopeData(updatedCardJson).installConfig?.javaHome === '/opt/jdk',
        'atomic sync update must persist the new install config',
      )

      // 缺少 installConfig 字段的条目必须清空已保存配置。
      const clearYaml = JSON.stringify({
        environments: [{ name: envName, registrationToken: newToken }],
      })
      await ctx.call('POST', '/api/settings/sync/import', { yaml: clearYaml, allowPartial: false })
      const { json: clearedJson } = await ctx.call('GET', cardPath(envId))
      assert(
        envelopeData(clearedJson).installConfig == null,
        `absent installConfig must clear saved settings: ${JSON.stringify(envelopeData(clearedJson).installConfig)}`,
      )
    } finally {
      if (envId) {
        try {
          const { json } = await ctx.call('GET', cardPath(envId))
          const current = envelopeData(json)
          await ctx.call(
            'DELETE',
            `${cardPath(envId)}?expectedVersion=${encodeURIComponent(String(current.version))}`,
          )
        } catch {
          // best-effort cleanup
        }
      }
    }
  },
})

registerCase({
  id: 'config_sync.install_config_hard_invalid_precheck',
  level: 'L1',
  title: '配置同步 installConfig 硬错误预检查与零副作用',
  docs: 'import/check 与 import 都必须深度校验 environment.installConfig：非法 studioUrl、未知字段、缺 operatingSystem 一律 400 硬拒绝（不得降级为 skipped 部分导入），不回显提交值，且不创建任何 Environment',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const name = `e2e-sync-bad-${suffix}`
    const token = `e2e-sync-bad-token-${suffix}`
    const invalidConfigs = [
      { operatingSystem: 'linux', daemon: { studioUrl: 'ftp://invalid' } },
      { operatingSystem: 'linux', daemon: { studioUrl: 'http://127.0.0.1:18081', unknown: 1 } },
      { daemon: { studioUrl: 'http://127.0.0.1:18081' } },
    ]

    for (const installConfig of invalidConfigs) {
      const yaml = JSON.stringify({ environments: [{ name, registrationToken: token, installConfig }] })
      for (const path of ['/api/settings/sync/import/check', '/api/settings/sync/import']) {
        const error = await expectHttpError(
          () => ctx.call('POST', path, { yaml, allowPartial: true }),
          { status: 400 },
        )
        assert(!String(error.body).includes('ftp://invalid'), 'hard error must not echo submitted values')
        assert(
          !String(error.body).includes('http://127.0.0.1:18081'),
          'hard error must not echo submitted values',
        )
      }
    }

    const { json: listJson } = await ctx.call('GET', '/api/harness/environments')
    const list = envelopeData(listJson)
    assert(Array.isArray(list), 'environment list must be an array')
    assert(
      !list.some((environment) => environment.name === name),
      'invalid installConfig must not create an environment',
    )
  },
})
