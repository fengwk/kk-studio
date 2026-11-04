import type { ReactNode } from 'react'
import type { AgentModelPricingConditionDTO } from '@/shared/api/generated/contracts'
import {
  isRawJsonNumber,
  scalarKind,
  type PricingScalarKind,
} from '@/features/catalog/pricing-condition-scalar'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { Select } from '@/shared/ui/controls/Select'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { useI18n, type TranslationKey } from '@/shared/i18n'

type ConditionKey = 'context' | 'serviceTier' | 'date' | 'time' | 'requestBody' | 'all' | 'any' | 'not'

/** 每种条件的显示名：添加菜单与已添加的条件块使用同一组 i18n key。 */
const CONDITION_LABELS: Record<ConditionKey, TranslationKey> = {
  context: 'ai.catalog.form.conditionContext',
  serviceTier: 'ai.catalog.form.conditionServiceTier',
  date: 'ai.catalog.form.conditionDate',
  time: 'ai.catalog.form.conditionTime',
  requestBody: 'ai.catalog.form.conditionRequestBody',
  all: 'ai.catalog.form.condition.all',
  any: 'ai.catalog.form.condition.any',
  not: 'ai.catalog.form.condition.not',
}

/** ISO 星期序号是后端 wire 契约，显式表保证每个序号只映射到已定义的译名。 */
const WEEKDAYS = [1, 2, 3, 4, 5, 6, 7] as const
type Weekday = (typeof WEEKDAYS)[number]
const WEEKDAY_LABELS: Record<Weekday, TranslationKey> = {
  1: 'ai.catalog.form.weekday.1',
  2: 'ai.catalog.form.weekday.2',
  3: 'ai.catalog.form.weekday.3',
  4: 'ai.catalog.form.weekday.4',
  5: 'ai.catalog.form.weekday.5',
  6: 'ai.catalog.form.weekday.6',
  7: 'ai.catalog.form.weekday.7',
}

/** 时间条件的最小合法默认值：全天使用 wire 规定的 00:00-24:00。 */
const ALL_DAY_TIME = { start: '00:00', end: '24:00', timezone: 'UTC' } as const

/**
 * 结构化条件编辑器：每个维度原位展开；空条件合法，all/any/not 递归编辑但深度受限。
 */
export function ConditionEditor({
  condition,
  onChange,
  depth = 0,
}: {
  condition: AgentModelPricingConditionDTO
  onChange: (condition: AgentModelPricingConditionDTO) => void
  depth?: number
}) {
  const { t } = useI18n()
  const available = (['context', 'serviceTier', 'date', 'time', 'requestBody', 'all', 'any', 'not'] as ConditionKey[])
    .filter((key) => condition[key] === undefined)

  function remove(key: ConditionKey) {
    const next = { ...condition }
    delete next[key]
    onChange(next)
  }

  function add(key: string) {
    if (!key) return
    if (key === 'all' || key === 'any') {
      onChange({ ...condition, [key]: [{}] })
    } else if (key === 'not') {
      onChange({ ...condition, not: {} })
    } else if (key === 'serviceTier') {
      // 显式空列表表示恒假；不能落成对象或折叠为缺省。
      onChange({ ...condition, serviceTier: [] })
    } else if (key === 'time') {
      onChange({ ...condition, time: { ...ALL_DAY_TIME } })
    } else if (key === 'requestBody') {
      onChange({ ...condition, requestBody: { path: '/', equals: '' } })
    } else {
      onChange({ ...condition, [key]: {} })
    }
  }

  return (
    <fieldset className="form-group condition-editor">
      <legend>{t('ai.catalog.form.condition')}</legend>
      <div className="condition-dimensions">
        {condition.context ? (
          <ConditionBlock label={t(CONDITION_LABELS.context)} onRemove={() => remove('context')}>
            <div className="form-grid-2">
              {(['gt', 'gte', 'lt', 'lte'] as const).map((field) => (
                <label className="form-group" key={field}>
                  <FieldLabel>{t(`ai.catalog.form.context.${field}`)}</FieldLabel>
                  <NumberInput
                    value={condition.context?.[field] == null ? '' : String(condition.context[field])}
                    min={0}
                    onChange={(value) => onChange({
                      ...condition,
                      context: {
                        ...condition.context,
                        [field]: value === '' ? undefined : Number(value),
                      },
                    })}
                    aria-label={t(`ai.catalog.form.context.${field}`)}
                  />
                </label>
              ))}
            </div>
          </ConditionBlock>
        ) : null}

        {condition.serviceTier ? (
          <ConditionBlock label={t(CONDITION_LABELS.serviceTier)} onRemove={() => remove('serviceTier')}>
            <TextInput
              value={condition.serviceTier.join(', ')}
              placeholder="standard, priority"
              aria-label={t('ai.catalog.form.conditionServiceTier')}
              onChange={(event) => onChange({
                ...condition,
                serviceTier: event.target.value.split(',').map((value) => value.trim()).filter(Boolean),
              })}
            />
          </ConditionBlock>
        ) : null}

        {condition.date ? (
          <ConditionBlock label={t(CONDITION_LABELS.date)} onRemove={() => remove('date')}>
            <div className="form-grid-2">
              <label className="form-group">
                <FieldLabel>{t('ai.catalog.form.dateFrom')}</FieldLabel>
                <TextInput
                  type="date"
                  value={condition.date.gte ?? ''}
                  aria-label={t('ai.catalog.form.dateFrom')}
                  onChange={(event) => onChange({
                    ...condition,
                    date: { ...condition.date, gte: event.target.value || undefined },
                  })}
                />
              </label>
              <label className="form-group">
                <FieldLabel>{t('ai.catalog.form.dateBefore')}</FieldLabel>
                <TextInput
                  type="date"
                  value={condition.date.lt ?? ''}
                  aria-label={t('ai.catalog.form.dateBefore')}
                  onChange={(event) => onChange({
                    ...condition,
                    date: { ...condition.date, lt: event.target.value || undefined },
                  })}
                />
              </label>
            </div>
          </ConditionBlock>
        ) : null}

        {condition.time ? (
          <ConditionBlock label={t(CONDITION_LABELS.time)} onRemove={() => remove('time')}>
            <div className="form-grid-2">
              <label className="form-group">
                <FieldLabel required>{t('ai.catalog.form.timeStart')}</FieldLabel>
                <TextInput
                  value={condition.time.start}
                  placeholder="HH:mm"
                  spellCheck={false}
                  aria-label={t('ai.catalog.form.timeStart')}
                  onChange={(event) => onChange({
                    ...condition,
                    time: { ...condition.time!, start: event.target.value },
                  })}
                />
              </label>
              <label className="form-group">
                <FieldLabel required>{t('ai.catalog.form.timeEnd')}</FieldLabel>
                <TextInput
                  value={condition.time.end}
                  placeholder="HH:mm"
                  spellCheck={false}
                  aria-label={t('ai.catalog.form.timeEnd')}
                  onChange={(event) => onChange({
                    ...condition,
                    time: { ...condition.time!, end: event.target.value },
                  })}
                />
              </label>
              <label className="form-group">
                <FieldLabel required>{t('ai.catalog.form.timeZone')}</FieldLabel>
                <TextInput
                  value={condition.time.timezone}
                  placeholder="Asia/Shanghai"
                  aria-label={t('ai.catalog.form.timeZone')}
                  onChange={(event) => onChange({
                    ...condition,
                    time: { ...condition.time!, timezone: event.target.value },
                  })}
                />
              </label>
            </div>
            <span className="inline-hint">{t('ai.catalog.form.timeHint')}</span>
            <div className="weekday-row">
              {WEEKDAYS.map((day) => (
                <Checkbox
                  key={day}
                  checked={condition.time!.weekdays?.includes(day) ?? false}
                  onChange={(checked) => {
                    const current = condition.time!.weekdays ?? []
                    const next = checked
                      ? [...current, day].sort((left, right) => left - right)
                      : current.filter((value) => value !== day)
                    onChange({
                      ...condition,
                      time: {
                        ...condition.time!,
                        weekdays: next.length > 0 ? next : undefined,
                      },
                    })
                  }}
                  label={t(WEEKDAY_LABELS[day])}
                />
              ))}
            </div>
          </ConditionBlock>
        ) : null}

        {condition.requestBody ? (
          <ConditionBlock
            label={t(CONDITION_LABELS.requestBody)}
            onRemove={() => remove('requestBody')}
          >
            <label className="form-group">
              <FieldLabel required>{t('ai.catalog.form.requestBodyPath')}</FieldLabel>
              <TextInput
                value={condition.requestBody.path ?? ''}
                placeholder="/speed"
                spellCheck={false}
                aria-label={t('ai.catalog.form.requestBodyPath')}
                onChange={(event) => onChange({
                  ...condition,
                  requestBody: { ...condition.requestBody, path: event.target.value },
                })}
              />
            </label>
            <label className="form-group">
              <FieldLabel required>{t('ai.catalog.form.requestBodyType')}</FieldLabel>
              <Select
                value={scalarKind(condition.requestBody.equals) ?? 'string'}
                options={(['string', 'number', 'boolean', 'null'] as PricingScalarKind[]).map((kind) => ({
                  value: kind,
                  label: t(`ai.catalog.form.requestBodyType.${kind}`),
                }))}
                aria-label={t('ai.catalog.form.requestBodyType')}
                onChange={(kind) => onChange({
                  ...condition,
                  requestBody: {
                    ...condition.requestBody,
                    equals: defaultScalar(kind as PricingScalarKind),
                  },
                })}
              />
            </label>
            {scalarKind(condition.requestBody.equals) === 'boolean' ? (
              <label className="form-group">
                <FieldLabel required>{t('ai.catalog.form.requestBodyValue')}</FieldLabel>
                <Select
                  value={String(condition.requestBody.equals)}
                  options={[
                    { value: 'true', label: 'true' },
                    { value: 'false', label: 'false' },
                  ]}
                  aria-label={t('ai.catalog.form.requestBodyValue')}
                  onChange={(value) => onChange({
                    ...condition,
                    requestBody: { ...condition.requestBody, equals: value === 'true' },
                  })}
                />
              </label>
            ) : scalarKind(condition.requestBody.equals) !== 'null' ? (
              <label className="form-group">
                <FieldLabel required>{t('ai.catalog.form.requestBodyValue')}</FieldLabel>
                <TextInput
                  value={scalarInputValue(condition.requestBody.equals)}
                  inputMode={scalarKind(condition.requestBody.equals) === 'number' ? 'decimal' : undefined}
                  spellCheck={false}
                  aria-label={t('ai.catalog.form.requestBodyValue')}
                  onChange={(event) => onChange({
                    ...condition,
                    requestBody: {
                      ...condition.requestBody,
                      equals: scalarInputScalar(
                        scalarKind(condition.requestBody!.equals) ?? 'string',
                        event.target.value,
                      ),
                    },
                  })}
                />
              </label>
            ) : null}
            <span className="inline-hint">{t('ai.catalog.form.requestBodyHint')}</span>
            <span className="inline-hint">{t('ai.catalog.form.requestBodySourceHint')}</span>
          </ConditionBlock>
        ) : null}

        {(['all', 'any'] as const).map((key) => condition[key] ? (
          <ConditionBlock
            key={key}
            label={t(CONDITION_LABELS[key])}
            onRemove={() => remove(key)}
          >
            <NestedConditions
              keyName={key}
              conditions={condition[key]!}
              onChange={(children) => onChange({ ...condition, [key]: children })}
              depth={depth}
            />
          </ConditionBlock>
        ) : null)}

        {condition.not ? (
          <ConditionBlock label={t(CONDITION_LABELS.not)} onRemove={() => remove('not')}>
            <ConditionEditor
              condition={condition.not}
              depth={depth + 1}
              onChange={(not) => onChange({ ...condition, not })}
            />
          </ConditionBlock>
        ) : null}
      </div>
      {available.length > 0 ? (
        <div className="condition-add">
          <Select
            value=""
            aria-label={t('ai.catalog.form.addCondition')}
            placeholder={t('ai.catalog.form.addCondition')}
            options={available.map((key) => ({
              value: key,
              label: t(CONDITION_LABELS[key]),
            }))}
            onChange={add}
          />
        </div>
      ) : null}
    </fieldset>
  )
}

/** 切换类型时的最小合法默认值；number 以原文对象保存，提交时才变成 JSON number。 */
function defaultScalar(kind: PricingScalarKind): unknown {
  switch (kind) {
    case 'number':
      return { rawJson: '0' }
    case 'boolean':
      return true
    case 'null':
      return null
    case 'string':
    default:
      return ''
  }
}

function scalarInputValue(value: unknown): string {
  if (isRawJsonNumber(value)) return 'rawJson' in value ? value.rawJson : value.rawJSON
  if (typeof value === 'string') return value
  if (typeof value === 'boolean') return String(value)
  if (typeof value === 'number') return String(value)
  return ''
}

function scalarInputScalar(kind: PricingScalarKind, text: string): unknown {
  if (kind === 'number') return { rawJson: text }
  if (kind === 'boolean') return text === 'true'
  if (kind === 'null') return null
  return text
}

function NestedConditions({
  keyName,
  conditions,
  onChange,
  depth,
}: {
  keyName: 'all' | 'any'
  conditions: AgentModelPricingConditionDTO[]
  onChange: (conditions: AgentModelPricingConditionDTO[]) => void
  depth: number
}) {
  const { t } = useI18n()
  return (
    <div className="pricing-nested">
      {conditions.map((child, index) => (
        <div key={index} className="pricing-nested-item">
          <Button
            variant="ghost"
            size="compact"
            danger
            aria-label={t('ai.catalog.form.removeNestedCondition')}
            onClick={() => onChange(conditions.filter((_, itemIndex) => itemIndex !== index))}
          >
            ×
          </Button>
          <ConditionEditor
            condition={child}
            depth={depth + 1}
            onChange={(next) =>
              onChange(conditions.map((item, itemIndex) => itemIndex === index ? next : item))}
          />
        </div>
      ))}
      <Button variant="inline" onClick={() => onChange([...conditions, {}])}>
        {t(`ai.catalog.form.add.${keyName}`)}
      </Button>
    </div>
  )
}

function ConditionBlock({
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
