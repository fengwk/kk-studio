import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { interactionService } from '@/shared/api/interaction-service'
import { ApprovalCard } from './ApprovalCard'
import { interactionDraftStore } from './interaction-draft-store'
import { QuestionnaireCard } from './QuestionnaireCard'

describe('Interactions Acceptance Suite (Spec §5)', () => {
  let queryClient: QueryClient

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    interactionDraftStore.resetForTesting()
    vi.restoreAllMocks()
  })

  const multiQuestionArguments = JSON.stringify({
    questions: [
      {
        question: '视频分辨率',
        options: [
          { label: '1080P', recommended: true },
          { label: '720P' },
        ],
      },
      {
        question: '交付产物',
        multiple: true,
        options: [{ label: '成片' }, { label: '字幕' }],
      },
      {
        question: '其他补充说明',
        options: [],
      },
    ],
  })

  // 场景 1: 多题 custom
  it('Scenario 1: 多题 custom — 支持单选、多选+custom、无选项纯custom并正确组装 answers', async () => {
    const submitSpy = vi.spyOn(interactionService, 'submitToolInput').mockResolvedValue({
      threadId: 'th-1',
      interactionId: 'int-multi',
      submissionId: 'sub-1',
      actor: 'local-user',
      acceptedAt: '2026-09-27T00:00:00Z',
      materialized: false,
    })

    render(
      <QuestionnaireCard
        interactionId="int-multi"
        threadId="th-1"
        argumentsJson={multiQuestionArguments}
      />,
      { wrapper },
    )

    // 推荐项不默认选中：1080P 按钮不带 is-selected
    const btn1080 = screen.getByRole('button', { name: /1080P/ })
    expect(btn1080).not.toHaveClass('is-selected')

    // 第 1 题（单选）：选择预置项 1080P
    fireEvent.click(btn1080)
    expect(btn1080).toHaveClass('is-selected')

    // 第 2 题（多选）：选择 "成片" 并追加自定义文本 "工程源文件"
    const btnFilm = screen.getByRole('button', { name: /成片/ })
    fireEvent.click(btnFilm)
    expect(btnFilm).toHaveClass('is-selected')

    const customInputs = screen.getAllByPlaceholderText('输入自定义回答')
    expect(customInputs).toHaveLength(3)

    fireEvent.change(customInputs[1], { target: { value: '工程源文件' } })

    // 第 3 题（无预置选项）：纯自定义回答
    fireEvent.change(customInputs[2], { target: { value: '需在国庆前完成交付' } })

    // 此时所有题目均已完成，点击提交
    const submitBtn = screen.getByRole('button', { name: '提交回答' })
    expect(submitBtn).not.toBeDisabled()

    fireEvent.click(submitBtn)

    await waitFor(() => {
      expect(submitSpy).toHaveBeenCalledTimes(1)
    })

    const submittedBody = submitSpy.mock.calls[0][1]
    expect(submittedBody.threadId).toBe('th-1')
    expect(submittedBody.declined).toBe(false)
    expect(submittedBody.submissionId).toBeDefined()
    // 验证后端禁止的 actor 绝对不存在
    expect((submittedBody as Record<string, unknown>).actor).toBeUndefined()

    // 验证 answers：按题目位置对应的一维数组，多选包含选项与自定义，第三题为纯自定义
    expect(submittedBody.answers).toEqual([
      ['1080P'],
      ['成片', '工程源文件'],
      ['需在国庆前完成交付'],
    ])
  })

  // 场景 2: 冻结重试
  it('Scenario 2: 冻结重试 — 网络错误时冻结 submissionId+answers，点击重试必须精确复用', async () => {
    let callCount = 0
    let firstSubmissionId = ''
    const submitSpy = vi.spyOn(interactionService, 'submitToolInput').mockImplementation(
      async (_id, body) => {
        callCount++
        if (callCount === 1) {
          firstSubmissionId = body.submissionId
          throw new Error('500 Internal Network Failure')
        }
        return {
          threadId: body.threadId,
          interactionId: 'int-freeze',
          submissionId: body.submissionId,
          actor: 'local-user',
          acceptedAt: '2026-09-27T00:00:00Z',
          materialized: false,
        }
      },
    )

    const simpleArg = JSON.stringify({
      questions: [
        {
          question: '确认模式',
          options: [{ label: '快速' }],
        },
      ],
    })

    render(
      <QuestionnaireCard
        interactionId="int-freeze"
        threadId="th-freeze"
        argumentsJson={simpleArg}
      />,
      { wrapper },
    )

    // 选择选项并提交
    fireEvent.click(screen.getByRole('button', { name: /快速/ }))
    fireEvent.click(screen.getByRole('button', { name: '提交回答' }))

    // 首次失败，展示错误及重试按钮
    await waitFor(() => {
      expect(screen.getByText('500 Internal Network Failure')).toBeInTheDocument()
    })
    expect(firstSubmissionId).toBeTruthy()

    // 点击重试
    const retryBtn = screen.getByRole('button', { name: '重试' })
    fireEvent.click(retryBtn)

    await waitFor(() => {
      expect(submitSpy).toHaveBeenCalledTimes(2)
    })

    // 核心断言：第二次调用的 submissionId 与 answers 必须与第一次完全一致（冻结复用）
    const secondCallBody = submitSpy.mock.calls[1][1]
    expect(secondCallBody.submissionId).toBe(firstSubmissionId)
    expect(secondCallBody.answers).toEqual([['快速']])
  })

  // 场景 3: 取消竞争 (409 冲突)
  it('Scenario 3: 取消竞争 — 遇到 409 冲突后不可恢复，保留草稿查看且禁止继续提交', async () => {
    vi.spyOn(interactionService, 'submitToolInput').mockRejectedValue(
      new ApiError('Turn was stopped or conflict', 409),
    )

    const simpleArg = JSON.stringify({
      questions: [
        {
          question: '选择方案',
          options: [{ label: '方案A' }],
        },
      ],
    })

    render(
      <QuestionnaireCard
        interactionId="int-409"
        threadId="th-409"
        argumentsJson={simpleArg}
      />,
      { wrapper },
    )

    const optA = screen.getByRole('button', { name: /方案A/ })
    fireEvent.click(optA)

    fireEvent.click(screen.getByRole('button', { name: '提交回答' }))

    await waitFor(() => {
      expect(screen.getByText(/操作冲突或已取消，不可恢复/)).toBeInTheDocument()
    })

    // 草稿保留作查看：选项依旧高亮选中
    expect(optA).toHaveClass('is-selected')

    // 禁用继续提交/重试
    const submitBtn = screen.getByRole('button', { name: '提交回答' })
    expect(submitBtn).toBeDisabled()
  })

  // 场景 4: 权限结果
  it('Scenario 4: 权限结果 — WAITING_APPROVAL 卡片允许与拒绝正确调用 decideApproval', async () => {
    const decideSpy = vi.spyOn(harnessService, 'decideApproval').mockResolvedValue({
      id: 'inv-app-1',
      modelInvocationId: 'm-1',
      assistantEntryId: 'a-1',
      callIndex: 0,
      status: 'READY',
      attempt: 1,
      toolCallId: 'c-1',
      toolName: 'bash',
      rendererKey: 'tool',
      environmentId: null,
      argumentsJson: '{"command":"rm -rf /"}',
      approvalJson: '{"required":true}',
      resultJson: null,
      errorJson: null,
      createTime: '2026-09-27T00:00:00Z',
      updateTime: '2026-09-27T00:00:00Z',
    })

    render(
      <ApprovalCard
        threadId="th-app"
        invocationId="inv-app-1"
        toolName="bash"
        approvalJson={JSON.stringify({ required: true, reason: '高危操作确认' })}
        argumentsJson='{"command":"rm -rf /"}'
      />,
      { wrapper },
    )

    expect(screen.getByText('bash')).toBeInTheDocument()
    expect(screen.getByText('高危操作确认')).toBeInTheDocument()

    // 点击允许 (ALLOW)
    const allowBtn = screen.getByRole('button', { name: '允许' })
    fireEvent.click(allowBtn)

    await waitFor(() => {
      expect(decideSpy).toHaveBeenCalledTimes(1)
    })

    expect(decideSpy).toHaveBeenCalledWith('th-app', 'inv-app-1', {
      decision: 'ALLOW',
      decisionId: expect.any(String),
      reason: null,
    })
    // 审批请求绝不携带 actor：身份只能来自服务端认证主体。
    expect(decideSpy.mock.calls[0]?.[2]).not.toHaveProperty('actor')
  })

  // 场景 5: 草稿保留
  it('Scenario 5: 草稿保留 — 用户输入草稿不因外部快照刷新或组件重挂载被清除', () => {
    const intId = 'int-preserve'
    const qArg = JSON.stringify({
      questions: [
        {
          question: '你的偏好',
          options: [{ label: '深色模式' }, { label: '浅色模式' }],
        },
      ],
    })

    // 第一次挂载渲染并输入
    const { unmount } = render(
      <QuestionnaireCard
        interactionId={intId}
        threadId="th-preserve"
        argumentsJson={qArg}
      />,
      { wrapper },
    )

    const darkBtn = screen.getByRole('button', { name: /深色模式/ })
    fireEvent.click(darkBtn)
    expect(darkBtn).toHaveClass('is-selected')

    const customInput = screen.getByPlaceholderText('输入自定义回答')
    fireEvent.change(customInput, { target: { value: '自动随系统' } })

    // 模拟快照推送或路由切换触发 unmount
    unmount()

    // 重新挂载组件（使用相同 interactionId 模拟刷新到达）
    render(
      <QuestionnaireCard
        interactionId={intId}
        threadId="th-preserve"
        argumentsJson={qArg}
      />,
      { wrapper },
    )

    // 核心断言：用户的自定义输入依然完好保留在 DOM 中！
    const remountedInput = screen.getByPlaceholderText('输入自定义回答') as HTMLInputElement
    expect(remountedInput.value).toBe('自动随系统')
  })

  it('Scenario 6: 明确拒答 — 拒答请求携带 declined:true 且严禁 answers，网络失败允许同 submissionId 重试', async () => {
    let count = 0
    let firstSubId = ''
    const submitSpy = vi.spyOn(interactionService, 'submitToolInput').mockImplementation(
      async (_id, body) => {
        count++
        if (count === 1) {
          firstSubId = body.submissionId
          throw new Error('Connection reset')
        }
        return {
          threadId: body.threadId,
          interactionId: 'int-decline',
          submissionId: body.submissionId,
          actor: 'local-user',
          acceptedAt: '2026-09-27T00:00:00Z',
          materialized: false,
        }
      },
    )

    render(
      <QuestionnaireCard
        interactionId="int-decline"
        threadId="th-dec"
        argumentsJson={multiQuestionArguments}
      />,
      { wrapper },
    )

    // 点击拒答
    const declineBtn = screen.getByRole('button', { name: '拒绝回答' })
    fireEvent.click(declineBtn)

    await waitFor(() => {
      expect(screen.getByText('Connection reset')).toBeInTheDocument()
    })
    expect(firstSubId).toBeTruthy()

    const firstCallBody = submitSpy.mock.calls[0][1]
    expect(firstCallBody.declined).toBe(true)
    expect(firstCallBody.answers).toBeUndefined()

    // 点击重试
    const retryBtn = screen.getByRole('button', { name: '重试' })
    fireEvent.click(retryBtn)

    await waitFor(() => {
      expect(submitSpy).toHaveBeenCalledTimes(2)
    })

    const secondCallBody = submitSpy.mock.calls[1][1]
    expect(secondCallBody.submissionId).toBe(firstSubId)
    expect(secondCallBody.declined).toBe(true)
    expect(secondCallBody.answers).toBeUndefined()
  })
})
