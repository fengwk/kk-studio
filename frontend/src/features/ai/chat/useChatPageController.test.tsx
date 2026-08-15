import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useChatPageController } from '@/features/ai/chat/useChatPageController'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listProviders: vi.fn(),
    listModels: vi.fn(),
    listAgents: vi.fn(),
  },
}))
vi.mock('@/shared/api/chat-service', () => ({
  chatService: {
    listChats: vi.fn(),
    createChat: vi.fn(),
  },
}))
describe('useChatPageController', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listAgents).mockResolvedValue(page([agent()]))
    vi.mocked(chatService.listChats).mockResolvedValue([])
    vi.mocked(chatService.createChat).mockResolvedValue({
      id: 'chat-1',
      title: '新的 Chat',
      agentName: 'default-assistant',
      yoloEnabled: false,
      version: '1',
      createTime: null,
      updateTime: null,
    })
  })

  it('loads only chats and the agent choices required to create a chat', async () => {
    const { result } = renderHook(() => useChatPageController(), {
      wrapper: queryWrapper(new QueryClient(queryClientOptions)),
    })

    await waitFor(() => {
      expect(result.current.busy).toBe(false)
      expect(result.current.chatPanelProps.agents).toEqual([agent()])
      expect(agentService.listAgents).toHaveBeenCalledTimes(1)
      expect(chatService.listChats).toHaveBeenCalledTimes(1)
    })

    expect(agentService.listProviders).not.toHaveBeenCalled()
    expect(agentService.listModels).not.toHaveBeenCalled()
  })

  it('opens the create-chat flow from the Chat panel and submits the selected agent', async () => {
    const { result } = renderHook(() => useChatPageController(), {
      wrapper: queryWrapper(new QueryClient(queryClientOptions)),
    })

    await waitFor(() => expect(result.current.busy).toBe(false))
    act(() => result.current.chatPanelProps.onCreate())
    expect(result.current.createChatModal.open).toBe(true)

    act(() => {
      result.current.createChatModal.onTitleChange('新的 Chat')
      result.current.createChatModal.onSelectAgent('default-assistant')
    })
    act(() => {
      result.current.createChatModal.onSubmit({ preventDefault() {} } as never)
    })

    await waitFor(() =>
      expect(chatService.createChat).toHaveBeenCalledWith({
        title: '新的 Chat',
        agentName: 'default-assistant',
        environment: null,
      }),
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
        <MemoryRouter>{children}</MemoryRouter>
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

function agent() {
  return {
    name: 'default-assistant',
    description: null,
    systemPrompt: null,
    model: 'minimax/MiniMax',
    variant: 'default',
    config: {
      tools: [],
      skills: [],
    },
    version: '1',
    createTime: null,
    updateTime: null,
  }
}
