import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { useChatListController } from '@/features/ai/chat/useChatListController'
import { chatService } from '@/shared/api/chat-service'

vi.mock('@/shared/api/chat-service', () => ({
  chatService: {
    listChats: vi.fn(async () => []),
    createChat: vi.fn(async () => ({
      id: 'c1',
      title: 'T',
      agentName: 'assistant',
      environmentName: null,
      yoloEnabled: false,
      version: '1',
      createTime: null,
      updateTime: null,
    })),
  },
}))

const agents = [
  {
    name: 'assistant',
    description: null,
    systemPrompt: null,
    model: 'minimax/MiniMax',
    variant: 'default',
    config: {
      tools: [],
      skills: [],
    },
    createTime: null,
    updateTime: null,
  },
]

const environments = [
  { name: 'local', status: 'READY', lastSeen: null, tools: [], skills: [] },
]

describe('useChatListController', () => {
  it('creates chat through the selected default agent', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    )
    const { result } = renderHook(() => useChatListController(agents, environments, true), { wrapper })
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
      result.current.createChatModal.onSelectEnvironment('local')
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
        environmentName: 'local',
      }),
    )
  })
})
