import { useEffect, useLayoutEffect, useRef, useState, type Dispatch, type ReactNode, type SetStateAction } from 'react'
import { useI18n } from '@/shared/i18n'
import { PaneHeader } from './PaneHeader'
import { PanePicker, type PanePickerActions } from './PanePicker'
import { canSplit, closePane, MAX_PANES, needsSinglePane, paneIds, paneLetter, projectLayout, resizeSplit, splitPane, swapPanes,
  type Axis, type PaneState, type Rect, type SplitView } from './model'
import './panes.css'

export type PaneLayoutProps<T> = {
  state: PaneState<T>
  setState: Dispatch<SetStateAction<PaneState<T>>>
  fullscreen: boolean
  onFullscreenChange: (fullscreen: boolean) => void
  describePane: (paneId: string, binding: T | undefined) => { title: string; content: ReactNode; actions?: ReactNode }
  picker?: PanePickerActions
  onClosePane?: (paneId: string) => void
  onSizeChange?: (bounds: Rect) => void
}

function position(rect?: Rect) {
  return rect && { left: rect.x, top: rect.y, width: rect.width, height: rect.height }
}

function Divider({ view, onResize }: { view: SplitView; onResize: (ratio: number) => void }) {
  const { t } = useI18n()
  const drag = useRef<{ coordinate: number; ratio: number }>(undefined)
  const horizontal = view.axis === 'X'
  const pct = (value: number) => Math.round(value * 100)
  return <div className="ws-pane-divider" data-axis={view.axis} style={position(view.line)}
    role="separator" tabIndex={0} data-ui-focus="row" aria-label={t('pane.resize')}
    aria-orientation={horizontal ? 'vertical' : 'horizontal'} aria-valuemin={pct(view.min)} aria-valuemax={pct(view.max)} aria-valuenow={pct(view.ratio)}
    onPointerDown={event => {
      if (event.button !== 0 || !event.isPrimary) return
      drag.current = { coordinate: horizontal ? event.clientX : event.clientY, ratio: view.ratio }
      event.currentTarget.setPointerCapture(event.pointerId)
    }}
    onPointerMove={event => {
      if (!drag.current || !event.currentTarget.hasPointerCapture(event.pointerId)) return
      const delta = (horizontal ? event.clientX : event.clientY) - drag.current.coordinate
      const extent = horizontal ? view.rect.width - view.line.width : view.rect.height - view.line.height
      onResize(drag.current.ratio + delta / extent)
    }}
    onLostPointerCapture={() => { drag.current = undefined }}
    onDoubleClick={() => onResize(0.5)}
    onKeyDown={event => {
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return
      const ratio = event.key === 'Home' ? view.min : event.key === 'End' ? view.max
        : event.key === (horizontal ? 'ArrowLeft' : 'ArrowUp') ? view.ratio - 0.05
          : event.key === (horizontal ? 'ArrowRight' : 'ArrowDown') ? view.ratio + 0.05 : undefined
      if (ratio !== undefined) { event.preventDefault(); onResize(ratio) }
    }} />
}

export function PaneLayout<T>({ state, setState, fullscreen, onFullscreenChange, describePane, picker, onClosePane, onSizeChange }: PaneLayoutProps<T>) {
  const { t } = useI18n()
  const stage = useRef<HTMLDivElement>(null)
  const pendingFocus = useRef(false)
  const [bounds, setBounds] = useState<Rect>({ x: 0, y: 0, width: 0, height: 0 })
  useLayoutEffect(() => {
    const element = stage.current!
    const measure = () => {
      const next = { x: 0, y: 0, width: element.clientWidth, height: element.clientHeight }
      setBounds(next)
      onSizeChange?.(next)
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [onSizeChange])
  const { focusedPaneId, layout, bindings } = state
  const narrow = needsSinglePane(layout, bounds)
  const singleView = narrow || fullscreen
  const projection = singleView ? undefined : projectLayout(layout, bounds)
  const descriptions = paneIds(layout).map((id, index) => ({ id, letter: paneLetter(index), ...describePane(id, bindings[id]) }))
  const byId = new Map(descriptions.map(pane => [pane.id, pane]))
  const focus = (id: string) => setState(previous => previous.focusedPaneId === id ? previous : { ...previous, focusedPaneId: id })
  const focusHeader = () => {
    stage.current?.querySelector<HTMLElement>('[data-pane-active] [data-pane-header]')?.focus({ preventScroll: true })
  }
  useLayoutEffect(() => {
    if (!pendingFocus.current) return
    pendingFocus.current = false
    focusHeader()
  }, [layout, focusedPaneId])
  useEffect(() => {
    if (!fullscreen || picker) return
    const exit = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !event.defaultPrevented && !event.isComposing) {
        event.preventDefault()
        onFullscreenChange(false)
      }
    }
    window.addEventListener('keydown', exit)
    return () => window.removeEventListener('keydown', exit)
  }, [fullscreen, picker, onFullscreenChange])
  useEffect(() => {
    // An iframe receives pointer/focus events in its own document.
    const activateFrame = () => {
      const frame = document.activeElement
      if (!(frame instanceof HTMLIFrameElement) || !stage.current?.contains(frame)) return
      const id = frame.closest<HTMLElement>('[data-pane-id]')?.dataset.paneId
      if (id) setState(previous => previous.focusedPaneId === id ? previous : { ...previous, focusedPaneId: id })
    }
    window.addEventListener('blur', activateFrame)
    return () => window.removeEventListener('blur', activateFrame)
  }, [setState])

  return <div className="ws-pane-layout" ref={stage}>
    {/* Stable DOM order is independent of tree order. Moving an iframe even within its parent reloads it. */}
    {[...byId.keys()].sort().map(id => {
      const pane = byId.get(id)!
      const active = id === focusedPaneId
      const rect = singleView ? (active ? bounds : undefined) : projection?.panes.get(id)
      const splitBlocked: Partial<Record<Axis, string>> = {}
      for (const axis of ['X', 'Y'] as const) {
        if (!canSplit(layout, axis, narrow ? undefined : rect)) {
          splitBlocked[axis] = descriptions.length >= MAX_PANES ? t('pane.limit', { count: MAX_PANES }) : t('pane.noRoom')
        }
      }
      return <section key={id} className="ws-pane-host" data-pane-id={id} data-pane-active={active || undefined} hidden={!rect} style={position(rect)}>
        <div onFocus={event => { if ((event.target as HTMLElement).hasAttribute('data-pane-header')) focus(id) }}>
          <PaneHeader pane={pane} choices={descriptions} active={active} narrow={narrow} fullscreen={fullscreen}
            splitBlocked={splitBlocked} actions={pane.actions}
            onSplit={axis => { pendingFocus.current = true; setState(previous => splitPane(previous, id, axis)) }}
            onSwitch={next => { if (next !== id) { pendingFocus.current = true; focus(next) } }}
            onFullscreenChange={next => { if (next) focus(id); onFullscreenChange(next) }}
            onClose={() => {
              pendingFocus.current = true
              if (onClosePane) onClosePane(id)
              else setState(previous => closePane(previous, id))
            }}
            onSwap={(from, to) => setState(previous => swapPanes(previous, from, to))} />
        </div>
        <div className="ws-pane-content" data-pane-content onFocus={() => focus(id)} onPointerDown={() => focus(id)}>{pane.content}</div>
      </section>
    })}
    {projection?.splits.map(view => <Divider key={view.splitId} view={view}
      onResize={ratio => setState(previous => resizeSplit(previous, view, ratio))} />)}
    {picker && <PanePicker layout={layout} choices={descriptions} focusedPaneId={focusedPaneId} overview={singleView}
      bounds={bounds} anchor={stage} {...picker} />}
  </div>
}
