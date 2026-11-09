import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ThreadDisclosure } from '@/features/thread/runtime/thread-panel/messages/ThreadDisclosure'
import type { ThreadDisclosureProps } from '@/features/thread/runtime/thread-panel/messages/thread-disclosure.types'
import { TRANSCRIPT_READING_INTENT_EVENT } from '@/features/thread/runtime/transcript-reading'

function ControlledDisclosure({
  initialExpanded = false,
  ...props
}: Omit<ThreadDisclosureProps, 'expanded' | 'onExpandedChange'> & {
  initialExpanded?: boolean
}) {
  const [expanded, setExpanded] = useState(initialExpanded)
  return (
    <ThreadDisclosure
      {...props}
      expanded={expanded}
      onExpandedChange={setExpanded}
    />
  )
}

function baseProps(overrides: Partial<ThreadDisclosureProps> = {}): Omit<ThreadDisclosureProps, 'expanded' | 'onExpandedChange'> {
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
})
