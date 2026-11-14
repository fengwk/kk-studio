import type { ModelPricingConditionDTO } from '@/shared/api/generated/contracts'
import { Select } from '@/shared/ui/controls/Select'
import { Button } from '@/shared/ui/controls/Button'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import { initialConditionValue } from './ai-pricing-condition-values'
import {
  ConditionBlock,
  ContextConditionFields,
  DateConditionFields,
  RequestBodyConditionFields,
  ServiceTierConditionField,
  TimeConditionFields,
} from './ai-pricing-condition-fields'
import './catalog-form-layout.css'
import './ai-pricing-condition-editor.css'

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

const CONDITION_KEYS = Object.keys(CONDITION_LABELS) as ConditionKey[]

/**
 * 结构化条件编辑器：每个维度原位展开；空条件合法，all/any/not 递归编辑但深度受限。
 */
export function ConditionEditor({
  condition,
  onChange,
  depth = 0,
}: {
  condition: ModelPricingConditionDTO
  onChange: (condition: ModelPricingConditionDTO) => void
  depth?: number
}) {
  const { t } = useI18n()
  const available = CONDITION_KEYS.filter((key) => condition[key] === undefined)

  function remove(key: ConditionKey) {
    const next = { ...condition }
    delete next[key]
    onChange(next)
  }

  function add(key: string) {
    if (!key) return
    onChange({ ...condition, [key]: initialConditionValue(key as ConditionKey) })
  }

  return (
    <fieldset className="form-group condition-editor">
      <legend>{t('ai.catalog.form.condition')}</legend>
      <div className="condition-dimensions">
        {condition.context ? (
          <ConditionBlock label={t(CONDITION_LABELS.context)} onRemove={() => remove('context')}>
            <ContextConditionFields
              context={condition.context}
              onChange={(context) => onChange({ ...condition, context })}
            />
          </ConditionBlock>
        ) : null}

        {condition.serviceTier ? (
          <ConditionBlock
            label={t(CONDITION_LABELS.serviceTier)}
            onRemove={() => remove('serviceTier')}
          >
            <ServiceTierConditionField
              serviceTier={condition.serviceTier}
              onChange={(serviceTier) => onChange({ ...condition, serviceTier })}
            />
          </ConditionBlock>
        ) : null}

        {condition.date ? (
          <ConditionBlock label={t(CONDITION_LABELS.date)} onRemove={() => remove('date')}>
            <DateConditionFields
              date={condition.date}
              onChange={(date) => onChange({ ...condition, date })}
            />
          </ConditionBlock>
        ) : null}

        {condition.time ? (
          <ConditionBlock label={t(CONDITION_LABELS.time)} onRemove={() => remove('time')}>
            <TimeConditionFields
              time={condition.time}
              onChange={(time) => onChange({ ...condition, time })}
            />
          </ConditionBlock>
        ) : null}

        {condition.requestBody ? (
          <ConditionBlock
            label={t(CONDITION_LABELS.requestBody)}
            onRemove={() => remove('requestBody')}
          >
            <RequestBodyConditionFields
              requestBody={condition.requestBody}
              onChange={(requestBody) => onChange({ ...condition, requestBody })}
            />
          </ConditionBlock>
        ) : null}

        {(['all', 'any'] as const).map((key) =>
          condition[key] ? (
            <ConditionBlock key={key} label={t(CONDITION_LABELS[key])} onRemove={() => remove(key)}>
              <NestedConditions
                keyName={key}
                conditions={condition[key]!}
                onChange={(children) => onChange({ ...condition, [key]: children })}
                depth={depth}
              />
            </ConditionBlock>
          ) : null,
        )}

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

/** all/any 的子条件列表：顺序即求值顺序，追加在末尾。 */
function NestedConditions({
  keyName,
  conditions,
  onChange,
  depth,
}: {
  keyName: 'all' | 'any'
  conditions: ModelPricingConditionDTO[]
  onChange: (conditions: ModelPricingConditionDTO[]) => void
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
              onChange(conditions.map((item, itemIndex) => (itemIndex === index ? next : item)))
            }
          />
        </div>
      ))}
      <Button variant="inline" onClick={() => onChange([...conditions, {}])}>
        {t(`ai.catalog.form.add.${keyName}`)}
      </Button>
    </div>
  )
}
