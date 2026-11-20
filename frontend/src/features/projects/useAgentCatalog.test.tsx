import { describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { agentService } from '@/shared/api/agent-service'
import { useAgentCatalog } from './useAgentCatalog'
import type { AgentDTO } from '@/shared/api/generated/contracts'

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

describe('useAgentCatalog', () => {
  it('reads the complete catalog in one request and keeps descriptions', async () => {
    // 测试意图：Agent 列表已不分页，超过 50 个 Agent 时必须一次全部返回；描述随同一次读取保留
    const catalogAgents = Array.from({ length: 65 }, (_, i) => ({
      name: `agent-${String(i + 1).padStart(3, '0')}`,
    }))
    vi.mocked(agentService.listAgents).mockResolvedValueOnce([
      ...catalogAgents.slice(1),
      { name: 'agent-001', description: '  负责实现  ' },
    ] as AgentDTO[])

    const { result } = renderHook(() => useAgentCatalog(), { wrapper: createWrapper() })

    await waitFor(() => {
      expect(result.current.ready).toBe(true)
    })

    expect(agentService.listAgents).toHaveBeenCalledTimes(1)
    expect(agentService.listAgents).toHaveBeenCalledWith()
    expect(result.current.agents).toHaveLength(65)
    expect(result.current.agents[0]).toEqual({ name: 'agent-001', description: '负责实现' })
    expect(result.current.agents[64].name).toBe('agent-065')
    expect(result.current.agents[1]).toEqual({ name: 'agent-002', description: null })
    expect(result.current.loading).toBe(false)
    expect(result.current.error).toBe(false)
  })

  it('reports a failed read without pretending the catalog is empty', async () => {
    // 测试意图：读取失败是「未知」而非「空目录」，不得据此判定已保存 Agent 不可用
    vi.mocked(agentService.listAgents).mockRejectedValueOnce(new Error('Network offline'))

    const { result } = renderHook(() => useAgentCatalog(), { wrapper: createWrapper() })

    await waitFor(() => {
      expect(result.current.loading).toBe(false)
    })

    expect(result.current.error).toBe(true)
    expect(result.current.ready).toBe(false)
    expect(result.current.agents).toEqual([])
  })

  it('stays unready while the catalog is still loading', async () => {
    // 测试意图：读取中目录内容未知，不能提前给出可用性结论
    vi.mocked(agentService.listAgents).mockImplementationOnce(() => new Promise(() => {}))

    const { result } = renderHook(() => useAgentCatalog(), { wrapper: createWrapper() })

    await waitFor(() => {
      expect(result.current.loading).toBe(true)
    })

    expect(result.current.ready).toBe(false)
    expect(result.current.error).toBe(false)
    expect(result.current.agents).toEqual([])
  })
})
