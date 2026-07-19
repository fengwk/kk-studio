import { describe, expect, it } from 'vitest'
import {
  filterAgents,
  filterModels,
  filterProviders,
  formatBackendDate,
  formatJsonSummary,
  includesSearch,
  resourceTitle,
} from '@/features/ai/ai-console-utils'
import {
  emptyAgentDraft,
  emptyModelDraft,
  emptyProviderDraft,
  toAgentDraft,
  toEditableAgent,
  toEditableAgentUpdate,
  toEditableModel,
  toEditableModelUpdate,
  toEditableProvider,
  toEditableProviderUpdate,
  toModelDraft,
  toProviderDraft,
} from '@/features/ai/ai-resource-draft-codecs'

describe('ai-console-utils', () => {
  it('builds editable drafts from DTOs and default factories', () => {
    expect(emptyProviderDraft()).toMatchObject({
      providerType: 'openai',
      modelCallTimeoutMillis: '1800000',
      modelCallIdleTimeoutMillis: '120000',
    })
    expect(emptyModelDraft(undefined, { name: 'minimax' } as never)).toMatchObject({ provider: 'minimax', defaultVariant: 'default' })
    expect(emptyAgentDraft({ providerName: 'minimax', name: 'MiniMax-M2.7', defaultVariant: 'default' } as never)).toMatchObject({
      defaultProvider: 'minimax',
      defaultModel: 'MiniMax-M2.7',
      defaultVariant: 'default',
    })

    expect(
      toProviderDraft({
        id: 'provider-1',
        name: 'minimax',
        description: 'provider desc',
        providerType: 'openai_response',
        baseUrl: 'https://api.minimax.io/v1',
        configured: true,
        modelCallTimeoutMillis: 120000,
        modelCallIdleTimeoutMillis: 3000,
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toEqual({
      name: 'minimax',
      description: 'provider desc',
      providerType: 'openai_response',
      baseUrl: 'https://api.minimax.io/v1',
      credential: '',
      modelCallTimeoutMillis: '120000',
      modelCallIdleTimeoutMillis: '3000',
    })

    expect(
      toModelDraft({
        id: 'model-1',
        providerId: 'provider-1',
        providerName: 'minimax',
        name: 'MiniMax-M2.7',
        description: 'model desc',
        defaultVariant: 'default',
        variantsJson: '[{"name":"default","temperature":0.2,"maxOutputTokens":256,"topK":32}]',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toMatchObject({
      provider: 'minimax',
      name: 'MiniMax-M2.7',
      defaultVariant: 'default',
    })

    expect(
      toAgentDraft({
        id: 'agent-1',
        name: 'assistant',
        description: 'desc',
        systemPrompt: 'prompt',
        defaultProviderId: 'provider-1',
        defaultProviderName: 'minimax',
        defaultModelId: 'model-1',
        defaultModelName: 'MiniMax-M2.7',
        defaultVariant: 'default',
        toolsJson: '["search"]',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toMatchObject({
      name: 'assistant',
      defaultProvider: 'minimax',
      defaultModel: 'MiniMax-M2.7',
      tools: ['search'],
    })
  })

  it('serializes structured drafts into backend payloads', () => {
    expect(
      toEditableProvider({
        name: ' minimax ',
        description: ' provider desc ',
        providerType: ' openai ',
        baseUrl: ' https://api.minimax.io/v1 ',
        credential: ' secret ',
        modelCallTimeoutMillis: '120000',
        modelCallIdleTimeoutMillis: '3000',
      }),
    ).toEqual({
      name: 'minimax',
      description: 'provider desc',
      providerType: 'openai',
      baseUrl: 'https://api.minimax.io/v1',
      credential: 'secret',
      modelCallTimeoutMillis: 120000,
      modelCallIdleTimeoutMillis: 3000,
    })
    expect(
      toEditableProviderUpdate({
        name: ' minimax ',
        description: ' provider desc ',
        providerType: ' openai ',
        baseUrl: ' https://api.minimax.io/v1 ',
        credential: ' secret ',
        modelCallTimeoutMillis: '120000',
        modelCallIdleTimeoutMillis: '3000',
      }),
    ).toEqual({
      name: 'minimax',
      description: 'provider desc',
      providerType: 'openai',
      baseUrl: 'https://api.minimax.io/v1',
      credential: 'secret',
      modelCallTimeoutMillis: 120000,
      modelCallIdleTimeoutMillis: 3000,
    })

    expect(
      toEditableModel({
        provider: ' minimax ',
        name: ' MiniMax-M2.7 ',
        description: ' chat model ',
        defaultVariant: ' default ',
        variants: [
          {
            id: 'variant-1',
            name: ' default ',
            temperature: '0.1',
            maxOutputTokens: '256',
            extras: [
              { id: 'extra-1', key: 'topK', value: '32' },
              { id: 'extra-2', key: 'supportsVision', value: 'true' },
            ],
          },
        ],
      }),
    ).toEqual({
      provider: 'minimax',
      name: 'MiniMax-M2.7',
      description: 'chat model',
      defaultVariant: 'default',
      variantsJson: '[{"name":"default","temperature":0.1,"maxOutputTokens":256,"topK":32,"supportsVision":true}]',
    })
    expect(
      toEditableModelUpdate({
        provider: ' minimax ',
        name: ' MiniMax-M2.7 ',
        description: ' chat model ',
        defaultVariant: ' default ',
        variants: [
          {
            id: 'variant-1',
            name: ' default ',
            temperature: '0.1',
            maxOutputTokens: '256',
            extras: [{ id: 'extra-1', key: 'topK', value: '32' }],
          },
        ],
      }),
    ).toEqual({
      description: 'chat model',
      defaultVariant: 'default',
      variantsJson: '[{"name":"default","temperature":0.1,"maxOutputTokens":256,"topK":32}]',
      name: 'MiniMax-M2.7',
    })

    expect(
      toEditableAgent({
        name: ' assistant ',
        description: ' desc ',
        systemPrompt: ' prompt ',
        defaultProvider: ' minimax ',
        defaultModel: ' MiniMax-M2.7 ',
        defaultVariant: ' default ',
        tools: [' search ', ''],
      }),
    ).toEqual({
      name: 'assistant',
      description: 'desc',
      systemPrompt: 'prompt',
      defaultProvider: 'minimax',
      defaultModel: 'MiniMax-M2.7',
      defaultVariant: 'default',
      toolsJson: '["search"]',
    })
    expect(
      toEditableAgentUpdate({
        name: ' assistant ',
        description: ' desc ',
        systemPrompt: ' prompt ',
        defaultProvider: ' minimax ',
        defaultModel: ' MiniMax-M2.7 ',
        defaultVariant: ' default ',
        tools: [' search ', ''],
      }),
    ).toEqual({
      description: 'desc',
      systemPrompt: 'prompt',
      defaultVariant: 'default',
      toolsJson: '["search"]',
      name: 'assistant',
      defaultProvider: 'minimax',
      defaultModel: 'MiniMax-M2.7',
    })
  })

  it('filters resources and formats helper values', () => {
    const agents = [
      {
        id: 'agent-1',
        name: 'assistant',
        description: 'Cloud agent',
        systemPrompt: null,
        defaultProviderId: 'provider-1',
        defaultProviderName: 'minimax',
        defaultModelId: 'model-1',
        defaultModelName: 'MiniMax-M2.7',
        defaultVariant: 'default',
        toolsJson: '[]',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      },
    ]
    const models = [
      {
        id: 'model-1',
        providerId: 'provider-1',
        providerName: 'minimax',
        name: 'MiniMax-M2.7',
        description: 'Chat model',
        defaultVariant: 'default',
        variantsJson: '[{"name":"default"}]',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      },
    ]
    const providers = [
      {
        id: 'provider-1',
        name: 'minimax',
        description: 'MiniMax endpoint',
        providerType: 'openai',
        baseUrl: 'https://api.minimax.io/v1',
        configured: true,
        modelCallTimeoutMillis: 1800000,
        modelCallIdleTimeoutMillis: 120000,
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      },
    ]

    expect(filterAgents(agents, 'cloud')).toHaveLength(1)
    expect(filterModels(models, 'm2.7')).toHaveLength(1)
    expect(filterProviders(providers, 'endpoint')).toHaveLength(1)
    expect(includesSearch('MiniMax', 'mini')).toBe(true)
    expect(includesSearch('MiniMax', '')).toBe(true)

    expect(resourceTitle({ kind: 'provider', mode: 'create' })).toBe('新建 Provider')
    expect(resourceTitle({ kind: 'model', mode: 'edit' })).toBe('编辑 Model')
    expect(resourceTitle({ kind: 'agent', mode: 'create' })).toBe('新建 Agent')
    expect(formatJsonSummary(null)).toBe('default')
    expect(formatJsonSummary('[{"name":"default"}]')).toBe('1 item')
    expect(formatJsonSummary('{"vision":true,"audio":false}')).toBe('2 keys')
    expect(formatJsonSummary('{broken')).toBe('invalid json')
    expect(formatBackendDate([2026, 6, 20, 2, 1, 0, 0])).toBe('2026-06-20 02:01')
    expect(formatBackendDate('2026-06-20T02:01:00')).toBe('2026-06-20 02:01')
    expect(formatBackendDate([Number.NaN])).toBe('-')
  })

  it('handles malformed backend drafts and serialization fallbacks', () => {
    expect(
      toProviderDraft({
        id: 'provider-2',
        name: 'stub',
        description: null,
        providerType: null,
        baseUrl: null,
        configured: false,
        modelCallTimeoutMillis: 1800000,
        modelCallIdleTimeoutMillis: 120000,
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toEqual({
      name: 'stub',
      description: '',
      providerType: 'openai',
      baseUrl: '',
      credential: '',
      modelCallTimeoutMillis: '1800000',
      modelCallIdleTimeoutMillis: '120000',
    })

    expect(
      toModelDraft({
        id: 'model-2',
        providerId: 'provider-2',
        providerName: 'stub',
        name: 'fallback-model',
        description: null,
        defaultVariant: null,
        variantsJson: '{broken',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toMatchObject({
      provider: 'stub',
      description: '',
      defaultVariant: 'default',
      variants: [{ name: 'default', temperature: '', maxOutputTokens: '', extras: [] }],
    })

    expect(
      toModelDraft({
        id: 'model-3',
        providerId: 'provider-2',
        providerName: 'stub',
        name: 'primitive-variant-model',
        description: null,
        defaultVariant: null,
        variantsJson: '[1]',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toMatchObject({
      variants: [{ name: '', temperature: '', maxOutputTokens: '', extras: [] }],
    })

    expect(
      toAgentDraft({
        id: 'agent-2',
        name: 'fallback-agent',
        description: null,
        systemPrompt: null,
        defaultProviderId: 'provider-2',
        defaultProviderName: 'stub',
        defaultModelId: 'model-2',
        defaultModelName: 'fallback-model',
        defaultVariant: null,
        toolsJson: '{"broken":true}',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toMatchObject({
      description: '',
      systemPrompt: '',
      defaultVariant: 'default',
      tools: [],
    })

    expect(
      toEditableProvider({
        name: ' stub ',
        description: '   ',
        providerType: ' openai ',
        baseUrl: '   ',
        credential: '   ',
        modelCallTimeoutMillis: '   ',
        modelCallIdleTimeoutMillis: '   ',
      }),
    ).toEqual({
      name: 'stub',
      description: null,
      providerType: 'openai',
      baseUrl: null,
      credential: null,
      modelCallTimeoutMillis: null,
      modelCallIdleTimeoutMillis: null,
    })

    expect(
      toEditableModel({
        provider: ' stub ',
        name: ' fallback-model ',
        description: '   ',
        defaultVariant: ' fallback ',
        variants: [
          {
            id: 'variant-1',
            name: '   ',
            temperature: '   ',
            maxOutputTokens: 'bad',
            extras: [
              { id: 'extra-1', key: 'flag', value: 'false' },
              { id: 'extra-2', key: 'nil', value: 'null' },
              { id: 'extra-3', key: 'offset', value: '-2' },
              { id: 'extra-4', key: 'note', value: ' text ' },
              { id: 'extra-5', key: '  ', value: 'ignored' },
            ],
          },
        ],
      }),
    ).toEqual({
      provider: 'stub',
      name: 'fallback-model',
      description: null,
      defaultVariant: 'fallback',
      variantsJson: '[{"name":"fallback"}]',
    })

    expect(
      toEditableModel({
        provider: 'stub',
        name: 'empty-variant-model',
        description: '',
        defaultVariant: '   ',
        variants: [{ id: 'variant-2', name: '   ', temperature: '', maxOutputTokens: '', extras: [] }],
      }),
    ).toMatchObject({
      defaultVariant: null,
      variantsJson: null,
    })

    expect(
      toEditableModel({
        provider: 'stub',
        name: 'coerce-model',
        description: '',
        defaultVariant: 'default',
        variants: [
          {
            id: 'variant-3',
            name: 'default',
            temperature: '',
            maxOutputTokens: '',
            extras: [
              { id: 'extra-6', key: 'flag', value: 'false' },
              { id: 'extra-7', key: 'nil', value: 'null' },
              { id: 'extra-8', key: 'offset', value: '-2' },
              { id: 'extra-9', key: 'ratio', value: '1.25' },
              { id: 'extra-10', key: 'label', value: ' text ' },
            ],
          },
        ],
      }).variantsJson,
    ).toBe('[{"name":"default","flag":false,"nil":null,"offset":-2,"ratio":1.25,"label":"text"}]')

    expect(
      toEditableAgent({
        name: ' agent ',
        description: '   ',
        systemPrompt: '   ',
        defaultProvider: ' stub ',
        defaultModel: ' fallback-model ',
        defaultVariant: '   ',
        tools: ['   '],
      }),
    ).toEqual({
      name: 'agent',
      description: null,
      systemPrompt: null,
      defaultProvider: 'stub',
      defaultModel: 'fallback-model',
      defaultVariant: null,
      toolsJson: null,
    })

    expect(includesSearch('MiniMax', 'missing')).toBe(false)
    expect(formatJsonSummary('123')).toBe('default')
    expect(formatBackendDate(null)).toBe('-')
  })
})
