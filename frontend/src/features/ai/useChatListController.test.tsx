import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { resolveChatDefaultAgentId, useChatListController } from '@/features/ai/useChatListController'
import { chatService } from '@/shared/api/chat-service'

vi.mock('@/shared/api/chat-service', () => ({
  chatService: {
    listChats: vi.fn(async () => []),
    createChat: vi.fn(async () => ({
      id: 'c1',
      title: 'T',
      defaultAgentId: 'a1',
      version: 1,
      createTime: null,
      updateTime: null,
    })),
  },
}))

const agents = [
  {
    id: 'a1',
    name: 'assistant',
    description: null,
    systemPrompt: null,
    modelId: 'm1',
    variant: 'default',
    config: {
      environmentName: null,
      tools: [],
      skills: [],
    },
    createTime: null,
    updateTime: null,
  },
]

describe('useChatListController', () => {
  it('resolves default agent fallbacks and creates chat', async () => {
    expect(resolveChatDefaultAgentId('a1', '', agents)).toBe('a1')
    expect(resolveChatDefaultAgentId('missing', 'a1', agents)).toBe('a1')
    expect(resolveChatDefaultAgentId(undefined, 'missing', agents)).toBe('')
    expect(resolveChatDefaultAgentId(undefined, '', [])).toBe('')

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    )
    const { result } = renderHook(() => useChatListController(agents), { wrapper })
    await waitFor(() => expect(result.current.chatsQuery.isSuccess).toBe(true))
    act(() => result.current.openCreateChat('a1'))
    expect(result.current.createChatModal.open).toBe(true)
    expect(result.current.createChatModal.selectedAgentId).toBe('a1')

    await act(async () => {
      result.current.createChatModal.onSubmit({ preventDefault() {} } as never)
    })
    expect(chatService.createChat).not.toHaveBeenCalled()
    expect(result.current.createChatModal.formError).toBe('请填写 Chat 名称')
    expect(result.current.createChatModal.nameError).toBe('请填写名称')

    act(() => {
      result.current.createChatModal.onTitleChange('Hello')
      result.current.createChatModal.onSelectAgent('')
    })
    expect(result.current.createChatModal.formError).toBe('')
    expect(result.current.createChatModal.nameError).toBe('')
    await act(async () => {
      result.current.createChatModal.onSubmit({ preventDefault() {} } as never)
    })
    await waitFor(() => expect(chatService.createChat).toHaveBeenCalledWith({ title: 'Hello', defaultAgentId: undefined }))
  })
})
