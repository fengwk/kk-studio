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

  it('opens the detail on click without changing the active option', async () => {
    const { onActivate } = renderView()
    // fireEvent.click 只派发 click（不模拟指针移动）：点击不改变 active。
    fireEvent.click(screen.getByRole('option', { name: /Event e1/ }))
    expect(onActivate).toHaveBeenCalledWith(expect.objectContaining({ id: 'e1' }))
    // 鼠标 click 不改变 active：active 仍是最新事件。
    expect(selectedId()).toBe('thread-event-e3')
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
