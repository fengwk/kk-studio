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
