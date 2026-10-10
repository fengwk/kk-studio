import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Trash2 } from 'lucide-react'
import { describe, expect, it, vi } from 'vitest'
import { Button } from '@/shared/ui/controls/Button'
import { IconButton } from '@/shared/ui/controls/IconButton'

describe('Button', () => {
  it('exposes normal/compact sizes, danger variant and disabled semantics', async () => {
    const user = userEvent.setup()
    const onNormal = vi.fn()
    const onCompact = vi.fn()
    const onDanger = vi.fn()
    render(
      <>
        <Button onClick={onNormal}>普通</Button>
        <Button size="compact" onClick={onCompact}>
          紧凑
        </Button>
        <Button variant="ghost" danger onClick={onDanger}>
          删除
        </Button>
        <Button disabled>禁用</Button>
      </>,
    )

    expect(screen.getByRole('button', { name: '普通' })).not.toHaveClass('is-compact')
    expect(screen.getByRole('button', { name: '紧凑' })).toHaveClass('is-compact')
    expect(screen.getByRole('button', { name: '删除' })).toHaveClass('ghost-btn', 'danger')

    await user.click(screen.getByRole('button', { name: '普通' }))
    await user.click(screen.getByRole('button', { name: '紧凑' }))
    await user.click(screen.getByRole('button', { name: '删除' }))
    expect([onNormal, onCompact, onDanger].map((action) => action.mock.calls.length)).toEqual([1, 1, 1])

    const disabled = screen.getByRole('button', { name: '禁用' })
    expect(disabled).toBeDisabled()
    expect(disabled).toHaveAttribute('type', 'button')
  })

  it('handles loading state with spinner, aria-busy, prevents click triggers, and retains original label', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const { rerender } = render(
      <Button loading onClick={onSubmit}>
        保存更改
      </Button>,
    )

    const button = screen.getByRole('button', { name: '保存更改' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('aria-busy', 'true')
    expect(button).toHaveClass('is-loading')
    expect(button.querySelector('.ui-loading-spinner')).toBeInTheDocument()

    // 验证 loading 时无法触发点击
    await user.click(button)
    expect(onSubmit).not.toHaveBeenCalled()

    // 验证 loading 结束后恢复可点击且去除 aria-busy 与 spinner
    rerender(<Button onClick={onSubmit}>保存更改</Button>)
    expect(button).toBeEnabled()
    expect(button).not.toHaveAttribute('aria-busy')
    expect(button).not.toHaveClass('is-loading')
    expect(button.querySelector('.ui-loading-spinner')).toBeNull()

    await user.click(button)
    expect(onSubmit).toHaveBeenCalledOnce()
  })

  it('does not infer loading from disabled prop alone', () => {
    render(<Button disabled>仅无权限禁用</Button>)
    const button = screen.getByRole('button', { name: '仅无权限禁用' })
    expect(button).toBeDisabled()
    expect(button).not.toHaveAttribute('aria-busy')
    expect(button).not.toHaveClass('is-loading')
    expect(button.querySelector('.ui-loading-spinner')).toBeNull()
  })
})

describe('IconButton', () => {
  it('requires an accessible name and supports compact, danger and disabled states', async () => {
    const user = userEvent.setup()
    const onDelete = vi.fn()
    render(
      <>
        <IconButton label="删除资源" danger size="compact" onClick={onDelete}>
          <Trash2 aria-hidden="true" />
        </IconButton>
        <IconButton label="关闭" disabled>
          <span aria-hidden="true">x</span>
        </IconButton>
      </>,
    )

    const deleteButton = screen.getByRole('button', { name: '删除资源' })
    expect(deleteButton).toHaveClass('icon-button', 'danger', 'is-compact')
    await user.click(deleteButton)
    expect(onDelete).toHaveBeenCalledOnce()

    expect(screen.getByRole('button', { name: '关闭' })).toBeDisabled()
  })
})
