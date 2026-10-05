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

  it('does not pair a tool call and result by tool name when both toolCallIds are absent', () => {
    // 没有调用身份时不再按工具名粗配，避免把无关结果并进同一张卡片。
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
    const { container } = render(<MessageList messages={[call, result]} />)
    expect(container.querySelectorAll('.thread-turn-tool')).toHaveLength(2)
  })

  it('pairs portable fixtures by toolCallId and rendererKey when callIdentity is absent', async () => {
    const user = userEvent.setup()
    const call: ToolDialogueMessage = {
      ...message,
      id: 'call-no-id',
      phase: 'call',
      text: '',
      toolCallId: 'portable-call',
      arguments: '{"path":"README.md"}',
    }
    const result: ToolDialogueMessage = {
      ...message,
      id: 'result-no-id',
      phase: 'result',
      text: 'fallback pair ok',
      toolCallId: 'portable-call',
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
          { ...message, id: 'r1', phase: 'result', text: 'orphan result', toolCallId: 'x-1', toolName: 'read', rendererKey: 'write' },
        ]}
      />,
    )
    // rendererKey 不同时 result 不并入 call，两张卡片各自保留。
    expect(container.querySelectorAll('.thread-tool-surface')).toHaveLength(2)
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

  it('colors each sibling independently: success, error, and waiting approval', () => {
    // 同一 batch 的 transient result 只配对自身调用；成功、错误和等待审批不能互相染色。
    const succeededCall: ToolDialogueMessage = {
      ...message,
      id: '40:tool-call:call-ok:0',
      phase: 'call',
      status: 'done',
      toolCallId: 'call-ok',
      toolName: 'bash',
      rendererKey: 'bash',
      arguments: '{"command":"ls"}',
      text: '',
    }
    const succeededResult: ToolDialogueMessage = {
      ...message,
      id: 'transient:tool-result:inv-ok:1',
      phase: 'result',
      status: 'done',
      toolCallId: 'call-ok',
      toolName: 'bash',
      rendererKey: 'bash',
      arguments: '{"command":"ls"}',
      text: 'listed files',
    }
    const failedCall: ToolDialogueMessage = {
      ...message,
      id: '40:tool-call:call-bad:1',
      phase: 'call',
      status: 'error',
      toolCallId: 'call-bad',
      text: '',
    }
    const failedResult: ToolDialogueMessage = {
      ...message,
      id: 'transient:tool-result:inv-bad:1',
      phase: 'result',
      status: 'error',
      toolCallId: 'call-bad',
      text: 'listed files failed',
      errorMessage: 'listed files failed',
    }
    const waitingCall: ToolDialogueMessage = {
      ...message,
      id: '40:tool-call:call-ask:2',
      phase: 'call',
      status: 'streaming',
      toolCallId: 'call-ask',
      text: '',
      approval: { required: true, decision: null, decisionId: null, reason: null },
    }
    const { container } = render(
      <MessageList
        messages={[
          succeededCall,
          succeededResult,
          failedCall,
          failedResult,
          waitingCall,
        ]}
        onDecideApproval={vi.fn()}
      />,
    )
    const cards = container.querySelectorAll('.thread-turn-tool')
    expect(cards).toHaveLength(3)
    expect(cards[0]).toHaveClass('tool-state-success')
    expect(cards[1]).toHaveClass('tool-state-error')
    expect(cards[2]).toHaveClass('tool-state-pending')
    expect(screen.getByText('listed files failed')).toBeInTheDocument()
    expect(screen.getByText('listed files')).toBeInTheDocument()
    expect(screen.getAllByText('listed files')).toHaveLength(1)
    expect(screen.getByRole('button', { name: '允许' })).toBeInTheDocument()
  })

  it('pairs reused toolCallIds by callIdentity at both history and current calls', () => {
    // MessageList 不能把历史 result 配到当前同 toolCallId 的 call 上。
    const historyCall: ToolDialogueMessage = {
      ...message,
      id: 'A:tool-call:x:0',
      phase: 'call',
      status: 'done',
      toolCallId: 'x',
      callIdentity: 'A:0',
      toolName: 'bash',
      rendererKey: 'bash',
      arguments: '{"command":"ls"}',
      text: '',
    }
    const historyResult: ToolDialogueMessage = {
      ...message,
      id: 'A-result',
      phase: 'result',
      status: 'done',
      toolCallId: 'x',
      callIdentity: 'A:0',
      toolName: 'bash',
      rendererKey: 'bash',
      arguments: '{"command":"ls"}',
      text: 'old output',
    }
    const currentCall: ToolDialogueMessage = {
      ...message,
      id: 'B:tool-call:x:0',
      phase: 'call',
      status: 'done',
      toolCallId: 'x',
      callIdentity: 'B:0',
      toolName: 'bash',
      rendererKey: 'bash',
      arguments: '{"command":"ls"}',
      text: '',
    }
    const currentResult: ToolDialogueMessage = {
      ...message,
      id: 'transient:tool-result:inv-b:1',
      phase: 'result',
      status: 'done',
      toolCallId: 'x',
      callIdentity: 'B:0',
      toolName: 'bash',
      rendererKey: 'bash',
      arguments: '{"command":"ls"}',
      text: 'current output',
    }
    const { container } = render(
      <MessageList messages={[historyCall, historyResult, currentCall, currentResult]} />,
    )
    const cards = container.querySelectorAll('.thread-turn-tool')
    expect(cards).toHaveLength(2)
    expect(cards[0]).toHaveClass('tool-state-success')
    expect(cards[1]).toHaveClass('tool-state-success')
    expect(screen.getAllByText('old output')).toHaveLength(1)
    expect(screen.getAllByText('current output')).toHaveLength(1)
  })
})
