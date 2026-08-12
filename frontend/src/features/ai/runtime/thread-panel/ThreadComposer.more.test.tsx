import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ThreadComposer } from '@/features/ai/runtime/thread-panel/ThreadComposer'
import { ThreadCommandPalette } from '@/features/ai/runtime/thread-panel/ThreadCommandPalette'
import { ThreadWidgetStack } from '@/features/ai/runtime/thread-panel/ThreadWidgetStack'
import { createTextPart, type ComposerPart } from '@/features/ai/composer/composer-parts'
import type { ThreadCommand } from '@/features/ai/runtime/thread-panel/thread-commands'

function ControlledComposer({
  onSubmit,
  onCommand,
  initial = [],
}: {
  onSubmit: () => void
  onCommand: (command: ThreadCommand) => void
  initial?: ComposerPart[]
}) {
  const [parts, setParts] = useState(initial)
  return (
    <ThreadComposer
      parts={parts}
      pending={false}
      disabled={false}
      onPartsChange={setParts}
      onSubmit={onSubmit}
      onCommand={onCommand}
    />
  )
}

describe('ThreadComposer interactions', () => {
  it('supports slash mode command execution and Escape close', async () => {
    const user = userEvent.setup()
    const onCommand = vi.fn()
    render(<ControlledComposer onSubmit={vi.fn()} onCommand={onCommand} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.type(editor, '/yolo')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.keyboard('{Enter}')
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'yolo' }))
  })

  it('navigates slash options with ArrowUp/ArrowDown then confirms with Enter', async () => {
    const user = userEvent.setup()
    const onCommand = vi.fn()
    render(<ControlledComposer onSubmit={vi.fn()} onCommand={onCommand} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.type(editor, '/')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    // 稳定产品顺序保持不变：session -> thread -> agent。
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'agent' }))
  })

  it('opens from slash or plus and closes with Escape', async () => {
    const user = userEvent.setup()
    render(<ControlledComposer onSubmit={vi.fn()} onCommand={vi.fn()} />)
    const add = screen.getByRole('button', { name: '打开命令表' })
    await user.click(add)
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('给 AI 发送消息'), '/stop')
    const palette = await screen.findByLabelText('命令表')
    expect(palette).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
  })

  it('keeps send enabled while a previous mutation is still pending', () => {
    render(
      <ThreadComposer
        parts={[createTextPart('hello')]}
        pending
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '发送消息' })).toBeEnabled()
  })

  it('cancels delayed focus work when the composer unmounts', () => {
    vi.useFakeTimers()
    try {
      const { unmount } = render(
        <ThreadComposer
          parts={[createTextPart('hello')]}
          pending={false}
          disabled={false}
          onPartsChange={vi.fn()}
          onSubmit={vi.fn()}
          onCommand={vi.fn()}
        />,
      )

      fireEvent.click(screen.getByRole('button', { name: '发送消息' }))
      expect(vi.getTimerCount()).toBe(1)
      unmount()
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('renders only the shared plus command entry, without standalone actor-state, Stop, or Retry controls', () => {
    render(
      <ThreadComposer
        parts={[]}
        pending={false}
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '打开命令表' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Stop' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
    expect(screen.queryByText('RUNNING')).not.toBeInTheDocument()
    expect(screen.queryByText('IDLE')).not.toBeInTheDocument()
  })

  it('disables the complete composer while no Thread projection is available', () => {
    render(
      <ThreadComposer
        parts={[createTextPart('message')]}
        pending={false}
        disabled
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByLabelText('给 AI 发送消息')).toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '打开命令表' })).toBeDisabled()
  })
})

describe('ThreadCommandPalette', () => {
  it('filters and selects a slash command', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    render(
      <ThreadCommandPalette
        open
        query="stop"
        activeIndex={0}
        onActiveIndexChange={vi.fn()}
        onSelect={onSelect}
      />,
    )
    await user.click(screen.getByRole('option', { name: /^stop/ }))
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 'stop' }))
  })
})

describe('ThreadWidgetStack', () => {
  it('renders queued messages under the working zone and stays empty when idle', () => {
    const { container, rerender } = render(
      <ThreadWidgetStack
        working
        queuedMessages={[
          { clientCommandId: 'queued-1', role: 'user', text: '稍后处理这条', sequence: 1 },
        ]}
      />,
    )
    expect(screen.getByText('Working...')).toBeInTheDocument()
    expect(screen.getByText('稍后处理这条')).toBeInTheDocument()
    const zone = screen.getByLabelText('会话组件区')
    const zoneText = zone.textContent ?? ''
    expect(zoneText.indexOf('Working...')).toBeLessThan(zoneText.indexOf('稍后处理这条'))

    rerender(<ThreadWidgetStack working={false} />)
    expect(container.querySelector('.thread-widget-zone')?.childNodes.length ?? 0).toBe(0)
  })
})
