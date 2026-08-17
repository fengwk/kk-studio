import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useRef } from 'react'
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
}: {
  disabled?: boolean
  active?: boolean
  focusOnEscape?: boolean
  pending?: boolean
  closeOverlay?: () => void
}) {
  const editorRef = useRef<HTMLDivElement>(null)
  const { focusComposer } = useComposerFocus({
    editorRef,
    disabled,
    active,
    focusOnEscape,
    pending,
    closeOverlay,
  })
  return (
    <>
      <button type="button">outside</button>
      <div hidden={!active}>
        <div
          ref={editorRef}
          contentEditable={!disabled}
          aria-label="给 AI 发送消息"
        />
      </div>
      <button type="button" onClick={() => focusComposer(true)}>focus composer</button>
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

  it('restores focus through global Escape and closes the active overlay', async () => {
    const user = userEvent.setup()
    const closeOverlay = vi.fn()
    render(<Harness focusOnEscape closeOverlay={closeOverlay} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    const outside = screen.getByRole('button', { name: 'outside' })
    await user.click(outside)
    expect(outside).toHaveFocus()

    fireEvent.keyDown(window, { key: 'Escape' })

    expect(closeOverlay).toHaveBeenCalled()
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
})
