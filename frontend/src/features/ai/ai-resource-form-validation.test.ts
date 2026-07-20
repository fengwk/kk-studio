import { describe, expect, it } from 'vitest'
import { emptyModelDraft } from '@/features/ai/ai-model-draft-codec'
import type { ModelDraft } from '@/features/ai/ai-console-types'
import {
  toUserFacingErrorMessage,
  validateResourceDraft,
} from '@/features/ai/ai-resource-form-validation'

function draft(overrides: Partial<ModelDraft> = {}): ModelDraft {
  return {
    ...emptyModelDraft(),
    providerId: 'provider-1',
    name: 'model',
    ...overrides,
  }
}

describe('ai-resource-form-validation', () => {
  it('rejects a model draft with a blank provider', () => {
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create' },
      {
        providerDraft: { name: '', description: '', providerType: 'openai', baseUrl: '', credential: '', modelCallTimeoutMillis: '', modelCallIdleTimeoutMillis: '' },
        modelDraft: draft({ providerId: '   ' }),
        agentDraft: { name: '', description: '', systemPrompt: '', modelId: '', variant: '', environmentName: '', tools: [], skills: [], allowedSubagents: [], executionPolicy: { maxTurns: '', maxDepth: '', maxDirectSubagents: '', maxTotalSubagents: '' } },
      },
    )
    expect(result.ok).toBe(false)
    expect(result.fields.providerId).toBeDefined()
  })

  it('rejects a model draft with a blank name', () => {
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create' },
      {
        providerDraft: { name: 'p', description: '', providerType: 'openai', baseUrl: '', credential: '', modelCallTimeoutMillis: '', modelCallIdleTimeoutMillis: '' },
        modelDraft: draft({ name: '   ' }),
        agentDraft: { name: '', description: '', systemPrompt: '', modelId: '', variant: '', environmentName: '', tools: [], skills: [], allowedSubagents: [], executionPolicy: { maxTurns: '', maxDepth: '', maxDirectSubagents: '', maxTotalSubagents: '' } },
      },
    )
    expect(result.ok).toBe(false)
    expect(result.fields.name).toBeDefined()
  })

  it('normalizes empty modalities to TEXT-only on submit', () => {
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create' },
      {
        providerDraft: { name: 'p', description: '', providerType: 'openai', baseUrl: '', credential: '', modelCallTimeoutMillis: '', modelCallIdleTimeoutMillis: '' },
        modelDraft: draft({ inputModalities: [] }),
        agentDraft: { name: '', description: '', systemPrompt: '', modelId: '', variant: '', environmentName: '', tools: [], skills: [], allowedSubagents: [], executionPolicy: { maxTurns: '', maxDepth: '', maxDirectSubagents: '', maxTotalSubagents: '' } },
      },
    )
    expect(result.ok).toBe(true)
    // The normalized draft is reflected back onto the input so submit can read it.
    expect(result.fields).toEqual({})
  })

  it('normalizes defaultVariant when the persisted value no longer exists', () => {
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create' },
      {
        providerDraft: { name: 'p', description: '', providerType: 'openai', baseUrl: '', credential: '', modelCallTimeoutMillis: '', modelCallIdleTimeoutMillis: '' },
        modelDraft: draft({
          defaultVariant: 'stale',
          variants: [
            { id: 'kv-default', name: 'fast', reasoningEffort: '', maxOutputTokens: '', temperature: '', topP: '', topK: '', frequencyPenalty: '', presencePenalty: '', stopSequences: '' },
            { id: 'kv-2', name: 'creative', reasoningEffort: '', maxOutputTokens: '', temperature: '', topP: '', topK: '', frequencyPenalty: '', presencePenalty: '', stopSequences: '' },
          ],
        }),
        agentDraft: { name: '', description: '', systemPrompt: '', modelId: '', variant: '', environmentName: '', tools: [], skills: [], allowedSubagents: [], executionPolicy: { maxTurns: '', maxDepth: '', maxDirectSubagents: '', maxTotalSubagents: '' } },
      },
    )
    expect(result.ok).toBe(true)
    expect(result.fields).toEqual({})
  })

  it('rejects when reasoning is enabled but every variant lacks effort', () => {
    const result = validateResourceDraft(
      { kind: 'model', mode: 'create' },
      {
        providerDraft: { name: 'p', description: '', providerType: 'openai', baseUrl: '', credential: '', modelCallTimeoutMillis: '', modelCallIdleTimeoutMillis: '' },
        modelDraft: draft({ reasoning: true, variants: [{ id: 'kv-x', name: 'medium', reasoningEffort: '', maxOutputTokens: '', temperature: '', topP: '', topK: '', frequencyPenalty: '', presencePenalty: '', stopSequences: '' }] }),
        agentDraft: { name: '', description: '', systemPrompt: '', modelId: '', variant: '', environmentName: '', tools: [], skills: [], allowedSubagents: [], executionPolicy: { maxTurns: '', maxDepth: '', maxDirectSubagents: '', maxTotalSubagents: '' } },
      },
    )
    expect(result.ok).toBe(true)
    // Reasoning effort must be auto-filled with the variant name; no error expected.
  })

  it('translates known backend errors into user-facing Chinese', () => {
    expect(toUserFacingErrorMessage(new Error('config.limit.context must be a positive integer'))).toMatch(
      /上下文窗口/,
    )
    expect(toUserFacingErrorMessage(new Error('config.defaultVariant must match a variant id'))).toMatch(
      /默认 Variant/,
    )
    expect(toUserFacingErrorMessage(new Error('duplicate variant id: fast'))).toMatch(/不能重复/)
    expect(toUserFacingErrorMessage(new Error('401 Unauthorized'))).toMatch(/没有权限/)
  })

  it('passes Chinese text through unchanged', () => {
    expect(toUserFacingErrorMessage(new Error('已开启 Reasoning'))).toMatch(/Reasoning/)
  })
})