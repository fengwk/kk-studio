import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ThinkingBlock } from '@/features/ai/runtime/thread-panel/messages/ThinkingBlock'

// jsdom 的测量 stub 为 7px/字符、容器宽 1200px：213 字符必然超宽，触发左侧省略。
const LONG_THINKING = `HEAD-${'x'.repeat(200)}-TAILEND`
const MIXED_THINKING = [
  '先读取 /usr/local/lib/node_modules/kk-studio 的入口，',
  '',
  '**结论**：路径与英文保持原顺序。',
].join('\n')

describe('ThinkingBlock', () => {
  it('returns null when thinking text is empty or pure whitespace', () => {
    // 测试意图：空思考或纯空白思考不能渲染空的 DOM 节点
    const { container: empty } = render(<ThinkingBlock thinking="" />)
    expect(empty).toBeEmptyDOMElement()

    const { container: whitespace } = render(<ThinkingBlock thinking={'  \n\t\n  '} />)
    expect(whitespace).toBeEmptyDOMElement()
  })

  it('collapses to a single flattened line with no header, title, or divider', () => {
    // 测试意图：默认收起只保留一行文本与一个展开按钮，不出现标题或内部分隔线
    const { container } = render(<ThinkingBlock thinking={'first\n\nsecond   third'} />)
    const section = container.querySelector('.thread-block-thinking')
    expect(section).not.toBeNull()
    expect(container.querySelector('.thread-block-label')).toBeNull()
    expect(container.querySelector('hr')).toBeNull()

    const line = container.querySelector('.thread-thinking-line')
    expect(line?.textContent).toBe('first second third')
    expect(container.querySelector('.md-root')).toBeNull()
    expect(container.querySelector('.thread-thinking-ellipsis')).toBeNull()

    const toggle = screen.getByRole('button', { name: '展开思考' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })

  it('keeps the newest suffix with a leading ellipsis when the line overflows', () => {
    // 测试意图：超出可用宽度时丢弃前文、保留最新尾部，并在左侧标记省略
    const { container } = render(<ThinkingBlock thinking={LONG_THINKING} />)
    const line = container.querySelector('.thread-thinking-line')
    expect(container.querySelector('.thread-thinking-ellipsis')).not.toBeNull()
    expect(line?.textContent?.startsWith('…')).toBe(true)
    expect(line?.textContent?.endsWith('-TAILEND')).toBe(true)
    // 不是整段，也不是最后一个自然行
    expect(line?.textContent).not.toContain('HEAD-')
  })

  it('expands the same block into the original Markdown through the current renderer', () => {
    // 测试意图：展开态用 MarkdownRenderer 渲染原始 Markdown，段落/粗体/路径都不被改写
    const { container } = render(<ThinkingBlock thinking={MIXED_THINKING} />)

    fireEvent.click(screen.getByRole('button', { name: '展开思考' }))

    const mdRoot = container.querySelector('.thread-thinking-text .md-root')
    expect(mdRoot).toHaveClass('md-tone-muted')
    const paragraphs = container.querySelectorAll('.thread-thinking-text p')
    expect(paragraphs).toHaveLength(2)
    expect(paragraphs[0]?.textContent).toBe('先读取 /usr/local/lib/node_modules/kk-studio 的入口，')
    expect(container.querySelector('strong')?.textContent).toBe('结论')
    // 展开后仍是同一个框，收起按钮回到第一行末尾
    expect(screen.getByRole('button', { name: '收起思考' })).toHaveAttribute('aria-expanded', 'true')
    expect(container.querySelector('.thread-thinking-line')).toBeNull()
  })

  it('keeps the user decision and updates the tail while streaming settles', () => {
    // 测试意图：流式刷新只更新内容，不重置用户的展开选择，也不在结束时自动展开
    const { container, rerender } = render(<ThinkingBlock thinking={'step one'} />)
    expect(container.querySelector('.thread-thinking-line')?.textContent).toBe('step one')

    rerender(<ThinkingBlock thinking={'step one\n\nstep two'} />)
    expect(container.querySelector('.thread-thinking-line')?.textContent).toBe('step one step two')

    fireEvent.click(screen.getByRole('button', { name: '展开思考' }))
    rerender(<ThinkingBlock thinking={'step one\n\nstep two\n\nstep three'} />)
    expect(screen.getByRole('button', { name: '收起思考' })).toHaveAttribute('aria-expanded', 'true')
    expect(container.querySelectorAll('.thread-thinking-text p')).toHaveLength(3)

    fireEvent.click(screen.getByRole('button', { name: '收起思考' }))
    expect(container.querySelector('.thread-thinking-line')?.textContent).toBe(
      'step one step two step three',
    )
  })
})
