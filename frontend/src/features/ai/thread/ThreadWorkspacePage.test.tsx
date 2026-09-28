import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
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
import { setLocale } from '@/shared/i18n'

const CHILD_THREAD_ID = '00000000-0000-0000-0000-000000000999'

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
    acceptCommandBatch: vi.fn(),
    compactThread: vi.fn(),
    setThreadYolo: vi.fn(),
    stopThread: vi.fn(),
  },
}))

function thread(overrides: Partial<HarnessThreadDTO> = {}): HarnessThreadDTO {
  return {
    name: 'Child Worker Thread',
    threadId: CHILD_THREAD_ID,
    sessionId: 'session-child',
    headEntryId: 'head-1',
    parentThreadId: null,
    yoloEnabled: false,
    nextCommandSequence: '1',
    status: 'WAITING_TOOL',
    branchSettings: {
      agentName: 'assistant',
      model: {
        providerName: 'minimax',
        modelName: 'MiniMax',
        variant: 'default',
      },
      environmentName: null,
    },
    createdAt: '2026-07-28T10:00:00Z',
    lastActivityAt: '2026-07-28T10:00:00Z',
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
          <Route path="/threads/:threadId" element={<ThreadWorkspacePage />} />
          <Route path="/chats" element={<div data-testid="chats-page">Chats List</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ThreadWorkspacePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setLocale('zh-CN')
    vi.mocked(agentService.listAgents).mockResolvedValue({ results: [], total: 0 })
    vi.mocked(agentService.listModels).mockResolvedValue({ results: [], total: 0 })
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
})
