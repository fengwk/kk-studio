import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  useCatalogPageController,
  type CatalogPageScope,
} from '@/features/ai/catalog/useCatalogPageController'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/shared/api/environment-service'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listProviders: vi.fn(),
    listModels: vi.fn(),
    listAgents: vi.fn(),
    listTools: vi.fn(),
    createProvider: vi.fn(),
    updateProvider: vi.fn(),
    deleteProvider: vi.fn(),
    createModel: vi.fn(),
    updateModel: vi.fn(),
    deleteModel: vi.fn(),
    createAgent: vi.fn(),
    updateAgent: vi.fn(),
    deleteAgent: vi.fn(),
  },
}))
vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
  },
}))

const scopeQueryMatrix: Array<{
  scope: CatalogPageScope
  expected: {
    providers: number
    models: number
    agents: number
    tools: number
    environments: number
  }
}> = [
  {
    scope: 'agents',
    expected: { providers: 1, models: 1, agents: 1, tools: 1, environments: 1 },
  },
  {
    scope: 'models',
    expected: { providers: 1, models: 1, agents: 0, tools: 0, environments: 0 },
  },
  {
    scope: 'providers',
    expected: { providers: 1, models: 0, agents: 0, tools: 0, environments: 0 },
  },
]

describe('useCatalogPageController', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listProviders).mockResolvedValue(page([provider()]))
    vi.mocked(agentService.listModels).mockResolvedValue(page([model()]))
    vi.mocked(agentService.listAgents).mockResolvedValue(page([agent()]))
    vi.mocked(agentService.listTools).mockResolvedValue([])
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
  })

  it.each(scopeQueryMatrix)(
    'loads only the resource queries required by the $scope catalog page',
    async ({ scope, expected }) => {
      const { result } = renderHook(() => useCatalogPageController(scope), {
        wrapper: queryWrapper(new QueryClient(queryClientOptions)),
      })

      await waitFor(() => {
        expect(agentService.listProviders).toHaveBeenCalledTimes(expected.providers)
        expect(agentService.listModels).toHaveBeenCalledTimes(expected.models)
        expect(agentService.listAgents).toHaveBeenCalledTimes(expected.agents)
        expect(agentService.listTools).toHaveBeenCalledTimes(expected.tools)
        expect(environmentService.listEnvironments).toHaveBeenCalledTimes(
          expected.environments,
        )
        expect(result.current.busy).toBe(false)
        expect(result.current.error).toBeNull()
      })
    },
  )

  it('keeps provider, model, and agent editor and delete interactions in the Catalog runtime', async () => {
    const { result } = renderHook(() => useCatalogPageController('agents'), {
      wrapper: queryWrapper(new QueryClient(queryClientOptions)),
    })

    await waitFor(() => expect(result.current.busy).toBe(false))

    act(() => result.current.providerPanelProps.onCreate())
    expect(result.current.resourceEditorModal.modal).toMatchObject({
      kind: 'provider',
      mode: 'create',
    })
    act(() => result.current.resourceEditorModal.onClose())
    act(() => result.current.providerPanelProps.onEdit(provider()))
    expect(result.current.resourceEditorModal.modal).toMatchObject({
      kind: 'provider',
      mode: 'edit',
    })
    act(() => result.current.providerPanelProps.onDelete(provider()))
    expect(result.current.resourceDeleteConfirmModal.modal?.title).toBe(
      '删除 Provider',
    )

    act(() => result.current.modelPanelProps.onCreate())
    expect(result.current.resourceEditorModal.modal).toMatchObject({
      kind: 'model',
      mode: 'create',
    })
    act(() => result.current.resourceEditorModal.onClose())
    act(() =>
      result.current.modelPanelProps.onEdit(
        result.current.modelPanelProps.models[0],
      ),
    )
    expect(result.current.resourceEditorModal.modal).toMatchObject({
      kind: 'model',
      mode: 'edit',
    })
    act(() =>
      result.current.modelPanelProps.onDelete(
        result.current.modelPanelProps.models[0],
      ),
    )
    expect(result.current.resourceDeleteConfirmModal.modal?.title).toBe(
      '删除 Model',
    )

    act(() => result.current.agentPanelProps.onCreate())
    expect(result.current.resourceEditorModal.modal).toMatchObject({
      kind: 'agent',
      mode: 'create',
    })
    act(() => result.current.resourceEditorModal.onClose())
    act(() =>
      result.current.agentPanelProps.onEdit(
        result.current.agentPanelProps.agents[0],
      ),
    )
    expect(result.current.resourceEditorModal.modal).toMatchObject({
      kind: 'agent',
      mode: 'edit',
    })
    act(() =>
      result.current.agentPanelProps.onDelete(
        result.current.agentPanelProps.agents[0],
      ),
    )
    expect(result.current.resourceDeleteConfirmModal.modal?.title).toBe(
      '删除 Agent',
    )
  })
})

const queryClientOptions = {
  defaultOptions: {
    queries: { retry: false },
    mutations: { retry: false },
  },
}

function queryWrapper(queryClient: QueryClient) {
  return function QueryWrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        {children}
      </QueryClientProvider>
    )
  }
}

function page<T>(results: T[]) {
  return {
    pageNumber: 1,
    pageSize: 50,
    totalCount: results.length,
    results,
  }
}

function provider() {
  return {
    id: 'provider-1',
    name: 'stub',
    description: 'Local provider',
    providerType: 'openai',
    baseUrl: 'http://stub.local/v1',
    configured: true,
    modelCallTimeoutMillis: 1800000,
    modelCallIdleTimeoutMillis: 120000,
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

function model() {
  return {
    providerName: 'stub',
    name: 'model',
    description: 'Test model',
    config: {
      limit: { context: 128000, output: 8192 },
      abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
      pricing: {
        currency: 'USD',
        pricingTier: 'default',
        serviceTier: 'default',
        serviceTierMultiplier: 1,
        version: 'v1',
        inputPerMillionTokens: 0,
        outputPerMillionTokens: 0,
        cacheReadPerMillionTokens: 0,
        cacheWritePerMillionTokens: 0,
        reasoningPerMillionTokens: 0,
      },
      defaultVariant: 'default',
      variants: [{ id: 'default' }],
    },
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

function agent() {
  return {
    id: 'agent-1',
    name: 'default-assistant',
    description: null,
    systemPrompt: null,
    model: 'model-1',
    variant: 'default',
    config: {
      tools: [],
      skills: [],
      subagents: [],
    },
    version: '1',
    createTime: null,
    updateTime: null,
  }
}
