import { useEffect, useState } from 'react'
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
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import { translate, useI18n } from '@/shared/i18n'

function toggleName(items: string[], name: string): string[] {
  return items.includes(name) ? items.filter((item) => item !== name) : [...items, name]
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
  environments?: LiveEnvironmentDTO[]
  fieldErrors?: Partial<Record<ResourceFieldKey, string>>
  onChange: (draft: AgentDraft) => void
}) {
  const { t } = useI18n()
  // 瞬态 Skill 目录浏览选择：只用于挑选展示哪个 live Environment 的 skill 名称，
  // 绝不进入 AgentDraft/提交 DTO；切换 Agent 或重新打开创建编辑器时重置。
  const [skillCatalogEnvironmentName, setSkillCatalogEnvironmentName] = useState('')
  const editorSessionKey = mode === 'edit' ? `edit:${draft.name}` : 'create'
  useEffect(() => {
    setSkillCatalogEnvironmentName('')
  }, [editorSessionKey])
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
  // Skill 候选只来自用户显式选中的一个 live Environment（ready===true）；未选择/来源失效时没有 live 候选，
  // 已勾选的名称仍作为可移除 orphan 保留。
  const readyEnvironments = environments.filter((environment) => environment.ready === true)
  const selectedSkillSource = readyEnvironments.find(
    (environment) => environment.name === skillCatalogEnvironmentName,
  )
  const skillSourceUnavailable =
    skillCatalogEnvironmentName !== '' && selectedSkillSource === undefined
  const skillSourceOptions = [
    // 显式可选的空/无选项：用户选择来源后可随时回到「无来源」（只清组件本地浏览状态）。
    { value: '', label: t('ai.catalog.form.none') },
    ...(skillSourceUnavailable
      ? [
          {
            value: skillCatalogEnvironmentName,
            label: `${skillCatalogEnvironmentName} (${t('ai.catalog.form.unavailable')})`,
            disabled: true,
          },
        ]
      : []),
    ...readyEnvironments.map((environment) => ({
      value: environment.name,
      label: environment.name,
    })),
  ]
  const skillCandidates = withSelectedOrphans(
    buildSkillCandidates(selectedSkillSource),
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

      <label className="form-group">
        <FieldLabel>{t('ai.catalog.form.skillCatalogSource')}</FieldLabel>
        <Select
          aria-label={t('ai.catalog.form.skillCatalogSource')}
          value={skillCatalogEnvironmentName}
          options={skillSourceOptions}
          onChange={(name) => setSkillCatalogEnvironmentName(name)}
        />
        <span className="inline-hint">{t('ai.catalog.form.skillCatalogSourceHint')}</span>
      </label>

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

      <fieldset className={`form-group capability-picker${fieldErrors.subagents ? ' is-error' : ''}`}>
        <legend>{t('ai.catalog.card.subagents')}</legend>
        <CapabilityChecklist
          options={subagentCandidates}
          selected={draft.subagents}
          emptyText={t('ai.catalog.form.noCandidateSubagents')}
          onToggle={(name) => onChange({ ...draft, subagents: toggleName(draft.subagents, name) })}
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
