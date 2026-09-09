import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ResourceCardLayout } from '@/features/ai/catalog/AiResourceCardLayout'

describe('ResourceCardLayout', () => {
  it.each(['agent', 'model', 'provider', 'server'] as const)('renders %s resources and invokes edit/delete actions', async (icon) => {
    const user = userEvent.setup()
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    render(<ResourceCardLayout icon={icon} title="resource" subtitle="detail" rows={[["Kind", icon]]} onEdit={onEdit} onDelete={onDelete} deletePending={false} />)
    expect(screen.queryByRole('button', { name: '创建会话 resource' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '编辑 resource' }))
    await user.click(screen.getByRole('button', { name: '删除 resource' }))
    expect([onEdit, onDelete].every((action) => action.mock.calls.length === 1)).toBe(true)
  })

  it('keeps optional start action and disables a pending delete', async () => {
    const user = userEvent.setup()
    const onStart = vi.fn()
    render(
      <ResourceCardLayout
        icon="agent"
        title="resource"
        subtitle="detail"
        rows={[]}
        onStart={onStart}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        deletePending
      />,
    )
    await user.click(screen.getByRole('button', { name: '创建会话 resource' }))
    expect(onStart).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: '删除 resource' })).toBeDisabled()
  })
})
