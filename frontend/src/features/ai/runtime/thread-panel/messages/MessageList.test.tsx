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
  contents: [{ type: 'text', text: 'full result' }],
  toolCallId: 'call-1',
  toolName: 'read',
  rendererKey: 'read',
  arguments: '{"path":"README.md"}',
}

const expandButton = () => screen.queryByRole('button', { name: '展开工具预览' })

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
      toolCallId: '',
      arguments: '{"path":"README.md"}',
    }
    const result: ToolDialogueMessage = {
      ...message,
      id: 'result-no-id',
      phase: 'result',
      contents: [{ type: 'text', text: 'fallback pair ok' }],
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
      toolCallId: 'portable-call',
      contents: [],
      arguments: '{"path":"README.md"}',
    }
    const result: ToolDialogueMessage = {
      ...message,
      id: 'result-no-id',
      phase: 'result',
      contents: [{ type: 'text', text: 'fallback pair ok' }],
      toolCallId: 'portable-call',
    }
    render(<MessageList messages={[call, result]} />)
    expect(screen.getAllByText('read')).toHaveLength(1)
    expect(screen.queryByText('fallback pair ok')).not.toBeInTheDocument()
    await user.click(expandButton()!)
    expect(screen.getByText('fallback pair ok')).toBeInTheDocument()
  })

  it('does not pair a call with a result whose rendererKey differs (identity mismatch)', () => {
    const { container } = render(
      <MessageList
        messages={[
          { ...message, id: 'c1', phase: 'call', contents: [], toolCallId: 'x-1', toolName: 'read' },
          {
            ...message,
            id: 'r1',
            phase: 'result',
            contents: [{ type: 'text', text: 'orphan result' }],
            toolCallId: 'x-1',
            toolName: 'read',
            rendererKey: 'write',
          },
        ]}
      />,
    )
    // rendererKey 不同时 result 不并入 call，两张卡片各自保留。
    expect(container.querySelectorAll('.thread-tool-surface')).toHaveLength(2)
    expect(screen.queryByText('orphan result')).not.toBeInTheDocument()
  })

  it('renders an approval request as a read-only record with no decision buttons', () => {
    const { container } = render(
      <MessageList
        messages={[
          {
            ...message,
            id: 'c-approval',
            phase: 'call',
            contents: [],
            toolCallId: 'a-1',
            status: 'streaming',
            approval: { required: true, decision: null, decisionId: null, reason: null },
          },
        ]}
      />,
    )

    // 时间线只读：人工决策在根交互区完成，卡片不提供 ALLOW/DENY 写入路径。
    expect(screen.getByText(/此工具调用需要审批/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '拒绝' })).not.toBeInTheDocument()
    expect(container.querySelectorAll('.thread-turn-tool')).toHaveLength(1)
  })

  it('renders an unknown tool role message as a tool card without pairing (default branch)', () => {
    const { container } = render(
      <MessageList messages={[{ ...message, id: 'unknown-role' } as unknown as DialogueMessage]} />,
    )
    // 未知 role 走 tool single 分支；单卡渲染、结果文本默认收起。
    expect(container.querySelectorAll('.thread-tool-surface')).toHaveLength(1)
    expect(screen.queryByText('full result')).not.toBeInTheDocument()
    expect(expandButton()).toBeInTheDocument()
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
    // read 的文本结果默认收起；展开后交给扩展 renderer，不叠加默认输出。
    expect(screen.queryByText('read renderer: {"path":"README.md"}')).not.toBeInTheDocument()
    await user.click(expandButton()!)
    expect(screen.getByText('read renderer: {"path":"README.md"}')).toBeInTheDocument()
    expect(screen.queryByText('full result')).not.toBeInTheDocument()

    rerender(
      <ExtensionHostProvider host={host}>
        <MessageList messages={[{ ...message, rendererKey: 'unknown' }]} />
      </ExtensionHostProvider>,
    )
    // 无贡献时回退到有序内容视图，不叠加扩展输出（同一卡片身份，展开选择保留）。
    expect(screen.queryByText('read renderer: {"path":"README.md"}')).not.toBeInTheDocument()
    expect(screen.getByText('full result')).toBeInTheDocument()
    expect(expandButton()).not.toBeInTheDocument()
  })

  it('pairs a durable tool call and result into one card', async () => {
    const user = userEvent.setup()
    const call: ToolDialogueMessage = {
      ...message,
      id: 'tool-call',
      phase: 'call',
      contents: [],
      arguments: '{"path":"README.md"}',
    }
    const result: ToolDialogueMessage = {
      ...message,
      id: 'tool-result',
      phase: 'result',
      contents: [{ type: 'text', text: 'ok' }],
    }
    render(<MessageList messages={[call, result]} />)
    expect(screen.getAllByText('read')).toHaveLength(1)
    expect(screen.queryByText('ok')).not.toBeInTheDocument()
    await user.click(expandButton()!)
    expect(screen.getByText('ok')).toBeInTheDocument()
    expect(screen.queryByText('工具调用 ·')).not.toBeInTheDocument()
    expect(screen.queryByText('工具结果 ·')).not.toBeInTheDocument()
  })

  it('renders a bare tool result as a single message when no call precedes it', () => {
    const { container } = render(
      <MessageList
        messages={[
          {
            ...message,
            id: 'standalone-result',
            phase: 'result',
            contents: [{ type: 'text', text: 'standalone output' }],
          },
        ]}
      />,
    )
    // 单张卡片；read 文本默认收起。
    expect(container.querySelectorAll('.thread-tool-surface')).toHaveLength(1)
    expect(screen.queryByText('standalone output')).not.toBeInTheDocument()
    expect(screen.queryByText('工具结果 ·')).not.toBeInTheDocument()
  })

  it('renders call without paired result as pending and paired call+result as success', () => {
    const call: ToolDialogueMessage = {
      ...message,
      id: 'tool-call-1',
      phase: 'call',
      status: 'done',
      contents: [],
      arguments: '{"path":"README.md"}',
    }
    const { container, rerender } = render(<MessageList messages={[call]} />)
    expect(container.querySelector('.thread-turn-tool')).toHaveClass('tool-state-pending')

    const result: ToolDialogueMessage = {
      ...message,
      id: 'tool-result-1',
      phase: 'result',
      status: 'done',
      contents: [{ type: 'text', text: 'ok' }],
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
      contents: [],
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
      contents: [{ type: 'text', text: 'listed files' }],
    }
    const failedCall: ToolDialogueMessage = {
      ...message,
      id: '40:tool-call:call-bad:1',
      phase: 'call',
      status: 'error',
      toolCallId: 'call-bad',
      contents: [],
    }
    const failedResult: ToolDialogueMessage = {
      ...message,
      id: 'transient:tool-result:inv-bad:1',
      phase: 'result',
      status: 'error',
      toolCallId: 'call-bad',
      contents: [{ type: 'text', text: 'listed files failed' }],
      errorMessage: 'listed files failed',
    }
    const waitingCall: ToolDialogueMessage = {
      ...message,
      id: '40:tool-call:call-ask:2',
      phase: 'call',
      status: 'streaming',
      toolCallId: 'call-ask',
      contents: [],
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
    expect(screen.getByText(/此工具调用需要审批/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()
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
      contents: [],
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
      contents: [{ type: 'text', text: 'old output' }],
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
      contents: [],
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
      contents: [{ type: 'text', text: 'current output' }],
    }
    const { container } = render(
      <MessageList messages={[historyCall, historyResult, currentCall, currentResult]} />,
    )
    const cards = container.querySelectorAll('.thread-turn-tool')
    // 两张卡片（不是四张）且 key 不冲突：复用同一 toolCallId 时按 callIdentity 区分。
    expect(cards).toHaveLength(2)
    expect(cards[0]).toHaveClass('tool-state-success')
    expect(cards[1]).toHaveClass('tool-state-success')
    expect(screen.getAllByText('old output')).toHaveLength(1)
    expect(screen.getAllByText('current output')).toHaveLength(1)
  })

  it('keeps the same DOM node identity when a streaming call becomes durable', () => {
    // 实时草稿与持久 call 使用同一个 toolCallId 作为 key：流式转终态不重挂载，
    // 展开、内部滚动和选择因此不会被重置。
    const streaming: ToolDialogueMessage = {
      ...message,
      id: 'transient:tool-call:x:0',
      phase: 'call',
      status: 'streaming',
      toolCallId: 'x',
      toolName: 'bash',
      rendererKey: 'bash',
      arguments: '{"command":"ls"}',
      contents: [],
      partialContents: [{ type: 'text', text: 'streaming output' }],
    }
    const { container, rerender } = render(<MessageList messages={[streaming]} />)
    const streamingNode = container.querySelector('.thread-turn-tool')
    expect(streamingNode).not.toBeNull()

    const durable: ToolDialogueMessage = {
      ...streaming,
      id: '40:tool-call:x:0',
      subjectEntryId: 'entry-40',
      status: 'done',
    }
    rerender(<MessageList messages={[durable]} />)

    expect(container.querySelectorAll('.thread-turn-tool')).toHaveLength(1)
    expect(container.querySelector('.thread-turn-tool')).toBe(streamingNode)
  })

  it('never renders a stale approval decision as a write path', () => {
    const onDecideApproval = vi.fn()
    const { container } = render(
      <MessageList
        messages={[
          {
            ...message,
            id: 'c-decided',
            phase: 'call',
            contents: [],
            toolCallId: 'a-2',
            status: 'done',
            approval: {
              required: true,
              decision: 'ALLOWED',
              decisionId: 'd-1',
              reason: 'looks safe',
            },
          } as ToolDialogueMessage,
        ]}
      />,
    )

    expect(screen.getByText(/已允许/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '允许' })).not.toBeInTheDocument()
    expect(onDecideApproval).not.toHaveBeenCalled()
    expect(container.querySelectorAll('.thread-turn-tool')).toHaveLength(1)
  })
})

