import { useEffect, useMemo, useRef, useState } from 'react'
import { filterSessionCommands, type SessionCommand } from '@/features/ai/session-panel/session-commands'

export function SessionCommandPalette({
  open,
  query,
  onQueryChange,
  onSelect,
  onClose,
  captureFocus = true,
}: {
  open: boolean
  query: string
  onQueryChange: (query: string) => void
  onSelect: (command: SessionCommand) => void
  onClose: () => void
  /** When false (slash mode), keep typing in the main composer textarea. */
  captureFocus?: boolean
}) {
  const commands = useMemo(() => filterSessionCommands(query), [query])
  const [index, setIndex] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    setIndex(0)
  }, [query, open])

  useEffect(() => {
    if (open && captureFocus) {
      inputRef.current?.focus()
    }
  }, [open, captureFocus])

  if (!open) {
    return null
  }

  return (
    <div className="session-command-palette" role="listbox" aria-label="命令表">
      {captureFocus ? (
        <div className="session-command-search">
          <span>/</span>
          <input
            ref={inputRef}
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
            placeholder="搜索命令…"
            onKeyDown={(event) => {
              if (event.key === 'Escape') {
                event.preventDefault()
                event.stopPropagation()
                onClose()
                return
              }
              if (event.key === 'ArrowDown') {
                event.preventDefault()
                setIndex((value) => Math.min(value + 1, Math.max(commands.length - 1, 0)))
                return
              }
              if (event.key === 'ArrowUp') {
                event.preventDefault()
                setIndex((value) => Math.max(value - 1, 0))
                return
              }
              if (event.key === 'Enter') {
                event.preventDefault()
                const command = commands[index]
                if (command) {
                  onSelect(command)
                }
              }
            }}
          />
        </div>
      ) : (
        <div className="session-command-search">
          <span>/</span>
          <span className="session-command-query">{query || '搜索命令…'}</span>
        </div>
      )}
      <ul className="session-command-list">
        {commands.length === 0 ? <li className="session-command-empty">无匹配命令</li> : null}
        {commands.map((command, commandIndex) => (
          <li key={command.id}>
            <button
              type="button"
              role="option"
              aria-selected={commandIndex === index}
              className={commandIndex === index ? 'active' : undefined}
              onMouseEnter={() => setIndex(commandIndex)}
              onClick={() => onSelect(command)}
            >
              <span className="session-command-label">{command.label}</span>
              <span className="session-command-desc">{command.description}</span>
            </button>
          </li>
        ))}
      </ul>
      <div className="session-command-meta">
        {commands.length}
        /
        {filterSessionCommands('').length}
      </div>
    </div>
  )
}
