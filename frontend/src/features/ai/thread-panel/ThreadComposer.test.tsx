import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ThreadComposer } from '@/features/ai/thread-panel/ThreadComposer'
import { filterThreadCommands, THREAD_COMMANDS } from '@/features/ai/thread-panel/thread-commands'

describe('ThreadComposer and commands', () => {
  it('filters slash commands to the exact remaining command ids', () => {
    expect(THREAD_COMMANDS.map((c) => c.id)).toEqual(['yolo', 'clear-draft'])
    expect(filterThreadCommands('yo').map((c) => c.id)).toEqual(['yolo'])
    expect(filterThreadCommands('').map((c) => c.id)).toEqual(['yolo', 'clear-draft'])
    expect(filterThreadCommands('missing')).toEqual([])
  })

  it('allows send while working is irrelevant and opens command palette', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const onCommand = vi.fn()
    const onDraftChange = vi.fn()
    render(
      <ThreadComposer
        draft="hello"
        pending={false}
        disabled={false}
        controlsPending={false}
        onDraftChange={onDraftChange}
        onSubmit={onSubmit}
        onCommand={onCommand}
      />,
    )
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(onSubmit).toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '打开命令表' }))
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.click(screen.getByText('yolo'))
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'yolo' }))
  })

  it('blocks send when draft is blank or pending', () => {
    const { rerender } = render(
      <ThreadComposer
        draft="   "
        pending={false}
        disabled={false}
        controlsPending={false}
        onDraftChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()
    rerender(
      <ThreadComposer
        draft="/yolo"
        pending={false}
        disabled={false}
        controlsPending={false}
        onDraftChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()
  })
})
