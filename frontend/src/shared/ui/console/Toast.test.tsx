import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Toast } from './Toast'

describe('Toast', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders success as status in a body-level portal and auto dismisses', () => {
    vi.useFakeTimers()
    const onDismiss = vi.fn()
    const { container } = render(<Toast message="配置已保存" onDismiss={onDismiss} />)

    const toast = screen.getByRole('status')
    expect(toast).toHaveTextContent('配置已保存')
    // 反馈必须挂在页级，不能被 modal-body 等滚动容器裁剪。
    expect(container.contains(toast)).toBe(false)
    expect(document.body.contains(toast)).toBe(true)

    act(() => {
      vi.advanceTimersByTime(4000)
    })
    expect(onDismiss).toHaveBeenCalledTimes(1)
  })

  it('uses role alert for error feedback', () => {
    render(<Toast message="复制失败" tone="error" onDismiss={vi.fn()} />)
    expect(screen.getByRole('alert')).toHaveTextContent('复制失败')
  })

  it('restarts the auto-dismiss timer when the same feedback repeats via nonce', () => {
    vi.useFakeTimers()
    const onDismiss = vi.fn()
    const { rerender } = render(<Toast message="命令已复制" nonce={0} onDismiss={onDismiss} />)

    act(() => {
      vi.advanceTimersByTime(3000)
    })
    // 相同 message 再次出现时靠 nonce 变化重新计时，而不是沿用旧定时器。
    rerender(<Toast message="命令已复制" nonce={1} onDismiss={onDismiss} />)
    act(() => {
      vi.advanceTimersByTime(3000)
    })
    expect(onDismiss).not.toHaveBeenCalled()
    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(onDismiss).toHaveBeenCalledTimes(1)
  })

  it('clears the pending timer on unmount so it cannot dismiss after teardown', () => {
    vi.useFakeTimers()
    const onDismiss = vi.fn()
    const { unmount } = render(<Toast message="配置已保存" onDismiss={onDismiss} />)

    unmount()
    act(() => {
      vi.advanceTimersByTime(10000)
    })
    expect(onDismiss).not.toHaveBeenCalled()
  })

  it('dismisses manually through the keyboard-accessible close button', () => {
    const onDismiss = vi.fn()
    render(<Toast message="配置已保存" onDismiss={onDismiss} />)

    const close = screen.getByRole('button', { name: '关闭通知' })
    close.focus()
    expect(close).toHaveFocus()
    fireEvent.click(close)
    expect(onDismiss).toHaveBeenCalledTimes(1)
  })
})
