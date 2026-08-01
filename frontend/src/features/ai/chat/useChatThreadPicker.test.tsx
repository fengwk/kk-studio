import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useChatThreadPicker } from '@/features/ai/chat/useChatThreadPicker'
import { chatService } from '@/shared/api/chat-service'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'

vi.mock('@/shared/api/chat-service', () => ({
  chatService: {
    listChatThreads: vi.fn(),
  },
}))

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    listThreads: vi.fn(),
  },
}))

function thread(threadId: string): HarnessThreadDTO {
  return {
    threadId,
    revision: '0',
    sessionId: null,
    sessionTitle: null,
    headEntryId: null,
    executionEpoch: 0,
    status: 'UNBOUND',
    inputSequence: 0,
    activeAgentDefinitionId: null,
    activeAgentName: null,
    activeEnvironmentName: null,
    modelId: null,
    variant: null,
    yoloEnabled: false,
    processing: false,
    createTime: '2026-01-01T00:00:00Z',
    updateTime: '2026-01-01T00:00:00Z',
  }
}

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

describe('useChatThreadPicker', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('keeps scope while changing sort and starts every scope/sort query at its first cursor', async () => {
    vi.mocked(chatService.listChatThreads).mockResolvedValue({
      items: [thread('current-recent')],
      nextCursor: null,
    })
    vi.mocked(harnessService.listThreads).mockResolvedValue({
      items: [thread('global-recent')],
      nextCursor: null,
    })

    const result = renderHook(
      ({ chatId, open, sort }) => useChatThreadPicker(chatId, open, sort),
      {
        initialProps: { chatId: 'chat-1', open: true, sort: 'recent' as const },
        wrapper,
      },
    )
    await waitFor(() => expect(result.result.current.items[0]?.threadId).toBe('current-recent'))

    result.result.current.setScope('global')
    await waitFor(() => expect(result.result.current.items[0]?.threadId).toBe('global-recent'))
    expect(harnessService.listThreads).toHaveBeenLastCalledWith({
      sort: 'recent',
      cursor: undefined,
      limit: 20,
    })

    result.rerender({ chatId: 'chat-1', open: true, sort: 'created' })
    await waitFor(() => expect(result.result.current.items[0]?.threadId).toBe('global-recent'))
    expect(harnessService.listThreads).toHaveBeenLastCalledWith({
      sort: 'created',
      cursor: undefined,
      limit: 20,
    })
  })

  it('loads more pages and resets scope when opening a different Chat', async () => {
    vi.mocked(chatService.listChatThreads)
      .mockResolvedValueOnce({ items: [thread('first')], nextCursor: 'cursor-1' })
      .mockResolvedValueOnce({ items: [thread('second')], nextCursor: null })
      .mockResolvedValue({ items: [thread('new-chat')], nextCursor: null })

    const result = renderHook(
      ({ chatId, open }) => useChatThreadPicker(chatId, open, 'recent'),
      {
        initialProps: { chatId: 'chat-1', open: true },
        wrapper,
      },
    )
    await waitFor(() => expect(result.result.current.items[0]?.threadId).toBe('first'))
    await result.result.current.loadMore()
    await waitFor(() => expect(result.result.current.items.map((item) => item.threadId)).toEqual(['first', 'second']))
    expect(chatService.listChatThreads).toHaveBeenNthCalledWith(2, 'chat-1', {
      sort: 'recent',
      cursor: 'cursor-1',
      limit: 20,
    })

    result.result.current.setScope('global')
    result.rerender({ chatId: 'chat-2', open: true })
    await waitFor(() => expect(result.result.current.scope).toBe('current'))
    expect(chatService.listChatThreads).toHaveBeenLastCalledWith('chat-2', {
      sort: 'recent',
      cursor: undefined,
      limit: 20,
    })
  })
})
