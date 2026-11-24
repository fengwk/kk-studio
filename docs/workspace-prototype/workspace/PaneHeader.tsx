import { Fragment, type ReactNode } from 'react'
import { ChevronDown, Columns2, Maximize2, Minimize2, PanelsTopLeft, Rows2, X } from 'lucide-react'
import { IconButton } from '@/shared/ui/controls/IconButton'

export type SplitDirection = 'row' | 'col'
export type PaneHeaderAction = 'view-select' | 'pane-split' | 'pane-close' | 'pane-fullscreen'
export type PaneHeaderProps = {
  id: string
  titleParts: readonly string[]
  letter?: string
  canDrag: boolean
  canSwitch: boolean
  canClose: boolean
  fullscreen: boolean
  actions?: ReactNode
  mobile?: boolean
  onAction: (action: PaneHeaderAction, anchor: HTMLButtonElement, direction?: SplitDirection) => void
}

/** Pane chrome renders view state. It neither selects a resource nor creates a split. */
export function PaneHeader({ id, titleParts, letter, canDrag, canSwitch, canClose, fullscreen, actions, mobile, onAction }: PaneHeaderProps) {
  const title = titleParts.join(' › ')
  const crumb = <span className="x-crumb" title={title}>
    {titleParts.map((part, index) => <Fragment key={index}>
      {index > 0 && <span className="x-sep" aria-hidden="true">›</span>}
      <span className={index === titleParts.length - 1 ? 'x-c-last' : 'x-c-dim'} data-allow-truncate>{part}</span>
    </Fragment>)}
  </span>
  const menu = { 'aria-haspopup': 'menu', 'aria-expanded': false, 'aria-controls': 'x-menu' } as const
  const split = (direction?: SplitDirection) => {
    const label = direction === 'row' ? '向右拆分' : direction === 'col' ? '向下拆分' : '拆分窗格'
    const Icon = direction === 'row' ? Columns2 : direction === 'col' ? Rows2 : PanelsTopLeft
    return <IconButton size="compact" className="x-ib x-pane-split" label={label} {...(direction ? {} : menu)}
      aria-keyshortcuts={direction === 'row' ? 'V' : direction === 'col' ? 'S' : undefined}
      data-action="pane-split" data-dir={direction ?? ''} data-fkey={`split:${id}:${direction ?? 'mobile'}`}
      onClick={({ currentTarget }) => onAction('pane-split', currentTarget, direction)}>
      <Icon className="x-ic" aria-hidden="true" />
    </IconButton>
  }
  const fullscreenLabel = fullscreen ? '退出全屏' : '全屏'
  const FullscreenIcon = fullscreen ? Minimize2 : Maximize2
  return <>
    {letter && <span className="x-pane-letter" aria-hidden="true" draggable={canDrag && !mobile ? true : undefined}
      data-pane-grip={canDrag && !mobile ? '' : undefined} title={canDrag && !mobile ? `拖动窗格 ${letter} 交换位置` : undefined}>{letter}</span>}
    {canSwitch ? <button type="button" className="x-view-select x-chat-btn" data-action="view-select"
      data-fkey={`view-select:${mobile ? 'mobile' : 'desktop'}`}
      aria-label={`已打开视图：${letter ? `${letter} ` : ''}${title}`} {...menu}
      onClick={({ currentTarget }) => onAction('view-select', currentTarget)}>
      {crumb}<span className="x-view-chevron" aria-hidden="true"><ChevronDown className="x-ic" /></span>
    </button> : crumb}
    <div className="x-pane-actions">
      {actions}{!fullscreen && (mobile ? split() : <>{split('row')}{split('col')}</>)}
      <IconButton size="compact" className="x-ib" label={fullscreenLabel} title={fullscreenLabel} data-action="pane-fullscreen"
        onClick={({ currentTarget }) => onAction('pane-fullscreen', currentTarget)}><FullscreenIcon className="x-ic" aria-hidden="true" /></IconButton>
      {canClose && <IconButton size="compact" className="x-ib" label="关闭窗格" title="关闭窗格" data-action="pane-close"
        onClick={({ currentTarget }) => onAction('pane-close', currentTarget)}><X className="x-ic" aria-hidden="true" /></IconButton>}
    </div>
  </>
}
