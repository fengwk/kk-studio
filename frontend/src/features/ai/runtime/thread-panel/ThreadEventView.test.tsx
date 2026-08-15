import { useRef, useState } from 'react'
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

/**
 * 受控渲染 harness：与真实 Pane 相同的接线 —— active 由 hook 持有（id 消失时
 * 自动回到最新事件），view 只负责派生与上报；detailOpen 跟随 selected。
 */
function Harness({
  events,
  initialScrollTop,
  onSelectSpy,
  onCloseDetailSpy,
}: {
  events: ThreadEventRecord[]
  initialScrollTop?: number | null
  onSelectSpy?: ReturnType<typeof vi.fn>
  onCloseDetailSpy?: ReturnType<typeof vi.fn>
}) {
  const transcriptBodyRef = useRef<HTMLDivElement>(null)
  const { activeEventId, setActiveEventId, eventsBodyRef } = useThreadPanelViewState(
    'thread-1',
    transcriptBodyRef,
    events,
  )
  const [selectedEventId, setSelectedEventId] = useState<string | null>(null)
  return (
    <ThreadEventView
      events={events}
      activeEventId={activeEventId}
      onActiveEventIdChange={setActiveEventId}
      bodyRef={eventsBodyRef}
      initialScrollTop={initialScrollTop ?? null}
      detailOpen={selectedEventId != null}
      onSelect={(event) => {
        setSelectedEventId(event.id)
        onSelectSpy?.(event)
      }}
      onCloseDetail={() => {
        setSelectedEventId(null)
        onCloseDetailSpy?.()
      }}
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
  it('renders one compact row per event (time + badge + summary) and follows the newest initially', () => {
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
    // 初始 active 是最新（末尾）事件；aria-activedescendant 指向它。
    expect(selectedId()).toBe(`thread-event-${events[1]!.id}`)
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

  it('navigates with ArrowUp/Down, Home/End, PageUp/PageDown (±8)', async () => {
    const user = userEvent.setup()
    const events = Array.from({ length: 15 }, (_, index) => record(`e${index + 1}`))
    render(<Harness events={events} />)
    await user.click(listbox())
    expect(selectedId()).toBe('thread-event-e15')

    await user.keyboard('{ArrowUp}')
    expect(selectedId()).toBe('thread-event-e14')
    await user.keyboard('{ArrowDown}')
    expect(selectedId()).toBe('thread-event-e15')
    // 边界不环绕。
    await user.keyboard('{ArrowDown}')
    expect(selectedId()).toBe('thread-event-e15')

    await user.keyboard('{Home}')
    expect(selectedId()).toBe('thread-event-e1')
    await user.keyboard('{End}')
    expect(selectedId()).toBe('thread-event-e15')

    // 步进 8：15 - 8 = 7，再 +8 回到 15。
    await user.keyboard('{PageUp}')
    expect(selectedId()).toBe('thread-event-e7')
    await user.keyboard('{PageDown}')
    expect(selectedId()).toBe('thread-event-e15')
  })

  it('opens the detail for the active item with Enter and Space', async () => {
    const user = userEvent.setup()
    const onSelectSpy = vi.fn()
    render(<Harness events={[record('e1'), record('e2'), record('e3')]} onSelectSpy={onSelectSpy} />)
    await user.click(listbox())
    await user.keyboard('{Home}{Enter}')
    expect(onSelectSpy).toHaveBeenCalledWith(expect.objectContaining({ id: 'e1' }))

    onSelectSpy.mockClear()
    await user.keyboard('{End}{ }')
    expect(onSelectSpy).toHaveBeenCalledWith(expect.objectContaining({ id: 'e3' }))
  })

  it('opens the detail on click and makes the clicked row active', async () => {
    const onSelectSpy = vi.fn()
    render(<Harness events={[record('e1'), record('e2'), record('e3')]} onSelectSpy={onSelectSpy} />)
    // fireEvent.click 只派发 click（不模拟指针移动）：点击打开详情并同时设为 active。
    fireEvent.click(screen.getByRole('option', { name: /Event e1/ }))
    expect(onSelectSpy).toHaveBeenCalledWith(expect.objectContaining({ id: 'e1' }))
    expect(selectedId()).toBe('thread-event-e1')
  })

  it('returns the active option to the newest event when its id disappears', () => {
    const first = render(<Harness events={[record('e1'), record('e2'), record('e3')]} />)
    fireEvent.mouseMove(screen.getByRole('option', { name: /Event e1/ }))
    expect(selectedId()).toBe('thread-event-e1')
    first.unmount()

    // 列表收缩且 active id（e1）已消失：hook 把 active 重置回最新事件。
    render(<Harness events={[record('e2'), record('e3')]} />)
    expect(selectedId()).toBe('thread-event-e3')
  })

  it('restores a saved scroll position on re-entry and sticks to bottom on first entry', () => {
    const events = Array.from({ length: 20 }, (_, index) => record(`e${index + 1}`))
    // 真实 DOM 属性：jsdom 不计算布局，用 getter spy 提供 scrollHeight/clientHeight。
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)

      // 首次进入（无保存位置）：贴底。
      const first = render(<Harness events={events} />)
      expect(listbox().scrollTop).toBe(600)
      first.unmount()

      // 重新进入（保存位置 120）：恢复，而不是贴底。
      const second = render(<Harness events={events} initialScrollTop={120} />)
      expect(listbox().scrollTop).toBe(120)
      // 恢复后 scrollTop 保持稳定（不因内容计数 effect 被拉回底部）。
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
    // test-setup 把 clientHeight 定义为 800：恢复位置 77 距底部 523px（>210 阈值），
    // stick 必须为 false，内容计数 effect 才不会把恢复位置拉回底部。
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)
      const view = render(
        <ThreadEventView
          events={events}
          activeEventId={null}
          onActiveEventIdChange={vi.fn()}
          bodyRef={externalRef}
          initialScrollTop={77}
          onSelect={vi.fn()}
          onCloseDetail={vi.fn()}
        />,
      )
      expect(externalRef.current).toBe(listbox())
      expect(listbox().scrollTop).toBe(77)
      view.unmount()
      // 卸载后外部 ref 被 React 清空。
      expect(externalRef.current).toBeNull()
    } finally {
      scrollHeightSpy.mockRestore()
      clientHeightSpy.mockRestore()
    }
  })

  it('changes active only on mousemove and a resting mouse never steals it', async () => {
    const user = userEvent.setup()
    render(<Harness events={[record('e1'), record('e2'), record('e3')]} />)
    await user.click(listbox())
    expect(selectedId()).toBe('thread-event-e3')

    // mousemove 到 e1 上：active 变为 e1。
    fireEvent.mouseMove(screen.getByRole('option', { name: /Event e1/ }))
    expect(selectedId()).toBe('thread-event-e1')

    // 键盘把 active 移到 e2；鼠标静止（不再派发 mousemove）时 active 保持 e2。
    await user.keyboard('{ArrowDown}')
    expect(selectedId()).toBe('thread-event-e2')
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(selectedId()).toBe('thread-event-e2')
  })

  it('closes the detail on Escape only when a detail is open', async () => {
    const user = userEvent.setup()
    const onCloseDetailSpy = vi.fn()
    const first = render(
      <Harness events={[record('e1')]} onCloseDetailSpy={onCloseDetailSpy} />,
    )
    // 打开详情（Enter），再 Escape 关闭。
    await user.click(listbox())
    await user.keyboard('{Enter}')
    expect(onCloseDetailSpy).not.toHaveBeenCalled()
    await user.keyboard('{Escape}')
    expect(onCloseDetailSpy).toHaveBeenCalledTimes(1)
    first.unmount()

    onCloseDetailSpy.mockClear()
    // detail 未打开：Escape 由 view 放行（不消费）。
    const second = render(
      <Harness events={[record('e1')]} onCloseDetailSpy={onCloseDetailSpy} />,
    )
    await user.click(listbox())
    await user.keyboard('{Escape}')
    expect(onCloseDetailSpy).not.toHaveBeenCalled()
    second.unmount()
  })

  it('shows an empty state when there are no events', () => {
    render(<Harness events={[]} />)
    expect(screen.getByText('暂无事件')).toBeInTheDocument()
    expect(listbox()).toBeInTheDocument()
  })
})
