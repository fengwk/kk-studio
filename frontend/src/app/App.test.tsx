import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/app/App'
import { AppProviders } from '@/app/providers'
import { agentService } from '@/shared/api/agent-service'
import { comfyuiService } from '@/shared/api/comfyui-service'
import { harnessService } from '@/shared/api/harness-service'

vi.mock('@/shared/api/agent-service', () => {
  const agentService = {
    listProviders: vi.fn(),
    listModels: vi.fn(),
    listAgents: vi.fn(),
  }
  return { agentService }
})

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    listThreads: vi.fn(),
    createThread: vi.fn(),
  },
}))

vi.mock('@/shared/api/comfyui-service', () => ({
  comfyuiService: {
    listWorkflows: vi.fn(),
  },
}))

const provider = {
  id: 'provider-1',
  name: 'stub',
  description: null,
  providerType: 'openai',
  baseUrl: null,
  apiKey: null,
  timeoutMillis: null,
  createTime: null,
  updateTime: null,
}
const model = {
  id: 'model-1',
  providerId: provider.id,
  providerName: provider.name,
  name: 'acceptance-stub',
  description: null,
  defaultVariant: 'default',
  variantsJson: null,
  createTime: null,
  updateTime: null,
}
const agent = {
  id: 'agent-1',
  name: 'default-assistant',
  description: null,
  systemPrompt: null,
  defaultProviderId: provider.id,
  defaultProviderName: provider.name,
  defaultModelId: model.id,
  defaultModelName: model.name,
  defaultVariant: 'default',
  toolsJson: null,
  createTime: null,
  updateTime: null,
}
const thread = {
  threadId: '1',
  sessionId: 's1',
  sessionTitle: 'Acceptance chat',
  headEntryId: '2',
  agentDefinitionId: agent.id,
  runtimeConfigJson: null,
  yoloEnabled: false,
  inputSequence: 0,
  processing: false,
  createTime: null,
  updateTime: null,
}

function page<T>(results: T[]) {
  return { pageNumber: 1, pageSize: 50, totalCount: results.length, results }
}

describe('App', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/')
    vi.mocked(agentService.listProviders).mockResolvedValue(page([provider]))
    vi.mocked(agentService.listModels).mockResolvedValue(page([model]))
    vi.mocked(agentService.listAgents).mockResolvedValue(page([agent]))
    vi.mocked(harnessService.listThreads).mockResolvedValue([thread])
    vi.mocked(comfyuiService.listWorkflows).mockResolvedValue(page([]))
  })

  it('redirects the root route directly to the global thread list', async () => {
    render(<AppProviders><App /></AppProviders>)

    await waitFor(() => expect(window.location.pathname).toBe('/threads'))
    expect(await screen.findByRole('button', { name: '新建 Chat' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'KK Studio' })).toHaveAttribute('href', '/threads')
  })

  it.each([
    ['/agents', '新建 Agent'],
    ['/models', '新建 Model'],
    ['/providers', '新建 Provider'],
  ])('renders the built-in resource route %s', async (path, createAction) => {
    window.history.replaceState({}, '', path)
    render(<AppProviders><App /></AppProviders>)

    expect(await screen.findByRole('button', { name: createAction })).toBeInTheDocument()
    expect(window.location.pathname).toBe(path)
  })

  it('renders the ComfyUI workflow page through its own isolated runtime without querying agent or Harness threads', async () => {
    vi.mocked(comfyuiService.listWorkflows).mockResolvedValue(page([]))

    window.history.replaceState({}, '', '/comfyui')
    render(<AppProviders><App /></AppProviders>)

    expect(await screen.findByRole('button', { name: '新建 ComfyUI Workflow' })).toBeInTheDocument()
    await waitFor(() => expect(comfyuiService.listWorkflows).toHaveBeenCalled())

    expect(agentService.listProviders).not.toHaveBeenCalled()
    expect(agentService.listModels).not.toHaveBeenCalled()
    expect(agentService.listAgents).not.toHaveBeenCalled()
    expect(harnessService.listThreads).not.toHaveBeenCalled()
  })
})
