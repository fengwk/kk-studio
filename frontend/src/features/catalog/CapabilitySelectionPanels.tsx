import { useMemo, useState } from 'react'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import type { CapabilityOption } from '@/features/catalog/agent-capability-candidates'
import { translate, useI18n } from '@/shared/i18n'
import './capability-picker.css'

export function ToolSelectionPanel({
  options,
  selected,
  emptyText,
  onToggle,
}: {
  options: CapabilityOption[]
  selected: string[]
  emptyText: string
  onToggle: (value: string) => void
}) {
  return <CapabilitySelectionPanel type="tool" options={options} selected={selected} emptyText={emptyText} onToggle={onToggle} />
}

export function SkillSelectionPanel({
  options,
  selected,
  emptyText,
  statusText,
  errorText,
  onToggle,
}: {
  options: CapabilityOption[]
  selected: string[]
  emptyText: string
  statusText?: string | null
  errorText?: string | null
  onToggle: (value: string) => void
}) {
  return <CapabilitySelectionPanel
    type="skill"
    options={options}
    selected={selected}
    emptyText={emptyText}
    statusText={statusText}
    errorText={errorText}
    onToggle={onToggle}
  />
}

function CapabilitySelectionPanel({
  type,
  options,
  selected,
  emptyText,
  statusText,
  errorText,
  onToggle,
}: {
  type: 'tool' | 'skill'
  options: CapabilityOption[]
  selected: string[]
  emptyText: string
  statusText?: string | null
  errorText?: string | null
  onToggle: (value: string) => void
}) {
  const { t } = useI18n()
  const [search, setSearch] = useState('')
  const filtered = useMemo(() => {
    const term = search.trim().toLocaleLowerCase()
    return options.filter((option) =>
      !term || `${option.name} ${option.description ?? ''}`.toLocaleLowerCase().includes(term),
    )
  }, [options, search])
  const groups = new Map<string, CapabilityOption[]>()
  for (const option of filtered) {
    const group = option.group ?? (option.missing ? t('ai.catalog.form.unavailable') : '')
    const items = groups.get(group) ?? []
    items.push(option)
    groups.set(group, items)
  }
  const searchId = `capability-search-${type}`

  return (
    <div className="capability-selection-panel">
      <label className="capability-search-label" htmlFor={searchId}>{t('ai.catalog.form.searchCapabilities')}</label>
      <input
        id={searchId}
        className="capability-search"
        type="search"
        value={search}
        onChange={(event) => setSearch(event.target.value)}
      />
      {statusText ? <div className="inline-hint" role="status">{statusText}</div> : null}
      {errorText ? <div className="inline-hint" role="alert">{errorText}</div> : null}
      {groups.size === 0 ? (
        <div className="capability-options"><div className="inline-hint">{emptyText}</div></div>
      ) : (
        <div className="capability-options">
          {[...groups].map(([group, items]) => (
            <section className="capability-group" key={group || 'all'} aria-label={group || undefined}>
              {group ? <h4 className="capability-group-heading">{group}</h4> : null}
              {items.map((option) => {
                const checked = selected.includes(option.value)
                const stateClass = option.missing ? ' is-offline is-missing' : option.offline ? ' is-offline' : ''
                return (
                  <Checkbox
                    key={option.value}
                    className={`capability-option capability-option-detailed${checked ? ' is-selected' : ''}${stateClass}`}
                    checked={checked}
                    onChange={() => onToggle(option.value)}
                  >
                    <span className="capability-option-body">
                      <span className="capability-option-heading">
                        <code className="capability-name">{option.name}</code>
                        {option.missing ? <span className="capability-option-status">{translate('ai.catalog.form.unavailable')}</span> : null}
                      </span>
                      {option.description ? <span className="capability-option-description" title={option.description}>{option.description}</span> : null}
                    </span>
                  </Checkbox>
                )
              })}
            </section>
          ))}
        </div>
      )}
    </div>
  )
}
