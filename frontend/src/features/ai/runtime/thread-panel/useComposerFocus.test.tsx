import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useRef, type ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import {
  useComposerFocus,
} from '@/features/ai/runtime/thread-panel/useComposerFocus'

function Harness({
  disabled = false,
  active = true,
  focusOnEscape = false,
  pending = false,
  closeOverlay = vi.fn(),
  onLeaveRegion,
  renderChild,
}: {
  disabled?: boolean
  active?: boolean
  focusOnEscape?: boolean
  pending?: boolean
  closeOverlay?: () => boolean | void
  onLeaveRegion?: () => void
  renderChild?: () => ReactNode
}) {
  const containerRef = useRef<HTMLDivElement>(null)
  const editorRef = useRef<HTMLDivElement>(null)
  const { isFocused, focusComposer, blurComposer, handleKeyDown } = useComposerFocus({
    containerRef,
    editorRef,
    disabled,
    active,
    focusOnEscape,
    pending,
    closeOverlay,
    onLeaveRegion,
  })
  return (
    <>
      <button type="button">outside</button>
      <div
        ref={containerRef}
        hidden={!active}
        onKeyDown={handleKeyDown}
      >
        <div
          ref={editorRef}
          contentEditable={!disabled}
          aria-label="给 AI 发送消息"
        />
        {renderChild?.()}
        <span data-testid="is-focused">{String(isFocused)}</span>
      </div>
      <button type="button" onClick={() => focusComposer(true)}>focus composer</button>
      <button type="button" onClick={() => blurComposer()}>blur composer</button>
    </>
  )
}

function expectCaretAtEnd(editor: HTMLElement) {
  const selection = document.getSelection()
  const caret = selection?.getRangeAt(0)
  expect(caret?.collapsed).toBe(true)
  const trailing = document.createRange()
  trailing.selectNodeContents(editor)
  trailing.setStart(caret!.endContainer, caret!.endOffset)
  expect(trailing.toString()).toBe('')
}

describe('useComposerFocus', () => {
  it('clears pending focus timers on unmount', () => {
    vi.useFakeTimers()
    try {
      const { unmount } = render(<Harness />)
      fireEvent.click(screen.getByRole('button', { name: 'focus composer' }))
      expect(vi.getTimerCount()).toBe(1)

      unmount()
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('clears scheduled focus work when the composer becomes inactive', () => {
    vi.useFakeTimers()
    try {
      const { rerender } = render(<Harness />)
      fireEvent.click(screen.getByRole('button', { name: 'focus composer' }))
      expect(vi.getTimerCount()).toBe(1)

      rerender(<Harness active={false} />)
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('retries focus while the editor is not yet editable', () => {
    vi.useFakeTimers()
    try {
      const { rerender } = render(<Harness disabled />)
      fireEvent.click(screen.getByRole('button', { name: 'focus composer' }))
      // 第一次尝试（0ms）发现 contenteditable=false，进入 16ms 重试。
      vi.advanceTimersByTime(0)
      expect(vi.getTimerCount()).toBe(1)

      rerender(<Harness disabled={false} />)
      vi.advanceTimersByTime(16)
      expect(screen.getByLabelText('给 AI 发送消息')).toHaveFocus()
      expectCaretAtEnd(screen.getByLabelText('给 AI 发送消息'))
    } finally {
      vi.useRealTimers()
    }
  })

  it('stops retrying after the focus budget is exhausted', () => {
    vi.useFakeTimers()
    try {
      render(<Harness disabled />)
      fireEvent.click(screen.getByRole('button', { name: 'focus composer' }))
      expect(vi.getTimerCount()).toBe(1)

      // 0ms 首次尝试 + 5 次 16ms 重试；预算耗尽后不再产生新定时器。
      vi.advanceTimersByTime(16 * 6)
      expect(vi.getTimerCount()).toBe(0)
      expect(screen.getByLabelText('给 AI 发送消息')).not.toHaveFocus()
    } finally {
      vi.useRealTimers()
    }
  })

  it('restores focus through global Escape when unfocused and closes the active overlay when focused', async () => {
    const user = userEvent.setup()
    const closeOverlay = vi.fn()
    render(<Harness focusOnEscape closeOverlay={closeOverlay} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    const outside = screen.getByRole('button', { name: 'outside' })

    // 区域内聚焦时按 Escape：关闭覆盖层并 blur
    await user.click(editor)
    expect(editor).toHaveFocus()
    fireEvent.keyDown(editor, { key: 'Escape' })
    expect(closeOverlay).toHaveBeenCalled()
    expect(editor).not.toHaveFocus()

    // 未聚焦时全局 Escape：恢复焦点到编辑器末尾
    await user.click(outside)
    expect(outside).toHaveFocus()

    fireEvent.keyDown(window, { key: 'Escape' })

    await waitFor(() => expect(editor).toHaveFocus())
    expectCaretAtEnd(editor)
  })

  it('does not respond to Escape while a blocking modal is open', async () => {
    const user = userEvent.setup()
    const closeOverlay = vi.fn()
    render(
      <>
        <Harness focusOnEscape closeOverlay={closeOverlay} />
        <div className="modal-backdrop">
          <button type="button">Modal action</button>
        </div>
      </>,
    )
    const modalAction = screen.getByRole('button', { name: 'Modal action' })
    await user.click(modalAction)
    fireEvent.keyDown(window, { key: 'Escape' })

    expect(modalAction).toHaveFocus()
    expect(closeOverlay).not.toHaveBeenCalled()
    expect(screen.getByLabelText('给 AI 发送消息')).not.toHaveFocus()
  })

  it('ignores composing, IME-sentinel and default-prevented Escape events', () => {
    const closeOverlay = vi.fn()
    render(<Harness focusOnEscape closeOverlay={closeOverlay} />)

    fireEvent.keyDown(window, { key: 'Escape', isComposing: true })
    fireEvent.keyDown(window, { key: 'Escape', keyCode: 229 })
    const prevented = new KeyboardEvent('keydown', {
      key: 'Escape',
      bubbles: true,
      cancelable: true,
    })
    prevented.preventDefault()
    window.dispatchEvent(prevented)

    expect(closeOverlay).not.toHaveBeenCalled()
    expect(screen.getByLabelText('给 AI 发送消息')).not.toHaveFocus()
  })

  it('does not consume Escape while the composer is disabled', async () => {
    const user = userEvent.setup()
    const closeOverlay = vi.fn()
    render(<Harness disabled focusOnEscape closeOverlay={closeOverlay} />)
    const outside = screen.getByRole('button', { name: 'outside' })
    await user.click(outside)
    fireEvent.keyDown(window, { key: 'Escape' })

    expect(outside).toHaveFocus()
    expect(closeOverlay).not.toHaveBeenCalled()
  })

  it('does not register the global Escape handler without focusOnEscape', async () => {
    const user = userEvent.setup()
    const closeOverlay = vi.fn()
    render(<Harness closeOverlay={closeOverlay} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    const outside = screen.getByRole('button', { name: 'outside' })
    await user.click(outside)
    fireEvent.keyDown(window, { key: 'Escape' })

    expect(closeOverlay).not.toHaveBeenCalled()
    expect(editor).not.toHaveFocus()
  })

  it('restores the ending caret when reactivated after an interaction panel takeover', async () => {
    const { rerender } = render(<Harness />)
    const editor = screen.getByLabelText('给 AI 发送消息')

    rerender(<Harness active={false} />)
    expect(editor.closest('[hidden]')).not.toBeNull()

    rerender(<Harness active />)
    await waitFor(() => expect(editor).toHaveFocus())
    expectCaretAtEnd(editor)
  })

  it('refocuses after a pending submission completes', async () => {
    const user = userEvent.setup()
    const { rerender } = render(<Harness pending />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    const outside = screen.getByRole('button', { name: 'outside' })
    await user.click(outside)
    expect(outside).toHaveFocus()

    rerender(<Harness pending={false} />)
    await waitFor(() => expect(editor).toHaveFocus())
  })

  it('defers pending auto-focus while inactive and restores exactly once on reactivation', async () => {
    const user = userEvent.setup()
    const { rerender } = render(<Harness pending active={false} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    const outside = screen.getByRole('button', { name: 'outside' })
    await user.click(outside)
    expect(outside).toHaveFocus()

    // pending 完成时 interaction panel 仍在接管：隐藏 editor 不抢焦点，只保留意图。
    rerender(<Harness pending={false} active={false} />)
    expect(editor).not.toHaveFocus()
    expect(outside).toHaveFocus()

    const focusSpy = vi.fn()
    editor.addEventListener('focus', focusSpy)
    // active 重新打开且 enabled：恢复焦点到编辑器末尾，且只发生一次。
    rerender(<Harness pending={false} active />)
    await waitFor(() => expect(editor).toHaveFocus())
    expectCaretAtEnd(editor)
    expect(focusSpy).toHaveBeenCalledTimes(1)

    rerender(<Harness pending={false} active />)
    expect(focusSpy).toHaveBeenCalledTimes(1)
  })

  it('skips pending auto-focus while disabled', async () => {
    const user = userEvent.setup()
    const { rerender } = render(<Harness pending disabled />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    const outside = screen.getByRole('button', { name: 'outside' })
    await user.click(outside)

    rerender(<Harness pending={false} disabled />)
    expect(outside).toHaveFocus()
    expect(editor).not.toHaveFocus()
  })

  it('cancels pending retry timers on blurComposer so no focus stealing occurs after timeout > 32ms', () => {
    vi.useFakeTimers()
    try {
      const { rerender } = render(<Harness disabled />)
      // 请求聚焦 disabled 的 editor（启动 0ms 首次尝试并准备 16ms 重试）
      fireEvent.click(screen.getByRole('button', { name: 'focus composer' }))
      vi.advanceTimersByTime(0)
      expect(vi.getTimerCount()).toBe(1)

      // 主动调用 blurComposer() 取消待执行聚焦
      fireEvent.click(screen.getByRole('button', { name: 'blur composer' }))
      expect(vi.getTimerCount()).toBe(0)

      // 变为 enabled，并向前快进超过 32ms（例如 48ms）
      rerender(<Harness disabled={false} />)
      vi.advanceTimersByTime(48)

      // 验证未产生任何新定时器，editor 绝对没有被抢焦
      expect(vi.getTimerCount()).toBe(0)
      expect(screen.getByLabelText('给 AI 发送消息')).not.toHaveFocus()
    } finally {
      vi.useRealTimers()
    }
  })

  it('isolates global Escape to only the pane configured with focusOnEscape', async () => {
    const user = userEvent.setup()
    render(
      <>
        <div data-testid="pane-1">
          <Harness focusOnEscape />
        </div>
        <div data-testid="pane-2">
          <Harness focusOnEscape={false} />
        </div>
      </>,
    )
    const editors = screen.getAllByLabelText('给 AI 发送消息')
    const outside = screen.getAllByRole('button', { name: 'outside' })[0]!

    await user.click(outside)
    expect(outside).toHaveFocus()
    expect(editors[0]).not.toHaveFocus()
    expect(editors[1]).not.toHaveFocus()

    fireEvent.keyDown(window, { key: 'Escape' })

    await waitFor(() => expect(editors[0]).toHaveFocus())
    expect(editors[1]).not.toHaveFocus()
  })

  it('ignores Escape when repeat is true', async () => {
    const user = userEvent.setup()
    render(<Harness focusOnEscape />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.click(editor)
    expect(editor).toHaveFocus()

    // 按住不放产生的 repeat 事件不应当关闭/blur
    fireEvent.keyDown(editor, { key: 'Escape', repeat: true })
    expect(editor).toHaveFocus()
  })

  it('does not blur composer when a child React component consumes Escape via stopPropagation or preventDefault', async () => {
    const user = userEvent.setup()
    const closeOverlay = vi.fn()
    const onChildEscape = vi.fn((e: React.KeyboardEvent) => {
      e.stopPropagation()
      e.preventDefault()
    })

    render(
      <Harness
        closeOverlay={closeOverlay}
        renderChild={() => (
          <button
            type="button"
            data-testid="child-control"
            onKeyDown={(e) => {
              if (e.key === 'Escape') {
                onChildEscape(e)
              }
            }}
          >
            control menu button
          </button>
        )}
      />,
    )
    const childControl = screen.getByTestId('child-control')
    await user.click(childControl)
    expect(childControl).toHaveFocus()

    // 在 child control 上按下 Escape
    fireEvent.keyDown(childControl, { key: 'Escape' })

    // 证明：child 的 React onKeyDown 先执行并消费了 Escape
    expect(onChildEscape).toHaveBeenCalled()
    // 证明：未冒泡到外层区域，closeOverlay 未被调用，没有 blur，焦点仍在 childControl 上
    expect(closeOverlay).not.toHaveBeenCalled()
    expect(childControl).toHaveFocus()
    expect(screen.getByTestId('is-focused')).toHaveTextContent('true')
  })

  it('does not process region Escape when disabled or active is false', () => {
    const closeOverlay = vi.fn()
    const { rerender } = render(<Harness disabled closeOverlay={closeOverlay} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    fireEvent.keyDown(editor, { key: 'Escape' })
    expect(closeOverlay).not.toHaveBeenCalled()

    rerender(<Harness active={false} closeOverlay={closeOverlay} />)
    fireEvent.keyDown(editor, { key: 'Escape' })
    expect(closeOverlay).not.toHaveBeenCalled()
  })

  it('does not execute onLeaveRegion or update state if unmounted before focusout microtask runs', async () => {
    const onLeaveRegion = vi.fn()
    const { unmount } = render(
      <Harness onLeaveRegion={onLeaveRegion} />
    )
    const editor = screen.getByLabelText('给 AI 发送消息')
    fireEvent.focusIn(editor)
    expect(screen.getByTestId('is-focused')).toHaveTextContent('true')

    // 触发 focusOut 并立即 unmount，微任务在 unmount 之后排队执行
    fireEvent.focusOut(editor)
    unmount()

    await Promise.resolve()
    expect(onLeaveRegion).not.toHaveBeenCalled()
  })
})
