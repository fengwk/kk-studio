import { describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { agentService } from '@/shared/api/agent-service'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import { fetchAllCatalogAgentNames, useCatalogAgentNames } from './useCatalogAgentNames'

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

describe('useCatalogAgentNames and fetchAllCatalogAgentNames', () => {
  it('paginates across multiple pages to fetch >50 catalog agents', async () => {
    // 测试意图：验证当 catalog 包含超过 50 个 Agent 时，自动执行分页查询直到拉取完所有页并排序去重
    const page1Agents = Array.from({ length: 50 }, (_, i) => ({
      name: `agent-${String(i + 1).padStart(3, '0')}`,
    }))
    const page2Agents = Array.from({ length: 15 }, (_, i) => ({
      name: `agent-${String(i + 51).padStart(3, '0')}`,
    }))

    vi.mocked(agentService.listAgents)
      .mockResolvedValueOnce({
        results: page1Agents as AgentDefinitionDTO[],
        totalCount: 65,
        pageNumber: 1,
        pageSize: 50,
      })
      .mockResolvedValueOnce({
        results: page2Agents as AgentDefinitionDTO[],
        totalCount: 65,
        pageNumber: 2,
        pageSize: 50,
      })

    const names = await fetchAllCatalogAgentNames()

    expect(agentService.listAgents).toHaveBeenCalledWith(1, 50)
    expect(agentService.listAgents).toHaveBeenCalledWith(2, 50)
    expect(names).toHaveLength(65)
    expect(names[0]).toBe('agent-001')
    expect(names[64]).toBe('agent-065')
  })

  it('handles fetch failures robustly without phantom invalid values', async () => {
    // 测试意图：验证网络或服务端接口失败时，函数防御性捕获异常并返回空数组，不生成非法脏数据
    vi.mocked(agentService.listAgents).mockRejectedValueOnce(new Error('Network offline'))

    const names = await fetchAllCatalogAgentNames()
    expect(names).toEqual([])
  })

  it('transparently preserves stale assigned names in options', async () => {
    // 测试意图：验证 Hook 正确合并 catalog 中的 Agent 以及历史已分配但已从 catalog 下线的 stale Agent，且不会静默丢失
    vi.mocked(agentService.listAgents).mockResolvedValueOnce({
      results: [{ name: 'catalog-agent-b' }, { name: 'catalog-agent-a' }] as AgentDefinitionDTO[],
      totalCount: 2,
      pageNumber: 1,
      pageSize: 50,
    })

    const { result } = renderHook(
      () =>
        useCatalogAgentNames({
          preserveNames: ['stale-agent-orphan', null, undefined, '', '   '],
        }),
      { wrapper: createWrapper() },
    )

    await waitFor(() => {
      expect(result.current.catalogNames).toEqual(['catalog-agent-a', 'catalog-agent-b'])
    })

    // options 应包含已保全的 stale-agent-orphan，且已排序
    expect(result.current.agentOptions).toEqual([
      'catalog-agent-a',
      'catalog-agent-b',
      'stale-agent-orphan',
    ])
  })
})
