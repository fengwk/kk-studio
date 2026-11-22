import { useEffect, useMemo, useRef } from 'react'
import {
  filterThreadCommands,
  THREAD_COMMANDS,
  type ThreadCommand,
} from '@/features/thread/runtime/thread-panel/thread-commands'
import { useI18n } from '@/shared/i18n'
import { threadCommandOptionId } from '@/features/thread/runtime/thread-panel/thread-command-navigation'
import './thread-command-palette.css'

/**
 * 命令表：`role="listbox"` 的可选命令列表。
 *
 * 列表容器与行只是展示层（`role="presentation"`），使 `role="option"` 成为 listbox 的
 * ARIA 直属子项；焦点始终留在编辑器，由 listbox 的 `aria-activedescendant`（以及
 * 编辑器上的 `aria-controls`）表达键盘游标所在项。
 */
export function ThreadCommandPalette({
  open,
  query,
  listboxId,
  commands: commandSource = THREAD_COMMANDS,
  activeIndex,
  scrollVersion = 0,
  onActiveIndexChange,
  onSelect,
}: {
  open: boolean
  query: string
  /** listbox 的 DOM id，同时是 option id 的前缀；由持有编辑器的 Composer 生成。 */
  listboxId: string
  commands?: ThreadCommand[]
  /** 过滤后列表的索引（父组件跳过禁用项时，可能指向一个已禁用的行）。 */
  activeIndex: number
  scrollVersion?: number
  onActiveIndexChange: (index: number) => void
  onSelect: (command: ThreadCommand) => void
}) {
  const { t } = useI18n()
  const commandSignature = JSON.stringify(commandSource.map(({ id, disabled }) => [id, Boolean(disabled)]))
  const commands = useMemo(() => filterThreadCommands(query, commandSource), [commandSource, query])
  const listRef = useRef<HTMLUListElement>(null)
  const enabledCount = useMemo(
    () => commands.reduce((count, command) => count + (command.disabled ? 0 : 1), 0),
    [commands],
  )
  const totalCount = commandSource.length
  const activeCommand = commands[activeIndex]

  useEffect(() => {
    if (!open) {
      return
    }
    const active = listRef.current?.querySelector<HTMLElement>('[aria-selected="true"]')
    active?.scrollIntoView({ block: 'nearest' })
  }, [open, query, commandSignature, scrollVersion])

  if (!open) {
    return null
  }

  return (
    <div
      id={listboxId}
      className="thread-command-palette"
      role="listbox"
      aria-label={t('ai.runtime.command.palette')}
      aria-activedescendant={
        activeCommand ? threadCommandOptionId(listboxId, activeCommand.id) : undefined
      }
    >
      <ul ref={listRef} className="thread-command-list" role="presentation">
        {commands.length === 0 ? (
          <li className="thread-command-empty" role="presentation">{t('ai.runtime.command.noMatch')}</li>
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
            <li key={command.id} role="presentation">
              <button
                type="button"
                id={threadCommandOptionId(listboxId, command.id)}
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
