import { useEffect, useMemo, useRef } from 'react'
import {
  filterThreadCommands,
  THREAD_COMMANDS,
  type ThreadCommand,
} from '@/features/ai/runtime/thread-panel/thread-commands'
import { useI18n } from '@/shared/i18n'

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
  /** 过滤后列表的索引（父组件跳过禁用项时，可能指向一个已禁用的行）。 */
  activeIndex: number
  onActiveIndexChange: (index: number) => void
  onSelect: (command: ThreadCommand) => void
}) {
  const { t } = useI18n()
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
    <div className="thread-command-palette" role="listbox" aria-label={t('ai.runtime.command.palette')}>
      <ul ref={listRef} className="thread-command-list">
        {commands.length === 0 ? (
          <li className="thread-command-empty">{t('ai.runtime.command.noMatch')}</li>
        ) : null}
        {commands.map((command, commandIndex) => {
          const disabled = Boolean(command.disabled)
          const active = commandIndex === activeIndex
          const label = command.labelKey ? t(command.labelKey) : command.label
          const description = command.descriptionKey
            ? t(command.descriptionKey)
            : command.description
          const disabledReason = command.disabledReasonKey
            ? t(command.disabledReasonKey)
            : command.disabledReason
          return (
            <li key={command.id}>
              <button
                type="button"
                role="option"
                aria-selected={active}
                aria-disabled={disabled}
                disabled={disabled}
                title={disabled ? disabledReason || t('ai.runtime.command.unavailable') : description}
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
                <span className="thread-command-label">{label}</span>
                <span className="thread-command-desc">
                  {disabled && disabledReason
                    ? `${description} · ${disabledReason}`
                    : description}
                </span>
              </button>
            </li>
          )
        })}
      </ul>
      <div className="thread-command-meta">
        {t('ai.runtime.command.meta', { enabled: enabledCount, total: totalCount })}
        {query.trim() ? t('ai.runtime.command.matches', { count: commands.length }) : ''}
        {t('ai.runtime.command.hint')}
      </div>
    </div>
  )
}
