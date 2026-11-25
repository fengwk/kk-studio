import { useState } from 'react'
import { act, fireEvent, render, renderHook, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PaneLayout } from './PaneLayout'
import type { PanePickerActions } from './PanePicker'
import { initialPaneState, paneIds, splitPane, type PaneState } from './model'
import { usePaneState } from './usePaneState'

function setStageSize(width: number, height: number) {
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', { configurable: true, get: () => width })
  Object.defineProperty(HTMLElement.prototype, 'clientHeight', { configurable: true, get: () => height })
}

function Harness({
  initial,
  picker,
  onClosePane,
  iframeInSecond = false,
}: {
  initial?: PaneState<string>
  picker?: PanePickerActions
  onClosePane?: (paneId: string) => void
  iframeInSecond?: boolean
}) {
  const [state, setState] = useState<PaneState<string>>(() => initial ?? initialPaneState<string>())
  const [fullscreen, setFullscreen] = useState(false)
  const ids = paneIds(state.layout)
  return (
    <PaneLayout
      state={state}
      setState={setState}
      fullscreen={fullscreen}
      onFullscreenChange={setFullscreen}
      picker={picker}
      onClosePane={onClosePane}
      activeHeaderLeading={<span data-testid="active-leading">Lead</span>}
      describePane={(id, binding) => ({
        title: binding ?? `Pane-${ids.indexOf(id) + 1}`,
        actions: <button type="button">Act-{ids.indexOf(id) + 1}</button>,
        content: iframeInSecond && id === ids[1]
          ? <iframe title={`frame-${id}`} />
          : <div>Content-{binding ?? ids.indexOf(id) + 1}</div>,
      })}
    />
  )
}

describe('PaneLayout / PaneHeader / PanePicker / usePaneState', () => {
  afterEach(() => {
    setStageSize(1200, 800)
  })

  it('splits, swaps, resizes via keyboard/pointer/double-click, fullscreen + Escape, and closes panes', async () => {
    const user = userEvent.setup()
    setStageSize(1200, 800)
    render(<Harness />)

    // Single pane has split buttons and fullscreen, no close button
    expect(screen.queryByRole('button', { name: '关闭窗格' })).toBeNull()
    expect(screen.getByTestId('active-leading')).toBeInTheDocument()

    // Split right via button
    await user.click(screen.getByRole('button', { name: '向右拆分' }))
    expect(screen.getAllByRole('separator')).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: '关闭窗格' })).toHaveLength(2)

    // Keyboard shortcut 's' on header splits down; repeat / modifier ignored
    const activeHeader = document.querySelector<HTMLElement>('[data-pane-active] [data-pane-header]')!
    fireEvent.keyDown(activeHeader, { key: 's', ctrlKey: true })
    expect(screen.getAllByRole('separator')).toHaveLength(1)
    fireEvent.keyDown(activeHeader, { key: 's', repeat: true })
    expect(screen.getAllByRole('separator')).toHaveLength(1)
    fireEvent.keyDown(activeHeader, { key: 's' })
    expect(screen.getAllByRole('separator')).toHaveLength(2)

    // Keyboard resize on X and Y separators
    const separators = screen.getAllByRole('separator')
    const xSep = separators.find((el) => el.getAttribute('data-axis') === 'X')!
    const ySep = separators.find((el) => el.getAttribute('data-axis') === 'Y')!
    fireEvent.keyDown(xSep, { key: 'ArrowRight' })
    expect(Number(xSep.getAttribute('aria-valuenow'))).toBeGreaterThan(50)
    fireEvent.keyDown(xSep, { key: 'Home' })
    const minVal = Number(xSep.getAttribute('aria-valuemin'))
    expect(Number(xSep.getAttribute('aria-valuenow'))).toBe(minVal)
    fireEvent.keyDown(xSep, { key: 'End' })
    expect(Number(xSep.getAttribute('aria-valuenow'))).toBe(Number(xSep.getAttribute('aria-valuemax')))
    fireEvent.keyDown(xSep, { key: 'ArrowLeft', shiftKey: true })
    fireEvent.doubleClick(xSep)
    expect(Number(xSep.getAttribute('aria-valuenow'))).toBe(50)

    fireEvent.keyDown(ySep, { key: 'ArrowDown' })
    fireEvent.keyDown(ySep, { key: 'ArrowUp' })

    // Pointer drag resize on separator
    xSep.setPointerCapture = vi.fn()
    xSep.hasPointerCapture = vi.fn(() => true)
    fireEvent.pointerDown(xSep, { button: 0, isPrimary: true, clientX: 600, clientY: 100, pointerId: 1 })
    fireEvent.pointerMove(xSep, { clientX: 660, clientY: 100, pointerId: 1 })
    expect(Number(xSep.getAttribute('aria-valuenow'))).toBeGreaterThan(50)
    fireEvent.lostPointerCapture(xSep, { pointerId: 1 })

    // Drag and drop swap between pane A and pane C
    const currentHeaders = document.querySelectorAll<HTMLElement>('[data-pane-header]')
    const grips = document.querySelectorAll<HTMLElement>('[data-pane-grip]')
    expect(grips).toHaveLength(3)
    const dataTransfer = {
      data: {} as Record<string, string>,
      types: ['application/x-kkstudio-pane'],
      effectAllowed: '',
      dropEffect: '',
      setData(k: string, v: string) { this.data[k] = v },
      getData(k: string) { return this.data[k] ?? '' },
    }
    fireEvent.dragStart(grips[0]!, { dataTransfer })
    fireEvent.dragOver(currentHeaders[2]!, { dataTransfer })
    fireEvent.drop(currentHeaders[2]!, { dataTransfer })

    // Focus another pane content and verify fullscreen + Escape exits fullscreen
    fireEvent.pointerDown(screen.getByText('Content-1'))
    const fsButtons = screen.getAllByRole('button', { name: '全屏' })
    await user.click(fsButtons[0]!)
    expect(screen.getAllByRole('button', { name: '退出全屏' }).length).toBeGreaterThan(0)
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('button', { name: '退出全屏' })).toBeNull()

    // Close one pane
    const closeBtns = screen.getAllByRole('button', { name: '关闭窗格' })
    await user.click(closeBtns[0]!)
    expect(screen.getAllByRole('separator')).toHaveLength(1)
  })

  it('calls custom onClosePane, activates iframe pane on window blur, and blocks split at 8 panes', async () => {
    const user = userEvent.setup()
    setStageSize(4000, 3000)
    let state = initialPaneState<string>()
    for (let i = 1; i < 8; i++) {
      state = splitPane(state, state.focusedPaneId, i % 2 === 1 ? 'X' : 'Y')
    }
    const onClosePane = vi.fn()
    render(<Harness initial={state} onClosePane={onClosePane} iframeInSecond />)

    // All 8 panes present; split buttons are aria-disabled with pane.limit title
    const splitRightBtns = screen.getAllByRole('button', { name: '向右拆分' })
    expect(splitRightBtns[0]).toHaveAttribute('aria-disabled', 'true')
    expect(splitRightBtns[0]).toHaveAttribute('title', '最多 8 个窗格')
    await user.click(splitRightBtns[0]!)

    // Custom onClosePane invoked
    await user.click(screen.getAllByRole('button', { name: '关闭窗格' })[0]!)
    expect(onClosePane).toHaveBeenCalledTimes(1)

    // Clicking/blurring into iframe activates its pane
    const iframe = document.querySelector('iframe')!
    Object.defineProperty(document, 'activeElement', { configurable: true, get: () => iframe })
    fireEvent.blur(window)
    const iframePane = iframe.closest('[data-pane-id]')!
    expect(iframePane).toHaveAttribute('data-pane-active', 'true')
    // restore activeElement getter
    delete (document as { activeElement?: unknown }).activeElement
  })

  it('supports narrow mode switch and split menus, outside dismiss, and PanePicker interactions', async () => {
    const user = userEvent.setup()
    setStageSize(500, 600)
    let state = initialPaneState<string>()
    const firstId = state.focusedPaneId
    state.bindings[firstId] = 'First'
    state = splitPane(state, firstId, 'X')
    const secondId = state.focusedPaneId
    state.bindings[secondId] = 'Second'

    const onSelect = vi.fn()
    const onCancel = vi.fn()
    const { rerender } = render(
      <Harness initial={state} picker={{ onSelect, onCancel }} />,
    )

    // PanePicker is shown as modal dialog in overview mode
    const pickerDialog = screen.getByRole('dialog', { name: '打开到窗格' })
    expect(pickerDialog).toHaveAttribute('data-overview', 'true')

    // Key 'a' selects first pane; Enter selects focused pane
    fireEvent.keyDown(pickerDialog, { key: 'a' })
    expect(onSelect).toHaveBeenCalledWith(firstId)
    fireEvent.keyDown(pickerDialog, { key: 'Enter' })
    expect(onSelect).toHaveBeenCalledWith(secondId)
    await user.click(within(pickerDialog).getByRole('button', { name: /A：First/ }))
    expect(onSelect).toHaveBeenCalledWith(firstId)

    // Clicking backdrop or cancel event calls onCancel
    fireEvent.click(pickerDialog)
    fireEvent(pickerDialog, new Event('cancel', { cancelable: true }))
    expect(onCancel).toHaveBeenCalledTimes(2)

    // Rerender without picker so narrow header menus work
    rerender(<Harness initial={state} />)
    const switchBtn = screen.getByRole('button', { name: /切换窗格：B Second/ })
    await user.click(switchBtn)
    const switchMenu = screen.getByRole('menu')
    expect(within(switchMenu).getByRole('menuitem', { name: 'A：First' })).toBeInTheDocument()
    await user.click(within(switchMenu).getByRole('menuitem', { name: 'A：First' }))
    expect(screen.getByRole('button', { name: /切换窗格：A First/ })).toBeInTheDocument()

    // Open split menu in narrow mode and dismiss on outside pointerdown
    const splitMenuBtn = screen.getByRole('button', { name: '拆分窗格' })
    await user.click(splitMenuBtn)
    expect(screen.getByRole('menu')).toBeInTheDocument()
    fireEvent.pointerDown(document.body)
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('usePaneState persists and resets across userId/spaceId changes and tolerates storage errors', () => {
    const isText = (v: unknown): v is string => typeof v === 'string'
    const { result, rerender } = renderHook(
      ({ userId, spaceId }) => usePaneState<string>(userId, spaceId, isText),
      { initialProps: { userId: 'u1', spaceId: 's1' } },
    )
    const firstId = result.current[0].focusedPaneId
    act(() => {
      result.current[1]((prev) => ({ ...prev, bindings: { [firstId]: 'hello' } }))
    })
    expect(localStorage.getItem('workspace-panes:["u1","s1"]')).toContain('hello')

    // Switching space resets state to the new key
    rerender({ userId: 'u1', spaceId: 's2' })
    expect(result.current[0].bindings).toEqual({})

    // Storage throwing does not break hook
    const getSpy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    const setSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    rerender({ userId: 'u2', spaceId: 's3' })
    expect(result.current[0].bindings).toEqual({})
    getSpy.mockRestore()
    setSpy.mockRestore()
  })
})
