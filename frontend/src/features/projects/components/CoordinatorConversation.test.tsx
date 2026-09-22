import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CoordinatorConversation } from './CoordinatorConversation'
import type { ProjectSnapshotDTO } from '../types'

interface MockAgentPaneProps {
  owner: { type: string; id: string }
  paneId: string
  capabilities?: { allowNewSession?: boolean; readOnly?: boolean }
  initialTarget?: { kind?: string; threadId?: string }
  defaults?: { agentName?: string }
}

// 遵守“共享AgentPane本身已有thinking/tool/error/approval测试，只证明复用组件和能力透传，不重复造大集成mock”原则
vi.mock('@/features/ai/runtime/AgentPane', () => ({
  AgentPane: vi.fn((props: MockAgentPaneProps) => (
    <div
      data-testid="mock-agent-pane"
      data-owner-type={props.owner.type}
      data-owner-id={props.owner.id}
      data-pane-id={props.paneId}
      data-readonly={String(props.capabilities?.readOnly)}
      data-allow-new-session={String(props.capabilities?.allowNewSession)}
      data-target-kind={props.initialTarget?.kind}
      data-thread-id={props.initialTarget?.threadId}
      data-agent-name={props.defaults?.agentName}
    >
      Mock AgentPane
    </div>
  )),
}))

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn().mockResolvedValue({ results: [{ name: 'coordinator' }] }),
  },
}))

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn().mockResolvedValue([]),
  },
}))

function renderComponent(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

describe('CoordinatorConversation', () => {
  const baseSnapshot: ProjectSnapshotDTO = {
    project: {
      id: 'proj-1',
      title: 'Project 1',
      description: 'Desc 1',
      coordinatorAgentName: 'coordinator-custom',
      nextIssueNumber: '1',
      version: '1',
      archivedAt: null,
      createdAt: '2026-09-22T00:00:00Z',
      updatedAt: '2026-09-22T00:00:00Z',
    },
    issues: [],
    dependencies: [],
    coordinatorSessionId: null,
    coordinatorSession: null,
    coordinatorThread: null,
  }

  it('shows loading placeholder when isLoadingSnapshot is true or snapshot is null', () => {
    // 测试意图：验证未完成快照拉取前展现 loading 态，防止竞态误触空会话创建
    const { rerender } = renderComponent(
      <CoordinatorConversation projectId="proj-1" isLoadingSnapshot={true} />,
    )
    expect(screen.getByText('加载 Coordinator 对话中...')).toBeInTheDocument()
    expect(screen.queryByTestId('mock-agent-pane')).toBeNull()

    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <CoordinatorConversation projectId="proj-1" snapshot={null} isLoadingSnapshot={false} />
      </QueryClientProvider>,
    )
    expect(screen.getByText('加载 Coordinator 对话中...')).toBeInTheDocument()
    expect(screen.queryByTestId('mock-agent-pane')).toBeNull()
  })

  it('renders blank first-send draft target with single-session restriction when no existing coordinatorThread is present', () => {
    // 测试意图：验证首次进入未绑定 Thread 的 Project 时，以 NEW_SESSION_DRAFT 状态挂载 AgentPane，并禁用多会话
    renderComponent(<CoordinatorConversation projectId="proj-1" snapshot={baseSnapshot} />)
    const pane = screen.getByTestId('mock-agent-pane')
    expect(pane).toHaveAttribute('data-owner-type', 'PROJECT')
    expect(pane).toHaveAttribute('data-owner-id', 'proj-1')
    expect(pane).toHaveAttribute('data-pane-id', 'coordinator')
    expect(pane).toHaveAttribute('data-target-kind', 'NEW_SESSION_DRAFT')
    expect(pane).toHaveAttribute('data-allow-new-session', 'false')
    expect(pane).toHaveAttribute('data-readonly', 'false')
    expect(pane).toHaveAttribute('data-agent-name', 'coordinator-custom')
  })

  it('restores bound thread target when snapshot contains coordinatorThread', () => {
    // 测试意图：验证已有 Coordinator 会话的项目能够直接恢复 BOUND_THREAD
    const boundSnapshot: ProjectSnapshotDTO = {
      ...baseSnapshot,
      coordinatorSessionId: 's1',
      coordinatorThread: {
        threadId: 't1',
        sessionId: 's1',
        headEntryId: 'e1',
        status: 'IDLE',
        nextCommandSequence: '1',
        version: '1',
        headMessagePreview: 'Hello project',
        lastMessageTimestamp: '2026-09-22T00:00:00Z',
      },
    }

    renderComponent(<CoordinatorConversation projectId="proj-1" snapshot={boundSnapshot} />)
    const pane = screen.getByTestId('mock-agent-pane')
    expect(pane).toHaveAttribute('data-owner-type', 'PROJECT')
    expect(pane).toHaveAttribute('data-owner-id', 'proj-1')
    expect(pane).toHaveAttribute('data-target-kind', 'BOUND_THREAD')
    expect(pane).toHaveAttribute('data-thread-id', 't1')
    expect(pane).toHaveAttribute('data-allow-new-session', 'false')
  })

  it('enforces read-only mode when project is archived', () => {
    // 测试意图：验证归档项目正确透传 readOnly: true 约束，只允许只读
    const archivedSnapshot: ProjectSnapshotDTO = {
      ...baseSnapshot,
      project: {
        ...baseSnapshot.project,
        archivedAt: '2026-09-22T10:00:00Z',
      },
    }

    renderComponent(<CoordinatorConversation projectId="proj-1" snapshot={archivedSnapshot} />)
    const pane = screen.getByTestId('mock-agent-pane')
    expect(pane).toHaveAttribute('data-readonly', 'true')
  })

  it('isolates state across project switching via key={projectId}', () => {
    // 测试意图：验证不同项目切换时，由 key={projectId} 与 props 确保 AgentPane 状态与目标彻底隔离
    const project2Snapshot: ProjectSnapshotDTO = {
      ...baseSnapshot,
      project: {
        ...baseSnapshot.project,
        id: 'proj-2',
        title: 'Project 2',
      },
    }

    const { rerender } = renderComponent(
      <CoordinatorConversation key="proj-1" projectId="proj-1" snapshot={baseSnapshot} />,
    )
    expect(screen.getByTestId('mock-agent-pane')).toHaveAttribute('data-owner-id', 'proj-1')

    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <CoordinatorConversation key="proj-2" projectId="proj-2" snapshot={project2Snapshot} />
      </QueryClientProvider>,
    )
    expect(screen.getByTestId('mock-agent-pane')).toHaveAttribute('data-owner-id', 'proj-2')
  })
})
