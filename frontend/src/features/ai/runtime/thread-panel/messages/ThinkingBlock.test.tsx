import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ThinkingBlock } from '@/features/ai/runtime/thread-panel/messages/ThinkingBlock'

describe('ThinkingBlock', () => {
  it('returns null when thinking text is empty or pure whitespace', () => {
    // 测试意图：空思考或纯空白思考不能渲染空的 DOM 节点
    const { container: empty } = render(<ThinkingBlock thinking="" streaming={false} />)
    expect(empty).toBeEmptyDOMElement()

    const { container: whitespace } = render(<ThinkingBlock thinking={'  \n\t\n  '} streaming={false} />)
    expect(whitespace).toBeEmptyDOMElement()
  })

  it('renders markdown paragraphs from upstream Gemini triple newlines collapsing excess blank lines', () => {
    // 测试意图：Upstream Gemini 发送的 'A\n\n\nB\n\n\n' 经 Markdown 渲染折叠为两个干净段落，消除纯文本 pre-wrap 下的巨大空行
    const { container } = render(<ThinkingBlock thinking={'A\n\n\nB\n\n\n'} streaming={false} />)
    const section = container.querySelector('.thread-block-thinking')
    expect(section).not.toBeNull()
    expect(section).not.toHaveClass('streaming')

    const mdRoot = container.querySelector('.md-root')
    expect(mdRoot).toHaveClass('md-tone-muted')

    const paragraphs = container.querySelectorAll('.thread-thinking-text p')
    expect(paragraphs).toHaveLength(2)
    expect(paragraphs[0]?.textContent).toBe('A')
    expect(paragraphs[1]?.textContent).toBe('B')
  })

  it('renders rich markdown formatting including bold, lists, and code blocks with muted tone', () => {
    // 测试意图：思考块以 muted tone 渲染完整 Markdown 能力（粗体、列表、代码块、内联代码）
    const content = [
      '**Analysis:**',
      '',
      '* Step 1: inspect code',
      '* Step 2: run tests',
      '',
      'Inline `variable` check:',
      '```ts',
      'const valid = true',
      '```',
    ].join('\n')

    const { container } = render(<ThinkingBlock thinking={content} streaming={true} />)
    const section = container.querySelector('.thread-block-thinking')
    expect(section).toHaveClass('streaming')

    expect(container.querySelector('strong')?.textContent).toBe('Analysis:')
    const listItems = container.querySelectorAll('li')
    expect(listItems).toHaveLength(2)
    expect(listItems[0]?.textContent).toContain('Step 1: inspect code')
    expect(listItems[1]?.textContent).toContain('Step 2: run tests')

    const inlineCode = container.querySelector('.md-inline-code')
    expect(inlineCode?.textContent).toBe('variable')

    const codeShell = container.querySelector('.md-code-shell')
    expect(codeShell).not.toBeNull()
    expect(codeShell?.textContent).toContain('const valid = true')
  })
})
