import { useState, type ReactNode } from 'react'
import type { ModelPricingConditionDTO } from '@/shared/api/generated/contracts'
import { describeCondition } from '@/features/catalog/ai-model-pricing-codec'
import { Button } from '@/shared/ui/controls/Button'
import { useI18n } from '@/shared/i18n'
import './catalog-form-layout.css'
import './ai-pricing.css'

export interface PricingRuleItem {
  id: string
  when: ModelPricingConditionDTO
}

let ruleSeed = 0
function nextRuleId(prefix: 'override' | 'multiplier'): string {
  ruleSeed += 1
  return `${prefix}-${ruleSeed}`
}

function move<T>(items: T[], index: number, delta: -1 | 1): T[] {
  const target = index + delta
  if (target < 0 || target >= items.length) {
    return items
  }
  const next = [...items]
  const [item] = next.splice(index, 1)
  next.splice(target, 0, item!)
  return next
}

/** 折叠规则列表：覆盖价格与倍率共用排序、展开、删除交互，正文由调用方渲染。 */
export function PricingRuleList<T extends PricingRuleItem>({
  idPrefix,
  title,
  addLabel,
  rules,
  expandedRuleId,
  onExpandedRuleIdChange,
  onChange,
  createRule,
  renderRule,
}: {
  idPrefix: 'override' | 'multiplier'
  title: string
  addLabel: string
  rules: T[]
  expandedRuleId: string | null
  onExpandedRuleIdChange: (id: string | null) => void
  onChange: (rules: T[]) => void
  createRule: (id: string) => T
  renderRule: (rule: T, update: (patch: Partial<T>) => void) => ReactNode
}) {
  const { t } = useI18n()
  const [show, setShow] = useState(rules.length > 0)

  return (
    <div className="pricing-fold">
      <div className="structured-section-head">
        <button
          type="button"
          className="ghost-inline-btn"
          aria-expanded={show}
          onClick={() => setShow((current) => !current)}
        >
          {title}
        </button>
        <Button
          variant="inline"
          onClick={() => {
            const id = nextRuleId(idPrefix)
            onChange([...rules, createRule(id)])
            onExpandedRuleIdChange(id)
            setShow(true)
          }}
        >
          {addLabel}
        </Button>
      </div>
      {show ? (
        <div className="pricing-rule-list">
          {rules.length === 0 ? (
            <span className="inline-hint">{t('ai.catalog.form.noPriceRules')}</span>
          ) : null}
          {rules.map((rule, index) => (
            <PricingRule
              key={rule.id}
              id={rule.id}
              summary={describeCondition(rule.when)}
              expanded={expandedRuleId === rule.id}
              onToggle={() => onExpandedRuleIdChange(expandedRuleId === rule.id ? null : rule.id)}
              onMoveUp={index > 0 ? () => onChange(move(rules, index, -1)) : undefined}
              onMoveDown={index < rules.length - 1 ? () => onChange(move(rules, index, 1)) : undefined}
              onDelete={() => {
                onChange(rules.filter((item) => item.id !== rule.id))
                if (expandedRuleId === rule.id) {
                  onExpandedRuleIdChange(null)
                }
              }}
            >
              {renderRule(rule, (patch) =>
                onChange(rules.map((item) => item.id === rule.id ? { ...item, ...patch } : item)))}
            </PricingRule>
          ))}
        </div>
      ) : null}
    </div>
  )
}

function PricingRule({
  id,
  summary,
  expanded,
  onToggle,
  onMoveUp,
  onMoveDown,
  onDelete,
  children,
}: {
  id: string
  summary: string
  expanded: boolean
  onToggle: () => void
  onMoveUp?: () => void
  onMoveDown?: () => void
  onDelete: () => void
  children: ReactNode
}) {
  const { t } = useI18n()
  return (
    <div className="pricing-rule">
      <div className="pricing-rule-head">
        <button
          type="button"
          className="pricing-rule-summary"
          aria-expanded={expanded}
          onClick={onToggle}
        >
          <code>{id}</code>
          <span>{summary}</span>
        </button>
        <div className="pricing-rule-actions">
          <Button variant="ghost" size="compact" disabled={!onMoveUp}
            aria-label={t('ai.catalog.form.moveRuleUp')} onClick={onMoveUp}>↑</Button>
          <Button variant="ghost" size="compact" disabled={!onMoveDown}
            aria-label={t('ai.catalog.form.moveRuleDown')} onClick={onMoveDown}>↓</Button>
          <Button variant="ghost" size="compact" danger
            aria-label={t('ai.catalog.form.removeRule')} onClick={onDelete}>×</Button>
        </div>
      </div>
      {expanded ? <div className="pricing-rule-body">{children}</div> : null}
    </div>
  )
}
