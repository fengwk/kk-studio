import { useEffect, useRef, useState, type CSSProperties, type KeyboardEvent } from 'react'
import { AlertCircle, ChevronRight, Clock, Folder, LoaderCircle, MessageCircle, MoreHorizontal, Plus, Square } from 'lucide-react'
import { createUuid } from '@/shared/lib/uuid'
import { useI18n } from '@/shared/i18n'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Menu, type MenuItem } from '@/shared/ui/overlays/Menu'
import { groupThreads, resolveTreeDrop, threadStates, visibleRows, type TreeDrop, type TreeRow } from './resource-tree'

import { bindTreeDrag } from './tree-drag'

function RowIcon({ row }: { row: TreeRow }) {
  if (row.kind === 'folder') return <Folder />
  if (row.kind === 'canvas') return <Square />
  if (row.kind === 'session') return <MessageCircle />
  if (row.kind !== 'thread') return null
  const Icon = row.thread.state === 'RUNNING' ? LoaderCircle : row.thread.state === 'WAITING' ? Clock : AlertCircle
  return row.thread.state !== 'IDLE' && <Icon data-thread-state={row.thread.state} />
}

const stateLabels = {
  NEEDS_YOU: 'workspace.needsYou', RUNNING: 'workspace.running', WAITING: 'workspace.waiting',
  FAILED: 'workspace.failed', IDLE: 'workspace.idle',
} as const

export function WorkspaceStatus({ index, open }: { index: ReadonlyMap<string, TreeRow>; open: (row: TreeRow, anchor: HTMLElement) => void }) {
  const { t } = useI18n()
  const groups = groupThreads(index.values())
  return <div className="workspace-tree">{threadStates.map(state => {
    const rows = groups.get(state) ?? []
    return rows.length > 0 && <section key={state} className="workspace-status-group">
      <h3>{t(stateLabels[state])}</h3>
      {rows.map(row => {
        const session = row.parentId == null ? undefined : index.get(row.parentId)
        return <button key={row.id} type="button" className="workspace-status-row" data-ui-focus="row"
          onClick={event => open(row, event.currentTarget)}>
          <RowIcon row={row} />
          <span className="workspace-row-label" title={row.name}>{row.name}</span>
          {session && <> <span className="workspace-status-session" title={session.name}>{session.name}</span></>}
        </button>
      })}
    </section>
  })}</div>
}

export function WorkspaceTree({ roots, index, expanded, toggle, open, create, createDirectory, rename, remove, move }: {
  roots: TreeRow[]
  index: ReadonlyMap<string, TreeRow>
  expanded: ReadonlySet<string>
  toggle: (id: string, force?: boolean) => void
  open: (row: TreeRow, anchor: HTMLElement) => void
  create: (kind: 'session' | 'canvas', parentId: string) => void
  createDirectory: (name: string, idempotencyKey: string, parentId?: string) => Promise<string>
  rename: (row: TreeRow, name: string) => Promise<void>
  remove: (row: TreeRow) => void
  move: (row: TreeRow, drop: TreeDrop) => void
}) {
  const { t } = useI18n()
  const [filter, setFilter] = useState('')
  const [selection, select] = useState('')
  const [menu, setMenu] = useState<{ id: string; anchor: HTMLElement }>()
  const [editing, setEditing] = useState<{ id: string; value: string; error?: string; pending?: boolean; newFolder?: { parentId?: string } }>()
  const list = useRef<HTMLDivElement>(null)
  const rows = visibleRows(roots, expanded, filter)
  if (editing?.newFolder) {
    const parentId = editing.newFolder.parentId ?? null
    const parent = parentId ? index.get(parentId) : undefined
    let at = parent ? rows.findIndex(row => row.id === parent.id) + 1 : rows.length
    if (parent) while (at < rows.length && rows[at]!.level > parent.level) at++
    rows.splice(at, 0, { kind: 'folder', id: editing.id, name: t('workspace.newFolder'), parentId, level: parent ? parent.level + 1 : 1, children: [] })
  }
  const selected = rows.find(row => row.id === selection) ?? rows[0]
  useEffect(() => {
    const element = list.current
    if (!element || filter.trim() || editing) return
    return bindTreeDrag(element, {
      resolve: (source, target, y) => resolveTreeDrop(roots, index, source, target, y),
      move: (id, drop) => { const row = index.get(id); if (row) { select(id); move(row, drop) } },
    })
  }, [editing, filter, index, move, roots])
  useEffect(() => {
    if (!menu) return
    const dismiss = (event: PointerEvent) => {
      if (event.target instanceof Element && !event.target.closest('.workspace-row-menu')) setMenu(undefined)
    }
    document.addEventListener('pointerdown', dismiss)
    return () => document.removeEventListener('pointerdown', dismiss)
  }, [menu])

  function focus(id: string) {
    select(id)
    list.current?.querySelector<HTMLElement>(`[data-tree-row="${CSS.escape(id)}"]`)?.focus()
  }
  function activate(row: TreeRow, anchor: HTMLElement) {
    if (row.kind === 'folder' || row.kind === 'session') toggle(row.id)
    else open(row, anchor)
  }
  function startCreate(parentId?: string) {
    setFilter('')
    if (parentId) toggle(parentId, true)
    setEditing({ id: createUuid(), value: '', newFolder: { parentId } })
  }
  function startRename(row: TreeRow) { setEditing({ id: row.id, value: row.name }) }
  function keys(event: KeyboardEvent<HTMLDivElement>, row: TreeRow) {
    if (event.target !== event.currentTarget || event.altKey || event.ctrlKey || event.metaKey || event.nativeEvent.isComposing) return
    const i = rows.indexOf(row)
    const parent = row.parentId && rows.find(candidate => candidate.id === row.parentId)
    const child = rows[i + 1]?.parentId === row.id ? rows[i + 1] : undefined
    switch (event.key) {
      case 'j': case 'ArrowDown': focus(rows[i + 1]?.id ?? row.id); break
      case 'k': case 'ArrowUp': focus(rows[i - 1]?.id ?? row.id); break
      case 'Home': focus(rows[0]!.id); break
      case 'End': focus(rows.at(-1)!.id); break
      case 'h': case 'ArrowLeft':
        if (child && !filter.trim()) toggle(row.id, false)
        else if (parent) focus(parent.id)
        break
      case 'l': case 'ArrowRight':
        if (child) focus(child.id)
        else if (row.children.length) toggle(row.id, true)
        break
      case 'Enter': case ' ': activate(row, event.currentTarget); break
      case 'r': case 'F2': startRename(row); break
      case 'd': case 'Delete': remove(row); break
      case 'ContextMenu': case 'F10': {
        if (event.key === 'F10' && !event.shiftKey) return
        const anchor = event.currentTarget.querySelector('button')
        if (anchor) setMenu({ id: row.id, anchor })
        break
      }
      default: return
    }
    event.preventDefault()
    event.stopPropagation()
  }
  function menuItems(row: TreeRow): MenuItem[] {
    return [
      ...(row.kind === 'folder' ? ([['session', 'pane.newSession'], ['canvas', 'workspace.newCanvas'], ['folder', 'workspace.newFolder']] as const)
        .map(([kind, label]) => ({ label: t(label), onSelect: () => { if (kind === 'folder') startCreate(row.id); else { setFilter(''); create(kind, row.id) } } })) : []),
      { label: t('workspace.rename'), onSelect: () => startRename(row) },
      { label: t('workspace.delete'), onSelect: () => remove(row) },
    ]
  }

  return <>
    <div className="workspace-tree-tools">
      <TextInput type="search" aria-label={t('workspace.filter')} placeholder={t('workspace.filter')} value={filter} onChange={event => setFilter(event.target.value)} />
      <IconButton className="workspace-touch" label={t('workspace.newFolder')} onClick={() => startCreate()}><Plus /></IconButton>
    </div>
    <div className="workspace-tree" role="tree" aria-label={t('workspace.directory')} ref={list}>
      {rows.map((row, i) => {
        const editor = editing?.id === row.id ? editing : undefined
        const movable = !filter.trim() && !editor && row.kind !== 'thread'
        const rowExpanded = row.children.length ? rows[i + 1]?.parentId === row.id : undefined
        return <div key={row.id} className="workspace-row" role="treeitem" aria-level={row.level} data-ui-focus="row"
          tabIndex={selected === row ? 0 : -1} aria-selected={selected === row} aria-expanded={rowExpanded}
          aria-label={row.name} data-tree-row={row.id} data-tree-movable={movable || undefined} draggable={movable}
          style={{ '--workspace-level': Math.min(6, row.level - 1) } as CSSProperties}
          onKeyDown={event => keys(event, row)} onFocus={event => { if (event.target === event.currentTarget) select(row.id) }}
          onClick={event => { if (!(event.target as Element).closest('button, input, form')) { focus(row.id); activate(row, event.currentTarget) } }}>
          <span className="workspace-row-chevron" aria-hidden="true">{row.children.length > 0 && <ChevronRight />}</span>
          <RowIcon row={row} />
          {editor ? <form className="workspace-inline-name" onSubmit={event => {
            event.preventDefault()
            if (editor.pending) return
            const name = editor.value.trim()
            if (!name) { setEditing({ ...editor, error: t('controls.nameRequired') }); return }
            setEditing({ ...editor, pending: true })
            const saved = editor.newFolder ? createDirectory(name, editor.id, editor.newFolder.parentId) : rename(row, name).then(() => row.id)
            void saved.then(id => { setEditing(undefined); requestAnimationFrame(() => focus(id)) })
              .catch((error: Error) => setEditing({ ...editor, error: error.message }))
          }}>
            <TextInput autoFocus value={editor.value} aria-label={editor.newFolder ? t('workspace.newFolder') : t('workspace.renameItem', { name: row.name })}
              invalid={Boolean(editor.error)} aria-describedby={editor.error ? `workspace-error-${row.id}` : undefined}
              readOnly={editor.pending} onFocus={event => event.currentTarget.select()}
              onKeyDown={event => {
                if (event.key === 'Escape' && !editor.pending) { event.preventDefault(); event.stopPropagation(); setEditing(undefined); requestAnimationFrame(() => { if (!editor.newFolder) focus(row.id); else if (row.parentId) focus(row.parentId); else list.current?.previousElementSibling?.querySelector('button')?.focus() }) }
              }} onChange={event => setEditing({ ...editor, value: event.target.value, error: undefined })} />
            {editor.error && <span role="alert" className="workspace-field-error" id={`workspace-error-${row.id}`}>{editor.error}</span>}
          </form> : <span className="workspace-row-label" title={row.name}>{row.name}</span>}
          {!editor && <div className="workspace-row-menu">
            <IconButton className="workspace-more workspace-touch" label={t('workspace.actions', { name: row.name })} size="compact"
              aria-haspopup="menu" aria-expanded={menu?.id === row.id} tabIndex={-1}
              onClick={event => setMenu(menu?.id === row.id ? undefined : { id: row.id, anchor: event.currentTarget })}><MoreHorizontal /></IconButton>
            {menu?.id === row.id && <Menu items={menuItems(row)} onClose={() => setMenu(undefined)} returnFocusTo={menu.anchor} />}
          </div>}
        </div>
      })}
    </div>
  </>
}
