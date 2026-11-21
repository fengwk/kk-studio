import { Button } from '@/shared/ui/controls/Button'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { keyToSkillRef, skillRefToKey } from '@/features/catalog/agent-capability-candidates'
import {
  agentCapabilityCandidates,
  isAgentModelUnavailable,
} from '@/features/catalog/agent-form-candidates'
import { CatalogEditorSection } from '@/features/catalog/CatalogEditorSection'
import {
  CapabilitySelectionPanel,
  type CapabilityDirectory,
} from '@/features/catalog/CapabilitySelectionPanels'
import type { AgentDraft } from '@/features/catalog/ai-console-types'
import { applyAgentModelSelection } from '@/features/catalog/ai-draft-normalizers'
import type { ResourceFieldKey } from '@/features/catalog/ai-resource-form-validation'
import { Select } from '@/shared/ui/controls/Select'
import { ModelSelectionPicker } from '@/shared/ui/controls/ModelSelectionPicker'
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
  capabilityDirectories,
  fieldErrors = {},
  onChange,
}: {
  draft: AgentDraft
  mode?: 'create' | 'edit'
  models: ModelDTO[]
  toolCatalog?: ToolCatalogEntryDTO[]
  agents?: AgentDTO[]
  skills?: SkillPackageSummaryDTO[]
  capabilityDirectories?: Partial<Record<'tools' | 'skills' | 'subagents', CapabilityDirectory>>
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
      <CatalogEditorSection name="basics">
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
      </CatalogEditorSection>

      <CatalogEditorSection name="execution">
        <label className="form-group">
          <FieldLabel required>{t('ai.catalog.form.executionDriver')}</FieldLabel>
          <Select
            aria-label={t('ai.catalog.form.executionDriver')}
            value={driver}
            options={[
              ...(driver !== 'builtin'
                ? [{ value: driver, label: `${driver} (${t('ai.catalog.form.unavailable')})` }]
                : []),
              { value: 'builtin', label: 'builtin' },
            ]}
            onChange={(value) => {
              if (value === 'builtin' && driver !== 'builtin') {
                onChange({ ...draft, execution: null, model: '', variant: '' })
              }
            }}
          />
        </label>
        {driver === 'builtin' ? (
          <>
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
                onModelChange={(model) => {
                  if (model !== draft.model) onChange(applyAgentModelSelection(draft, model, models))
                }}
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
            {models.length === 0 && (
              <div className="inline-hint" role="status">
                {t('ai.catalog.form.needModel')}
              </div>
            )}
            {models.length > 0 && !draft.model && (
              <Button
                variant="inline"
                onClick={() =>
                  onChange(applyAgentModelSelection(draft, models[0].name, models))
                }
              >
                {t('ai.catalog.form.fillFirstModel')}
              </Button>
            )}
          </>
        ) : null}
      </CatalogEditorSection>

      <CatalogEditorSection name="prompt">
        <label className="form-group">
          <FieldLabel>{t('ai.catalog.form.systemPrompt')}</FieldLabel>
          <TextArea
            value={draft.systemPrompt}
            onChange={(event) => onChange({ ...draft, systemPrompt: event.target.value })}
            placeholder={t('ai.catalog.form.systemPromptPlaceholder')}
            rows={4}
          />
        </label>
      </CatalogEditorSection>

      <CatalogEditorSection name="capabilities">
        <CapabilitySelectionPanel
          label={t('ai.catalog.form.agentTools')}
          manageLabel={t('ai.catalog.form.manageTools')}
          searchLabel={t('ai.catalog.form.searchTools')}
          options={toolCandidates}
          selected={visibleSelectedTools}
          emptyText={t('ai.catalog.form.noCandidateTools')}
          directory={capabilityDirectories?.tools}
          error={fieldErrors.tools}
          onToggle={(value) => onChange({ ...draft, tools: toggleValue(draft.tools, value) })}
        />
        <CapabilitySelectionPanel
          label={t('ai.catalog.form.agentSkills')}
          manageLabel={t('ai.catalog.form.manageSkills')}
          searchLabel={t('ai.catalog.form.searchSkills')}
          options={skillCandidates}
          selected={visibleSelectedSkills}
          emptyText={t('ai.catalog.form.noCandidateSkills')}
          directory={capabilityDirectories?.skills}
          error={fieldErrors.skills}
          onToggle={(value) => {
            const exists = draft.skills.some((ref) => skillRefToKey(ref) === value)
            const nextSkills = exists
              ? draft.skills.filter((ref) => skillRefToKey(ref) !== value)
              : [...draft.skills, keyToSkillRef(value)]
            onChange({ ...draft, skills: nextSkills })
          }}
        />
        <CapabilitySelectionPanel
          label={t('ai.catalog.form.agentSubagents')}
          manageLabel={t('ai.catalog.form.manageSubagents')}
          searchLabel={t('ai.catalog.form.searchSubagents')}
          options={subagentCandidates}
          selected={draft.subagents}
          emptyText={t('ai.catalog.form.noCandidateSubagents')}
          directory={capabilityDirectories?.subagents}
          error={fieldErrors.subagents}
          onToggle={(value) =>
            onChange({ ...draft, subagents: toggleValue(draft.subagents, value) })
          }
        />
      </CatalogEditorSection>
    </>
  )
}
