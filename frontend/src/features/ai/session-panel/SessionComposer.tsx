import { useMemo, useState, type KeyboardEvent } from 'react'
import { PlusIcon, SendIcon } from '@/features/canvas/icons'
import { SessionCommandPalette } from '@/features/ai/session-panel/SessionCommandPalette'
import type { SessionCommand } from '@/features/ai/session-panel/session-commands'

/**
 * Canvas-style dock: + opens command table; typing `/` also opens it with search.
 */
export function SessionComposer({
  draft,
  activeRun,
  pending,
  disabled,
  controlsPending,
  onDraftChange,
  onSubmit,
  onCommand,
}: {
  draft: string
  activeRun: boolean
  pending: boolean
  disabled: boolean
  controlsPending: boolean
  onDraftChange: (draft: string) => void
  onSubmit: () => void
  onCommand: (command: SessionCommand) => void
}) {
  const [menuOpen, setMenuOpen] = useState(false)
  const [menuQuery, setMenuQuery] = useState('')

  const slashMode = draft.startsWith('/')
  const open = menuOpen || slashMode
  const query = slashMode ? draft.slice(1) : menuQuery

  const canSend = useMemo(
    () => Boolean(draft.trim()) && !pending && !disabled && !activeRun && !draft.startsWith('/'),
    [activeRun, disabled, draft, pending],
  )

  function openMenu() {
    setMenuOpen(true)
    setMenuQuery('')
  }

  function closeMenu() {
    setMenuOpen(false)
    setMenuQuery('')
    if (draft.startsWith('/')) {
      onDraftChange('')
    }
  }

  function handleSelect(command: SessionCommand) {
    closeMenu()
    if (draft.startsWith('/')) {
      onDraftChange('')
    }
    onCommand(command)
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Escape' && open) {
      event.preventDefault()
      closeMenu()
      return
    }
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      if (open) {
        return
      }
      event.preventDefault()
      onSubmit()
    }
  }

  function handleChange(value: string) {
    onDraftChange(value)
    if (value.startsWith('/')) {
      setMenuOpen(false)
    }
  }

  return (
    <div className="session-composer">
      <SessionCommandPalette
        open={open}
        query={query}
        onQueryChange={(value) => {
          if (slashMode) {
            onDraftChange(`/${value}`)
          } else {
            setMenuQuery(value)
          }
        }}
        onSelect={handleSelect}
        onClose={closeMenu}
      />
      <div className="session-dock">
        <button
          className="session-dock-add"
          type="button"
          aria-label="打开命令表"
          aria-expanded={open}
          onClick={() => (open ? closeMenu() : openMenu())}
          disabled={disabled || controlsPending}
        >
          <PlusIcon />
        </button>
        <textarea
          value={draft}
          onChange={(event) => handleChange(event.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="告诉 Agent 下一步要完成什么…（/ 打开命令）"
          disabled={disabled || pending}
          rows={1}
          aria-label="给 AI 发送消息"
        />
        <button
          className="session-dock-send"
          type="button"
          aria-label="发送消息"
          onClick={onSubmit}
          disabled={!canSend}
        >
          <SendIcon />
        </button>
      </div>
    </div>
  )
}
