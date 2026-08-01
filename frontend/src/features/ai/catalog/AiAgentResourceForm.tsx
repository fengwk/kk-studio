import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import {
  buildSkillCandidates,
  buildToolCandidates,
  withSelectedOrphans,
  type CapabilityOption,
} from '@/features/ai/catalog/agent-capability-candidates'
import type { AgentDraft } from '@/features/ai/catalog/ai-console-types'
import { applyAgentModelSelection, variantOptionsFromModel } from '@/features/ai/catalog/ai-draft-normalizers'
import { modelRef, type AgentModelView } from '@/features/ai/catalog/AgentModelView'
import { emptyAgentDraft } from '@/features/ai/catalog/ai-resource-draft-codecs'
import type { ResourceFieldKey } from '@/features/ai/catalog/ai-resource-form-validation'
import { FormSelect } from '@/shared/ui/console/FormSelect'
import type {
  AgentDefinitionDTO,
  ToolCatalogEntryDTO,
} from '@/shared/api/contracts/ai-catalog'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import { translate, useI18n } from '@/shared/i18n'

function toggleName(items: string[], name: string): string[] {
  return items.includes(name) ? items.filter((item) => item !== name) : [...items, name]
}

export function AgentForm({
  draft,
  models,
  toolCatalog = [],
  environments = [],
  fieldErrors = {},
  onChange,
}: {
  draft: AgentDraft
  models: AgentModelView[]
  toolCatalog?: ToolCatalogEntryDTO[]
  agents?: AgentDefinitionDTO[]
  environments?: LiveEnvironmentDTO[]
  fieldErrors?: Partial<Record<ResourceFieldKey, string>>
  onChange: (draft: AgentDraft) => void
}) {
  const { t } = useI18n()
  const selectedModel = models.find((model) => String(model.id) === draft.modelId)
  const variantOptions = variantOptionsFromModel(selectedModel)
  const selectedVariant = draft.variant.trim()
  const toolCandidates = withSelectedOrphans(
    buildToolCandidates(toolCatalog),
    draft.tools,
  )
  const skillCandidates = withSelectedOrphans(
    buildSkillCandidates(environments),
    draft.skills,
  )

  return (
    <>
      <label className={`form-group${fieldErrors.name ? ' is-error' : ''}`}>
        <FieldLabel required>{t('ai.catalog.form.name')}</FieldLabel>
        <input value={draft.name} onChange={(event) => onChange({ ...draft, name: event.target.value })} placeholder="default-assistant" required />
        {fieldErrors.name ? <span className="field-error">{fieldErrors.name}</span> : null}
      </label>
      <label className="form-group">
        <FieldLabel>{t('ai.catalog.form.description')}</FieldLabel>
        <input
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          placeholder={t('ai.catalog.form.descriptionPlaceholder')}
        />
      </label>
      <label className={`form-group${fieldErrors.modelId ? ' is-error' : ''}`}>
        <FieldLabel required>{t('ai.catalog.form.defaultModel')}</FieldLabel>
        <FormSelect
          aria-label={t('ai.catalog.form.defaultModel')}
          value={draft.modelId}
          required
          options={models.map((model) => ({
            value: String(model.id),
            label: modelRef(model),
          }))}
          onChange={(modelId) => onChange(applyAgentModelSelection(draft, modelId, models))}
        />
        {fieldErrors.modelId ? <span className="field-error">{fieldErrors.modelId}</span> : null}
      </label>
      <label className={`form-group${fieldErrors.variant ? ' is-error' : ''}`}>
        <FieldLabel>{t('ai.catalog.form.defaultVariantOverride')}</FieldLabel>
        <FormSelect
          aria-label={t('ai.catalog.form.defaultVariantOverride')}
          value={selectedVariant}
          disabled={models.length === 0}
          placeholder={t('ai.catalog.form.useModelDefault')}
          options={[
            { value: '', label: t('ai.catalog.form.useModelDefault') },
            ...variantOptions.map((variantName) => ({ value: variantName, label: variantName })),
          ]}
          onChange={(variant) => onChange({ ...draft, variant })}
        />
        {fieldErrors.variant ? <span className="field-error">{fieldErrors.variant}</span> : null}
      </label>
      <label className="form-group">
        <FieldLabel>{t('ai.catalog.form.systemPrompt')}</FieldLabel>
        <textarea
          value={draft.systemPrompt}
          onChange={(event) => onChange({ ...draft, systemPrompt: event.target.value })}
          placeholder={t('ai.catalog.form.systemPromptPlaceholder')}
          rows={4}
        />
      </label>

      <fieldset className={`form-group capability-picker${fieldErrors.tools ? ' is-error' : ''}`}>
        <legend>{t('ai.catalog.form.tools')}</legend>
        <CapabilityChecklist
          options={toolCandidates}
          selected={draft.tools}
          emptyText={t('ai.catalog.form.noCandidateTools')}
          onToggle={(name) => onChange({ ...draft, tools: toggleName(draft.tools, name) })}
        />
        {fieldErrors.tools ? <span className="field-error">{fieldErrors.tools}</span> : null}
      </fieldset>

      <fieldset className={`form-group capability-picker${fieldErrors.skills ? ' is-error' : ''}`}>
        <legend>{t('ai.catalog.card.skills')}</legend>
        <CapabilityChecklist
          options={skillCandidates}
          selected={draft.skills}
          emptyText={t('ai.catalog.form.noCandidateSkills')}
          onToggle={(name) => onChange({ ...draft, skills: toggleName(draft.skills, name) })}
        />
        {fieldErrors.skills ? <span className="field-error">{fieldErrors.skills}</span> : null}
      </fieldset>

      {models.length === 0 && (
        <div className="inline-hint" role="status">
          {t('ai.catalog.form.needModel')}
        </div>
      )}
      {models.length > 0 && !draft.modelId && (
        <button className="ghost-inline-btn" type="button" onClick={() => onChange(emptyAgentDraft(models[0]))}>
          {t('ai.catalog.form.fillFirstModel')}
        </button>
      )}
    </>
  )
}

function CapabilityChecklist({
  options,
  selected,
  emptyText,
  onToggle,
}: {
  options: CapabilityOption[]
  selected: string[]
  emptyText: string
  onToggle: (name: string) => void
}) {
  // options 已含 selected orphan；仅当既无候选也无已选时才显示空态。
  if (options.length === 0) {
    return (
      <div className="capability-options">
        <div className="inline-hint">{emptyText}</div>
      </div>
    )
  }

  return (
    <div className="capability-options">
      {options.map((option) => {
        const checked = selected.includes(option.name)
        const stateClass = option.missing ? ' is-offline is-missing' : option.offline ? ' is-offline' : ''
        return (
          <label
            key={option.name}
            className={`capability-option capability-option-detailed${checked ? ' is-selected' : ''}${stateClass}`}
          >
            <input type="checkbox" checked={checked} onChange={() => onToggle(option.name)} />
            <span className="capability-option-body">
              <span className="capability-option-heading">
                <code className="capability-name">{option.name}</code>
                {option.version ? (
                  <span className="capability-option-meta">{option.version}</span>
                ) : null}
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
          </label>
        )
      })}
    </div>
  )
}
