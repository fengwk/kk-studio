import { ChevronDown, ChevronUp } from 'lucide-react'
import { useId } from 'react'
import { sanitizeIntegerInput } from '@/shared/lib/numeric-input'
import { useI18n } from '@/shared/i18n'

/**
 * 整数输入：文本框 + 自定义步进，不使用浏览器原生 number spinner。
 * 值以字符串进出，允许空串作为编辑中间态。
 */
export function NumberInput({
  id,
  value,
  onChange,
  min,
  max,
  step = 1,
  disabled = false,
  className,
  'aria-label': ariaLabel,
  'aria-describedby': ariaDescribedBy,
}: {
  id?: string
  value: string
  onChange: (next: string) => void
  min?: number
  max?: number
  step?: number
  disabled?: boolean
  className?: string
  'aria-label'?: string
  'aria-describedby'?: string
}) {
  const { t } = useI18n()
  const generatedId = useId()
  const fieldId = id ?? generatedId

  const nudge = (direction: 1 | -1) => {
    const parsed = value.trim() === '' ? Number.NaN : Number(value)
    const base = Number.isFinite(parsed) ? parsed : direction > 0 ? (min ?? 0) - step : (max ?? 0) + step
    let next = base + direction * step
    if (min != null && next < min) {
      next = min
    }
    if (max != null && next > max) {
      next = max
    }
    onChange(String(next))
  }

  return (
    <div className={`number-input${disabled ? ' is-disabled' : ''}`}>
      <input
        id={fieldId}
        className={className ?? 'number-input-field'}
        type="text"
        inputMode="numeric"
        autoComplete="off"
        spellCheck={false}
        value={value}
        disabled={disabled}
        aria-label={ariaLabel}
        aria-describedby={ariaDescribedBy}
        onChange={(event) => onChange(sanitizeIntegerInput(event.target.value))}
        onKeyDown={(event) => {
          if (event.key === 'ArrowUp') {
            event.preventDefault()
            nudge(1)
          } else if (event.key === 'ArrowDown') {
            event.preventDefault()
            nudge(-1)
          }
        }}
      />
      <div className="number-input-steppers">
        <button
          type="button"
          className="number-input-step"
          tabIndex={-1}
          disabled={disabled}
          aria-label={t('shared.numberInput.increment')}
          onClick={() => nudge(1)}
        >
          <ChevronUp aria-hidden="true" />
        </button>
        <button
          type="button"
          className="number-input-step"
          tabIndex={-1}
          disabled={disabled}
          aria-label={t('shared.numberInput.decrement')}
          onClick={() => nudge(-1)}
        >
          <ChevronDown aria-hidden="true" />
        </button>
      </div>
    </div>
  )
}
