import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ThreadDisclosure } from '@/features/thread/runtime/thread-panel/messages/ThreadDisclosure'
import type { ThreadDisclosureProps } from '@/features/thread/runtime/thread-panel/messages/thread-disclosure.types'
import { TRANSCRIPT_READING_INTENT_EVENT } from '@/features/thread/runtime/transcript-reading'

function ControlledDisclosure({
  initialExpanded = false,
  ...props
}: ControlledProps) {
  const [expanded, setExpanded] = useState(initialExpanded)
  return (
    <ThreadDisclosure
      {...props}
      expanded={expanded}
      onExpandedChange={setExpanded}
    />
  )
}

type ControlledProps = Omit<ThreadDisclosureProps, 'expanded' | 'onExpandedChange'> & {
  initialExpanded?: boolean
}

function baseProps(overrides: Partial<ControlledProps> = {}): ControlledProps {
  return {
    header: <span>bash npm test</span>,
    tone: 'success',
    hasDetails: true,
    label: 'bash: Succeeded',
    preview: <p>last ten lines</p>,
    body: <p>supplement</p>,
    ...overrides,
  }
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('ThreadDisclosure', () => {
  it('renders the shared shell with a compound tone class and semantic label', () => {
    render(<ControlledDisclosure {...baseProps()} />)
    const section = screen.getByRole('region', { name: 'bash: Succeeded' })
    expect(section).toHaveClass('thread-block', 'thread-disclosure', 'is-success')
    expect(section).toHaveAttribute('title', 'bash: Succeeded')
    expect(section.querySelector('.thread-disclosure-preview')).toHaveTextContent('last ten lines')
    expect(section.querySelector('.thread-disclosure-body')).toHaveTextContent('supplement')
  })

  it('uses the header as the first-row box with clamped text and actions as aligned siblings', () => {
    // 意图：外层 header 负责统一首行盒，clamp 只作用于文本，动作作为首行同级项定位。
    const { container } = render(
      <ControlledDisclosure {...baseProps({
        header: <span>two-line header content</span>,
        actions: <button type="button">Action</button>,
      })} />,
    )
    const header = container.querySelector('.thread-disclosure-header')
    const main = header?.querySelector('.thread-disclosure-header-main')
    const text = header?.querySelector('.thread-disclosure-header-text.is-clamped')
    const actions = header?.querySelector('.thread-disclosure-actions')

    expect(header?.firstElementChild).toBe(main)
    // 首行留白在外层首行盒，clamp 节点只包文本。
    expect(main?.firstElementChild).toBe(text)
    expect(actions).toHaveClass('thread-disclosure-actions')
    expect(actions?.parentElement).toBe(header)
    expect(header?.querySelector('.thread-disclosure-toggle')).not.toBeNull()
  })

  it('hides the chevron when the collapsed and expanded views are identical', () => {
    const { container } = render(
      <ControlledDisclosure {...baseProps({
        hasDetails: false,
        preview: undefined,
        body: undefined,
        actions: undefined,
      })} />,
    )
    expect(container.querySelector('.thread-disclosure-toggle')).toBeNull()
  })

  it('exposes the collapse state on the chevron and notifies transcript readers', async () => {
    const user = userEvent.setup()
    const onReadingIntent = vi.fn()
    document.addEventListener(TRANSCRIPT_READING_INTENT_EVENT, onReadingIntent)
    try {
      render(<ControlledDisclosure {...baseProps()} />)
      const toggle = screen.getByRole('button', { name: '展开详情' })
      expect(toggle).toHaveAttribute('aria-expanded', 'false')

      await user.click(toggle)
      expect(screen.getByRole('button', { name: '收起详情' })).toHaveAttribute(
        'aria-expanded',
        'true',
      )
      expect(onReadingIntent).toHaveBeenCalledTimes(1)
      expect(onReadingIntent.mock.calls[0]?.[0]).toMatchObject({ bubbles: true })
    } finally {
      document.removeEventListener(TRANSCRIPT_READING_INTENT_EVENT, onReadingIntent)
    }
  })

  it('always keeps the expanded chevron reachable even without hidden details', () => {
    render(<ControlledDisclosure {...baseProps({ initialExpanded: true, hasDetails: false })} />)
    expect(screen.getByRole('button', { name: '收起详情' })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
  })

  it('detects a CSS-clamped header and offers a chevron in the collapsed view', () => {
    vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(60)
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(40)
    render(<ControlledDisclosure {...baseProps({
      hasDetails: false,
      preview: undefined,
      body: undefined,
    })} />)
    expect(screen.getByRole('button', { name: '展开详情' })).toBeInTheDocument()
  })

  it('remeasures the clamped header when its content grows without changing the box size', () => {
    let headerHeight = 40
    vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockImplementation(() => headerHeight)
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(40)
    const { rerender } = render(
      <ControlledDisclosure
        {...baseProps({
          header: <span>two lines that still fit</span>,
          hasDetails: false,
          preview: undefined,
          body: undefined,
        })}
      />,
    )
    expect(screen.queryByRole('button', { name: '展开详情' })).not.toBeInTheDocument()

    // 第三行被 CSS clamp 掉时元素外框仍是两行高，只有 scrollHeight 增长。
    headerHeight = 60
    rerender(
      <ControlledDisclosure
        {...baseProps({
          header: <span>three lines now overflow the clamp</span>,
          hasDetails: false,
          preview: undefined,
          body: undefined,
        })}
      />,
    )
    expect(screen.getByRole('button', { name: '展开详情' })).toBeInTheDocument()

    // 内容缩短后 chevron 必须同步消失，不能留下不可用的展开入口。
    headerHeight = 40
    rerender(
      <ControlledDisclosure
        {...baseProps({
          header: <span>short again</span>,
          hasDetails: false,
          preview: undefined,
          body: undefined,
        })}
      />,
    )
    expect(screen.queryByRole('button', { name: '展开详情' })).not.toBeInTheDocument()
  })

  it('does not render the bottom collapse action before the card exceeds the viewport', () => {
    // jsdom 的布局桩把每个元素都报告为 800px 高；抬高视口以验证「不高于可视区时不出现」。
    const originalInnerHeight = window.innerHeight
    Object.defineProperty(window, 'innerHeight', { value: 2000, configurable: true })
    try {
      render(<ControlledDisclosure {...baseProps({ initialExpanded: true })} />)
      expect(document.querySelector('.thread-disclosure-collapse')).toBeNull()
    } finally {
      Object.defineProperty(window, 'innerHeight', {
        value: originalInnerHeight,
        configurable: true,
      })
    }
  })

  it('collapses a long card, aligns its top in the nearest scroller and returns keyboard focus', () => {
    let frame: FrameRequestCallback | undefined
    vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => {
      frame = callback
      return 1
    }))
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(400)
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
      const top = this.matches('.thread-disclosure') ? 140 : 40
      return new DOMRect(0, top, 600, 800)
    })
    const { container } = render(
      <div style={{ overflowY: 'auto' }}>
        <ControlledDisclosure {...baseProps({ initialExpanded: true })} />
      </div>,
    )
    const scroller = container.firstElementChild as HTMLElement
    scroller.scrollTop = 20

    // 验证收起后的定位与焦点，不只断言按钮存在；布局回调在 React 提交后执行。
    fireEvent.click(screen.getByRole('button', { name: /^收起$/ }))
    expect(screen.getByRole('button', { name: '展开详情' })).toHaveAttribute('aria-expanded', 'false')
    act(() => frame?.(0))
    expect(scroller.scrollTop).toBe(120)
    expect(screen.getByRole('button', { name: '展开详情' })).toHaveFocus()
    expect(screen.queryByRole('button', { name: /^收起$/ })).toBeNull()
  })

  it('uses document scrolling for a long standalone card without a transcript scroller', () => {
    let frame: FrameRequestCallback | undefined
    vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => {
      frame = callback
      return 1
    }))
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 100, 600, 2000))
    const { container } = render(<ControlledDisclosure {...baseProps({ initialExpanded: true })} />)
    const card = container.querySelector('.thread-disclosure') as HTMLElement
    const scrollIntoView = vi.spyOn(card, 'scrollIntoView')
    fireEvent.click(screen.getByRole('button', { name: /^收起$/ }))
    act(() => frame?.(0))
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'start' })
    expect(screen.getByRole('button', { name: '展开详情' })).toHaveFocus()
  })

  it('updates the header and long-card affordance on resize without ResizeObserver and releases listeners', () => {
    // 覆盖无 observer 时的真实 resize 降级，确保容器卸载后不遗留 window 监听。
    vi.stubGlobal('ResizeObserver', undefined)
    let height = 40
    vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockImplementation(() => height)
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(40)
    const removeListener = vi.spyOn(window, 'removeEventListener')
    const { unmount } = render(<ControlledDisclosure {...baseProps({ hasDetails: false })} />)
    expect(screen.queryByRole('button', { name: '展开详情' })).toBeNull()
    height = 60
    fireEvent(window, new Event('resize'))
    fireEvent.click(screen.getByRole('button', { name: '展开详情' }))
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 600, 2000))
    fireEvent(window, new Event('resize'))
    expect(screen.getByRole('button', { name: /^收起$/ })).toBeInTheDocument()
    unmount()
    expect(removeListener.mock.calls.filter(([event]) => event === 'resize')).toHaveLength(2)
  })
})
