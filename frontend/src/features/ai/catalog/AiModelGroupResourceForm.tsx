import { ArrowDown, ArrowUp, Trash2 } from 'lucide-react'
import type { ModelGroupDraft } from '@/features/ai/catalog/ai-console-types'
import type { AgentModelView } from '@/features/ai/catalog/AgentModelView'
import { variantOptionsFromModel } from '@/features/ai/catalog/ai-draft-variant-options'
import { newGroupMemberDraft } from '@/features/ai/catalog/ai-model-group-draft-codec'
import type { ResourceFieldKey } from '@/features/ai/catalog/ai-resource-form-validation'
import { Button } from '@/shared/ui/controls/Button'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { Select } from '@/shared/ui/controls/Select'
import { TagInput } from '@/shared/ui/controls/TagInput'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { useI18n } from '@/shared/i18n'

export function ModelGroupForm({
  draft,
  models,
  fieldErrors = {},
  onChange,
}: {
  draft: ModelGroupDraft
  models: AgentModelView[]
  fieldErrors?: Partial<Record<ResourceFieldKey, string>>
  onChange: (draft: ModelGroupDraft) => void
}) {
  const { t } = useI18n()
  const candidates = models.filter((model) => model.kind === 'MODEL')
  const selectedModels = draft.members
    .map((member) => candidates.find((model) => model.name === member.modelName))
    .filter((model): model is AgentModelView => model != null)
  const summary = summarizeGroup(selectedModels)

  function updateMember(index: number, patch: Partial<ModelGroupDraft['members'][number]>) {
    onChange({
      ...draft,
      members: draft.members.map((member, memberIndex) =>
        memberIndex === index ? { ...member, ...patch } : member),
    })
  }

  function move(index: number, delta: -1 | 1) {
    const target = index + delta
    if (target < 0 || target >= draft.members.length) {
      return
    }
    const next = [...draft.members]
    const [member] = next.splice(index, 1)
    next.splice(target, 0, member!)
    onChange({ ...draft, members: next })
  }

  return (
    <>
      <label className={`form-group${fieldErrors.name ? ' is-error' : ''}`}>
        <FieldLabel required>{t('ai.catalog.form.name')}</FieldLabel>
        <TextInput
          invalid={Boolean(fieldErrors.name)}
          aria-label={t('ai.catalog.form.name')}
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          placeholder="coding"
          required
        />
        <span className="inline-hint">{t('ai.catalog.form.groupNameHint')}</span>
        {fieldErrors.name ? <span className="field-error">{fieldErrors.name}</span> : null}
      </label>

      <label className="form-group">
        <FieldLabel>{t('ai.catalog.form.description')}</FieldLabel>
        <TextInput
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          placeholder={t('ai.catalog.form.groupDescriptionPlaceholder')}
        />
      </label>

      <section className="structured-section" aria-labelledby="model-group-members-heading">
        <div className="structured-section-head">
          <h3 id="model-group-members-heading">{t('ai.catalog.form.groupMembers')}</h3>
          <Button
            variant="inline"
            onClick={() => onChange({ ...draft, members: [...draft.members, newGroupMemberDraft()] })}
          >
            {t('ai.catalog.form.addGroupMember')}
          </Button>
        </div>
        {candidates.length === 0 ? (
          <span className="inline-hint" role="status">{t('ai.catalog.form.noGroupModels')}</span>
        ) : null}
        {draft.members.length === 0 ? (
          <span className="field-error">{t('ai.catalog.form.groupMembersRequired')}</span>
        ) : null}
        <div className="group-member-list">
          {draft.members.map((member, index) => {
            const selected = candidates.find((model) => model.name === member.modelName)
            const variants = variantOptionsFromModel(selected)
            return (
              <div className="group-member-row" key={member.draftId}>
                <span className="group-member-index">{index + 1}</span>
                <Select
                  value={member.modelName}
                  aria-label={t('ai.catalog.form.groupMemberModel', { index: index + 1 })}
                  placeholder={t('ai.catalog.form.groupMemberModelPlaceholder')}
                  options={candidates.map((model) => ({ value: model.name, label: model.name }))}
                  onChange={(modelName) => {
                    const nextModel = candidates.find((model) => model.name === modelName)
                    const defaultVariant = nextModel?.config?.defaultVariant
                      ?? variantOptionsFromModel(nextModel)[0]
                      ?? ''
                    updateMember(index, { modelName, variant: defaultVariant })
                  }}
                />
                <Select
                  value={member.variant}
                  aria-label={t('ai.catalog.form.groupMemberVariant', { index: index + 1 })}
                  disabled={!selected}
                  placeholder={t('ai.catalog.form.groupMemberVariantPlaceholder')}
                  options={variants.map((variant) => ({ value: variant, label: variant }))}
                  onChange={(variant) => updateMember(index, { variant })}
                />
                <div className="group-member-actions">
                  <Button
                    variant="ghost"
                    size="compact"
                    disabled={index === 0}
                    aria-label={t('ai.catalog.form.moveGroupMemberUp')}
                    onClick={() => move(index, -1)}
                  >
                    <ArrowUp aria-hidden="true" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="compact"
                    disabled={index === draft.members.length - 1}
                    aria-label={t('ai.catalog.form.moveGroupMemberDown')}
                    onClick={() => move(index, 1)}
                  >
                    <ArrowDown aria-hidden="true" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="compact"
                    danger
                    aria-label={t('ai.catalog.form.removeGroupMember', { index: index + 1 })}
                    onClick={() => onChange({
                      ...draft,
                      members: draft.members.filter((_, memberIndex) => memberIndex !== index),
                    })}
                  >
                    <Trash2 aria-hidden="true" />
                  </Button>
                </div>
              </div>
            )
          })}
        </div>
        {fieldErrors.group ? <span className="field-error">{fieldErrors.group}</span> : null}
        <GroupSummary summary={summary} />
      </section>

      <label className={`form-group${fieldErrors.fallbackStatusCodes ? ' is-error' : ''}`}>
        <FieldLabel>{t('ai.catalog.form.fallbackStatusCodes')}</FieldLabel>
        <TagInput
          value={draft.fallbackStatusCodes}
          min={400}
          max={599}
          ariaLabel={t('ai.catalog.form.fallbackStatusCodes')}
          placeholder={t('ai.catalog.form.fallbackStatusCodesPlaceholder')}
          onChange={(fallbackStatusCodes) => onChange({ ...draft, fallbackStatusCodes })}
        />
        <span className="inline-hint">{t('ai.catalog.form.fallbackStatusCodesHint')}</span>
        {fieldErrors.fallbackStatusCodes ? (
          <span className="field-error">{fieldErrors.fallbackStatusCodes}</span>
        ) : null}
      </label>

      <label className={`form-group${fieldErrors.cooldownSeconds ? ' is-error' : ''}`}>
        <FieldLabel>{t('ai.catalog.form.cooldownSeconds')}</FieldLabel>
        <NumberInput
          value={draft.cooldownSeconds}
          min={0}
          aria-label={t('ai.catalog.form.cooldownSeconds')}
          onChange={(cooldownSeconds) => onChange({ ...draft, cooldownSeconds })}
        />
        <span className="inline-hint">{t('ai.catalog.form.cooldownHint')}</span>
        {fieldErrors.cooldownSeconds ? (
          <span className="field-error">{fieldErrors.cooldownSeconds}</span>
        ) : null}
      </label>
    </>
  )
}

interface GroupSummaryData {
  context?: number
  output?: number
  tools?: boolean
  reasoning?: boolean
  modalities?: string[]
}

/** 能力取所有已配置成员交集；上下限取最小值；缺配置不伪造 false。 */
function summarizeGroup(models: AgentModelView[]): GroupSummaryData {
  const configs = models
    .map((model) => model.config)
    .filter((config): config is NonNullable<typeof config> => config != null)
  if (configs.length === 0) {
    return {}
  }
  const contexts = configs.map((config) => config.limit.context)
  const outputs = configs.map((config) => config.limit.output)
  const modalities = configs
    .map((config) => new Set(config.abilities.inputModalities))
    .reduce((current, next) => new Set([...current].filter((item) => next.has(item))))
  return {
    context: Math.min(...contexts),
    output: Math.min(...outputs),
    tools: configs.every((config) => config.abilities.tools),
    reasoning: configs.every((config) => config.abilities.reasoning),
    modalities: [...modalities],
  }
}

function GroupSummary({ summary }: { summary: GroupSummaryData }) {
  const { t } = useI18n()
  const unknown = t('ai.catalog.card.emptyValue')
  return (
    <div className="inline-hint group-summary" role="status">
      <span>
        {t('ai.catalog.form.groupSummaryAbilities')}:{' '}
        {summary.tools == null ? unknown : summary.tools ? t('ai.catalog.form.tools') : '—'}
        {' / '}
        {summary.reasoning == null ? unknown : summary.reasoning ? t('ai.catalog.form.reasoning') : '—'}
        {summary.modalities?.length ? ` / ${summary.modalities.join(', ')}` : ''}
      </span>
      <span>
        {t('ai.catalog.form.contextWindow')}: {summary.context ?? unknown}
        {' · '}
        {t('ai.catalog.form.maxOutputTokens')}: {summary.output ?? unknown}
      </span>
    </div>
  )
}
