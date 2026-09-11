import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ToolApprovalBar } from '@/features/ai/runtime/thread-panel/messages/ToolApprovalBar'
import type { ToolRenderContext } from '@/features/ai/runtime/thread-panel/messages/tool-message-view'
import type { ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

function message(invocationId: string): ToolDialogueMessage {
  return {
    id: `call-${invocationId}`,
    role: 'tool',
    subjectEntryId: null,
    createdAt: null,
    status: 'streaming',
    phase: 'call',
    text: '',
    toolCallId: `call-${invocationId}`,
    toolName: 'bash',
    rendererKey: 'bash',
    arguments: '',
    attachments: [],
    invocationId,
    approval: { required: true, decision: null, decisionId: null, reason: null },
  }
}

function context(approvalPending = false): ToolRenderContext {
  return {
    toolName: 'bash',
    arguments: '',
    text: '',
    attachments: [],
    approval: { required: true, decision: null, decisionId: null, reason: null },
    approvalPending,
    argumentsStreaming: false,
  }
}

/** 永不 settle 的决策请求：把 pending 固定在“已点击、尚未回执”的窗口内。 */
function unresolvedRequest(): Promise<void> {
  return new Promise<void>(() => undefined)
}

describe('ToolApprovalBar pending feedback', () => {
  it('disables both decisions and shows the pending indicator immediately on ALLOW', async () => {
    const user = userEvent.setup()
    const onDecideApproval = vi.fn(unresolvedRequest)
    render(
      <ToolApprovalBar
        context={context()}
        message={message('inv-1')}
        onDecideApproval={onDecideApproval}
      />,
    )

    await user.click(screen.getByRole('button', { name: '允许' }))

    expect(onDecideApproval).toHaveBeenCalledWith(
      expect.objectContaining({ invocationId: 'inv-1' }),
      'ALLOW',
    )
    // 未收到任何权威回执前，本地 pending 就已生效：状态可见、两个决策同时不可用。
    expect(screen.getByRole('status')).toHaveTextContent('正在提交审批')
    expect(screen.getByRole('button', { name: '允许' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '拒绝' })).toBeDisabled()
  })

  it('shows the same pending feedback for DENY', async () => {
    const user = userEvent.setup()
    const onDecideApproval = vi.fn(unresolvedRequest)
    render(
      <ToolApprovalBar
        context={context()}
        message={message('inv-1')}
        onDecideApproval={onDecideApproval}
      />,
    )

    await user.click(screen.getByRole('button', { name: '拒绝' }))

    expect(onDecideApproval).toHaveBeenCalledWith(
      expect.objectContaining({ invocationId: 'inv-1' }),
      'DENY',
    )
    expect(screen.getByRole('status')).toHaveTextContent('正在提交审批')
    expect(screen.getByRole('button', { name: '允许' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '拒绝' })).toBeDisabled()
  })

  it('prevents duplicate submissions while the decision is unresolved', async () => {
    const user = userEvent.setup()
    const onDecideApproval = vi.fn(unresolvedRequest)
    render(
      <ToolApprovalBar
        context={context()}
        message={message('inv-1')}
        onDecideApproval={onDecideApproval}
      />,
    )

    await user.click(screen.getByRole('button', { name: '允许' }))
    // 两个决策在 pending 期间都不可再次提交，避免 ALLOW/DENY 互相覆盖。
    await user.click(screen.getByRole('button', { name: '拒绝' }))
    await user.click(screen.getByRole('button', { name: '允许' }))

    expect(onDecideApproval).toHaveBeenCalledTimes(1)
  })

  it('restores actionable controls when the decision request rejects', async () => {
    const user = userEvent.setup()
    let rejectRequest: (error: Error) => void = () => undefined
    const onDecideApproval = vi.fn(
      () => new Promise<void>((_, reject) => {
        rejectRequest = reject
      }),
    )
    render(
      <ToolApprovalBar
        context={context()}
        message={message('inv-1')}
        onDecideApproval={onDecideApproval}
      />,
    )

    await user.click(screen.getByRole('button', { name: '允许' }))
    expect(screen.getByRole('status')).toBeInTheDocument()

    // 失败只释放本地 pending：错误呈现仍由宿主的既有通道负责。
    act(() => rejectRequest(new Error('network')))

    expect(await screen.findByRole('button', { name: '允许' })).toBeEnabled()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('does not leak pending state when the rendered invocation changes', async () => {
    const user = userEvent.setup()
    const onDecideApproval = vi.fn(unresolvedRequest)
    const { rerender } = render(
      <ToolApprovalBar
        context={context()}
        message={message('inv-1')}
        onDecideApproval={onDecideApproval}
      />,
    )

    await user.click(screen.getByRole('button', { name: '允许' }))
    expect(screen.getByRole('status')).toBeInTheDocument()

    // snapshot 把审批条换成另一条 invocation：旧 pending 必须立即失效。
    rerender(
      <ToolApprovalBar
        context={context()}
        message={message('inv-2')}
        onDecideApproval={onDecideApproval}
      />,
    )

    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '允许' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '拒绝' })).toBeEnabled()
  })

  it('keeps the controller-wide approvalPending as an additional busy signal', () => {
    render(
      <ToolApprovalBar
        context={context(true)}
        message={message('inv-1')}
        onDecideApproval={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: '允许' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '拒绝' })).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('正在提交审批')
  })
})
