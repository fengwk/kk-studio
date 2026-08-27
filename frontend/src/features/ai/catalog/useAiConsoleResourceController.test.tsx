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

  it('keeps unsaved Agent fields and the persisted model binding across query refreshes', async () => {
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
      expect(screen.getByTestId('agent-model')).toHaveValue('stub/acceptance-stub')
    })
    expect(screen.getByTestId('agent-description')).toHaveValue('edited description')
  })

  it('closes the editor and invalidates dependent queries after successful create mutations', async () => {
    const user = userEvent.setup()
    const { queryClient } = renderHarness()
    await ready()

    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(screen.getByRole('button', { name: 'create-provider' }))
    await user.type(screen.getByTestId('provider-name'), 'new-provider')
    await user.click(screen.getByRole('button', { name: 'submit' }))
    await waitFor(() => {
      expect(agentService.createProvider).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'new-provider' }),
      )
      expect(screen.getByTestId('modal-open')).toHaveTextContent('closed')
    })
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: queryKeys.providers.list }),
    )

    await user.click(screen.getByRole('button', { name: 'create-model' }))
    await user.type(screen.getByTestId('model-name'), 'new-model')
    await user.click(screen.getByRole('button', { name: 'submit' }))
    await waitFor(() => {
      expect(agentService.createModel).toHaveBeenCalledWith(
        expect.objectContaining({ providerName: 'stub', name: 'new-model' }),
      )
      expect(screen.getByTestId('modal-open')).toHaveTextContent('closed')
    })
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: queryKeys.models.list }),
    )

    await user.click(screen.getByRole('button', { name: 'create-agent' }))
    await user.type(screen.getByTestId('agent-name'), 'new-agent')
    await user.click(screen.getByRole('button', { name: 'submit' }))
    await waitFor(() => {
      expect(agentService.createAgent).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'new-agent', model: 'stub/acceptance-stub' }),
      )
      expect(screen.getByTestId('modal-open')).toHaveTextContent('closed')
    })
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: queryKeys.agents.list }),
    )
  })

  it('closes the editor and invalidates dependent queries after a successful agent update', async () => {
    const user = userEvent.setup()
    const { queryClient } = renderHarness()
    await ready()

    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(screen.getByRole('button', { name: 'open-agent' }))
    await user.clear(screen.getByTestId('agent-description'))
    await user.type(screen.getByTestId('agent-description'), 'updated agent')
    await user.click(screen.getByRole('button', { name: 'submit' }))
    await waitFor(() => {
      expect(agentService.updateAgent).toHaveBeenCalledWith(
        'default-assistant',
        expect.objectContaining({ description: 'updated agent', expectedVersion: '0' }),
      )
      expect(screen.getByTestId('modal-open')).toHaveTextContent('closed')
    })
    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: queryKeys.agents.list }),
    )
  })

  it('keeps existing field errors when a later API failure maps to the general field', async () => {
    const user = userEvent.setup()
    renderHarness()
    await ready()

    let rejectUpdate!: (reason?: unknown) => void
    vi.mocked(agentService.updateProvider).mockImplementation(
      () => new Promise((_resolve, reject) => {
        rejectUpdate = reject
      }),
    )
    await user.click(screen.getByRole('button', { name: 'open-provider' }))
    await user.click(screen.getByRole('button', { name: 'submit' }))
    await waitFor(() => {
      expect(agentService.updateProvider).toHaveBeenCalled()
    })

    // API 请求尚未结束时，本地再次提交无效草稿，先形成字段错误。
    await user.clear(screen.getByTestId('provider-name'))
    await user.click(screen.getByRole('button', { name: 'submit' }))
    await waitFor(() => {
      expect(screen.getByTestId('form-error')).toHaveTextContent('请填写 Provider 名称')
    })
    expect(screen.getByTestId('field-error')).toHaveTextContent('请填写名称')

    act(() => {
      rejectUpdate(new Error('agent provider name already exists'))
    })
    await waitFor(() => {
      expect(screen.getByTestId('form-error')).toHaveTextContent('Provider 名称已存在')
    })
    // 在途 API 失败只更新总错误，不覆盖刚产生的 name 字段错误。
    expect(screen.getByTestId('field-error')).toHaveTextContent('请填写名称')
  })

  it('maps a rejected Error and restores it when the resource modal reopens', async () => {
    const user = userEvent.setup()
    renderHarness()
    await ready()

    vi.mocked(agentService.updateProvider).mockRejectedValue(new Error('Network Error'))
    await user.click(screen.getByRole('button', { name: 'open-provider' }))
    await user.click(screen.getByRole('button', { name: 'submit' }))
    await waitFor(() => {
      expect(screen.getByTestId('form-error')).toHaveTextContent('网络异常')
    })

    await user.click(screen.getByRole('button', { name: 'close-resource' }))
    expect(screen.getByTestId('modal-open')).toHaveTextContent('closed')
    await user.click(screen.getByRole('button', { name: 'open-provider' }))
    await waitFor(() => {
      expect(screen.getByTestId('form-error')).toHaveTextContent('网络异常')
    })
  })

  it('maps a rejected string to the generic required-fields error', async () => {
    const user = userEvent.setup()
    renderHarness()
    await ready()

    vi.mocked(agentService.updateModel).mockRejectedValue('backend rejected')
    await user.click(screen.getByRole('button', { name: 'open-model' }))
    await user.click(screen.getByRole('button', { name: 'submit' }))
    await waitFor(() => {
      expect(screen.getByTestId('form-error')).toHaveTextContent('保存失败，请检查必填项后重试')
    })
  })

  it('confirms provider, model, and agent deletes with identity and version and closes the confirm modal', async () => {
    const user = userEvent.setup()
    renderHarness()
    await ready()

    await user.click(screen.getByRole('button', { name: 'delete-provider' }))
    await user.click(screen.getByRole('button', { name: 'confirm-delete' }))
    await waitFor(() => {
      expect(agentService.deleteProvider).toHaveBeenCalledWith('stub', '0')
      expect(screen.queryByTestId('delete-confirm-open')).not.toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: 'delete-model' }))
    await user.click(screen.getByRole('button', { name: 'confirm-delete' }))
    await waitFor(() => {
      expect(agentService.deleteModel).toHaveBeenCalledWith('stub', 'acceptance-stub', '0')
      expect(screen.queryByTestId('delete-confirm-open')).not.toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: 'delete-agent' }))
    await user.click(screen.getByRole('button', { name: 'confirm-delete' }))
    await waitFor(() => {
      expect(agentService.deleteAgent).toHaveBeenCalledWith('default-assistant', '0')
      expect(screen.queryByTestId('delete-confirm-open')).not.toBeInTheDocument()
    })
  })

  it('closes the delete confirm modal without invoking any mutation', async () => {
    const user = userEvent.setup()
    renderHarness()
    await ready()

    await user.click(screen.getByRole('button', { name: 'delete-provider' }))
    expect(screen.getByTestId('delete-confirm-open')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'close-delete' }))
    expect(screen.queryByTestId('delete-confirm-open')).not.toBeInTheDocument()
    expect(agentService.deleteProvider).not.toHaveBeenCalled()
    expect(agentService.deleteModel).not.toHaveBeenCalled()
    expect(agentService.deleteAgent).not.toHaveBeenCalled()
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
  const editor = controller.resourceEditorModal
  const errorField = Object.entries(editor.fieldErrors)[0]?.[1] ?? ''
  const formError = editor.formError
    ? editor.formError
    : errorField
      ? `⟦${errorField}⟧`
      : ''

  return (
    <div>
      <div data-testid="ready">{ready ? 'ready' : 'loading'}</div>
      <div data-testid="modal-open">{modal ? 'open' : 'closed'}</div>
      <div data-testid="form-error">{formError}</div>
      <div data-testid="field-error">{errorField}</div>
      <button type="button" onClick={() => editor.onClose()}>
        close-resource
      </button>
      <button type="button" onClick={() => controller.openCreateProvider()}>
        create-provider
      </button>
      <button type="button" onClick={() => controller.openEditProvider('stub')}>
        open-provider
      </button>
      <button type="button" onClick={() => controller.openCreateModel()}>
        create-model
      </button>
      <button type="button" onClick={() => controller.openEditModel('stub', 'acceptance-stub')}>
        open-model
      </button>
      <button type="button" onClick={() => controller.openCreateAgent()}>
        create-agent
      </button>
      <button type="button" onClick={() => controller.openEditAgent('default-assistant')}>
        open-agent
      </button>
      <form onSubmit={editor.onSubmit}>
        <button type="submit">submit</button>
      </form>

      {modal?.kind === 'provider' && (
        <input
          data-testid="provider-name"
          value={editor.providerDraft.name}
          onChange={(event) =>
            editor.onProviderDraftChange({
              ...editor.providerDraft,
              name: event.target.value,
            })
          }
        />
      )}

      {modal?.kind === 'model' && (
        <>
          <input data-testid="model-provider" value={editor.modelDraft.providerName} readOnly />
          <input
            data-testid="model-name"
            value={editor.modelDraft.name}
            onChange={(event) =>
              editor.onModelDraftChange({
                ...editor.modelDraft,
                name: event.target.value,
              })
            }
          />
        </>
      )}

      {modal?.kind === 'agent' && (
        <>
          <input data-testid="agent-model" value={editor.agentDraft.model} readOnly />
          <input
            data-testid="agent-name"
            value={editor.agentDraft.name}
            onChange={(event) =>
              editor.onAgentDraftChange({
                ...editor.agentDraft,
                name: event.target.value,
              })
            }
          />
          <input
            data-testid="agent-description"
            value={editor.agentDraft.description}
            onChange={(event) =>
              editor.onAgentDraftChange({
                ...editor.agentDraft,
                description: event.target.value,
              })
            }
          />
        </>
      )}

      {controller.deleteConfirmModal.modal && (
        <div data-testid="delete-confirm-open">open</div>
      )}
      <button type="button" onClick={() => controller.deleteProvider('stub', '0')}>
        delete-provider
      </button>
      <button type="button" onClick={() => controller.deleteModel('stub', 'acceptance-stub', '0')}>
        delete-model
      </button>
      <button type="button" onClick={() => controller.deleteAgent('default-assistant', '0')}>
        delete-agent
      </button>
      <button type="button" onClick={() => controller.deleteConfirmModal.modal?.onConfirm()}>
        confirm-delete
      </button>
      <button type="button" onClick={() => controller.deleteConfirmModal.onClose()}>
        close-delete
      </button>
    </div>
  )
}

async function ready() {
  await waitFor(() => {
    expect(screen.getByTestId('ready')).toHaveTextContent('ready')
  })
}

function renderHarness() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <ResourceControllerHarness />
    </QueryClientProvider>,
  )
  return {
    queryClient,
    ...rendered,
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
      toolIds: [],
      skills: [],
      subagents: [],
    },
    version: '0',
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:00:00',
  }
}
