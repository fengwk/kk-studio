import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { InteractionsPage } from '@/app/pages/InteractionsPage'
import { ApiError } from '@/shared/api/client'
import type { InteractionDTO, InteractionPageDTO } from '@/shared/api/generated/contracts'
import { harnessService } from '@/shared/api/harness-service'
import { interactionService } from '@/shared/api/interaction-service'
import { ApplicationEventProvider } from '@/shared/app-events'
import { queryKeys } from '@/shared/lib/query-keys'
import { RootInteractionList } from '@/features/thread/runtime/panes/RootThreadControlArea'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
import { interactionDraftStore } from './interaction-draft-store'
import { RootInteractionsProvider } from './root-interactions'

/**
 * 409 冲突后的回读闭环：两个宿主界面（待处理页与执行根控制区）都用同一份列表事实决定
 * 该事项是「已由其他端处理」（回读后不在待处理列表，需要移除）还是「仍待处理」（解除锁定，
 * 换成新的提交身份重新确认，不重放被拒的旧决定）。
 */
const ROOT_THREAD_ID = 'th-root'

const QUESTION_ARGUMENTS = JSON.stringify({
  questions: [
    {
      question: '选择迁移窗口',
      options: [{ label: '本周六凌晨' }, { label: '本周日凌晨' }],
    },
  ],
})

function approvalInteraction(invocationId: string): InteractionDTO {
  return {
    type: 'APPROVAL',
    toolInvocationId: invocationId,
    threadId: ROOT_THREAD_ID,
    rootThreadId: ROOT_THREAD_ID,
    sessionId: 'sess-root',
    owner: { kind: 'CHAT', id: 'chat-1', title: 'Chat' },
    toolCallId: `call-${invocationId}`,
    toolName: 'bash',
    argumentsJson: '{"command":"ls"}',
    approval: { required: true },
    queuedSameFileCount: 0,
    createdAt: 1790467200000,
  }
}

function inputInteraction(invocationId: string): InteractionDTO {
  return {
    type: 'INPUT',
    toolInvocationId: invocationId,
    threadId: ROOT_THREAD_ID,
    rootThreadId: ROOT_THREAD_ID,
    sessionId: 'sess-root',
    owner: { kind: 'CHAT', id: 'chat-1', title: 'Chat' },
    toolCallId: `call-${invocationId}`,
    toolName: 'ask_user',
    argumentsJson: QUESTION_ARGUMENTS,
    createdAt: 1790467200000,
  }
}

/**
 * 问卷卡在列表异步读到后才出现，它对草稿 store 的订阅在提交后的 effect 里建立；订阅建立前的选择已写入
 * store，但要等订阅建立后才重新渲染。先等「提交回答」变为可用，再像用户一样点击提交。
 */
async function chooseOptionAndWaitForSubmit(label: RegExp) {
  fireEvent.click(await screen.findByRole('radio', { name: label }))
  await waitFor(() => expect(screen.getByRole('button', { name: '提交回答' })).toBeEnabled())
}

function page(items: InteractionDTO[]): InteractionPageDTO {
  return { items, nextCursor: null, total: items.length, freshnessAt: null }
}

function submitted(toolInvocationId: string, submissionId: string) {
  return {
    threadId: ROOT_THREAD_ID,
    toolInvocationId,
    submissionId,
    actor: 'local-user',
    acceptedAt: 1790467200000,
    materialized: false,
  }
}

describe('409 冲突后的待处理事实回读', () => {
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

  /** 执行根控制区的待处理列表；与 RootThreadControlArea 内是同一个组件。 */
  const rootSurface = () => (
    <RootInteractionsProvider rootThreadId={ROOT_THREAD_ID} approvalsVisible>
      <RootInteractionList />
    </RootInteractionsProvider>
  )

  beforeEach(() => {
    sockets = new FakeWebSocketHarness()
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    interactionDraftStore.resetForTesting()
    vi.restoreAllMocks()
  })

  it('待处理页：审批 409 且回读已无该事项时刷新交互与 Thread 并从待处理移除', async () => {
    let pending = [approvalInteraction('inv-gone')]
    const listSpy = vi.spyOn(interactionService, 'listInteractions')
      .mockImplementation(async () => page(pending))
    const decideSpy = vi.spyOn(harnessService, 'decideApproval')
      .mockRejectedValue(new ApiError('not waiting for approval', 409, 'APPROVAL_NOT_APPLICABLE'))
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    render(<InteractionsPage />, { wrapper })

    const allow = await screen.findByRole('button', { name: '允许' })
    const callsBefore = listSpy.mock.calls.length
    // 另一端先一步处理了同一调用：回读得到的第一页不再包含它。
    pending = []
    fireEvent.click(allow)

    expect(await screen.findByRole('heading', { name: '暂无待处理事项' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '允许' })).toBeNull()
    await waitFor(() => expect(listSpy.mock.calls.length).toBeGreaterThan(callsBefore))
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: queryKeys.interactions.all })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.threads.snapshot(ROOT_THREAD_ID),
      exact: true,
      refetchType: 'none',
    })
    // 已处置的事项不再重放旧决定。
    expect(decideSpy).toHaveBeenCalledTimes(1)
  })

  it('待处理页：审批 409 且回读仍待处理时解除锁定并用新决定身份重新确认', async () => {
    const pending = [approvalInteraction('inv-live')]
    vi.spyOn(interactionService, 'listInteractions')
      .mockImplementation(async () => page(pending))
    const decideSpy = vi.spyOn(harnessService, 'decideApproval')
      .mockRejectedValueOnce(new ApiError('decision mismatch', 409, 'APPROVAL_DECISION_MISMATCH'))
      .mockResolvedValue({} as never)
    render(<InteractionsPage />, { wrapper })

    fireEvent.click(await screen.findByRole('button', { name: '允许' }))
    expect(await screen.findByText('此事项的最新状态已重新载入，请重新确认后提交。'))
      .toBeInTheDocument()

    // 回读证明事项仍待处理：卡片回到可操作状态，没有只读围栏。
    expect(screen.getByRole('button', { name: '允许' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '拒绝' })).toBeEnabled()
    expect(screen.getByRole('textbox', { name: '拒绝原因（可选）' })).toBeEnabled()
    expect(document.querySelector('.is-non-recoverable')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '允许' }))
    await waitFor(() => expect(decideSpy).toHaveBeenCalledTimes(2))
    expect(decideSpy.mock.calls[1]![2].decisionId)
      .not.toBe(decideSpy.mock.calls[0]![2].decisionId)
  })

  it('待处理页：问卷 409 且回读仍待处理时保留答案并用新提交身份重新提交', async () => {
    const pending = [inputInteraction('int-live')]
    vi.spyOn(interactionService, 'listInteractions')
      .mockImplementation(async () => page(pending))
    const submitSpy = vi.spyOn(interactionService, 'submitToolInput')
      .mockRejectedValueOnce(new ApiError('submission mismatch', 409, 'INPUT_SUBMISSION_MISMATCH'))
      .mockResolvedValue(submitted('int-live', 'sub-second'))
    render(<InteractionsPage />, { wrapper })

    await chooseOptionAndWaitForSubmit(/本周六凌晨/)
    fireEvent.click(screen.getByRole('button', { name: '提交回答' }))
    expect(await screen.findByText('此事项的最新状态已重新载入，请重新确认后提交。'))
      .toBeInTheDocument()

    // 草稿保留作重新确认，重试入口不再复用被拒的冻结身份。
    expect(screen.getByRole('radio', { name: /本周六凌晨/ })).toHaveAttribute('aria-checked', 'true')
    expect(screen.queryByRole('button', { name: '重试' })).toBeNull()
    const submit = screen.getByRole('button', { name: '提交回答' })
    expect(submit).toBeEnabled()

    fireEvent.click(submit)
    await waitFor(() => expect(submitSpy).toHaveBeenCalledTimes(2))
    const ids = submitSpy.mock.calls.map((call) => call[2].submissionId)
    expect(ids[1]).not.toBe(ids[0])
    expect(submitSpy.mock.calls[1]![2]).toMatchObject({
      type: 'ANSWER',
      answers: [['本周六凌晨']],
    })
  })

  it('执行根控制区：问卷 409 且回读已无该事项时从待处理区移除', async () => {
    let pending = [inputInteraction('int-root-gone')]
    const listSpy = vi.spyOn(interactionService, 'listInteractions')
      .mockImplementation(async () => page(pending))
    vi.spyOn(interactionService, 'submitToolInput')
      .mockRejectedValue(new ApiError('not applicable', 409, 'INPUT_SUBMISSION_NOT_APPLICABLE'))
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    render(rootSurface(), { wrapper })

    await chooseOptionAndWaitForSubmit(/本周六凌晨/)
    const callsBefore = listSpy.mock.calls.length
    pending = []
    fireEvent.click(screen.getByRole('button', { name: '提交回答' }))

    await waitFor(() => expect(screen.queryByRole('radio')).toBeNull())
    expect(screen.queryByRole('button', { name: '提交回答' })).toBeNull()
    await waitFor(() => expect(listSpy.mock.calls.length).toBeGreaterThan(callsBefore))
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: queryKeys.interactions.all })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.threads.snapshot(ROOT_THREAD_ID),
      exact: true,
      refetchType: 'none',
    })
  })

  it('执行根控制区：审批 409 且回读仍待处理时卡片保持可操作', async () => {
    const pending = [approvalInteraction('inv-root-live')]
    vi.spyOn(interactionService, 'listInteractions')
      .mockImplementation(async () => page(pending))
    vi.spyOn(harnessService, 'decideApproval')
      .mockRejectedValue(new ApiError('still waiting', 409, 'APPROVAL_DECISION_MISMATCH'))
    render(rootSurface(), { wrapper })

    fireEvent.click(await screen.findByRole('button', { name: '允许' }))
    expect(await screen.findByText('此事项的最新状态已重新载入，请重新确认后提交。'))
      .toBeInTheDocument()
    expect(screen.getByRole('button', { name: '允许' })).toBeEnabled()
    expect(screen.getByRole('textbox', { name: '拒绝原因（可选）' })).toBeEnabled()
  })

  it('待处理页：回读本身失败时保留只读围栏并提示刷新', async () => {
    let calls = 0
    vi.spyOn(interactionService, 'listInteractions').mockImplementation(async () => {
      calls += 1
      if (calls > 1) {
        throw new Error('interactions unavailable')
      }
      return page([approvalInteraction('inv-offline')])
    })
    vi.spyOn(harnessService, 'decideApproval')
      .mockRejectedValue(new ApiError('not applicable', 409, 'APPROVAL_NOT_APPLICABLE'))
    render(<InteractionsPage />, { wrapper })

    fireEvent.click(await screen.findByRole('button', { name: '允许' }))
    // 没有新事实可判断时不猜结果：保持只读围栏，由用户刷新页重读。
    expect(await screen.findByText('此事项已变更，请刷新查看最新状态。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '允许' })).toBeDisabled()
    expect(document.querySelector('.is-non-recoverable')).not.toBeNull()
  })
})
