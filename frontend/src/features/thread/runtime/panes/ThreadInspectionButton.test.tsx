import { type ContextType } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ThreadInspectionButton } from './ThreadInspectionButton'
import { ThreadNavigationContext } from './thread-navigation-context'

const CHILD = 'child-thread'

function renderButton(inspect: NonNullable<ContextType<typeof ThreadNavigationContext>>, afterEntryId?: string) {
  render(
    <ThreadNavigationContext.Provider value={inspect}>
      <ThreadInspectionButton threadId={CHILD} afterEntryId={afterEntryId}>Inspect</ThreadInspectionButton>
    </ThreadNavigationContext.Provider>,
  )
}

describe('ThreadInspectionButton', () => {
  it('offers no action without pane navigation', () => {
    render(<ThreadInspectionButton threadId={CHILD}>Inspect</ThreadInspectionButton>)
    expect(screen.queryByText('Inspect')).not.toBeInTheDocument()
  })

  it('opens a child in the current pane with no standalone address', async () => {
    const inspect = vi.fn()
    renderButton(inspect)

    const button = screen.getByRole('button', { name: 'Inspect' })
    expect(button).toHaveAttribute('type', 'button')
    expect(button).not.toHaveAttribute('href')
    await userEvent.setup().click(button)
    expect(inspect).toHaveBeenCalledExactlyOnceWith(CHILD)
  })

  it.each(['{Enter}', ' '])('keeps the compaction cursor on keyboard activation (%j)', async (key) => {
    const inspect = vi.fn()
    const user = userEvent.setup()
    renderButton(inspect, 'cut-1')

    await user.tab()
    expect(screen.getByRole('button', { name: 'Inspect' })).toHaveFocus()
    await user.keyboard(key)
    expect(inspect).toHaveBeenCalledExactlyOnceWith(CHILD, { afterEntryId: 'cut-1' })
  })
})
