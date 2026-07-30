import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  useAiConsoleController,
  type AiConsolePageScope,
} from '@/features/ai/extensions/useAiConsoleController'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { environmentService } from '@/shared/api/environment-service'
import { queryKeys } from '@/shared/lib/query-keys'

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
  },
}))
vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
  },
}))

/** Each scope asserts both required requests and forbidden cross-page requests. */
const scopeQueryMatrix: Array<{
  scope: AiConsolePageScope
  expected: {
    providers: number
    models: number
    agents: number
    environments: number
    chats: number
  }
}> = [
  {
    scope: 'chats',
    expected: {
      providers: 0,
      models: 0,
      agents: 1,
      environments: 0,
      chats: 1,
    },
  },
  {
    scope: 'agents',
    expected: {
      providers: 1,
      models: 1,
      agents: 1,
      environments: 1,
      chats: 0,
    },
  },
  {
    scope: 'models',
    expected: {
      providers: 1,
      models: 1,
      agents: 0,
      environments: 0,
      chats: 0,
    },
  },
  {
    scope: 'providers',
    expected: {
      providers: 1,
      models: 0,
      agents: 0,
      environments: 0,
      chats: 0,
    },
  },
]

describe('useAiConsoleController', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listProviders).mockResolvedValue(page([]))
    vi.mocked(agentService.listModels).mockResolvedValue(page([]))
    vi.mocked(agentService.listAgents).mockResolvedValue(page([]))
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
    vi.mocked(chatService.listChats).mockResolvedValue([])
  })

  it.each(scopeQueryMatrix)(
    'loads only the queries required by the $scope page scope',
    async ({ scope, expected }) => {
      const { result } = renderHook(() => useAiConsoleController(scope), {
        wrapper: queryWrapper(new QueryClient(queryClientOptions)),
      })

      await waitFor(() => {
        expect(agentService.listProviders).toHaveBeenCalledTimes(expected.providers)
        expect(agentService.listModels).toHaveBeenCalledTimes(expected.models)
        expect(agentService.listAgents).toHaveBeenCalledTimes(expected.agents)
        expect(environmentService.listEnvironments).toHaveBeenCalledTimes(
          expected.environments,
        )
        expect(chatService.listChats).toHaveBeenCalledTimes(expected.chats)
        expect(result.current.busy).toBe(false)
        expect(result.current.error).toBeNull()
      })
    },
  )

  /** A cached disabled-query failure must not render an unrelated page as failed. */
  it('excludes disabled query errors from page state aggregation', async () => {
    const queryClient = new QueryClient(queryClientOptions)
    await expect(
      queryClient.fetchQuery({
        queryKey: queryKeys.models.list,
        queryFn: async () => {
          throw new Error('stale models error')
        },
      }),
    ).rejects.toThrow('stale models error')

    const { result } = renderHook(() => useAiConsoleController('providers'), {
      wrapper: queryWrapper(queryClient),
    })

    await waitFor(() => {
      expect(agentService.listProviders).toHaveBeenCalledTimes(1)
      expect(result.current.busy).toBe(false)
      expect(result.current.error).toBeNull()
    })

    expect(agentService.listModels).not.toHaveBeenCalled()
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
