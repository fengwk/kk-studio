import { fireEvent, render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { TRANSCRIPT_READING_INTENT_EVENT } from '@/features/ai/runtime/transcript-reading'
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

  it('follows the tail only for continuous logs, pausing on user scroll-up and resuming at the bottom', () => {
    // bash 持续日志：followTail 开启时才跟随尾部，用户上滚立即暂停，自己回底恢复。
    const { container, rerender } = render(
      <ToolOutputViewport followKey="a" followTail>streaming a</ToolOutputViewport>,
    )
    const output = container.querySelector<HTMLPreElement>('.thread-tool-output')
    expect(output).not.toBeNull()
    stubScrollMetrics(output!, 400, 100)

    // 仍贴底时新内容继续跟随底部。
    rerender(<ToolOutputViewport followKey="ab" followTail>streaming ab</ToolOutputViewport>)
    expect(output?.scrollTop).toBe(400)

    // 用户向历史方向滚动后立即暂停跟随，后续增长不得把视图拉回底部。
    output!.scrollTop = 60
    fireEvent.scroll(output!)
    rerender(<ToolOutputViewport followKey="abc" followTail>streaming abc</ToolOutputViewport>)
    expect(output?.scrollTop).toBe(60)

    // 主动回到底部后恢复跟随。
    output!.scrollTop = 300
    fireEvent.scroll(output!)
    rerender(<ToolOutputViewport followKey="abcd" followTail>streaming abcd</ToolOutputViewport>)
    expect(output?.scrollTop).toBe(400)

    // 持续日志结束（followTail 关闭）：即使仍在跟随也不再自动贴底。
    output!.scrollTop = 120
    rerender(<ToolOutputViewport followKey="abcd">settled abcd</ToolOutputViewport>)
    expect(output?.scrollTop).toBe(120)
  })

  it('reads static Text/JSON from the top and never pulls to the bottom as content grows', () => {
    // 静态正文（结果 Text/JSON、write/edit/task 参数）默认 followTail=false：
    // 挂载即从顶部读（挂载写入必须可观测，因此用原型级尺寸桩），内容增长也不强拉到底部。
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(400)
      clientHeightSpy.mockReturnValue(100)
      const { container, rerender } = render(
        <ToolOutputViewport followKey="line-1">line-1</ToolOutputViewport>,
      )
      const output = container.querySelector<HTMLPreElement>('.thread-tool-output')
      expect(output?.scrollTop).toBe(0)

      rerender(<ToolOutputViewport followKey={'x'.repeat(2000)}>{'x'.repeat(2000)}</ToolOutputViewport>)
      expect(output?.scrollTop).toBe(0)

      // 用户自己滚到底部也不改变静态语义：后续内容增长仍不移动位置。
      output!.scrollTop = 400
      fireEvent.scroll(output!)
      rerender(<ToolOutputViewport followKey={'y'.repeat(3000)}>{'y'.repeat(3000)}</ToolOutputViewport>)
      expect(output?.scrollTop).toBe(400)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('starts following the tail when the body becomes a continuous log, and stops when it settles', () => {
    // 同一节点从静态正文变为持续日志（同一 toolCallId 的新一轮流式输出）：跟随尾部；
    // 转为终态静态正文后停在当前阅读位置，不再自动贴底。
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(400)
      clientHeightSpy.mockReturnValue(100)
      const { container, rerender } = render(
        <ToolOutputViewport followKey="a">static a</ToolOutputViewport>,
      )
      const output = container.querySelector<HTMLPreElement>('.thread-tool-output')
      expect(output?.scrollTop).toBe(0)

      rerender(<ToolOutputViewport followKey="ab" followTail>streaming ab</ToolOutputViewport>)
      expect(output?.scrollTop).toBe(400)

      rerender(<ToolOutputViewport followKey="abc">settled abc</ToolOutputViewport>)
      expect(output?.scrollTop).toBe(400)
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
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

  it('bubbles a reading intent when the user scrolls back into history', () => {
    // 内层回看必须通知 transcript 外层暂停贴底（只暂停，恢复由外层决定），
    // 否则并发的流式增长会把用户正在看的卡片拉走。
    const onReadingIntent = vi.fn()
    const { container } = render(
      <div>
        <ToolOutputViewport followKey="a">streaming a</ToolOutputViewport>
      </div>,
    )
    const host = container.firstElementChild as HTMLElement
    host.addEventListener(TRANSCRIPT_READING_INTENT_EVENT, onReadingIntent)
    const output = container.querySelector<HTMLPreElement>('.thread-tool-output')
    stubScrollMetrics(output!, 400, 100)

    // 跟随底部时向下（贴底）的滚动不是回看意图。
    output!.scrollTop = 400
    fireEvent.scroll(output!)
    expect(onReadingIntent).not.toHaveBeenCalled()

    // 向历史方向滚动：冒泡一次阅读意图。
    output!.scrollTop = 120
    fireEvent.scroll(output!)
    expect(onReadingIntent).toHaveBeenCalledTimes(1)
    expect(onReadingIntent.mock.calls[0]?.[0]).toMatchObject({
      bubbles: true,
      detail: { source: 'tool-output' },
    })

    // 内层自己滚回底部只恢复内层跟随，不再向外层声明任何意图。
    output!.scrollTop = 400
    fireEvent.scroll(output!)
    expect(onReadingIntent).toHaveBeenCalledTimes(1)
  })
})
