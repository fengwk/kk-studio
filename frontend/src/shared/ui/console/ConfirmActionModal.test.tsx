import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ConfirmModalState } from '@/shared/ui/console/confirm-modal'
import { ConfirmActionModal } from '@/shared/ui/console/ConfirmActionModal'

// 组装删除型确认弹窗（危险色 + 自定义确认文案），使各测试聚焦自身关注点。
function renderDeleteModal(modal: Partial<ConfirmModalState> = {}) {
  const user = userEvent.setup()
  const onClose = vi.fn()
  const onConfirm = vi.fn()
  const { unmount, rerender } = render(
    <ConfirmActionModal
      modal={{
        title: '确认删除？',
        description: '删除后不可恢复。',
        confirmLabel: '删除',
        tone: 'danger',
        onConfirm,
        ...modal,
      }}
      pending={false}
      onClose={onClose}
    />,
  )
  return { user, onClose, onConfirm, unmount, rerender }
}

// 把元素挂到 body 上并聚焦，作为"弹窗打开前的活动元素"。
function appendBodyButton() {
  const trigger = document.createElement('button')
  trigger.textContent = '打开'
  document.body.append(trigger)
  trigger.focus()
  return trigger
}

describe('ConfirmActionModal', () => {
  it('renders nothing when no modal state is provided', () => {
    render(<ConfirmActionModal modal={null} pending={false} onClose={vi.fn()} />)

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('closes through cancel, header close and confirm buttons', async () => {
    const { user, onClose, onConfirm } = renderDeleteModal()

    // 取消与 header 关闭都走 onClose，不触发确认动作。
    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(onClose).toHaveBeenCalledOnce()
    expect(onConfirm).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '关闭' }))
    expect(onClose).toHaveBeenCalledTimes(2)

    // 确认按钮只触发 onConfirm，不触发关闭回调。
    await user.click(screen.getByRole('button', { name: '删除' }))
    expect(onConfirm).toHaveBeenCalledOnce()
    expect(onClose).toHaveBeenCalledTimes(2)
  })

  it('closes on Escape with focus returned to the previously focused element', async () => {
    const trigger = appendBodyButton()
    const { user, onClose, onConfirm, rerender } = renderDeleteModal()

    // 弹窗打开后取消按钮获得初始焦点。
    await waitFor(() => expect(screen.getByRole('button', { name: '取消' })).toHaveFocus())

    // Escape 关闭弹窗，且只走 onClose。
    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledOnce()
    expect(onConfirm).not.toHaveBeenCalled()

    // 父组件收到 onClose 后置空 modal，弹窗卸载并把焦点归还给打开前的元素。
    rerender(<ConfirmActionModal modal={null} pending={false} onClose={onClose} />)
    expect(trigger).toHaveFocus()
  })

  it('ignores Escape that was defaultPrevented, composing, or a 229 keyCode IME event', async () => {
    // 捕获阶段监听先于组件注册；defaultPrevented 后组件必须放行而不关闭。
    const preventer = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !event.defaultPrevented) {
        event.preventDefault()
      }
    }
    window.addEventListener('keydown', preventer, true)
    const { user, onClose } = renderDeleteModal()

    await user.keyboard('{Escape}')
    window.removeEventListener('keydown', preventer, true)
    expect(onClose).not.toHaveBeenCalled()

    // 中文输入法组合中按下的 Escape 属于合成事件，不应触发关闭。
    window.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, isComposing: true }),
    )
    expect(onClose).not.toHaveBeenCalled()

    // 浏览器将 IME 候选键上报为 keyCode 229 时，同样应忽略该 Escape。
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, keyCode: 229 }))
    expect(onClose).not.toHaveBeenCalled()
  })

  it('does not close on Escape while pending', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(
      <ConfirmActionModal
        modal={{
          title: '确认删除？',
          description: '删除后不可恢复。',
          confirmLabel: '删除',
          onConfirm: vi.fn(),
        }}
        pending
        onClose={onClose}
      />,
    )

    await user.keyboard('{Escape}')
    expect(onClose).not.toHaveBeenCalled()
  })

  it('ignores backdrop mousedown and header close while pending', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(
      <ConfirmActionModal
        modal={{
          title: '确认删除？',
          description: '删除后不可恢复。',
          confirmLabel: '删除',
          onConfirm: vi.fn(),
        }}
        pending
        onClose={onClose}
      />,
    )
    const dialog = screen.getByRole('alertdialog')

    // 点击卡片外的遮罩区域：pending 时 effectiveClose 为空操作，不关闭。
    await user.click(document.querySelector('.modal-backdrop') as HTMLElement)
    expect(onClose).not.toHaveBeenCalled()

    // pending 期间 header 关闭按钮被禁用，点击不会产生回调。
    await user.click(within(dialog).getByRole('button', { name: '关闭' }))
    expect(onClose).not.toHaveBeenCalled()
  })

  it('restores focus to the previous element after the modal unmounts', async () => {
    const trigger = appendBodyButton()
    const { unmount } = renderDeleteModal()
    await waitFor(() => expect(screen.getByRole('button', { name: '取消' })).toHaveFocus())

    // 卸载弹窗（等同关闭），焦点归还给打开前的元素。
    unmount()
    expect(trigger).toHaveFocus()
  })

  it('shows the trash icon by default and the refresh icon when configured', () => {
    const { unmount } = renderDeleteModal()
    const icon = screen.getByRole('alertdialog').querySelector('.confirm-modal-icon svg')
    expect(icon).toHaveClass('lucide-trash2')
    expect(icon).not.toHaveClass('lucide-refresh-cw')

    // 卸载默认弹窗后再渲染 refresh 配置，验证图标分支切换。
    unmount()
    renderDeleteModal({ icon: 'refresh' })
    const refreshIcon = screen.getByRole('alertdialog').querySelector('.confirm-modal-icon svg')
    expect(refreshIcon).toHaveClass('lucide-refresh-cw')
    expect(refreshIcon).not.toHaveClass('lucide-trash2')
  })
})
