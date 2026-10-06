import { fireEvent, render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ToolOutputViewport } from '@/features/ai/runtime/thread-panel/messages/ToolOutputViewport'

/** jsdom 不布局，用固定尺寸模拟有界视口，才能验证跟随/暂停的状态机。 */
function stubScrollMetrics(element: HTMLElement, scrollHeight: number, clientHeight: number) {
  Object.defineProperty(element, 'scrollHeight', { value: scrollHeight, configurable: true })
  Object.defineProperty(element, 'clientHeight', { value: clientHeight, configurable: true })
}

describe('ToolOutputViewport', () => {
  it('is the single bounded readonly scroll owner and never truncates content', () => {
    const longText = Array.from({ length: 40 }, (_, index) => `line-${index + 1}`).join('\n')
    const { container } = render(
      <ToolOutputViewport followKey={longText}>{longText}</ToolOutputViewport>,
    )
    const output = container.querySelector<HTMLPreElement>('.thread-tool-output')

    expect(output).not.toBeNull()
    expect(output?.tagName).toBe('PRE')
    // 完整原文都在 DOM 中：不再有 N 行截断或「还有 N 行」提示。
    expect(output).toHaveTextContent('line-1')
    expect(output).toHaveTextContent('line-40')
    expect(output?.textContent).not.toContain('more line')
    expect(output?.textContent).not.toContain('earlier line')
    // 键盘与触屏都能滚回内部日志。
    expect(output).toHaveAttribute('tabindex', '0')
  })

  it('follows the tail while content grows, pauses on user scroll-up, and resumes at the bottom', () => {
    const { container, rerender } = render(
      <ToolOutputViewport followKey="a">streaming a</ToolOutputViewport>,
    )
    const output = container.querySelector<HTMLPreElement>('.thread-tool-output')
    expect(output).not.toBeNull()
    stubScrollMetrics(output!, 400, 100)

    // 仍贴底时新内容继续跟随底部。
    rerender(<ToolOutputViewport followKey="ab">streaming ab</ToolOutputViewport>)
    expect(output?.scrollTop).toBe(400)

    // 用户向历史方向滚动后立即暂停跟随，后续增长不得把视图拉回底部。
    output!.scrollTop = 60
    fireEvent.scroll(output!)
    rerender(<ToolOutputViewport followKey="abc">streaming abc</ToolOutputViewport>)
    expect(output?.scrollTop).toBe(60)

    // 主动回到底部后恢复跟随。
    output!.scrollTop = 300
    fireEvent.scroll(output!)
    rerender(<ToolOutputViewport followKey="abcd">streaming abcd</ToolOutputViewport>)
    expect(output?.scrollTop).toBe(400)
  })

  it('does not touch scroll position on mount when the element is already rendered', () => {
    const { container } = render(
      <ToolOutputViewport followKey="static">static body</ToolOutputViewport>,
    )
    const output = container.querySelector<HTMLPreElement>('.thread-tool-output')
    stubScrollMetrics(output!, 400, 100)
    output!.scrollTop = 120
    fireEvent.scroll(output!)

    // 与内容签名无关的重渲染不改变用户当前回看位置。
    expect(output?.scrollTop).toBe(120)
  })
})
