import { describe, expect, it } from 'vitest'
import { emptyModelDraft } from '@/features/ai/catalog/ai-model-draft-codec'
import type { ModelDraft } from '@/features/ai/catalog/ai-console-types'
import {
  toUserFacingErrorMessage,
  validateResourceDraft,
} from '@/features/ai/catalog/ai-resource-form-validation'

const EMPTY_AGENT_DRAFT = {
  name: '',
  description: '',
  systemPrompt: '',
  modelId: '',
  variant: '',
  environmentName: '',
  tools: [],
  skills: [],
}

function draft(overrides: Partial<ModelDraft> = {}): ModelDraft {
  return {
    ...emptyModelDraft(),
    providerId: 'provider-1',
    name: 'model',
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
        modelDraft: draft({ providerId: '   ' }),
        agentDraft: { ...EMPTY_AGENT_DRAFT },
      },
    )
    expect(result.ok).toBe(false)
    expect(result.fields.providerId).toBeDefined()
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
              maxOutputTokens: '',
              temperature: '',
              topP: '',
              topK: '',
              frequencyPenalty: '',
              presencePenalty: '',
              stopSequences: '',
            },
            {
              draftId: 'variant-2',
              id: 'creative',
              reasoningEffort: '',
              maxOutputTokens: '',
              temperature: '',
              topP: '',
              topK: '',
              frequencyPenalty: '',
              presencePenalty: '',
              stopSequences: '',
            },
          ],
        }),
        agentDraft: { ...EMPTY_AGENT_DRAFT },
      },
    )
    expect(result.ok).toBe(false)
    expect(result.fields.defaultVariant).toBeDefined()
  })

  it('rejects when reasoning is enabled but every variant lacks effort', () => {
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
              maxOutputTokens: '',
              temperature: '',
              topP: '',
              topK: '',
              frequencyPenalty: '',
              presencePenalty: '',
              stopSequences: '',
            },
          ],
        }),
        agentDraft: { ...EMPTY_AGENT_DRAFT },
      },
    )
    expect(result.ok).toBe(true)
    // Reasoning effort must be auto-filled with the variant id; no error expected.
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

  it('maps sampling and agent variant errors to their fields', () => {
    expect(
      toUserFacingErrorMessage(new Error('variant medium temperature must not be negative')),
    ).toMatch(/Temperature/)
    expect(toUserFacingErrorMessage(new Error('variant must not be blank'))).toMatch(/Variant/)
  })
})
