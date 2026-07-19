import { useEffect, useMemo, useState } from 'react'
import { filterThreadCommands, type ThreadCommand } from '@/features/ai/thread-panel/thread-commands'

export function ThreadCommandPalette({
  open,
  query,
  onSelect,
}: {
  open: boolean
  query: string
  onSelect: (command: ThreadCommand) => void
}) {
  const commands = useMemo(() => filterThreadCommands(query), [query])
  const [index, setIndex] = useState(0)

  useEffect(() => {
    setIndex(0)
  }, [query, open])

  if (!open) {
    return null
  }

  return (
    <div className="thread-command-palette" role="listbox" aria-label="命令表">
      <div className="thread-command-search">
        <span>/</span>
        <span className="thread-command-query">{query || '搜索命令…'}</span>
      </div>
      <ul className="thread-command-list">
        {commands.length === 0 ? <li className="thread-command-empty">无匹配命令</li> : null}
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
              <span className="thread-command-label">{command.label}</span>
              <span className="thread-command-desc">{command.description}</span>
            </button>
          </li>
        ))}
      </ul>
      <div className="thread-command-meta">
        {commands.length}
        /
        {filterThreadCommands('').length}
      </div>
    </div>
  )
}
