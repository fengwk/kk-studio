import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { setLocale } from '@/shared/i18n'

const conflict = { reason: 'STALE_COMMAND_CURSOR', detail: 'expected=0 actual=1' }

beforeEach(() => {
  setLocale('zh-CN')
})

describe('ConflictPresenter', () => {
  it('exposes the localized title as the alertdialog accessible name', () => {
    render(
      <ConflictPresenter conflict={conflict} onRefresh={vi.fn()} onClose={vi.fn()} />,
    )

    expect(screen.getByRole('alertdialog', { name: '数据已发生变化' }))
      .toHaveTextContent('STALE_COMMAND_CURSOR')
  })

  it('keeps the display text and button behaviors', async () => {
    const user = userEvent.setup()
    const onRefresh = vi.fn()
    const onRetry = vi.fn()
    const onClose = vi.fn()
    render(
      <ConflictPresenter
        conflict={conflict}
        onRefresh={onRefresh}
        onRetry={onRetry}
        onClose={onClose}
      />,
    )

    const dialog = screen.getByRole('alertdialog')
    expect(dialog).toHaveTextContent('数据已发生变化')
    expect(dialog).toHaveTextContent('expected=0 actual=1')
    await user.click(screen.getByRole('button', { name: '刷新' }))
    expect(onRefresh).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: '重试' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('renders nothing without a conflict', () => {
    const { container } = render(
      <ConflictPresenter conflict={null} onRefresh={vi.fn()} onClose={vi.fn()} />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('uses the shared modal layer with Escape close and focus restoration', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    const opener = document.createElement('button')
    opener.textContent = '外部触发按钮'
    document.body.append(opener)
    opener.focus()

    const { unmount } = render(
      <ConflictPresenter conflict={conflict} onRefresh={vi.fn()} onClose={onClose} />,
    )

    const dialog = screen.getByRole('alertdialog', { name: '数据已发生变化' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    // portal 到 body 的共享弹层，而不是原地的裸 alertdialog。
    expect(dialog.closest('.modal-backdrop')?.parentElement).toBe(document.body)

    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledOnce()

    unmount()
    expect(opener).toHaveFocus()
    opener.remove()
  })
})
