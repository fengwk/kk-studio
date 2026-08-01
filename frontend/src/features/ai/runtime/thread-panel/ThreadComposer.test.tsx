import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ThreadComposer } from '@/features/ai/runtime/thread-panel/ThreadComposer'
import {
  filterThreadCommands,
  threadCommandsForScene,
  THREAD_COMMANDS,
} from '@/features/ai/runtime/thread-panel/thread-commands'
import { firstEnabledCommandIndex } from '@/features/ai/runtime/thread-panel/thread-command-navigation'

describe('ThreadComposer and commands', () => {
  it('keeps stable command order and grays unsupported blank-scene commands', () => {
    expect(THREAD_COMMANDS.map((c) => c.id)).toEqual([
      'session',
      'thread',
      'agent',
      'environment',
      'model',
      'variant',
      'yolo',
      'tree',
      'stop',
      'new',
    ])
    const blank = threadCommandsForScene('blank')
    expect(blank.map((c) => c.id)).toEqual(THREAD_COMMANDS.map((c) => c.id))
    // A blank pane has no Thread yet, so `/session` (which rebinds the current Thread) is
    // unavailable while `/thread` (pure pane selection) stays enabled.
    expect(blank.filter((c) => !c.disabled).map((c) => c.id)).toEqual([
      'thread',
      'agent',
      'environment',
    ])
    expect(blank.find((c) => c.id === 'session')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'new')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'model')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'tree')?.disabled).toBe(true)
    expect(threadCommandsForScene('bound').every((c) => !c.disabled)).toBe(true)
    expect(filterThreadCommands('yo').map((c) => c.id)).toEqual(['yolo'])
    expect(filterThreadCommands('sto').map((c) => c.id)).toEqual(['stop'])
    expect(filterThreadCommands('tree')[0]?.id).toBe('tree')
    expect(['history', 'branch', 'rebind', 'head'].every((keyword) => filterThreadCommands(keyword).some((command) => command.id === 'tree'))).toBe(true)
    expect(filterThreadCommands('', blank).map((c) => c.id)).toEqual(THREAD_COMMANDS.map((c) => c.id))
    expect(firstEnabledCommandIndex(blank)).toBe(1)
    expect(filterThreadCommands('missing')).toEqual([])
  })

  it('allows send and opens the command palette only from a slash draft', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const onCommand = vi.fn()
    const onDraftChange = vi.fn()
    const { rerender } = render(
      <ThreadComposer
        draft="hello"
        pending={false}
        disabled={false}
        onDraftChange={onDraftChange}
        onSubmit={onSubmit}
        onCommand={onCommand}
      />,
    )
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(onSubmit).toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: '打开命令表' })).not.toBeInTheDocument()

    rerender(
      <ThreadComposer
        draft="/stop"
        pending={false}
        disabled={false}
        onDraftChange={onDraftChange}
        onSubmit={onSubmit}
        onCommand={onCommand}
      />,
    )
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: /^stop/ }))
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'stop' }))

    onCommand.mockClear()
    rerender(
      <ThreadComposer
        draft="/tree"
        pending={false}
        disabled={false}
        onDraftChange={onDraftChange}
        onSubmit={onSubmit}
        onCommand={onCommand}
      />,
    )
    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('{Enter}')
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'tree' }))
  })

  it('blocks send when draft is blank or pending', () => {
    const { rerender } = render(
      <ThreadComposer
        draft="   "
        pending={false}
        disabled={false}
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
        onDraftChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()
  })
})
