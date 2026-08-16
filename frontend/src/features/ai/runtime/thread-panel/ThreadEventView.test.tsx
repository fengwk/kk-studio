import { useRef } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ThreadEventView } from '@/features/ai/runtime/thread-panel/ThreadEventView'
import { useThreadPanelViewState } from '@/features/ai/runtime/thread-panel/useThreadPanelViewState'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'

function record(id: string, overrides: Partial<ThreadEventRecord> = {}): ThreadEventRecord {
  return {
    id,
    source: 'entry',
    entryId: id,
    turnStartEntryId: 'turn-1',
    turnNumber: 1,
    kind: 'ASSISTANT_MESSAGE',
    status: 'completed',
    title: `Event ${id}`,
    summary: `summary ${id}`,
    createdAt: '2026-07-28T10:00:00Z',
    details: [{ label: 'Entry ID', value: id }],
    rawJson: '{"x":1}',
    ...overrides,
  }
}

/** 与真实 Pane 相同：选中由 hook 持有；未选中时详情关闭。 */
function Harness({
  events,
  initialScrollTop,
}: {
  events: ThreadEventRecord[]
  initialScrollTop?: number | null
}) {
  const transcriptBodyRef = useRef<HTMLDivElement>(null)
  const { selectedEventId, selectEvent, eventsBodyRef } = useThreadPanelViewState(
    'thread-1',
    transcriptBodyRef,
    events,
  )
  return (
    <ThreadEventView
      events={events}
      selectedEventId={selectedEventId}
      onSelectedEventIdChange={selectEvent}
      bodyRef={eventsBodyRef}
      initialScrollTop={initialScrollTop ?? null}
    />
  )
}

function listbox() {
  return screen.getByRole('listbox', { name: '事件' })
}

function selectedId() {
  return listbox().getAttribute('aria-activedescendant')
}

describe('ThreadEventView', () => {
  it('renders one compact row per event and starts with no selection', () => {
    const events = [record('e1'), record('e2', { status: 'running' })]
    render(<Harness events={events} />)
    expect(listbox()).toBeInTheDocument()
    const options = screen.getAllByRole('option')
    expect(options).toHaveLength(2)

    const first = options[0]!
    expect(first.className).toContain('thread-event')
    expect(first.className).toContain('kind-ASSISTANT_MESSAGE')
    expect(first.className).toContain('status-completed')
    expect(first.querySelector('[data-event-time]')?.textContent).toMatch(/^\d{2}:\d{2}:\d{2}$/)
    expect(first.querySelector('.thread-event-badge')?.textContent).toBe('Event e1')
    expect(first.querySelector('.thread-event-summary')?.textContent).toBe('summary e1')
    expect(selectedId()).toBeNull()
    expect(options.every((option) => !option.className.includes('active'))).toBe(true)
  })

  it('pins a 10-line system prompt preview above the independently scrolling event list', () => {
    render(
      <ThreadEventView
        events={[record('e1')]}
        selectedEventId={null}
        onSelectedEventIdChange={vi.fn()}
        systemPrompt={'line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\nline11'}
      />,
    )
    const preview = screen.getByLabelText('系统提示词')
    expect(preview).toHaveTextContent('line1')
    expect(preview).toHaveTextContent('line11')
    expect(preview.querySelector('.thread-system-prompt-body')).not.toBeNull()
    expect(listbox().parentElement).toHaveClass('thread-events-shell')
  })

  it('renders a pulse dot only for running rows and failed rows keep the danger class', () => {
    render(
      <Harness
        events={[
          record('e1', { status: 'running' }),
          record('e2', { status: 'failed' }),
          record('e3'),
        ]}
      />,
    )
    const options = screen.getAllByRole('option')
    expect(options[0]!.querySelector('.thread-event-pulse')).not.toBeNull()
    expect(options[1]!.querySelector('.thread-event-pulse')).toBeNull()
    expect(options[1]!.className).toContain('status-failed')
    expect(options[2]!.querySelector('.thread-event-pulse')).toBeNull()
  })

  it('selects a row on click and moves only with ArrowUp/Down after a selection exists', async () => {
    const user = userEvent.setup()
    render(<Harness events={[record('e1'), record('e2'), record('e3')]} />)
    await user.click(listbox())
    expect(selectedId()).toBeNull()
    await user.keyboard('{ArrowUp}')
    expect(selectedId()).toBeNull()

    fireEvent.click(screen.getByRole('option', { name: /Event e2/ }))
    expect(selectedId()).toBe('thread-event-e2')
    await user.keyboard('{ArrowUp}')
    expect(selectedId()).toBe('thread-event-e1')
    await user.keyboard('{ArrowDown}')
    expect(selectedId()).toBe('thread-event-e2')
    await user.keyboard('{ArrowDown}')
    expect(selectedId()).toBe('thread-event-e3')
    await user.keyboard('{ArrowDown}')
    expect(selectedId()).toBe('thread-event-e3')
    await user.keyboard('{Home}')
    expect(selectedId()).toBe('thread-event-e3')
    await user.keyboard('{PageUp}')
    expect(selectedId()).toBe('thread-event-e3')
  })

  it('does not change selection on hover', () => {
    render(<Harness events={[record('e1'), record('e2'), record('e3')]} />)
    fireEvent.click(screen.getByRole('option', { name: /Event e2/ }))
    expect(selectedId()).toBe('thread-event-e2')
    fireEvent.mouseMove(screen.getByRole('option', { name: /Event e1/ }))
    expect(selectedId()).toBe('thread-event-e2')
  })

  it('clears the selection on Escape only when a row is selected', async () => {
    const user = userEvent.setup()
    render(<Harness events={[record('e1'), record('e2')]} />)
    await user.click(listbox())
    await user.keyboard('{Escape}')
    expect(selectedId()).toBeNull()

    fireEvent.click(screen.getByRole('option', { name: /Event e1/ }))
    expect(selectedId()).toBe('thread-event-e1')
    await user.keyboard('{Escape}')
    expect(selectedId()).toBeNull()
  })

  it('clears the selection when the selected id disappears', () => {
    const first = render(<Harness events={[record('e1'), record('e2'), record('e3')]} />)
    fireEvent.click(screen.getByRole('option', { name: /Event e1/ }))
    expect(selectedId()).toBe('thread-event-e1')
    first.unmount()

    render(<Harness events={[record('e2'), record('e3')]} />)
    expect(selectedId()).toBeNull()
  })

  it('restores a saved scroll position on re-entry and sticks to bottom on first entry', () => {
    const events = Array.from({ length: 20 }, (_, index) => record(`e${index + 1}`))
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)

      const first = render(<Harness events={events} />)
      expect(listbox().scrollTop).toBe(600)
      first.unmount()

      const second = render(<Harness events={events} initialScrollTop={120} />)
      expect(listbox().scrollTop).toBe(120)
      expect(listbox().scrollTop).toBe(120)
      second.unmount()
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('accepts an external bodyRef and restores its saved scroll position', () => {
    const events = Array.from({ length: 20 }, (_, index) => record(`e${index + 1}`))
    const externalRef = { current: null as HTMLDivElement | null }
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const view = render(
        <ThreadEventView
          events={events}
          selectedEventId={null}
          onSelectedEventIdChange={vi.fn()}
          bodyRef={externalRef}
          initialScrollTop={77}
        />,
      )
      expect(externalRef.current).toBe(listbox())
      expect(listbox().scrollTop).toBe(77)
      view.unmount()
      expect(externalRef.current).toBeNull()
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('shows an empty state when there are no events', () => {
    render(<Harness events={[]} />)
    expect(screen.getByText('暂无事件')).toBeInTheDocument()
    expect(listbox()).toBeInTheDocument()
  })
})
