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
          activities={[activity('subagent_started', { childSessionId: 'child-1', targetAgent: 'researcher' })]}
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
    expect(screen.getByTitle('打开子会话')).toHaveAttribute('href', '/sessions/child-1')

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

  it('formats every supported root action and subagent activity', () => {
    render(
      <MemoryRouter>
        <TaskTimelinePanel
          activities={[
            activity('subagent_resumed', { childSessionId: 'child-1', targetAgent: 'researcher' }),
            activity('subagent_completed', { childSessionId: 'child-1', status: 'SUCCEEDED' }),
            activity('subagent_cancel_requested', { childSessionId: 'child-1', reason: 'timeout' }),
            activity('permission_requested', { invocationId: 'invocation-1', tool: 'write_file' }),
            activity('permission_resolved', { invocationId: 'invocation-1', decision: 'ALLOW' }),
            activity('steer_requested', {}),
            activity('follow_up_requested', {}),
            activity('steer_consumed', {}),
            activity('follow_up_consumed', {}),
            activity('control_promoted', {}),
            activity('abort_requested', {}),
            activity('run_started', {}),
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

    expect(screen.getByText('恢复子代理 researcher')).toBeInTheDocument()
    expect(screen.getByText('子代理完成：SUCCEEDED')).toBeInTheDocument()
    expect(screen.getByText('请求取消子代理：timeout')).toBeInTheDocument()
    expect(screen.getByText('等待工具授权：write_file')).toBeInTheDocument()
    expect(screen.getByText('工具授权已ALLOW')).toBeInTheDocument()
    expect(screen.getByText('已提交 steer 指令')).toBeInTheDocument()
    expect(screen.getByText('已提交 follow-up')).toBeInTheDocument()
    expect(screen.getByText('已消费 steer 指令')).toBeInTheDocument()
    expect(screen.getByText('已消费 follow-up')).toBeInTheDocument()
    expect(screen.getByText('follow-up 已提升为 Run')).toBeInTheDocument()
    expect(screen.getByText('已请求终止 Run')).toBeInTheDocument()
    expect(screen.queryByText('run_started')).not.toBeInTheDocument()
  })
})

const node: SubagentTaskNode = {
  task: {
    parentInvocationId: 'task-1',
    parentSessionId: 'root',
    childSessionId: 'child-1',
    childRunId: 'child-run-1',
    targetAgent: 'researcher',
    workingCopyPolicy: 'SHARED',
    workingCopyRevision: null,
    maxTurns: 10,
    idleTimeoutMillis: 60000,
    status: 'SUCCEEDED',
    report: {
      childSessionId: 'child-1',
      childRunId: 'child-run-1',
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
      childSessionId: 'child-2',
      childRunId: 'child-run-2',
      targetAgent: 'nested-agent',
      workingCopyPolicy: 'SHARED',
      workingCopyRevision: null,
      maxTurns: 5,
      idleTimeoutMillis: null,
      status: 'RUNNING',
      report: null,
      createTime: '2026-06-20T02:00:00Z',
      updateTime: '2026-06-20T02:00:00Z',
    },
    children: [],
  }],
}

function activity(type: string, payload: Record<string, unknown>): RootActivityDTO {
  return {
    rootSessionId: 'root',
    sessionId: 'root',
    runId: 'run-1',
    eventId: '1',
    sequence: 1,
    type,
    payloadJson: JSON.stringify(payload),
    createTime: '2026-06-20T02:00:00Z',
  }
}
