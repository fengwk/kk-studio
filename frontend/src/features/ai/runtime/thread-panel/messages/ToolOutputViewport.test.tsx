import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ToolOutputViewport } from '@/features/ai/runtime/thread-panel/messages/ToolOutputViewport'

describe('ToolOutputViewport', () => {
  it('keeps the formatted text without creating a focusable nested scroll owner', () => {
    const { container } = render(
      <ToolOutputViewport text={'... (2 earlier lines)\none\ntwo\nthree\nfour'} />,
    )
    const output = container.querySelector('pre')

    // 上游 formatter 已完成尾随窗口裁剪；viewport 只负责展示，不抢占滚轮/键盘焦点。
    expect(output).toHaveTextContent('... (2 earlier lines)')
    expect(output).toHaveTextContent('four')
    expect(output).not.toHaveAttribute('tabindex')
  })

  it('accepts a collapsed line budget and removes the height cap when expanded', () => {
    const { container, rerender } = render(
      <ToolOutputViewport text={'one\ntwo'} maxLines={10} />,
    )
    const output = container.querySelector('pre')

    expect(output).toHaveStyle({ '--thread-tool-output-lines': '10' })
    expect(output).not.toHaveClass('is-expanded')

    rerender(<ToolOutputViewport text={'one\ntwo'} maxLines={null} />)
    expect(output).toHaveClass('is-expanded')
    expect(output?.style.getPropertyValue('--thread-tool-output-lines')).toBe('')
  })
})
