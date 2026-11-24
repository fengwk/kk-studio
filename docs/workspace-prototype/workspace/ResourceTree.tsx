import { ChevronRight, Folder, LayoutTemplate, MoreHorizontal } from 'lucide-react'
import { IconButton } from '@/shared/ui/controls/IconButton'
import type { CSSProperties } from 'react'
import { ThreadStatusIcon, type ThreadStatus } from './ThreadStatusIcon'

type ResourceRow = {
  key: string
  kind: 'folder' | 'session' | 'canvas' | 'thread'
  title: string
  level: number
  tabIndex: number
  selected: boolean
  movable: boolean
  draggable: boolean
  expanded?: boolean
  rename?: string
  status?: ThreadStatus
  count?: number
  time?: string
}

export function ResourceTree({ rows }: { rows: readonly ResourceRow[] }) {
  if (!rows.length) return <p className="x-none">没有匹配项</p>
  return rows.map((row) => <div key={row.key}
    className={`x-row x-row-${row.kind}`}
    role="treeitem" aria-level={row.level} aria-selected={row.selected} aria-expanded={row.expanded}
    tabIndex={row.tabIndex} data-key={row.key} data-fkey={`row:${row.key}`} data-movable={row.movable || undefined} draggable={row.draggable || undefined}
    style={{ '--x-indent': `${Math.min(row.level - 1, 6) * 18}px` } as CSSProperties}>
    {row.expanded !== undefined ? <ChevronRight className="x-ic x-chev" aria-hidden="true" /> :
      row.kind !== 'thread' && <span className="x-chev-sp" />}
    {row.kind === 'folder' && <Folder className="x-ic x-fic" aria-hidden="true" />}
    {row.kind === 'canvas' && <LayoutTemplate className="x-ic x-fic" aria-hidden="true" />}
    {(row.kind === 'thread' || row.kind === 'session') && <ThreadStatusIcon status={row.status} />}
    {row.rename !== undefined ? <input className="x-rename" data-fkey="rename" maxLength={80} aria-label={`重命名「${row.title}」`} defaultValue={row.rename} /> :
      <span className="x-row-label" data-allow-truncate>{row.title}</span>}
    {row.kind === 'folder' && row.status && <ThreadStatusIcon status={row.status} />}
    <span className="x-tail">
      {row.count !== undefined && <span className="x-count">{row.count}</span>}
      {row.time && <span className="x-meta">{row.time}</span>}
      <IconButton size="compact" className="x-ib x-more" label="更多操作" data-action="row-more" tabIndex={-1}
        aria-haspopup="menu" aria-expanded={false} aria-controls="x-menu">
        <MoreHorizontal className="x-ic" aria-hidden="true" />
      </IconButton>
    </span>
  </div>)
}
