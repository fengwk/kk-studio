import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Menu } from './Menu'

describe('Menu', () => {
  it('supports keyboard navigation and closes on Escape returning focus', async () => {
    const user = userEvent.setup()
    const trigger = document.createElement('button')
    document.body.append(trigger)
    trigger.focus()
    const onClose = vi.fn()
    render(<Menu returnFocusTo={trigger} onClose={onClose} items={[{ label: 'Alpha', onSelect: vi.fn() }, { label: 'Beta', onSelect: vi.fn() }]} />)
    await waitFor(() => expect(screen.getByRole('menuitem', { name: 'Alpha' })).toHaveFocus())
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('menuitem', { name: 'Beta' })).toHaveFocus()
    await user.keyboard('{Home}')
    expect(screen.getByRole('menuitem', { name: 'Alpha' })).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledOnce()
    expect(trigger).toHaveFocus()
    trigger.remove()
  })

  it('supports End, letter shortcuts and Tab close', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<Menu onClose={onClose} items={[{ label: 'Alpha', onSelect: vi.fn() }, { label: 'Beta', onSelect: vi.fn() }]} />)
    await waitFor(() => expect(screen.getByRole('menuitem', { name: 'Alpha' })).toHaveFocus())
    await user.keyboard('{End}')
    expect(screen.getByRole('menuitem', { name: 'Beta' })).toHaveFocus()
    await user.keyboard('a')
    expect(screen.getByRole('menuitem', { name: 'Alpha' })).toHaveFocus()
    await user.keyboard('{Tab}')
    expect(onClose).toHaveBeenCalledOnce()
  })
})
