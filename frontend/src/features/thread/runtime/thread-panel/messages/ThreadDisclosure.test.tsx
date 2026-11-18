import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ThreadDisclosure } from '@/features/thread/runtime/thread-panel/messages/ThreadDisclosure'
import type { ThreadDisclosureProps } from '@/features/thread/runtime/thread-panel/messages/thread-disclosure.types'
import { TRANSCRIPT_READING_INTENT_EVENT } from '@/features/thread/runtime/thread-timeline/transcript-reading'

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

  it('keeps the header chevron as the only collapse control for long expanded content', async () => {
    const user = userEvent.setup()
    render(<ControlledDisclosure {...baseProps({ initialExpanded: true })} />)
    expect(document.querySelector('.thread-disclosure-collapse')).toBeNull()
    const toggle = screen.getByRole('button', { name: '收起详情' })
    await user.click(toggle)
    expect(screen.getByRole('button', { name: '展开详情' })).toHaveAttribute('aria-expanded', 'false')
    expect(document.querySelector('.thread-disclosure-collapse')).toBeNull()
  })
})
