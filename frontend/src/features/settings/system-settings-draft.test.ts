import { describe, expect, it } from 'vitest'
import {
  assembleSettingsUpdate,
  draftLeafPaths,
  DraftValidationError,
  getDraftValue,
  setDraftValue,
  settingsSectionsToDraft,
} from '@/features/settings/system-settings-draft'
import {
  DEFAULT_MAX_RESOURCE_BYTES,
  makeSettingsDto,
} from '@/test-support/settings-test-fixtures'

describe('system settings draft codec', () => {
  it('derives a string-value draft from the wire aggregate (Long stays string, Integer becomes string)', () => {
    const draft = settingsSectionsToDraft(makeSettingsDto())
    expect(draft.tool.permission).toEqual([
      { tool: 'write', rules: [{ pattern: '*', action: 'ask' }] },
      { tool: 'edit', rules: [{ pattern: '*', action: 'ask' }] },
      { tool: 'bash', rules: [{ pattern: '*', action: 'ask' }] },
    ])
    expect(draft.environment.maxResourceBytes).toBe(DEFAULT_MAX_RESOURCE_BYTES)
    expect(draft.aiRuntime.retryMaxRetries).toBe('3')
    expect(draft.aiRuntime.subagentMaxTotalConcurrency).toBe('0')
    expect(draft.storageMedia.thumbnailQuality).toBe('80')
    expect(draft.integrations.comfyui.baseUrl).toBe('')
    expect(draft.integrations.openCliHub.baseUrl).toBe('')
  })

  it('assembles a complete update with expectedVersion and correct wire types', () => {
    const draft = settingsSectionsToDraft(makeSettingsDto())
    draft.tool.defaultYolo = true
    draft.aiRuntime.retryMaxRetries = '5'
    draft.aiRuntime.subagentMaxTotalConcurrency = '12'
    const update = assembleSettingsUpdate(draft, '0')

    expect(update.expectedVersion).toBe('0')
    expect(update.tool.defaultYolo).toBe(true)
    expect(update.aiRuntime.retryMaxRetries).toBe(5)
    expect(update.aiRuntime.subagentMaxTotalConcurrency).toBe(12)
    expect(update.aiRuntime.retryMaxDelayMillis).toBe('60000')
    expect(update.environment.maxResourceBytes).toBe(DEFAULT_MAX_RESOURCE_BYTES)
    expect(update.advanced.processorLeaseDurationMillis).toBe('30000')
    // 六个 section 全部完整存在于请求体。
    expect(Object.keys(update).sort()).toEqual(
      ['advanced', 'aiRuntime', 'environment', 'expectedVersion', 'integrations', 'storageMedia', 'tool'].sort(),
    )
  })

  it('round-trips the current aggregate without non-editable runtime infrastructure settings', () => {
    const dto = makeSettingsDto()

    // 完整对象相等与精确 advanced 键集合共同证明非热更新基础设施配置不会被 draft 重新写入 PUT。
    const update = assembleSettingsUpdate(settingsSectionsToDraft(dto), dto.version)
    expect(update).toEqual({
      tool: dto.tool,
      aiRuntime: dto.aiRuntime,
      environment: dto.environment,
      integrations: dto.integrations,
      storageMedia: dto.storageMedia,
      advanced: dto.advanced,
      expectedVersion: dto.version,
    })
    expect(Object.keys(update.advanced).sort()).toEqual(
      [
        'applicationEventHeartbeatIntervalMillis',
        'applicationEventMaxBytes',
        'applicationEventQueueCapacity',
        'applicationEventSendTimeoutMillis',
        'modelDispatchBusyFallbackDelayMillis',
        'postgresqlWorkNotificationPollMillis',
        'postgresqlWorkReconnectBackoffMillis',
        'processorHeartbeatIntervalMillis',
        'processorLeaseDurationMillis',
        'resourceMaxBytes',
        'threadResolveFailureDelayMillis',
        'toolDispatchBusyFallbackDelayMillis',
        'toolPreflightFailureDelayMillis',
      ].sort(),
    )
  })

  it('normalizes blank optional text to null', () => {
    const draft = settingsSectionsToDraft(makeSettingsDto())
    draft.integrations.minimaxH3.comfyBaseUrl = '   '
    draft.integrations.comfyui.baseUrl = 'https://comfy.example.com'
    const update = assembleSettingsUpdate(draft, '0')
    expect(update.integrations.minimaxH3.comfyBaseUrl).toBeNull()
    expect(update.integrations.comfyui.baseUrl).toBe('https://comfy.example.com')
  })

  it('serializes MiniMax H3 integration with strict declared fields and rejects unknown properties', () => {
    // MiniMax H3 契约只声明并支持合法属性，服务端与客户端均执行严格字段收敛。
    // 本用例验证：
    // 1. draft 初始化仅包含已声明合法字段，叶子路径列表与属性枚举严格对应；
    // 2. getDraftValue / setDraftValue 按未知路径访问时 fail-closed；
    // 3. update 序列化产物不包含未声明属性，现有合法字段完成正确的类型转换；
    // 4. 即便输入 DTO 包含未知属性，draft 与 update 组装也不会透传。
    const unknownField = 'unknownField'
    const expectedKeys = [
      'comfyBaseUrl',
      'comfyConnectTimeoutMillis',
      'comfyMaxWaitMillis',
      'comfyPollIntervalMillis',
      'comfyRequestTimeoutMillis',
      'enabled',
      'promptAgentName',
      'promptMaxWaitMillis',
    ].sort()

    const dto = makeSettingsDto()
    const draft = settingsSectionsToDraft(dto)

    expect(Object.keys(draft.integrations.minimaxH3).sort()).toEqual(expectedKeys)
    expect(Object.hasOwn(draft.integrations.minimaxH3, unknownField)).toBe(false)
    expect(() => getDraftValue(draft, `integrations.minimaxH3.${unknownField}`)).toThrow(
      /unknown system settings draft path/,
    )
    expect(() => setDraftValue(draft, `integrations.minimaxH3.${unknownField}`, 'custom')).toThrow(
      /unknown system settings draft path/,
    )
    expect(draftLeafPaths(draft)).not.toContain(`integrations.minimaxH3.${unknownField}`)

    // 修改若干字段以验证合法字段的赋值与序列化转换
    draft.integrations.minimaxH3.enabled = true
    draft.integrations.minimaxH3.promptAgentName = '  custom-agent  '
    draft.integrations.minimaxH3.promptMaxWaitMillis = '450000'
    draft.integrations.minimaxH3.comfyBaseUrl = 'https://comfy.internal:8188'
    draft.integrations.minimaxH3.comfyConnectTimeoutMillis = '12000'
    draft.integrations.minimaxH3.comfyRequestTimeoutMillis = '35000'
    draft.integrations.minimaxH3.comfyPollIntervalMillis = '2500'
    draft.integrations.minimaxH3.comfyMaxWaitMillis = '1200000'

    const update = assembleSettingsUpdate(draft, '0')
    expect(Object.keys(update.integrations.minimaxH3).sort()).toEqual(expectedKeys)
    expect(Object.hasOwn(update.integrations.minimaxH3, unknownField)).toBe(false)
    expect(update.integrations.minimaxH3).toEqual({
      enabled: true,
      promptAgentName: 'custom-agent',
      promptMaxWaitMillis: '450000',
      comfyBaseUrl: 'https://comfy.internal:8188',
      comfyConnectTimeoutMillis: '12000',
      comfyRequestTimeoutMillis: '35000',
      comfyPollIntervalMillis: '2500',
      comfyMaxWaitMillis: '1200000',
    })

    // 输入 DTO 携带残留未知属性时，draft 与 update 依然不会泄露该未知属性
    const dirtyDto = makeSettingsDto()
    ;(dirtyDto.integrations.minimaxH3 as Record<string, unknown>)[unknownField] = 'dirty-value'
    const cleanDraft = settingsSectionsToDraft(dirtyDto)
    expect(Object.hasOwn(cleanDraft.integrations.minimaxH3, unknownField)).toBe(false)
    const cleanUpdate = assembleSettingsUpdate(cleanDraft, '0')
    expect(Object.hasOwn(cleanUpdate.integrations.minimaxH3, unknownField)).toBe(false)
    expect(Object.keys(cleanUpdate.integrations.minimaxH3).sort()).toEqual(expectedKeys)
  })

  it('rejects blank tool names, blank patterns and empty numeric fields with typed reasons', () => {
    const blankTool = settingsSectionsToDraft(makeSettingsDto())
    blankTool.tool.permission = [{ tool: '  ', rules: [{ pattern: '*', action: 'ask' }] }]
    expect(() => assembleSettingsUpdate(blankTool, '0')).toThrowError(
      expect.objectContaining<DraftValidationError>({ reason: 'blankToolName' }),
    )

    const blankPattern = settingsSectionsToDraft(makeSettingsDto())
    blankPattern.tool.permission = [
      { tool: 'bash', rules: [{ pattern: '  ', action: 'ask' }] },
    ]
    expect(() => assembleSettingsUpdate(blankPattern, '0')).toThrowError(
      expect.objectContaining<DraftValidationError>({ reason: 'blankPattern' }),
    )

    const emptyNumeric = settingsSectionsToDraft(makeSettingsDto())
    emptyNumeric.environment.maxResourceBytes = ''
    expect(() => assembleSettingsUpdate(emptyNumeric, '0')).toThrowError(
      expect.objectContaining<DraftValidationError>({ reason: 'emptyNumericField' }),
    )
  })

  it('rejects two permission groups whose names become equal after trim instead of silently overwriting', () => {
    const draft = settingsSectionsToDraft(makeSettingsDto())
    // 两个分组 canonical 名（trim 后）相同：JSON 对象键会覆盖前一个分组的规则造成数据丢失，必须拒绝。
    draft.tool.permission = [
      { tool: 'write', rules: [{ pattern: 'secret/**', action: 'deny' }] },
      { tool: '  write  ', rules: [{ pattern: '*', action: 'allow' }] },
    ]
    expect(() => assembleSettingsUpdate(draft, '0')).toThrowError(
      expect.objectContaining<DraftValidationError>({ reason: 'duplicateToolName' }),
    )
  })

  it('serializes __proto__ as an own permission key without mutating the object prototype', () => {
    const draft = settingsSectionsToDraft(makeSettingsDto())
    draft.tool.permission = [
      { tool: '__proto__', rules: [{ pattern: '*', action: 'ask' }] },
    ]

    const permission = assembleSettingsUpdate(draft, '0').tool.permission
    expect(Object.getPrototypeOf(permission)).toBe(Object.prototype)
    expect(Object.prototype.hasOwnProperty.call(permission, '__proto__')).toBe(true)
    expect(permission['__proto__']).toEqual([{ pattern: '*', action: 'ask' }])
    expect(JSON.stringify(permission)).toBe(
      '{"__proto__":[{"pattern":"*","action":"ask"}]}',
    )
  })

  it('assembles a null fallback model as null and a complete one as a DTO', () => {
    // null 表示禁用 one-shot fallback。
    const nullDraft = settingsSectionsToDraft(makeSettingsDto())
    nullDraft.aiRuntime.compactionFallbackModel = null
    expect(assembleSettingsUpdate(nullDraft, '0').aiRuntime.compactionFallbackModel).toBeNull()

    // 三个字段完整 -> DTO（trim 后）。
    const fullDraft = settingsSectionsToDraft(makeSettingsDto())
    fullDraft.aiRuntime.compactionFallbackModel = {
      providerName: '  openai  ',
      modelName: 'gpt-4o',
      variant: 'default',
    }
    expect(assembleSettingsUpdate(fullDraft, '0').aiRuntime.compactionFallbackModel).toEqual({
      providerName: 'openai',
      modelName: 'gpt-4o',
      variant: 'default',
    })
  })

  it('treats an all-empty fallback model as null and rejects a partial one deterministically', () => {
    // 全部为空 = null（与 ModelSelectionEditor 的 null 语义一致）。
    const emptyDraft = settingsSectionsToDraft(makeSettingsDto())
    emptyDraft.aiRuntime.compactionFallbackModel = { providerName: '', modelName: '', variant: '' }
    expect(assembleSettingsUpdate(emptyDraft, '0').aiRuntime.compactionFallbackModel).toBeNull()

    // 部分填写必须确定性报错，而不是静默丢弃或生成畸形 DTO。
    for (const partial of [
      { providerName: 'openai', modelName: '', variant: '' },
      { providerName: '', modelName: 'gpt-4o', variant: '' },
      { providerName: '', modelName: '', variant: 'default' },
      { providerName: 'openai', modelName: 'gpt-4o', variant: '' },
      { providerName: 'openai', modelName: '', variant: 'default' },
      { providerName: '', modelName: 'gpt-4o', variant: 'default' },
    ]) {
      const draft = settingsSectionsToDraft(makeSettingsDto())
      draft.aiRuntime.compactionFallbackModel = partial
      expect(() => assembleSettingsUpdate(draft, '0')).toThrowError(
        expect.objectContaining<DraftValidationError>({ reason: 'partialModelSelection' }),
      )
    }
  })

  it('hydrates a stored fallback model into the draft and round-trips it back', () => {
    const dto = makeSettingsDto()
    dto.aiRuntime.compactionFallbackModel = {
      providerName: 'openai',
      modelName: 'gpt-4o',
      variant: 'default',
    }
    const draft = settingsSectionsToDraft(dto)
    expect(draft.aiRuntime.compactionFallbackModel).toEqual({
      providerName: 'openai',
      modelName: 'gpt-4o',
      variant: 'default',
    })
    expect(assembleSettingsUpdate(draft, '0').aiRuntime.compactionFallbackModel).toEqual({
      providerName: 'openai',
      modelName: 'gpt-4o',
      variant: 'default',
    })
  })

  it('get/set by path stay on the single draft mapping point', () => {
    const draft = settingsSectionsToDraft(makeSettingsDto())
    const next = setDraftValue(draft, 'aiRuntime.compactionKeepRecentTokens', '25000')
    expect(next.aiRuntime.compactionKeepRecentTokens).toBe('25000')
    expect(getDraftValue(next, 'aiRuntime.compactionKeepRecentTokens')).toBe('25000')
    // 原 draft 未被修改（不可变写入）。
    expect(draft.aiRuntime.compactionKeepRecentTokens).toBe('20000')
    // 未知路径必须 fail closed。
    expect(() => setDraftValue(draft, 'aiRuntime.bogusField', 'true')).toThrow(
      /unknown system settings draft path/,
    )
    expect(() => getDraftValue(draft, 'tool.missing')).toThrow(/unknown system settings draft path/)
    // draft leaf 枚举与 schema 语义一致：两个 custom atomic leaf 各算一个 leaf。
    expect(draftLeafPaths(draft)).toContain('tool.permission')
    expect(draftLeafPaths(draft)).toContain('aiRuntime.compactionFallbackModel')
  })
})
