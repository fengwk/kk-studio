import {
  Archive, ArchiveRestore, ArrowLeft, Check, Columns2, Folder, FolderPlus, GitBranch, LayoutTemplate, PanelRight,
  Pencil, Play, Plus, Rows2, Trash2, X,
} from 'lucide-react'
import { useId, useLayoutEffect, useRef, useState } from 'react'
import { FieldError } from '@/shared/ui/controls/FieldError'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { svg } from './icons.js'

const icons = {
  archive: Archive, archiveRestore: ArchiveRestore, back: ArrowLeft,
  branch: GitBranch, canvas: LayoutTemplate, folder: Folder, folderPlus: FolderPlus,
  pencil: Pencil, play: Play, plus: Plus, sideR: PanelRight,
  splitD: Rows2, splitR: Columns2, trash: Trash2, x: X,
}

type MenuItem = {
  label: string
  icon?: keyof typeof icons | 'keyRound' | 'logOut'
  letter?: string
  note?: string
  key?: string
  danger?: boolean
  disabled?: boolean
  current?: boolean
  accessibleLabel?: string
  rename?: (name: string) => string
  sep?: false
} | { sep: true }

function SpaceNameEditor({ title, save }: { title: string; save: (name: string) => string }) {
  const input = useRef<HTMLInputElement>(null)
  const errorId = useId()
  const [error, setError] = useState('')
  useLayoutEffect(() => {
    input.current?.focus()
    input.current?.select()
  }, [])
  function submit() {
    const message = save(input.current!.value.trim())
    if (message) {
      setError(message)
      input.current?.focus()
    }
  }
  return <div className="x-menu-rename">
    <div className="x-menu-rename-controls">
      <TextInput ref={input} defaultValue={title} aria-label="空间名称" maxLength={80} autoComplete="off"
        invalid={!!error} aria-describedby={error ? errorId : undefined} onChange={() => setError('')}
        onKeyDown={(event) => {
          if (event.key !== 'Enter' || event.nativeEvent.isComposing || event.keyCode === 229 || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return
          event.preventDefault()
          submit()
        }} />
      <IconButton label="保存空间名称" title="保存空间名称" size="compact" tabIndex={-1} onClick={submit}>
        <Check className="x-ic" aria-hidden="true" />
      </IconButton>
    </div>
    {error && <FieldError id={errorId} role="alert">{error}</FieldError>}
  </div>
}

// Positioning and menu lifecycle stay with the controller; this component owns the rows.
export function WorkspaceMenu({ items, editing }: {
  items: readonly MenuItem[]
  editing?: { index: number; save: (name: string) => string }
}) {
  return items.map((item, index) => {
    if (item.sep) return <div key={index} className="x-menu-sep" role="separator" />
    if (editing?.index === index) return <SpaceNameEditor key={index} title={item.label} save={editing.save} />
    const accountIcon = item.icon === 'keyRound' || item.icon === 'logOut' ? item.icon : null
    const Icon = item.icon && item.icon !== 'keyRound' && item.icon !== 'logOut' ? icons[item.icon] : null
    const row = <button key={index} type="button" role="menuitem" tabIndex={-1}
      className={`x-menu-item${item.danger ? ' x-danger' : ''}`} data-mi={index}
      aria-label={item.current && item.rename ? `修改空间名称：${item.label}` : item.accessibleLabel}
      aria-keyshortcuts={item.rename ? 'F2' : undefined} aria-disabled={item.disabled || undefined} aria-current={item.current || undefined}>
      {item.letter ? <span className="x-menu-letter" aria-hidden="true">{item.letter}</span> :
        Icon ? <Icon className="x-ic" aria-hidden="true" /> :
        accountIcon ? <span className="x-ic-slot" aria-hidden="true" dangerouslySetInnerHTML={{ __html: svg(accountIcon) }} /> :
        <span className="x-ic" aria-hidden="true" />}
      <span className="x-mi-text"><span>{item.label}</span>{item.note && <small>{item.note}</small>}</span>
      {item.current && !item.rename && <Check className="x-ic" aria-hidden="true" />}
      {item.key && <kbd>{item.key}</kbd>}
    </button>
    if (!item.rename) return row
    const editLabel = `重命名「${item.label}」`
    return <div key={index} className="x-menu-space-row" role="presentation">
      {row}
      <IconButton className="x-menu-space-edit" data-mi={index} role="menuitem" tabIndex={-1}
        size="compact" label={editLabel} title={editLabel}>
        <Pencil className="x-ic" aria-hidden="true" />
      </IconButton>
    </div>
  })
}
