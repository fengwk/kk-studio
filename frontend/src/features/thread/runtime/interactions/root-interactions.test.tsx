import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { InteractionDTO, InteractionPageDTO } from '@/shared/api/generated/contracts'
import { interactionService } from '@/shared/api/interaction-service'
import { ApplicationEventProvider } from '@/shared/app-events'
import type { ToolDialogueMessage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import { ToolMessageBlock } from '@/features/thread/runtime/thread-panel/messages/ToolMessageBlock'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
import { visibleApprovalInvocationIds } from './approval-review-context'
import { RootInteractionsProvider } from './root-interactions'

const review = {
  path: '/src/Hidden.tsx',
  diff: '-old\n+new',
  sha256: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
}

/** 待审批 edit：同一调用既有冻结审查，也需要和审批卡去重。 */
function pendingEdit(invocationId: string): ToolDialogueMessage {
  return {
    id: `call-${invocationId}`,
    role: 'tool',
    phase: 'call',
    subjectEntryId: 'asst-hidden',
    toolCallId: `call-${invocationId}`,
    toolName: 'edit',
    rendererKey: 'edit',
    arguments: JSON.stringify({ path: '/src/Hidden.tsx' }),
    contents: [],
    createdAt: 1790467200000,
    status: 'done',
    invocationId,
    invocationStatus: 'WAITING_APPROVAL',
    approval: {
      required: true,
      decision: null,
      decisionId: null,
      reason: null,
      editReview: review,
    },
    editReview: review,
  }
}

function approvalInteraction(invocationId: string): InteractionDTO {
  return {
    type: 'APPROVAL',
    toolInvocationId: invocationId,
    threadId: 'thread-root',
    rootThreadId: 'thread-root',
    sessionId: 'sess-root',
    owner: { kind: 'CHAT_SPACE', id: 'space-1', title: 'Workspace' },
    toolCallId: `call-${invocationId}`,
    toolName: 'edit',
    argumentsJson: JSON.stringify({ path: '/src/Hidden.tsx' }),
    approval: { required: true, editReview: review },
    queuedSameFileCount: 0,
    createdAt: 1790467200000,
  }
}

describe('执行根 pane 的审批卡审查位', () => {
  let queryClient: QueryClient
  let sockets: FakeWebSocketHarness

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
        {children}
      </ApplicationEventProvider>
    </QueryClientProvider>
  )

  beforeEach(() => {
    sockets = new FakeWebSocketHarness()
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    vi.restoreAllMocks()
  })

  function page(items: InteractionDTO[]): InteractionPageDTO {
    return { items, nextCursor: null, total: items.length, freshnessAt: null }
  }

  it('只有会渲染审批卡的调用 ID 进入审查位，问卷与环境等待不算', () => {
    const input: InteractionDTO = {
      type: 'INPUT',
      toolInvocationId: 'inv-input',
      threadId: 'thread-root',
      rootThreadId: 'thread-root',
      sessionId: 'sess-root',
      owner: { kind: 'CHAT_SPACE', id: 'space-1', title: 'Workspace' },
      toolCallId: 'call-inv-input',
      toolName: 'ask_user',
      argumentsJson: '{"questions":[]}',
      createdAt: 1790467200000,
    }
    const environmentWait: InteractionDTO = {
      type: 'ENVIRONMENT_WAIT',
      rootThreadId: 'thread-root',
      owner: { kind: 'CHAT_SPACE', id: 'space-1', title: 'Workspace' },
      environmentId: 'env-1',
      environmentName: 'archlinux',
      waitingCount: 1,
      createdAt: 1790467200000,
    }

    expect(visibleApprovalInvocationIds([approvalInteraction('inv-a'), input, environmentWait]))
      .toEqual(['inv-a'])
  })

  it('审批卡在 pane 内渲染时时间线让出 diff，审批区隐藏时仍保留 diff', async () => {
    vi.spyOn(interactionService, 'listInteractions')
      .mockResolvedValue(page([approvalInteraction('inv-visible')]))

    const { rerender } = render(
      <RootInteractionsProvider rootThreadId="thread-root" approvalsVisible>
        <ToolMessageBlock message={pendingEdit('inv-visible')} />
      </RootInteractionsProvider>,
      { wrapper },
    )

    await waitFor(() => expect(document.querySelector('.thread-tool-state-label')?.textContent)
      .toContain('等待审批'))
    // 审批区可见：时间线不再重复冻结审查材料（diff 只在审批卡里）。
    await waitFor(() =>
      expect(document.querySelector('.thread-tool-preview-body')).toBeNull(),
    )

    // Debug 主视图接管时根控制区仍在 DOM 里但不可见：不能因此吞掉时间线材料。
    rerender(
      <RootInteractionsProvider rootThreadId="thread-root" approvalsVisible={false}>
        <ToolMessageBlock message={pendingEdit('inv-visible')} />
      </RootInteractionsProvider>,
    )
    expect(document.querySelector('.thread-tool-preview-body.is-edit')).toBeInTheDocument()
  })

  it('分页未加载到的审批调用不在审查位内，工具卡保留 diff', async () => {
    // 首页只返回另一条调用：本调用的审批卡尚未加载，工具卡必须继续展示 diff。
    vi.spyOn(interactionService, 'listInteractions')
      .mockResolvedValue(page([approvalInteraction('inv-other')]))

    render(
      <RootInteractionsProvider rootThreadId="thread-root" approvalsVisible>
        <ToolMessageBlock message={pendingEdit('inv-unloaded')} />
      </RootInteractionsProvider>,
      { wrapper },
    )

    expect(document.querySelector('.thread-tool-preview-body.is-edit')).toBeInTheDocument()
  })
})
