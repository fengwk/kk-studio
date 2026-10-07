import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, expect, it, vi } from 'vitest'
import { useInteractionFreshnessRecheck } from '@/shared/lib/useInteractionFreshnessRecheck'

afterEach(() => vi.useRealTimers())

function setup() {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-08T00:00:00Z'))
  const client = new QueryClient()
  const invalidate = vi.spyOn(client, 'invalidateQueries').mockResolvedValue()
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return { invalidate, wrapper }
}

it('读取到服务端时效后只排一次回读：越过截止点即失效全部待处理视图，之后不再重复', () => {
  const { invalidate, wrapper } = setup()
  const nowSeconds = Date.now() / 1000
  renderHook(({ at }) => useInteractionFreshnessRecheck(at), {
    wrapper,
    initialProps: { at: nowSeconds + 10 },
  })

  // 截止点前（含 250ms 宽限）不读；这是时间对账，不是轮询。
  act(() => vi.advanceTimersByTime(10250))
  expect(invalidate).toHaveBeenCalledExactlyOnceWith({ queryKey: ['interactions'] })

  // 同一截止点只消费一次：无新数据时不会反复回读。
  act(() => vi.advanceTimersByTime(60000))
  expect(invalidate).toHaveBeenCalledTimes(1)
})

it('续租替换旧截止点：旧定时器被取消，只按新截止点回读一次', () => {
  const { invalidate, wrapper } = setup()
  const nowSeconds = Date.now() / 1000
  const hook = renderHook(({ at }) => useInteractionFreshnessRecheck(at), {
    wrapper,
    initialProps: { at: nowSeconds + 10 },
  })

  hook.rerender({ at: nowSeconds + 20 })
  act(() => vi.advanceTimersByTime(10250))
  expect(invalidate).not.toHaveBeenCalled()

  act(() => vi.advanceTimersByTime(10000))
  expect(invalidate).toHaveBeenCalledTimes(1)
  hook.unmount()
})

it('接受 ISO 字符串时刻并解析为同一截止点', () => {
  const { invalidate, wrapper } = setup()
  renderHook(() => useInteractionFreshnessRecheck('2026-10-08T00:00:30Z'), { wrapper })
  act(() => vi.advanceTimersByTime(30250))
  expect(invalidate).toHaveBeenCalledTimes(1)
})

it('没有未来变更时刻时不排任务（无租约即停止）', () => {
  const { invalidate, wrapper } = setup()
  renderHook(() => useInteractionFreshnessRecheck(null), { wrapper })
  act(() => vi.advanceTimersByTime(600000))
  expect(invalidate).not.toHaveBeenCalled()
})

it('卸载时清理定时器，不遗留失效动作', () => {
  const { invalidate, wrapper } = setup()
  const nowSeconds = Date.now() / 1000
  const hook = renderHook(() => useInteractionFreshnessRecheck(nowSeconds + 10), { wrapper })
  hook.unmount()
  act(() => vi.advanceTimersByTime(60000))
  expect(invalidate).not.toHaveBeenCalled()
})
