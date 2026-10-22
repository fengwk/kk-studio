import { describe, expect, it } from 'vitest'
import {
  assembleSettingsUpdate,
  draftLeafPaths,
  getDraftValue,
  setDraftValue,
  settingsSectionsToDraft,
} from '@/features/settings/system-settings-draft'
import {
  DEFAULT_MAX_RESOURCE_BYTES,
  makeSettingsDto,
} from '@/test-support/settings-test-fixtures'

describe('system settings draft codec', () => {
  // 两种权威来源分别 hydrate，随后切换；不能从另一份聚合或前端默认值混入来源。
  it.each(['models_dev_v1', 'models_dev_v2'] as const)('preserves and edits catalog source %s', (source) => {
    const dto = makeSettingsDto()
    dto.aiRuntime.modelCatalogSource = source
    const draft = settingsSectionsToDraft(dto)
    expect(draft.aiRuntime.modelCatalogSource).toBe(source)
    expect(assembleSettingsUpdate(draft, dto.version).aiRuntime).toEqual(dto.aiRuntime)
    draft.aiRuntime.modelCatalogSource = source === 'models_dev_v1' ? 'models_dev_v2' : 'models_dev_v1'
    expect(assembleSettingsUpdate(draft, dto.version).aiRuntime).toEqual({
      ...dto.aiRuntime, modelCatalogSource: draft.aiRuntime.modelCatalogSource,
    })
    expect(dto.aiRuntime.modelCatalogSource).toBe(source)
  })
  // 默认关闭和清空代理都必须明确发送 null；绕过列表保留为字符串，不能产生模块覆盖。
  it('round-trips direct connections and serializes only the two global proxy fields', () => {
    const dto = makeSettingsDto()
    const draft = settingsSectionsToDraft(dto)
    expect(draft.network).toEqual({ proxyUrl: '', noProxyHosts: 'localhost,127.*,::1' })
    expect(assembleSettingsUpdate(draft, dto.version).network).toEqual(dto.network)

    draft.network.proxyUrl = '  http://proxy.internal:8080  '
    draft.network.noProxyHosts = 'localhost,127.*,::1,minio,*.internal'
    expect(assembleSettingsUpdate(draft, dto.version).network).toEqual({
      proxyUrl: 'http://proxy.internal:8080',
      noProxyHosts: 'localhost,127.*,::1,minio,*.internal',
    })
    draft.network.proxyUrl = '   '
    draft.network.noProxyHosts = ''
    expect(assembleSettingsUpdate(draft, dto.version).network).toEqual({
      proxyUrl: null,
      noProxyHosts: '',
    })
  })

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
  })

  it('assembles a complete update with expectedVersion and correct wire types', () => {
    const draft = settingsSectionsToDraft(makeSettingsDto())
    draft.tool.modelGatewayBusyRetryMillis = '6000'
    draft.aiRuntime.retryMaxRetries = '5'
    draft.aiRuntime.subagentMaxTotalConcurrency = '12'
    const update = assembleSettingsUpdate(draft, '0')

    expect(update.expectedVersion).toBe('0')
    expect(update.tool.modelGatewayBusyRetryMillis).toBe('6000')
    expect(update.aiRuntime.retryMaxRetries).toBe(5)
    expect(update.aiRuntime.subagentMaxTotalConcurrency).toBe(12)
    expect(update.aiRuntime.retryMaxDelayMillis).toBe('60000')
    expect(update.environment.maxResourceBytes).toBe(DEFAULT_MAX_RESOURCE_BYTES)
    expect(update.advanced.processorLeaseDurationMillis).toBe('30000')
    // 六个 section 全部完整存在于请求体。
    expect(Object.keys(update).sort()).toEqual(
      ['advanced', 'aiRuntime', 'environment', 'network', 'expectedVersion', 'storageMedia', 'tool'].sort(),
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
      network: dto.network,
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
    // 六节中唯一的 nullable 文本字段是全局代理地址：空白必须序列化为 null，非空保留 trim 后的值。
    const blank = setDraftValue(settingsSectionsToDraft(makeSettingsDto()), 'network.proxyUrl', '   ')
    expect(assembleSettingsUpdate(blank, '0').network.proxyUrl).toBeNull()
    const trimmed = setDraftValue(blank, 'network.proxyUrl', '  http://proxy.internal:8080  ')
    expect(assembleSettingsUpdate(trimmed, '0').network.proxyUrl).toBe(
      'http://proxy.internal:8080',
    )
  })

  it('rejects blank tool names, blank patterns and empty numeric fields with typed reasons', () => {
    const blankTool = settingsSectionsToDraft(makeSettingsDto())
    blankTool.tool.permission = [{ tool: '  ', rules: [{ pattern: '*', action: 'ask' }] }]
    expect(() => assembleSettingsUpdate(blankTool, '0')).toThrowError(
      expect.objectContaining({ reason: 'blankToolName' }),
    )

    const blankPattern = settingsSectionsToDraft(makeSettingsDto())
    blankPattern.tool.permission = [
      { tool: 'bash', rules: [{ pattern: '  ', action: 'ask' }] },
    ]
    expect(() => assembleSettingsUpdate(blankPattern, '0')).toThrowError(
      expect.objectContaining({ reason: 'blankPattern' }),
    )

    const emptyNumeric = settingsSectionsToDraft(makeSettingsDto())
    emptyNumeric.environment.maxResourceBytes = ''
    expect(() => assembleSettingsUpdate(emptyNumeric, '0')).toThrowError(
      expect.objectContaining({ reason: 'emptyNumericField' }),
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
      expect.objectContaining({ reason: 'duplicateToolName' }),
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
    // 权限和状态码列表分别作为完整字段参与 schema 校验。
    expect(draftLeafPaths(draft)).toContain('tool.permission')
    expect(draftLeafPaths(draft)).toContain('aiRuntime.modelHttpRetryStatusCodes')
  })

  it('assembles and validates modelHttpRetryStatusCodes correctly', () => {
    const draft = settingsSectionsToDraft(makeSettingsDto())
    draft.aiRuntime.modelHttpRetryStatusCodes = [408, '429', 500, '502', 503, 504]
    const update = assembleSettingsUpdate(draft, '0')
    expect(update.aiRuntime.modelHttpRetryStatusCodes).toEqual([408, 429, 500, 502, 503, 504])

    // Duplicate check
    draft.aiRuntime.modelHttpRetryStatusCodes = [429, 500, '500']
    expect(() => assembleSettingsUpdate(draft, '0')).toThrowError(
      expect.objectContaining({ reason: 'httpStatusDuplicate' }),
    )

    // Out of range check
    draft.aiRuntime.modelHttpRetryStatusCodes = [200]
    expect(() => assembleSettingsUpdate(draft, '0')).toThrowError(
      expect.objectContaining({ reason: 'httpStatusOutOfRange' }),
    )

    draft.aiRuntime.modelHttpRetryStatusCodes = [600]
    expect(() => assembleSettingsUpdate(draft, '0')).toThrowError(
      expect.objectContaining({ reason: 'httpStatusOutOfRange' }),
    )

    // Non-integer check
    draft.aiRuntime.modelHttpRetryStatusCodes = ['abc']
    expect(() => assembleSettingsUpdate(draft, '0')).toThrowError(
      expect.objectContaining({ reason: 'httpStatusNotInteger' }),
    )

    draft.aiRuntime.modelHttpRetryStatusCodes = [500.5]
    expect(() => assembleSettingsUpdate(draft, '0')).toThrowError(
      expect.objectContaining({ reason: 'httpStatusNotInteger' }),
    )
  })
})
