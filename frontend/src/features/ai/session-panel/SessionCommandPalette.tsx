import { useEffect, useMemo, useState } from 'react'
import { filterSessionCommands, type SessionCommand } from '@/features/ai/session-panel/session-commands'

export function SessionCommandPalette({
  open,
  query,
  onQueryChange,
  onSelect,
  onClose,
}: {
  open: boolean
  query: string
  onQueryChange: (query: string) => void
  onSelect: (command: SessionCommand) => void
  onClose: () => void
}) {
  const commands = useMemo(() => filterSessionCommands(query), [query])
  const [index, setIndex] = useState(0)

  useEffect(() => {
    setIndex(0)
  }, [query, open])

  if (!open) {
    return null
  }

  return (
    <div className="session-command-palette" role="listbox" aria-label="命令表">
      <div className="session-command-search">
        <span>/</span>
        <input
          value={query}
          onChange={(event) => onQueryChange(event.target.value)}
          placeholder="搜索命令…"
          autoFocus
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
