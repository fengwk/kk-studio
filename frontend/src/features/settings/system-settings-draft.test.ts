import { describe, expect, it } from 'vitest'
import {
  assembleSettingsUpdate,
  DraftValidationError,
  settingsSectionsToDraft,
} from '@/features/settings/system-settings-draft'
import { makeSettingsDto } from '@/features/settings/settings-test-fixtures'

describe('system settings draft codec', () => {
  it('derives a string-value draft from the wire aggregate (Long stays string, Integer becomes string)', () => {
    const draft = settingsSectionsToDraft(makeSettingsDto())
    expect(draft.tool.permission).toEqual([
      { tool: 'write', rules: [{ pattern: '*', action: 'ask' }] },
      { tool: 'edit', rules: [{ pattern: '*', action: 'ask' }] },
      { tool: 'bash', rules: [{ pattern: '*', action: 'ask' }] },
    ])
    expect(draft.environment.maxResourceBytes).toBe('8388608')
    expect(draft.aiRuntime.retryMaxRetries).toBe('3')
    expect(draft.aiRuntime.subagentMaxTotalConcurrency).toBe('')
    expect(draft.storageMedia.thumbnailQuality).toBe('80')
    expect(draft.integrations.comfyui.baseUrl).toBe('')
    expect(draft.integrations.openCliHub.baseUrl).toBe('http://vps-opencli-hub:8080')
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
    expect(update.environment.maxResourceBytes).toBe('8388608')
    expect(update.advanced.processorLeaseDurationMillis).toBe('30000')
    // 六个 section 全部完整存在于请求体。
    expect(Object.keys(update).sort()).toEqual(
      ['advanced', 'aiRuntime', 'environment', 'expectedVersion', 'integrations', 'storageMedia', 'tool'].sort(),
    )
  })

  it('normalizes blank optional text to null and nullable int empty to null', () => {
    const draft = settingsSectionsToDraft(makeSettingsDto())
    draft.integrations.minimaxH3.comfyBaseUrl = '   '
    draft.integrations.comfyui.baseUrl = 'https://comfy.example.com'
    const update = assembleSettingsUpdate(draft, '0')
    expect(update.integrations.minimaxH3.comfyBaseUrl).toBeNull()
    expect(update.integrations.comfyui.baseUrl).toBe('https://comfy.example.com')
    expect(update.aiRuntime.subagentMaxTotalConcurrency).toBeNull()
  })

  it('rejects blank tool names, blank patterns and empty numeric fields with typed reasons', () => {
    const blankTool = settingsSectionsToDraft(makeSettingsDto())
    blankTool.tool.permission = [{ tool: '  ', rules: [{ pattern: '*', action: 'ask' }] }]
    expect(() => assembleSettingsUpdate(blankTool, '0')).toThrowError(
      expect.objectContaining<DraftValidationError>({ reason: 'blankToolName' }),
    )

    const blankPattern = settingsSectionsToDraft(makeSettingsDto())
    blankPattern.tool.permission = [{ tool: 'bash', rules: [{ pattern: '  ', action: 'ask' }] }]
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
})
