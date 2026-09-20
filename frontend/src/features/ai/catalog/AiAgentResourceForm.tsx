import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import {
  buildSkillCandidates,
  buildSubagentCandidates,
  buildToolCandidates,
  keyToSkillRef,
  skillRefToKey,
  withSelectedOrphans,
  withSelectedSkillOrphans,
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
  SkillPackageDTO,
  ToolCatalogEntryDTO,
} from '@/shared/api/contracts/ai-catalog'
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
  skills = [],
  skillsLoading = false,
  skillsError = null,
  fieldErrors = {},
  onChange,
}: {
  draft: AgentDraft
  mode?: 'create' | 'edit'
  models: AgentModelView[]
  toolCatalog?: ToolCatalogEntryDTO[]
  agents?: AgentDefinitionDTO[]
  skills?: SkillPackageDTO[]
  skillsLoading?: boolean
  skillsError?: unknown
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
    draft.tools,
  )
  const visibleToolValues = new Set(toolCandidates.map((option) => option.value))
  const visibleSelectedTools = draft.tools.filter((id) => visibleToolValues.has(id.trim()))

  const selectedSkillKeys = draft.skills.map(skillRefToKey)
  const skillCandidates = withSelectedSkillOrphans(
    buildSkillCandidates(skills),
    draft.skills,
  )
  const visibleSkillValues = new Set(skillCandidates.map((option) => option.value))
  const visibleSelectedSkills = selectedSkillKeys.filter((key) => visibleSkillValues.has(key))

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
        {/* 多行描述：内部换行必须原样保留在 draft 与提交 payload 中。 */}
        <textarea
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          placeholder={t('ai.catalog.form.descriptionPlaceholder')}
          rows={2}
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
          selected={visibleSelectedTools}
          emptyText={t('ai.catalog.form.noCandidateTools')}
          onToggle={(value) =>
            onChange({ ...draft, tools: toggleValue(visibleSelectedTools, value) })
          }
        />
        {fieldErrors.tools ? <span className="field-error">{fieldErrors.tools}</span> : null}
      </fieldset>

      <fieldset className={`form-group capability-picker${fieldErrors.skills ? ' is-error' : ''}`}>
        <legend>{t('ai.catalog.card.skills')}</legend>
        <CapabilityChecklist
          options={skillCandidates}
          selected={visibleSelectedSkills}
          emptyText={t('ai.catalog.form.noCandidateSkills')}
          statusText={skillsLoading ? t('ai.common.loadingResources') : null}
          errorText={skillsError ? t('ai.common.resourceLoadFailed') : null}
          onToggle={(value) => {
            const exists = draft.skills.some((ref) => skillRefToKey(ref) === value)
            const nextSkills = exists
              ? draft.skills.filter((ref) => skillRefToKey(ref) !== value)
              : [...draft.skills, keyToSkillRef(value)]
            onChange({ ...draft, skills: nextSkills })
          }}
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

      <div className="form-group">
        <label className="checkbox-field">
          <input
            type="checkbox"
            checked={draft.inheritParentEnvironment}
            onChange={(event) =>
              onChange({ ...draft, inheritParentEnvironment: event.target.checked })
            }
          />
          <span>{t('ai.catalog.form.inheritParentEnvironment')}</span>
        </label>
      </div>

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
  statusText,
  errorText,
  onToggle,
}: {
  options: CapabilityOption[]
  selected: string[]
  emptyText: string
  statusText?: string | null
  errorText?: string | null
  onToggle: (value: string) => void
}) {
  if (options.length === 0) {
    if (statusText) {
      return (
        <div className="capability-options" role="status">
          <div className="inline-hint">{statusText}</div>
        </div>
      )
    }
    if (errorText) {
      return (
        <div className="capability-options" role="alert">
          <div className="inline-hint">{errorText}</div>
        </div>
      )
    }
    return (
      <div className="capability-options">
        <div className="inline-hint">{emptyText}</div>
      </div>
    )
  }

  return (
    <div className="capability-options">
      {statusText ? (
        <div className="inline-hint" role="status">
          {statusText}
        </div>
      ) : null}
      {errorText ? (
        <div className="inline-hint" role="alert">
          {errorText}
        </div>
      ) : null}
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
