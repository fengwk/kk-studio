import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { translate } from '@/shared/i18n'
import type { CapabilityOption } from '@/features/catalog/agent-capability-candidates'

/**
 * 能力多选清单：加载中/失败/无候选三种空态优先呈现状态，
 * 有候选时状态行置顶；离线与已缺失的候选标出原因而不是隐藏。
 */
export function CapabilityChecklist({
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
  if (options.length === 0) {
    if (statusText) {
      return (
        <div className="capability-options" role="status">
          <div className="inline-hint">{statusText}</div>
        </div>
      )
    }
    if (errorText) {
      return (
        <div className="capability-options" role="alert">
          <div className="inline-hint">{errorText}</div>
        </div>
      )
    }
    return (
      <div className="capability-options">
        <div className="inline-hint">{emptyText}</div>
      </div>
    )
  }

  return (
    <div className="capability-options">
      {statusText ? (
        <div className="inline-hint" role="status">
          {statusText}
        </div>
      ) : null}
      {errorText ? (
        <div className="inline-hint" role="alert">
          {errorText}
        </div>
      ) : null}
      {options.map((option) => {
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
                {option.missing ? (
                  <span className="capability-option-status">
                    {translate('ai.catalog.form.unavailable')}
                  </span>
                ) : null}
                {!option.missing && option.offline ? (
                  <span className="capability-option-status">
                    {translate('ai.catalog.form.offline')}
                  </span>
                ) : null}
              </span>
              {option.description ? (
                <span className="capability-option-description" title={option.description}>
                  {option.description}
                </span>
              ) : null}
            </span>
          </Checkbox>
        )
      })}
    </div>
  )
}
