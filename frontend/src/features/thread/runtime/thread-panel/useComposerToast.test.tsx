import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useComposerToast } from '@/features/thread/runtime/thread-panel/useComposerToast'

/**
 * 提示条时长语义：普通提示短暂出现，失败原因保留到用户关闭。
 *
 * Composer 只把上传失败标记为 persistent；其余一次性提示仍按 5 秒自动清理，
 * 不长期遮挡会话内容。
 */
describe('useComposerToast', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('dismisses an ordinary notice after five seconds', () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useComposerToast())

    act(() => result.current.showToast('已复制'))
    expect(result.current.toast).toBe('已复制')

    act(() => vi.advanceTimersByTime(4999))
    expect(result.current.toast).toBe('已复制')

    act(() => vi.advanceTimersByTime(1))
    expect(result.current.toast).toBeNull()
  })

  it('keeps a persistent notice until it is dismissed', () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useComposerToast())

    act(() => result.current.showToast('photo.png 上传失败：403 Forbidden', { persistent: true }))
    expect(result.current.toast).toBe('photo.png 上传失败：403 Forbidden')

    act(() => vi.advanceTimersByTime(60_000))
    expect(result.current.toast).toBe('photo.png 上传失败：403 Forbidden')

    act(() => result.current.dismissToast())
    expect(result.current.toast).toBeNull()
  })

  it('replaces the visible notice and restarts the timer for the newest one', () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useComposerToast())

    act(() => result.current.showToast('first'))
    act(() => vi.advanceTimersByTime(4000))
    act(() => result.current.showToast('second'))
    expect(result.current.toast).toBe('second')

    // 旧提示的计时器不能提前清掉新提示。
    act(() => vi.advanceTimersByTime(1000))
    expect(result.current.toast).toBe('second')

    act(() => vi.advanceTimersByTime(4000))
    expect(result.current.toast).toBeNull()
  })

  it('clears the pending timer on unmount', () => {
    vi.useFakeTimers()
    const { result, unmount } = renderHook(() => useComposerToast())

    act(() => result.current.showToast('stale'))
    expect(vi.getTimerCount()).toBe(1)

    unmount()
    // 卸载后不再保留计时器，避免组件消失后仍写状态。
    expect(vi.getTimerCount()).toBe(0)
  })
})
