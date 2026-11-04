import type { ReactNode } from 'react'
import { sanitizeDecimalInput, sanitizeIntegerInput } from '@/shared/lib/numeric-input'
import { Button } from '@/shared/ui/controls/Button'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { useI18n } from '@/shared/i18n'

const RATE_FIELDS = [
  { field: 'input', labelKey: 'ai.catalog.form.inputPrice' },
  { field: 'output', labelKey: 'ai.catalog.form.outputPrice' },
  { field: 'cacheRead', labelKey: 'ai.catalog.form.cacheReadPrice' },
  { field: 'cacheWrite', labelKey: 'ai.catalog.form.cacheWritePrice' },
  { field: 'reasoning', labelKey: 'ai.catalog.form.reasoningPrice' },
] as const

type RateField = (typeof RATE_FIELDS)[number]['field']
type RateValues = Partial<Record<RateField, string | null | undefined>>

/**
 * 覆盖价格与倍率因子共用同一套字段渲染；差异只在标题、占位符、无障碍名称与
 * 调用方附加的区块（覆盖价格可再带 TTL）。
 */
export function RateFieldsEditor({
  legend,
  value,
  placeholder,
  fieldAriaLabel,
  onFieldChange,
  children,
}: {
  legend: string
  value: RateValues
  placeholder: string
  fieldAriaLabel: (label: string) => string
  onFieldChange: (field: RateField, value: string | undefined) => void
  children?: ReactNode
}) {
  const { t } = useI18n()
  return (
    <fieldset className="form-group">
      <legend>{legend}</legend>
      <div className="form-grid-2">
        {RATE_FIELDS.map(({ field, labelKey }) => (
          <label className="form-group" key={field}>
            <FieldLabel>{t(labelKey)}</FieldLabel>
            <TextInput
              inputMode="decimal"
              value={value[field] ?? ''}
              placeholder={placeholder}
              onChange={(event) => {
                const next = sanitizeDecimalInput(event.target.value)
                onFieldChange(field, next === '' ? undefined : next)
              }}
              aria-label={fieldAriaLabel(t(labelKey))}
            />
          </label>
        ))}
      </div>
      {children}
    </fieldset>
  )
}

export function TtlRateEditor({
  value,
  onChange,
}: {
  value: Record<string, string>
  onChange: (value: Record<string, string>) => void
}) {
  const { t } = useI18n()
  const entries = Object.entries(value)
  const nextSeconds = entries.length === 0
    ? '300'
    : String(Math.max(...entries.map(([seconds]) => Number(seconds) || 0)) + 300)

  return (
    <div className="editor-stack">
      {entries.map(([seconds, rate]) => (
        <div className="editor-row" key={seconds}>
          <TextInput
            value={seconds}
            inputMode="numeric"
            aria-label={t('ai.catalog.form.ttlSeconds')}
            onChange={(event) => {
              const nextKey = sanitizeIntegerInput(event.target.value)
              if (!nextKey || nextKey === seconds) {
                return
              }
              const next: Record<string, string> = {}
              for (const [key, rateValue] of entries) {
                next[key === seconds ? nextKey : key] = rateValue
              }
              onChange(next)
            }}
          />
          <TextInput
            value={rate}
            inputMode="decimal"
            aria-label={t('ai.catalog.form.ttlRate')}
            onChange={(event) =>
              onChange({ ...value, [seconds]: sanitizeDecimalInput(event.target.value) })}
          />
          <Button
            variant="ghost"
            size="compact"
            danger
            aria-label={t('ai.catalog.form.removeTtl')}
            onClick={() => {
              const next = { ...value }
              delete next[seconds]
              onChange(next)
            }}
          >
            ×
          </Button>
        </div>
      ))}
      <Button
        variant="inline"
        onClick={() => onChange({ ...value, [nextSeconds]: '0' })}
      >
        {t('ai.catalog.form.addTtl')}
      </Button>
    </div>
  )
}
