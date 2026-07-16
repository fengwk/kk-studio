import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useComfyuiPageController } from '@/features/ai/useComfyuiPageController'
import { comfyuiService } from '@/shared/api/comfyui-service'
import type { ComfyuiWorkflowApiDTO } from '@/shared/api/contracts'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listProviders: vi.fn(),
    listModels: vi.fn(),
    listAgents: vi.fn(),
    listSessions: vi.fn(),
    createSession: vi.fn(),
    updateSession: vi.fn(),
    deleteSession: vi.fn(),
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

vi.mock('@/shared/api/comfyui-service', () => ({
  comfyuiService: {
    listWorkflows: vi.fn(),
    createWorkflow: vi.fn(),
    updateWorkflow: vi.fn(),
    deleteWorkflow: vi.fn(),
    runWorkflow: vi.fn(),
    getRun: vi.fn(),
    cancelRun: vi.fn(),
    uploadFile: vi.fn(),
    createPresignedUpload: vi.fn(),
    createPresignedDownload: vi.fn(),
  },
}))

import { agentService } from '@/shared/api/agent-service'

function renderHook() {
  let captured: ReturnType<typeof useComfyuiPageController> | null = null
  function Probe() {
    captured = useComfyuiPageController()
    return (
      <div>
        <span data-testid="busy">{String(captured.busy)}</span>
        <span data-testid="error">{captured.error instanceof Error ? captured.error.message : captured.error ?? '-'}</span>
        <span data-testid="count">{captured.comfyuiPanelProps.workflows.length}</span>
        <span data-testid="search">{captured.search}</span>
        <button type="button" onClick={() => captured.setSearch('image')}>search</button>
        <button type="button" onClick={() => captured.comfyuiPanelProps.onCreate()}>create</button>
        <button type="button" onClick={() => captured.comfyuiPanelProps.onDelete(workflow())}>delete</button>
      </div>
    )
  }
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return {
    captured: () => captured as ReturnType<typeof useComfyuiPageController>,
    ...render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/comfyui']}>
          <Routes>
            <Route path="/comfyui" element={<Probe />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    ),
  }
}

function workflow(): ComfyuiWorkflowApiDTO {
  return {
    id: 'workflow-1',
    apiName: 'image-upscale',
    name: 'Image Upscale',
    description: null,
    workflowJson: '{}',
    inputBindingsJson: '[]',
    defaultSelector: '$.outputs',
    enabled: true,
    createTime: null,
    updateTime: null,
  }
}

describe('useComfyuiPageController', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(comfyuiService.listWorkflows).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 0,
      results: [],
    })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('only queries comfyuiService.listWorkflows and never touches legacy agent endpoints', async () => {
    renderHook()

    await waitFor(() => expect(comfyuiService.listWorkflows).toHaveBeenCalled())

    expect(agentService.listProviders).not.toHaveBeenCalled()
    expect(agentService.listModels).not.toHaveBeenCalled()
    expect(agentService.listAgents).not.toHaveBeenCalled()
    expect(agentService.listSessions).not.toHaveBeenCalled()
  })

  it('loads workflow data, surfaces busy/error state, and exposes its own search filter', async () => {
    vi.mocked(comfyuiService.listWorkflows).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [workflow()],
    })
    const user = userEvent.setup()
    renderHook()

    await waitFor(() => expect(screen.getByTestId('count')).toHaveTextContent('1'))
    expect(screen.getByTestId('busy')).toHaveTextContent('false')

    await user.click(screen.getByText('search'))
    await waitFor(() => expect(screen.getByTestId('search')).toHaveTextContent('image'))
  })

  it('does not invoke legacy create/update/delete endpoints when CRUD handlers fire', async () => {
    vi.mocked(comfyuiService.listWorkflows).mockResolvedValue({
      pageNumber: 1,
      pageSize: 100,
      totalCount: 1,
      results: [workflow()],
    })
    vi.mocked(comfyuiService.createWorkflow).mockResolvedValue(workflow())
    vi.mocked(comfyuiService.deleteWorkflow).mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderHook()

    await waitFor(() => expect(screen.getByTestId('count')).toHaveTextContent('1'))

    await user.click(screen.getByText('create'))
    await user.click(screen.getByText('delete'))

    await waitFor(() => {
      expect(agentService.createProvider).not.toHaveBeenCalled()
      expect(agentService.updateProvider).not.toHaveBeenCalled()
      expect(agentService.deleteProvider).not.toHaveBeenCalled()
      expect(agentService.createModel).not.toHaveBeenCalled()
      expect(agentService.updateModel).not.toHaveBeenCalled()
      expect(agentService.deleteModel).not.toHaveBeenCalled()
      expect(agentService.createAgent).not.toHaveBeenCalled()
      expect(agentService.updateAgent).not.toHaveBeenCalled()
      expect(agentService.deleteAgent).not.toHaveBeenCalled()
      expect(agentService.createSession).not.toHaveBeenCalled()
      expect(agentService.updateSession).not.toHaveBeenCalled()
      expect(agentService.deleteSession).not.toHaveBeenCalled()
    })
  })

  it('surfaces comfyui workflow errors without falling back to legacy endpoints', async () => {
    vi.mocked(comfyuiService.listWorkflows).mockRejectedValue(new Error('comfyui offline'))
    renderHook()

    await waitFor(() => expect(screen.getByTestId('error')).toHaveTextContent('comfyui offline'))
    expect(agentService.listProviders).not.toHaveBeenCalled()
    expect(agentService.listSessions).not.toHaveBeenCalled()
  })
})