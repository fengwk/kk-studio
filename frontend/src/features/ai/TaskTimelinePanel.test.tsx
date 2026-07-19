import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { TaskTimelinePanel } from '@/features/ai/TaskTimelinePanel'
import type { SubagentTaskNode } from '@/features/ai/subagent-task-tree'
import type { RootActivityDTO } from '@/shared/api/contracts'

describe('TaskTimelinePanel', () => {
  it('renders root activity, nested tasks, child report/revision/artifacts and permission relay decisions', async () => {
    const user = userEvent.setup()
    const onDecision = vi.fn()
    render(
      <MemoryRouter>
        <TaskTimelinePanel
          activities={[activity('subagent_started', { childSessionId: 'child-1', target: 'researcher' })]}
          taskTree={[node]}
          relayPermissions={[{ invocationId: 'invocation-1', sessionId: 'child-1', tool: 'write_file', workdir: '/repo', arguments: '{}' }]}
          loading={false}
          error={null}
          decisionPending={false}
          onDecision={onDecision}
        />
      </MemoryRouter>,
    )

    expect(screen.getByText('启动子代理 researcher')).toBeInTheDocument()
    expect(screen.getByText('子代理权限：write_file')).toBeInTheDocument()
    expect(screen.getByText('nested-agent')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /researcher/i }))

    expect(screen.getByText('完成报告')).toBeInTheDocument()
    expect(screen.getByText('ISOLATED / revision-7')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'image/png · artifact-1' })).toHaveAttribute('href', '/api/artifacts/artifact-1')
    expect(screen.getByTitle('子 Thread child-thread-1')).toBeInTheDocument()

    await user.click(screen.getAllByRole('button', { name: '允许' })[0]!)
    await user.click(screen.getAllByRole('button', { name: '拒绝' })[0]!)
    expect(onDecision).toHaveBeenNthCalledWith(1, 'invocation-1', 'allow')
    expect(onDecision).toHaveBeenNthCalledWith(2, 'invocation-1', 'deny')
  })

  it('renders loading, error and empty states without a selected task', () => {
    render(
      <MemoryRouter>
        <TaskTimelinePanel
          activities={[]}
          taskTree={[]}
          relayPermissions={[]}
          loading
          error={new Error('offline')}
          decisionPending={false}
          onDecision={() => undefined}
        />
      </MemoryRouter>,
    )

    expect(screen.getByText('正在加载任务活动')).toBeInTheDocument()
    expect(screen.getByText('任务活动加载失败')).toBeInTheDocument()
    expect(screen.getByText('暂无子代理任务')).toBeInTheDocument()
    expect(screen.getByText('选择子代理查看报告、版本与产物')).toBeInTheDocument()
  })

  it('formats supported subagent/permission activities and omits unrelated chatter', () => {
    render(
      <MemoryRouter>
        <TaskTimelinePanel
          activities={[
            activity('subagent_started', { childSessionId: 'child-1' }),
            activity('subagent_completed', { childSessionId: 'child-1', status: 'SUCCEEDED' }),
            activity('subagent_cancel_requested', { childSessionId: 'child-1', reason: 'timeout' }),
            activity('permission_requested', { invocationId: 'invocation-1', tool: 'write_file' }),
            activity('permission_resolved', { invocationId: 'invocation-1', decision: 'ALLOW' }),
            activity('thread_started', {}),
          ].map((item, index) => ({ ...item, eventId: String(index + 1) }))}
          taskTree={[]}
          relayPermissions={[]}
          loading={false}
          error={null}
          decisionPending={false}
          onDecision={() => undefined}
        />
      </MemoryRouter>,
    )

    expect(screen.getByText('启动子代理 child-1')).toBeInTheDocument()
    expect(screen.getByText('子代理完成：SUCCEEDED')).toBeInTheDocument()
    expect(screen.getByText('请求取消子代理：timeout')).toBeInTheDocument()
    expect(screen.getByText('等待工具授权：write_file')).toBeInTheDocument()
    expect(screen.getByText('工具授权已ALLOW')).toBeInTheDocument()
    expect(screen.queryByText('thread_started')).not.toBeInTheDocument()
  })

  it('renders permissions-only mode and can select a nested child task', async () => {
    const user = userEvent.setup()
    const onDecision = vi.fn()
    const { rerender } = render(
      <MemoryRouter>
        <TaskTimelinePanel
          activities={[]}
          taskTree={[]}
          relayPermissions={[]}
          loading={false}
          error={null}
          decisionPending={false}
          onDecision={onDecision}
          permissionsOnly
        />
      </MemoryRouter>,
    )
    expect(document.body.textContent).toBe('')

    rerender(
      <MemoryRouter>
        <TaskTimelinePanel
          activities={[]}
          taskTree={[node]}
          relayPermissions={[{ invocationId: 'invocation-1', sessionId: 'child-1', tool: 'bash', workdir: '', arguments: '' }]}
          loading={false}
          error={null}
          decisionPending
          onDecision={onDecision}
          permissionsOnly
        />
      </MemoryRouter>,
    )
    expect(screen.getByText('子代理权限：bash')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '允许' })).toBeDisabled()

    rerender(
      <MemoryRouter>
        <TaskTimelinePanel
          activities={[]}
          taskTree={[node]}
          relayPermissions={[]}
          loading={false}
          error={null}
          decisionPending={false}
          onDecision={onDecision}
        />
      </MemoryRouter>,
    )
    await user.click(screen.getByRole('button', { name: /nested-agent/i }))
    expect(screen.getByTitle('子 Thread child-thread-2')).toBeInTheDocument()
    expect(screen.getAllByText('RUNNING').length).toBeGreaterThan(0)
  })
})

const node: SubagentTaskNode = {
  task: {
    parentInvocationId: 'task-1',
    parentSessionId: 'root',
    parentThreadId: 'root-thread',
    childSessionId: 'child-1',
    childThreadId: 'child-thread-1',
    targetAgent: 'researcher',
    workingCopyPolicy: 'SHARED',
    workingCopyRevision: null,
    maxTurns: 10,
    status: 'SUCCEEDED',
    report: {
      childSessionId: 'child-1',
      childThreadId: 'child-thread-1',
      status: 'SUCCEEDED',
      finalReport: '完成报告',
      artifacts: [{ artifactId: 'artifact-1', mediaType: 'image/png', sizeBytes: 32 }],
      turnCount: 2,
      toolCount: 3,
      workingCopyPolicy: 'ISOLATED',
      workingCopyRevision: 'revision-7',
    },
    createTime: '2026-06-20T02:00:00Z',
    updateTime: '2026-06-20T02:00:00Z',
  },
  children: [{
    task: {
      parentInvocationId: 'task-2',
      parentSessionId: 'child-1',
      parentThreadId: 'child-thread-1',
      childSessionId: 'child-2',
      childThreadId: 'child-thread-2',
      targetAgent: 'nested-agent',
      workingCopyPolicy: 'SHARED',
      workingCopyRevision: null,
      maxTurns: 5,
      status: 'RUNNING',
      report: null,
      createTime: '2026-06-20T02:00:00Z',
      updateTime: '2026-06-20T02:00:00Z',
    },
    children: [],
  }],
}

function activity(eventType: string, payload: Record<string, unknown>): RootActivityDTO {
  return {
    rootSessionId: 'root',
    sessionId: 'root',
    threadId: 'root-thread',
    eventId: '1',
    eventType,
    payloadJson: JSON.stringify(payload),
    createTime: '2026-06-20T02:00:00Z',
  }
}
