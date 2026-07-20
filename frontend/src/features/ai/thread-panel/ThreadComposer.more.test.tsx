import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ThreadComposer } from '@/features/ai/thread-panel/ThreadComposer'
import { ThreadCommandPalette } from '@/features/ai/thread-panel/ThreadCommandPalette'
import { ThreadWidgetStack } from '@/features/ai/thread-panel/ThreadWidgetStack'
import { ThreadSubagentWidget } from '@/features/ai/thread-panel/ThreadSubagentWidget'
import { ThreadActivityWidget } from '@/features/ai/thread-panel/ThreadActivityWidget'
import type { ThreadCommand } from '@/features/ai/thread-panel/thread-commands'

function ControlledComposer({
  onSubmit,
  onCommand,
  initial = '',
}: {
  onSubmit: () => void
  onCommand: (command: ThreadCommand) => void
  initial?: string
}) {
  const [draft, setDraft] = useState(initial)
  return (
    <ThreadComposer
      draft={draft}
      pending={false}
      disabled={false}
      onDraftChange={setDraft}
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
    const textarea = screen.getByLabelText('给 AI 发送消息')
    await user.type(textarea, '/yolo')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.keyboard('{Enter}')
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'yolo' }))
  })

  it('opens only from slash mode and closes with Escape', async () => {
    const user = userEvent.setup()
    render(<ControlledComposer onSubmit={vi.fn()} onCommand={vi.fn()} />)
    expect(screen.queryByRole('button', { name: '打开命令表' })).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('给 AI 发送消息'), '/stop')
    const palette = await screen.findByLabelText('命令表')
    expect(palette).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
  })

  it('keeps send enabled while a previous mutation is still pending', () => {
    render(
      <ThreadComposer
        draft="hello"
        pending
        disabled={false}
        onDraftChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '发送消息' })).toBeEnabled()
  })

  it('does not render standalone actor-state, Stop, Retry, or add controls', () => {
    render(
      <ThreadComposer
        draft=""
        pending={false}
        disabled={false}
        onDraftChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.queryByRole('button', { name: '打开命令表' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Stop' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
    expect(screen.queryByText('RUNNING')).not.toBeInTheDocument()
    expect(screen.queryByText('IDLE')).not.toBeInTheDocument()
  })

  it('disables the complete composer while no Thread projection is available', () => {
    render(
      <ThreadComposer
        draft="message"
        pending={false}
        disabled
        onDraftChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByLabelText('给 AI 发送消息')).toBeDisabled()
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()
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
        onSelect={onSelect}
      />,
    )
    await user.click(screen.getByRole('option', { name: /^stop/ }))
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 'stop' }))
  })
})

describe('ThreadWidgetStack and SubagentWidget', () => {
  it('renders working status and nested running/queued/waiting subagents', () => {
    render(
      <ThreadWidgetStack
        working
        queuedMessages={[
          { inputId: 'queued-1', role: 'user', text: '稍后处理这条', sequence: 1 },
        ]}
      >
        <ThreadSubagentWidget
          taskTree={[
            {
              task: {
                parentInvocationId: 't1',
                parentSessionId: 's1',
                parentThreadId: 'th1',
                childSessionId: 's2',
                childThreadId: 'th2',
                targetAgent: 'worker',
                workingCopyPolicy: 'SHARED',
                workingCopyRevision: null,
                maxTurns: 3,
                status: 'RUNNING',
                report: { finalReport: 'detail-text-here' } as never,
                createTime: null,
                updateTime: null,
              },
              children: [
                {
                  task: {
                    parentInvocationId: 't2',
                    parentSessionId: 's2',
                    parentThreadId: 'th2',
                    childSessionId: 's3',
                    childThreadId: 'th3',
                    targetAgent: '',
                    workingCopyPolicy: 'SHARED',
                    workingCopyRevision: null,
                    maxTurns: 1,
                    status: 'QUEUED',
                    report: null,
                    createTime: null,
                    updateTime: null,
                  },
                  children: [
                    {
                      task: {
                        parentInvocationId: '',
                        parentSessionId: 's3',
                        parentThreadId: 'th3',
                        childSessionId: 's4',
                        childThreadId: 'th4',
                        targetAgent: '',
                        workingCopyPolicy: 'SHARED',
                        workingCopyRevision: null,
                        maxTurns: 1,
                        status: 'WAITING',
                        report: null,
                        createTime: null,
                        updateTime: null,
                      },
                      children: [],
                    },
                  ],
                },
              ],
            },
            {
              task: {
                parentInvocationId: 'done',
                parentSessionId: 's1',
                parentThreadId: 'th1',
                childSessionId: 'sd',
                childThreadId: 'thd',
                targetAgent: 'done-agent',
                workingCopyPolicy: 'SHARED',
                workingCopyRevision: null,
                maxTurns: 1,
                status: 'SUCCEEDED',
                report: null,
                createTime: null,
                updateTime: null,
              },
              children: [],
            },
          ]}
        />
      </ThreadWidgetStack>,
    )
    expect(screen.getByText('Working...')).toBeInTheDocument()
    expect(screen.getByText('稍后处理这条')).toBeInTheDocument()
    const zoneText = screen.getByLabelText('会话组件区').textContent ?? ''
    expect(zoneText.indexOf('Working...')).toBeLessThan(zoneText.indexOf('稍后处理这条'))
    expect(screen.getByText(/worker/)).toBeInTheDocument()
    expect(screen.getByText(/detail-text-here/)).toBeInTheDocument()
    expect(screen.queryByText('done-agent')).not.toBeInTheDocument()
  })

  it('hides widget stack when idle and empty, and empty subagent widget', () => {
    const { container } = render(<ThreadWidgetStack working={false} />)
    expect(container).toBeEmptyDOMElement()
    const empty = render(<ThreadSubagentWidget taskTree={[]} />)
    expect(empty.container).toBeEmptyDOMElement()
  })

  it('collapses zone when children all render null (no residual height/border)', () => {
    const { container } = render(
      <ThreadWidgetStack working={false}>
        <ThreadSubagentWidget taskTree={[]} />
        <ThreadActivityWidget activities={[]} />
      </ThreadWidgetStack>,
    )
    const zone = container.querySelector('.thread-widget-zone')
    // 节点可存在，但 :empty → 高度/边框为 0；更常见是完全无可见内容
    expect(zone == null || zone.childNodes.length === 0).toBe(true)
  })
})
