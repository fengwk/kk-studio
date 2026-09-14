import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { MessageList } from '@/features/ai/runtime/thread-panel/messages/MessageList'
import type {
  DialogueMessage,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'
import { ExtensionHostProvider } from '@/platform/extensions/ExtensionHostContext'

const message: ToolDialogueMessage = {
  id: 'tool-1',
  role: 'tool',
  subjectEntryId: 'entry-1',
  createdAt: null,
  status: 'done',
  phase: 'result',
  text: 'full result',
  toolCallId: 'call-1',
  toolName: 'read',
  rendererKey: 'read',
  arguments: '{"path":"README.md"}',
  attachments: [],
}

describe('MessageList tool renderer dispatch', () => {
  it('renders an empty list without crashing and without any tool surface', () => {
    const { container } = render(<MessageList messages={[]} />)
    expect(container.querySelector('.thread-block')).not.toBeInTheDocument()
    expect(container.querySelector('.thread-tool-surface')).not.toBeInTheDocument()
  })

  it('renders the tool pairing with rendererKey+toolName when both toolCallIds are absent', async () => {
    const user = userEvent.setup()
    const call: ToolDialogueMessage = {
      ...message,
      id: 'call-no-id',
      phase: 'call',
      text: '',
      toolCallId: '',
      arguments: '{"path":"README.md"}',
    }
    const result: ToolDialogueMessage = {
      ...message,
      id: 'result-no-id',
      phase: 'result',
      text: 'fallback pair ok',
      toolCallId: '',
    }
    render(<MessageList messages={[call, result]} />)
    expect(screen.getAllByText('read')).toHaveLength(1)
    expect(screen.queryByText('fallback pair ok')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '展开工具预览' }))
    expect(screen.getByText('fallback pair ok')).toBeInTheDocument()
  })

  it('does not pair a call with a result whose rendererKey differs (identity mismatch)', () => {
    const { container } = render(
      <MessageList
        messages={[
          { ...message, id: 'c1', phase: 'call', text: '', toolCallId: 'x-1', toolName: 'read' },
          { ...message, id: 'r1', phase: 'result', text: 'orphan result', toolCallId: 'x-1', toolName: 'write', rendererKey: 'write' },
        ]}
      />,
    )
    // 身份不匹配（toolCallId 相同但 rendererKey 兜底失败）时 result 作为孤立 single 消息展示。
    expect(container.querySelectorAll('.thread-tool-surface')).toHaveLength(1)
    // 收起态压缩了 read 结果文本，展开后同卡内可见。
    expect(screen.queryByText('orphan result')).not.toBeInTheDocument()
  })

  it('passes approvalPending down so undecided approval buttons are disabled', () => {
    const onDecideApproval = vi.fn()
    render(
      <MessageList
        messages={[
          {
            ...message,
            id: 'c-approval',
            phase: 'call',
            text: '',
            toolCallId: 'a-1',
            status: 'streaming',
            approval: { required: true, decision: null, decisionId: null, reason: null },
          },
        ]}
        onDecideApproval={onDecideApproval}
        approvalPending
      />,
    )
    expect(screen.getByRole('button', { name: '允许' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '拒绝' })).toBeDisabled()
  })

  it('renders an unknown tool role message as a tool card without pairing (default branch)', () => {
    const { container } = render(
      <MessageList messages={[{ ...message, id: 'unknown-role' } as unknown as DialogueMessage]} />,
    )
    // 未知 role 走 tool single 分支；单卡渲染、结果文本不展示（收起态压缩）。
    expect(container.querySelectorAll('.thread-tool-surface')).toHaveLength(1)
    expect(screen.queryByText('full result')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '展开工具预览' })).toBeInTheDocument()
  })

  it('dispatches by the frozen rendererKey and falls back when no contribution exists', async () => {
    const user = userEvent.setup()
    const host = new ExtensionHost()
    host.register({
      id: 'test.tools',
      toolRenderers: [
        {
          id: 'read',
          component: ({ message: rendered }) => (
            <div>read renderer: {rendered.arguments}</div>
          ),
        },
      ],
    })
    const { rerender } = render(
      <ExtensionHostProvider host={host}>
        <MessageList messages={[message]} />
      </ExtensionHostProvider>,
    )
    expect(screen.queryByText('read renderer: {"path":"README.md"}')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '展开工具预览' }))
    expect(screen.getByText('read renderer: {"path":"README.md"}')).toBeInTheDocument()
    expect(screen.queryByText('full result')).not.toBeInTheDocument()

    rerender(
      <ExtensionHostProvider host={host}>
        <MessageList messages={[{ ...message, rendererKey: 'unknown' }]} />
      </ExtensionHostProvider>,
    )
    expect(screen.getByText('full result')).toBeInTheDocument()
  })

  it('pairs a durable tool call and result into one card', async () => {
    const user = userEvent.setup()
    const call: ToolDialogueMessage = {
      ...message,
      id: 'tool-call',
      phase: 'call',
      text: '',
      arguments: '{"path":"README.md"}',
    }
    const result: ToolDialogueMessage = {
      ...message,
      id: 'tool-result',
      phase: 'result',
      text: 'ok',
    }
    render(<MessageList messages={[call, result]} />)
    expect(screen.getAllByText('read')).toHaveLength(1)
    expect(screen.queryByText('ok')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '展开工具预览' }))
    expect(screen.getByText('ok')).toBeInTheDocument()
    expect(screen.queryByText('工具调用 ·')).not.toBeInTheDocument()
    expect(screen.queryByText('工具结果 ·')).not.toBeInTheDocument()
  })

  it('renders a bare tool result as a single message when no call precedes it', () => {
    render(
      <MessageList
        messages={[
          { ...message, id: 'standalone-result', phase: 'result', text: 'standalone output' },
        ]}
      />,
    )
    // 收起态默认结果被压缩，直接断言文本不在；展开后可读（且无重复卡片）。
    expect(screen.queryByText('standalone output')).not.toBeInTheDocument()
    expect(screen.queryByText('工具结果 ·')).not.toBeInTheDocument()
    const { container } = render(
      <MessageList
        messages={[
          { ...message, id: 'standalone-result', phase: 'result', text: 'standalone output' },
        ]}
      />,
    )
    expect(container.querySelectorAll('.thread-tool-surface')).toHaveLength(1)
  })

  it('renders call without paired result as pending and paired call+result as success', () => {
    const call: ToolDialogueMessage = {
      ...message,
      id: 'tool-call-1',
      phase: 'call',
      status: 'done',
      text: '',
      arguments: '{"path":"README.md"}',
    }
    const { container, rerender } = render(<MessageList messages={[call]} />)
    expect(container.querySelector('.thread-turn-tool')).toHaveClass('tool-state-pending')

    const result: ToolDialogueMessage = {
      ...message,
      id: 'tool-result-1',
      phase: 'result',
      status: 'done',
      text: 'ok',
    }
    rerender(<MessageList messages={[call, result]} />)
    expect(container.querySelector('.thread-turn-tool')).toHaveClass('tool-state-success')
  })
})
