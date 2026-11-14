import type { ModelGroupDraft } from '@/features/catalog/ai-console-types'
import type { SingleModelDTO, ModelDTO } from '@/shared/api/generated/contracts'
import { ModelGroupMembersSection } from '@/features/catalog/ModelGroupMembersSection'
import type { ResourceFieldKey } from '@/features/catalog/ai-resource-form-validation'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { TagInput } from '@/shared/ui/controls/TagInput'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { useI18n } from '@/shared/i18n'
import './catalog-form-layout.css'
import './ai-model-group-resource-form.css'

export function ModelGroupForm({
  draft,
  models,
  fieldErrors = {},
  onChange,
}: {
  draft: ModelGroupDraft
  models: ModelDTO[]
  fieldErrors?: Partial<Record<ResourceFieldKey, string>>
  onChange: (draft: ModelGroupDraft) => void
}) {
  const { t } = useI18n()
  const candidates = models.filter(
    (model): model is SingleModelDTO => model.type === 'MODEL',
  )
  const selectedModels = draft.members
    .map((member) => candidates.find((model) => model.name === member.modelName))
    .filter((model): model is SingleModelDTO => model != null)
  const summary = summarizeGroup(selectedModels)

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
        <ModelGroupMembersSection draft={draft} candidates={candidates} onChange={onChange} />
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
function summarizeGroup(models: ModelDTO[]): GroupSummaryData {
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
