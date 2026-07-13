import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AiConsolePage } from '@/features/ai/AiConsolePage'
import { agentService } from '@/shared/api/agent-service'
import type { ReactElement } from 'react'

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

describe('AiConsolePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listProviders).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [provider()],
    })
    vi.mocked(agentService.listModels).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [model()],
    })
    vi.mocked(agentService.listAgents).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [agent()],
    })
    vi.mocked(agentService.listSessions).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [session()],
    })
    vi.mocked(agentService.createSession).mockResolvedValue({
      ...session(),
      sessionId: 'session-2',
      title: 'New Chat',
    })
    vi.mocked(agentService.updateSession).mockResolvedValue({
      ...session(),
      title: 'Renamed',
    })
    vi.mocked(agentService.deleteSession).mockResolvedValue(undefined)
    vi.mocked(agentService.createProvider).mockResolvedValue(provider())
    vi.mocked(agentService.updateProvider).mockResolvedValue(provider())
    vi.mocked(agentService.deleteProvider).mockResolvedValue(undefined)
    vi.mocked(agentService.createModel).mockResolvedValue(model())
    vi.mocked(agentService.updateModel).mockResolvedValue(model())
    vi.mocked(agentService.deleteModel).mockResolvedValue(undefined)
    vi.mocked(agentService.createAgent).mockResolvedValue(agent())
    vi.mocked(agentService.updateAgent).mockResolvedValue(agent())
    vi.mocked(agentService.deleteAgent).mockResolvedValue(undefined)
  })

  it('renders backend sessions and creates a session from a selected agent', async () => {
    const user = userEvent.setup()
    renderConsole('chat')

    expect(await screen.findByText('Script Review')).toBeInTheDocument()
    expect(screen.getByText('2026-06-20 02:01')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /新建 Chat/ }))
    await user.type(screen.getByPlaceholderText('会话标题'), 'New Chat')
    await user.click(screen.getByRole('button', { name: '确认创建' }))

    await waitFor(() => {
      expect(agentService.createSession).toHaveBeenCalledWith({ agentName: 'default-assistant', title: 'New Chat' })
    })
  })

  it('edits and deletes chat sessions', async () => {
    const user = userEvent.setup()
    renderConsole('chat')

    expect(await screen.findByText('Script Review')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /编辑/ }))
    await user.clear(screen.getByPlaceholderText('会话标题'))
    await user.type(screen.getByPlaceholderText('会话标题'), 'Renamed')
    await user.click(screen.getByRole('button', { name: '保存修改' }))

    await waitFor(() => {
      expect(agentService.updateSession).toHaveBeenCalledWith('session-1', { title: 'Renamed' })
    })

    await user.click(screen.getByRole('button', { name: /删除/ }))
    expect(await screen.findByRole('alertdialog', { name: '删除 Chat' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(agentService.deleteSession).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: /删除/ }))
    await user.click(screen.getByRole('button', { name: '确认删除' }))
    await waitFor(() => {
      expect(agentService.deleteSession).toHaveBeenCalledWith('session-1')
    })
  })

  it('renders agent, model and provider views from separate DTOs', async () => {
    renderConsole('agent')
    expect(await screen.findByText('default-assistant')).toBeInTheDocument()
    expect(screen.getByText('Cloud agent')).toBeInTheDocument()

    cleanup()
    renderConsole('model')
    expect(await screen.findByText('MiniMax-M2.7')).toBeInTheDocument()
    expect(screen.getByText('1 item')).toBeInTheDocument()

    cleanup()
    renderConsole('provider')
    expect(await screen.findByText('minimax')).toBeInTheDocument()
    expect(screen.getByText('openai')).toBeInTheDocument()
  })

  it('creates, edits and deletes models with model editable fields', async () => {
    const user = userEvent.setup()
    renderConsole('model')

    await user.click(await screen.findByRole('button', { name: /新建 Model/ }))
    await user.type(screen.getByPlaceholderText('MiniMax-M2.7'), 'MiniMax-M3')
    await user.click(screen.getByRole('button', { name: '确认创建' }))

    await waitFor(() => {
      expect(agentService.createModel).toHaveBeenCalledWith(
        expect.objectContaining({
          provider: 'minimax',
          name: 'MiniMax-M3',
          defaultVariant: 'default',
          variantsJson: '[{"name":"default"}]',
        }),
      )
    })

    await user.click(screen.getByRole('button', { name: '编辑 MiniMax-M2.7' }))
    const descField = screen.getByPlaceholderText('模型说明')
    await user.clear(descField)
    await user.type(descField, 'Updated description')
    await user.click(screen.getByRole('button', { name: '保存修改' }))

    await waitFor(() => {
      expect(agentService.updateModel).toHaveBeenCalledWith('model-1', expect.objectContaining({ description: 'Updated description' }))
    })

    await user.click(screen.getByRole('button', { name: '删除 MiniMax-M2.7' }))
    expect(await screen.findByRole('alertdialog', { name: '删除 Model' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '确认删除' }))
    await waitFor(() => {
      expect(agentService.deleteModel).toHaveBeenCalledWith('model-1')
    })
  })

  it('renders fallback values for malformed backend resource fields', async () => {
    vi.mocked(agentService.listModels).mockResolvedValueOnce({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [{ ...model(), description: null, variantsJson: '{broken' }],
    })
    renderConsole('model')
    expect((await screen.findAllByText('MiniMax-M2.7')).length).toBeGreaterThan(0)
    expect(screen.getByText('invalid json')).toBeInTheDocument()

    cleanup()
    vi.mocked(agentService.listModels).mockResolvedValueOnce({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [{ ...model(), variantsJson: null }],
    })
    renderConsole('model')
    await waitFor(() => {
      expect(screen.getAllByText('default').length).toBeGreaterThan(0)
    })

    cleanup()
    vi.mocked(agentService.listSessions).mockResolvedValueOnce({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [{ ...session(), title: null, updateTime: null }],
    })
    renderConsole('chat')
    expect(await screen.findByText('Untitled Chat')).toBeInTheDocument()
    expect(screen.getAllByText('-').length).toBeGreaterThan(0)
  })

  it('creates, edits and deletes providers and agents with editable fields', async () => {
    const user = userEvent.setup()

    renderConsole('provider')
    await user.click(await screen.findByRole('button', { name: /新建 Provider/ }))
    await user.type(screen.getByPlaceholderText('minimax'), 'minimax-2')
    await user.click(screen.getByRole('button', { name: '确认创建' }))

    await waitFor(() => {
      expect(agentService.createProvider).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'minimax-2', providerType: 'openai' }),
      )
    })

    await user.click(screen.getByRole('button', { name: '编辑 minimax' }))
    const providerDesc = screen.getByPlaceholderText('用途说明')
    await user.clear(providerDesc)
    await user.type(providerDesc, 'Updated provider description')
    await user.click(screen.getByRole('button', { name: '保存修改' }))

    await waitFor(() => {
      expect(agentService.updateProvider).toHaveBeenCalledWith('provider-1', expect.objectContaining({ description: 'Updated provider description' }))
    })

    await user.click(screen.getByRole('button', { name: '删除 minimax' }))
    expect(await screen.findByRole('alertdialog', { name: '删除 Provider' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '确认删除' }))
    await waitFor(() => {
      expect(agentService.deleteProvider).toHaveBeenCalledWith('provider-1')
    })

    cleanup()
    renderConsole('agent')
    await user.click(await screen.findByRole('button', { name: /新建 Agent/ }))
    await user.type(screen.getByPlaceholderText('default-assistant'), 'agent-2')
    await user.click(screen.getByRole('button', { name: '确认创建' }))

    await waitFor(() => {
      expect(agentService.createAgent).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'agent-2', defaultProvider: 'minimax', defaultModel: 'MiniMax-M2.7' }),
      )
    })

    await user.click(screen.getByRole('button', { name: '编辑 default-assistant' }))
    const agentDesc = screen.getByPlaceholderText('用途说明')
    await user.clear(agentDesc)
    await user.type(agentDesc, 'Updated agent description')
    await user.click(screen.getByRole('button', { name: '保存修改' }))

    await waitFor(() => {
      expect(agentService.updateAgent).toHaveBeenCalledWith('agent-1', expect.objectContaining({ description: 'Updated agent description' }))
    })

    await user.click(screen.getByRole('button', { name: '删除 default-assistant' }))
    expect(await screen.findByRole('alertdialog', { name: '删除 Agent' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '确认删除' }))
    await waitFor(() => {
      expect(agentService.deleteAgent).toHaveBeenCalledWith('agent-1')
    })
  })

  it('filters sessions and handles query errors', async () => {
    const user = userEvent.setup()
    renderConsole('chat')

    expect(await screen.findByText('Script Review')).toBeInTheDocument()
    await user.type(screen.getByPlaceholderText('搜索资源...'), 'missing')
    expect(screen.queryByText('Script Review')).not.toBeInTheDocument()

    cleanup()
    vi.mocked(agentService.listAgents).mockRejectedValueOnce(new Error('agents offline'))
    renderConsole('agent')

    expect(screen.getByText('正在加载资源')).toBeInTheDocument()
    expect(await screen.findByText('agents offline')).toBeInTheDocument()
  })
})

function renderConsole(tab: 'chat' | 'agent' | 'model' | 'provider') {
  return render(wrap(<AiConsolePage initialTab={tab} />))
}

function wrap(element: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Routes>
          <Route path="*" element={element} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
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

function session() {
  return {
    sessionId: 'session-1',
    agentId: 'agent-1',
    agentName: 'default-assistant',
    title: 'Script Review',
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:01:00',
  }
}
