import { useEffect, useRef, useState } from 'react'
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { Inbox } from 'lucide-react'
import { request } from '@/shared/api/client'
import type { InteractionDTO } from '@/shared/api/generated/contracts'
import { useResourceSync } from '@/shared/app-events/useResourceSync'
import { useI18n } from '@/shared/i18n'
import { IconButton } from '@/shared/ui/controls/IconButton'
import '@/shared/ui/layout/list-row.css'
import { workspaceKeys } from './queries'
import { getThreadPlacement } from './workspace-api'

async function listPending(signal: AbortSignal) {
  const roots = new Map<string, InteractionDTO>()
  let cursor: string | undefined
  do {
    const page = await request('GET /api/harness/interactions', { query: { cursor, limit: 100 } }, { signal })
    for (const item of page.items) {
      if (item.type === 'ENVIRONMENT_WAIT') continue
      const last = roots.get(item.rootThreadId)
      if (!last || item.createdAt > last.createdAt) roots.set(item.rootThreadId, item)
    }
    cursor = page.nextCursor ?? undefined
  } while (cursor)
  return [...roots.values()].sort((a, b) => Number(b.owner.kind === 'CHAT_SPACE') - Number(a.owner.kind === 'CHAT_SPACE') || b.createdAt - a.createdAt)
}

export function WorkspaceInbox({ userId, onOpen }: { userId: string; onOpen: (spaceId: string, threadId: string) => void }) {
  const { t } = useI18n()
  const client = useQueryClient()
  const queryKey = ['workspace-inbox', userId]
  const pending = useQuery({ queryKey, queryFn: ({ signal }) => listPending(signal) })
  useResourceSync({ kind: 'interactions' }, { sync: () => client.invalidateQueries({ queryKey, exact: true }), syncOn: name => name === 'changed' })
  const [open, setOpen] = useState(false)
  const host = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const popup = useRef<HTMLDivElement>(null)
  const items = pending.data ?? []
  const placements = useQueries({ queries: (open ? items : []).map(item => ({
    queryKey: [...workspaceKeys.tree(item.owner.id), 'placement', item.rootThreadId],
    queryFn: ({ signal }: { signal: AbortSignal }) => getThreadPlacement(item.owner.id, item.rootThreadId, signal),
    enabled: item.owner.kind === 'CHAT_SPACE',
  })) })
  useEffect(() => {
    if (!open) return
    popup.current?.focus()
    const dismiss = (event: PointerEvent) => {
      if (event.target instanceof Node && !host.current?.contains(event.target)) setOpen(false)
    }
    document.addEventListener('pointerdown', dismiss)
    return () => document.removeEventListener('pointerdown', dismiss)
  }, [open])
  return <div ref={host} className="workspace-inbox">
    <span className="workspace-inbox-anchor">
      <IconButton ref={trigger} className="workspace-touch" label={t('workspace.inbox')} aria-expanded={open}
        onClick={() => setOpen(!open)}><Inbox />{items.length > 0 && <span className="workspace-inbox-count">{items.length}</span>}</IconButton>
    </span>
    {open && <div ref={popup} tabIndex={-1} role="region" aria-label={t('workspace.inbox')} className="workspace-inbox-popup"
      onKeyDown={event => {
        if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); setOpen(false); trigger.current?.focus() }
      }}>
      {!items.length && <p>{t(pending.isError ? 'shared.requestFailed' : pending.isPending ? 'workspace.loading' : 'workspace.inboxEmpty')}</p>}
      {items.map((item, index) => {
        const label = item.owner.title || t('workspace.inboxBackground')
        const thread = placements[index]?.data
        return <button className="workspace-inbox-row" type="button" key={item.rootThreadId} data-ui-focus="row"
          disabled={item.owner.kind !== 'CHAT_SPACE'} onClick={() => { setOpen(false); trigger.current?.focus(); onOpen(item.owner.id, item.rootThreadId) }}>
          <span className="ui-list-row-label" title={thread?.title}>{thread?.title || t('pane.thread')}</span>
          <span className="ui-list-row-label" title={label}>{label}</span>
        </button>
      })}
    </div>}
  </div>
}
