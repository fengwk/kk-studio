import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAiConsoleResourceController } from '@/features/ai/catalog/useAiConsoleResourceController'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'

vi.mock('@/shared/api/environment-service', () => ({ environmentService: { listEnvironments: vi.fn(async () => []) } }))
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

describe('useAiConsoleResourceController', () => {
  let currentProvider = provider()
  let currentModel = model()
  let currentAgent = agent()

  beforeEach(() => {
    vi.clearAllMocks()
    currentProvider = provider()
    currentModel = model()
    currentAgent = agent()

    vi.mocked(agentService.listProviders).mockImplementation(async () => page([currentProvider]))
    vi.mocked(agentService.listModels).mockImplementation(async () => page([currentModel]))
    vi.mocked(agentService.listAgents).mockImplementation(async () => page([currentAgent]))
    vi.mocked(agentService.listTools).mockResolvedValue([])
    vi.mocked(agentService.createProvider).mockResolvedValue(currentProvider)
    vi.mocked(agentService.deleteProvider).mockResolvedValue(undefined)
    vi.mocked(agentService.createModel).mockResolvedValue(currentModel)
    vi.mocked(agentService.deleteModel).mockResolvedValue(undefined)
    vi.mocked(agentService.createAgent).mockResolvedValue(currentAgent)
    vi.mocked(agentService.deleteAgent).mockResolvedValue(undefined)
    vi.mocked(agentService.updateProvider).mockImplementation(async (_name, data) => {
      currentProvider = { ...currentProvider, ...data, name: data.name ?? currentProvider.name }
      currentModel = { ...currentModel, providerName: currentProvider.name }
      currentAgent = { ...currentAgent, model: `${currentModel.providerName}/${currentModel.name}` }
      return currentProvider
    })
    vi.mocked(agentService.updateModel).mockImplementation(async (_providerName, _modelName, data) => {
      currentModel = { ...currentModel, ...data }
      currentAgent = { ...currentAgent, model: `${currentModel.providerName}/${currentModel.name}` }
      return currentModel
    })
    vi.mocked(agentService.updateAgent).mockImplementation(async (_name, data) => {
      currentAgent = { ...currentAgent, ...data }
      return currentAgent
    })
  })

  it('opens catalog resources by immutable names', async () => {
    const user = userEvent.setup()
    renderHarness()

    await waitFor(() => {
      expect(screen.getByTestId('ready')).toHaveTextContent('ready')
    })

    await user.click(screen.getByRole('button', { name: 'open-provider' }))
    await user.click(screen.getByRole('button', { name: 'submit' }))

    await waitFor(() => {
      expect(agentService.updateProvider).toHaveBeenCalledWith(
        'stub',
        expect.objectContaining({ expectedVersion: '0' }),
      )
    })

    await user.click(screen.getByRole('button', { name: 'open-model' }))
    expect(screen.getByTestId('model-provider')).toHaveValue('stub')
    await user.click(screen.getByRole('button', { name: 'submit' }))

    await waitFor(() => {
      expect(agentService.updateModel).toHaveBeenCalledWith(
        'stub',
        'acceptance-stub',
        expect.objectContaining({ expectedVersion: '0' }),
      )
    })

    await user.click(screen.getByRole('button', { name: 'open-agent' }))
    expect(screen.getByTestId('agent-model')).toHaveValue('stub/acceptance-stub')
  })

  it('keeps unsaved agent fields while synchronizing a stale model binding', async () => {
    const user = userEvent.setup()
    const { queryClient } = renderHarness()

    await waitFor(() => {
      expect(screen.getByTestId('ready')).toHaveTextContent('ready')
    })

    await user.click(screen.getByRole('button', { name: 'open-agent' }))
    await user.clear(screen.getByTestId('agent-description'))
    await user.type(screen.getByTestId('agent-description'), 'edited description')

    act(() => {
      queryClient.setQueryData(queryKeys.models.list, page([{ ...currentModel, providerName: 'stub-v2', name: 'acceptance-stub-v2' }]))
      queryClient.setQueryData(
        queryKeys.agents.list,
        page([{ ...currentAgent, model: 'stub-v2/acceptance-stub-v2' }]),
      )
    })

    await waitFor(() => {
      expect(screen.getByTestId('agent-model')).toHaveValue('stub-v2/acceptance-stub-v2')
    })
    expect(screen.getByTestId('agent-description')).toHaveValue('edited description')
  })
})

function ResourceControllerHarness() {
  const controller = useAiConsoleResourceController({
    providers: true,
    models: true,
    agents: true,
    environments: true,
  })
  const modal = controller.resourceEditorModal.modal
  const ready = !controller.providersQuery.isLoading && !controller.modelsQuery.isLoading && !controller.agentsQuery.isLoading

  return (
    <div>
      <div data-testid="ready">{ready ? 'ready' : 'loading'}</div>
      <button type="button" onClick={() => controller.openEditProvider('stub')}>
        open-provider
      </button>
      <button type="button" onClick={() => controller.openEditModel('stub', 'acceptance-stub')}>
        open-model
      </button>
      <button type="button" onClick={() => controller.openEditAgent('default-assistant')}>
        open-agent
      </button>
      <form onSubmit={controller.resourceEditorModal.onSubmit}>
        <button type="submit">submit</button>
      </form>

      {modal?.kind === 'provider' && (
        <input
          data-testid="provider-name"
          value={controller.resourceEditorModal.providerDraft.name}
          onChange={(event) =>
            controller.resourceEditorModal.onProviderDraftChange({
              ...controller.resourceEditorModal.providerDraft,
              name: event.target.value,
            })
          }
        />
      )}

      {modal?.kind === 'model' && (
        <>
          <input data-testid="model-provider" value={controller.resourceEditorModal.modelDraft.providerName} readOnly />
          <input
            data-testid="model-name"
            value={controller.resourceEditorModal.modelDraft.name}
            onChange={(event) =>
              controller.resourceEditorModal.onModelDraftChange({
                ...controller.resourceEditorModal.modelDraft,
                name: event.target.value,
              })
            }
          />
        </>
      )}

      {modal?.kind === 'agent' && (
        <>
          <input data-testid="agent-model" value={controller.resourceEditorModal.agentDraft.model} readOnly />
          <input
            data-testid="agent-description"
            value={controller.resourceEditorModal.agentDraft.description}
            onChange={(event) =>
              controller.resourceEditorModal.onAgentDraftChange({
                ...controller.resourceEditorModal.agentDraft,
                description: event.target.value,
              })
            }
          />
        </>
      )}
    </div>
  )
}

function renderHarness() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <ResourceControllerHarness />
      </QueryClientProvider>,
    ),
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
    name: 'stub',
    description: 'Local deterministic provider',
    providerType: 'openai',
    baseUrl: 'http://stub.local/v1',
    configured: true,
    modelCallTimeoutMillis: 1800000,
    modelCallIdleTimeoutMillis: 120000,
    version: '0',
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:00:00',
  }
}

function model() {
  return {
    providerName: 'stub',
    name: 'acceptance-stub',
    description: 'Acceptance model',
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
        cacheWriteLongPerMillionTokens: 0,
        reasoningPerMillionTokens: 0,
      },
      defaultVariant: 'default',
      variants: [{ id: 'default' }],
    },
    version: '0',
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:00:00',
  }
}

function agent() {
  return {
    name: 'default-assistant',
    description: 'Cloud agent',
    systemPrompt: 'You are helpful',
    model: 'stub/acceptance-stub',
    variant: 'default',
    config: {
      tools: [],
      skills: []
    },
    version: '0',
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:00:00',
  }
}
