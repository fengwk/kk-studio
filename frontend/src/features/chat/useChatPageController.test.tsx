import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useChatPageController } from '@/features/chat/useChatPageController'
import { agentService } from '@/shared/api/agent-service'
import { ApiError } from '@/shared/api/client'
import { chatService } from '@/features/chat/chat-service'
import type { AgentDTO, ChatDTO } from '@/shared/api/generated/contracts'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listProviders: vi.fn(),
    listModels: vi.fn(),
    listAgents: vi.fn(),
  },
}))
vi.mock('@/features/chat/chat-service', () => ({
  chatService: {
    listChats: vi.fn(),
    createChat: vi.fn(),
    updateChat: vi.fn(),
  },
}))
describe('useChatPageController', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listAgents).mockResolvedValue([agent()])
    vi.mocked(chatService.listChats).mockResolvedValue({ items: [], nextCursor: null })
    vi.mocked(chatService.createChat).mockResolvedValue({
      id: 'chat-1',
      title: '新的 Chat',
      agentName: 'default-assistant',
      environmentName: null,
      yoloEnabled: false,
      version: '1',
      createdAt: 0,
      updatedAt: 0,
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

    // 目录读取状态随表单下传，供 Agent 选择器区分加载/失败/已确认不存在
    expect(result.current.createChatModal.agentsLoading).toBe(false)
    expect(result.current.createChatModal.agentsError).toBe(false)
    expect(result.current.createChatModal.agentsReady).toBe(true)

    expect(agentService.listProviders).not.toHaveBeenCalled()
    expect(agentService.listModels).not.toHaveBeenCalled()
    expect(result.current.chatPanelProps.setup).toEqual({
      hasProvider: true,
      hasModel: true,
      hasAgent: true,
    })
  })

  // 没有可用 Agent 时才查询 Provider / Model，用于首次使用引导。
  it('reports catalog setup progress when no usable agent exists', async () => {
    vi.mocked(agentService.listAgents).mockResolvedValue([agent({ execution: null })])
    vi.mocked(agentService.listProviders).mockResolvedValue([{ name: 'p' }] as never)
    vi.mocked(agentService.listModels).mockResolvedValue([])

    const { result } = renderHook(() => useChatPageController(), {
      wrapper: queryWrapper(new QueryClient(queryClientOptions)),
    })

    await waitFor(() =>
      expect(result.current.chatPanelProps.setup).toEqual({
        hasProvider: true,
        hasModel: false,
        hasAgent: false,
      }),
    )
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
        yoloEnabled: false,
        environmentName: null,
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
      environmentName: null,
      yoloEnabled: false,
      version: '1',
      createdAt: 0,
      updatedAt: 0,
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
    expect(result.current.deleteConfirmModal.modal?.title).toBe('删除对话')
    expect(result.current.deleteConfirmModal.modal?.description).toContain('Panel Chat')

    // 关闭删除
    act(() => result.current.deleteConfirmModal.onClose())
    expect(result.current.deleteConfirmModal.modal).toBeNull()
  })

  // 编辑后服务端按 updated_at 重排（刚改的排到最前），列表缓存刷新必须透传该顺序而不是按 createdAt 重排。
  it('keeps the server updated-at order after an edit refreshes the list', async () => {
    const editedBefore: ChatDTO = chat({
      id: 'chat-edited',
      title: 'Edited',
      version: '1',
      createdAt: 1781913600000,
      updatedAt: 1781913600000,
    })
    const untouched: ChatDTO = chat({
      id: 'chat-untouched',
      title: 'Untouched',
      version: '1',
      createdAt: 1782086400000,
      updatedAt: 1782086400000,
    })
    vi.mocked(chatService.listChats)
      // 首屏：服务端 updated_at 倒序，untouched 最近被改过
      .mockResolvedValueOnce({ items: [untouched, editedBefore], nextCursor: null })
      // 编辑后回读：编辑把 chat-edited 顶到最前，尽管它的 createdAt 更早
      .mockResolvedValue({
        items: [{ ...editedBefore, title: 'Edited once', version: '2', updatedAt: 1782172800000 }, untouched],
        nextCursor: null,
      })
    vi.mocked(chatService.updateChat).mockResolvedValue({
      ...editedBefore,
      title: 'Edited once',
      version: '2',
      updatedAt: 1782172800000,
    })

    const { result } = renderHook(() => useChatPageController(), {
      wrapper: queryWrapper(new QueryClient(queryClientOptions)),
    })
    await waitFor(() => expect(result.current.busy).toBe(false))
    expect(result.current.chatPanelProps.chats.map((item) => item.id)).toEqual([
      'chat-untouched',
      'chat-edited',
    ])

    act(() => result.current.chatPanelProps.onEdit(editedBefore))
    act(() => result.current.createChatModal.onTitleChange('Edited once'))
    await act(async () => {
      result.current.createChatModal.onSubmit({ preventDefault() {} } as never)
    })

    await waitFor(() =>
      expect(result.current.chatPanelProps.chats.map((item) => item.title)).toEqual([
        'Edited once',
        'Untouched',
      ]),
    )
  })

  // 加载更多失败只能就地提示：已加载的列表保留、不并入页面级 error，文案经 toUserFacingErrorMessage 本地化。
  it('keeps the loaded list when loading more fails and reports a localized inline error', async () => {
    const loaded: ChatDTO = chat({ id: 'chat-1', title: 'Loaded' })
    const appended: ChatDTO = chat({ id: 'chat-2', title: 'Appended' })
    vi.mocked(chatService.listChats)
      .mockResolvedValueOnce({ items: [loaded], nextCursor: 'cursor-1' })
      .mockRejectedValueOnce(new ApiError('Request timeout', undefined, 'timeout'))
      .mockResolvedValueOnce({ items: [appended], nextCursor: null })

    const { result } = renderHook(() => useChatPageController(), {
      wrapper: queryWrapper(new QueryClient(queryClientOptions)),
    })
    await waitFor(() => expect(result.current.chatPanelProps.chats).toHaveLength(1))

    await act(async () => {
      result.current.chatPanelProps.onLoadMore()
    })

    await waitFor(() =>
      expect(result.current.chatPanelProps.loadMoreError).toBe('网络异常，请稍后重试'),
    )
    expect(result.current.error).toBeFalsy()
    expect(result.current.chatPanelProps.chats.map((item) => item.id)).toEqual(['chat-1'])
    expect(result.current.chatPanelProps.hasMore).toBe(true)
    expect(result.current.chatPanelProps.loadingMore).toBe(false)

    // 重试成功后就地提示消失并追加新页
    await act(async () => {
      result.current.chatPanelProps.onLoadMore()
    })
    await waitFor(() => expect(result.current.chatPanelProps.loadMoreError).toBeNull())
    expect(result.current.chatPanelProps.chats.map((item) => item.id)).toEqual([
      'chat-1',
      'chat-2',
    ])
    expect(result.current.chatPanelProps.hasMore).toBe(false)
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

function chat(overrides: Partial<ChatDTO> = {}): ChatDTO {
  return {
    id: 'chat-1',
    title: 'Chat',
    agentName: 'default-assistant',
    environmentName: null,
    yoloEnabled: false,
    version: '1',
    createdAt: 0,
    updatedAt: 0,
    ...overrides,
  }
}

function agent(overrides: Partial<AgentDTO> = {}): AgentDTO {
  return {
    name: 'default-assistant',
    description: null,
    systemPrompt: null,
    execution: { driver: 'builtin', settings: { modelName: 'minimax/MiniMax', variant: 'default' } },
    config: {
      tools: [],
      skills: [],
      subagents: [],
    },
    version: '1',
    createdAt: 0,
    updatedAt: 0,
    ...overrides,
  }
}
