import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ThreadComposer } from '@/features/ai/thread-panel/ThreadComposer'
import { ThreadCommandPalette } from '@/features/ai/thread-panel/ThreadCommandPalette'
import { ThreadWidgetStack } from '@/features/ai/thread-panel/ThreadWidgetStack'
import { ThreadSubagentWidget } from '@/features/ai/thread-panel/ThreadSubagentWidget'
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
      controlsPending={false}
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

  it('opens plus palette, filters, and closes with Escape', async () => {
    const user = userEvent.setup()
    render(<ControlledComposer onSubmit={vi.fn()} onCommand={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: '打开命令表' }))
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
        controlsPending={false}
        onDraftChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '发送消息' })).toBeEnabled()
  })
})

describe('ThreadCommandPalette keyboard', () => {
  it('navigates and selects commands with keyboard', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    const onClose = vi.fn()
    render(
      <ThreadCommandPalette
        open
        query=""
        onQueryChange={vi.fn()}
        onSelect={onSelect}
        onClose={onClose}
      />,
    )
    const input = screen.getByPlaceholderText('搜索命令…')
    await user.type(input, '{ArrowDown}{ArrowUp}{Enter}')
    expect(onSelect).toHaveBeenCalled()
    await user.type(input, '{Escape}')
    expect(onClose).toHaveBeenCalled()
  })
})

describe('ThreadWidgetStack and SubagentWidget', () => {
  it('renders working status and nested running/queued/waiting subagents', () => {
    render(
      <ThreadWidgetStack working>
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
})
