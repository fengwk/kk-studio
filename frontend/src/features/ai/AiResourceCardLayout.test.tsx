import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ResourceCardLayout } from '@/features/ai/AiResourceCardLayout'

describe('ResourceCardLayout', () => {
  it.each(['agent', 'model', 'provider'] as const)('renders %s resources and invokes all available actions', async (icon) => {
    const user = userEvent.setup()
    const onStart = vi.fn()
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    render(<ResourceCardLayout icon={icon} title="resource" subtitle="detail" rows={[["Kind", icon]]} onStart={onStart} onEdit={onEdit} onDelete={onDelete} deletePending={false} />)
    await user.click(screen.getByRole('button', { name: '创建会话 resource' }))
    await user.click(screen.getByRole('button', { name: '编辑 resource' }))
    await user.click(screen.getByRole('button', { name: '删除 resource' }))
    expect([onStart, onEdit, onDelete].every((action) => action.mock.calls.length === 1)).toBe(true)
  })

  it('omits unavailable start and disables a pending delete', () => {
    render(<ResourceCardLayout icon="agent" title="resource" subtitle="detail" rows={[]} onEdit={vi.fn()} onDelete={vi.fn()} deletePending />)
    expect(screen.queryByRole('button', { name: '创建会话 resource' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '删除 resource' })).toBeDisabled()
  })
})
