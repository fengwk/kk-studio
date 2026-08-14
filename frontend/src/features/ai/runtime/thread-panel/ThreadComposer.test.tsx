import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ThreadComposer } from '@/features/ai/runtime/thread-panel/ThreadComposer'
import {
  createAttachmentPart,
  createTextPart,
  slashQueryOf,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import {
  filterThreadCommands,
  threadCommandsForActiveView,
  threadCommandsForScene,
  THREAD_COMMANDS,
} from '@/features/ai/runtime/thread-panel/thread-commands'
import { firstEnabledCommandIndex } from '@/features/ai/runtime/thread-panel/thread-command-navigation'

function ControlledComposer() {
  const [parts, setParts] = useState<ComposerPart[]>([])
  return (
    <ThreadComposer
      parts={parts}
      pending={false}
      disabled={false}
      onPartsChange={setParts}
      onSubmit={vi.fn()}
      onCommand={vi.fn()}
    />
  )
}

describe('ThreadComposer and commands', () => {
  it('keeps stable command order and grays unsupported blank-scene commands', () => {
    expect(THREAD_COMMANDS.map((c) => c.id)).toEqual([
      'thread',
      'agent',
      'environment',
      'yolo',
      'tree',
      'stop',
      'new',
      'upload',
      'events',
      'conversation',
      'shortcuts',
    ])
    // `/session`（全局 Session 重绑定）已彻底移除，不再出现在稳定命令表中。
    expect(THREAD_COMMANDS.some((c) => c.id === 'session')).toBe(false)
    const blank = threadCommandsForScene('chat-blank')
    expect(blank.map((c) => c.id)).toEqual(THREAD_COMMANDS.map((c) => c.id))
    // 空面板还没有 Thread，因此 `/tree`/`/stop`/`/new`/`/events`/`/conversation`
    // 不可用，而 `/thread`（仅切换面板）与 `/shortcuts` 保持可用。
    expect(blank.filter((c) => !c.disabled).map((c) => c.id)).toEqual([
      'thread',
      'agent',
      'environment',
      'yolo',
      'upload',
      'shortcuts',
    ])
    expect(blank.find((c) => c.id === 'new')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'tree')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'events')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'conversation')?.disabled).toBe(true)
    expect(threadCommandsForScene('chat-bound').every((c) => !c.disabled)).toBe(true)
    expect(filterThreadCommands('yo').map((c) => c.id)).toEqual(['yolo'])
    expect(filterThreadCommands('sto').map((c) => c.id)).toEqual(['stop'])
    expect(filterThreadCommands('tree')[0]?.id).toBe('tree')
    expect(filterThreadCommands('events')[0]?.id).toBe('events')
    expect(filterThreadCommands('shortcuts')[0]?.id).toBe('shortcuts')
    expect(['history', 'branch', 'rebind', 'head'].every((keyword) => filterThreadCommands(keyword).some((command) => command.id === 'tree'))).toBe(true)
    expect(filterThreadCommands('', blank).map((c) => c.id)).toEqual(THREAD_COMMANDS.map((c) => c.id))
    expect(firstEnabledCommandIndex(blank)).toBe(0)
    expect(filterThreadCommands('missing')).toEqual([])
  })

  it('projects canvas-bound to only the capabilities the controller supports', () => {
    // Canvas Bound 的 controller 只支持 stop；upload 由 ThreadComposer 处理文件选择。
    // agent/environment/yolo/tree/new/thread 绝不投影为可用，避免传给只支持 stop 的 controller。
    const canvasBound = threadCommandsForScene('canvas-bound')
    expect(canvasBound.filter((c) => !c.disabled).map((c) => c.id)).toEqual([
      'stop',
      'upload',
      'events',
      'conversation',
      'shortcuts',
    ])
    for (const id of ['thread', 'agent', 'environment', 'yolo', 'tree', 'new']) {
      expect(canvasBound.find((c) => c.id === id)?.disabled).toBe(true)
    }
    // canvas-blank 仍支持 agent/environment/yolo/upload/shortcuts，但没有 /thread。
    const canvasBlank = threadCommandsForScene('canvas-blank')
    expect(canvasBlank.filter((c) => !c.disabled).map((c) => c.id)).toEqual([
      'agent',
      'environment',
      'yolo',
      'upload',
      'shortcuts',
    ])
  })

  it('disables the already-active main-view command while keeping it visible', () => {
    const bound = threadCommandsForScene('chat-bound')
    const withEvents = threadCommandsForActiveView(bound, 'events')
    expect(withEvents.find((c) => c.id === 'events')?.disabled).toBe(true)
    expect(withEvents.find((c) => c.id === 'events')?.disabledReasonKey).toBe('ai.runtime.command.activeView')
    expect(withEvents.find((c) => c.id === 'conversation')?.disabled).toBe(false)
    expect(withEvents.map((c) => c.id)).toEqual(THREAD_COMMANDS.map((c) => c.id))
    const withConversation = threadCommandsForActiveView(bound, 'conversation')
    expect(withConversation.find((c) => c.id === 'conversation')?.disabled).toBe(true)
    expect(withConversation.find((c) => c.id === 'events')?.disabled).toBe(false)
  })

  it('uses slash as a text-only shortcut without consuming attachments', () => {
    expect(slashQueryOf([createTextPart('/stop')])).toBe('stop')
    expect(
      slashQueryOf([
        createTextPart('/stop'),
        createAttachmentPart('upload-1', 'keep.txt'),
      ]),
    ).toBeNull()
  })

  it('allows send and executes slash commands', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const onCommand = vi.fn()
    const onPartsChange = vi.fn()
    const { rerender } = render(
      <ThreadComposer
        parts={[createTextPart('hello')]}
        pending={false}
        disabled={false}
        onPartsChange={onPartsChange}
        onSubmit={onSubmit}
        onCommand={onCommand}
      />,
    )
    await user.click(screen.getByRole('button', { name: '发送消息' }))
    expect(onSubmit).toHaveBeenCalled()

    rerender(
      <ThreadComposer
        parts={[createTextPart('/stop')]}
        pending={false}
        disabled={false}
        onPartsChange={onPartsChange}
        onSubmit={onSubmit}
        onCommand={onCommand}
      />,
    )
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: /^stop/ }))
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'stop' }))
    expect(onPartsChange).toHaveBeenCalledWith([])

    onCommand.mockClear()
    rerender(
      <ThreadComposer
        parts={[createTextPart('/tree')]}
        pending={false}
        disabled={false}
        onPartsChange={onPartsChange}
        onSubmit={onSubmit}
        onCommand={onCommand}
      />,
    )
    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('{Enter}')
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'tree' }))
  })

  it('opens the primary command menu from plus without writing slash into the editor', async () => {
    const user = userEvent.setup()
    render(<ControlledComposer />)
    const add = screen.getByRole('button', { name: '打开命令表' })
    const editor = screen.getByLabelText('给 AI 发送消息')
    expect(add).toHaveAttribute('aria-expanded', 'false')

    await user.click(add)

    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    expect(add).toHaveAttribute('aria-expanded', 'true')
    expect(editor).toBeEmptyDOMElement()
    expect(document.querySelector('.thread-command-search')).toBeNull()
    expect(screen.getByRole('option', { name: /^upload/ })).toBeInTheDocument()

    await user.click(editor)
    await user.keyboard('{Escape}')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    expect(editor).toBeEmptyDOMElement()
  })

  it('opens plus menu over a non-empty draft and executes commands without clearing it', async () => {
    const user = userEvent.setup()
    const onPartsChange = vi.fn()
    const onCommand = vi.fn()
    render(
      <ThreadComposer
        parts={[createTextPart('保留这段草稿')]}
        pending={false}
        disabled={false}
        onPartsChange={onPartsChange}
        onSubmit={vi.fn()}
        onCommand={onCommand}
      />,
    )
    const editor = screen.getByLabelText('给 AI 发送消息')

    await user.click(screen.getByRole('button', { name: '打开命令表' }))

    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    expect(editor).toHaveTextContent('保留这段草稿')
    expect(onPartsChange).not.toHaveBeenCalled()

    await user.click(screen.getByRole('option', { name: /^agent/ }))

    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'agent' }))
    expect(onPartsChange).not.toHaveBeenCalled()
    expect(editor).toHaveTextContent('保留这段草稿')
  })

  it('handles /upload locally and keeps the native file input hidden', async () => {
    const user = userEvent.setup()
    render(
      <ThreadComposer
        parts={[createTextPart('/upload')]}
        pending={false}
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    const input = document.querySelector<HTMLInputElement>('input[type="file"]')
    expect(input).not.toBeNull()
    expect(input).toHaveAttribute('hidden')
    expect(input).toHaveClass('composer-file-input-hidden')
    const click = vi.spyOn(input!, 'click').mockImplementation(() => undefined)
    await user.click(screen.getByRole('option', { name: /^upload/ }))
    expect(click).toHaveBeenCalledOnce()
    click.mockRestore()
  })

  it('blocks send when draft is blank or pending', () => {
    const { rerender } = render(
      <ThreadComposer
        parts={[createTextPart('   ')]}
        pending={false}
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()
    rerender(
      <ThreadComposer
        parts={[createTextPart('/yolo')]}
        pending={false}
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()
  })

  it('keeps the placeholder visible for newline-only and whitespace-only drafts', () => {
    const props = {
      pending: false,
      disabled: false,
      onPartsChange: vi.fn(),
      onSubmit: vi.fn(),
      onCommand: vi.fn(),
    }
    const { rerender } = render(<ThreadComposer {...props} parts={[]} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    expect(editor).toHaveAttribute('data-placeholder-visible', 'true')

    rerender(<ThreadComposer {...props} parts={[createTextPart('\n')]} />)
    expect(editor).toHaveAttribute('data-placeholder-visible', 'true')

    rerender(<ThreadComposer {...props} parts={[createTextPart(' \n ')]} />)
    expect(editor).toHaveAttribute('data-placeholder-visible', 'true')

    rerender(<ThreadComposer {...props} parts={[createTextPart('message')]} />)
    expect(editor).toHaveAttribute('data-placeholder-visible', 'false')
  })
})
