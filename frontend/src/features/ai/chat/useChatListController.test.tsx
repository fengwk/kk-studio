import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useChatListController } from '@/features/ai/chat/useChatListController'
import { chatService } from '@/shared/api/chat-service'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'

vi.mock('@/shared/api/chat-service', () => ({
  chatService: {
    listChats: vi.fn(async () => []),
    createChat: vi.fn(async () => ({
      id: 'c1',
      title: 'T',
      agentName: 'assistant',
      yoloEnabled: false,
      version: '1',
      createTime: null,
      updateTime: null,
    })),
    updateChat: vi.fn(async (chatId: string, data: { title?: string | null; agentName?: string | null; expectedVersion: string }) => ({
      id: chatId,
      title: data.title ?? null,
      agentName: data.agentName ?? 'assistant',
      yoloEnabled: false,
      version: '2',
      createTime: null,
      updateTime: null,
    })),
    deleteChat: vi.fn(async () => undefined),
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
      inheritParentEnvironment: true,
      tools: [],
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
      inheritParentEnvironment: true,
      tools: [],
      skills: [],
      subagents: [],
    },
    version: '1',
    createTime: null,
    updateTime: null,
  },
]

describe('useChatListController', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('creates a Chat with the selected Agent', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    )
    const { result } = renderHook(() => useChatListController(agents, true), { wrapper })
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
      }),
    )
  })

  // 验证切换 Agent 后提交：Chat 只持久化 title/agentName，不再有任何目录状态
  it('creates a Chat after switching Agent without any workspace state', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    )
    const { result } = renderHook(() => useChatListController(agents, true), { wrapper })
    await waitFor(() => expect(result.current.chatsQuery.isSuccess).toBe(true))
    act(() => result.current.openCreateChat('assistant'))
    act(() => {
      result.current.createChatModal.onTitleChange('Chat')
      result.current.createChatModal.onSelectAgent('coder')
    })
    await act(async () => {
      result.current.createChatModal.onSubmit({ preventDefault() {} } as never)
    })
    await waitFor(() => {
      const lastCall = vi.mocked(chatService.createChat).mock.calls.at(-1)!
      expect(lastCall[0]).toEqual({
        title: 'Chat',
        agentName: 'coder',
      })
    })
  })

  // 验证编辑 Chat 流程：复用表单、携带当前 expectedVersion 提交并在成功后关闭弹窗
  it('edits a Chat submitting expectedVersion and closing modal on success', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    )
    const { result } = renderHook(() => useChatListController(agents, true), { wrapper })
    await waitFor(() => expect(result.current.chatsQuery.isSuccess).toBe(true))

    const existingChat = {
      id: 'chat-42',
      title: 'Original Title',
      agentName: 'assistant',
      yoloEnabled: false,
      version: '5',
      createTime: null,
      updateTime: null,
    }

    act(() => result.current.openEditChat(existingChat))
    expect(result.current.createChatModal.open).toBe(true)
    expect(result.current.createChatModal.mode).toBe('edit')
    expect(result.current.createChatModal.title).toBe('Original Title')
    expect(result.current.createChatModal.selectedAgentName).toBe('assistant')

    act(() => {
      result.current.createChatModal.onTitleChange('Renamed Chat')
      result.current.createChatModal.onSelectAgent('coder')
    })

    await act(async () => {
      result.current.createChatModal.onSubmit({ preventDefault() {} } as never)
    })

    await waitFor(() => {
      expect(chatService.updateChat).toHaveBeenCalledWith('chat-42', {
        title: 'Renamed Chat',
        agentName: 'coder',
        expectedVersion: '5',
      })
      expect(result.current.createChatModal.open).toBe(false)
    })
  })

  // 验证 CAS 冲突或更新失败时：Modal 保持打开，用户草稿保留不丢，并展示冲突错误
  it('preserves user draft and keeps modal open when update fails or encounters 409 CAS conflict', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    )
    vi.mocked(chatService.updateChat).mockRejectedValueOnce(new Error('409 Conflict: version conflict'))

    const { result } = renderHook(() => useChatListController(agents, true), { wrapper })
    await waitFor(() => expect(result.current.chatsQuery.isSuccess).toBe(true))

    const existingChat = {
      id: 'chat-cas',
      title: 'Stable Chat',
      agentName: 'assistant',
      yoloEnabled: false,
      version: '3',
      createTime: null,
      updateTime: null,
    }

    act(() => result.current.openEditChat(existingChat))
    act(() => {
      result.current.createChatModal.onTitleChange('New In-Flight Title')
    })

    await act(async () => {
      result.current.createChatModal.onSubmit({ preventDefault() {} } as never)
    })

    await waitFor(() => {
      expect(chatService.updateChat).toHaveBeenCalledTimes(1)
      // Modal 必须保持打开
      expect(result.current.createChatModal.open).toBe(true)
      // 字段草稿不丢
      expect(result.current.createChatModal.title).toBe('New In-Flight Title')
      // 显示用户可读的冲突错误
      expect(result.current.createChatModal.formError).toBe('资源冲突，请刷新后重试')
    })

    // 用户继续修改标题后错误自动清除
    act(() => {
      result.current.createChatModal.onTitleChange('Corrected Title')
    })
    expect(result.current.createChatModal.formError).toBe('')
    expect(result.current.createChatModal.title).toBe('Corrected Title')
  })

  // 验证取消操作：关闭弹窗且不触发任何写入
  it('closes modal on cancel without performing any writes', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    )
    const { result } = renderHook(() => useChatListController(agents, true), { wrapper })
    await waitFor(() => expect(result.current.chatsQuery.isSuccess).toBe(true))

    vi.mocked(chatService.createChat).mockClear()
    vi.mocked(chatService.updateChat).mockClear()

    act(() => result.current.openCreateChat('assistant'))
    act(() => {
      result.current.createChatModal.onTitleChange('Temporary Title')
    })
    expect(result.current.createChatModal.open).toBe(true)

    act(() => {
      result.current.createChatModal.onClose()
    })

    expect(result.current.createChatModal.open).toBe(false)
    expect(chatService.createChat).not.toHaveBeenCalled()
    expect(chatService.updateChat).not.toHaveBeenCalled()
  })

  // 验证删除流程：弹出确认弹窗，确认后调用 deleteChat 提交 expectedVersion
  it('opens delete confirmation modal and calls deleteChat with expectedVersion on confirm', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    )
    const { result } = renderHook(() => useChatListController(agents, true), { wrapper })
    await waitFor(() => expect(result.current.chatsQuery.isSuccess).toBe(true))

    const targetChat = {
      id: 'chat-to-delete',
      title: 'Target Chat',
      agentName: 'assistant',
      yoloEnabled: false,
      version: '9',
      createTime: null,
      updateTime: null,
    }

    act(() => result.current.openDeleteChat(targetChat))
    expect(result.current.deleteConfirmModal.modal).not.toBeNull()
    expect(result.current.deleteConfirmModal.modal?.title).toBe('删除 Chat')
    expect(result.current.deleteConfirmModal.modal?.description).toContain('Target Chat')

    await act(async () => {
      result.current.deleteConfirmModal.modal?.onConfirm()
    })

    await waitFor(() => {
      expect(chatService.deleteChat).toHaveBeenCalledWith('chat-to-delete', '9')
      expect(result.current.deleteConfirmModal.modal).toBeNull()
    })
  })
})
