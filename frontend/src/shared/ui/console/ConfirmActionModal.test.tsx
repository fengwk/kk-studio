import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConfirmActionModal } from '@/shared/ui/console/ConfirmActionModal'

describe('ConfirmActionModal', () => {
  it('uses an icon-only close control and focuses the safe action', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    const onConfirm = vi.fn()
    render(
      <ConfirmActionModal
        modal={{
          title: '丢弃未发送的修改？',
          description: '切换到所选历史位置后会丢弃输入框中未发送的消息，是否继续？',
          confirmLabel: '丢弃修改',
          tone: 'danger',
          onConfirm,
        }}
        pending={false}
        onClose={onClose}
      />,
    )

    const dialog = screen.getByRole('alertdialog', { name: '丢弃未发送的修改？' })
    const close = within(dialog).getByRole('button', { name: '关闭' })
    expect(close).toHaveClass('modal-close-button')
    expect(close).not.toHaveTextContent('关闭')
    expect(close.querySelector('svg')).toBeInTheDocument()
    await waitFor(() => expect(within(dialog).getByRole('button', { name: '取消' })).toHaveFocus())

    await user.click(close)
    expect(onClose).toHaveBeenCalledOnce()
    expect(onConfirm).not.toHaveBeenCalled()
  })

  it('disables every dismissal and confirmation control while pending', () => {
    render(
      <ConfirmActionModal
        modal={{
          title: '确认操作？',
          description: '操作正在提交。',
          confirmLabel: '确认',
          tone: 'danger',
          onConfirm: vi.fn(),
        }}
        pending
        onClose={vi.fn()}
      />,
    )

    const dialog = screen.getByRole('alertdialog', { name: '确认操作？' })
    expect(within(dialog).getByRole('button', { name: '关闭' })).toBeDisabled()
    expect(within(dialog).getByRole('button', { name: '取消' })).toBeDisabled()
    expect(within(dialog).getByRole('button', { name: '确认' })).toBeDisabled()
  })
})
