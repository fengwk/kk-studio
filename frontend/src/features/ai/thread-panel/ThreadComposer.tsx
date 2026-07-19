import { useEffect, useMemo, useRef, type KeyboardEvent } from 'react'
import { SendIcon } from '@/features/canvas/icons'
import { ThreadCommandPalette } from '@/features/ai/thread-panel/ThreadCommandPalette'
import { filterThreadCommands, type ThreadCommand } from '@/features/ai/thread-panel/thread-commands'

const TEXTAREA_MIN_HEIGHT = 37
const TEXTAREA_LINE_HEIGHT = 19
const TEXTAREA_MAX_LINES = 10
const TEXTAREA_MAX_HEIGHT = TEXTAREA_MIN_HEIGHT + TEXTAREA_LINE_HEIGHT * (TEXTAREA_MAX_LINES - 1)

/**
 * Canvas-style dock: typing `/` opens the command table with search.
 * Composer stays enabled while Thread is working or a prior HTTP mutation is in flight.
 */
export function ThreadComposer({
  draft,
  pending,
  disabled,
  onDraftChange,
  onSubmit,
  onCommand,
}: {
  draft: string
  pending: boolean
  disabled: boolean
  onDraftChange: (draft: string) => void
  onSubmit: () => void
  onCommand: (command: ThreadCommand) => void
}) {
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const wasPendingRef = useRef(false)

  const slashMode = draft.startsWith('/')
  const query = slashMode ? draft.slice(1) : ''

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

  function closeSlashMode() {
    onDraftChange('')
    focusComposer()
  }

  function handleSelect(command: ThreadCommand) {
    closeSlashMode()
    onCommand(command)
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Escape' && slashMode) {
      event.preventDefault()
      event.stopPropagation()
      closeSlashMode()
      return
    }
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      if (slashMode) {
        event.preventDefault()
        const command = filterThreadCommands(query)[0]
        if (command) {
          handleSelect(command)
        }
        return
      }
      event.preventDefault()
      onSubmit()
      focusComposer()
    }
  }

  return (
    <div className="thread-composer">
      <ThreadCommandPalette
        open={slashMode}
        query={query}
        onSelect={handleSelect}
      />
      <div className="thread-dock">
        <textarea
          ref={textareaRef}
          value={draft}
          onChange={(event) => onDraftChange(event.target.value)}
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
    </div>
  )
}
