import { describe, expect, it } from 'vitest'
import {
  applyModelSelection,
  emptyAgentDraft,
  emptyModelDraft,
  emptyProviderDraft,
  filterAgents,
  filterModels,
  filterProviders,
  filterSessions,
  formatBackendDate,
  formatJsonSummary,
  includesSearch,
  resourceTitle,
  toAgentDraft,
  toEditableAgent,
  toEditableAgentUpdate,
  toEditableModel,
  toEditableModelUpdate,
  toEditableProvider,
  toEditableProviderUpdate,
  toSessionTitleUpdate,
  toModelDraft,
  toProviderDraft,
} from '@/features/ai/ai-console-utils'

describe('ai-console-utils', () => {
  it('builds editable drafts from DTOs and default factories', () => {
    expect(emptyProviderDraft()).toMatchObject({ providerType: 'openai', timeoutMillis: '60000', streamIdleTimeoutMillis: '60000' })
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
        apiKey: 'secret',
        timeoutMillis: 120000,
        streamIdleTimeoutMillis: 180000,
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toEqual({
      name: 'minimax',
      description: 'provider desc',
      providerType: 'openai_response',
      baseUrl: 'https://api.minimax.io/v1',
      apiKey: 'secret',
      timeoutMillis: '120000',
      streamIdleTimeoutMillis: '180000',
    })

    expect(
      toModelDraft({
        id: 'model-1',
        providerId: 'provider-1',
        providerName: 'minimax',
        name: 'MiniMax-M2.7',
        description: 'model desc',
        capabilitiesJson: '{"tools":true,"input":["text","image"],"output":["text"],"vision":true}',
        limitJson: '{"context":128000,"input":32000,"output":4096}',
        pricingJson: '{"input":0.1,"output":0.3,"cacheRead":0.02}',
        defaultVariant: 'default',
        variantsJson: '[{"name":"default","temperature":0.2,"maxOutputTokens":256,"topK":32}]',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toMatchObject({
      provider: 'minimax',
      name: 'MiniMax-M2.7',
      defaultVariant: 'default',
      capabilities: [
        { key: 'tools', value: 'true' },
        { key: 'input', value: 'text,image' },
        { key: 'output', value: 'text' },
        { key: 'vision', value: 'true' },
      ],
      limits: [
        { key: 'context', value: '128000' },
        { key: 'input', value: '32000' },
        { key: 'output', value: '4096' },
      ],
      pricing: [
        { key: 'input', value: '0.1' },
        { key: 'output', value: '0.3' },
        { key: 'cacheRead', value: '0.02' },
      ],
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
        subagentsJson: '["critic"]',
        skillsJson: '["brainstorm"]',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toMatchObject({
      name: 'assistant',
      defaultProvider: 'minimax',
      defaultModel: 'MiniMax-M2.7',
      tools: ['search'],
      subagents: ['critic'],
      skills: ['brainstorm'],
    })
  })

  it('serializes structured drafts into backend payloads', () => {
    expect(
      toEditableProvider({
        name: ' minimax ',
        description: ' provider desc ',
        providerType: ' openai ',
        baseUrl: ' https://api.minimax.io/v1 ',
        apiKey: ' secret ',
        timeoutMillis: '120000',
        streamIdleTimeoutMillis: 'oops',
      }),
    ).toEqual({
      name: 'minimax',
      description: 'provider desc',
      providerType: 'openai',
      baseUrl: 'https://api.minimax.io/v1',
      apiKey: 'secret',
      timeoutMillis: 120000,
      streamIdleTimeoutMillis: null,
    })
    expect(
      toEditableProviderUpdate({
        name: ' minimax ',
        description: ' provider desc ',
        providerType: ' openai ',
        baseUrl: ' https://api.minimax.io/v1 ',
        apiKey: ' secret ',
        timeoutMillis: '120000',
        streamIdleTimeoutMillis: 'oops',
      }),
    ).toEqual({
      name: 'minimax',
      description: 'provider desc',
      providerType: 'openai',
      baseUrl: 'https://api.minimax.io/v1',
      apiKey: 'secret',
      timeoutMillis: 120000,
      streamIdleTimeoutMillis: null,
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
        capabilities: [{ id: 'cap-1', key: 'vision', value: 'true' }],
        limits: [{ id: 'limit-1', key: 'contextWindow', value: '128000' }],
        pricing: [{ id: 'price-1', key: 'input', value: '0.1' }],
      }),
    ).toEqual({
      provider: 'minimax',
      name: 'MiniMax-M2.7',
      description: 'chat model',
      capabilitiesJson: '{"vision":true}',
      limitJson: '{"contextWindow":128000}',
      pricingJson: '{"input":0.1}',
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
        capabilities: [{ id: 'cap-1', key: 'vision', value: 'true' }],
        limits: [{ id: 'limit-1', key: 'contextWindow', value: '128000' }],
        pricing: [{ id: 'price-1', key: 'input', value: '0.1' }],
      }),
    ).toEqual({
      description: 'chat model',
      capabilitiesJson: '{"vision":true}',
      limitJson: '{"contextWindow":128000}',
      pricingJson: '{"input":0.1}',
      defaultVariant: 'default',
      variantsJson: '[{"name":"default","temperature":0.1,"maxOutputTokens":256,"topK":32}]',
      name: 'MiniMax-M2.7',
    })

    expect(
      toEditableModel({
        provider: ' minimax ',
        name: ' MiniMax-M2.7 ',
        description: '',
        defaultVariant: ' default ',
        variants: [{ id: 'variant-1', name: ' default ', temperature: '', maxOutputTokens: '', extras: [] }],
        capabilities: [
          { id: 'cap-1', key: 'tools', value: 'true' },
          { id: 'cap-2', key: 'input', value: 'text, image' },
          { id: 'cap-3', key: 'output', value: 'text' },
        ],
        limits: [
          { id: 'limit-1', key: 'context', value: '128000' },
          { id: 'limit-2', key: 'input', value: '32000' },
          { id: 'limit-3', key: 'output', value: '4096' },
        ],
        pricing: [
          { id: 'price-1', key: 'input', value: '0.1' },
          { id: 'price-2', key: 'output', value: '0.3' },
          { id: 'price-3', key: 'cacheRead', value: '0.02' },
          { id: 'price-4', key: 'cacheWrite', value: '0.05' },
        ],
      }),
    ).toMatchObject({
      capabilitiesJson: '{"tools":true,"input":["text","image"],"output":["text"]}',
      limitJson: '{"context":128000,"input":32000,"output":4096}',
      pricingJson: '{"input":0.1,"output":0.3,"cacheRead":0.02,"cacheWrite":0.05}',
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
        subagents: [' critic '],
        skills: [' brainstorm ', ''],
      }),
    ).toEqual({
      name: 'assistant',
      description: 'desc',
      systemPrompt: 'prompt',
      defaultProvider: 'minimax',
      defaultModel: 'MiniMax-M2.7',
      defaultVariant: 'default',
      toolsJson: '["search"]',
      subagentsJson: '["critic"]',
      skillsJson: '["brainstorm"]',
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
        subagents: [' critic '],
        skills: [' brainstorm ', ''],
      }),
    ).toEqual({
      description: 'desc',
      systemPrompt: 'prompt',
      defaultVariant: 'default',
      toolsJson: '["search"]',
      subagentsJson: '["critic"]',
      skillsJson: '["brainstorm"]',
      name: 'assistant',
      defaultProvider: 'minimax',
      defaultModel: 'MiniMax-M2.7',
    })
    expect(toSessionTitleUpdate(' renamed ')).toEqual({ title: 'renamed' })
    expect(toSessionTitleUpdate('   ')).toEqual({ title: null })
  })

  it('filters resources and formats helper values', () => {
    const sessions = [
      {
        sessionId: 'session-1',
        agentName: 'assistant',
        title: 'Script Review',
        status: 'active',
        currentHeadEventId: 'event-1',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:01:00',
      },
    ]
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
        subagentsJson: '[]',
        skillsJson: '[]',
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
        capabilitiesJson: null,
        limitJson: null,
        pricingJson: null,
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
        apiKey: null,
        timeoutMillis: null,
        streamIdleTimeoutMillis: null,
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      },
    ]

    expect(filterSessions(sessions, new Map([['assistant', agents[0]]]), 'script')).toHaveLength(1)
    expect(filterAgents(agents, 'cloud')).toHaveLength(1)
    expect(filterModels(models, 'm2.7')).toHaveLength(1)
    expect(filterProviders(providers, 'endpoint')).toHaveLength(1)
    expect(includesSearch('MiniMax', 'mini')).toBe(true)
    expect(includesSearch('MiniMax', '')).toBe(true)

    expect(resourceTitle({ kind: 'provider', mode: 'create' })).toBe('新建 Provider')
    expect(resourceTitle({ kind: 'model', mode: 'edit' })).toBe('编辑 Model')
    expect(resourceTitle({ kind: 'agent', mode: 'create' })).toBe('新建 Agent')
    expect(
      applyModelSelection(
        emptyAgentDraft(),
        'minimax/MiniMax-M2.7',
        [
          {
            id: 'model-1',
            providerId: 'provider-1',
            providerName: 'minimax',
            name: 'MiniMax-M2.7',
            description: null,
            capabilitiesJson: null,
            limitJson: null,
            pricingJson: null,
            defaultVariant: 'creative',
            variantsJson: '[{"name":"creative"},{"name":"default"}]',
            createTime: '2026-06-20T02:00:00',
            updateTime: '2026-06-20T02:00:00',
          },
        ],
      ),
    ).toMatchObject({ defaultProvider: 'minimax', defaultModel: 'MiniMax-M2.7', defaultVariant: 'creative' })
    expect(applyModelSelection(emptyAgentDraft(), 'invalid')).toEqual(emptyAgentDraft())

    expect(formatJsonSummary(null)).toBe('default')
    expect(formatJsonSummary('[{"name":"default"}]')).toBe('1 item')
    expect(formatJsonSummary('{"vision":true,"audio":false}')).toBe('2 keys')
    expect(formatJsonSummary('{broken')).toBe('invalid json')
    expect(formatBackendDate([2026, 6, 20, 2, 1, 0, 0])).toBe('2026-06-20 02:01')
    expect(formatBackendDate('2026-06-20T02:01:00')).toBe('2026-06-20 02:01')
    expect(formatBackendDate([Number.NaN] as never)).toBe('-')
  })

  it('handles malformed backend drafts and serialization fallbacks', () => {
    expect(
      toProviderDraft({
        id: 'provider-2',
        name: 'stub',
        description: null,
        providerType: null,
        baseUrl: null,
        apiKey: null,
        timeoutMillis: null,
        streamIdleTimeoutMillis: null,
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toEqual({
      name: 'stub',
      description: '',
      providerType: 'openai',
      baseUrl: '',
      apiKey: '',
      timeoutMillis: '',
      streamIdleTimeoutMillis: '',
    })

    expect(
      toModelDraft({
        id: 'model-2',
        providerId: 'provider-2',
        providerName: 'stub',
        name: 'fallback-model',
        description: null,
        capabilitiesJson: '[]',
        limitJson: '{broken',
        pricingJson: 'null',
        defaultVariant: null,
        variantsJson: '{broken',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toMatchObject({
      provider: 'stub',
      description: '',
      defaultVariant: 'default',
      capabilities: [],
      limits: [],
      pricing: [],
      variants: [{ name: 'default', temperature: '', maxOutputTokens: '', extras: [] }],
    })

    expect(
      toModelDraft({
        id: 'model-3',
        providerId: 'provider-2',
        providerName: 'stub',
        name: 'primitive-variant-model',
        description: null,
        capabilitiesJson: '{"enabled":false,"limit":null}',
        limitJson: '{"max":1.5}',
        pricingJson: '{}',
        defaultVariant: null,
        variantsJson: '[1]',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toMatchObject({
      capabilities: [
        { key: 'enabled', value: 'false' },
        { key: 'limit', value: '' },
      ],
      limits: [{ key: 'max', value: '1.5' }],
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
        subagentsJson: '[1," ",false,null]',
        skillsJson: '{broken',
        createTime: '2026-06-20T02:00:00',
        updateTime: '2026-06-20T02:00:00',
      }),
    ).toMatchObject({
      description: '',
      systemPrompt: '',
      defaultVariant: 'default',
      tools: [],
      subagents: ['1', 'false'],
      skills: [],
    })

    expect(
      toEditableProvider({
        name: ' stub ',
        description: '   ',
        providerType: ' openai ',
        baseUrl: '   ',
        apiKey: '   ',
        timeoutMillis: '   ',
        streamIdleTimeoutMillis: '1.5',
      }),
    ).toEqual({
      name: 'stub',
      description: null,
      providerType: 'openai',
      baseUrl: null,
      apiKey: null,
      timeoutMillis: null,
      streamIdleTimeoutMillis: 1.5,
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
        capabilities: [{ id: 'cap-1', key: '  ', value: 'ignored' }],
        limits: [],
        pricing: [],
      }),
    ).toEqual({
      provider: 'stub',
      name: 'fallback-model',
      description: null,
      capabilitiesJson: null,
      limitJson: null,
      pricingJson: null,
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
        capabilities: [],
        limits: [],
        pricing: [],
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
        capabilities: [],
        limits: [],
        pricing: [],
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
        subagents: [],
        skills: ['   '],
      }),
    ).toEqual({
      name: 'agent',
      description: null,
      systemPrompt: null,
      defaultProvider: 'stub',
      defaultModel: 'fallback-model',
      defaultVariant: null,
      toolsJson: null,
      subagentsJson: null,
      skillsJson: null,
    })

    expect(includesSearch('MiniMax', 'missing')).toBe(false)
    expect(formatJsonSummary('123')).toBe('default')
    expect(formatBackendDate(null as never)).toBe('-')
  })
})
