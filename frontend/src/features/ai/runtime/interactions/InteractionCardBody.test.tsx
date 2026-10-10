import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { InteractionCardBody } from './InteractionCardBody'
import { interactionDraftStore } from './interaction-draft-store'
import {
  LONG_ANSWER,
  LONG_OPTION_COUNT,
  LONG_QUESTION_COUNT,
  LONG_QUESTIONNAIRE,
  LONG_QUESTIONNAIRE_JSON,
} from '@/test-support/resources/long-questionnaire'
import type {
  EnvironmentWaitInteractionDTO,
  InputInteractionDTO,
} from '@/shared/api/contracts/ai-interaction'
import { interactionService } from '@/shared/api/interaction-service'
import { harnessService } from '@/shared/api/harness-service'

beforeEach(() => {
  interactionDraftStore.resetForTesting()
  vi.restoreAllMocks()
})

it('renders environment waits read-only without approval or questionnaire actions', () => {
  const submit = vi.spyOn(interactionService, 'submitToolInput')
  const approve = vi.spyOn(harnessService, 'decideApproval')
  const item: EnvironmentWaitInteractionDTO = {
    type: 'ENVIRONMENT_WAIT', rootThreadId: 'root', environmentId: 'env',
    environmentName: 'archlinux', waitingCount: 2, createTime: 1780000000,
    owner: { type: 'CHAT', chatId: 'chat', chatTitle: 'Chat', issueId: null,
      issueTitle: null, agentName: null, rootThreadName: 'Root' },
    interactionId: null, status: null, threadId: null, sessionId: null,
    toolCallId: null, toolName: null, argumentsJson: null, approvalJson: null,
  }
  render(<InteractionCardBody item={item} />)
  // 环境等待只有聚合身份：展示等待的环境与调用数，不出现任何可操作入口。
  expect(screen.getByRole('status')).toHaveTextContent('2 个工具调用等待 archlinux 上线')
  expect(screen.queryAllByRole('button')).toHaveLength(0)
  expect(submit).not.toHaveBeenCalled()
  expect(approve).not.toHaveBeenCalled()
})

it('renders and submits a long questionnaire without truncating questions, options or answers', async () => {
  const submit = vi.spyOn(interactionService, 'submitToolInput').mockResolvedValue({
    threadId: 'th-1', interactionId: 'int-long', submissionId: 'sub-long',
    actor: 'local-user', acceptedAt: '2026-09-27T00:00:00Z', materialized: false,
  })
  const item: InputInteractionDTO = {
    type: 'INPUT', status: 'WAITING_INPUT', rootThreadId: 'root',
    interactionId: 'int-long', threadId: 'th-1', sessionId: 's-1',
    toolCallId: 'call-1', toolName: 'ask_user',
    argumentsJson: LONG_QUESTIONNAIRE_JSON,
    approvalJson: null, environmentId: null, environmentName: null, waitingCount: null,
    createTime: 1780000000,
    owner: { type: 'CHAT', chatId: 'chat', chatTitle: 'Chat', issueId: null,
      issueTitle: null, agentName: null, rootThreadName: 'Root' },
  }
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <InteractionCardBody item={item} />
    </QueryClientProvider>,
  )

  // 超过旧业务上限的题数与文本都完整渲染：每题一个自定义输入，问题原文逐字保留。
  const longQuestion = LONG_QUESTIONNAIRE.questions[0].question
  const inputs = screen.getAllByPlaceholderText('输入自定义回答')
  expect(screen.getAllByText(longQuestion)).toHaveLength(LONG_QUESTION_COUNT)
  expect(inputs).toHaveLength(LONG_QUESTION_COUNT)
  const options = LONG_QUESTIONNAIRE.questions[0].options
  expect(screen.getAllByText(options[LONG_OPTION_COUNT - 1].label)).toHaveLength(LONG_QUESTION_COUNT)
  expect(screen.getAllByText(options[0].description)).toHaveLength(LONG_QUESTION_COUNT * LONG_OPTION_COUNT)

  // 每题仍派发真实 change 事件，批量提交渲染，避免每次输入都重复遍历整张长问卷。
  act(() => {
    for (const input of inputs) {
      fireEvent.change(input, { target: { value: LONG_ANSWER } })
    }
  })
  for (const input of inputs) {
    expect(input).toHaveValue(LONG_ANSWER)
  }

  const submitBtn = screen.getByRole('button', { name: '提交回答' })
  expect(submitBtn).not.toBeDisabled()
  fireEvent.click(submitBtn)

  await waitFor(() => {
    expect(submit).toHaveBeenCalledTimes(1)
  })
  // 长答案原样提交、不截断、不静默降级。
  const body = submit.mock.calls[0][1]
  expect(body.answers).toHaveLength(LONG_QUESTION_COUNT)
  expect(body.answers).toEqual(Array.from({ length: LONG_QUESTION_COUNT }, () => [LONG_ANSWER]))
})
