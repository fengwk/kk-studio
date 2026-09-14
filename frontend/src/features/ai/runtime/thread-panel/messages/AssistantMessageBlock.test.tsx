import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AssistantMessageBlock } from '@/features/ai/runtime/thread-panel/messages/AssistantMessageBlock'
import type { TextDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

function message(overrides: Partial<TextDialogueMessage> = {}): TextDialogueMessage {
  return {
    id: 'msg-1',
    role: 'assistant',
    subjectEntryId: 'entry-1',
    text: 'partial answer',
    thinking: '',
    status: 'done',
    createdAt: null,
    ...overrides,
  }
}

describe('AssistantMessageBlock', () => {
  it('renders text and thinking content for normal assistant turns', () => {
    render(
      <AssistantMessageBlock
        message={message({ text: 'final answer', thinking: 'why', aborted: false })}
      />,
    )
    expect(screen.getByText('final answer')).toBeInTheDocument()
    expect(screen.getByText('why')).toBeInTheDocument()
    expect(screen.queryByText('已停止')).not.toBeInTheDocument()
  })

  it('renders the 已停止 affordance and applies the aborted treatment when aborted is true', () => {
    const { container } = render(
      <AssistantMessageBlock
        message={message({ text: 'partial answer', aborted: true })}
      />,
    )
    expect(screen.getByText('已停止')).toBeInTheDocument()
    expect(container.querySelector('.thread-turn-assistant.aborted')).toBeInTheDocument()
  })

  it('does not show the 已停止 affordance for non-aborted assistant turns', () => {
    render(<AssistantMessageBlock message={message({ aborted: false })} />)
    expect(screen.queryByText('已停止')).not.toBeInTheDocument()
  })

  it('shows the streaming ellipsis placeholder while text and thinking are still empty', () => {
    const { container } = render(
      <AssistantMessageBlock
        message={message({ text: '', thinking: '', status: 'streaming' })}
      />,
    )
    expect(screen.getByText('…')).toBeInTheDocument()
    expect(container.querySelector('.thread-streaming-hint')).toBeInTheDocument()
  })

  it('keeps the raw provider payload visible as preformatted text on error', () => {
    const { container } = render(
      <AssistantMessageBlock
        message={message({ text: '{"error":"bad request"}', status: 'error' })}
      />,
    )
    // 失败时原始 JSON/HTML 必须原样保留，禁止 Markdown 重排。
    expect(container.querySelector('.thread-error-raw')).toHaveTextContent('{"error":"bad request"}')
    expect(container.querySelector('.thread-assistant-text markdown')).not.toBeInTheDocument()
    expect(screen.getByText('{"error":"bad request"}')).toBeInTheDocument()
  })

  it('shows the assistant failure message when an error turn has no text or thinking', () => {
    render(<AssistantMessageBlock message={message({ text: '', thinking: '', status: 'error' })} />)
    expect(screen.getByText('助手回复失败')).toBeInTheDocument()
    expect(screen.queryByText('…')).not.toBeInTheDocument()
  })

  it('normalizes trailing whitespace and newlines from thinking text while preserving leading and internal blank lines', () => {
    // 测试意图：只移除思考末尾空白，作者的缩进和内部段落结构必须保持原样。
    const { container } = render(
      <AssistantMessageBlock
        message={message({
          thinking: '  step 1\n\n  step 2\n\n\n',
          text: 'answer',
        })}
      />,
    )
    const thinkingEl = container.querySelector('.thread-thinking-text')
    expect(thinkingEl).not.toBeNull()
    expect(thinkingEl?.textContent).toBe('  step 1\n\n  step 2')
  })

  it('does not render thinking block when thinking contains only whitespace or newlines', () => {
    // 测试意图：纯空白思考不能生成一个可见的空块或占用正文前的布局间距。
    const { container } = render(
      <AssistantMessageBlock
        message={message({
          thinking: '   \n\n  \t  ',
          text: 'answer',
        })}
      />,
    )
    expect(container.querySelector('.thread-block-thinking')).toBeNull()
  })

  it('maintains normalized text through streaming transition without extra spacing', () => {
    // 测试意图：流式和终态使用同一渲染边界规则，状态切换不能重新引入尾随空行。
    const { container, rerender } = render(
      <AssistantMessageBlock
        message={message({
          thinking: 'reasoning step\n\n',
          text: '',
          status: 'streaming',
        })}
      />,
    )
    let thinkingBlock = container.querySelector('.thread-block-thinking')
    expect(thinkingBlock).toHaveClass('streaming')
    expect(container.querySelector('.thread-thinking-text')?.textContent).toBe('reasoning step')

    rerender(
      <AssistantMessageBlock
        message={message({
          thinking: 'reasoning step\n\n',
          text: 'final answer',
          status: 'done',
        })}
      />,
    )
    thinkingBlock = container.querySelector('.thread-block-thinking')
    expect(thinkingBlock).not.toHaveClass('streaming')
    expect(container.querySelector('.thread-thinking-text')?.textContent).toBe('reasoning step')
    expect(screen.getByText('final answer')).toBeInTheDocument()
  })
})
