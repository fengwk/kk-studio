import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAgentSessionMessageMutation } from '@/features/ai/useAgentSessionMessageMutation'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessSessionDTO, HarnessSessionEntryDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    createMessage: vi.fn(),
    getSession: vi.fn(),
  },
}))

function session(leafEntryId: string): HarnessSessionDTO {
  return {
    sessionId: '1',
    agentDefinitionId: '1',
    rootSessionId: '1',
    parentSessionId: null,
    depth: 0,
    leafEntryId,
    activeRunId: null,
    title: null,
    yoloEnabled: false,
    createTime: '2026-07-18T00:00:00',
    updateTime: '2026-07-18T00:00:00',
  }
}

function entry(id: string): HarnessSessionEntryDTO {
  return {
    sessionEntryId: id,
    sessionId: '1',
    parentEntryId: null,
    runId: id,
    entryType: 'message',
    payloadJson: '{}',
    createTime: '2026-07-18T00:00:00',
  }
}

describe('useAgentSessionMessageMutation', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(harnessService.createMessage).mockReset()
    vi.mocked(harnessService.getSession).mockReset()
  })

  function wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }

  it('submits against the cached session leaf', async () => {
    queryClient.setQueryData(queryKeys.sessions.detail('1'), session('leaf-a'))
    vi.mocked(harnessService.createMessage).mockResolvedValue(entry('user-1'))
    const onSubmitted = vi.fn()

    const { result } = renderHook(() => useAgentSessionMessageMutation('1', onSubmitted), { wrapper })
    await act(async () => {
      await result.current.mutateAsync('hello')
    })

    expect(harnessService.createMessage).toHaveBeenCalledWith('1', {
      content: 'hello',
      expectedLeafEntryId: 'leaf-a',
    })
    await waitFor(() => expect(onSubmitted).toHaveBeenCalled())
  })

  it('refreshes leaf and retries once on leaf conflict', async () => {
    queryClient.setQueryData(queryKeys.sessions.detail('1'), session('stale-leaf'))
    vi.mocked(harnessService.createMessage)
      .mockRejectedValueOnce(new Error('session leaf changed before run submission'))
      .mockResolvedValueOnce(entry('user-2'))
    vi.mocked(harnessService.getSession).mockResolvedValue(session('fresh-leaf'))

    const { result } = renderHook(() => useAgentSessionMessageMutation('1', vi.fn()), { wrapper })
    await act(async () => {
      await result.current.mutateAsync('retry me')
    })

    expect(harnessService.createMessage).toHaveBeenNthCalledWith(1, '1', {
      content: 'retry me',
      expectedLeafEntryId: 'stale-leaf',
    })
    expect(harnessService.getSession).toHaveBeenCalledWith('1')
    expect(harnessService.createMessage).toHaveBeenNthCalledWith(2, '1', {
      content: 'retry me',
      expectedLeafEntryId: 'fresh-leaf',
    })
  })
})
