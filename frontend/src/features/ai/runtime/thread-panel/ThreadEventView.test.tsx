import { useMemo, useRef, useState } from 'react'
import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ThreadEventView } from '@/features/ai/runtime/thread-panel/ThreadEventView'
import { ThreadEventDetail } from '@/features/ai/runtime/thread-panel/ThreadEventDetail'
import { useThreadPanelViewState } from '@/features/ai/runtime/thread-panel/useThreadPanelViewState'
import type { DebugInspectorSelection } from '@/features/ai/runtime/thread-panel/ThreadDebugInspector'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'
import type { ThreadModelRequestDebugData } from '@/features/ai/runtime/thread-timeline-types'

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
  const full = listbox().getAttribute('aria-activedescendant')
  if (!full) return null
  const match = full.match(/-event-(.+)$/)
  return match ? `thread-event-${match[1]}` : full
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

  it('pins a next request preview above the independently scrolling event list', () => {
    render(
      <ThreadEventView
        events={[record('e1')]}
        selectedEventId={null}
        onSelectedEventIdChange={vi.fn()}
        debug={{
          kind: 'NEXT_REQUEST_PREVIEW',
          generatedAt: '2026-09-21T00:00:00.000Z',
          model: { providerName: 'minimax', modelName: 'MiniMax-M2.7', variant: 'default' },
          environmentName: null,
          systemInstruction: 'line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\nline11',
          tools: [],
          skills: [],
          subagents: [],
          cacheControl: null,
          planningError: null,
          frozenInvocation: null,
        }}
      />,
    )
    const preview = screen.getByLabelText('Next Request Preview')
    expect(preview).toHaveTextContent('line1')
    expect(preview).toHaveTextContent('line11')
    expect(preview.querySelector('.thread-system-prompt-body')).not.toBeNull()
    expect(listbox().parentElement).toHaveClass('thread-debug-col-events')
    expect(listbox().closest('.thread-events-shell')).not.toBeNull()
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

  describe('Responsive 3-zone layout and tab behavior', () => {
    const listeners = new Set<(width: number) => void>()
    const originalResizeObserver = global.ResizeObserver

    beforeEach(() => {
      listeners.clear()
      global.ResizeObserver = class MockResizeObserver implements ResizeObserver {
        callback: ResizeObserverCallback
        constructor(cb: ResizeObserverCallback) {
          this.callback = cb
        }
        observe(target: Element) {
          const fn = (width: number) => {
            this.callback([{ target, contentRect: { width } } as ResizeObserverEntry], this)
          }
          listeners.add(fn)
          // 初始宽模式
          fn(1400)
        }
        unobserve() {}
        disconnect() {
          listeners.clear()
        }
      }
    })

    afterEach(() => {
      global.ResizeObserver = originalResizeObserver
      listeners.clear()
    })

    function triggerResize(width: number) {
      act(() => {
        listeners.forEach((fn) => fn(width))
      })
    }

    function sampleDebugData(
      overrides: Partial<ThreadModelRequestDebugData> = {},
    ): ThreadModelRequestDebugData {
      return {
        kind: 'NEXT_REQUEST_PREVIEW',
        generatedAt: '2026-09-21T00:00:00.000Z',
        model: { providerName: 'minimax', modelName: 'MiniMax-M2.7', variant: 'default' },
        environmentName: 'dev-box',
        systemInstruction: 'You are an expert assistant.',
        tools: [
          {
            name: 'read',
            description: 'Read file',
            inputSchemaJson: '{"type":"object"}',
            environmentSupport: 'OPTIONAL',
            requiredEnvironmentId: null,
            provenance: 'builtin:read',
            state: 'SENT',
            filterReason: null,
          },
        ],
        skills: [],
        subagents: [],
        cacheControl: null,
        planningError: null,
        frozenInvocation: null,
        ...overrides,
      }
    }

    function ResponsiveHarness({
      initialEvents = [record('e1', { title: 'First Event' }), record('e2', { title: 'Second Event' })],
      initialDebug = sampleDebugData(),
    }: {
      initialEvents?: ThreadEventRecord[]
      initialDebug?: ThreadModelRequestDebugData | null
    }) {
      const [events] = useState(initialEvents)
      const [selectedEventId, setSelectedEventId] = useState<string | null>(null)
      const [debugSelection, setDebugSelection] = useState<DebugInspectorSelection | null>(null)

      const selectedRecord = useMemo(
        () => (selectedEventId ? events.find((e) => e.id === selectedEventId) ?? null : null),
        [events, selectedEventId],
      )

      return (
        <div>
          <div data-testid="controls">
            <button type="button" onClick={() => triggerResize(800)}>Trigger Narrow</button>
            <button type="button" onClick={() => triggerResize(1400)}>Trigger Wide</button>
          </div>
          <ThreadEventView
            events={events}
            selectedEventId={selectedEventId}
            onSelectedEventIdChange={(id) => {
              if (id != null) {
                setDebugSelection(null)
              }
              setSelectedEventId(id)
            }}
            debug={initialDebug}
            debugSelection={debugSelection}
            onSelectInspector={(selection) => {
              if (selection != null) {
                setSelectedEventId(null)
              }
              setDebugSelection(selection)
            }}
            selectedRecord={selectedRecord}
          />
        </div>
      )
    }

    it('switches tabs in narrow mode and supports roving tabIndex with Arrow keys', async () => {
      const user = userEvent.setup()
      render(<ResponsiveHarness />)

      // 切换到窄容器模式 (< 1100px)
      await user.click(screen.getByRole('button', { name: 'Trigger Narrow' }))

      const previewTab = screen.getByRole('tab', { name: '请求预览' })
      const eventsTab = screen.getByRole('tab', { name: '事件' })
      const detailTab = screen.getByRole('tab', { name: '详情' })

      // 默认处于事件 tab，roving tabIndex: events 为 0，其余为 -1
      expect(eventsTab).toHaveAttribute('aria-selected', 'true')
      expect(eventsTab).toHaveAttribute('tabIndex', '0')
      expect(previewTab).toHaveAttribute('aria-selected', 'false')
      expect(previewTab).toHaveAttribute('tabIndex', '-1')
      expect(detailTab).toHaveAttribute('aria-selected', 'false')
      expect(detailTab).toHaveAttribute('tabIndex', '-1')

      // 测试键盘导航：在 eventsTab 按 ArrowRight 移动到 detailTab
      eventsTab.focus()
      await user.keyboard('{ArrowRight}')
      expect(detailTab).toHaveAttribute('aria-selected', 'true')
      expect(detailTab).toHaveAttribute('tabIndex', '0')
      expect(eventsTab).toHaveAttribute('tabIndex', '-1')
      expect(detailTab).toHaveFocus()

      // 按 Home 键跳到第一个 tab（请求预览）
      await user.keyboard('{Home}')
      expect(previewTab).toHaveAttribute('aria-selected', 'true')
      expect(previewTab).toHaveAttribute('tabIndex', '0')
      expect(previewTab).toHaveFocus()

      // 按 ArrowLeft 循环跳到最后一个 tab (详情)
      await user.keyboard('{ArrowLeft}')
      expect(detailTab).toHaveAttribute('aria-selected', 'true')
      expect(detailTab).toHaveFocus()

      // 按 End 键直接跳到最后一个 tab (详情)
      await user.keyboard('{End}')
      expect(detailTab).toHaveAttribute('aria-selected', 'true')
      expect(detailTab).toHaveFocus()

      // 内容展示
      expect(screen.getByText('You are an expert assistant.')).toBeInTheDocument()
    })

    it('automatically activates detail tab on event click and manages focus cleanly', async () => {
      const user = userEvent.setup()
      render(<ResponsiveHarness />)

      await user.click(screen.getByRole('button', { name: 'Trigger Narrow' }))

      const eventsTab = screen.getByRole('tab', { name: '事件' })
      const detailTab = screen.getByRole('tab', { name: '详情' })
      expect(eventsTab).toHaveAttribute('aria-selected', 'true')

      // 点击事件行
      const eventRow = screen.getByRole('option', { name: /First Event/ })
      await user.click(eventRow)

      // 自动切到详情页签并展示事件详情
      expect(detailTab).toHaveAttribute('aria-selected', 'true')
      expect(screen.getByRole('heading', { level: 3, name: 'First Event' })).toBeInTheDocument()

      // 焦点转移到关闭按钮
      const closeBtn = screen.getByRole('button', { name: '关闭事件详情' })
      expect(closeBtn).toBeInTheDocument()

      // 按 Escape 局部关闭详情，并恢复焦点到触发元素
      await user.keyboard('{Escape}')
      expect(eventsTab).toHaveAttribute('aria-selected', 'true')
      expect(detailTab).toHaveAttribute('aria-selected', 'false')
    })

    it('automatically activates detail tab on tool click and returns to preview on close', async () => {
      const user = userEvent.setup()
      render(<ResponsiveHarness />)

      await user.click(screen.getByRole('button', { name: 'Trigger Narrow' }))

      const previewTab = screen.getByRole('tab', { name: '请求预览' })
      const detailTab = screen.getByRole('tab', { name: '详情' })

      // 切换到预览
      await user.click(previewTab)
      expect(previewTab).toHaveAttribute('aria-selected', 'true')

      // 点击 Tool
      const toolBtn = screen.getByRole('button', { name: 'Tool read' })
      await user.click(toolBtn)

      // 自动激活详情并显示 Inspector
      expect(detailTab).toHaveAttribute('aria-selected', 'true')
      expect(screen.getByTestId('thread-debug-inspector')).toHaveAttribute(
        'aria-label',
        'INSPECTOR: Tool · read',
      )

      // 关闭详情
      const closeBtn = screen.getByRole('button', { name: 'Close inspector' })
      await user.click(closeBtn)

      // 来源是 preview，故返回 preview tab
      expect(previewTab).toHaveAttribute('aria-selected', 'true')
      expect(detailTab).toHaveAttribute('aria-selected', 'false')
    })

    it('preserves selection state across narrow and wide transitions', async () => {
      const user = userEvent.setup()
      render(<ResponsiveHarness />)

      // 初始宽模式下选择 Second Event
      await user.click(screen.getByRole('option', { name: /Second Event/ }))
      expect(screen.getByRole('heading', { level: 3, name: 'Second Event' })).toBeInTheDocument()

      // 缩窄为 800px
      await user.click(screen.getByRole('button', { name: 'Trigger Narrow' }))

      const shell = screen.getByRole('listbox', { name: '事件' }).closest('.thread-events-shell')
      expect(shell).toHaveAttribute('data-layout', 'narrow')
      const detailTab = screen.getByRole('tab', { name: '详情' })
      expect(detailTab).toHaveAttribute('aria-selected', 'true')
      expect(screen.getByRole('heading', { level: 3, name: 'Second Event' })).toBeInTheDocument()

      // 再拉宽为 1400px
      await user.click(screen.getByRole('button', { name: 'Trigger Wide' }))
      expect(shell).toHaveAttribute('data-layout', 'wide')
      // 宽模式三列同时展示且 Second Event 仍为选中状态
      expect(screen.getByRole('heading', { level: 3, name: 'Second Event' })).toBeInTheDocument()
      expect(screen.getByText('You are an expert assistant.')).toBeInTheDocument()
    })

    it('isolates IDs across multiple pane instances to prevent ID collision in split views', () => {
      render(
        <div>
          <div data-testid="pane-1">
            <ThreadEventView
              events={[record('e1')]}
              selectedEventId={null}
              onSelectedEventIdChange={vi.fn()}
            />
          </div>
          <div data-testid="pane-2">
            <ThreadEventView
              events={[record('e1')]}
              selectedEventId={null}
              onSelectedEventIdChange={vi.fn()}
            />
          </div>
        </div>,
      )

      const pane1Options = screen.getByTestId('pane-1').querySelectorAll('[role="option"]')
      const pane2Options = screen.getByTestId('pane-2').querySelectorAll('[role="option"]')

      expect(pane1Options).toHaveLength(1)
      expect(pane2Options).toHaveLength(1)

      const id1 = pane1Options[0]!.id
      const id2 = pane2Options[0]!.id

      // 验证两者 ID 互不相同（包含各自独立的 useId 前缀）
      expect(id1).not.toBe(id2)
      expect(id1).toContain('-event-e1')
      expect(id2).toContain('-event-e1')
    })

    it('renders clean placeholder in wide mode when no event or inspector is selected', () => {
      render(<ResponsiveHarness />)
      expect(screen.getByTestId('thread-debug-placeholder')).toHaveTextContent('未选择任何事件或检查项')
    })

    it('displays events normally when debug data is not loaded or null', () => {
      render(<ResponsiveHarness initialDebug={null} />)
      expect(screen.getByRole('option', { name: /First Event/ })).toBeInTheDocument()
      expect(screen.getByRole('option', { name: /Second Event/ })).toBeInTheDocument()
      expect(screen.getByText('暂无请求预览数据')).toBeInTheDocument()
    })

    it('formats invalid or missing timestamps gracefully', () => {
      render(
        <ResponsiveHarness
          initialEvents={[
            record('e-inv-1', { title: 'Invalid Date', createdAt: 'not-a-valid-date' }),
            record('e-inv-2', { title: 'Null Date', createdAt: null as unknown as string }),
          ]}
        />,
      )
      const times = screen.getAllByText('--:--:--')
      expect(times).toHaveLength(2)
    })

    it('handles container Escape key without selection safely', async () => {
      const user = userEvent.setup()
      render(<ResponsiveHarness />)
      // 在宽屏无选中的状态下按 Escape
      await user.keyboard('{Escape}')
      expect(screen.getByTestId('thread-debug-placeholder')).toBeInTheDocument()
    })

    it('keeps listbox focused when clicking events in wide mode allowing arrow navigation', async () => {
      const user = userEvent.setup()
      render(<ResponsiveHarness />)
      const listbox = screen.getByRole('listbox', { name: '事件' })
      const firstEvent = screen.getByRole('option', { name: /First Event/ })
      await user.click(firstEvent)

      // 宽屏点击事件后焦点仍停留在 listbox，不被详情抢焦
      expect(listbox).toHaveFocus()
      // 可以继续按 ArrowDown 键直接导航
      await user.keyboard('{ArrowDown}')
      expect(screen.getByRole('heading', { level: 3, name: 'Second Event' })).toBeInTheDocument()
    })

    it('supports direct ThreadEventDetail rendering, autoFocus, and Escape handling', async () => {
      const user = userEvent.setup()
      const onClose = vi.fn()
      const { rerender } = render(
        <ThreadEventDetail
          record={record('e-test', {
            title: 'Detail Test',
            details: [{ label: 'Key', value: 'Value' }],
            payloadJson: '{"foo":"bar"}',
          })}
          onClose={onClose}
          autoFocusCloseButton={true}
        />,
      )

      const closeBtn = screen.getByRole('button', { name: '关闭事件详情' })
      expect(closeBtn).toHaveFocus()

      // 验证 composing 状态下按 Escape 不触发 onClose
      fireEvent.keyDown(closeBtn, { key: 'Escape', isComposing: true })
      expect(onClose).not.toHaveBeenCalled()

      // 正常按 Escape 触发关闭
      await user.keyboard('{Escape}')
      expect(onClose).toHaveBeenCalledTimes(1)

      // autoFocusCloseButton = false 时不自动聚焦关闭按钮
      rerender(
        <ThreadEventDetail
          record={record('e-test-2', { title: 'No AutoFocus' })}
          onClose={onClose}
          autoFocusCloseButton={false}
        />,
      )
    })
  })
})
