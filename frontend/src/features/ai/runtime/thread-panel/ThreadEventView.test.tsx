import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ThreadEventView } from '@/features/ai/runtime/thread-panel/ThreadEventView'
import type { ThreadEventItem } from '@/features/ai/runtime/thread-events'

function eventItem(id: string, title = `Event ${id}`): ThreadEventItem {
  return {
    id,
    source: 'entry',
    title,
    text: `summary ${id}`,
    createTime: '2026-07-28T10:00:00Z',
    details: [{ label: 'Entry ID', value: id }],
    payloadJson: '{"x":1}',
  }
}

function renderView(
  overrides: Partial<{
    events: ThreadEventItem[]
    detailOpen: boolean
    onActivate: (event: ThreadEventItem) => void
    onCloseDetail: () => void
  }> = {},
) {
  const onActivate = overrides.onActivate ?? vi.fn()
  const onCloseDetail = overrides.onCloseDetail ?? vi.fn()
  const events = overrides.events ?? [eventItem('e1'), eventItem('e2'), eventItem('e3')]
  const view = render(
    <ThreadEventView
      events={events}
      detailOpen={overrides.detailOpen ?? false}
      onActivate={onActivate}
      onCloseDetail={onCloseDetail}
    />,
  )
  return { onActivate, onCloseDetail, events, unmount: view.unmount }
}

function listbox() {
  return screen.getByRole('listbox', { name: '事件' })
}

function selectedId() {
  return listbox().getAttribute('aria-activedescendant')
}

describe('ThreadEventView', () => {
  it('renders one option per event and follows the newest event initially', () => {
    const { events } = renderView()
    expect(listbox()).toBeInTheDocument()
    expect(screen.getAllByRole('option')).toHaveLength(3)
    // 初始 active 是最新（末尾）事件；aria-activedescendant 指向它。
    expect(selectedId()).toBe(`thread-event-${events[2]!.id}`)
  })

  it('navigates with ArrowUp/Down, Home/End, PageUp/PageDown', async () => {
    const user = userEvent.setup()
    const events = Array.from({ length: 15 }, (_, index) => eventItem(`e${index + 1}`))
    renderView({ events })
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

    await user.keyboard('{PageUp}')
    expect(selectedId()).toBe('thread-event-e5')
    await user.keyboard('{PageDown}')
    expect(selectedId()).toBe('thread-event-e15')
  })

  it('opens the detail for the active item with Enter and Space', async () => {
    const user = userEvent.setup()
    const { onActivate, events } = renderView()
    await user.click(listbox())
    await user.keyboard('{Home}{Enter}')
    expect(onActivate).toHaveBeenCalledWith(expect.objectContaining({ id: 'e1' }))

    onActivate.mockClear()
    await user.keyboard('{End}{ }')
    expect(onActivate).toHaveBeenCalledWith(expect.objectContaining({ id: events[2]!.id }))
  })

  it('opens the detail on click and makes the clicked row active', async () => {
    const { onActivate } = renderView()
    // fireEvent.click 只派发 click（不模拟指针移动）：点击打开详情并同时设为 active。
    fireEvent.click(screen.getByRole('option', { name: /Event e1/ }))
    expect(onActivate).toHaveBeenCalledWith(expect.objectContaining({ id: 'e1' }))
    expect(selectedId()).toBe('thread-event-e1')
  })

  it('returns the active option to the newest event when its id disappears', () => {
    const first = renderView({ events: [eventItem('e1'), eventItem('e2'), eventItem('e3')] })
    fireEvent.mouseMove(screen.getByRole('option', { name: /Event e1/ }))
    expect(selectedId()).toBe('thread-event-e1')
    first.unmount()

    // 列表收缩且 active id（e1）已消失：active 回到最新事件。
    renderView({ events: [eventItem('e2'), eventItem('e3')] })
    expect(selectedId()).toBe('thread-event-e3')
  })

  it('restores a saved scroll position on re-entry and sticks to bottom on first entry', () => {
    const events = Array.from({ length: 20 }, (_, index) => eventItem(`e${index + 1}`))
    // 真实 DOM 属性：jsdom 不计算布局，用 getter spy 提供 scrollHeight/clientHeight。
    const scrollHeightSpy = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get')
    const clientHeightSpy = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    try {
      scrollHeightSpy.mockReturnValue(600)
      clientHeightSpy.mockReturnValue(200)

      // 首次进入（无保存位置）：贴底。
      const first = render(
        <ThreadEventView events={events} onActivate={vi.fn()} onCloseDetail={vi.fn()} />,
      )
      expect(listbox().scrollTop).toBe(600)
      first.unmount()

      // 重新进入（保存位置 120）：恢复，而不是贴底。
      const second = render(
        <ThreadEventView
          events={events}
          initialScrollTop={120}
          onActivate={vi.fn()}
          onCloseDetail={vi.fn()}
        />,
      )
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
    const events = Array.from({ length: 20 }, (_, index) => eventItem(`e${index + 1}`))
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
          bodyRef={externalRef}
          initialScrollTop={77}
          onActivate={vi.fn()}
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
    renderView()
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
    const first = renderView({ detailOpen: true })
    await user.click(listbox())
    await user.keyboard('{Escape}')
    expect(first.onCloseDetail).toHaveBeenCalledTimes(1)
    first.unmount()

    const second = renderView({ detailOpen: false })
    await user.click(listbox())
    await user.keyboard('{Escape}')
    expect(second.onCloseDetail).not.toHaveBeenCalled()
  })

  it('shows an empty state when there are no events', () => {
    renderView({ events: [] })
    expect(screen.getByText('暂无事件')).toBeInTheDocument()
    expect(listbox()).toBeInTheDocument()
  })
})
