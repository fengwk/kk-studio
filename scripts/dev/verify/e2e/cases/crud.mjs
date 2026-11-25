import { assert, assertExactFields, expectHttpError, listResults, cid } from '../lib/http.mjs'
import { baseModelConfig, encodePathTail, providerCreateBody, providerUpdateBody } from '../lib/fixtures.mjs'
import { createCaseAdder } from '../lib/registry.mjs'

export const cases = []
const registerCase = createCaseAdder(cases)

registerCase({
  id: 'crud.provider.invalid_name',
  level: 'L1',
  title: 'Provider 非法 name 拒绝',
  docs: 'POST /api/platform/providers name 空白或包含 / => 400',
  async run(ctx) {
    for (const [name, messageIncludes] of [
      ['', /name must not be blank/i],
      ['provider/name', /name must not contain/i],
    ]) {
      await expectHttpError(
        () =>
          ctx.call('POST', '/api/platform/providers', {
            name,
            description: 'x',
            providerType: 'openai',
            baseUrl: 'https://example.com/v1',
            credential: { operation: 'SET', value: 'sk' },
            modelCallTimeoutMillis: '1000',
            modelCallIdleTimeoutMillis: '1000',
          }),
        { status: 400, code: 'validation', messageIncludes },
      )
    }
  },
})

registerCase({
  id: 'crud.provider.invalid_missing_type',
  level: 'L1',
  title: 'Provider 缺少 providerType 拒绝',
  docs: 'POST /api/platform/providers 无 providerType => 400',
  async run(ctx) {
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/platform/providers', {
          name: `e2e-missing-type-${cid().slice(0, 6)}`,
          description: 'x',
          baseUrl: 'https://example.com/v1',
          credential: { operation: 'SET', value: 'sk' },
          modelCallTimeoutMillis: '1000',
          modelCallIdleTimeoutMillis: '1000',
        }),
      { status: 400, code: 'validation', messageIncludes: /providerType|blank|required/i },
    )
  },
})

registerCase({
  id: 'crud.model.invalid_update_config',
  level: 'L1',
  title: 'Model 非法更新不破坏原配置',
  docs: 'path 以全局逻辑名定位既有 Model；PUT body 的 name 是目标逻辑名（支持重命名，本 case 保持同名）；PUT 非法 defaultVariant => 400，随后 GET 原配置不变',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const provider = (await ctx.call('POST', '/api/platform/providers', providerCreateBody(suffix))).json
    const modelName = `e2e-model-invupd-${suffix}`
    const model = (
        await ctx.call('POST', '/api/platform/models', {
          type: 'MODEL',
          providerName: provider.name,
          name: modelName,
          modelId: `wire-invupd-${suffix}`,
          description: 'ok',
          config: baseModelConfig(),
        })
      ).json
    await expectHttpError(
      () =>
        ctx.call(
          'PUT',
          modelPath(modelName),
          {
            type: 'MODEL',
            name: modelName,
            providerName: provider.name,
            modelId: model.modelId,
            description: 'bad',
            config: baseModelConfig({ defaultVariant: 'nope' }),
            expectedVersion: model.version,
          },
        ),
      { status: 400, code: 'validation', messageIncludes: /defaultVariant/i },
    )
    const still = await findModel(ctx, modelName)
    assert(still?.config?.defaultVariant === 'default', JSON.stringify(still))
    // 失败的 PUT 不得改动 identity/version：name 与 version 与创建时完全一致。
    assert(
      still?.name === modelName && String(still?.version) === String(model.version),
      JSON.stringify(still),
    )
    await deleteModel(ctx, still)
    await deleteProvider(ctx, provider)
  },
})

registerCase({
  id: 'crud.agent.invalid_name',
  level: 'L1',
  title: 'Agent 非法 name 拒绝',
  docs: 'POST /api/platform/agents name 空白或包含 / => 400',
  async run(ctx) {
    const model = await firstModel(ctx)
    for (const [name, messageIncludes] of [
      ['  ', /name|blank/i],
      ['agent/name', /name must not contain/i],
    ]) {
      await expectHttpError(
        () =>
          ctx.call('POST', '/api/platform/agents', {
            name,
            description: 'd',
            systemPrompt: 's',
            execution: { driver: 'builtin', settings: { modelName: modelRef(model), variant: model.config.defaultVariant } },
            config: { tools: [], skills: [], subagents: [] },
          }),
        { status: 400, code: 'validation', messageIncludes },
      )
    }
  },
})

registerCase({
  id: 'crud.agent.invalid_variant',
  level: 'L1',
  title: 'Agent 不存在的 Variant 拒绝',
  docs: 'POST /api/platform/agents variant 不属于所选 Model => 400',
  async run(ctx) {
    const model = await firstModel(ctx)
    await expectHttpError(
      () =>
        ctx.call('POST', '/api/platform/agents', {
          name: `e2e-invalid-variant-${cid().slice(0, 8)}`,
          description: 'd',
          systemPrompt: 's',
          execution: { driver: 'builtin', settings: { modelName: modelRef(model), variant: '__missing_variant__' } },
          config: { tools: [], skills: [], subagents: [] },
        }),
      { status: 400, code: 'validation', messageIncludes: /variant/i },
    )
  },
})

registerCase({
  id: 'crud.provider.lifecycle',
  level: 'L1',
  title: 'Provider name identity 创建/更新/硬删除/同名重建',
  docs: '记录存续期间 name 不可修改；PUT 仅更新 editable properties；DELETE 带 expectedVersion 硬删除后同名可重建，version 从 0 重新开始且读取到新数据',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const created = (await ctx.call('POST', '/api/platform/providers', providerCreateBody(suffix))).json
    assert(created?.name && !('id' in created), JSON.stringify(created))
    const listed = listResults(
      (await ctx.call('GET', '/api/platform/providers')).json,
    )
    assert(listed.some((provider) => provider.name === created.name), 'created Provider not listed')

    const updated = (
        await ctx.call(
          'PUT',
          `/api/platform/providers/${encodeURIComponent(created.name)}`,
          {
            description: 'e2e provider updated',
            baseUrl: 'https://example.com/v2',
            credential: { operation: 'KEEP' },
            modelCallTimeoutMillis: '1800000',
            modelCallIdleTimeoutMillis: '120000',
            expectedVersion: created.version,
          },
        )
      ).json
    assert(updated.name === created.name, JSON.stringify(updated))
    assert(updated.baseUrl === 'https://example.com/v2', JSON.stringify(updated))
    assert(updated.credential?.configured === true, 'empty credential should keep configured')
    await deleteProvider(ctx, updated)
    const after = listResults(
      (await ctx.call('GET', '/api/platform/providers')).json,
    )
    assert(!after.some((provider) => provider.name === created.name), 'Provider still listed')

    // 硬删除后同名可重建：version 从 0 重新开始，且严格使用新请求体属性。
    const recreated = (
        await ctx.call('POST', '/api/platform/providers', {
          ...providerCreateBody(suffix),
          description: 'e2e provider recreated',
          baseUrl: 'https://example.com/v3',
        })
      ).json
    assert(recreated.name === created.name, JSON.stringify(recreated))
    assert(String(recreated.version) === '0', JSON.stringify(recreated))
    assert(
      recreated.baseUrl === 'https://example.com/v3'
        && recreated.description === 'e2e provider recreated',
      JSON.stringify(recreated),
    )
    const relisted = listResults(
      (await ctx.call('GET', '/api/platform/providers')).json,
    )
    assert(
      relisted.some((provider) => provider.name === created.name),
      'recreated Provider not listed',
    )
    await deleteProvider(ctx, recreated)
  },
})

registerCase({
  id: 'crud.model.lifecycle',
  level: 'L1',
  title: 'Model 全局逻辑名 identity 创建/更新/硬删除/同名重建',
  docs: 'path 以全局逻辑名定位；PUT 不改 providerName；protocolOptionsJson 在创建/读取/更新时保留数值文本；DELETE 后同名可重建且 version 从 0 开始；Provider 最后删除',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const provider = (await ctx.call('POST', '/api/platform/providers', providerCreateBody(suffix))).json
    const name = `vendor/e2e-model-${suffix}`
    // 文本必须跨 HTTP/持久化往返不变，不能被 JS 的 Number 舍入。
    const originalOptions = '{"large":9007199254740993,"ratio":0.12345678901234567890123456789}'
    const updatedOptions = '{"large":9223372036854775808,"nested":{"ratio":1.234567890123456789e+45}}'
    const model = (
        await ctx.call('POST', '/api/platform/models', {
          type: 'MODEL',
          providerName: provider.name,
          name,
          modelId: `wire-lifecycle-${suffix}`,
          description: 'create',
          config: baseModelConfig({
            variants: [{ id: 'default', promptCacheRetention: 'SHORT', protocolOptionsJson: originalOptions }],
          }),
        })
      ).json
    assert(
      model?.providerName === provider.name && model.name === name && !('id' in model),
      JSON.stringify(model),
    )
    assert(model.config.variants[0].protocolOptionsJson === originalOptions, 'created options changed')
    const listed = await findModel(ctx, name)
    assert(listed?.config.variants[0].protocolOptionsJson === originalOptions, 'listed options changed')
    const updatedConfig = baseModelConfig({
      limit: { context: 8192, output: 1024 },
      abilities: { tools: false, reasoning: true, inputModalities: ['TEXT', 'IMAGE'] },
      defaultVariant: 'fast',
      variants: [
        { id: 'fast', promptCacheRetention: 'SHORT', protocolOptionsJson: updatedOptions },
        { id: 'quality', promptCacheRetention: 'SHORT', reasoningEffort: 'high' },
      ],
    })
    const updated = (
        await ctx.call('PUT', modelPath(name), {
          type: 'MODEL',
          name,
          providerName: provider.name,
          modelId: model.modelId,
          description: 'updated',
          config: updatedConfig,
          expectedVersion: model.version,
        })
      ).json
    assert(updated.providerName === provider.name && updated.name === name, JSON.stringify(updated))
    assert(updated.config.defaultVariant === 'fast', JSON.stringify(updated.config))
    assert(updated.config.variants[0].protocolOptionsJson === updatedOptions, 'updated options changed')
    const relisted = await findModel(ctx, name)
    assert(relisted?.config.variants[0].protocolOptionsJson === updatedOptions, 'relisted options changed')
    await deleteModel(ctx, updated)
    assert(!(await findModel(ctx, name)), 'Model still listed')

    // 硬删除后同名可重建：version 从 0 重新开始，且严格使用新请求体配置。
    const recreated = (
        await ctx.call('POST', '/api/platform/models', {
          type: 'MODEL',
          providerName: provider.name,
          name,
          modelId: `wire-recreated-${suffix}`,
          description: 'recreated',
          config: baseModelConfig(),
        })
      ).json
    assert(
      recreated.providerName === provider.name && recreated.name === name,
      JSON.stringify(recreated),
    )
    assert(String(recreated.version) === '0', JSON.stringify(recreated))
    assert(recreated.description === 'recreated', JSON.stringify(recreated))
    assert(
      recreated.config.defaultVariant === 'default',
      `recreated Model must reflect current body config: ${JSON.stringify(recreated)}`,
    )
    assert(await findModel(ctx, name), 'recreated Model not listed')
    await deleteModel(ctx, recreated)
    await deleteProvider(ctx, provider)
  },
})

registerCase({
  id: 'crud.model_group.lifecycle',
  level: 'L1',
  title: 'Model Group 创建/更新/硬删除 roundtrip',
  docs: 'GROUP 与 MODEL 共用全局逻辑名：创建有序 Fill First 成员、fallbackStatusCodes 与 cooldownSeconds；读取只暴露 group 与派生能力摘要（无组级 pricing/variants/defaultVariant）；PUT 可重排成员并清空状态码/冷却为 0；DELETE 后列表不再包含该组',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    let provider = null
    const models = []
    let group = null
    try {
      provider = (await ctx.call('POST', '/api/platform/providers', providerCreateBody(suffix))).json
      for (const [index, variant] of ['default', 'review'].entries()) {
        const model = (
            await ctx.call('POST', '/api/platform/models', {
              type: 'MODEL',
              providerName: provider.name,
              name: `e2e-group-member-${index}-${suffix}`,
              modelId: `wire-group-member-${index}-${suffix}`,
              description: `group member ${index}`,
              config: baseModelConfig({
                limit: { context: 4096 + index * 1024, output: 256 },
                variants: [{ id: variant, promptCacheRetention: 'SHORT' }],
                defaultVariant: variant,
              }),
            })
          ).json
        models.push(model)
      }

      const groupName = `e2e-model-group-${suffix}`
      group = (
          await ctx.call('POST', '/api/platform/models', {
            type: 'GROUP',
            name: groupName,
            description: 'fill first group',
            group: {
              members: [
                { modelName: models[0].name, variant: 'default' },
                { modelName: models[1].name, variant: 'review' },
              ],
              fallbackStatusCodes: [429, 503],
              cooldownSeconds: 30,
            },
          })
        ).json
      assert(
        group.type === 'GROUP'
          && group.name === groupName
          && group.providerName == null
          && group.modelId == null
          && group.group?.members?.length === 2
          && group.group.members[0].modelName === models[0].name
          && group.group.members[0].variant === 'default'
          && group.group.members[1].modelName === models[1].name
          && group.group.members[1].variant === 'review'
          && JSON.stringify(group.group.fallbackStatusCodes) === JSON.stringify([429, 503])
          && group.group.cooldownSeconds === 30,
        JSON.stringify(group),
      )
      assertExactFields(
        group,
        ['name', 'type', 'description', 'config', 'group', 'version', 'createdAt', 'updatedAt'],
        'ModelDTO(GROUP)',
      )
      assert(
        group.config?.limit?.context === 4096
          && group.config?.limit?.output === 256
          && group.config?.pricing == null
          && group.config?.defaultVariant == null
          && JSON.stringify(group.config?.variants) === JSON.stringify([]),
        `group config must be a derived capability summary: ${JSON.stringify(group.config)}`,
      )
      assert((await findModel(ctx, groupName))?.type === 'GROUP', 'group missing from list')

      const updated = (
          await ctx.call('PUT', modelPath(groupName), {
            type: 'GROUP',
            name: groupName,
            description: 'updated group',
            group: {
              members: [
                { modelName: models[1].name, variant: 'review' },
                { modelName: models[0].name, variant: 'default' },
              ],
              fallbackStatusCodes: [],
              cooldownSeconds: 0,
            },
            expectedVersion: group.version,
          })
        ).json
      assert(
        updated.description === 'updated group'
          && updated.group?.members?.[0]?.modelName === models[1].name
          && updated.group?.members?.[1]?.modelName === models[0].name
          && JSON.stringify(updated.group?.fallbackStatusCodes) === JSON.stringify([])
          && updated.group?.cooldownSeconds === 0,
        JSON.stringify(updated),
      )
      assert(String(updated.version) !== String(group.version), 'group version did not advance')

      await deleteModel(ctx, updated)
      group = null
      assert(!(await findModel(ctx, groupName)), 'group still listed after delete')
    } finally {
      if (group) {
        await deleteModel(ctx, group).catch(() => undefined)
      }
      for (const model of models) {
        await deleteModel(ctx, model).catch(() => undefined)
      }
      if (provider) {
        await deleteProvider(ctx, provider).catch(() => undefined)
      }
    }
  },
})

registerCase({
  id: 'crud.agent.lifecycle',
  level: 'L1',
  title: 'Agent name identity 创建/更新/硬删除/同名重建',
  docs: '记录存续期间 name 不可修改；PUT 可更新 default model/prompt/variant/config；DELETE 硬删除后同名可重建，version 从 0 重新开始且读取到新数据',
  async run(ctx) {
    const [model, updatedModel] = await firstTwoModels(ctx)
    const name = `e2e-agent-${cid().slice(0, 8)}`
    const agent = (
        await ctx.call('POST', '/api/platform/agents', {
          name,
          description: 'create',
          systemPrompt: 'you are e2e',
          execution: { driver: 'builtin', settings: { modelName: modelRef(model), variant: model.config.defaultVariant } },
          config: { tools: [], skills: [], subagents: [] },
        })
      ).json
    assert(agent?.name === name && agent.execution?.driver === 'builtin' && agent.execution.settings?.modelName === modelRef(model) && !('id' in agent), JSON.stringify(agent))
    const updated = (
        await ctx.call('PUT', `/api/platform/agents/${encodeURIComponent(name)}`, {
          description: 'updated',
          systemPrompt: 'updated prompt',
          execution: { driver: 'builtin', settings: { modelName: modelRef(updatedModel), variant: updatedModel.config.defaultVariant } },
          config: { tools: [], skills: [], subagents: [] },
          expectedVersion: agent.version,
        })
      ).json
    assert(
      updated.name === name && updated.execution?.driver === 'builtin' && updated.execution.settings?.modelName === modelRef(updatedModel),
      JSON.stringify(updated),
    )
    assert(updated.systemPrompt === 'updated prompt', JSON.stringify(updated))
    assert(Object.keys(updated.config ?? {}).sort().join(',') === 'skills,subagents,tools', JSON.stringify(updated))
    await ctx.call(
      'DELETE',
      `/api/platform/agents/${encodeURIComponent(name)}?expectedVersion=${encodeURIComponent(updated.version)}`,
    )
    const agents = listResults(
      (await ctx.call('GET', '/api/platform/agents')).json,
    )
    assert(!agents.some((candidate) => candidate.name === name), 'Agent still listed')

    // 硬删除后同名可重建：version 从 0 重新开始，且严格使用新请求体 prompt。
    const recreated = (
        await ctx.call('POST', '/api/platform/agents', {
          name,
          description: 'recreated',
          systemPrompt: 'recreated prompt',
          execution: { driver: 'builtin', settings: { modelName: modelRef(model), variant: model.config.defaultVariant } },
          config: { tools: [], skills: [], subagents: [] },
        })
      ).json
    assert(recreated.name === name && recreated.execution?.driver === 'builtin' && recreated.execution.settings?.modelName === modelRef(model), JSON.stringify(recreated))
    assert(String(recreated.version) === '0', JSON.stringify(recreated))
    assert(recreated.systemPrompt === 'recreated prompt', JSON.stringify(recreated))
    const relisted = listResults(
      (await ctx.call('GET', '/api/platform/agents')).json,
    )
    assert(relisted.some((candidate) => candidate.name === name), 'recreated Agent not listed')
    await ctx.call(
      'DELETE',
      `/api/platform/agents/${encodeURIComponent(name)}?expectedVersion=${encodeURIComponent(recreated.version)}`,
    )
  },
})

registerCase({
  id: 'crud.agent.subagent_reference_lifecycle',
  level: 'L1',
  title: 'Agent subagents 名称引用与删除保护',
  docs: '创建 parent.subagents=[child] 后 child DELETE => 409；PUT parent 移除引用后 child 可硬删除；公开 config 始终完整返回 tools/skills/subagents',
  async run(ctx) {
    const model = await firstModel(ctx)
    const suffix = cid().slice(0, 8)
    let child = null
    let parent = null
    try {
      child = (
          await ctx.call('POST', '/api/platform/agents', {
            name: `e2e-child-${suffix}`,
            description: 'subagent child',
            systemPrompt: 'return a concise report',
            execution: { driver: 'builtin', settings: { modelName: modelRef(model), variant: model.config.defaultVariant } },
            config: { tools: [], skills: [], subagents: [] },
          })
        ).json
      parent = (
          await ctx.call('POST', '/api/platform/agents', {
            name: `e2e-parent-${suffix}`,
            description: 'subagent parent',
            systemPrompt: 'delegate when needed',
            execution: { driver: 'builtin', settings: { modelName: modelRef(model), variant: model.config.defaultVariant } },
            config: {
              tools: [],
              skills: [],
              subagents: [child.name],
            },
          })
        ).json
      assert(
        Array.isArray(parent.config?.tools)
          && parent.config.tools.length === 0
          && Array.isArray(parent.config?.skills)
          && parent.config.skills.length === 0
          && JSON.stringify(parent.config?.subagents) === JSON.stringify([child.name])
          && Object.keys(parent.config).sort().join(',') === 'skills,subagents,tools',
        JSON.stringify(parent),
      )
      await expectHttpError(
        () =>
          ctx.call(
            'DELETE',
            `/api/platform/agents/${encodeURIComponent(child.name)}?expectedVersion=${encodeURIComponent(child.version)}`,
          ),
        { status: 409, code: 'in_use', messageIncludes: /in use|subagent|referenc/i },
      )
      parent = (
          await ctx.call(
            'PUT',
            `/api/platform/agents/${encodeURIComponent(parent.name)}`,
            {
              description: parent.description,
              systemPrompt: parent.systemPrompt,
              execution: parent.execution,
              config: {
                tools: [],
                skills: [],
                subagents: [],
              },
              expectedVersion: parent.version,
            },
          )
        ).json
      await ctx.call(
        'DELETE',
        `/api/platform/agents/${encodeURIComponent(child.name)}?expectedVersion=${encodeURIComponent(child.version)}`,
      )
      child = null
      await ctx.call(
        'DELETE',
        `/api/platform/agents/${encodeURIComponent(parent.name)}?expectedVersion=${encodeURIComponent(parent.version)}`,
      )
      parent = null
    } finally {
      for (const agent of [parent, child]) {
        if (!agent?.name) continue
        try {
          await ctx.call(
            'DELETE',
            `/api/platform/agents/${encodeURIComponent(agent.name)}?expectedVersion=${encodeURIComponent(agent.version)}`,
          )
        } catch {
          // 保留主断言失败；隔离 E2E schema 会在下一次 rebuild 清理。
        }
      }
    }
  },
})

registerCase({
  id: 'crud.model.delete_unknown_rejected',
  level: 'L1',
  title: '删除不存在 Model 被拒绝',
  docs: 'DELETE 使用全局逻辑名 path；未知 Model name => 404',
  async run(ctx) {
    await expectHttpError(
      () => ctx.call('DELETE', '/api/platform/models/missing-model?expectedVersion=0'),
      { status: 404, code: 'not_found', messageIncludes: /not found|unknown|model/i },
    )
  },
})

/** 列出全部 Provider，用于读回持久化事实。 */
async function listProviders(ctx) {
  return listResults(
    (await ctx.call('GET', '/api/platform/providers')).json,
  )
}

registerCase({
  id: 'crud.provider.http_retry_override',
  level: 'L1',
  title: 'Provider HTTP 重试白名单三态覆盖与严格整数校验',
  docs: 'POST/PUT /api/platform/providers 的 modelHttpRetryStatusCodes 为 number[]|null：无覆盖显式输出 null（继承系统名单）；PUT 整体替换，省略即写 null；数组完全替代系统名单；空数组禁用；非 400–599 整数/越界/重复/非数组/字符串/小数/null 元素一律 400 且不推进版本；测试结束前按 CAS 删除临时 Provider',
  async run(ctx) {
    const suffix = cid().slice(0, 8)
    const created = (await ctx.call('POST', '/api/platform/providers', providerCreateBody(suffix))).json
    let current = created
    const put = (overrides) =>
      ctx.call('PUT', `/api/platform/providers/${encodeURIComponent(created.name)}`, {
        ...providerUpdateBody(overrides),
        expectedVersion: String(current.version),
      })
    try {
      // 无覆盖时字段显式输出 null；断言失败也必须清理已创建资源。
      assert(
        Object.hasOwn(created, 'modelHttpRetryStatusCodes') &&
          created.modelHttpRetryStatusCodes === null,
        `uncovered Provider must expose explicit null override: ${JSON.stringify(created)}`,
      )
      // 数组完全替代系统名单。
      current = (await put({ modelHttpRetryStatusCodes: [500, 503] })).json
      assert(
        JSON.stringify(current.modelHttpRetryStatusCodes) === JSON.stringify([500, 503]),
        JSON.stringify(current),
      )
      // 读回持久化事实。
      const listed = (await listProviders(ctx)).find(
        (provider) => provider.name === created.name,
      )
      assert(
        JSON.stringify(listed?.modelHttpRetryStatusCodes) === JSON.stringify([500, 503]),
        JSON.stringify(listed),
      )
      // 整体替换语义：省略字段即写 null，恢复继承系统名单。
      current = (await put({})).json
      assert(
        current.modelHttpRetryStatusCodes === null,
        `omitted override must reset to inherited null: ${JSON.stringify(current)}`,
      )
      // 空数组表示禁用自动重试，必须与继承(null)区分。
      current = (await put({ modelHttpRetryStatusCodes: [] })).json
      assert(
        Array.isArray(current.modelHttpRetryStatusCodes) &&
          current.modelHttpRetryStatusCodes.length === 0,
        JSON.stringify(current),
      )
      // 显式 null 同样清除覆盖。
      current = (await put({ modelHttpRetryStatusCodes: null })).json
      assert(
        current.modelHttpRetryStatusCodes === null,
        `explicit null must clear the override: ${JSON.stringify(current)}`,
      )
      // 严格整数形状：非数组/小数/字符串/越界/重复/null 元素一律 400。
      const versionBeforeInvalid = String(current.version)
      for (const invalidList of [
        429, '429', 429.5, [429.5], ['429'], [399], [600], [429, 429], [null], {},
      ]) {
        await expectHttpError(() => put({ modelHttpRetryStatusCodes: invalidList }), {
          status: 400,
          code: 'validation',
        })
      }
      const afterInvalid = (await listProviders(ctx)).find(
        (provider) => provider.name === created.name,
      )
      assert(
        afterInvalid && String(afterInvalid.version) === versionBeforeInvalid,
        `invalid retry status list must not advance version: ${JSON.stringify(afterInvalid)}`,
      )
    } finally {
      const latest = (await listProviders(ctx)).find((provider) => provider.name === created.name)
      if (latest) await deleteProvider(ctx, latest)
    }
  },
})

async function firstModel(ctx) {
  const models = listResults(
    (await ctx.call('GET', '/api/platform/models')).json,
  )
  assert(models[0]?.providerName && models[0]?.name, 'need seeded Model')
  return models[0]
}

async function firstTwoModels(ctx) {
  const models = listResults(
    (await ctx.call('GET', '/api/platform/models')).json,
  )
  assert(
    models[0]?.providerName
      && models[0]?.name
      && models[1]?.providerName
      && models[1]?.name,
    'need two seeded Models',
  )
  return [models[0], models[1]]
}

async function findModel(ctx, name) {
  const models = listResults(
    (await ctx.call('GET', '/api/platform/models')).json,
  )
  return models.find((model) => model.name === name)
}

function modelRef(model) {
  return model.name
}

function modelPath(name) {
  return `/api/platform/models/${encodePathTail(name)}`
}

async function deleteModel(ctx, model) {
  await ctx.call(
    'DELETE',
    `${modelPath(model.name)}?expectedVersion=${encodeURIComponent(model.version)}`,
  )
}

async function deleteProvider(ctx, provider) {
  await ctx.call(
    'DELETE',
    `/api/platform/providers/${encodeURIComponent(provider.name)}?expectedVersion=${encodeURIComponent(provider.version)}`,
  )
}
