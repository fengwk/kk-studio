import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ToolOutputViewport } from '@/features/thread/runtime/thread-panel/messages/ToolOutputViewport'

describe('ToolOutputViewport', () => {
  it('renders complete transcript text without creating an inner scroll region', () => {
    const longText = Array.from({ length: 40 }, (_, index) => `line-${index + 1}`).join('\n')
    const { container } = render(
      <ToolOutputViewport bounded={false}>{longText}</ToolOutputViewport>,
    )
    const output = container.querySelector<HTMLPreElement>('.thread-tool-output')

    expect(output).not.toBeNull()
    expect(output?.tagName).toBe('PRE')
    expect(output).toHaveTextContent('line-1')
    expect(output).toHaveTextContent('line-40')
    // 对话记录里的正文不抢键盘焦点，也不声明有界滚动。
    expect(output).not.toHaveAttribute('tabindex')
    expect(output).not.toHaveClass('is-bounded')
  })

  it('keeps a bounded focusable viewport only for the approval card', () => {
    const { container } = render(
      <ToolOutputViewport bounded className="is-edit">-old\n+new</ToolOutputViewport>,
    )
    const output = container.querySelector<HTMLPreElement>('.thread-tool-output')
    expect(output).toHaveClass('thread-tool-pre', 'thread-tool-output', 'is-bounded', 'is-edit')
    expect(output).toHaveAttribute('tabindex', '0')
  })
})
