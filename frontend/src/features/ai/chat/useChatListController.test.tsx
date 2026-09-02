import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { useChatListController } from '@/features/ai/chat/useChatListController'
import { chatService } from '@/shared/api/chat-service'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'

vi.mock('@/shared/api/chat-service', () => ({
  chatService: {
    listChats: vi.fn(async () => []),
    createChat: vi.fn(async () => ({
      id: 'c1',
      title: 'T',
      agentName: 'assistant',
      workspacePath: null,
      yoloEnabled: false,
      version: '1',
      createTime: null,
      updateTime: null,
    })),
  },
}))

const agents: AgentDefinitionDTO[] = [
  {
    name: 'assistant',
    description: null,
    systemPrompt: null,
    model: 'minimax/MiniMax',
    variant: 'default',
    environmentId: 'env-dev-1',
    config: {
      toolIds: [],
      skills: [],
      subagents: [],
    },
    version: '1',
    createTime: null,
    updateTime: null,
  },
  {
    name: 'coder',
    description: null,
    systemPrompt: null,
    model: 'minimax/MiniMax',
    variant: 'default',
    environmentId: 'env-prod-2',
    config: {
      toolIds: [],
      skills: [],
      subagents: [],
    },
    version: '1',
    createTime: null,
    updateTime: null,
  },
]

const environments: EnvironmentCardDTO[] = [
  {
    id: 'env-dev-1',
    name: 'dev',
    rootPath: null,
    ready: true,
    status: 'READY',
    lastSeen: null,
    capabilities: [],
    skills: [],
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T00:00:00.000Z',
  },
]

describe('useChatListController', () => {
  it('creates a Chat with the selected Agent', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    )
    const { result } = renderHook(() => useChatListController(agents, true, environments), { wrapper })
    await waitFor(() => expect(result.current.chatsQuery.isSuccess).toBe(true))
    act(() => result.current.openCreateChat('assistant'))
    expect(result.current.createChatModal.open).toBe(true)
    expect(result.current.createChatModal.selectedAgentName).toBe('assistant')

    await act(async () => {
      result.current.createChatModal.onSubmit({ preventDefault() {} } as never)
    })
    expect(chatService.createChat).not.toHaveBeenCalled()
    expect(result.current.createChatModal.formError).toBe('请填写 Chat 名称')
    expect(result.current.createChatModal.nameError).toBe('请填写名称')

    act(() => {
      result.current.createChatModal.onTitleChange('Hello')
      result.current.createChatModal.onSelectAgent('assistant')
    })
    expect(result.current.createChatModal.formError).toBe('')
    expect(result.current.createChatModal.nameError).toBe('')
    await act(async () => {
      result.current.createChatModal.onSubmit({ preventDefault() {} } as never)
    })
    await waitFor(() =>
      expect(chatService.createChat).toHaveBeenCalledWith({
        title: 'Hello',
        agentName: 'assistant',
        workspacePath: null,
      }),
    )
  })

  it('creates a Chat with selected workspacePath and resets workspacePath when switching to an agent with a different environment', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    )
    const { result } = renderHook(() => useChatListController(agents, true, environments), { wrapper })
    await waitFor(() => expect(result.current.chatsQuery.isSuccess).toBe(true))
    act(() => result.current.openCreateChat('assistant'))
    act(() => {
      result.current.createChatModal.onTitleChange('Chat')
      result.current.createChatModal.onSelectWorkspacePath('projects/kk-studio')
    })
    expect(result.current.createChatModal.selectedWorkspacePath).toBe('projects/kk-studio')

    // 切换到不同 environmentId 的 agent -> workspacePath 自动重置为 null
    act(() => {
      result.current.createChatModal.onSelectAgent('coder')
    })
    expect(result.current.createChatModal.selectedWorkspacePath).toBeNull()

    // 重新设置 workspacePath 并提交
    act(() => {
      result.current.createChatModal.onSelectWorkspacePath('projects/coder-app')
    })
    await act(async () => {
      result.current.createChatModal.onSubmit({ preventDefault() {} } as never)
    })
    await waitFor(() => {
      const lastCall = vi.mocked(chatService.createChat).mock.calls.at(-1)!
      expect(lastCall[0]).toEqual({
        title: 'Chat',
        agentName: 'coder',
        workspacePath: 'projects/coder-app',
      })
    })
  })
})
