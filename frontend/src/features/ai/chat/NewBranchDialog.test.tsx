import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { NewBranchDialog } from '@/features/ai/chat/NewBranchDialog'
import { setLocale } from '@/shared/i18n'

const DESTINATIONS = [
  { value: 'pane-1', label: '1' },
  { value: 'pane-2', label: '2 · thread-one' },
  { value: 'pane-9', label: '9' },
]

function renderDialog(overrides: Partial<React.ComponentProps<typeof NewBranchDialog>> = {}) {
  const onConfirm = vi.fn()
  const onClose = vi.fn()
  const view = render(
    <NewBranchDialog
      destinations={DESTINATIONS}
      defaultDestination="pane-9"
      onConfirm={onConfirm}
      onClose={onClose}
      {...overrides}
    />,
  )
  return { ...view, onConfirm, onClose }
}

const nameInput = () => document.querySelector<HTMLInputElement>('.new-branch-name')!
const submit = () => document.querySelector<HTMLButtonElement>('.modal-footer button[type="submit"]')!
const value = () => document.querySelector('.new-branch-dialog .ui-select-value')?.textContent ?? ''

describe('NewBranchDialog', () => {
  setLocale('zh-CN')

  it('uses the shared dialog body/footer and form controls', () => {
    renderDialog()
    // 标题 / 正文 / 操作区使用共享 Dialog 布局，输入与按钮不再贴边。
    const form = document.querySelector('.new-branch-dialog .modal-card-form')
    expect(form).not.toBeNull()
    expect(form!.querySelector('.modal-body')).not.toBeNull()
    expect(form!.querySelector('.modal-footer')).not.toBeNull()
    // 名称/位置使用共享控件与共享标签，提交/取消使用共享 Button 变体。
    expect(nameInput().classList.contains('ui-text-input')).toBe(true)
    expect(form!.querySelector('.field-label')).not.toBeNull()
    expect(submit().classList.contains('btn-primary')).toBe(true)
    expect(document.querySelector('.modal-footer .ghost-btn')).not.toBeNull()
  })

  it('submits the normalized name with the default destination pane', async () => {
    const user = userEvent.setup()
    const { onConfirm } = renderDialog()
    // 缺省目标位置来自触发分支的 pane，并带上它当前的分支名。
    expect(value()).toBe('9')
    await user.type(nameInput(), '  branch-1  ')
    await user.click(submit())
    expect(onConfirm).toHaveBeenCalledWith({ name: 'branch-1', paneId: 'pane-9' })
  })

  it('rejects a blank name without submitting', async () => {
    const user = userEvent.setup()
    const { onConfirm } = renderDialog({ defaultDestination: 'pane-1' })
    await user.click(submit())
    expect(onConfirm).not.toHaveBeenCalled()
    expect(nameInput()).toHaveAttribute('aria-invalid', 'true')
    expect(document.querySelector('.field-error')?.textContent?.length).toBeGreaterThan(0)

    await user.type(nameInput(), 'a'.repeat(257))
    await user.click(submit())
    expect(onConfirm).not.toHaveBeenCalled()
    expect(nameInput()).toHaveAttribute('aria-invalid', 'true')
  })

  it('lets the user pick any destination position 1..9', async () => {
    const user = userEvent.setup()
    const { onConfirm } = renderDialog()
    await user.click(document.querySelector<HTMLButtonElement>('.new-branch-dialog .ui-select-trigger')!)
    const options = [...document.querySelectorAll<HTMLButtonElement>('.ui-select-option')]
    expect(options.map((option) => option.textContent)).toEqual(['1', '2 · thread-one', '9'])
    await user.click(options[1]!)
    expect(value()).toBe('2 · thread-one')
    await user.type(nameInput(), 'branch-1')
    await user.click(submit())
    expect(onConfirm).toHaveBeenCalledWith({ name: 'branch-1', paneId: 'pane-2' })
  })

  it('surfaces the busy error and overwrite confirmation handed down by the workspace', async () => {
    const user = userEvent.setup()
    const { rerender, onConfirm } = renderDialog({
      formError: 'destination busy',
      overwriteWarning: 'overwrite draft?',
      pending: true,
    })
    expect(document.querySelector('.form-error-banner')?.textContent).toBe('destination busy')
    expect(document.querySelector('.new-branch-overwrite')?.textContent).toBe('overwrite draft?')
    // 在途（目标 pane 有未完成操作）时提交与取消都被禁用。
    expect(submit()).toBeDisabled()
    expect(document.querySelector<HTMLButtonElement>('.modal-footer .ghost-btn')).toBeDisabled()

    // 覆盖确认阶段：目标位置仍然合法则保留用户/调用方的选择，提交走同一回调。
    rerender(
      <NewBranchDialog
        destinations={DESTINATIONS}
        defaultDestination="pane-1"
        formError={null}
        overwriteWarning="overwrite draft?"
        onConfirm={onConfirm}
        onClose={vi.fn()}
      />,
    )
    await user.type(nameInput(), 'branch-1')
    expect(value()).toBe('9')
    await user.click(submit())
    expect(onConfirm).toHaveBeenCalledWith({ name: 'branch-1', paneId: 'pane-9' })

    // 目标位置从候选中消失时收敛到缺省位置，绝不保留不可达位置。
    rerender(
      <NewBranchDialog
        destinations={[{ value: 'pane-1', label: '1' }]}
        defaultDestination="pane-1"
        formError={null}
        overwriteWarning={null}
        onConfirm={onConfirm}
        onClose={vi.fn()}
      />,
    )
    expect(value()).toBe('1')
  })
})
