import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { AgentSelectionModal, SelectionListModal } from '@/features/ai/SelectionListModal'

describe('SelectionListModal', () => {
  it('renders nothing when closed and empty list text when open', () => {
    const { container, rerender } = render(
      <SelectionListModal
        open={false}
        title="选择 Session"
        items={[]}
        sort="recent"
        onSortChange={() => undefined}
        onSelect={() => undefined}
        onClose={() => undefined}
      />,
    )
    expect(container).toBeEmptyDOMElement()
    rerender(
      <SelectionListModal
        open
        title="选择 Session"
        items={[]}
        sort="created"
        onSortChange={() => undefined}
        onSelect={() => undefined}
        onClose={() => undefined}
        emptyText="空列表"
      />,
    )
    expect(screen.getByText('空列表')).toBeInTheDocument()
  })

  it('supports sort toggles and selection', async () => {
    const user = userEvent.setup()
    const onSortChange = vi.fn()
    const onSelect = vi.fn()
    render(
      <SelectionListModal
        open
        title="选择 Session"
        items={[
          { id: 's1', title: 'Alpha', subtitle: 'main', badge: 'RUNNING' },
          { id: 's2', title: 'Beta' },
        ]}
        sort="recent"
        onSortChange={onSortChange}
        onSelect={onSelect}
        onClose={() => undefined}
      />,
    )
    expect(screen.getByText('Alpha')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '创建时间' }))
    expect(onSortChange).toHaveBeenCalledWith('created')
    await user.click(screen.getByRole('button', { name: /Alpha/ }))
    expect(onSelect).toHaveBeenCalledWith('s1')
  })

  it('lists agents for selection', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    render(
      <AgentSelectionModal
        open
        agents={[{ id: 'a1', name: 'assistant', description: 'desc' }]}
        onSelect={onSelect}
        onClose={() => undefined}
      />,
    )
    await user.click(screen.getByRole('button', { name: /assistant/ }))
    expect(onSelect).toHaveBeenCalledWith('a1')
  })
})
