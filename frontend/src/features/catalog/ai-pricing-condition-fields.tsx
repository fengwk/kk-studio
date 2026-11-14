import type { ReactNode } from 'react'
import type { ModelPricingConditionDTO } from '@/shared/api/generated/contracts'
import { scalarKind, type PricingScalarKind } from '@/features/catalog/pricing-condition-scalar'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { Select } from '@/shared/ui/controls/Select'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { useI18n } from '@/shared/i18n'
import {
  SCALAR_KINDS,
  WEEKDAYS,
  WEEKDAY_LABELS,
  defaultScalar,
  scalarInputScalar,
  scalarInputValue,
} from './ai-pricing-condition-values'

/** 单个条件维度的外壳：标题 + 移除按钮；内部字段由调用方提供。 */
export function ConditionBlock({
  label,
  onRemove,
  children,
}: {
  label: string
  onRemove: () => void
  children: ReactNode
}) {
  const { t } = useI18n()
  return (
    <div className="condition-block">
      <div className="condition-block-head">
        <strong>{label}</strong>
        <Button
          variant="ghost"
          size="compact"
          danger
          aria-label={t('ai.catalog.form.removeCondition', { label })}
          onClick={onRemove}
        >
          ×
        </Button>
      </div>
      {children}
    </div>
  )
}

export function ContextConditionFields({
  context,
  onChange,
}: {
  context: NonNullable<ModelPricingConditionDTO['context']>
  onChange: (context: NonNullable<ModelPricingConditionDTO['context']>) => void
}) {
  const { t } = useI18n()
  return (
    <div className="form-grid-2">
      {(['gt', 'gte', 'lt', 'lte'] as const).map((field) => (
        <label className="form-group" key={field}>
          <FieldLabel>{t(`ai.catalog.form.context.${field}`)}</FieldLabel>
          <NumberInput
            value={context[field] == null ? '' : String(context[field])}
            min={0}
            onChange={(value) =>
              onChange({ ...context, [field]: value === '' ? undefined : Number(value) })
            }
            aria-label={t(`ai.catalog.form.context.${field}`)}
          />
        </label>
      ))}
    </div>
  )
}

/** 显式空列表表示恒假；逗号分隔文本往返，单独的一项不会折叠为缺省。 */
export function ServiceTierConditionField({
  serviceTier,
  onChange,
}: {
  serviceTier: string[]
  onChange: (serviceTier: string[]) => void
}) {
  const { t } = useI18n()
  return (
    <TextInput
      value={serviceTier.join(', ')}
      placeholder="standard, priority"
      aria-label={t('ai.catalog.form.conditionServiceTier')}
      onChange={(event) =>
        onChange(event.target.value.split(',').map((value) => value.trim()).filter(Boolean))
      }
    />
  )
}

export function DateConditionFields({
  date,
  onChange,
}: {
  date: NonNullable<ModelPricingConditionDTO['date']>
  onChange: (date: NonNullable<ModelPricingConditionDTO['date']>) => void
}) {
  const { t } = useI18n()
  return (
    <div className="form-grid-2">
      <label className="form-group">
        <FieldLabel>{t('ai.catalog.form.dateFrom')}</FieldLabel>
        <TextInput
          type="date"
          value={date.gte ?? ''}
          aria-label={t('ai.catalog.form.dateFrom')}
          onChange={(event) => onChange({ ...date, gte: event.target.value || undefined })}
        />
      </label>
      <label className="form-group">
        <FieldLabel>{t('ai.catalog.form.dateBefore')}</FieldLabel>
        <TextInput
          type="date"
          value={date.lt ?? ''}
          aria-label={t('ai.catalog.form.dateBefore')}
          onChange={(event) => onChange({ ...date, lt: event.target.value || undefined })}
        />
      </label>
    </div>
  )
}

export function TimeConditionFields({
  time,
  onChange,
}: {
  time: NonNullable<ModelPricingConditionDTO['time']>
  onChange: (time: NonNullable<ModelPricingConditionDTO['time']>) => void
}) {
  const { t } = useI18n()
  return (
    <>
      <div className="form-grid-2">
        <label className="form-group">
          <FieldLabel required>{t('ai.catalog.form.timeStart')}</FieldLabel>
          <TextInput
            value={time.start}
            placeholder="HH:mm"
            spellCheck={false}
            aria-label={t('ai.catalog.form.timeStart')}
            onChange={(event) => onChange({ ...time, start: event.target.value })}
          />
        </label>
        <label className="form-group">
          <FieldLabel required>{t('ai.catalog.form.timeEnd')}</FieldLabel>
          <TextInput
            value={time.end}
            placeholder="HH:mm"
            spellCheck={false}
            aria-label={t('ai.catalog.form.timeEnd')}
            onChange={(event) => onChange({ ...time, end: event.target.value })}
          />
        </label>
        <label className="form-group">
          <FieldLabel required>{t('ai.catalog.form.timeZone')}</FieldLabel>
          <TextInput
            value={time.timezone}
            placeholder="Asia/Shanghai"
            aria-label={t('ai.catalog.form.timeZone')}
            onChange={(event) => onChange({ ...time, timezone: event.target.value })}
          />
        </label>
      </div>
      <span className="inline-hint">{t('ai.catalog.form.timeHint')}</span>
      <div className="weekday-row">
        {WEEKDAYS.map((day) => (
          <Checkbox
            key={day}
            checked={time.weekdays?.includes(day) ?? false}
            onChange={(checked) => {
              const current = time.weekdays ?? []
              const next = checked
                ? [...current, day].sort((left, right) => left - right)
                : current.filter((value) => value !== day)
              onChange({ ...time, weekdays: next.length > 0 ? next : undefined })
            }}
            label={t(WEEKDAY_LABELS[day])}
          />
        ))}
      </div>
    </>
  )
}

/** 请求体条件：路径 + 类型化标量；类型切换时换成该类型的最小合法值。 */
export function RequestBodyConditionFields({
  requestBody,
  onChange,
}: {
  requestBody: NonNullable<ModelPricingConditionDTO['requestBody']>
  onChange: (requestBody: NonNullable<ModelPricingConditionDTO['requestBody']>) => void
}) {
  const { t } = useI18n()
  const kind = scalarKind(requestBody.equals)
  return (
    <>
      <label className="form-group">
        <FieldLabel required>{t('ai.catalog.form.requestBodyPath')}</FieldLabel>
        <TextInput
          value={requestBody.path ?? ''}
          placeholder="/speed"
          spellCheck={false}
          aria-label={t('ai.catalog.form.requestBodyPath')}
          onChange={(event) => onChange({ ...requestBody, path: event.target.value })}
        />
      </label>
      <label className="form-group">
        <FieldLabel required>{t('ai.catalog.form.requestBodyType')}</FieldLabel>
        <Select
          value={kind ?? 'string'}
          options={SCALAR_KINDS.map((option) => ({
            value: option,
            label: t(`ai.catalog.form.requestBodyType.${option}`),
          }))}
          aria-label={t('ai.catalog.form.requestBodyType')}
          onChange={(next) =>
            onChange({ ...requestBody, equals: defaultScalar(next as PricingScalarKind) })
          }
        />
      </label>
      {kind === 'boolean' ? (
        <label className="form-group">
          <FieldLabel required>{t('ai.catalog.form.requestBodyValue')}</FieldLabel>
          <Select
            value={String(requestBody.equals)}
            options={[
              { value: 'true', label: 'true' },
              { value: 'false', label: 'false' },
            ]}
            aria-label={t('ai.catalog.form.requestBodyValue')}
            onChange={(value) => onChange({ ...requestBody, equals: value === 'true' })}
          />
        </label>
      ) : kind !== 'null' ? (
        <label className="form-group">
          <FieldLabel required>{t('ai.catalog.form.requestBodyValue')}</FieldLabel>
          <TextInput
            value={scalarInputValue(requestBody.equals)}
            inputMode={kind === 'number' ? 'decimal' : undefined}
            spellCheck={false}
            aria-label={t('ai.catalog.form.requestBodyValue')}
            onChange={(event) =>
              onChange({
                ...requestBody,
                equals: scalarInputScalar(kind ?? 'string', event.target.value),
              })
            }
          />
        </label>
      ) : null}
      <span className="inline-hint">{t('ai.catalog.form.requestBodyHint')}</span>
      <span className="inline-hint">{t('ai.catalog.form.requestBodySourceHint')}</span>
    </>
  )
}
