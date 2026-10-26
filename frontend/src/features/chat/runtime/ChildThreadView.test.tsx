import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ChildThreadView } from '@/features/chat/runtime/ChildThreadView'
import { harnessService } from '@/shared/api/harness-service'
import type { ThreadProjection } from '@/features/chat/runtime/useThreadProjection'
import { extractQueuedCustomMessages } from '@/features/chat/runtime/queued-custom-messages'
import { buildThreadTimeline } from '@/features/chat/runtime/thread-timeline'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/shared/app-events/__tests__/fake-websocket'
import { setLocale } from '@/shared/i18n'
import type { HarnessThreadSnapshotDTO } from '@/shared/api/generated/contracts'

const CHILD_THREAD_ID = '22222222-2222-4333-8444-555555555555'
const ROOT_THREAD_ID = '11111111-2222-4333-8444-555555555555'

function mockChildSnapshot(overrides: Partial<HarnessThreadSnapshotDTO> = {}): HarnessThreadSnapshotDTO {
  return {
    version: '1',
    thread: {
      threadId: CHILD_THREAD_ID,
      parentThreadId: ROOT_THREAD_ID,
      name: 'worker-subagent',
      sessionId: 'session-child',
      headEntryId: 'entry-1',
      rootThreadId: ROOT_THREAD_ID,
      yoloEnabled: false,
      nextCommandSequence: '3',
      version: '1',
      status: 'RUNNING',
      executionControl: 'RUNNABLE',
      branchSettings: {
        agentName: 'worker',
        model: { modelName: 'MiniMax', variant: 'default' },
        environmentName: null,
        goal: null,
      },
      createTime: 1774958400,
      updateTime: 1774958400,
    },
    entries: [
      {
        entryId: 'entry-1',
        sessionId: 'session-child',
        parentEntryId: null,
        entryType: 'CUSTOM_MESSAGE',
        payloadJson: JSON.stringify({
          message: {
            role: 'USER',
            contents: [{ type: 'text', text: '历史初始任务' }],
          },
        }),
        createTime: 1774958400,
        usageCost: null,
      },
    ],
    queuedCommands: [
      {
        threadId: CHILD_THREAD_ID,
        sequence: '2',
        type: 'CUSTOM_MESSAGE',
        state: 'QUEUED',
        idempotencyKey: 'cmd-supp-1',
        payloadJson: JSON.stringify({
          message: {
            role: 'USER',
            contents: [{ type: 'text', text: '请优先补充边界测试用例' }],
          },
        }),
        cancelledAt: null,
        createTime: 1774958450,
      },
    ],
    modelInvocation: null,
    toolInvocations: [],
    modelAttemptFailures: [],
    stopReceipts: [],
    unconfirmedExecutions: [],
    manualCompaction: { available: false, disabledReason: null },
    ...overrides,
  }
}

function createMockProjection(snapshot: HarnessThreadSnapshotDTO): ThreadProjection {
  const queuedCustomMessages = extractQueuedCustomMessages(snapshot.queuedCommands)
  return {
    threadId: snapshot.thread.threadId,
    sessionId: snapshot.thread.sessionId,
    agents: [],
    models: [],
    thread: snapshot.thread,
    bound: true,
    isRoot: false,
    title: snapshot.thread.name,
    timeline: buildThreadTimeline(snapshot.entries, snapshot.queuedCommands, snapshot.toolInvocations),
    events: [],
    runtimeLabels: { environmentName: null, contextWindow: undefined },
    branchUsage: null,
    working: snapshot.thread.status !== 'IDLE',
    entries: snapshot.entries,
    messagesLoading: false,
    messagesError: null,
    bodyRef: { current: null },
    queuedCommands: snapshot.queuedCommands,
    queuedCustomMessages,
    manualCompaction: snapshot.manualCompaction,
    stopReceipts: snapshot.stopReceipts,
    unconfirmedExecutions: snapshot.unconfirmedExecutions,
    modelInvocation: snapshot.modelInvocation,
    toolInvocations: snapshot.toolInvocations,
    modelAttemptFailures: snapshot.modelAttemptFailures,
    snapshotQuery: {
      isSuccess: true,
      isLoading: false,
      error: null,
      refetch: vi.fn(),
    } as never,
  }
}

describe('ChildThreadView 只读子观察面板 QUEUED CUSTOM_MESSAGE', () => {
  let queryClient: QueryClient
  let sockets: FakeWebSocketHarness

  beforeEach(() => {
    sockets = new FakeWebSocketHarness()
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    act(() => setLocale('zh-CN'))
  })

  afterEach(() => {
    cleanup()
    queryClient.clear()
    vi.restoreAllMocks()
    act(() => setLocale('zh-CN'))
  })

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider url="ws://test/events/v1" socketFactory={sockets.factory}>
        <MemoryRouter>
          {children}
        </MemoryRouter>
      </ApplicationEventProvider>
    </QueryClientProvider>
  )

  it('在子观察面板正确接线并展示只读待处理补充要求，包含实际文本与稳定 sequence', () => {
    const snapshot = mockChildSnapshot()
    const projection = createMockProjection(snapshot)

    render(
      <ChildThreadView
        threadId={CHILD_THREAD_ID}
        projection={projection}
        environments={[]}
      />,
      { wrapper },
    )

    // 验证子观察面板只读标识
    expect(screen.getByText('只读查看')).toBeInTheDocument()

    // 验证待处理补充要求展示
    expect(screen.getByText('待处理补充要求')).toBeInTheDocument()
    expect(screen.getByText('请优先补充边界测试用例')).toBeInTheDocument()

    // 验证 sequence 稳定标识
    const item = screen.getByText('请优先补充边界测试用例').closest('li')
    expect(item).toHaveAttribute('data-sequence', '2')

    // 验证只读约束：不渲染 Composer / 输入框 / 写删重排按钮
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /send|发送/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /delete|remove|cancel|删除|取消/i })).not.toBeInTheDocument()
  })

  it('支持中英语言切换（zh-CN 待处理补充要求 / en-US Pending supplementary requirement）', () => {
    const snapshot = mockChildSnapshot()
    const projection = createMockProjection(snapshot)

    render(
      <ChildThreadView
        threadId={CHILD_THREAD_ID}
        projection={projection}
        environments={[]}
      />,
      { wrapper },
    )

    expect(screen.getByText('待处理补充要求')).toBeInTheDocument()

    act(() => setLocale('en-US'))
    expect(screen.getByText('Pending supplementary requirement')).toBeInTheDocument()
    expect(screen.getByText('请优先补充边界测试用例')).toBeInTheDocument()
  })

  it('当命令被后端应用后，快照中不再为 QUEUED 时补充要求移除，历史沿用 durable 不另复制', () => {
    const initialSnapshot = mockChildSnapshot()
    const initialProjection = createMockProjection(initialSnapshot)

    const { rerender } = render(
      <ChildThreadView
        threadId={CHILD_THREAD_ID}
        projection={initialProjection}
        environments={[]}
      />,
      { wrapper },
    )

    expect(screen.getByText('待处理补充要求')).toBeInTheDocument()

    // 模拟后端应用完毕：queuedCommands 中状态变为 APPLIED 或移除，entries 中新增物化的 durable CUSTOM_MESSAGE
    const appliedSnapshot = mockChildSnapshot({
      queuedCommands: [
        {
          threadId: CHILD_THREAD_ID,
          sequence: '2',
          type: 'CUSTOM_MESSAGE',
          state: 'APPLIED',
          idempotencyKey: 'cmd-supp-1',
          payloadJson: JSON.stringify({
            message: {
              role: 'USER',
              contents: [{ type: 'text', text: '请优先补充边界测试用例' }],
            },
          }),
          cancelledAt: null,
          createTime: 1774958450,
        },
      ],
      entries: [
        initialSnapshot.entries[0]!,
        {
          entryId: 'entry-2',
          sessionId: 'session-child',
          parentEntryId: 'entry-1',
          entryType: 'CUSTOM_MESSAGE',
          payloadJson: JSON.stringify({
            message: {
              role: 'USER',
              contents: [{ type: 'text', text: '请优先补充边界测试用例' }],
            },
          }),
          createTime: 1774958490,
          usageCost: null,
        },
      ],
    })
    const appliedProjection = createMockProjection(appliedSnapshot)

    rerender(
      <ChildThreadView
        threadId={CHILD_THREAD_ID}
        projection={appliedProjection}
        environments={[]}
      />,
    )

    // 待处理补充要求区域自动移除
    expect(screen.queryByText('待处理补充要求')).not.toBeInTheDocument()
    expect(screen.getAllByText('请优先补充边界测试用例')).toHaveLength(1)
    expect(screen.getByText('请优先补充边界测试用例').closest('.thread-block-user')).not.toBeNull()
  })

  it('lets a stopped child thread abandon its own unconfirmed execution', async () => {
    // 测试意图：未确认退出事实随投影出现在可查看的子代理面板；
    // 放弃请求使用子 Thread 身份与它自己的 expectedVersion，而不是根或展示层猜测。
    const user = userEvent.setup()
    const base = mockChildSnapshot()
    const stopped = {
      ...base,
      version: '7',
      thread: {
        ...base.thread,
        version: '7',
        executionControl: 'STOPPED',
      },
      unconfirmedExecutions: [{
        executionId: 'execution-child',
        invocationId: 'invocation-child',
        attempt: 2,
        kind: 'TOOL',
        registeredAt: 1767225600000,
      }],
    }
    const projection = createMockProjection(stopped)
    const abandon = vi.spyOn(harnessService, 'abandonExecution').mockResolvedValue(stopped)

    render(
      <ChildThreadView
        threadId={CHILD_THREAD_ID}
        projection={projection}
        environments={[]}
      />,
      { wrapper },
    )

    expect(screen.getByRole('heading', { name: '未确认退出执行' })).toBeInTheDocument()
    expect(screen.getByText('执行 execution-child')).toBeInTheDocument()
    await user.click(screen.getByRole('button', {
      name: '放弃等待 TOOL 第 2 次尝试',
    }))
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('执行 execution-child 将不再等待退出确认')
    await user.click(within(dialog).getByRole('button', { name: '放弃等待' }))

    await waitFor(() => expect(abandon).toHaveBeenCalledWith(
      CHILD_THREAD_ID,
      'execution-child',
      { expectedVersion: '7' },
    ))
  })
})
