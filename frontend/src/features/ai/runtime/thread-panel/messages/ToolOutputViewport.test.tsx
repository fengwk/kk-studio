import { fireEvent, render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ToolOutputViewport } from '@/features/ai/runtime/thread-panel/messages/ToolOutputViewport'

describe('ToolOutputViewport', () => {
  it('keeps full output and follows the tail only while the user remains at the bottom', () => {
    const { container, rerender } = render(<ToolOutputViewport text={'one\ntwo\nthree\nfour\nfive\nsix'} />)
    const output = container.querySelector('pre')
    expect(output).not.toBeNull()
    if (!output) {
      return
    }
    Object.defineProperties(output, {
      scrollHeight: { configurable: true, value: 120 },
      clientHeight: { configurable: true, value: 30 },
    })

    output.scrollTop = 90
    fireEvent.scroll(output)
    rerender(<ToolOutputViewport text={'one\ntwo\nthree\nfour\nfive\nsix\nseven'} />)
    expect(output.scrollTop).toBe(120)
    expect(output).toHaveTextContent('one')
    expect(output).toHaveTextContent('seven')

    output.scrollTop = 10
    fireEvent.scroll(output)
    rerender(<ToolOutputViewport text={'one\ntwo\nthree\nfour\nfive\nsix\nseven\neight'} />)
    expect(output.scrollTop).toBe(10)
  })
})
