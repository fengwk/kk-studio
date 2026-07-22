import { useEffect, useMemo, useRef } from 'react'
import {
  filterThreadCommands,
  THREAD_COMMANDS,
  type ThreadCommand,
} from '@/features/ai/thread-panel/thread-commands'

export function ThreadCommandPalette({
  open,
  query,
  commands: commandSource = THREAD_COMMANDS,
  activeIndex,
  onActiveIndexChange,
  onSelect,
}: {
  open: boolean
  query: string
  commands?: ThreadCommand[]
  /** Index into the filtered list (may point at a disabled row while moving is skipped by parent). */
  activeIndex: number
  onActiveIndexChange: (index: number) => void
  onSelect: (command: ThreadCommand) => void
}) {
  const commands = useMemo(() => filterThreadCommands(query, commandSource), [commandSource, query])
  const listRef = useRef<HTMLUListElement>(null)
  const enabledCount = useMemo(
    () => commands.reduce((count, command) => count + (command.disabled ? 0 : 1), 0),
    [commands],
  )
  const totalCount = commandSource.length

  useEffect(() => {
    if (!open) {
      return
    }
    const active = listRef.current?.querySelector<HTMLElement>('[aria-selected="true"]')
    active?.scrollIntoView({ block: 'nearest' })
  }, [activeIndex, open, commands])

  if (!open) {
    return null
  }

  return (
    <div className="thread-command-palette" role="listbox" aria-label="命令表">
      <div className="thread-command-search">
        <span>/</span>
        <span className="thread-command-query">{query || '搜索命令…'}</span>
      </div>
      <ul ref={listRef} className="thread-command-list">
        {commands.length === 0 ? <li className="thread-command-empty">无匹配命令</li> : null}
        {commands.map((command, commandIndex) => {
          const disabled = Boolean(command.disabled)
          const active = commandIndex === activeIndex
          return (
            <li key={command.id}>
              <button
                type="button"
                role="option"
                aria-selected={active}
                aria-disabled={disabled}
                disabled={disabled}
                title={disabled ? command.disabledReason || '当前不可用' : command.description}
                className={
                  [active ? 'active' : '', disabled ? 'is-disabled' : ''].filter(Boolean).join(' ') || undefined
                }
                onMouseEnter={() => {
                  if (!disabled) {
                    onActiveIndexChange(commandIndex)
                  }
                }}
                onClick={() => {
                  if (!disabled) {
                    onSelect(command)
                  }
                }}
              >
                <span className="thread-command-label">{command.label}</span>
                <span className="thread-command-desc">
                  {disabled && command.disabledReason
                    ? `${command.description} · ${command.disabledReason}`
                    : command.description}
                </span>
              </button>
            </li>
          )
        })}
      </ul>
      <div className="thread-command-meta">
        可用 {enabledCount} / 共 {totalCount}
        {query.trim() ? ` · 匹配 ${commands.length}` : ''}
        {' · ↑↓ 选择 · Enter 确认'}
      </div>
    </div>
  )
}
