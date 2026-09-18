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
      }),
    )
  })

  // 验证从 Chat 面板调用 onEdit 与 onDelete 可达并触发相应弹窗状态
  it('wires onEdit and onDelete handlers from chatPanelProps to modals', async () => {
    const { result } = renderHook(() => useChatPageController(), {
      wrapper: queryWrapper(new QueryClient(queryClientOptions)),
    })

    await waitFor(() => expect(result.current.busy).toBe(false))

    const testChat = {
      id: 'chat-99',
      title: 'Panel Chat',
      agentName: 'default-assistant',
      yoloEnabled: false,
      version: '1',
      createTime: null,
      updateTime: null,
    }

    // 触发编辑
    act(() => result.current.chatPanelProps.onEdit(testChat))
    expect(result.current.createChatModal.open).toBe(true)
    expect(result.current.createChatModal.mode).toBe('edit')
    expect(result.current.createChatModal.title).toBe('Panel Chat')

    // 关闭编辑
    act(() => result.current.createChatModal.onClose())
    expect(result.current.createChatModal.open).toBe(false)

    // 触发删除
    act(() => result.current.chatPanelProps.onDelete(testChat))
    expect(result.current.deleteConfirmModal.modal).not.toBeNull()
    expect(result.current.deleteConfirmModal.modal?.title).toBe('删除 Chat')
    expect(result.current.deleteConfirmModal.modal?.description).toContain('Panel Chat')

    // 关闭删除
    act(() => result.current.deleteConfirmModal.onClose())
    expect(result.current.deleteConfirmModal.modal).toBeNull()
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
    environmentId: null,
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
