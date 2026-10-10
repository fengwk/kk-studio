import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { interactionService } from '@/shared/api/interaction-service'
import { harnessService } from '@/shared/api/harness-service'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { setLocale } from '@/shared/i18n'
import { projectsApi } from '@/features/projects/projects-api'
import type { IssueDetailDTO } from '@/features/projects/types'
import { InteractionsPage } from './InteractionsPage'

const mockedNavigate = vi.fn()
vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useNavigate: () => mockedNavigate,
  }
})

describe('InteractionsPage', () => {
  let queryClient: QueryClient
  let sockets: FakeWebSocketHarness

  const wrapper = ({ children }: { children: ReactNode }) => (
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
          {children}
        </ApplicationEventProvider>
      </QueryClientProvider>
    </MemoryRouter>
  )

  beforeEach(() => {
    sockets = new FakeWebSocketHarness()
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    mockedNavigate.mockReset()
    vi.restoreAllMocks()
  })

  it('renders empty state when there are no interactions', async () => {
    vi.spyOn(interactionService, 'listInteractions').mockResolvedValue({
      items: [],
      nextCursor: null,
      total: 0,
      freshnessAt: null,
    })

    const { container } = render(<InteractionsPage />, { wrapper })

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '暂无待处理事项' })).toBeInTheDocument()
    })
    expect(
      screen.getByText('需要审批、补充输入或等待环境的请求会显示在这里。'),
    ).toBeInTheDocument()
    expect(container.querySelector('.interactions-empty-icon svg')).not.toBeNull()
    expect(container.querySelector('.state-block')).toBeNull()

    setLocale('en-US')
    expect(await screen.findByRole('heading', { name: 'No pending interactions' })).toBeInTheDocument()
    expect(
      screen.getByText(
        'Requests requiring your approval, input, or environment availability will appear here.',
      ),
    ).toBeInTheDocument()
  })

  it('shows loading feedback and disables refresh until the initial read completes', async () => {
    let resolve!: (value: Awaited<ReturnType<typeof interactionService.listInteractions>>) => void
    vi.spyOn(interactionService, 'listInteractions').mockReturnValue(new Promise((done) => {
      resolve = done
    }))
    const { container } = render(<InteractionsPage />, { wrapper })

    expect(screen.getByRole('status')).toHaveTextContent('正在加载资源')
    expect(container.querySelector('.interactions-loading-spinner.animate-spin')).not.toBeNull()
    expect(container.querySelector('.interactions-empty-state')).toBeNull()
    expect(screen.getByRole('button', { name: '刷新' })).toBeDisabled()
    resolve({ items: [], nextCursor: null, total: 0, freshnessAt: null })
    expect(await screen.findByRole('heading', { name: '暂无待处理事项' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '刷新' })).toBeEnabled()
  })

  it('renders error state and retries on button click', async () => {
    let callCount = 0
    const listSpy = vi.spyOn(interactionService, 'listInteractions').mockImplementation(async () => {
      callCount++
      if (callCount === 1) {
        throw new Error('Network disconnected')
      }
      return { items: [], nextCursor: null, total: 0, freshnessAt: null }
    })

    const { container } = render(<InteractionsPage />, { wrapper })

    await waitFor(() => {
      expect(screen.getByText('Network disconnected')).toBeInTheDocument()
    })
    expect(container.querySelector('.interactions-empty-state')).toBeNull()

    const retryBtn = screen.getByRole('button', { name: '重试' })
    fireEvent.click(retryBtn)

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '暂无待处理事项' })).toBeInTheDocument()
    })
    expect(listSpy).toHaveBeenCalled()
  })

  it('renders items with Chat and Issue sources and supports navigation', async () => {
    vi.spyOn(interactionService, 'listInteractions').mockResolvedValue({
      items: [
        {
          type: 'INPUT',
          interactionId: 'int-1',
          status: 'WAITING_INPUT',
          threadId: 'th-1',
          rootThreadId: 'root-th-1',
          sessionId: 'sess-1',
          owner: {
            type: 'CHAT',
            chatId: 'chat-abc',
            chatTitle: '产品对话',
            issueId: null,
            issueTitle: null,
            agentName: null,
            rootThreadName: '根线程',
          },
          toolCallId: 'call-1',
          toolName: 'ask_user',
          argumentsJson: JSON.stringify({
            questions: [{ question: '你选择哪个？', options: [{ label: '选项A' }] }],
          }),
          approvalJson: null,
          environmentId: null,
          environmentName: null,
          waitingCount: null,
          createTime: '2026-09-27T10:00:00Z',
        },
        {
          type: 'APPROVAL',
          interactionId: 'int-2',
          status: 'WAITING_APPROVAL',
          threadId: 'th-2',
          rootThreadId: 'root-th-2',
          sessionId: 'sess-2',
          owner: {
            type: 'ISSUE_AGENT',
            chatId: null,
            chatTitle: null,
            issueId: 'issue-101',
            issueTitle: '修复缺陷',
            agentName: 'architect',
            rootThreadName: '根线程',
          },
          toolCallId: 'call-2',
          toolName: 'write',
          argumentsJson: '{"path":"main.ts"}',
          approvalJson: JSON.stringify({ required: true, reason: '写代码' }),
          environmentId: null,
          environmentName: null,
          waitingCount: null,
          createTime: '2026-09-27T10:05:00Z',
        },
      ],
      nextCursor: null,
      total: 2,
      freshnessAt: null,
    })

    render(<InteractionsPage />, { wrapper })

    await waitFor(() => {
      expect(screen.getByText('你选择哪个？')).toBeInTheDocument()
      expect(screen.getByText('write')).toBeInTheDocument()
    })
    expect(screen.queryByText('等待审批')).not.toBeInTheDocument()
    expect(document.querySelector('time')).toBeNull()

    // 验证问卷内容存在
    expect(screen.getByText('你选择哪个？')).toBeInTheDocument()
    expect(screen.getByText('选项A')).toBeInTheDocument()

    // 验证审批内容存在
    expect(screen.getByText('write')).toBeInTheDocument()
    expect(screen.getByText('写代码')).toBeInTheDocument()

    // 点击 Chat 来源按钮，验证跳转至目标 Chat 并定位到 thread
    const chatSourceBtn = screen.getByTitle('打开对话')
    fireEvent.click(chatSourceBtn)
    expect(mockedNavigate).toHaveBeenCalledWith('/chats/chat-abc?thread=root-th-1')

    // 测试意图：验证点击 ISSUE_AGENT 来源时通过 query issue 解析 projectId 并精准跳转至 /projects/{id}?issue={id}&thread={threadId}
    vi.spyOn(projectsApi, 'getIssue').mockResolvedValue({
      issue: {
        id: 'issue-101',
        projectId: 'proj-xyz',
        title: 'Fix issue',
        description: '',
        state: 'IN_PROGRESS',
        version: '1',
      },
    } as unknown as IssueDetailDTO)

    const issueSourceBtn = screen.getByTitle('打开任务对话')
    fireEvent.click(issueSourceBtn)
    await waitFor(() => {
      expect(mockedNavigate).toHaveBeenCalledWith('/projects/proj-xyz?issue=issue-101&thread=root-th-2')
    })
    // 审批仍写原始调用并只移除完成的条目，不影响问卷。
    const decision = vi.spyOn(harnessService, 'decideApproval').mockResolvedValue({
      id: 'int-2',
      modelInvocationId: 'model-2',
      assistantEntryId: 'entry-2',
      callIndex: 0,
      status: 'PENDING',
      attempt: 1,
      toolCallId: 'call-2',
      toolName: 'write',
      rendererKey: 'raw',
      environmentId: null,
      requiredEnvironmentId: null,
      waitingForEnvironment: false,
      requiredEnvironmentName: null,
      environmentWaitFreshnessAt: null,
      argumentsJson: '{"path":"main.ts"}',
      approvalJson: '{"decision":"ALLOWED"}',
      resultJson: null,
      errorJson: null,
      createTime: '2026-09-27T10:05:00Z',
      updateTime: '2026-09-27T10:06:00Z',
    })
    fireEvent.click(screen.getByRole('button', { name: '允许', exact: true }))
    await waitFor(() => expect(screen.queryByText('write')).not.toBeInTheDocument())
    expect(decision).toHaveBeenCalledWith('th-2', 'int-2', {
      decision: 'ALLOW', reason: null, decisionId: expect.any(String),
    })
    expect(screen.getByText('你选择哪个？')).toBeInTheDocument()
  })

  it('navigates to /projects when getIssue fails', async () => {
    vi.spyOn(interactionService, 'listInteractions').mockResolvedValue({
      items: [
        {
          type: 'INPUT',
          interactionId: 'int-err',
          status: 'WAITING_INPUT',
          threadId: 'th-err',
          rootThreadId: 'root-th-err',
          sessionId: 'sess-err',
          owner: {
            type: 'ISSUE_AGENT',
            chatId: null,
            chatTitle: null,
            issueId: 'issue-missing',
            issueTitle: null,
            agentName: 'coder',
            rootThreadName: null,
          },
          toolCallId: 'call-err',
          toolName: 'ask_user',
          argumentsJson: '{}',
          approvalJson: null,
          environmentId: null,
          environmentName: null,
          waitingCount: null,
          createTime: '2026-09-27T10:00:00Z',
        },
      ],
      nextCursor: null,
      total: 1,
      freshnessAt: null,
    })
    vi.spyOn(projectsApi, 'getIssue').mockRejectedValue(new Error('not found'))

    render(<InteractionsPage />, { wrapper })
    await waitFor(() => {
      expect(screen.getByTitle('打开任务对话')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByTitle('打开任务对话'))
    await waitFor(() => {
      expect(mockedNavigate).toHaveBeenCalledWith('/projects')
    })
  })

  it('supports refresh and load more, and renders environment waits read-only without raw fallback', async () => {
    let listCount = 0
    const listSpy = vi.spyOn(interactionService, 'listInteractions').mockImplementation(async () => {
      listCount++
      if (listCount === 1) {
        return {
          items: [
            {
              type: 'ENVIRONMENT_WAIT',
              rootThreadId: 'root-th-env',
              createTime: '2026-09-27T10:00:00Z',
              interactionId: null,
              status: null,
              threadId: null,
              sessionId: null,
              toolCallId: null,
              toolName: null,
              argumentsJson: null,
              approvalJson: null,
              environmentId: 'env-1',
              environmentName: 'archlinux',
              waitingCount: 3,
              owner: {
                type: 'CHAT',
                chatId: 'c1',
                chatTitle: null,
                issueId: null,
                issueTitle: null,
                agentName: null,
                rootThreadName: null,
              },
            },
            {
              type: 'APPROVAL',
              interactionId: 'int-appr',
              status: 'WAITING_APPROVAL',
              threadId: 'th-appr',
              rootThreadId: 'root-th-appr',
              sessionId: 'sess-appr',
              owner: {
                type: 'CHAT',
                chatId: 'c1',
                chatTitle: null,
                issueId: null,
                issueTitle: null,
                agentName: null,
                rootThreadName: null,
              },
              toolCallId: 'call-appr',
              toolName: 'shell',
              argumentsJson: '{"cmd":"ls"}',
              approvalJson: JSON.stringify({ required: true }),
              environmentId: null,
              environmentName: null,
              waitingCount: null,
              createTime: '2026-09-27T10:00:00Z',
            },
          ],
          nextCursor: 'cursor-2',
          total: 2,
          freshnessAt: null,
        }
      }
      if (listCount === 2) {
        throw new Error('Next page unavailable')
      }
      return {
        items: [
          {
            type: 'INPUT',
            interactionId: 'int-more',
            status: 'WAITING_INPUT',
            threadId: 'th-more',
            rootThreadId: 'root-th-more',
            sessionId: 'sess-more',
            owner: {
              type: 'CHAT',
              chatId: 'c1',
              chatTitle: null,
              issueId: null,
              issueTitle: null,
              agentName: null,
              rootThreadName: null,
            },
            toolCallId: 'call-more',
            toolName: 'ask_user',
            argumentsJson: JSON.stringify({ questions: [{ question: 'More Q', options: [] }] }),
            approvalJson: null,
            environmentId: null,
            environmentName: null,
            waitingCount: null,
            createTime: '2026-09-27T10:00:00Z',
          },
        ],
        nextCursor: null,
      }
    })

    render(<InteractionsPage />, { wrapper })
    await waitFor(() => {
      // 环境等待卡片只展示等待的环境与调用数，不渲染任何可操作入口或原始载荷回退分支。
      expect(screen.getByText('3 个工具调用等待 archlinux 上线')).toBeInTheDocument()
      expect(screen.getByText('加载更多')).toBeInTheDocument()
    })
    const environmentWaitCard = screen
      .getByText('3 个工具调用等待 archlinux 上线')
      .closest('.interaction-feed-item')
    // 卡片主体不得出现任何可操作入口（来源跳转按钮属于导航，不在主体内）。
    expect(environmentWaitCard?.querySelector('.interaction-item-body button')).toBeNull()

    // 分页失败不能用错误态替换已加载卡片；恢复动作仍可用。
    fireEvent.click(screen.getByRole('button', { name: '加载更多' }))
    expect(await screen.findByText('Next page unavailable')).toBeInTheDocument()
    expect(screen.getByText('3 个工具调用等待 archlinux 上线')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重试' })).toBeEnabled()
    expect(listSpy).toHaveBeenLastCalledWith(null, 'cursor-2', 20)
    fireEvent.click(screen.getByRole('button', { name: '加载更多' }))
    await waitFor(() => {
      expect(screen.getByText('More Q')).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: '加载更多' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    await waitFor(() => expect(screen.queryByText('Next page unavailable')).not.toBeInTheDocument())
    expect(listSpy).toHaveBeenLastCalledWith(null, null, 20)
  })
})
