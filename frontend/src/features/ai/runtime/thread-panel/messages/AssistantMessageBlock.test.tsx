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

  it('renders long (>128 chars) multiline raw error and HTML body safely escaped in pre block without markdown parsing', () => {
    // 验证助手错误状态下超长多行 JSON 与 HTML 免 Markdown 解析、纯文本安全转义呈现
    const rawPayload = [
      'HTTP 502',
      '{',
      '  "error": {',
      '    "message": "upstream upstream_model_gateway_failed: connection timeout. 详情：上游网关超时",',
      '    "type": "gateway_timeout",',
      '    "param": null,',
      '    "code": "bad_gateway",',
      '    "request_id": "req_gw_1234567890_timeout_xyz",',
      '    "response_html": "<html><body><h1>502 Bad Gateway</h1><script>alert(1)</script></body></html>"',
      '  }',
      '}',
    ].join('\n')
    expect(rawPayload.length).toBeGreaterThan(128)

    const { container } = render(
      <AssistantMessageBlock
        message={message({ text: rawPayload, status: 'error' })}
      />,
    )

    const pre = container.querySelector('.thread-error-raw')
    expect(pre).not.toBeNull()
    expect(pre?.textContent).toBe(rawPayload)
    // 保证 HTML/脚本未被执行或渲染为 DOM
    expect(container.querySelector('h1')).toBeNull()
    expect(container.querySelector('script')).toBeNull()
    expect(screen.getByText(/req_gw_1234567890_timeout_xyz/)).toBeInTheDocument()
  })

  it('shows the assistant failure message when an error turn has no text or thinking', () => {
    render(<AssistantMessageBlock message={message({ text: '', thinking: '', status: 'error' })} />)
    expect(screen.getByText('助手回复失败')).toBeInTheDocument()
    expect(screen.queryByText('…')).not.toBeInTheDocument()
  })

  it('normalizes trailing whitespace and newlines from thinking text while preserving paragraph structure', () => {
    // 测试意图：移除思考末尾多余空白，内部段落结构经 Markdown 渲染为标准段落元素，消除冗余空行。
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
    const paragraphs = thinkingEl?.querySelectorAll('p')
    expect(paragraphs).toHaveLength(2)
    expect(paragraphs?.[0]?.textContent).toBe('step 1')
    expect(paragraphs?.[1]?.textContent).toBe('step 2')
  })

  it('renders markdown thinking from triple newlines, bold, lists, and code without modifying raw error pre', () => {
    // 测试意图：思考内容支持 Markdown 渲染（多空行折叠为段落、加粗、列表、代码块），同时保持 error 状态下 raw pre 不被转义。
    const { container } = render(
      <AssistantMessageBlock
        message={message({
          thinking: 'A\n\n\nB\n\n* item 1\n* item 2\n\n**bold logic**\n\n```ts\nconst x = 1;\n```',
          text: 'raw error { "code": 500 }',
          status: 'error',
        })}
      />,
    )
    const thinkingEl = container.querySelector('.thread-thinking-text')
    expect(thinkingEl).not.toBeNull()
    const paragraphs = thinkingEl?.querySelectorAll('p')
    expect(paragraphs?.length).toBeGreaterThanOrEqual(2)
    expect(paragraphs?.[0]?.textContent).toBe('A')
    expect(paragraphs?.[1]?.textContent).toBe('B')
    expect(thinkingEl?.querySelector('strong')?.textContent).toBe('bold logic')
    expect(thinkingEl?.querySelectorAll('li')).toHaveLength(2)
    expect(thinkingEl?.querySelector('.md-code-shell')).not.toBeNull()

    // 错误正文 pre 原样保留未被 Markdown 篡改
    const rawError = container.querySelector('.thread-error-raw')
    expect(rawError).not.toBeNull()
    expect(rawError?.tagName).toBe('PRE')
    expect(rawError?.textContent).toBe('raw error { "code": 500 }')
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
