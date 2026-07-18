import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { PlusIcon, SendIcon } from '@/features/canvas/icons'
import { SessionCommandPalette } from '@/features/ai/session-panel/SessionCommandPalette'
import { filterSessionCommands, type SessionCommand } from '@/features/ai/session-panel/session-commands'

const TEXTAREA_MIN_HEIGHT = 37
const TEXTAREA_LINE_HEIGHT = 19
const TEXTAREA_MAX_LINES = 10
const TEXTAREA_MAX_HEIGHT = TEXTAREA_MIN_HEIGHT + TEXTAREA_LINE_HEIGHT * (TEXTAREA_MAX_LINES - 1)

/**
 * Canvas-style dock: + opens command table; typing `/` also opens it with search.
 * Composer keeps focus while typing and after send/cancel.
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
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const wasPendingRef = useRef(false)

  const slashMode = draft.startsWith('/')
  const open = menuOpen || slashMode
  const query = slashMode ? draft.slice(1) : menuQuery

  const canSend = useMemo(
    () => Boolean(draft.trim()) && !pending && !disabled && !activeRun && !draft.startsWith('/'),
    [activeRun, disabled, draft, pending],
  )

  useEffect(() => {
    const el = textareaRef.current
    if (!el) {
      return
    }
    el.style.height = 'auto'
    const next = Math.max(TEXTAREA_MIN_HEIGHT, Math.min(el.scrollHeight, TEXTAREA_MAX_HEIGHT))
    el.style.height = `${next}px`
    el.style.overflowY = el.scrollHeight > TEXTAREA_MAX_HEIGHT ? 'auto' : 'hidden'
  }, [draft])

  // After send completes (pending true -> false), keep typing in the composer.
  useEffect(() => {
    if (wasPendingRef.current && !pending && !disabled) {
      focusComposer()
    }
    wasPendingRef.current = pending
  }, [pending, disabled])

  function focusComposer() {
    const tryFocus = (attempt: number) => {
      const el = textareaRef.current
      if (!el || el.disabled) {
        if (attempt < 5) {
          window.setTimeout(() => tryFocus(attempt + 1), 16)
        }
        return
      }
      if (document.activeElement !== el) {
        el.focus({ preventScroll: true })
      }
      if (attempt < 5 && document.activeElement !== el) {
        window.setTimeout(() => tryFocus(attempt + 1), 16)
      }
    }
    window.setTimeout(() => tryFocus(0), 0)
  }

  function openMenu() {
    setMenuOpen(true)
    setMenuQuery('')
  }

  function closeMenu(options?: { restoreFocus?: boolean }) {
    setMenuOpen(false)
    setMenuQuery('')
    if (draft.startsWith('/')) {
      onDraftChange('')
    }
    if (options?.restoreFocus !== false) {
      focusComposer()
    }
  }

  function handleSelect(command: SessionCommand) {
    closeMenu({ restoreFocus: true })
    if (draft.startsWith('/')) {
      onDraftChange('')
    }
    onCommand(command)
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Escape' && open) {
      event.preventDefault()
      event.stopPropagation()
      closeMenu({ restoreFocus: true })
      return
    }
    if (open && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
      // Keep focus in textarea; palette selection is mouse-driven in slash mode.
      return
    }
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      if (open) {
        // In slash mode Enter executes the first matching command.
        if (slashMode) {
          event.preventDefault()
          const command = filterSessionCommands(query)[0]
          if (command) {
            handleSelect(command)
          }
        }
        return
      }
      event.preventDefault()
      onSubmit()
      focusComposer()
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
        captureFocus={!slashMode}
        onQueryChange={(value) => {
          if (slashMode) {
            onDraftChange(`/${value}`)
          } else {
            setMenuQuery(value)
          }
        }}
        onSelect={handleSelect}
        onClose={() => closeMenu({ restoreFocus: true })}
      />
      <div className="session-dock">
        <button
          className="session-dock-add"
          type="button"
          aria-label="打开命令表"
          aria-expanded={open}
          onClick={() => (open ? closeMenu({ restoreFocus: true }) : openMenu())}
          disabled={disabled || controlsPending}
        >
          <PlusIcon />
        </button>
        <textarea
          ref={textareaRef}
          value={draft}
          onChange={(event) => handleChange(event.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="告诉 Agent 下一步要完成什么…（/ 打开命令）"
          disabled={disabled}
          rows={1}
          aria-label="给 AI 发送消息"
        />
        <button
          className="session-dock-send"
          type="button"
          aria-label="发送消息"
          onClick={() => {
            onSubmit()
            focusComposer()
          }}
          disabled={!canSend}
        >
          <SendIcon />
        </button>
      </div>
    </div>
  )
}
