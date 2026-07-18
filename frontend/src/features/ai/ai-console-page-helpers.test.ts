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
      providerDraft: { providerType: 'openai', timeoutMillis: '60000' },
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
      modelDraft: { provider: 'minimax', defaultVariant: 'default' },
    })
    expect(editModelEditorPlan(models, 'model-1')).toMatchObject({
      kind: 'model',
      modal: { kind: 'model', mode: 'edit', id: 'model-1' },
      modelDraft: { name: 'MiniMax-M2.7', provider: 'minimax' },
    })
    expect(editModelEditorPlan(models, 'missing')).toBeNull()

    expect(createAgentEditorPlan(models)).toMatchObject({
      kind: 'agent',
      modal: { kind: 'agent', mode: 'create' },
      agentDraft: { defaultProvider: 'minimax', defaultModel: 'MiniMax-M2.7' },
    })
    expect(editAgentEditorPlan(agents, models, 'agent-1')).toMatchObject({
      kind: 'agent',
      modal: { kind: 'agent', mode: 'edit', id: 'agent-1' },
      agentDraft: { name: 'default-assistant', defaultProvider: 'minimax' },
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
          apiKey: ' secret ',
          timeoutMillis: '120000',
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
        apiKey: 'secret',
        timeoutMillis: 120000,
      },
    })

    const modelPlan = buildResourceSubmitPlan(
      { kind: 'model', mode: 'create' },
      {
        providerDraft: emptyProviderDraft(),
        modelDraft: {
          provider: ' minimax ',
          name: ' MiniMax-M2.7 ',
          description: ' chat model ',
          defaultVariant: ' default ',
          variants: [{ id: 'variant-1', name: ' default ', temperature: '0.1', maxOutputTokens: '256', extras: [] }],
        },
        agentDraft: emptyAgentDraft(),
      },
    )
    expect(modelPlan).toEqual({
      kind: 'model',
      mode: 'create',
      data: {
        provider: 'minimax',
        name: 'MiniMax-M2.7',
        description: 'chat model',
        defaultVariant: 'default',
        variantsJson: '[{"name":"default","temperature":0.1,"maxOutputTokens":256}]',
      },
    })

    const agentPlan = buildResourceSubmitPlan(
      { kind: 'agent', mode: 'create' },
      {
        providerDraft: emptyProviderDraft(),
        modelDraft: emptyModelDraft(),
        agentDraft: {
          name: ' assistant ',
          description: ' desc ',
          systemPrompt: ' prompt ',
          defaultProvider: ' minimax ',
          defaultModel: ' MiniMax-M2.7 ',
          defaultVariant: ' default ',
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
        defaultProvider: 'minimax',
        defaultModel: 'MiniMax-M2.7',
        defaultVariant: 'default',
        toolsJson: '["search"]',
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
          apiKey: '',
          timeoutMillis: '60000',
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
        apiKey: null,
        timeoutMillis: 60000,
      },
    })

    const modelPlan = buildResourceSubmitPlan(
      { kind: 'model', mode: 'edit', id: 'model-1' },
      {
        providerDraft: emptyProviderDraft(),
        modelDraft: {
          provider: 'minimax',
          name: ' MiniMax-M2.7 ',
          description: ' updated ',
          defaultVariant: ' default ',
          variants: [{ id: 'variant-1', name: ' default ', temperature: '', maxOutputTokens: '', extras: [] }],
        },
        agentDraft: emptyAgentDraft(),
      },
    )
    expect(modelPlan).toEqual({
      kind: 'model',
      mode: 'edit',
      id: 'model-1',
      data: {
        description: 'updated',
        defaultVariant: 'default',
        variantsJson: '[{"name":"default"}]',
        name: 'MiniMax-M2.7',
      },
    })

    const agentPlan = buildResourceSubmitPlan(
      { kind: 'agent', mode: 'edit', id: 'agent-1' },
      {
        providerDraft: emptyProviderDraft(),
        modelDraft: emptyModelDraft(),
        agentDraft: {
          name: ' assistant ',
          description: ' updated ',
          systemPrompt: ' prompt ',
          defaultProvider: ' minimax ',
          defaultModel: ' MiniMax-M2.7 ',
          defaultVariant: ' default ',
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
        defaultVariant: 'default',
        toolsJson: '["search"]',
        name: 'assistant',
        defaultProvider: 'minimax',
        defaultModel: 'MiniMax-M2.7',
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
    apiKey: '',
    timeoutMillis: '60000',
  }
}

function emptyModelDraft() {
  return {
    provider: '',
    name: '',
    description: '',
    defaultVariant: 'default',
    variants: [{ id: 'variant-1', name: 'default', temperature: '', maxOutputTokens: '', extras: [] }],
  }
}

function emptyAgentDraft() {
  return {
    name: '',
    description: '',
    systemPrompt: '',
    defaultProvider: '',
    defaultModel: '',
    defaultVariant: 'default',
    tools: [],
  }
}

function provider() {
  return {
    id: 'provider-1',
    name: 'minimax',
    description: 'MiniMax endpoint',
    providerType: 'openai',
    baseUrl: 'https://api.minimax.chat/v1',
    apiKey: 'test-key',
    timeoutMillis: 60000,
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
    defaultVariant: 'default',
    variantsJson: '[{"name":"default","temperature":0.2}]',
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
    defaultProviderId: 'provider-1',
    defaultProviderName: 'minimax',
    defaultModelId: 'model-1',
    defaultModelName: 'MiniMax-M2.7',
    defaultVariant: 'default',
    toolsJson: '[]',
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:00:00',
  }
}
