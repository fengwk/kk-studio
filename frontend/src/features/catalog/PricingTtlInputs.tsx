import { useI18n, type TranslationKey } from '@/shared/i18n'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { sanitizeDecimalInput, sanitizeIntegerInput } from '@/shared/lib/numeric-input'
import {
  createPricingDraftKey,
  findPricingTtlRowIssues,
  type PricingTtlRow,
  type PricingTtlRowIssue,
} from '@/features/catalog/model-pricing-draft'
import './ai-pricing.css'

const ISSUE_KEYS: Record<PricingTtlRowIssue, TranslationKey> = {
  invalidSeconds: 'ai.catalog.form.pricingTtlInvalid',
  duplicateSeconds: 'ai.catalog.form.pricingTtlDuplicate',
  rateRequired: 'ai.catalog.form.pricingRateRequired',
}

/**
 * TTL 缓存写入价行编辑器。行以 draftKey 稳定受控，新增行从空白开始，
 * 使空白价、非法秒数与重复秒数都能就地提示，而不是被静默合并或丢弃。
 */
export function PricingTtlInputs({ rows, onChange }: {
  rows: PricingTtlRow[]
  onChange: (rows: PricingTtlRow[]) => void
}) {
  const { t } = useI18n()
  const issues = findPricingTtlRowIssues(rows)
  const updateRow = (draftKey: string, patch: Partial<PricingTtlRow>) => {
    onChange(rows.map((row) => (row.draftKey === draftKey ? { ...row, ...patch } : row)))
  }
  return (
    <>
      {rows.map((row) => {
        const issue = issues[row.draftKey]
        return (
          <div className="pricing-plan-row" key={row.draftKey}>
            <div className="form-grid-2">
              <label className="form-group">
                <FieldLabel required>{t('ai.catalog.form.pricingTtlSeconds')}</FieldLabel>
                <TextInput
                  inputMode="numeric"
                  aria-label={t('ai.catalog.form.pricingTtlSeconds')}
                  value={row.seconds}
                  invalid={issue === 'invalidSeconds' || issue === 'duplicateSeconds'}
                  onChange={(event) => updateRow(row.draftKey, { seconds: sanitizeIntegerInput(event.target.value) })} />
              </label>
              <label className="form-group">
                <FieldLabel required>{t('ai.catalog.form.cacheWritePrice')}</FieldLabel>
                <TextInput
                  inputMode="decimal"
                  aria-label={t('ai.catalog.form.cacheWritePrice')}
                  value={row.rate}
                  placeholder={t('ai.catalog.form.pricingUnpriced')}
                  invalid={issue === 'rateRequired'}
                  onChange={(event) => updateRow(row.draftKey, { rate: sanitizeDecimalInput(event.target.value) })} />
              </label>
            </div>
            <button
              type="button"
              className="ghost-inline-btn"
              aria-label={t('ai.catalog.form.removePricingRule')}
              onClick={() => onChange(rows.filter((candidate) => candidate.draftKey !== row.draftKey))}>×</button>
            {issue ? <span className="field-error" role="alert">{t(ISSUE_KEYS[issue])}</span> : null}
          </div>
        )
      })}
      <button
        type="button"
        className="ghost-inline-btn"
        onClick={() => onChange([...rows, { draftKey: createPricingDraftKey(), seconds: '', rate: '' }])}>
        {t('ai.catalog.form.addPricingTtl')}
      </button>
    </>
  )
}
