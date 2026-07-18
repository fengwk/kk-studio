import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ChatObservabilityPanel } from '@/features/ai/ChatObservabilityPanel'
import type { ToolInvocationDTO } from '@/shared/api/contracts'

describe('ChatObservabilityPanel', () => {
  it('renders permission-only banner and decisions', async () => {
    const user = userEvent.setup()
    const onDecision = vi.fn()
    render(
      <ChatObservabilityPanel
        yolo={{ enabled: false }}
        usage={undefined}
        toolInvocations={[invocation('WAITING_APPROVAL')]}
        error={null}
        yoloPending={false}
        decisionPending={false}
        onYoloChange={vi.fn()}
        onDecision={onDecision}
        permissionsOnly
      />,
    )
    expect(screen.getByLabelText('工具授权')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '允许' }))
    await user.click(screen.getByRole('button', { name: '拒绝' }))
    expect(onDecision).toHaveBeenCalledWith('inv-1', 'allow')
    expect(onDecision).toHaveBeenCalledWith('inv-1', 'deny')
  })

  it('returns null in permissions-only mode without pending decisions', () => {
    const { container } = render(
      <ChatObservabilityPanel
        toolInvocations={[invocation('SUCCEEDED')]}
        error={null}
        yoloPending={false}
        decisionPending={false}
        onYoloChange={vi.fn()}
        onDecision={vi.fn()}
        permissionsOnly
      />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('renders full observability panel with usage and yolo toggle', async () => {
    const user = userEvent.setup()
    const onYoloChange = vi.fn()
    render(
      <ChatObservabilityPanel
        yolo={{ enabled: true }}
        usage={{
          scopeType: 'thread',
          scopeId: '1',
          recordCount: 1,
          inputTokens: 10,
          outputTokens: 5,
          cacheReadTokens: 0,
          cacheWriteTokens: 0,
          cacheWriteLongTokens: 0,
          reasoningTokens: 0,
          providerTotalTokens: 15,
          cacheEligibleRecordCount: 0,
          cacheHitRecordCount: 0,
          cacheHitRatio: 0,
          tokenReadRatio: 0,
          unamortizedCacheWriteTokens: 0,
          costs: [],
        }}
        toolInvocations={[invocation('RUNNING'), invocation('WAITING_APPROVAL')]}
        error={new Error('obs failed')}
        yoloPending={false}
        decisionPending={false}
        onYoloChange={onYoloChange}
        onDecision={vi.fn()}
      />,
    )
    expect(screen.getByText(/部分运行信息加载失败/)).toBeInTheDocument()
    expect(screen.getByText(/需要工具授权：bash/)).toBeInTheDocument()
    const toggle = screen.getByRole('checkbox')
    await user.click(toggle)
    expect(onYoloChange).toHaveBeenCalled()
  })

  it('shows loading usage and disables yolo without snapshot', () => {
    render(
      <ChatObservabilityPanel
        toolInvocations={[]}
        error={null}
        yoloPending={false}
        decisionPending={false}
        onYoloChange={vi.fn()}
        onDecision={vi.fn()}
        compact
      />,
    )
    expect(screen.getByText(/Usage：加载中/)).toBeInTheDocument()
    expect(screen.getByRole('checkbox')).toBeDisabled()
  })
})

function invocation(status: string): ToolInvocationDTO {
  return {
    id: 'inv-1',
    threadId: '1',
    assistantEntryId: 'a1',
    ordinal: 0,
    toolCallId: 'call-1',
    toolName: 'bash',
    toolVersion: '1',
    targetType: 'environment',
    environmentId: 'env-1',
    argumentsJson: '{"cmd":"ls"}',
    status,
    permissionAction: 'ask',
    permissionDecision: null,
    deadlineAt: null,
    leaseOwner: null,
    leaseUntil: null,
    cancelRequestedAt: null,
    resultJson: null,
    errorMessage: null,
    createTime: null,
    startedAt: null,
    finishedAt: null,
    updateTime: null,
  }
}
