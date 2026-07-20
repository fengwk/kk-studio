import { describe, expect, it } from 'vitest'
import { resolveThreadAgentId } from '@/features/ai/ai-console-page-helpers'
import {
  buildResourceSubmitPlan,
  createAgentEditorPlan,
  createModelEditorPlan,
  createProviderEditorPlan,
  editAgentEditorPlan,
  editModelEditorPlan,
  editProviderEditorPlan,
} from '@/features/ai/ai-resource-editor-plans'
import { emptyAgentDraft as codecEmptyAgentDraft } from '@/features/ai/ai-agent-draft-codec'
import { emptyModelDraft as codecEmptyModelDraft } from '@/features/ai/ai-model-draft-codec'

describe('ai-console-page-helpers', () => {
  it('resolves the preferred thread agent id', () => {
    const agents = [agent()]

    expect(resolveThreadAgentId('explicit-agent', 'selected-agent', agents)).toBe('explicit-agent')
    expect(resolveThreadAgentId(undefined, 'selected-agent', agents)).toBe('selected-agent')
    expect(resolveThreadAgentId(undefined, '', agents)).toBe('agent-1')
    expect(resolveThreadAgentId(undefined, '', [])).toBe('')
  })

  it('builds create and edit editor plans with fallbacks', () => {
    const providers = [provider()]
    const models = [model()]
    const agents = [agent()]

    expect(createProviderEditorPlan()).toMatchObject({
      kind: 'provider',
      modal: { kind: 'provider', mode: 'create' },
      providerDraft: {
        providerType: 'openai',
        modelCallTimeoutMillis: '1800000',
        modelCallIdleTimeoutMillis: '120000',
      },
    })
    expect(editProviderEditorPlan(providers, 'provider-1')).toMatchObject({
      kind: 'provider',
      modal: { kind: 'provider', mode: 'edit', id: 'provider-1' },
      providerDraft: { name: 'minimax' },
    })
    expect(editProviderEditorPlan(providers, 'missing')).toBeNull()

    expect(createModelEditorPlan(models, providers)).toMatchObject({
      kind: 'model',
      modal: { kind: 'model', mode: 'create' },
      modelDraft: { providerId: 'provider-1', defaultVariant: 'medium' },
    })
    expect(editModelEditorPlan(models, 'model-1')).toMatchObject({
      kind: 'model',
      modal: { kind: 'model', mode: 'edit', id: 'model-1' },
      modelDraft: { name: 'MiniMax-M2.7', providerId: 'provider-1', defaultVariant: 'default' },
    })
    expect(editModelEditorPlan(models, 'missing')).toBeNull()

    expect(createAgentEditorPlan(models)).toMatchObject({
      kind: 'agent',
      modal: { kind: 'agent', mode: 'create' },
      agentDraft: { modelId: 'model-1', variant: 'default' },
    })
    expect(editAgentEditorPlan(agents, models, 'agent-1')).toMatchObject({
      kind: 'agent',
      modal: { kind: 'agent', mode: 'edit', id: 'agent-1' },
      agentDraft: { name: 'default-assistant', modelId: 'model-1' },
    })
    expect(editAgentEditorPlan(agents, models, 'missing')).toBeNull()
  })

  it('builds create resource submit plans', () => {
    const providerPlan = buildResourceSubmitPlan(
      { kind: 'provider', mode: 'create' },
      {
        providerDraft: {
          name: ' minimax ',
          description: ' provider desc ',
          providerType: ' openai ',
          baseUrl: ' https://api.minimax.io/v1 ',
          credential: ' secret ',
          modelCallTimeoutMillis: '120000',
          modelCallIdleTimeoutMillis: '3000',
        },
        modelDraft: emptyModelDraft(),
        agentDraft: emptyAgentDraft(),
      },
    )
    expect(providerPlan).toEqual({
      kind: 'provider',
      mode: 'create',
      data: {
        name: 'minimax',
        description: 'provider desc',
        providerType: 'openai',
        baseUrl: 'https://api.minimax.io/v1',
        credential: 'secret',
        modelCallTimeoutMillis: 120000,
        modelCallIdleTimeoutMillis: 3000,
      },
    })

    const modelPlan = buildResourceSubmitPlan(
      { kind: 'model', mode: 'create' },
      {
        providerDraft: emptyProviderDraft(),
        modelDraft: {
          ...emptyModelDraft(),
          providerId: ' provider-1 ',
          name: ' MiniMax-M2.7 ',
          description: ' chat model ',
          defaultVariant: 'default',
          variants: [
            {
              ...emptyModelDraft().variants[0],
              name: 'default',
              temperature: '0.1',
              maxOutputTokens: '256',
            },
          ],
        },
        agentDraft: emptyAgentDraft(),
      },
    )
    expect(modelPlan).toMatchObject({
      kind: 'model',
      mode: 'create',
      data: {
        providerId: 'provider-1',
        name: 'MiniMax-M2.7',
        description: 'chat model',
        capabilitiesJson: '["TEXT","TOOLS"]',
      },
    })
    if (modelPlan.kind !== 'model') {
      throw new Error('expected model plan')
    }
    expect(JSON.parse(modelPlan.data.configJson || '{}')).toMatchObject({
      limit: { context: 128000, output: 8192 },
      abilities: {
        tools: true,
        reasoning: false,
        modalities: { input: ['TEXT'], output: ['TEXT'] },
      },
      defaultVariant: 'default',
      variants: [{ id: 'default', temperature: 0.1, maxOutputTokens: 256 }],
    })

    const agentPlan = buildResourceSubmitPlan(
      { kind: 'agent', mode: 'create' },
      {
        providerDraft: emptyProviderDraft(),
        modelDraft: emptyModelDraft(),
        agentDraft: {
          ...emptyAgentDraft(),
          name: ' assistant ',
          description: ' desc ',
          systemPrompt: ' prompt ',
          modelId: ' model-1 ',
          variant: ' default ',
          tools: [' search '],
        },
      },
    )
    expect(agentPlan).toEqual({
      kind: 'agent',
      mode: 'create',
      data: {
        name: 'assistant',
        description: 'desc',
        systemPrompt: 'prompt',
        modelId: 'model-1',
        variant: 'default',
        config: {
          environmentName: null,
          tools: ['search'],
          skills: [],
          allowedSubagents: [],
          executionPolicy: null,
        },
      },
    })
  })

  it('builds edit resource submit plans', () => {
    const providerPlan = buildResourceSubmitPlan(
      { kind: 'provider', mode: 'edit', id: 'provider-1' },
      {
        providerDraft: {
          name: ' minimax ',
          description: ' updated ',
          providerType: ' openai ',
          baseUrl: '',
          credential: '',
          modelCallTimeoutMillis: '1800000',
          modelCallIdleTimeoutMillis: '120000',
        },
        modelDraft: emptyModelDraft(),
        agentDraft: emptyAgentDraft(),
      },
    )
    expect(providerPlan).toEqual({
      kind: 'provider',
      mode: 'edit',
      id: 'provider-1',
      data: {
        name: 'minimax',
        description: 'updated',
        providerType: 'openai',
        baseUrl: null,
        credential: null,
        modelCallTimeoutMillis: 1800000,
        modelCallIdleTimeoutMillis: 120000,
      },
    })

    const modelPlan = buildResourceSubmitPlan(
      { kind: 'model', mode: 'edit', id: 'model-1' },
      {
        providerDraft: emptyProviderDraft(),
        modelDraft: {
          ...emptyModelDraft(),
          providerId: 'provider-1',
          name: ' MiniMax-M2.7 ',
          description: ' updated ',
          defaultVariant: 'default',
          variants: [{ ...emptyModelDraft().variants[0], name: 'default' }],
        },
        agentDraft: emptyAgentDraft(),
      },
    )
    expect(modelPlan).toMatchObject({
      kind: 'model',
      mode: 'edit',
      id: 'model-1',
      data: {
        description: 'updated',
        capabilitiesJson: '["TEXT","TOOLS"]',
        name: 'MiniMax-M2.7',
      },
    })
    if (modelPlan.kind !== 'model') {
      throw new Error('expected model plan')
    }
    expect(JSON.parse(modelPlan.data.configJson || '{}')).toMatchObject({
      defaultVariant: 'default',
      variants: [{ id: 'default' }],
    })

    const agentPlan = buildResourceSubmitPlan(
      { kind: 'agent', mode: 'edit', id: 'agent-1' },
      {
        providerDraft: emptyProviderDraft(),
        modelDraft: emptyModelDraft(),
        agentDraft: {
          ...emptyAgentDraft(),
          name: ' assistant ',
          description: ' updated ',
          systemPrompt: ' prompt ',
          modelId: ' model-1 ',
          variant: ' default ',
          tools: [' search '],
        },
      },
    )
    expect(agentPlan).toEqual({
      kind: 'agent',
      mode: 'edit',
      id: 'agent-1',
      data: {
        description: 'updated',
        systemPrompt: 'prompt',
        variant: 'default',
        name: 'assistant',
        modelId: 'model-1',
        config: {
          environmentName: null,
          tools: ['search'],
          skills: [],
          allowedSubagents: [],
          executionPolicy: null,
        },
      },
    })
  })
})

function emptyProviderDraft() {
  return {
    name: '',
    description: '',
    providerType: 'openai',
    baseUrl: '',
    credential: '',
    modelCallTimeoutMillis: '1800000',
    modelCallIdleTimeoutMillis: '120000',
  }
}

function emptyModelDraft() {
  return codecEmptyModelDraft()
}

function emptyAgentDraft() {
  return codecEmptyAgentDraft()
}

function provider() {
  return {
    id: 'provider-1',
    name: 'minimax',
    description: 'MiniMax endpoint',
    providerType: 'openai',
    baseUrl: 'https://api.minimax.chat/v1',
    configured: true,
    modelCallTimeoutMillis: 1800000,
    modelCallIdleTimeoutMillis: 120000,
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:00:00',
  }
}

function model() {
  return {
    id: 'model-1',
    providerId: 'provider-1',
    providerName: 'minimax',
    name: 'MiniMax-M2.7',
    description: 'Chat model',
    capabilitiesJson: '["TEXT","TOOLS"]',
    configJson:
      '{"limit":{"context":128000,"output":8192},"abilities":{"tools":true,"reasoning":false,"modalities":{"input":["TEXT"],"output":["TEXT"]}},"pricing":{"currency":"USD","pricingTier":"default","serviceTier":"default","serviceTierMultiplier":1,"version":"v1","inputPerMillionTokens":0,"outputPerMillionTokens":0,"cacheReadPerMillionTokens":0,"cacheWritePerMillionTokens":0,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0},"defaultVariant":"default","variants":[{"id":"default","temperature":0.2}]}',
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:00:00',
  }
}

function agent() {
  return {
    id: 'agent-1',
    name: 'default-assistant',
    description: 'Cloud agent',
    systemPrompt: 'You are helpful',
    modelId: 'model-1',
    variant: 'default',
    config: { tools: [], skills: [], allowedSubagents: [] },
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:00:00',
  }
}
