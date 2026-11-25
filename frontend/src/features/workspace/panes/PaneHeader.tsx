import { useEffect, useRef, useState, type ReactNode } from 'react'
import { ChevronDown, Columns2, Maximize2, Minimize2, PanelsTopLeft, Rows2, X } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { Menu, type MenuItem } from '@/shared/ui/overlays/Menu'
import type { Axis } from './model'
import './panes.css'

export type PaneLabel = { id: string; letter: string; title: string }
export type PaneHeaderProps = {
  pane: PaneLabel
  choices: readonly PaneLabel[]
  active: boolean
  narrow: boolean
  fullscreen: boolean
  splitBlocked?: Partial<Record<Axis, string>>
  actions?: ReactNode
  leading?: ReactNode
  onSplit: (axis: Axis) => void
  onSwitch: (paneId: string) => void
  onFullscreenChange: (fullscreen: boolean) => void
  onClose: () => void
  onSwap: (from: string, to: string) => void
}

const DRAG_TYPE = 'application/x-kkstudio-pane'

export function PaneHeader({ pane, choices, active, narrow, fullscreen, splitBlocked, actions, leading,
  onSplit, onSwitch, onFullscreenChange, onClose, onSwap }: PaneHeaderProps) {
  const { t } = useI18n()
  const [menu, setMenu] = useState<{ kind: 'switch' | 'split'; anchor: HTMLButtonElement }>()
  const header = useRef<HTMLElement>(null)
  const multiple = choices.length > 1
  const drag = multiple && !narrow && !fullscreen
  const switchable = multiple && narrow && !fullscreen
  const menuKind = menu && (menu.kind === 'switch' ? switchable : narrow && !fullscreen) ? menu.kind : undefined
  const showMenu = (kind: 'split' | 'switch', anchor: HTMLButtonElement) => setMenu(menuKind === kind ? undefined : { kind, anchor })
  useEffect(() => {
    if (!menuKind) return
    const dismiss = (event: PointerEvent) => {
      if (event.target instanceof Node && !header.current?.contains(event.target)) setMenu(undefined)
    }
    document.addEventListener('pointerdown', dismiss)
    return () => document.removeEventListener('pointerdown', dismiss)
  }, [menuKind])

  const split = (axis: Axis) => { if (!fullscreen && !splitBlocked?.[axis]) onSplit(axis) }
  const splitLabels = { X: t('pane.splitRight'), Y: t('pane.splitDown') }
  const splitItems: MenuItem[] = (['X', 'Y'] as const).map(axis => ({
    label: splitLabels[axis], disabled: !!splitBlocked?.[axis], onSelect: () => split(axis),
  }))
  const switchItems: MenuItem[] = choices.map(choice => ({
    label: t('pane.choice', choice), onSelect: () => onSwitch(choice.id),
  }))
  switchItems.push({ label: t('pane.close'), onSelect: onClose })
  const title = <span className="ws-pane-title" title={pane.title}>{pane.title}</span>
  const letter = multiple && <span className="ws-pane-letter" aria-hidden="true" data-active={active || undefined}
    draggable={drag} data-pane-grip={drag ? '' : undefined} title={drag ? t('pane.drag', pane) : undefined}
    onDragStart={event => { event.dataTransfer.setData(DRAG_TYPE, pane.id); event.dataTransfer.effectAllowed = 'move' }}>
    {pane.letter}
  </span>
  const fullscreenLabel = t(fullscreen ? 'pane.exitFullscreen' : 'pane.fullscreen')
  const FullscreenIcon = fullscreen ? Minimize2 : Maximize2

  return <header ref={header} className="ws-pane-header" data-pane-header tabIndex={0} data-ui-focus="row"
    data-narrow={narrow || undefined} aria-label={pane.title}
    onKeyDown={event => {
      if (event.target !== event.currentTarget || event.nativeEvent.isComposing || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return
      const axis = event.key === 'v' ? 'X' : event.key === 's' ? 'Y' : undefined
      if (axis) { event.preventDefault(); if (!event.repeat) split(axis) }
    }}
    onDragOver={event => {
      if (drag && event.dataTransfer.types.includes(DRAG_TYPE)) {
        event.preventDefault()
        event.dataTransfer.dropEffect = 'move'
      }
    }}
    onDrop={event => { if (drag && event.dataTransfer.types.includes(DRAG_TYPE)) {
      event.preventDefault()
      onSwap(event.dataTransfer.getData(DRAG_TYPE), pane.id)
    } }}>
    {leading}
    {switchable ? <button className="ws-pane-switch" type="button" data-ui-focus="row"
      aria-label={t('pane.switch', pane)} aria-haspopup="menu" aria-expanded={menuKind === 'switch'}
      onClick={event => showMenu('switch', event.currentTarget)}>
      {letter}{title}<ChevronDown aria-hidden="true" />
    </button> : <>{letter}{title}</>}
    <div className="ws-pane-actions">
      {actions}
      {!fullscreen && (narrow ? <IconButton className="ws-pane-touch" label={t('pane.split')}
        title={splitBlocked?.X} aria-disabled={!!splitBlocked?.X} aria-haspopup="menu" aria-expanded={menuKind === 'split'}
        onClick={event => { if (!splitBlocked?.X) showMenu('split', event.currentTarget) }}>
        <PanelsTopLeft aria-hidden="true" />
      </IconButton> : (['X', 'Y'] as const).map(axis => {
        const Icon = axis === 'X' ? Columns2 : Rows2
        return <IconButton key={axis} size="compact" label={splitLabels[axis]} aria-disabled={!!splitBlocked?.[axis]}
          title={splitBlocked?.[axis] ?? splitLabels[axis]} aria-keyshortcuts={axis === 'X' ? 'V' : 'S'}
          onClick={() => split(axis)}><Icon aria-hidden="true" /></IconButton>
      }))}
      <IconButton className={narrow ? 'ws-pane-touch' : undefined} size="compact" label={fullscreenLabel} title={fullscreenLabel}
        onClick={() => onFullscreenChange(!fullscreen)}><FullscreenIcon aria-hidden="true" /></IconButton>
      {multiple && !narrow && <IconButton size="compact" label={t('pane.close')} title={t('pane.close')} onClick={onClose}>
        <X aria-hidden="true" />
      </IconButton>}
    </div>
    {menuKind && <div className="ws-pane-menu" data-switch={menuKind === 'switch' || undefined}>
      <Menu items={menuKind === 'switch' ? switchItems : splitItems} onClose={() => setMenu(undefined)} returnFocusTo={menu?.anchor} />
    </div>}
  </header>
}
