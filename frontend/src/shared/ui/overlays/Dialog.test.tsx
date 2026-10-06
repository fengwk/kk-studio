import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { Select } from '@/shared/ui/controls/Select'
import { Dialog } from '@/shared/ui/overlays/Dialog'

/** 模拟“打开弹窗”的外部队列按钮：用于验证关闭后焦点归还。 */
function openerButton() {
  const opener = document.createElement('button')
  opener.textContent = '外部触发按钮'
  document.body.append(opener)
  return opener
}

describe('Dialog', () => {
  it('portals the card to body and names it from the visible title', () => {
    const { container } = render(
      <Dialog title="编辑项目配置" onClose={vi.fn()}>
        <div className="modal-body">body</div>
      </Dialog>,
    )

    const dialog = screen.getByRole('dialog')
    expect(container).not.toContainElement(dialog)
    expect(dialog.parentElement?.parentElement).toBe(document.body)
    expect(dialog).toHaveAccessibleName('编辑项目配置')
    expect(screen.getByRole('button', { name: '关闭' })).toBeInTheDocument()
  })

  it('prefers data-autofocus for the initial focus', () => {
    render(
      <Dialog title="确认删除" onClose={vi.fn()}>
        <div className="modal-body">内容</div>
        <div className="modal-footer">
          <button type="button" data-autofocus>
            取消
          </button>
          <button type="button">删除</button>
        </div>
      </Dialog>,
    )

    expect(screen.getByRole('button', { name: '取消' })).toHaveFocus()
  })

  it('returns focus to the opener even when inner content uses autoFocus', async () => {
    const user = userEvent.setup()
    const opener = openerButton()

    function Harness() {
      const [open, setOpen] = useState(false)
      return (
        <div>
          <button
            type="button"
            onClick={() => {
              opener.focus()
              setOpen(true)
            }}
          >
            打开弹窗
          </button>
          {open ? (
            <Dialog title="新建项目" onClose={() => setOpen(false)}>
              <input aria-label="项目名称" autoFocus />
            </Dialog>
          ) : null}
        </div>
      )
    }

    render(<Harness />)
    await user.click(screen.getByRole('button', { name: '打开弹窗' }))
    await waitFor(() => expect(screen.getByLabelText('项目名称')).toHaveFocus())

    await user.click(screen.getByRole('button', { name: '关闭' }))
    // autoFocus 已在 commit 时抢走焦点，opener 仍必须在挂载首次 render 时被记录。
    await waitFor(() => expect(opener).toHaveFocus())
  })

  it('returns focus to the nested opener when a sub dialog unmounts', async () => {
    const user = userEvent.setup()

    function Harness() {
      const [subOpen, setSubOpen] = useState(false)
      return (
        <Dialog className="resource-modal-card" title="编辑 Issue" onClose={vi.fn()}>
          <button type="button" onClick={() => setSubOpen(true)}>
            删除 Issue
          </button>
          {subOpen ? (
            <Dialog className="sub-card" title="删除 Issue 确认" onClose={() => setSubOpen(false)}>
              <button type="button" data-autofocus onClick={() => setSubOpen(false)}>
                取消
              </button>
            </Dialog>
          ) : null}
        </Dialog>
      )
    }

    render(<Harness />)
    const deleteButton = screen.getByRole('button', { name: '删除 Issue' })
    await user.click(deleteButton)
    expect(screen.getAllByRole('dialog')).toHaveLength(2)
    expect(screen.getByRole('button', { name: '取消' })).toHaveFocus()

    await user.click(screen.getByRole('button', { name: '取消' }))
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1))
    expect(deleteButton).toHaveFocus()
  })

  it('closes only the top dialog on Escape', async () => {
    const user = userEvent.setup()
    const outerClose = vi.fn()
    const innerClose = vi.fn()

    function Harness() {
      const [innerOpen, setInnerOpen] = useState(true)
      return (
        <>
          <Dialog title="外层" onClose={outerClose}>
            <button type="button">外层动作</button>
          </Dialog>
          {innerOpen ? (
            <Dialog
              className="sub-card"
              title="内层"
              onClose={() => {
                innerClose()
                setInnerOpen(false)
              }}
            >
              <button type="button">内层动作</button>
            </Dialog>
          ) : null}
        </>
      )
    }

    render(<Harness />)
    await user.keyboard('{Escape}')
    expect(innerClose).toHaveBeenCalledOnce()
    expect(outerClose).not.toHaveBeenCalled()

    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1))
    await user.keyboard('{Escape}')
    expect(outerClose).toHaveBeenCalledOnce()
  })

  it('keeps Escape deferring to a real Select listbox before closing the dialog', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()

    render(
      <Dialog title="新建项目" onClose={onClose}>
        <Select
          aria-label="模型"
          value="a"
          options={[
            { value: 'a', label: 'Alpha' },
            { value: 'b', label: 'Bravo' },
          ]}
          onChange={() => undefined}
        />
      </Dialog>,
    )

    await user.click(screen.getByLabelText('模型'))
    expect(screen.getByRole('listbox')).toBeInTheDocument()

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()

    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('ignores Escape while pending and does not close on card mousedown', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()

    const { rerender } = render(
      <Dialog title="删除项目" pending onClose={onClose}>
        <div className="modal-body">内容</div>
      </Dialog>,
    )

    await user.keyboard('{Escape}')
    await user.click(document.querySelector('.modal-backdrop') as HTMLElement)
    await user.click(screen.getByRole('button', { name: '关闭' }))
    expect(onClose).not.toHaveBeenCalled()

    rerender(
      <Dialog title="删除项目" onClose={onClose}>
        <div className="modal-body">内容</div>
      </Dialog>,
    )
    await user.click(screen.getByText('内容'))
    expect(onClose).not.toHaveBeenCalled()

    await user.click(document.querySelector('.modal-backdrop') as HTMLElement)
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('ignores composing, keyCode 229 and defaultPrevented Escape', async () => {
    const onClose = vi.fn()
    render(
      <Dialog title="删除项目" onClose={onClose}>
        <div className="modal-body">内容</div>
      </Dialog>,
    )

    window.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, isComposing: true }),
    )
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, keyCode: 229 }))
    const preventer = (event: KeyboardEvent) => event.preventDefault()
    window.addEventListener('keydown', preventer, true)
    // preventDefault 需要可取消事件；真实浏览器与 userEvent 的按键均为 cancelable。
    window.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    )
    window.removeEventListener('keydown', preventer, true)

    expect(onClose).not.toHaveBeenCalled()
  })

  it('cycles Tab inside the card and skips hidden or inert controls', async () => {
    const user = userEvent.setup()
    render(
      <Dialog title="编辑项目" onClose={vi.fn()}>
        <button type="button" hidden>
          隐藏动作
        </button>
        <button type="button">第一个</button>
        <div inert>
          <button type="button">惰性动作</button>
        </div>
        <button type="button">最后一个</button>
      </Dialog>,
    )

    const closeButton = screen.getByRole('button', { name: '关闭' })
    const last = screen.getByRole('button', { name: '最后一个' })
    await waitFor(() => expect(closeButton).toHaveFocus())

    last.focus()
    await user.tab()
    expect(closeButton).toHaveFocus()

    await user.tab({ shift: true })
    expect(last).toHaveFocus()
    // hidden/inert 控件不进入可聚焦集合（无障碍树会直接隐藏它们，故用 DOM 查询断言）。
    expect(document.querySelector('button[hidden]')).not.toHaveFocus()
    expect(document.querySelector('[inert] button')).not.toHaveFocus()
  })
})
