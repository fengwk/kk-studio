import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ThreadWorkspacePage } from '@/features/ai/thread/ThreadWorkspacePage'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { environmentService } from '@/shared/api/environment-service'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessSessionEntryDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'
import { createTextPart } from '@/features/ai/composer/composer-parts'
import { saveThreadDraftParts } from '@/features/ai/runtime/thread-draft-store'
import { setLocale } from '@/shared/i18n'

const CHILD_THREAD_ID = '00000000-0000-0000-0000-000000000999'
const OTHER_CHILD_THREAD_ID = '00000000-0000-0000-0000-000000000998'

const { fakeApplicationEvents } = vi.hoisted(() => {
  const manager = { subscribe: vi.fn(() => () => undefined) }
  return { fakeApplicationEvents: { useApplicationEvents: () => manager } }
})

vi.mock('@/shared/app-events', () => ({
  useApplicationEvents: fakeApplicationEvents.useApplicationEvents,
}))
vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listAgents: vi.fn(),
    listModels: vi.fn(),
    listProviders: vi.fn(),
  },
}))
vi.mock('@/shared/api/chat-service', () => ({
  chatService: {
    getChat: vi.fn(),
    listChatSessions: vi.fn(),
  },
}))
vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
  },
}))
vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getThreadSnapshot: vi.fn(),
    decideApproval: vi.fn(),
    listSessionThreads: vi.fn(),
    listSessionEntries: vi.fn(),
    acceptThreadCommandBatch: vi.fn(),
    acceptCommandBatch: vi.fn(),
    compactThread: vi.fn(),
    setThreadYolo: vi.fn(),
    stopThread: vi.fn(),
    getThreadTree: vi.fn(),
  },
}))

function thread(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    name: 'Child Worker Thread',
    threadId: CHILD_THREAD_ID,
    sessionId: 'session-child',
    headEntryId: 'head-1',
    parentThreadId: null,
    yoloPolicy: { mode: 'DISABLE', rootThreadId: null },
    nextCommandSequence: '1',
    version: '1',
    status: 'WAITING_APPROVAL',
    processing: true,
    executionControl: 'RUNNABLE',
    branchSettings: {
      agentName: 'assistant',
      model: {
        providerName: 'minimax',
        modelName: 'MiniMax',
        variant: 'default',
      },
      environmentName: null,
      goal: null,
    },
    createTime: '2026-07-28T10:00:00Z',
    updateTime: '2026-07-28T10:00:00Z',
    ...overrides,
  }
}

function snapshotWithPendingTool(targetThreadId: string): HarnessThreadSnapshotDTO {
  const toolEntry: HarnessSessionEntryDTO = {
    entryId: 'entry-tool-1',
    sessionId: 'session-child',
    parentEntryId: null,
    entryType: 'MESSAGE',
    payloadJson: JSON.stringify({
      message: {
        role: 'ASSISTANT',
        contents: [
          {
            type: 'tool_call',
            toolCallId: 'call-bash-1',
            toolName: 'bash',
            rendererKey: 'bash',
            argumentsJson: '{"command":"rm -rf /tmp/test"}',
          },
        ],
      },
    }),
    createTime: '2026-07-28T10:00:01Z',
  }

  return {
    thread: thread({ threadId: targetThreadId }),
    entries: [toolEntry],
    queuedCommands: [],
    toolInvocations: [
      {
        id: 'inv-bash-1',
        modelInvocationId: 'm-1',
        assistantEntryId: 'entry-tool-1',
        callIndex: 0,
        status: 'WAITING_APPROVAL',
        attempt: 1,
        toolCallId: 'call-bash-1',
        toolName: 'bash',
        rendererKey: 'bash',
        environment: null,
        argumentsJson: '{"command":"rm -rf /tmp/test"}',
        approvalJson: JSON.stringify({
          required: true,
          decision: null,
          decisionId: null,
          reason: 'Dangerous shell command execution',
        }),
        resultJson: null,
        errorJson: null,
        createTime: '2026-07-28T10:00:01Z',
        updateTime: '2026-07-28T10:00:01Z',
      },
    ],
    modelInvocation: null,
    modelAttemptFailures: [],
    manualCompaction: { available: true, disabledReason: null },
    stopReceipts: [],
  }
}

function renderPage(initialEntry: string) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/threads/:threadId" element={<ThreadRouteHarness />} />
          <Route path="/chats" element={<div data-testid="chats-page">Chats List</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function ThreadRouteHarness() {
  const navigate = useNavigate()
  return (
    <>
      <button type="button" onClick={() => navigate(`/threads/${OTHER_CHILD_THREAD_ID}`)}>
        打开另一个 Thread
      </button>
      <ThreadWorkspacePage />
    </>
  )
}

function userEntry(text: string, entryId: string): HarnessSessionEntryDTO {
  return {
    entryId,
    sessionId: 'session-child',
    parentEntryId: null,
    entryType: 'MESSAGE',
    payloadJson: JSON.stringify({
      message: { role: 'USER', contents: [{ type: 'text', text }] },
    }),
    createTime: '2026-07-28T10:00:00Z',
  }
}

function snapshotFor(
  targetThreadId: string,
  overrides: Partial<HarnessThreadSnapshotDTO> = {},
): HarnessThreadSnapshotDTO {
  return {
    ...snapshotWithPendingTool(targetThreadId),
    thread: thread({
      threadId: targetThreadId,
      name: targetThreadId === OTHER_CHILD_THREAD_ID ? 'Idle Child' : 'Waiting Parent',
      status: targetThreadId === OTHER_CHILD_THREAD_ID ? 'IDLE' : 'WAITING_APPROVAL',
    }),
    entries: [
      userEntry(
        targetThreadId === OTHER_CHILD_THREAD_ID ? 'child only' : 'parent only',
        `entry-${targetThreadId}`,
      ),
    ],
    queuedCommands: targetThreadId === OTHER_CHILD_THREAD_ID
      ? []
      : [{
          threadId: targetThreadId,
          sequence: '1',
          type: 'USER_MESSAGE',
          state: 'QUEUED',
          idempotencyKey: `queued-${targetThreadId}`,
          payloadJson: JSON.stringify({
            message: { role: 'USER', contents: [{ type: 'text', text: 'parent queued' }] },
          }),
          cancelledAt: null,
          createTime: '2026-07-28T10:00:02Z',
        }],
    toolInvocations: [],
    ...overrides,
  }
}

describe('ThreadWorkspacePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setLocale('zh-CN')
    vi.mocked(agentService.listAgents).mockResolvedValue({ results: [], total: 0 })
    vi.mocked(agentService.listModels).mockResolvedValue({
      pageNumber: 1,
      pageSize: 50,
      totalCount: 1,
      results: [{
        providerName: 'minimax',
        name: 'MiniMax',
        description: null,
        config: {
          limit: { context: 128000, output: 8192 },
          abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
          pricing: {
            currency: 'USD',
            pricingTier: 'default',
            serviceTier: 'standard',
            serviceTierMultiplier: 1,
          },
          defaultVariant: 'default',
          variants: [{ id: 'default' }],
        },
        version: '0',
        createTime: null,
        updateTime: null,
      }],
    })
    vi.mocked(harnessService.acceptThreadCommandBatch).mockResolvedValue([])
    vi.mocked(agentService.listProviders).mockResolvedValue({ results: [], total: 0 })
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
    vi.mocked(harnessService.decideApproval).mockResolvedValue(undefined)
  })

  it('renders invalid ID error state when threadId is not a canonical UUID and does not call harness', () => {
    renderPage('/threads/not-a-valid-uuid')

    expect(screen.getByText('无效的 Thread ID')).toBeInTheDocument()
    expect(harnessService.getThreadSnapshot).not.toHaveBeenCalled()
    expect(chatService.getChat).not.toHaveBeenCalled()
  })

  it('renders error state when harnessService returns error (e.g. 404)', async () => {
    vi.mocked(harnessService.getThreadSnapshot).mockRejectedValue(new Error('404 Not Found'))

    renderPage(`/threads/${CHILD_THREAD_ID}`)

    await waitFor(() => {
      expect(screen.getByText('加载 Thread 失败')).toBeInTheDocument()
    })
    expect(chatService.getChat).not.toHaveBeenCalled()
  })

  it('navigates back to /chats when clicking the back button in error state', async () => {
    const user = userEvent.setup()
    renderPage('/threads/invalid-id')

    const backButton = screen.getByRole('button', { name: '返回列表' })
    await user.click(backButton)

    expect(screen.getByTestId('chats-page')).toBeInTheDocument()
  })

  it('loads thread snapshot via harnessService directly without calling chatService', async () => {
    const snapshot = snapshotWithPendingTool(CHILD_THREAD_ID)
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot)

    renderPage(`/threads/${CHILD_THREAD_ID}`)

    await waitFor(() => {
      expect(screen.getAllByText('Child Worker Thread').length).toBeGreaterThan(0)
    })

    expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(CHILD_THREAD_ID)
    expect(chatService.getChat).not.toHaveBeenCalled()
    expect(chatService.listChatSessions).not.toHaveBeenCalled()
  })

  it('renders ToolApprovalBar in child thread timeline and clicking Allow calls harnessService.decideApproval with child thread ID', async () => {
    const user = userEvent.setup()
    const snapshot = snapshotWithPendingTool(CHILD_THREAD_ID)
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot)

    renderPage(`/threads/${CHILD_THREAD_ID}`)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '允许' })).toBeInTheDocument()
    })

    const allowButton = screen.getByRole('button', { name: '允许' })
    expect(allowButton).toBeInTheDocument()
    expect(allowButton).not.toBeDisabled()

    await user.click(allowButton)

    await waitFor(() => {
      expect(harnessService.decideApproval).toHaveBeenCalledTimes(1)
    })

    const [targetThreadId, invocationId, payload] = vi.mocked(harnessService.decideApproval).mock.calls[0]
    expect(targetThreadId).toBe(CHILD_THREAD_ID)
    expect(invocationId).toBe('inv-bash-1')
    expect(payload.decision).toBe('ALLOW')
    expect(payload.decisionId).toBeDefined()
  })

  it('renders ToolApprovalBar and clicking Deny calls harnessService.decideApproval with DENY for child thread ID', async () => {
    const user = userEvent.setup()
    const snapshot = snapshotWithPendingTool(CHILD_THREAD_ID)
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(snapshot)

    renderPage(`/threads/${CHILD_THREAD_ID}`)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '拒绝' })).toBeInTheDocument()
    })

    const denyButton = screen.getByRole('button', { name: '拒绝' })
    expect(denyButton).toBeInTheDocument()

    await user.click(denyButton)

    await waitFor(() => {
      expect(harnessService.decideApproval).toHaveBeenCalledTimes(1)
    })

    const [targetThreadId, invocationId, payload] = vi.mocked(harnessService.decideApproval).mock.calls[0]
    expect(targetThreadId).toBe(CHILD_THREAD_ID)
    expect(invocationId).toBe('inv-bash-1')
    expect(payload.decision).toBe('DENY')
  })

  it('isolates child thread routing from IssueAgent and Chat owner state without crosstalk', async () => {
    // 测试意图：验证子线程独立路由工作区在没有显式 owner 时，作为只读的 BOUND_THREAD 渲染，
    // 绝不调用 chatService.listChatSessions，并且即使 localStorage 中存在其他 CHAT 或 ISSUE_AGENT 的 PaneTarget，
    // 也严格锁定在 URL 参数指定的 childThreadId 上，子线程路由与 IssueAgent/Chat owner 语义互不混淆。
    const foreignIssueTarget = JSON.stringify({
      kind: 'BOUND_THREAD',
      threadId: 'foreign-thread-999',
    })
    localStorage.setItem('kk-studio.agent-pane-target.ISSUE_AGENT:issue-1:architect:thread-00000000-0000-0000-0000-000000000999', foreignIssueTarget)
    localStorage.setItem('kk-studio.agent-pane-target.CHAT:chat-1:pane-1', foreignIssueTarget)

    const childSnapshot = snapshotWithPendingTool(CHILD_THREAD_ID)
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue(childSnapshot)

    renderPage(`/threads/${CHILD_THREAD_ID}`)

    await waitFor(() => {
      expect(screen.getAllByText('Child Worker Thread').length).toBeGreaterThan(0)
    })

    // 确保请求的是子线程的快照，而不是其他 owner 残留的 foreign thread
    expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(CHILD_THREAD_ID)
    expect(harnessService.getThreadSnapshot).not.toHaveBeenCalledWith('foreign-thread-999')
    expect(chatService.listChatSessions).not.toHaveBeenCalled()
  })

  it('exposes the same interactive composer as a normal Thread and isolates two child snapshots when the route changes', async () => {
    // 子 Thread 工作区与普通 Thread 能力一致（可输入、可设置、可 Goal/Stop）；
    // 切换 child 后旧消息、排队、审批与该 Thread 自己的草稿都不得残留到另一个 child。
    const user = userEvent.setup()
    await saveThreadDraftParts(CHILD_THREAD_ID, [createTextPart('stale child draft')], [])
    vi.mocked(harnessService.getThreadSnapshot).mockImplementation(async (threadId: string) => {
      if (threadId === OTHER_CHILD_THREAD_ID) {
        return snapshotFor(OTHER_CHILD_THREAD_ID)
      }
      return snapshotFor(CHILD_THREAD_ID, {
        toolInvocations: snapshotWithPendingTool(CHILD_THREAD_ID).toolInvocations,
        entries: [
          userEntry('parent only', 'entry-parent'),
          ...snapshotWithPendingTool(CHILD_THREAD_ID).entries,
        ],
      })
    })

    renderPage(`/threads/${CHILD_THREAD_ID}`)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '允许' })).toBeInTheDocument()
    })
    // 子 Thread 工作区提供完整交互面：可编辑 composer、设置与命令表（含 goal / stop）。
    const composer = screen.getByRole('textbox', { name: '给 AI 发送消息' })
    expect(composer).toHaveAttribute('contenteditable', 'true')
    expect(composer).toHaveAttribute('aria-disabled', 'false')
    expect(screen.getByRole('button', { name: '发送消息' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Model 与 Variant' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: '打开命令表' }))
    const palette = await screen.findByRole('listbox', { name: '命令表' })
    expect(within(palette).getByRole('option', { name: /goal/ })).toHaveAttribute('aria-disabled', 'false')
    expect(within(palette).getByRole('option', { name: /stop/ })).toHaveAttribute('aria-disabled', 'false')
    await user.keyboard('{Escape}')

    expect(screen.getByText('parent only')).toBeInTheDocument()
    expect(screen.getByText('parent queued')).toBeInTheDocument()
    expect(screen.getByText('等待审批')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '允许' }))
    await waitFor(() => {
      expect(harnessService.decideApproval).toHaveBeenCalledWith(
        CHILD_THREAD_ID,
        'inv-bash-1',
        expect.objectContaining({ decision: 'ALLOW' }),
      )
    })

    await user.click(screen.getByRole('button', { name: '打开另一个 Thread' }))

    await waitFor(() => {
      expect(screen.getAllByText('Idle Child').length).toBeGreaterThan(0)
    })
    expect(screen.queryByText('parent only')).not.toBeInTheDocument()
    expect(screen.queryByText('parent queued')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()
    expect(screen.queryByText('等待审批')).not.toBeInTheDocument()
    expect(screen.queryByText('stale child draft')).not.toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '给 AI 发送消息' })).toBeInTheDocument()
    expect(screen.getByText('child only')).toBeInTheDocument()
    expect(harnessService.getThreadSnapshot).toHaveBeenCalledWith(OTHER_CHILD_THREAD_ID)
  })

  it('does not expose the inactive relationship tree on a child thread', async () => {
    // 手动关系树已移除：子 Thread 自身不是 root，因此不挂载活跃代理树，也不查询整棵树。
    const rootId = '00000000-0000-4000-8000-000000000010'
    vi.mocked(harnessService.getThreadSnapshot).mockResolvedValue({
      ...snapshotWithPendingTool(CHILD_THREAD_ID),
      thread: thread({ threadId: CHILD_THREAD_ID, parentThreadId: rootId, name: 'Waiting Parent' }),
    })
    vi.mocked(harnessService.getThreadTree).mockResolvedValue([
      {
        threadId: rootId,
        parentThreadId: null,
        name: 'Root Agent',
        agentName: 'assistant',
        model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
        status: 'STOPPED',
        processing: false,
        turnCount: 2,
        toolCallCount: 1,
        outcome: null,
      },
      {
        threadId: CHILD_THREAD_ID,
        parentThreadId: rootId,
        name: 'Waiting Parent',
        agentName: 'coder',
        model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
        status: 'WAITING_APPROVAL',
        processing: true,
        turnCount: 1,
        toolCallCount: 1,
        outcome: null,
      },
      {
        threadId: OTHER_CHILD_THREAD_ID,
        parentThreadId: rootId,
        name: 'Idle Child',
        agentName: 'reviewer',
        model: { providerName: 'anthropic', modelName: 'Claude', variant: 'default' },
        status: 'IDLE',
        processing: false,
        turnCount: 1,
        toolCallCount: 0,
        outcome: 'FAILED',
      },
    ])

    renderPage(`/threads/${CHILD_THREAD_ID}`)
    expect(await screen.findByRole('button', { name: '允许' })).toBeEnabled()
    expect(screen.queryByRole('button', { name: 'Agent 关系' })).not.toBeInTheDocument()
    expect(harnessService.getThreadTree).not.toHaveBeenCalled()
  })
})
