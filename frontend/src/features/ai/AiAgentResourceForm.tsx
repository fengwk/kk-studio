import { FieldLabel } from '@/features/ai/FieldLabel'
import {
  buildCapabilityCandidates,
  PLATFORM_ENVIRONMENT_NAME,
  withSelectedOrphans,
  type CapabilityOption,
} from '@/features/ai/agent-capability-candidates'
import type { AgentDraft } from '@/features/ai/ai-console-types'
import { applyAgentModelSelection, variantOptionsFromModel } from '@/features/ai/ai-draft-normalizers'
import { modelRef, type AgentModelView } from '@/features/ai/AgentModelView'
import { emptyAgentDraft } from '@/features/ai/ai-resource-draft-codecs'
import type { ResourceFieldKey } from '@/features/ai/ai-resource-form-validation'
import { FormSelect } from '@/features/ai/FormSelect'
import type {
  AgentDefinitionDTO,
  LiveEnvironmentDTO,
} from '@/shared/api/contracts'

function toggleName(items: string[], name: string): string[] {
  return items.includes(name) ? items.filter((item) => item !== name) : [...items, name]
}

export function AgentForm({
  draft,
  models,
  environments = [],
  fieldErrors = {},
  onChange,
}: {
  draft: AgentDraft
  models: AgentModelView[]
  agents?: AgentDefinitionDTO[]
  environments?: LiveEnvironmentDTO[]
  fieldErrors?: Partial<Record<ResourceFieldKey, string>>
  onChange: (draft: AgentDraft) => void
}) {
  const selectedModel = models.find((model) => String(model.id) === draft.modelId)
  const variantOptions = variantOptionsFromModel(selectedModel)
  const selectedVariant = draft.variant.trim()
  const readyEnvironments = environments.filter(
    (environment) =>
      environment.name !== PLATFORM_ENVIRONMENT_NAME && String(environment.status).toUpperCase() === 'READY',
  )
  const selectedEnvironment = environments.find((environment) => environment.name === draft.environmentName)
  const environmentMissing = Boolean(draft.environmentName.trim()) && !selectedEnvironment
  const environmentOffline =
    Boolean(selectedEnvironment) && String(selectedEnvironment?.status).toUpperCase() !== 'READY'
  const toolCandidates = withSelectedOrphans(
    buildCapabilityCandidates(environments, draft.environmentName, 'tools'),
    draft.tools,
  )
  const skillCandidates = withSelectedOrphans(
    buildCapabilityCandidates(environments, draft.environmentName, 'skills'),
    draft.skills,
  )

  return (
    <>
      <label className={`form-group${fieldErrors.name ? ' is-error' : ''}`}>
        <FieldLabel required>Name</FieldLabel>
        <input value={draft.name} onChange={(event) => onChange({ ...draft, name: event.target.value })} placeholder="default-assistant" required />
        {fieldErrors.name ? <span className="field-error">{fieldErrors.name}</span> : null}
      </label>
      <label className="form-group">
        <FieldLabel>Description</FieldLabel>
        <input value={draft.description} onChange={(event) => onChange({ ...draft, description: event.target.value })} placeholder="用途说明" />
      </label>
      <label className={`form-group${fieldErrors.modelId ? ' is-error' : ''}`}>
        <FieldLabel required>Default Model</FieldLabel>
        <FormSelect
          aria-label="Default Model"
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
        <FieldLabel>Default Variant Override</FieldLabel>
        <FormSelect
          aria-label="Default Variant Override"
          value={selectedVariant}
          disabled={models.length === 0}
          placeholder="（使用模型默认）"
          options={[
            { value: '', label: '（使用模型默认）' },
            ...variantOptions.map((variantName) => ({ value: variantName, label: variantName })),
          ]}
          onChange={(variant) => onChange({ ...draft, variant })}
        />
        {fieldErrors.variant ? <span className="field-error">{fieldErrors.variant}</span> : null}
      </label>
      <label className="form-group">
        <FieldLabel>System Prompt</FieldLabel>
        <textarea value={draft.systemPrompt} onChange={(event) => onChange({ ...draft, systemPrompt: event.target.value })} placeholder="系统提示词" rows={4} />
      </label>

      <label className={`form-group${fieldErrors.environmentName ? ' is-error' : ''}`}>
        <FieldLabel>Environment</FieldLabel>
        <FormSelect
          aria-label="Environment"
          value={draft.environmentName}
          placeholder="（无）"
          options={[
            { value: '', label: '（无）' },
            ...readyEnvironments.map((environment) => ({
              value: environment.name,
              label: environment.name,
            })),
            ...(environmentMissing || environmentOffline
              ? [
                  {
                    value: draft.environmentName,
                    label: `${draft.environmentName}${environmentMissing ? '（缺失）' : '（离线）'}`,
                  },
                ]
              : []),
          ]}
          onChange={(environmentName) => onChange({ ...draft, environmentName })}
        />
        {fieldErrors.environmentName ? (
          <span className="field-error">{fieldErrors.environmentName}</span>
        ) : null}
      </label>
      <div className="inline-hint" role="note">
        切换 Environment 只会刷新可选 Tools/Skills 列表；已勾选项会尽量保留，不会自动清空。离线/暂不可用项置灰，仍可取消勾选并保存。
      </div>

      <fieldset className={`form-group capability-picker${fieldErrors.tools ? ' is-error' : ''}`}>
        <legend>Tools</legend>
        <CapabilityChecklist
          options={toolCandidates}
          selected={draft.tools}
          emptyText="暂无候选 Tools"
          onToggle={(name) => onChange({ ...draft, tools: toggleName(draft.tools, name) })}
        />
        {fieldErrors.tools ? <span className="field-error">{fieldErrors.tools}</span> : null}
      </fieldset>

      <fieldset className={`form-group capability-picker${fieldErrors.skills ? ' is-error' : ''}`}>
        <legend>Skills</legend>
        <CapabilityChecklist
          options={skillCandidates}
          selected={draft.skills}
          emptyText="暂无候选 Skills"
          onToggle={(name) => onChange({ ...draft, skills: toggleName(draft.skills, name) })}
        />
        {fieldErrors.skills ? <span className="field-error">{fieldErrors.skills}</span> : null}
      </fieldset>

      {models.length === 0 && (
        <div className="inline-hint" role="status">
          需要先创建 Model 才能配置 Agent。
        </div>
      )}
      {models.length > 0 && !draft.modelId && (
        <button className="ghost-inline-btn" type="button" onClick={() => onChange(emptyAgentDraft(models[0]))}>
          使用第一个 Model 填充默认配置
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

  const groups = new Map<string, CapabilityOption[]>()
  for (const option of options) {
    const bucket = groups.get(option.source) ?? []
    bucket.push(option)
    groups.set(option.source, bucket)
  }

  return (
    <div className="capability-options">
      {[...groups.entries()].map(([source, items]) => (
        <div key={source} className="capability-group">
          {items.map((option) => {
            const checked = selected.includes(option.name)
            const label = option.missing ? option.name : `${option.source}/${option.name}`
            const stateClass = option.missing
              ? ' is-offline is-missing'
              : option.offline
                ? ' is-offline'
                : ''
            return (
              <label
                key={`${option.source}/${option.name}`}
                className={`capability-option${checked ? ' is-selected' : ''}${stateClass}`}
              >
                <input type="checkbox" checked={checked} onChange={() => onToggle(option.name)} />
                <span>
                  <code className="capability-name">{label}</code>
                  {option.missing ? <small>不可用</small> : null}
                  {!option.missing && option.offline ? <small>offline</small> : null}
                </span>
              </label>
            )
          })}
        </div>
      ))}
    </div>
  )
}
