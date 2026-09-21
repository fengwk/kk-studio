import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { useBoundThreadPanelLabels } from '@/features/ai/runtime/useBoundThreadPanelViews'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'

const READY_ENVIRONMENT: EnvironmentCardDTO = {
  id: '8d347585-fd47-46da-9e0b-66d61e0ff21b',
  name: 'dev-box',
  registrationToken: null,
  status: 'READY',
  ready: true,
  lastSeen: '2026-09-19T00:00:00.000Z',
  capabilities: [],
  userName: 'dev-user',
  homeDirectory: '/home/dev',
  version: '1',
  createTime: '2026-09-19T00:00:00.000Z',
  updateTime: '2026-09-19T00:00:00.000Z',
}

describe('useBoundThreadPanelLabels', () => {
  /**
   * 测试意图：bound footer 只投影已生效 snapshot 的 environmentName；
   * 已知名称解析为 Card UUID/READY，未知名称保留原名并标记 unavailable，null 才表示 none。
   */
  it('resolves the active branch environment name without losing unknown identities', () => {
    const { result, rerender } = renderHook(
      ({ environmentName }: { environmentName: string | null }) =>
        useBoundThreadPanelLabels([READY_ENVIRONMENT], {
          runtimeLabels: { environmentName, contextWindow: 128_000 },
          branchUsage: null,
        }),
      { initialProps: { environmentName: 'dev-box' } },
    )

    expect(result.current.environment).toEqual({
      environmentId: READY_ENVIRONMENT.id,
      environmentName: 'dev-box',
    })
    expect(result.current.environmentReady).toBe(true)

    rerender({ environmentName: 'deleted-box' })
    expect(result.current.environment).toEqual({
      environmentId: 'deleted-box',
      environmentName: 'deleted-box',
    })
    expect(result.current.environmentReady).toBe(false)

    rerender({ environmentName: null })
    expect(result.current.environment).toBeNull()
    expect(result.current.environmentReady).toBeUndefined()
  })
})
