import { describe, expect, it } from 'vitest'
import { toUserFacingErrorMessage } from '@/features/ai/ai-user-facing-error'
import { emptyModelDraft } from '@/features/ai/catalog/ai-model-draft-codec'
import type { ModelDraft } from '@/features/ai/catalog/ai-console-types'
import {
  validateResourceDraft,
} from '@/features/ai/catalog/ai-resource-form-validation'
import { setLocale } from '@/shared/i18n'

const EMPTY_AGENT_DRAFT = {
  name: '',
  description: '',
  systemPrompt: '',
  model: '',
  variant: '',
  inheritParentEnvironment: true,
  tools: [],
  skills: [],
  subagents: [],
}

function draft(overrides: Partial<ModelDraft> = {}): ModelDraft {
  return {
    ...emptyModelDraft(),
    providerName: 'provider-1',
    name: 'model',
    modelId: 'wire-model',
    ...overrides,
  }
}

function providerDraft() {
  return {
    name: 'p',
    description: '',
    providerType: 'openai',
    baseUrl: '',
    credential: '',
    modelCallTimeoutMillis: '',
    modelCallIdleTimeoutMillis: '',
  }
}

describe('ai-resource-form-validation', () => {
  it('rejects a model draft with a blank provider', () => {
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create' },
      {
        providerDraft: { ...providerDraft(), name: '' },
        modelDraft: draft({ providerName: '   ' }),
        agentDraft: { ...EMPTY_AGENT_DRAFT },
      },
    )
    expect(result.ok).toBe(false)
    expect(result.fields.providerName).toBeDefined()
  })

  it('rejects a model draft with a blank name', () => {
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create' },
      {
        providerDraft: providerDraft(),
        modelDraft: draft({ name: '   ' }),
        agentDraft: { ...EMPTY_AGENT_DRAFT },
      },
    )
    expect(result.ok).toBe(false)
    expect(result.fields.name).toBeDefined()
  })

  it('rejects empty input modalities', () => {
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create' },
      {
        providerDraft: providerDraft(),
        modelDraft: draft({ inputModalities: [] }),
        agentDraft: { ...EMPTY_AGENT_DRAFT },
      },
    )
    expect(result.ok).toBe(false)
    expect(result.fields.inputModalities).toBeDefined()
  })

  it('rejects a defaultVariant that does not exist', () => {
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create' },
      {
        providerDraft: providerDraft(),
        modelDraft: draft({
          defaultVariant: 'stale',
          variants: [
            {
              draftId: 'variant-default',
              id: 'fast',
              reasoningEffort: '',
            },
            {
              draftId: 'variant-2',
              id: 'creative',
              reasoningEffort: '',
            },
          ],
        }),
        agentDraft: { ...EMPTY_AGENT_DRAFT },
      },
    )
    expect(result.ok).toBe(false)
    expect(result.fields.defaultVariant).toBeDefined()
  })

  /** Reasoning 开启时全部 variant 的空思考强度仍然合法：空值表示不覆盖协议默认。 */
  it('accepts empty reasoning effort as the protocol default when reasoning is enabled', () => {
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create' },
      {
        providerDraft: providerDraft(),
        modelDraft: draft({
          reasoning: true,
          variants: [
            {
              draftId: 'variant-x',
              id: 'medium',
              reasoningEffort: '',
            },
          ],
        }),
        agentDraft: { ...EMPTY_AGENT_DRAFT },
      },
    )
    expect(result.ok).toBe(true)
    expect(result.fields).toEqual({})
  })

  /** 校验不得写回或补全 reasoningEffort：draft 必须保持用户输入的原样。 */
  it('does not mutate the model draft while validating', () => {
    const modelDraft = draft({
      reasoning: true,
      variants: [{ draftId: 'variant-x', id: 'medium', reasoningEffort: '' }],
    })
    const snapshot = JSON.stringify(modelDraft)
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create' },
      {
        providerDraft: providerDraft(),
        modelDraft,
        agentDraft: { ...EMPTY_AGENT_DRAFT },
      },
    )
    expect(result.ok).toBe(true)
    expect(JSON.stringify(modelDraft)).toBe(snapshot)
  })

  it('translates known backend errors into user-facing Chinese', () => {
    expect(
      toUserFacingErrorMessage(new Error('config.limit.context must be a positive integer')),
    ).toMatch(/上下文窗口/)
    expect(
      toUserFacingErrorMessage(new Error('config.defaultVariant must match a variant id')),
    ).toMatch(/默认 Variant/)
    expect(toUserFacingErrorMessage(new Error('duplicate variant id: fast'))).toMatch(/不能重复/)
    expect(toUserFacingErrorMessage(new Error('401 Unauthorized'))).toMatch(/没有权限/)
  })

  it('passes Chinese text through unchanged', () => {
    expect(toUserFacingErrorMessage(new Error('已开启 Reasoning'))).toMatch(/Reasoning/)
  })

  it('maps reasoning effort and agent variant errors to their fields', () => {
    expect(
      toUserFacingErrorMessage(new Error('variant medium reasoningEffort must not exceed 64 characters')),
    ).toMatch(/思考强度|Reasoning/)
    expect(toUserFacingErrorMessage(new Error('variant must not be blank'))).toMatch(/Variant/)
    expect(toUserFacingErrorMessage(new Error('unsupported provider type wire value: OPENAI'))).toMatch(
      /Provider Type/,
    )
  })

  describe('protocolOptions form validation', () => {
    it('accepts valid protocolOptions JSON object on create', () => {
      const result = validateResourceDraft(
        { kind: 'model', mode: 'create' },
        {
          providerDraft: providerDraft(),
          modelDraft: draft({
            variants: [
              {
                draftId: 'v1',
                id: 'medium',
                reasoningEffort: '',
                protocolOptionsJson: '{\n  "temperature": 0.7\n}',
              },
            ],
          }),
          agentDraft: { ...EMPTY_AGENT_DRAFT },
        },
      )
      expect(result.ok).toBe(true)
      expect(result.fields).toEqual({})
    })

    it('rejects invalid protocolOptions JSON and surfaces localized variant-specific error', () => {
      setLocale('zh-CN')
      const result = validateResourceDraft(
        { kind: 'model', mode: 'create' },
        {
          providerDraft: providerDraft(),
          modelDraft: draft({
            defaultVariant: 'fast',
            variants: [
              {
                draftId: 'v1',
                id: 'fast',
                reasoningEffort: '',
                protocolOptionsJson: '{ not valid json }',
              },
            ],
          }),
          agentDraft: { ...EMPTY_AGENT_DRAFT },
        },
      )
      expect(result.ok).toBe(false)
      expect(result.message).toContain('Variant "fast"')
      expect(result.message).toContain('protocolOptions 必须是有效的 JSON')
      expect(result.fields.variants).toBe(result.message)
    })

    it('rejects non-object root (array/scalar/null) with localized variant-specific error', () => {
      setLocale('zh-CN')
      const result = validateResourceDraft(
        { kind: 'model', mode: 'create' },
        {
          providerDraft: providerDraft(),
          modelDraft: draft({
            defaultVariant: 'fast',
            variants: [
              {
                draftId: 'v1',
                id: 'fast',
                reasoningEffort: '',
                protocolOptionsJson: '[1, 2, 3]',
              },
            ],
          }),
          agentDraft: { ...EMPTY_AGENT_DRAFT },
        },
      )
      expect(result.ok).toBe(false)
      expect(result.message).toContain('Variant "fast"')
      expect(result.message).toContain('protocolOptions 必须是 JSON 对象')
      expect(result.fields.variants).toBe(result.message)
    })

    it('validates protocolOptions even when model reasoning is disabled', () => {
      setLocale('zh-CN')
      const result = validateResourceDraft(
        { kind: 'model', mode: 'create' },
        {
          providerDraft: providerDraft(),
          modelDraft: draft({
            reasoning: false,
            defaultVariant: 'standard',
            variants: [
              {
                draftId: 'v1',
                id: 'standard',
                reasoningEffort: '',
                protocolOptionsJson: 'null',
              },
            ],
          }),
          agentDraft: { ...EMPTY_AGENT_DRAFT },
        },
      )
      expect(result.ok).toBe(false)
      expect(result.message).toContain('Variant "standard"')
      expect(result.message).toContain('protocolOptions 必须是 JSON 对象')
      expect(result.fields.variants).toBe(result.message)
    })

    it('supports English locale for protocolOptions validation error messages', () => {
      setLocale('en-US')
      expect(
        toUserFacingErrorMessage(new Error('variant fast protocolOptionsJson must be valid JSON')),
      ).toBe('Variant "fast" protocolOptions must be valid JSON')
      expect(
        toUserFacingErrorMessage(new Error('variant fast protocolOptionsJson must be a JSON object')),
      ).toBe('Variant "fast" protocolOptions must be a JSON object')
      setLocale('zh-CN')
    })
  })
})
