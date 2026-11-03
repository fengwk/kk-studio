import { useEffect, useRef, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react'
import { useI18n } from '@/shared/i18n'

const MIN_IDE_WIDTH = 360
const MIN_CONTENT_WIDTH = 320
const DIVIDER_WIDTH = 8
const DEFAULT_IDE_WIDTH = 720
const WIDTH_KEY = 'kk-studio.ide-panel-width'

function savedWidth(): number {
  try {
    const value = Number(window.localStorage.getItem(WIDTH_KEY))
    return Number.isFinite(value) && value >= MIN_IDE_WIDTH ? value : DEFAULT_IDE_WIDTH
  } catch {
    return DEFAULT_IDE_WIDTH
  }
}

/** 分栏只修改自身 DOM 的宽度，拖动不更新 React 状态或重渲染页面。 */
export function IdeSplitShell({ children, panel, isOpen }: {
  children: ReactNode
  panel: ReactNode
  isOpen: boolean
}) {
  const { t } = useI18n()
  const shellRef = useRef<HTMLDivElement>(null)
  const dividerRef = useRef<HTMLDivElement>(null)
  const widthRef = useRef<number | null>(null)
  const dragRef = useRef<{ pointerId: number; startX: number; startWidth: number } | null>(null)

  useEffect(() => {
    const shell = shellRef.current
    const divider = dividerRef.current
    if (!shell || !divider) return
    if (widthRef.current == null) widthRef.current = savedWidth()
    shell.style.setProperty('--ide-panel-width', `${widthRef.current}px`)
    const updateAria = () => {
      const max = Math.max(MIN_IDE_WIDTH, shell.clientWidth - MIN_CONTENT_WIDTH - DIVIDER_WIDTH)
      const panelWidth = shell.querySelector<HTMLElement>('.ide-overlay-panel')?.getBoundingClientRect().width ?? 0
      divider.setAttribute('aria-valuemax', String(Math.round(max)))
      divider.setAttribute('aria-valuenow', String(Math.round(panelWidth)))
    }
    updateAria()
    window.addEventListener('resize', updateAria)
    return () => window.removeEventListener('resize', updateAria)
  }, [isOpen])

  function setWidth(requested: number) {
    const shell = shellRef.current
    if (!shell) return
    const max = Math.max(MIN_IDE_WIDTH, shell.clientWidth - MIN_CONTENT_WIDTH - DIVIDER_WIDTH)
    const width = Math.round(Math.min(max, Math.max(MIN_IDE_WIDTH, requested)))
    widthRef.current = width
    shell.style.setProperty('--ide-panel-width', `${width}px`)
    dividerRef.current?.setAttribute('aria-valuenow', String(width))
  }

  function persistWidth() {
    try {
      if (widthRef.current != null) window.localStorage.setItem(WIDTH_KEY, String(widthRef.current))
    } catch {
      // 布局仍可用于当前页面，即使浏览器禁用了存储。
    }
  }

  function onPointerDown(event: PointerEvent<HTMLDivElement>) {
    if (event.button !== 0) return
    const panelElement = shellRef.current?.querySelector<HTMLElement>('.ide-overlay-panel')
    if (!panelElement) return
    event.preventDefault()
    dragRef.current = { pointerId: event.pointerId, startX: event.clientX, startWidth: panelElement.getBoundingClientRect().width }
    event.currentTarget.setPointerCapture(event.pointerId)
    shellRef.current?.classList.add('is-resizing')
  }

  function onPointerMove(event: PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current
    if (drag?.pointerId === event.pointerId) setWidth(drag.startWidth + drag.startX - event.clientX)
  }

  function finishPointer(event: PointerEvent<HTMLDivElement>) {
    if (dragRef.current?.pointerId !== event.pointerId) return
    dragRef.current = null
    shellRef.current?.classList.remove('is-resizing')
    persistWidth()
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const panelWidth = shellRef.current?.querySelector<HTMLElement>('.ide-overlay-panel')?.getBoundingClientRect().width
    if (panelWidth == null) return
    const step = event.shiftKey ? 50 : 10
    if (event.key === 'ArrowLeft') setWidth(panelWidth + step)
    else if (event.key === 'ArrowRight') setWidth(panelWidth - step)
    else if (event.key === 'Home') setWidth(MIN_IDE_WIDTH)
    else if (event.key === 'End') setWidth(Number.MAX_SAFE_INTEGER)
    else return
    event.preventDefault()
    persistWidth()
  }

  return (
    <div ref={shellRef} className="ide-split-shell" data-ide-open={isOpen}>
      <div className="ide-split-content">{children}</div>
      <div
        ref={dividerRef}
        className="ide-split-divider"
        role="separator"
        aria-label={t('ai.ide.resize')}
        aria-orientation="vertical"
        aria-controls="ide-panel"
        aria-valuemin={MIN_IDE_WIDTH}
        tabIndex={isOpen ? 0 : -1}
        hidden={!isOpen}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={finishPointer}
        onPointerCancel={finishPointer}
        onLostPointerCapture={finishPointer}
        onKeyDown={onKeyDown}
      />
      {panel}
    </div>
  )
}
