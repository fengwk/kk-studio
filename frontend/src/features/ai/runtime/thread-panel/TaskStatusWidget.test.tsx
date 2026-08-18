import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import {
  TaskStatusWidget,
  type TaskApprovalDecision,
} from '@/features/ai/runtime/thread-panel/TaskStatusWidget'
import type { ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

function taskMessage(heartbeatJson: string): ToolDialogueMessage {
  return {
    id: 'task-call',
    role: 'tool',
    subjectEntryId: 'e1',
    createdAt: null,
    status: 'streaming',
    phase: 'call',
    text: '',
    toolCallId: 'call-task',
    toolName: 'task',
    rendererKey: 'task',
    arguments: '{"subagent_type":"explorer","prompt":"inspect"}',
    attachments: [],
    invocationId: 'inv-parent',
    partial: `${heartbeatJson}\n`,
  }
}

function heartbeat(
  threadId: string,
  overrides: Partial<Record<string, unknown>> = {},
): string {
  return JSON.stringify({
    kind: 'task.status',
    threadId,
    subagentType: 'explorer',
    state: 'running_tool',
    depth: 2,
    turns: 3,
    toolCalls: 5,
    lastActivity: 'running read',
    approvals: [],
    descendants: [],
    ...overrides,
  })
}

describe('TaskStatusWidget', () => {
  it('returns nothing when there are no active tasks', () => {
    const { container } = render(
      <TaskStatusWidget
        messages={[
          {
            id: 'u1',
            role: 'user',
            subjectEntryId: null,
            createdAt: null,
            text: 'hi',
          },
        ]}
      />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('renders the running count and per-task subagent/turns/toolCalls/lastActivity', () => {
    render(
      <TaskStatusWidget
        messages={[
          taskMessage(heartbeat('101', { subagentType: 'explorer', turns: 3, toolCalls: 5 })),
          taskMessage(
            heartbeat('102', { subagentType: 'coder', state: 'running_model', turns: 1, toolCalls: 0 }),
          ),
        ]}
      />,
    )
    expect(screen.getByText('2 个运行中')).toBeInTheDocument()
    expect(screen.getByText('explorer')).toBeInTheDocument()
    expect(screen.getByText('coder')).toBeInTheDocument()
    expect(screen.getByText('工具运行中')).toBeInTheDocument()
    expect(screen.getByText('模型运行中')).toBeInTheDocument()
    expect(screen.getAllByText('3 轮')).toHaveLength(1)
    expect(screen.getAllByText('5 次工具调用')).toHaveLength(1)
    // 两个任务都上报了同一 lastActivity。
    expect(screen.getAllByText('running read')).toHaveLength(2)
  })

  it('omits optional metrics and uses zero indentation when the heartbeat omits them', () => {
    render(
      <TaskStatusWidget
        messages={[
          taskMessage(
            heartbeat('101', {
              depth: null,
              turns: null,
              toolCalls: null,
              lastActivity: null,
            }),
          ),
        ]}
      />,
    )

    expect(screen.getByText('1 个运行中')).toBeInTheDocument()
    expect(screen.getByText('explorer')).toBeInTheDocument()
    expect(screen.getByText('工具运行中')).toBeInTheDocument()
    expect(screen.queryByText('3 轮')).not.toBeInTheDocument()
    expect(screen.queryByText('5 次工具调用')).not.toBeInTheDocument()
    expect(screen.queryByText('running read')).not.toBeInTheDocument()
    expect(screen.getByRole('listitem').getAttribute('style')).toContain('--task-depth: 0')
  })

  it('replaces heartbeats of the same thread and orders rows by depth', () => {
    render(
      <TaskStatusWidget
        messages={[
          taskMessage(heartbeat('101', { depth: 3, subagentType: 'helper' })),
          taskMessage(heartbeat('101', { depth: 2, state: 'waiting_approval' })),
        ]}
      />,
    )
    // 同一子 Thread 只保留最新一帧：running 总数不是 2。
    expect(screen.getByText('1 个运行中')).toBeInTheDocument()
    const rows = screen.getAllByRole('listitem')
    expect(rows).toHaveLength(1)
  })

  it('renders approval entries with tool name/reason and forwards Allow/Deny to the child thread', async () => {
    const user = userEvent.setup()
    const onDecideApproval = vi.fn(
      (_threadId: string, _invocationId: string, _decision: TaskApprovalDecision) => undefined,
    )
    render(
      <TaskStatusWidget
        messages={[
          taskMessage(
            heartbeat('101', {
              state: 'waiting_approval',
              approvals: [
                { invocationId: '201', toolName: 'bash', reason: 'runs a script' },
                { invocationId: '202', toolName: 'read', reason: null },
              ],
            }),
          ),
        ]}
        onDecideApproval={onDecideApproval}
      />,
    )
    expect(screen.getByText('等待审批：bash — runs a script')).toBeInTheDocument()
    expect(screen.getByText('等待审批：read')).toBeInTheDocument()
    // 子任务审批复用同一 danger 按钮契约，覆盖嵌套审批不会退回普通灰色边框。
    expect(screen.getByRole('button', { name: '拒绝 read' })).toHaveClass('ghost-btn', 'danger')

    await user.click(screen.getByRole('button', { name: '允许 bash' }))
    expect(onDecideApproval).toHaveBeenCalledWith('101', '201', 'ALLOW')
    await user.click(screen.getByRole('button', { name: '拒绝 read' }))
    expect(onDecideApproval).toHaveBeenCalledWith('101', '202', 'DENY')
  })

  it('shows approvals even when the state has not flipped to waiting_approval yet', () => {
    render(
      <TaskStatusWidget
        messages={[
          taskMessage(
            heartbeat('101', {
              state: 'running_tool',
              approvals: [{ invocationId: '201', toolName: 'bash', reason: null }],
            }),
          ),
        ]}
      />,
    )
    expect(screen.getByText('等待审批：bash')).toBeInTheDocument()
  })

  it('forwards descendant approvals to the actual nested thread', async () => {
    const user = userEvent.setup()
    const onDecideApproval = vi.fn()
    render(
      <TaskStatusWidget
        messages={[
          taskMessage(
            heartbeat('101', {
              descendants: [
                {
                  threadId: '303',
                  subagentType: 'helper',
                  state: 'waiting_approval',
                  depth: 3,
                  turns: 1,
                  toolCalls: 1,
                  lastActivity: 'waiting bash',
                  approvals: [{ invocationId: '404', toolName: 'bash', reason: null }],
                },
              ],
            }),
          ),
        ]}
        onDecideApproval={onDecideApproval}
      />,
    )

    await user.click(screen.getByRole('button', { name: '允许 bash' }))
    expect(onDecideApproval).toHaveBeenCalledWith('303', '404', 'ALLOW')
  })

  it('disables all decision buttons while an approval request is pending', () => {
    render(
      <TaskStatusWidget
        messages={[
          taskMessage(
            heartbeat('101', {
              state: 'waiting_approval',
              approvals: [{ invocationId: '201', toolName: 'bash', reason: null }],
            }),
          ),
        ]}
        approvalPending
      />,
    )
    expect(screen.getByRole('button', { name: '允许 bash' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '拒绝 bash' })).toBeDisabled()
  })

  it('applies the parentTaskLevel offset to the aggregation keys', () => {
    render(
      <TaskStatusWidget
        messages={[
          taskMessage(heartbeat('101', { depth: 1 })),
          taskMessage(heartbeat('102', { subagentType: 'coder', depth: 1 })),
          taskMessage(heartbeat('103', { subagentType: 'helper', depth: 2 })),
        ]}
        parentTaskLevel={3}
      />,
    )
    // parentTaskLevel + depth 聚合：101/102 -> level 4，103 -> level 5；全部展示。
    expect(screen.getByText('3 个运行中')).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
  })
})
