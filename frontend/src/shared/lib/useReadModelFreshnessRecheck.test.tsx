import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, expect, it, vi } from 'vitest'
import { useReadModelFreshnessRecheck } from '@/shared/lib/useReadModelFreshnessRecheck'

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

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
  renderHook(({ at }) => useReadModelFreshnessRecheck(['interactions'], at), {
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

it('同缓存两个消费者共享同一截止点，只失效一次', () => {
  const { invalidate, wrapper } = setup()
  const at = Date.now() / 1000 + 10
  renderHook(() => useReadModelFreshnessRecheck(['interactions'], at), { wrapper })
  renderHook(() => useReadModelFreshnessRecheck(['interactions'], at), { wrapper })
  act(() => vi.advanceTimersByTime(10250))
  expect(invalidate).toHaveBeenCalledTimes(1)
})

it('续租替换旧截止点：旧定时器被取消，只按新截止点回读一次', () => {
  const { invalidate, wrapper } = setup()
  const nowSeconds = Date.now() / 1000
  const hook = renderHook(({ at }) => useReadModelFreshnessRecheck(['interactions'], at), {
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
  renderHook(() => useReadModelFreshnessRecheck(['interactions'], '2026-10-08T00:00:30Z'), { wrapper })
  act(() => vi.advanceTimersByTime(30250))
  expect(invalidate).toHaveBeenCalledTimes(1)
})

it('没有未来变更时刻时不排任务（无租约即停止）', () => {
  const { invalidate, wrapper } = setup()
  renderHook(() => useReadModelFreshnessRecheck(['interactions'], null), { wrapper })
  act(() => vi.advanceTimersByTime(600000))
  expect(invalidate).not.toHaveBeenCalled()
})

it('卸载时清理定时器，不遗留失效动作', () => {
  const { invalidate, wrapper } = setup()
  const nowSeconds = Date.now() / 1000
  const hook = renderHook(() => useReadModelFreshnessRecheck(['interactions'], nowSeconds + 10), { wrapper })
  hook.unmount()
  act(() => vi.advanceTimersByTime(60000))
  expect(invalidate).not.toHaveBeenCalled()
})

it('最后卸载释放计时器；后来加入已消费截止点不再回读', () => {
  const { invalidate, wrapper } = setup()
  const at = Date.now() / 1000 + 10
  const first = renderHook(() => useReadModelFreshnessRecheck(['interactions'], at), { wrapper })
  act(() => vi.advanceTimersByTime(10250))
  first.unmount()
  expect(vi.getTimerCount()).toBe(0)
  renderHook(() => useReadModelFreshnessRecheck(['interactions'], at), { wrapper })
  expect(vi.getTimerCount()).toBe(0)
  act(() => vi.advanceTimersByTime(60000))
  expect(invalidate).toHaveBeenCalledTimes(1)
})

it('不同截止点按绝对未来时刻重排，不从上次回读重新计算间隔', () => {
  const { invalidate, wrapper } = setup()
  const nowSeconds = Date.now() / 1000
  renderHook(() => useReadModelFreshnessRecheck(['interactions'], nowSeconds + 10), { wrapper })
  renderHook(() => useReadModelFreshnessRecheck(['interactions'], nowSeconds + 20), { wrapper })
  expect(vi.getTimerCount()).toBe(1)
  act(() => vi.advanceTimersByTime(10249))
  expect(invalidate).not.toHaveBeenCalled()
  act(() => vi.advanceTimersByTime(1))
  expect(invalidate).toHaveBeenCalledTimes(1)
  act(() => vi.advanceTimersByTime(9999))
  expect(invalidate).toHaveBeenCalledTimes(1)
  act(() => vi.advanceTimersByTime(1))
  expect(invalidate).toHaveBeenCalledTimes(2)
  expect(vi.getTimerCount()).toBe(0)
})

it('一个同截止点消费者卸载不影响另一个，最后卸载清理未触发任务', () => {
  const { invalidate, wrapper } = setup()
  const at = Date.now() / 1000 + 10
  const first = renderHook(() => useReadModelFreshnessRecheck(['interactions'], at), { wrapper })
  const second = renderHook(({ at }) => useReadModelFreshnessRecheck(['interactions'], at), {
    wrapper,
    initialProps: { at },
  })
  first.unmount()
  expect(vi.getTimerCount()).toBe(1)
  act(() => vi.advanceTimersByTime(10250))
  expect(invalidate).toHaveBeenCalledTimes(1)
  second.rerender({ at: at + 20 })
  expect(vi.getTimerCount()).toBe(1)
  second.unmount()
  expect(vi.getTimerCount()).toBe(0)
  act(() => vi.advanceTimersByTime(60000))
  expect(invalidate).toHaveBeenCalledTimes(1)
})

it('续租只注销自己的旧截止点，仍订阅旧截止点的消费者继续回读', () => {
  const { invalidate, wrapper } = setup()
  const at = Date.now() / 1000 + 10
  const first = renderHook(({ at }) => useReadModelFreshnessRecheck(['interactions'], at), {
    wrapper,
    initialProps: { at },
  })
  renderHook(() => useReadModelFreshnessRecheck(['interactions'], at), { wrapper })
  first.rerender({ at: at + 10 })
  act(() => vi.advanceTimersByTime(10250))
  expect(invalidate).toHaveBeenCalledTimes(1)
  act(() => vi.advanceTimersByTime(10000))
  expect(invalidate).toHaveBeenCalledTimes(2)
})

it('独立 QueryClient 不共享计时器或消费记录', () => {
  const first = setup()
  const second = setup()
  const at = Date.now() / 1000 + 10
  renderHook(() => useReadModelFreshnessRecheck(['interactions'], at), { wrapper: first.wrapper })
  renderHook(() => useReadModelFreshnessRecheck(['interactions'], at), { wrapper: second.wrapper })
  expect(vi.getTimerCount()).toBe(2)
  act(() => vi.advanceTimersByTime(10250))
  expect(first.invalidate).toHaveBeenCalledTimes(1)
  expect(second.invalidate).toHaveBeenCalledTimes(1)
})

it('同一缓存的不同查询 scope 互不抑制，换绑取消旧 scope 的截止点', () => {
  const { invalidate, wrapper } = setup()
  const at = Date.now() / 1000 + 10
  const first = renderHook(({ id }) =>
    useReadModelFreshnessRecheck(['threads', id, 'snapshot'], at), {
    wrapper, initialProps: { id: 'a' },
  })
  renderHook(() => useReadModelFreshnessRecheck(['interactions'], at), { wrapper })
  first.rerender({ id: 'b' })
  act(() => vi.advanceTimersByTime(10250))
  expect(invalidate).toHaveBeenCalledTimes(2)
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['threads', 'b', 'snapshot'] })
  expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ['threads', 'a', 'snapshot'] })
  renderHook(() => useReadModelFreshnessRecheck(['threads', 'a', 'snapshot'], at), { wrapper })
  act(() => vi.advanceTimersByTime(0))
  expect(invalidate).toHaveBeenCalledTimes(3)
})

it('过去时刻只消费一次，切换后回到旧截止点也不重复', () => {
  const { invalidate, wrapper } = setup()
  const at = Date.now() / 1000 - 10
  const hook = renderHook(({ at }) => useReadModelFreshnessRecheck(['interactions'], at), {
    wrapper,
    initialProps: { at },
  })
  act(() => vi.advanceTimersByTime(250))
  expect(invalidate).toHaveBeenCalledTimes(1)
  hook.rerender({ at: at - 10 })
  act(() => vi.advanceTimersByTime(250))
  expect(invalidate).toHaveBeenCalledTimes(2)
  hook.rerender({ at })
  expect(vi.getTimerCount()).toBe(0)
  act(() => vi.advanceTimersByTime(60000))
  expect(invalidate).toHaveBeenCalledTimes(2)
})

it('undefined、无效 ISO 和非有限数字不排任务，清空截止点取消旧任务', () => {
  const { invalidate, wrapper } = setup()
  const hook = renderHook(({ at }) => useReadModelFreshnessRecheck(['interactions'], at), {
    wrapper,
    initialProps: { at: undefined as string | number | undefined },
  })
  hook.rerender({ at: 'invalid' })
  hook.rerender({ at: Infinity })
  expect(vi.getTimerCount()).toBe(0)
  hook.rerender({ at: Date.now() / 1000 + 10 })
  expect(vi.getTimerCount()).toBe(1)
  hook.rerender({ at: undefined })
  expect(vi.getTimerCount()).toBe(0)
  act(() => vi.advanceTimersByTime(60000))
  expect(invalidate).not.toHaveBeenCalled()
})
