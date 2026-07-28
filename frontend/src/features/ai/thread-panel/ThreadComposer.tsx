import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { ArrowUp } from 'lucide-react'
import {
  ThreadCommandPalette,
} from '@/features/ai/thread-panel/ThreadCommandPalette'
import {
  firstEnabledCommandIndex,
  stepEnabledCommandIndex,
  useFilteredThreadCommands,
} from '@/features/ai/thread-panel/thread-command-navigation'
import { THREAD_COMMANDS, type ThreadCommand } from '@/features/ai/thread-panel/thread-commands'

const TEXTAREA_MIN_HEIGHT = 28
const TEXTAREA_LINE_HEIGHT = 18
const TEXTAREA_MAX_LINES = 10
const TEXTAREA_MAX_HEIGHT = TEXTAREA_MIN_HEIGHT + TEXTAREA_LINE_HEIGHT * (TEXTAREA_MAX_LINES - 1)

/**
 * Canvas-style dock: typing `/` opens the command table with search.
 * Composer stays enabled while Thread is working or a prior HTTP mutation is in flight.
 * ArrowUp/ArrowDown move the highlighted command; Enter confirms the active enabled item.
 */
export function ThreadComposer({
  draft,
  pending,
  disabled,
  onDraftChange,
  onSubmit,
  onCommand,
  commands = THREAD_COMMANDS,
}: {
  draft: string
  pending: boolean
  disabled: boolean
  onDraftChange: (draft: string) => void
  onSubmit: () => void
  onCommand: (command: ThreadCommand) => void
  commands?: ThreadCommand[]
}) {
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const focusTimerRef = useRef<number | null>(null)
  const wasPendingRef = useRef(false)

  const slashMode = draft.startsWith('/')
  const query = slashMode ? draft.slice(1) : ''
  const filteredCommands = useFilteredThreadCommands(query, commands)
  const [activeIndex, setActiveIndex] = useState(0)

  // Pending HTTP mutation must not block continuous submissions.
  const canSend = useMemo(
    () => Boolean(draft.trim()) && !disabled && !draft.startsWith('/'),
    [disabled, draft],
  )

  const clearFocusTimer = useCallback(() => {
    if (focusTimerRef.current === null) {
      return
    }
    window.clearTimeout(focusTimerRef.current)
    focusTimerRef.current = null
  }, [])

  const focusComposer = useCallback(() => {
    clearFocusTimer()
    const scheduleFocus = (attempt: number, delay: number) => {
      focusTimerRef.current = window.setTimeout(() => {
        focusTimerRef.current = null
        tryFocus(attempt)
      }, delay)
    }
    const tryFocus = (attempt: number) => {
      const el = textareaRef.current
      if (!el || el.disabled) {
        if (attempt < 5) {
          scheduleFocus(attempt + 1, 16)
        }
        return
      }
      if (document.activeElement !== el) {
        el.focus({ preventScroll: true })
      }
      if (attempt < 5 && document.activeElement !== el) {
        scheduleFocus(attempt + 1, 16)
      }
    }
    scheduleFocus(0, 0)
  }, [clearFocusTimer])

  useEffect(() => {
    if (!slashMode) {
      return
    }
    setActiveIndex(firstEnabledCommandIndex(filteredCommands))
  }, [slashMode, query, filteredCommands])

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
  }, [pending, disabled, focusComposer])

  useEffect(() => () => clearFocusTimer(), [clearFocusTimer])

  function closeSlashMode() {
    onDraftChange('')
    focusComposer()
  }

  function handleSelect(command: ThreadCommand) {
    if (command.disabled) {
      return
    }
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
    if (slashMode && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
      event.preventDefault()
      event.stopPropagation()
      const delta = event.key === 'ArrowDown' ? 1 : -1
      setActiveIndex((current) => stepEnabledCommandIndex(filteredCommands, current, delta))
      return
    }
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      if (slashMode) {
        event.preventDefault()
        const command = filteredCommands[activeIndex]
        if (command && !command.disabled) {
          handleSelect(command)
        } else {
          const fallback = filteredCommands.find((item) => !item.disabled)
          if (fallback) {
            handleSelect(fallback)
          }
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
        commands={commands}
        activeIndex={activeIndex}
        onActiveIndexChange={setActiveIndex}
        onSelect={handleSelect}
      />
      <div className="thread-dock">
        <textarea
          ref={textareaRef}
          value={draft}
          onChange={(event) => onDraftChange(event.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="输入任务（/打开命令）"
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
          <ArrowUp className="send-icon" aria-hidden="true" />
        </button>
      </div>
    </div>
  )
}
