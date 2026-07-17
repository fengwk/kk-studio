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
    listSessions: vi.fn(),
    createSession: vi.fn(),
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
const session = {
  sessionId: '1',
  agentDefinitionId: agent.id,
  title: 'Acceptance chat',
  rootSessionId: '1',
  parentSessionId: null,
  depth: 0,
  leafEntryId: '2',
  activeRunId: null,
  yoloEnabled: false,
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
    vi.mocked(harnessService.listSessions).mockResolvedValue([session])
    vi.mocked(comfyuiService.listWorkflows).mockResolvedValue(page([]))
  })

  it('redirects the root route directly to the global session list', async () => {
    // The URL and rendered action together prove startup bypasses any resource selector.
    render(<AppProviders><App /></AppProviders>)

    await waitFor(() => expect(window.location.pathname).toBe('/sessions'))
    expect(await screen.findByRole('button', { name: '新建 Chat' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'KK Studio' })).toHaveAttribute('href', '/sessions')
  })

  it.each([
    ['/agents', '新建 Agent'],
    ['/models', '新建 Model'],
    ['/providers', '新建 Provider'],
  ])('renders the built-in resource route %s', async (path, createAction) => {
    // Rendering each contribution proves route registration stays data-driven in the Workbench.
    window.history.replaceState({}, '', path)
    render(<AppProviders><App /></AppProviders>)

    expect(await screen.findByRole('button', { name: createAction })).toBeInTheDocument()
    expect(window.location.pathname).toBe(path)
  })

  it('renders the ComfyUI workflow page through its own isolated runtime without querying agent or Harness sessions', async () => {
    vi.mocked(comfyuiService.listWorkflows).mockResolvedValue(page([]))

    window.history.replaceState({}, '', '/comfyui')
    render(<AppProviders><App /></AppProviders>)

    // The ComfyUI page must consume only its own workflow query once mounted.
    expect(await screen.findByRole('button', { name: '新建 ComfyUI Workflow' })).toBeInTheDocument()
    await waitFor(() => expect(comfyuiService.listWorkflows).toHaveBeenCalled())

    // No agent or Harness session API may be queried while ComfyUI is the active route.
    expect(agentService.listProviders).not.toHaveBeenCalled()
    expect(agentService.listModels).not.toHaveBeenCalled()
    expect(agentService.listAgents).not.toHaveBeenCalled()
    expect(harnessService.listSessions).not.toHaveBeenCalled()
  })
})
