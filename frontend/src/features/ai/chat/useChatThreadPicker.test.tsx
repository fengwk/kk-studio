import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { sortThreads, useChatThreadPicker } from '@/features/ai/chat/useChatThreadPicker'
import { chatService } from '@/shared/api/chat-service'
import { queryKeys } from '@/shared/lib/query-keys'
import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'

vi.mock('@/shared/api/chat-service', () => ({
  chatService: {
    listChatThreads: vi.fn(),
  },
}))

function thread(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    threadId: 't',
    sessionId: 's',
    headEntryId: 'root',
    yoloEnabled: false,
    nextCommandSequence: '1',
    revision: '0',
    status: 'IDLE',
    processing: false,
    branchSettings: {
      environment: null,
      agentName: 'assistant',
      model: { providerName: 'p', modelName: 'm', variant: 'v' },
      activeTools: [],
    },
    createTime: '2026-01-01T00:00:00Z',
    updateTime: '2026-01-01T00:00:00Z',
    ...overrides,
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

  it('runs a single useQuery on queryKeys.chats.threads(chatId) via chatService.listChatThreads', async () => {
    vi.mocked(chatService.listChatThreads).mockResolvedValue([thread({ threadId: 't1' })])

    const result = renderHook(
      ({ chatId, open, sort }) => useChatThreadPicker(chatId, open, sort),
      {
        initialProps: { chatId: 'chat-1', open: true, sort: 'recent' as const },
        wrapper,
      },
    )

    await waitFor(() => expect(result.result.current.items[0]?.threadId).toBe('t1'))
    expect(chatService.listChatThreads).toHaveBeenCalledWith('chat-1')
    expect(chatService.listChatThreads).toHaveBeenCalledTimes(1)
    expect(queryKeys.chats.threads('chat-1')).toEqual(['chats', 'threads', 'chat-1'])
  })

  it('does not fetch when the picker is closed', async () => {
    vi.mocked(chatService.listChatThreads).mockResolvedValue([])

    renderHook(() => useChatThreadPicker('chat-1', false, 'recent'), { wrapper })

    await new Promise((resolve) => setTimeout(resolve, 10))
    expect(chatService.listChatThreads).not.toHaveBeenCalled()
  })
})

describe('sortThreads', () => {
  it('sorts by updateTime descending when sort="recent"', () => {
    const sorted = sortThreads(
      [
        thread({ threadId: 'old', updateTime: '2026-01-01T00:00:00Z' }),
        thread({ threadId: 'new', updateTime: '2026-01-03T00:00:00Z' }),
        thread({ threadId: 'mid', updateTime: '2026-01-02T00:00:00Z' }),
      ],
      'recent',
    ).map((item) => item.threadId)
    expect(sorted).toEqual(['new', 'mid', 'old'])
  })

  it('sorts by createTime newest-first when sort="created"', () => {
    const sorted = sortThreads(
      [
        thread({ threadId: 'b', createTime: '2026-01-02T00:00:00Z' }),
        thread({ threadId: 'a', createTime: '2026-01-01T00:00:00Z' }),
        thread({ threadId: 'c', createTime: '2026-01-03T00:00:00Z' }),
      ],
      'created',
    ).map((item) => item.threadId)
    expect(sorted).toEqual(['c', 'b', 'a'])
  })

  it('handles BackendDateTime in number[] array form', () => {
    const sorted = sortThreads(
      [
        thread({ threadId: 'iso', updateTime: '2026-01-02T00:00:00Z' }),
        thread({ threadId: 'arr', updateTime: [2026, 1, 3, 0, 0, 0] }),
        thread({ threadId: 'epoch', updateTime: Date.parse('2026-01-01T00:00:00Z') }),
      ],
      'recent',
    ).map((item) => item.threadId)
    expect(sorted).toEqual(['arr', 'iso', 'epoch'])
  })

  it('treats null timestamps as the oldest possible value', () => {
    const sorted = sortThreads(
      [
        thread({ threadId: 'has', updateTime: '2026-01-01T00:00:00Z' }),
        thread({ threadId: 'none', updateTime: null }),
      ],
      'recent',
    ).map((item) => item.threadId)
    expect(sorted).toEqual(['has', 'none'])
  })
})
