import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { PlusIcon, SendIcon } from '@/features/canvas/icons'
import { ThreadCommandPalette } from '@/features/ai/thread-panel/ThreadCommandPalette'
import { filterThreadCommands, type ThreadCommand } from '@/features/ai/thread-panel/thread-commands'
import type { ThreadStatus } from '@/shared/api/contracts'

const TEXTAREA_MIN_HEIGHT = 37
const TEXTAREA_LINE_HEIGHT = 19
const TEXTAREA_MAX_LINES = 10
const TEXTAREA_MAX_HEIGHT = TEXTAREA_MIN_HEIGHT + TEXTAREA_LINE_HEIGHT * (TEXTAREA_MAX_LINES - 1)

/**
 * Canvas-style dock: + opens command table; typing `/` also opens it with search.
 * Composer stays enabled while Thread is working or a prior HTTP mutation is in flight.
 */
export function ThreadComposer({
  draft,
  pending,
  disabled,
  controlsPending,
  onDraftChange,
  onSubmit,
  onCommand,
  threadStatus,
  onStop,
  onRetry,
  stopPending,
  retryPending,
}: {
  draft: string
  pending: boolean
  disabled: boolean
  controlsPending: boolean
  onDraftChange: (draft: string) => void
  onSubmit: () => void
  onCommand: (command: ThreadCommand) => void
  threadStatus?: ThreadStatus
  onStop: () => void
  onRetry: () => void
  stopPending: boolean
  retryPending: boolean
}) {
  const [menuOpen, setMenuOpen] = useState(false)
  const [menuQuery, setMenuQuery] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const wasPendingRef = useRef(false)

  const slashMode = draft.startsWith('/')
  const open = menuOpen || slashMode
  const query = slashMode ? draft.slice(1) : menuQuery

  // Pending HTTP mutation must not block continuous submissions.
  const canSend = useMemo(
    () => Boolean(draft.trim()) && !disabled && !draft.startsWith('/'),
    [disabled, draft],
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

  function handleSelect(command: ThreadCommand) {
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
      return
    }
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      if (open) {
        if (slashMode) {
          event.preventDefault()
          const command = filterThreadCommands(query)[0]
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
    <div className="thread-composer">
      <ThreadCommandPalette
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
      <div className="thread-dock">
        <button
          className="thread-dock-add"
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
          className="thread-dock-send"
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
      <div className="thread-composer-extra">
        {(threadStatus === 'RUNNING' || threadStatus === 'WAITING' || threadStatus === 'RETRYING') ? (
          <button className="thread-extra-btn" type="button" onClick={onStop} disabled={stopPending}>
            {stopPending ? 'Stopping…' : 'Stop'}
          </button>
        ) : null}
        {threadStatus === 'FAILED' ? (
          <button className="thread-extra-btn" type="button" onClick={onRetry} disabled={retryPending}>
            {retryPending ? 'Retrying…' : 'Retry'}
          </button>
        ) : null}
        {threadStatus ? <span className="thread-extra-hint">{threadStatus}</span> : null}
      </div>
    </div>
  )
}
