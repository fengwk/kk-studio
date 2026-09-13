import { describe, expect, it, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import {
  notifyProjectsChanged,
  useProjectsInvalidation,
} from './useProjectsInvalidation'

describe('useProjectsInvalidation', () => {
  it('should receive notifications when notifyProjectsChanged is dispatched', () => {
    // 测试意图：验证订阅者能够准确接收到 notifyProjectsChanged 广播的 payload
    const listener = vi.fn()
    const { unmount } = renderHook(() => useProjectsInvalidation(listener))

    notifyProjectsChanged({ projectId: 'a0000000-0000-0000-0000-000000000001' })
    expect(listener).toHaveBeenCalledWith({
      projectId: 'a0000000-0000-0000-0000-000000000001',
    })

    unmount()
    notifyProjectsChanged({ projectId: 'a0000000-0000-0000-0000-000000000002' })
    // 取消挂载后不应再触发
    expect(listener).toHaveBeenCalledTimes(1)
  })
})
