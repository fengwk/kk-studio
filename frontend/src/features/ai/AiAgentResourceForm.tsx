import { StringListEditor } from '@/features/ai/AiResourceFieldEditors'
import {
  buildCapabilityCandidates,
  markInvalidSelections,
  PLATFORM_ENVIRONMENT_NAME,
} from '@/features/ai/agent-capability-candidates'
import type { AgentDraft } from '@/features/ai/ai-console-types'
import { applyAgentModelSelection, variantOptionsFromModel } from '@/features/ai/ai-draft-normalizers'
import { emptyAgentDraft } from '@/features/ai/ai-resource-draft-codecs'
import type { AgentDefinitionDTO, AgentModelDTO, LiveEnvironmentDTO } from '@/shared/api/contracts'

function toggleName(items: string[], name: string): string[] {
  return items.includes(name) ? items.filter((item) => item !== name) : [...items, name]
}

export function AgentForm({
  draft,
  models,
  agents = [],
  environments = [],
  onChange,
}: {
  draft: AgentDraft
  models: AgentModelDTO[]
  agents?: AgentDefinitionDTO[]
  environments?: LiveEnvironmentDTO[]
  onChange: (draft: AgentDraft) => void
}) {
  const selectedModel = models.find((model) => String(model.id) === draft.modelId)
  const variantOptions = variantOptionsFromModel(selectedModel)
  const selectedVariant = variantOptions.includes(draft.variant.trim()) ? draft.variant.trim() : variantOptions[0]
  const readyEnvironments = environments.filter(
    (environment) =>
      environment.name !== PLATFORM_ENVIRONMENT_NAME && String(environment.status).toUpperCase() === 'READY',
  )
  const selectedEnvironment = environments.find((environment) => environment.name === draft.environmentName)
  const environmentMissing = Boolean(draft.environmentName.trim()) && !selectedEnvironment
  const environmentOffline =
    Boolean(selectedEnvironment) && String(selectedEnvironment?.status).toUpperCase() !== 'READY'
  const toolCandidates = buildCapabilityCandidates(environments, draft.environmentName, 'tools')
  const skillCandidates = buildCapabilityCandidates(environments, draft.environmentName, 'skills')
  const toolSelections = markInvalidSelections(draft.tools, toolCandidates)
  const skillSelections = markInvalidSelections(draft.skills, skillCandidates)
  const subagentNames = agents.map((agent) => agent.name).filter(Boolean)

  return (
    <>
      <label className="form-group">
        <span>Name</span>
        <input value={draft.name} onChange={(event) => onChange({ ...draft, name: event.target.value })} placeholder="default-assistant" required />
      </label>
      <label className="form-group">
        <span>Description</span>
        <input value={draft.description} onChange={(event) => onChange({ ...draft, description: event.target.value })} placeholder="用途说明" />
      </label>
      <label className="form-group">
        <span>Model</span>
        <select value={draft.modelId} onChange={(event) => onChange(applyAgentModelSelection(draft, event.target.value, models))} required>
          {models.map((model) => (
            <option key={String(model.id)} value={String(model.id)}>
              {model.name} ({model.providerName})
            </option>
          ))}
        </select>
      </label>
      <label className="form-group">
        <span>Variant</span>
        <select
          value={selectedVariant}
          onChange={(event) => onChange({ ...draft, variant: event.target.value })}
          disabled={models.length === 0}
        >
          {variantOptions.map((variantName) => (
            <option key={variantName} value={variantName}>
              {variantName}
            </option>
          ))}
        </select>
      </label>
      <label className="form-group">
        <span>System Prompt</span>
        <textarea value={draft.systemPrompt} onChange={(event) => onChange({ ...draft, systemPrompt: event.target.value })} placeholder="系统提示词" rows={4} />
      </label>

      <label className="form-group">
        <span>Environment</span>
        <select
          value={draft.environmentName}
          onChange={(event) => onChange({ ...draft, environmentName: event.target.value })}
        >
          <option value="">（无）</option>
          {readyEnvironments.map((environment) => (
            <option key={environment.name} value={environment.name}>
              {environment.name}
            </option>
          ))}
          {environmentMissing || environmentOffline ? (
            <option value={draft.environmentName}>
              {draft.environmentName}
              {environmentMissing ? '（缺失）' : '（离线）'}
            </option>
          ) : null}
        </select>
      </label>
      {(environmentMissing || environmentOffline) && (
        <div className="inline-hint danger" role="status">
          {environmentMissing
            ? `当前 Environment “${draft.environmentName}” 不在 live registry 中。`
            : `当前 Environment “${draft.environmentName}” 离线。`}
        </div>
      )}

      <fieldset className="form-group capability-picker">
        <legend>Tools（短名）</legend>
        <div className="capability-options">
          {toolCandidates.map((option) => (
            <label key={option.name} className="capability-option">
              <input
                type="checkbox"
                checked={draft.tools.includes(option.name)}
                onChange={() => onChange({ ...draft, tools: toggleName(draft.tools, option.name) })}
              />
              <span>
                {option.name}
                <small>
                  {option.source}
                  {option.offline ? ' · offline' : ''}
                </small>
              </span>
            </label>
          ))}
          {toolCandidates.length === 0 && <div className="inline-hint">暂无候选 Tools</div>}
        </div>
        {toolSelections.some((item) => item.invalid || item.offline) && (
          <div className="inline-hint danger" role="status">
            无效/离线 Tools：
            {toolSelections
              .filter((item) => item.invalid || item.offline)
              .map((item) => item.name)
              .join(', ')}
          </div>
        )}
      </fieldset>

      <fieldset className="form-group capability-picker">
        <legend>Skills（短名）</legend>
        <div className="capability-options">
          {skillCandidates.map((option) => (
            <label key={option.name} className="capability-option">
              <input
                type="checkbox"
                checked={draft.skills.includes(option.name)}
                onChange={() => onChange({ ...draft, skills: toggleName(draft.skills, option.name) })}
              />
              <span>
                {option.name}
                <small>
                  {option.source}
                  {option.offline ? ' · offline' : ''}
                </small>
              </span>
            </label>
          ))}
          {skillCandidates.length === 0 && <div className="inline-hint">暂无候选 Skills</div>}
        </div>
        {skillSelections.some((item) => item.invalid || item.offline) && (
          <div className="inline-hint danger" role="status">
            无效/离线 Skills：
            {skillSelections
              .filter((item) => item.invalid || item.offline)
              .map((item) => item.name)
              .join(', ')}
          </div>
        )}
      </fieldset>

      <fieldset className="form-group capability-picker">
        <legend>Allowed Subagents</legend>
        <div className="capability-options">
          {subagentNames.map((name) => (
            <label key={name} className="capability-option">
              <input
                type="checkbox"
                checked={draft.allowedSubagents.includes(name)}
                onChange={() => onChange({ ...draft, allowedSubagents: toggleName(draft.allowedSubagents, name) })}
              />
              <span>{name}</span>
            </label>
          ))}
          {subagentNames.length === 0 && <div className="inline-hint">暂无其它 Agent 可选</div>}
        </div>
        <StringListEditor
          label="额外 Subagent 短名"
          items={draft.allowedSubagents.filter((name) => !subagentNames.includes(name))}
          onChange={(extras) =>
            onChange({
              ...draft,
              allowedSubagents: [
                ...draft.allowedSubagents.filter((name) => subagentNames.includes(name)),
                ...extras,
              ],
            })
          }
          itemPlaceholder="agent short name"
        />
      </fieldset>

      <div className="form-grid-2">
        <label className="form-group">
          <span>maxTurns</span>
          <input
            value={draft.executionPolicy.maxTurns}
            onChange={(event) =>
              onChange({
                ...draft,
                executionPolicy: { ...draft.executionPolicy, maxTurns: event.target.value },
              })
            }
            placeholder="可选"
          />
        </label>
        <label className="form-group">
          <span>maxDepth</span>
          <input
            value={draft.executionPolicy.maxDepth}
            onChange={(event) =>
              onChange({
                ...draft,
                executionPolicy: { ...draft.executionPolicy, maxDepth: event.target.value },
              })
            }
            placeholder="可选"
          />
        </label>
        <label className="form-group">
          <span>maxDirectSubagents</span>
          <input
            value={draft.executionPolicy.maxDirectSubagents}
            onChange={(event) =>
              onChange({
                ...draft,
                executionPolicy: { ...draft.executionPolicy, maxDirectSubagents: event.target.value },
              })
            }
            placeholder="可选"
          />
        </label>
        <label className="form-group">
          <span>maxTotalSubagents</span>
          <input
            value={draft.executionPolicy.maxTotalSubagents}
            onChange={(event) =>
              onChange({
                ...draft,
                executionPolicy: { ...draft.executionPolicy, maxTotalSubagents: event.target.value },
              })
            }
            placeholder="可选"
          />
        </label>
      </div>

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
