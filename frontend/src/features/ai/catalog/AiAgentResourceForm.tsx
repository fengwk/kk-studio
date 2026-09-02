import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import {
  buildSkillCandidates,
  buildSubagentCandidates,
  buildToolCandidates,
  withSelectedOrphans,
  type CapabilityOption,
} from '@/features/ai/catalog/agent-capability-candidates'
import type { AgentDraft } from '@/features/ai/catalog/ai-console-types'
import { applyAgentModelSelection, variantOptionsFromModel } from '@/features/ai/catalog/ai-draft-normalizers'
import { modelRef, type AgentModelView } from '@/features/ai/catalog/AgentModelView'
import { emptyAgentDraft } from '@/features/ai/catalog/ai-resource-draft-codecs'
import type { ResourceFieldKey } from '@/features/ai/catalog/ai-resource-form-validation'
import { Select } from '@/shared/ui/console/Select'
import type {
  AgentDefinitionDTO,
  ToolCatalogEntryDTO,
} from '@/shared/api/contracts/ai-catalog'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { translate, useI18n } from '@/shared/i18n'

function toggleValue(items: string[], value: string): string[] {
  return items.includes(value) ? items.filter((item) => item !== value) : [...items, value]
}

export function AgentForm({
  draft,
  mode = 'create',
  models,
  toolCatalog = [],
  agents = [],
  environments = [],
  fieldErrors = {},
  onChange,
}: {
  draft: AgentDraft
  mode?: 'create' | 'edit'
  models: AgentModelView[]
  toolCatalog?: ToolCatalogEntryDTO[]
  agents?: AgentDefinitionDTO[]
  environments?: EnvironmentCardDTO[]
  fieldErrors?: Partial<Record<ResourceFieldKey, string>>
  onChange: (draft: AgentDraft) => void
}) {
  const { t } = useI18n()
  const selectedModel = models.find((model) => modelRef(model) === draft.model)
  const modelUnavailable =
    mode === 'edit' && Boolean(draft.model) && selectedModel === undefined
  const variantOptions = variantOptionsFromModel(selectedModel)
  const selectedVariant = draft.variant.trim()
  const modelOptions = modelUnavailable
    ? [
        {
          value: draft.model,
          label: `${draft.model} (${t('ai.catalog.form.unavailable')})`,
          disabled: true,
        },
        ...models.map((model) => ({ value: modelRef(model), label: modelRef(model) })),
      ]
    : models.map((model) => ({ value: modelRef(model), label: modelRef(model) }))
  const variantSelectOptions = [
    { value: '', label: t('ai.catalog.form.useModelDefault') },
    ...(modelUnavailable && selectedVariant
      ? [
          {
            value: selectedVariant,
            label: `${selectedVariant} (${t('ai.catalog.form.unavailable')})`,
            disabled: true,
          },
        ]
      : []),
    ...variantOptions.map((variantName) => ({ value: variantName, label: variantName })),
  ]
  const toolCandidates = withSelectedOrphans(
    buildToolCandidates(toolCatalog),
    draft.toolIds,
  )

  const selectedEnvironmentId = (draft.environmentId ?? '').trim()
  const selectedEnvironment = environments.find((env) => env.id === selectedEnvironmentId)
  const environmentUnavailable =
    selectedEnvironmentId !== '' && selectedEnvironment === undefined

  const environmentOptions = [
    { value: '', label: t('ai.catalog.form.none') },
    ...(environmentUnavailable
      ? [
          {
            value: selectedEnvironmentId,
            label: `${selectedEnvironmentId} (${t('ai.catalog.form.unavailable')})`,
            disabled: true,
          },
        ]
      : []),
    ...environments.map((env) => ({
      value: env.id,
      label: env.ready ? env.name : `${env.name} (${t('ai.catalog.form.unavailable')})`,
    })),
  ]

  const skillCandidates = withSelectedOrphans(
    buildSkillCandidates(selectedEnvironment),
    draft.skills,
  )

  // Subagent 候选来自当前全局 Agent catalog；create 模式下同名候选（该行尚不存在）不展示，
  // edit 模式下当前 agent 已存在，可以正常显示。已勾选但 catalog 缺失的名称保留为可移除 orphan。
  const subagentCatalogCandidates = buildSubagentCandidates(agents)
  const subagentCandidates = withSelectedOrphans(
    mode === 'create' && draft.name.trim()
      ? subagentCatalogCandidates.filter((candidate) => candidate.name !== draft.name.trim())
      : subagentCatalogCandidates,
    draft.subagents,
  )

  return (
    <>
      <label className={`form-group${fieldErrors.name ? ' is-error' : ''}`}>
        <FieldLabel required>{t('ai.catalog.form.name')}</FieldLabel>
        <input
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          placeholder="default-assistant"
          readOnly={mode === 'edit'}
          required
        />
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
      <label className={`form-group${fieldErrors.model ? ' is-error' : ''}`}>
        <FieldLabel required>{t('ai.catalog.form.defaultModel')}</FieldLabel>
        <Select
          aria-label={t('ai.catalog.form.defaultModel')}
          aria-describedby={modelUnavailable ? 'agent-model-identity-status' : undefined}
          value={draft.model}
          required
          options={modelOptions}
          onChange={(model) => onChange(applyAgentModelSelection(draft, model, models))}
        />
        {modelUnavailable ? (
          <span id="agent-model-identity-status" className="inline-hint" role="status">
            {t('ai.catalog.form.unavailableIdentityHint')}
          </span>
        ) : null}
        {fieldErrors.model ? <span className="field-error">{fieldErrors.model}</span> : null}
      </label>
      <label className={`form-group${fieldErrors.variant ? ' is-error' : ''}`}>
        <FieldLabel>{t('ai.catalog.form.defaultVariantOverride')}</FieldLabel>
        <Select
          aria-label={t('ai.catalog.form.defaultVariantOverride')}
          value={selectedVariant}
          disabled={selectedModel === undefined}
          placeholder={t('ai.catalog.form.useModelDefault')}
          aria-describedby={modelUnavailable ? 'agent-model-variant-status' : undefined}
          options={variantSelectOptions}
          onChange={(variant) => onChange({ ...draft, variant })}
        />
        {modelUnavailable ? (
          <span id="agent-model-variant-status" className="inline-hint" role="status">
            {t('ai.catalog.form.unavailableModelVariants')}
          </span>
        ) : null}
        {fieldErrors.variant ? <span className="field-error">{fieldErrors.variant}</span> : null}
      </label>
      <label className="form-group">
        <FieldLabel>{t('ai.catalog.form.environment')}</FieldLabel>
        <Select
          aria-label={t('ai.catalog.form.environment')}
          value={draft.environmentId}
          options={environmentOptions}
          onChange={(environmentId) => onChange({ ...draft, environmentId })}
        />
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

      <fieldset className={`form-group capability-picker${fieldErrors.toolIds ? ' is-error' : ''}`}>
        <legend>{t('ai.catalog.form.tools')}</legend>
        <CapabilityChecklist
          options={toolCandidates}
          selected={draft.toolIds}
          emptyText={t('ai.catalog.form.noCandidateTools')}
          onToggle={(value) => onChange({ ...draft, toolIds: toggleValue(draft.toolIds, value) })}
        />
        {fieldErrors.toolIds ? <span className="field-error">{fieldErrors.toolIds}</span> : null}
      </fieldset>

      <fieldset className={`form-group capability-picker${fieldErrors.skills ? ' is-error' : ''}`}>
        <legend>{t('ai.catalog.card.skills')}</legend>
        <CapabilityChecklist
          options={skillCandidates}
          selected={draft.skills}
          emptyText={t('ai.catalog.form.noCandidateSkills')}
          onToggle={(value) => onChange({ ...draft, skills: toggleValue(draft.skills, value) })}
        />
        {fieldErrors.skills ? <span className="field-error">{fieldErrors.skills}</span> : null}
      </fieldset>

      <fieldset className={`form-group capability-picker${fieldErrors.subagents ? ' is-error' : ''}`}>
        <legend>{t('ai.catalog.card.subagents')}</legend>
        <CapabilityChecklist
          options={subagentCandidates}
          selected={draft.subagents}
          emptyText={t('ai.catalog.form.noCandidateSubagents')}
          onToggle={(value) => onChange({ ...draft, subagents: toggleValue(draft.subagents, value) })}
        />
        {fieldErrors.subagents ? (
          <span className="field-error">{fieldErrors.subagents}</span>
        ) : null}
      </fieldset>

      {models.length === 0 && (
        <div className="inline-hint" role="status">
          {t('ai.catalog.form.needModel')}
        </div>
      )}
      {models.length > 0 && !draft.model && (
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
  onToggle: (value: string) => void
}) {
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
        const checked = selected.includes(option.value)
        const stateClass = option.missing ? ' is-offline is-missing' : option.offline ? ' is-offline' : ''
        return (
          <label
            key={option.value}
            className={`capability-option capability-option-detailed${checked ? ' is-selected' : ''}${stateClass}`}
          >
            <input
              type="checkbox"
              value={option.value}
              checked={checked}
              onChange={() => onToggle(option.value)}
            />
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
