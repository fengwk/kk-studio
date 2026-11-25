import { useLayoutEffect, useRef, type CSSProperties, type RefObject } from 'react'
import { useI18n } from '@/shared/i18n'
import { minimumSize, projectLayout, type Layout, type Rect } from './model'
import type { PaneLabel } from './PaneHeader'
import './panes.css'

export type PanePickerActions = { onSelect: (paneId: string) => void; onCancel: () => void; returnFocusTo?: HTMLElement | null }
export type PanePickerProps = PanePickerActions & {
  choices: readonly PaneLabel[]
  layout: Layout
  focusedPaneId: string
  bounds: Rect
  anchor?: RefObject<HTMLElement | null>
  overview: boolean
}

export function PanePicker({ choices, layout, focusedPaneId, bounds, anchor, overview, onSelect, onCancel, returnFocusTo }: PanePickerProps) {
  const { t } = useI18n()
  const dialog = useRef<HTMLDialogElement>(null)
  const current = useRef<HTMLButtonElement>(null)
  const min = minimumSize(layout)
  const width = Math.max(min.width, bounds.width), height = Math.max(min.height, bounds.height)
  const { panes } = projectLayout(layout, { x: 0, y: 0, width, height })
  useLayoutEffect(() => {
    const element = dialog.current!
    const source = returnFocusTo ?? document.activeElement
    element.showModal()
    current.current?.focus({ preventScroll: true })
    return () => {
      element.close()
      if (source instanceof HTMLElement && source.isConnected) source.focus({ preventScroll: true })
    }
  }, [returnFocusTo])

  useLayoutEffect(() => {
    const box = anchor?.current?.getBoundingClientRect()
    const element = dialog.current!
    element.style.left = `${box?.left ?? bounds.x}px`
    element.style.top = `${box?.top ?? bounds.y}px`
  })

  return <dialog ref={dialog} className="ws-pane-picker" data-overview={overview || undefined} aria-label={t('pane.pick')}
    style={{ width: bounds.width, height: bounds.height }}
    onCancel={event => { event.preventDefault(); onCancel() }}
    onClick={event => { if (event.target === event.currentTarget) onCancel() }}
    onKeyDown={event => {
      if (event.nativeEvent.isComposing || event.altKey || event.ctrlKey || event.metaKey) return
      const match = event.key === 'Enter' ? focusedPaneId : choices.find(choice => choice.letter.toLowerCase() === event.key.toLowerCase())?.id
      if (!match) return
      event.preventDefault()
      event.stopPropagation()
      if (!event.repeat) onSelect(match)
    }}>
    {choices.map(choice => {
      const rect = panes.get(choice.id)!
      const style: CSSProperties = overview ? {
        left: `${rect.x / width * 100}%`, top: `${rect.y / height * 100}%`,
        width: `${rect.width / width * 100}%`, height: `${rect.height / height * 100}%`,
      } : { left: rect.x, top: rect.y, width: rect.width, height: rect.height }
      const active = choice.id === focusedPaneId
      return <div key={choice.id} className="ws-pane-target" style={style}>
        {overview && <span className="ws-pane-target-title" title={choice.title}>{choice.title}</span>}
        <button type="button" ref={active ? current : undefined} data-ui-focus="row" aria-current={active || undefined}
          aria-label={t('pane.choice', choice)} aria-keyshortcuts={active ? `Enter ${choice.letter}` : choice.letter}
          title={choice.title} onClick={() => onSelect(choice.id)}>{choice.letter}</button>
      </div>
    })}
  </dialog>
}
