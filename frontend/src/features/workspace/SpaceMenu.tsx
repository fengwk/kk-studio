import { useEffect, useRef, useState } from 'react'
import { Archive, Check, ChevronsUpDown, Pencil, Plus, RotateCcw, SlidersHorizontal } from 'lucide-react'
import type { ChatSpaceDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { TextInput } from '@/shared/ui/controls/TextInput'

export function SpaceMenu({ current, spaces, select, create, rename, archive, settings }: {
  current: ChatSpaceDTO
  spaces: ChatSpaceDTO[]
  select: (id: string) => void
  create: () => void
  rename: (space: ChatSpaceDTO, title: string) => Promise<void>
  archive: (space: ChatSpaceDTO, archived: boolean) => void
  settings: () => void
}) {
  const { t } = useI18n()
  const [open, setOpen] = useState(false)
  const [archived, setArchived] = useState(false)
  const [edit, setEdit] = useState<{ id: string; value: string; pending?: boolean; error?: string }>()
  const host = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const popup = useRef<HTMLDivElement>(null)
  function close() { setOpen(false); setArchived(false); setEdit(undefined) }
  function begin(space: ChatSpaceDTO) { setEdit({ id: space.id, value: space.title }) }
  useEffect(() => {
    if (!open) return
    popup.current?.querySelector<HTMLElement>('[aria-current="true"], [role="menuitem"]')?.focus()
    const outside = (event: PointerEvent) => { if (event.target instanceof Node && !host.current?.contains(event.target)) close() }
    document.addEventListener('pointerdown', outside)
    return () => document.removeEventListener('pointerdown', outside)
  }, [open])
  const action = (run: () => void) => { close(); trigger.current?.focus(); run() }
  const active = spaces.filter(space => !space.archivedAt)
  const inactive = spaces.filter(space => space.archivedAt)
  return <div className="workspace-space-menu" ref={host}>
    <button ref={trigger} className="workspace-space-trigger workspace-touch" type="button" aria-expanded={open} aria-haspopup="menu"
      onClick={() => open ? close() : setOpen(true)}><span className="workspace-row-label" title={current.title}>{current.title}</span><ChevronsUpDown /></button>
    {open && <div className="workspace-space-popup" role="menu" aria-label={t('workspace.spaces')} ref={popup} onKeyDown={event => {
      if (event.nativeEvent.isComposing) return
      if (event.key === 'Tab') { close(); return }
      if (event.key === 'Escape') {
        event.preventDefault(); event.stopPropagation()
        if (edit && !edit.pending) {
          const id = edit.id
          setEdit(undefined)
          requestAnimationFrame(() => popup.current?.querySelector<HTMLElement>(`[data-space="${CSS.escape(id)}"]`)?.focus())
        } else if (!edit) { close(); trigger.current?.focus() }
        return
      }
      if (event.target instanceof HTMLInputElement || event.altKey || event.ctrlKey || event.metaKey) return
      const items = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not(:disabled)'))
      const currentIndex = items.indexOf(document.activeElement as HTMLButtonElement)
      const next = event.key === 'ArrowDown' ? items[(currentIndex + 1) % items.length]
        : event.key === 'ArrowUp' ? items[(currentIndex - 1 + items.length) % items.length]
          : event.key === 'Home' ? items[0] : event.key === 'End' ? items.at(-1) : undefined
      if (next) { event.preventDefault(); next.focus() }
    }}>
      {active.map(space => <div className="workspace-space-row" key={space.id}>
        {edit?.id === space.id ? <form className="workspace-inline-name" onSubmit={event => {
          event.preventDefault()
          if (edit.pending) return
          const title = edit.value.trim()
          if (!title) { setEdit({ ...edit, error: t('controls.nameRequired') }); return }
          setEdit({ ...edit, pending: true })
          void rename(space, title).then(() => action(() => {})).catch((error: Error) => setEdit(currentEdit =>
            currentEdit?.id === space.id ? { ...edit, error: error.message } : currentEdit))
        }}>
          <div className="workspace-name-controls"><TextInput autoFocus readOnly={edit.pending} value={edit.value}
            aria-label={t('workspace.renameItem', { name: space.title })} invalid={Boolean(edit.error)}
            aria-describedby={edit.error ? 'workspace-space-error' : undefined} onFocus={event => event.currentTarget.select()}
            onChange={event => setEdit({ ...edit, value: event.target.value, error: undefined })} />
          <IconButton label={t('shared.confirm')} type="submit" size="compact" disabled={edit.pending}><Check /></IconButton></div>
          {edit.error && <div role="alert" className="workspace-field-error" id="workspace-space-error">{edit.error}</div>}
        </form> : <>
          <button type="button" role="menuitem" tabIndex={-1} data-space={space.id} data-ui-focus="row"
            aria-current={space.id === current.id || undefined}
            onKeyDown={event => { if (event.key === 'F2') { event.preventDefault(); begin(space) } }}
            onClick={() => space.id === current.id ? begin(space) : action(() => select(space.id))}>
            <span className="workspace-row-label" title={space.title}>{space.title}</span>
          </button>
          <IconButton size="compact" className="workspace-space-pencil" label={t('workspace.renameItem', { name: space.title })}
            tabIndex={-1} onClick={() => begin(space)}><Pencil /></IconButton>
        </>}
      </div>)}
      <div className="workspace-menu-actions">
        <button type="button" role="menuitem" tabIndex={-1} onClick={() => action(create)}><Plus />{t('workspace.newSpace')}</button>
        <button type="button" role="menuitem" tabIndex={-1} onClick={() => action(settings)}><SlidersHorizontal />{t('workspace.spaceDefaults')}</button>
        {!current.archivedAt && <button type="button" role="menuitem" tabIndex={-1} onClick={() => action(() => archive(current, true))}><Archive />{t('workspace.archive')}</button>}
        {inactive.length > 0 && <button type="button" role="menuitem" tabIndex={-1} aria-expanded={archived} onClick={() => setArchived(!archived)}><Archive />{t('workspace.archived')}</button>}
        {archived && inactive.map(space => <button type="button" role="menuitem" tabIndex={-1} key={space.id} onClick={() => action(() => archive(space, false))}>
          <RotateCcw /><span className="workspace-row-label" title={space.title}>{space.title}</span></button>)}
      </div>
    </div>}
  </div>
}
