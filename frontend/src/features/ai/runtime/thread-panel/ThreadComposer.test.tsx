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
  threadCommandsForTarget,
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
      'models',
      'tree',
      'stop',
      'new',
      'upload',
      'debug',
      'shortcuts',
      'compact',
      'rename-session',
      'rename-thread',
    ])
    // `/session`（全局 Session 重绑定）已彻底移除，不再出现在稳定命令表中。
    expect(THREAD_COMMANDS.some((c) => c.id === 'session')).toBe(false)
    const blank = threadCommandsForTarget({ kind: 'NEW_SESSION_DRAFT' })
    expect(blank.map((c) => c.id)).toEqual(THREAD_COMMANDS.map((c) => c.id))
    // 空面板还没有 Thread，因此 `/tree`/`/stop`/`/new`/`/debug`/`/compact`
    // 不可用，而 `/thread`（仅切换面板）与 `/shortcuts` 保持可用。
    expect(blank.filter((c) => !c.disabled).map((c) => c.id)).toEqual([
      'thread',
      'agent',
      'environment',
      'yolo',
      'models',
      'upload',
      'shortcuts',
    ])
    expect(blank.find((c) => c.id === 'new')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'tree')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'debug')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'compact')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'rename-session')?.disabled).toBe(true)
    expect(blank.find((c) => c.id === 'rename-thread')?.disabled).toBe(true)
    expect(threadCommandsForTarget({ kind: 'BOUND_THREAD' }).every((c) => !c.disabled)).toBe(true)
    expect(filterThreadCommands('yo').map((c) => c.id)).toEqual(['yolo'])
    expect(filterThreadCommands('sto').map((c) => c.id)).toEqual(['stop'])
    expect(filterThreadCommands('tree')[0]?.id).toBe('tree')
    expect(filterThreadCommands('debug')[0]?.id).toBe('debug')
    expect(filterThreadCommands('shortcuts')[0]?.id).toBe('shortcuts')
    expect(['history', 'branch'].every((keyword) =>
      filterThreadCommands(keyword).some((command) => command.id === 'tree'))).toBe(true)
    expect(filterThreadCommands('', blank).map((c) => c.id)).toEqual(THREAD_COMMANDS.map((c) => c.id))
    expect(firstEnabledCommandIndex(blank)).toBe(0)
    expect(filterThreadCommands('missing')).toEqual([])
  })

  it('projects the same command matrix for Chat and Canvas bound targets', () => {
    const chatBound = threadCommandsForTarget({ kind: 'BOUND_THREAD', threadId: 't1' })
    const canvasBound = threadCommandsForTarget({ kind: 'BOUND_THREAD', threadId: 't1' })
    expect(canvasBound.map((command) => command.id)).toEqual(chatBound.map((command) => command.id))
    expect(canvasBound.filter((command) => !command.disabled).map((command) => command.id))
      .toEqual(THREAD_COMMANDS.map((command) => command.id))
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

  it('renders a two-level composer with Permission and Model/Variant controls', () => {
    const { container } = render(
      <ThreadComposer
        parts={[]}
        pending={false}
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
        settings={{
          model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'high' },
          models: [
            {
              providerName: 'minimax',
              name: 'MiniMax',
              config: {
                defaultVariant: 'default',
                variants: [{ id: 'default' }, { id: 'high' }],
              },
            },
          ],
          yoloEnabled: false,
          onModelChange: vi.fn(),
          onYoloChange: vi.fn(),
        }}
      />,
    )

    const dock = container.querySelector('.thread-dock')
    expect(dock).not.toBeNull()
    expect(dock?.querySelector('.composer-editor')).not.toBeNull()
    expect(dock?.querySelector('.thread-dock-controls')).not.toBeNull()
    expect(screen.getByRole('button', { name: '权限模式' })).toHaveTextContent('Default')
    expect(screen.getByRole('button', { name: 'Model 与 Variant' })).toHaveTextContent(
      'minimax/MiniMax · high',
    )
  })

  it('selects Permission and a two-level Model/Variant through anchored listboxes', async () => {
    const user = userEvent.setup()
    const onModelChange = vi.fn()
    const onYoloChange = vi.fn()
    render(
      <ThreadComposer
        parts={[]}
        pending={false}
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
        settings={{
          model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
          models: [
            {
              providerName: 'minimax',
              name: 'MiniMax',
              config: {
                defaultVariant: 'default',
                variants: [{ id: 'default' }, { id: 'high' }],
              },
            },
            {
              providerName: 'openai',
              name: 'gpt',
              config: {
                defaultVariant: 'fast',
                variants: [{ id: 'fast' }, { id: 'quality' }],
              },
            },
          ],
          yoloEnabled: false,
          onModelChange,
          onYoloChange,
        }}
      />,
    )

    await user.click(screen.getByRole('button', { name: '权限模式' }))
    await user.click(screen.getByRole('option', { name: 'YOLO' }))
    expect(onYoloChange).toHaveBeenCalledWith(true)

    await user.click(screen.getByRole('button', { name: 'Model 与 Variant' }))
    const search = screen.getByRole('searchbox', { name: '搜索模型' })
    expect(search).toHaveFocus()
    await user.type(search, 'gpt')
    expect(screen.queryByRole('option', { name: 'minimax/MiniMax' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: 'openai/gpt' }))
    expect(screen.getByRole('listbox', { name: 'Variant 选项' })).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: 'quality' }))
    expect(onModelChange).toHaveBeenCalledWith({
      providerName: 'openai',
      modelName: 'gpt',
      variant: 'quality',
    })
  })

  it('opens the model menu from /models', async () => {
    const user = userEvent.setup()
    render(
      <ThreadComposer
        parts={[createTextPart('/models')]}
        pending={false}
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
        settings={{
          model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
          models: [
            {
              providerName: 'minimax',
              name: 'MiniMax',
              config: {
                defaultVariant: 'default',
                variants: [{ id: 'default' }],
              },
            },
          ],
          yoloEnabled: false,
          onModelChange: vi.fn(),
          onYoloChange: vi.fn(),
        }}
      />,
    )
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: /^models/ }))
    expect(screen.getByRole('searchbox', { name: '搜索模型' })).toHaveFocus()
    expect(screen.getByRole('listbox', { name: 'Model 选项' })).toBeInTheDocument()
  })
})
