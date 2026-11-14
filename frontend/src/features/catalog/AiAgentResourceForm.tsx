import { Button } from '@/shared/ui/controls/Button'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { keyToSkillRef, skillRefToKey } from '@/features/catalog/agent-capability-candidates'
import {
  agentCapabilityCandidates,
  isAgentModelUnavailable,
} from '@/features/catalog/agent-form-candidates'
import { CapabilityChecklist } from '@/features/catalog/CapabilityChecklist'
import type { AgentDraft } from '@/features/catalog/ai-console-types'
import { applyAgentModelSelection } from '@/features/catalog/ai-draft-normalizers'
import { emptyAgentDraft } from '@/features/catalog/ai-resource-draft-codecs'
import type { ResourceFieldKey } from '@/features/catalog/ai-resource-form-validation'
import { Select } from '@/shared/ui/controls/Select'
import { ModelSelectionPicker } from '@/shared/ui/controls/ModelSelectionPicker'
import { ToolSelectionPanel, SkillSelectionPanel } from '@/features/catalog/CapabilitySelectionPanels'
import { useI18n } from '@/shared/i18n'
import type {
  AgentDTO,
  ModelDTO,
  SkillPackageSummaryDTO,
  ToolCatalogEntryDTO,
} from '@/shared/api/generated/contracts'
import './capability-picker.css'

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
  models: ModelDTO[]
  toolCatalog?: ToolCatalogEntryDTO[]
  agents?: AgentDTO[]
  skills?: SkillPackageSummaryDTO[]
  skillsLoading?: boolean
  skillsError?: unknown
  fieldErrors?: Partial<Record<ResourceFieldKey, string>>
  onChange: (draft: AgentDraft) => void
}) {
  const { t } = useI18n()
  const driver = draft.execution?.driver ?? 'builtin'
  const selectedModel = models.find((model) => model.name === draft.model)
  const modelUnavailable = isAgentModelUnavailable(draft, models, mode)
  const {
    tools: toolCandidates,
    selectedTools: visibleSelectedTools,
    skills: skillCandidates,
    selectedSkills: visibleSelectedSkills,
    subagents: subagentCandidates,
  } = agentCapabilityCandidates({ draft, mode, toolCatalog, agents, skills })

  return (
    <>
      <label className={`form-group${fieldErrors.name ? ' is-error' : ''}`}>
        <FieldLabel required>{t('ai.catalog.form.name')}</FieldLabel>
        <TextInput
          invalid={Boolean(fieldErrors.name)}
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
        <TextArea
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          placeholder={t('ai.catalog.form.descriptionPlaceholder')}
          rows={2}
        />
      </label>
      <label className="form-group">
        <FieldLabel required>{t('ai.catalog.form.executionDriver')}</FieldLabel>
        <Select
          aria-label={t('ai.catalog.form.executionDriver')}
          value={driver}
          options={[
            ...(driver !== 'builtin' ? [{ value: driver, label: `${driver} (${t('ai.catalog.form.unavailable')})` }] : []),
            { value: 'builtin', label: 'builtin' },
          ]}
          onChange={(value) => {
            if (value === 'builtin' && driver !== 'builtin') {
              onChange({ ...draft, execution: null, model: '', variant: '' })
            }
          }}
        />
      </label>
      {driver === 'builtin' ? <>
      <label className={`form-group${fieldErrors.model ? ' is-error' : ''}`}>
        <FieldLabel required>{t('ai.catalog.form.defaultModel')}</FieldLabel>
        <ModelSelectionPicker
          models={models}
          modelName={draft.model}
          variant={draft.variant.trim()}
          modelLabel={t('ai.catalog.form.defaultModel')}
          variantLabel={t('ai.catalog.form.defaultVariantOverride')}
          variantFieldLabel={t('ai.catalog.form.defaultVariantOverride')}
          allowModelGroup
          showVariant
          variantUnsetLabel={t('ai.catalog.form.useModelDefault')}
          modelRequired
          modelUnavailable={modelUnavailable}
          variantUnavailable={modelUnavailable}
          variantDisabled={selectedModel === undefined}
          modelDescribedBy={modelUnavailable ? 'agent-model-identity-status' : undefined}
          variantDescribedBy={modelUnavailable ? 'agent-model-variant-status' : undefined}
          onModelChange={(model) => onChange(applyAgentModelSelection(draft, model, models))}
          onVariantChange={(variant) => onChange({ ...draft, variant })}
        />
        {modelUnavailable ? (
          <span id="agent-model-identity-status" className="inline-hint" role="status">
            {t('ai.catalog.form.unavailableIdentityHint')}
          </span>
        ) : null}
        {fieldErrors.model ? <span className="field-error">{fieldErrors.model}</span> : null}
      </label>
      {modelUnavailable ? (
        <span id="agent-model-variant-status" className="inline-hint" role="status">
          {t('ai.catalog.form.unavailableModelVariants')}
        </span>
      ) : null}
      {fieldErrors.variant ? <span className="field-error">{fieldErrors.variant}</span> : null}
      </> : null}
      <label className="form-group">
        <FieldLabel>{t('ai.catalog.form.systemPrompt')}</FieldLabel>
        <TextArea
          value={draft.systemPrompt}
          onChange={(event) => onChange({ ...draft, systemPrompt: event.target.value })}
          placeholder={t('ai.catalog.form.systemPromptPlaceholder')}
          rows={4}
        />
      </label>

      <fieldset className={`form-group capability-picker${fieldErrors.tools ? ' is-error' : ''}`}>
        <legend>{t('ai.catalog.form.tools')}</legend>
        <ToolSelectionPanel
          options={toolCandidates}
          selected={visibleSelectedTools}
          emptyText={t('ai.catalog.form.noCandidateTools')}
          onToggle={(value) => onChange({ ...draft, tools: toggleValue(visibleSelectedTools, value) })}
        />
        {fieldErrors.tools ? <span className="field-error">{fieldErrors.tools}</span> : null}
      </fieldset>

      <fieldset className={`form-group capability-picker${fieldErrors.skills ? ' is-error' : ''}`}>
        <legend>{t('ai.catalog.card.skills')}</legend>
        <SkillSelectionPanel
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

      {driver === 'builtin' && models.length === 0 && (
        <div className="inline-hint" role="status">
          {t('ai.catalog.form.needModel')}
        </div>
      )}
      {driver === 'builtin' && models.length > 0 && !draft.model && (
        <Button variant="inline" onClick={() => onChange(emptyAgentDraft(models[0]))}>
          {t('ai.catalog.form.fillFirstModel')}
        </Button>
      )}
    </>
  )
}

