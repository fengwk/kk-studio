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
        <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
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

  // 测试意图：控制器/插件发起的独立 Agent 任务没有产品页面，其审批仍在交互中心可见、可操作，只标明来源而不提供跳转。
  it('renders headless agent task approvals with a non-navigating source label', async () => {
    vi.spyOn(interactionService, 'listInteractions').mockResolvedValue({
      items: [
        {
          type: 'APPROVAL',
          toolInvocationId: 'int-3',
          threadId: 'th-3',
          rootThreadId: 'th-3',
          sessionId: 'sess-3',
          owner: { kind: 'AGENT_TASK', id: 'th-3', title: 'reporter', link: null },
          toolCallId: 'call-3',
          toolName: 'bash',
          argumentsJson: '{"command":"ls"}',
          approval: { required: true, reason: '列目录' },
          queuedSameFileCount: 0,
          createdAt: 1790503560000,
        },
      ],
      nextCursor: null,
      total: 1,
      freshnessAt: null,
    })

    render(<InteractionsPage />, { wrapper })

    // 归属固定为“类型 · 标题”；AGENT_TASK 没有产品页面，只显示文本。
    expect(await screen.findByText('Agent 任务 · reporter')).toBeInTheDocument()
    expect(screen.getByText('Agent 任务 · reporter').closest('button')).toBeNull()
    expect(screen.getByRole('button', { name: '允许' })).toBeInTheDocument()
    expect(mockedNavigate).not.toHaveBeenCalled()
  })

  // 测试意图：相同 sessionId 的事项属于一个会话组，且组内按新到旧排列并可进入根会话。
  it('groups pending items by session and sorts items newest first', async () => {
    vi.spyOn(interactionService, 'listInteractions').mockResolvedValue({
      items: [
        {
          type: 'APPROVAL', toolInvocationId: 'older', threadId: 'thread-older',
          rootThreadId: 'root-session', sessionId: 'session-1',
          owner: { kind: 'CHAT', id: 'chat-1', title: '同一会话', link: '/chats/chat-1' },
          toolCallId: 'call-older', toolName: 'plugin_a', argumentsJson: '{"value":"old"}',
          approval: { required: true }, queuedSameFileCount: 0, createdAt: 100,
        },
        {
          type: 'APPROVAL', toolInvocationId: 'newer', threadId: 'thread-newer',
          rootThreadId: 'root-session', sessionId: 'session-1',
          owner: { kind: 'CHAT', id: 'chat-1', title: '同一会话', link: '/chats/chat-1' },
          toolCallId: 'call-newer', toolName: 'plugin_b', argumentsJson: '{"value":"new"}',
          approval: { required: true }, queuedSameFileCount: 0, createdAt: 200,
        },
      ],
      nextCursor: null, total: 2, freshnessAt: null,
    })

    const { container } = render(<InteractionsPage />, { wrapper })
    expect(await screen.findByRole('heading', { name: '同一会话' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '会话: 同一会话' })).toHaveAttribute('href', '/threads/root-session')
    expect(container.querySelectorAll('.interactions-session-group')).toHaveLength(1)
    expect(screen.getByText('new')).toBeInTheDocument()
    expect(screen.getByText('old')).toBeInTheDocument()
    expect(Array.from(container.querySelectorAll('.interaction-approval-tool-name')).map((node) => node.textContent))
      .toEqual(['plugin_b', 'plugin_a'])
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
          toolInvocationId: 'int-1',
          threadId: 'th-1',
          rootThreadId: 'root-th-1',
          sessionId: 'sess-1',
          owner: { kind: 'CHAT', id: 'chat-abc', title: '产品对话', link: '/chats/chat-abc' },
          toolCallId: 'call-1',
          toolName: 'ask_user',
          argumentsJson: JSON.stringify({
            questions: [{ question: '你选择哪个？', options: [{ label: '选项A' }] }],
          }),
          createdAt: 1790503200000,
        },
        {
          type: 'APPROVAL',
          toolInvocationId: 'int-2',
          threadId: 'th-2',
          rootThreadId: 'root-th-2',
          sessionId: 'sess-2',
          owner: { kind: 'ISSUE_AGENT', id: 'issue-101:architect', title: '修复缺陷',
            link: '/projects/proj-xyz?issue=issue-101&thread=old#details' },
          toolCallId: 'call-2',
          toolName: 'write',
          argumentsJson: '{"path":"main.ts"}',
          approval: { required: true, reason: '写代码' },
          queuedSameFileCount: 2,
          createdAt: 1790503500000,
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
    expect(screen.getByText('处理后，此文件还有 2 个修改将依次请求审批')).toBeInTheDocument()

    // 点击 Chat 来源按钮，验证跳转至目标 Chat 并定位到 thread
    const chatSourceBtn = screen.getByRole('button', { name: 'Chat · 产品对话' })
    fireEvent.click(chatSourceBtn)
    expect(mockedNavigate).toHaveBeenCalledWith('/chats/chat-abc?thread=root-th-1')

    // 服务器提供的查询参数与片段必须保留，旧 thread 则替换为执行根。
    const issueSourceBtn = screen.getByRole('button', { name: 'Issue · 修复缺陷' })
    fireEvent.click(issueSourceBtn)
    await waitFor(() => {
      expect(mockedNavigate).toHaveBeenCalledWith('/projects/proj-xyz?issue=issue-101&thread=root-th-2#details')
    })
    // 审批仍写原始调用并只移除完成的条目，不影响问卷。
    const decision = vi.spyOn(harnessService, 'decideApproval').mockResolvedValue({
      id: 'int-2',
      threadId: 'thread-1',
      turnStartEntryId: 'turn-1',
      assistantEntryId: 'entry-2',
      callIndex: 0,
      status: 'READY',
      attempt: 1,
      toolCallId: 'call-2',
      queuedBehindToolCallId: null,
      toolName: 'write',
      rendererKey: 'raw',
      environmentId: null,
      requiredEnvironmentId: null,
      waitingForEnvironment: false,
      requiredEnvironmentName: null,
      environmentWaitFreshnessAt: null,
      argumentsJson: '{"path":"main.ts"}',
      approval: { decision: 'ALLOWED' },
      result: null,
      error: null,
      createdAt: 1790503500,
      updatedAt: 1790503560,
    })
    fireEvent.click(screen.getByRole('button', { name: '允许' }))
    await waitFor(() => expect(screen.queryByText('write')).not.toBeInTheDocument())
    expect(decision).toHaveBeenCalledWith('th-2', 'int-2', {
      decision: 'ALLOW', reason: null, decisionId: expect.any(String),
    })
    expect(screen.getByText('你选择哪个？')).toBeInTheDocument()
  })

  // 不可信链接不可成为导航入口；title 仍可作为非交互来源显示。
  it.each(['https://example.com/path', 'javascript:alert(1)', 'relative/path', '//example.com/path', '/\\example.com/path', '/x\n/y'])(
    'does not navigate to an unsafe source link %s', async (link) => {
    vi.spyOn(interactionService, 'listInteractions').mockResolvedValue({
      items: [
        {
          type: 'INPUT',
          toolInvocationId: 'int-err',
          threadId: 'th-err',
          rootThreadId: 'root-th-err',
          sessionId: 'sess-err',
          owner: { kind: 'ISSUE_AGENT', id: 'issue-missing:coder', title: '来源', link },
          toolCallId: 'call-err',
          toolName: 'ask_user',
          argumentsJson: '{}',
          createdAt: 1790503200000,
        },
      ],
      nextCursor: null,
      total: 1,
      freshnessAt: null,
    })
    render(<InteractionsPage />, { wrapper })
    const source = await screen.findByText('Issue · 来源')
    expect(source.closest('button')).toBeNull()
    fireEvent.click(source)
    expect(mockedNavigate).not.toHaveBeenCalled()
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
              createdAt: 1790503200000,
              environmentId: 'env-1',
              environmentName: 'archlinux',
              waitingCount: 3,
              owner: { kind: 'CHAT', id: 'c1', title: null, link: '/chats/c1' },
            },
            {
              type: 'APPROVAL',
              toolInvocationId: 'int-appr',
              threadId: 'th-appr',
              rootThreadId: 'root-th-appr',
              sessionId: 'sess-appr',
              owner: { kind: 'CHAT', id: 'c1', title: null, link: '/chats/c1' },
              toolCallId: 'call-appr',
              toolName: 'shell',
              argumentsJson: '{"cmd":"ls"}',
              approval: { required: true },
              queuedSameFileCount: 0,
              createdAt: 1790503200000,
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
            toolInvocationId: 'int-more',
            threadId: 'th-more',
            rootThreadId: 'root-th-more',
            sessionId: 'sess-more',
            owner: { kind: 'CHAT', id: 'c1', title: null, link: '/chats/c1' },
            toolCallId: 'call-more',
            toolName: 'ask_user',
            argumentsJson: JSON.stringify({ questions: [{ question: 'More Q', options: [] }] }),
            createdAt: 1790503200000,
          },
        ],
        nextCursor: null,
        total: 3,
        freshnessAt: null,
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
