import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { agentService } from '@/shared/api/agent-service'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import { useCoordinatorAgents } from './useCoordinatorAgents'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn(),
  },
}))

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return function QueryWrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

function createMockAgent(name: string): AgentDefinitionDTO {
  return {
    name,
    description: null,
    systemPrompt: null,
    model: 'mock-model',
    variant: null,
    config: { tools: [], mcpServers: [] },
    version: 1,
    createTime: '2026-09-14T00:00:00Z',
    updateTime: '2026-09-14T00:00:00Z',
  }
}

describe('useCoordinatorAgents', () => {
  it('calls agentService.listAgents() without custom pagination and sorts/deduplicates agents', async () => {
    // 测试意图：验证使用默认 listAgents() 请求契约以保证 React Query 缓存一致性，并对列表进行去重与字母序排序
    vi.mocked(agentService.listAgents).mockResolvedValue({
      results: [
        createMockAgent('zeta-agent'),
        createMockAgent('alpha-agent'),
        createMockAgent('zeta-agent'), // 重复项
        createMockAgent('beta-agent'),
      ],
      totalCount: 4,
      pageNumber: 1,
      pageSize: 50,
    })

    const { result } = renderHook(() => useCoordinatorAgents(), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isLoading).toBe(false))

    expect(agentService.listAgents).toHaveBeenCalledWith()
    expect(result.current.agents.map((a) => a.name)).toEqual([
      'alpha-agent',
      'beta-agent',
      'zeta-agent',
    ])
    expect(result.current.options).toEqual([
      { value: 'alpha-agent', label: 'alpha-agent' },
      { value: 'beta-agent', label: 'beta-agent' },
      { value: 'zeta-agent', label: 'zeta-agent' },
    ])
    expect(result.current.isCoordinatorValid('alpha-agent')).toBe(true)
    expect(result.current.isCoordinatorValid('unknown-agent')).toBe(false)
  })

  it('keeps orphan coordinator as disabled at top of options and invalid for submission', async () => {
    // 测试意图：验证孤儿 Agent 被防御性置于首项且 disabled，不可作为合法有效 coordinator
    vi.mocked(agentService.listAgents).mockResolvedValue({
      results: [createMockAgent('agent-1')],
      totalCount: 1,
      pageNumber: 1,
      pageSize: 50,
    })

    const { result } = renderHook(() => useCoordinatorAgents('orphan-agent'), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isLoading).toBe(false))

    expect(result.current.options).toEqual([
      { value: 'orphan-agent', label: 'orphan-agent (不可用)', disabled: true },
      { value: 'agent-1', label: 'agent-1' },
    ])
    expect(result.current.isCoordinatorValid('orphan-agent')).toBe(false)
    expect(result.current.isCoordinatorValid('agent-1')).toBe(true)
  })
})
