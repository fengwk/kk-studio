import { useId, useState } from 'react'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldError } from '@/shared/ui/controls/FieldError'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { useFieldErrorId } from '@/shared/ui/controls/field-error'
import type { CapabilityOption } from '@/features/catalog/agent-capability-candidates'
import { useI18n } from '@/shared/i18n'
import './capability-picker.css'

export interface CapabilityDirectory {
  status: 'loading' | 'error' | 'ready'
  retry: () => void
}

export function CapabilitySelectionPanel({
  label, searchLabel, options, selected, emptyText, directory, error, onToggle,
}: {
  label: string
  searchLabel: string
  options: CapabilityOption[]
  selected: string[]
  emptyText: string
  directory?: CapabilityDirectory
  error?: string
  onToggle: (value: string) => void
}) {
  const { t } = useI18n()
  const searchId = useId()
  const errorId = useFieldErrorId(error)
  const [search, setSearch] = useState('')
  const [selectedOnly, setSelectedOnly] = useState(false)
  const checkedValues = new Set(selected)
  const status = directory?.status ?? 'ready'
  const empty = !options.some((option) => !option.missing)
  const term = search.trim().toLocaleLowerCase()
  const groups = new Map<string, CapabilityOption[]>()
  for (const option of options) {
    if (selectedOnly && !checkedValues.has(option.value)) continue
    if (term && !`${option.group ?? ''} ${option.name} ${option.description ?? ''}`.toLocaleLowerCase().includes(term)) continue
    const group = option.group ?? ''
    const items = groups.get(group) ?? []
    items.push(option)
    groups.set(group, items)
  }

  return (
    <fieldset
      className={`form-group capability-picker${error ? ' is-error' : ''}`}
      aria-describedby={errorId}
    >
      <legend>{label} · {t('ai.catalog.form.selectedCount', { count: checkedValues.size })}</legend>
        <div className="capability-selection-panel">
          {error ? <FieldError id={errorId}>{error}</FieldError> : null}
          <label className="capability-search-label" htmlFor={searchId}>{searchLabel}</label>
          <TextInput
            id={searchId}
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            onKeyDown={(event) => { if (event.key === 'Enter') event.preventDefault() }}
          />
          <div className="capability-filter">
            <Button size="compact" variant="secondary" aria-pressed={!selectedOnly} onClick={() => setSelectedOnly(false)}>
              {t('ai.catalog.form.allCapabilities')}
            </Button>
            <Button size="compact" variant="secondary" aria-pressed={selectedOnly} onClick={() => setSelectedOnly(true)}>
              {t('ai.catalog.form.selectedCapabilities')}
            </Button>
          </div>
          {status === 'loading' ? <div className="inline-hint" role="status">{t('ai.common.loadingResources')}</div> : null}
          {status === 'error' ? (
            <div className="inline-hint" role="alert">
              {t('ai.common.resourceLoadFailed')}{' '}
              <Button variant="secondary" onClick={directory?.retry}>{t('shared.retry')}</Button>
            </div>
          ) : null}
          {status === 'ready' && empty ? <div className="inline-hint" role="status">{emptyText}</div> : null}
          {groups.size === 0 && status === 'ready' && (!empty || options.length > 0) ? (
            <div className="inline-hint" role="status">{t('ai.catalog.form.noMatchingCapabilities')}</div>
          ) : null}
          {[...groups].map(([group, items]) => (
            <section className="capability-group" key={group} aria-label={group || undefined}>
              {group ? <h4 className="capability-group-heading">{group}</h4> : null}
              {items.map((option) => {
                const checked = checkedValues.has(option.value)
                const missing = status === 'ready' && option.missing
                const stateClass = missing ? ' is-offline is-missing' : option.offline && !option.missing ? ' is-offline' : ''
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
                        {missing ? <span className="capability-option-status">{t('ai.catalog.form.unavailable')}</span> : null}
                        {option.offline && !option.missing ? <span className="capability-option-status">{t('ai.catalog.form.offline')}</span> : null}
                      </span>
                      {option.description ? <span className="capability-option-description">{option.description}</span> : null}
                    </span>
                  </Checkbox>
                )
              })}
            </section>
          ))}
        </div>
    </fieldset>
  )
}
