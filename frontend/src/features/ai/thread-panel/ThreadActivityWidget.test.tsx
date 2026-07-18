import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ThreadActivityWidget } from '@/features/ai/thread-panel/ThreadActivityWidget'
import type { RootActivityDTO } from '@/shared/api/contracts'

describe('ThreadActivityWidget', () => {
  it('renders supported activity lines and ignores unknown types', () => {
    render(
      <ThreadActivityWidget
        activities={[
          activity('subagent_started', { target: 'researcher', childSessionId: 'c1' }),
          activity('subagent_completed', { status: 'SUCCEEDED' }),
          activity('subagent_cancel_requested', { reason: 'idle' }),
          activity('permission_requested', { tool: 'bash' }),
          activity('permission_resolved', { decision: 'ALLOW' }),
          activity('thread_idle', {}),
        ]}
      />,
    )
    expect(screen.getByText(/启动子代理 researcher/)).toBeInTheDocument()
    expect(screen.getByText(/子代理完成：SUCCEEDED/)).toBeInTheDocument()
    expect(screen.getByText(/请求取消子代理：idle/)).toBeInTheDocument()
    expect(screen.getByText(/等待工具授权：bash/)).toBeInTheDocument()
    expect(screen.getByText(/工具授权已ALLOW/)).toBeInTheDocument()
    expect(screen.queryByText('thread_idle')).not.toBeInTheDocument()
  })

  it('returns null without visible activities', () => {
    const { container } = render(<ThreadActivityWidget activities={[activity('thread_idle', {})]} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('falls back to childSessionId/invocationId when target or tool is absent', () => {
    render(
      <ThreadActivityWidget
        activities={[
          activity('subagent_started', { childSessionId: 'child-9' }),
          activity('permission_requested', { invocationId: 'inv-9' }),
        ]}
      />,
    )
    expect(screen.getByText(/启动子代理 child-9/)).toBeInTheDocument()
    expect(screen.getByText(/等待工具授权：inv-9/)).toBeInTheDocument()
  })
})

function activity(eventType: string, payload: Record<string, unknown>): RootActivityDTO {
  return {
    rootSessionId: '1',
    sessionId: '1',
    threadId: '1',
    eventId: eventType,
    eventType,
    payloadJson: JSON.stringify(payload),
    createTime: null,
  }
}
