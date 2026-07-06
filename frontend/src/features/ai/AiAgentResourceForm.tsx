import { StringListEditor } from '@/features/ai/AiResourceFieldEditors'
import type { AgentDraft } from '@/features/ai/ai-console-types'
import { applyAgentModelSelection, variantOptionsFromModel } from '@/features/ai/ai-draft-normalizers'
import { emptyAgentDraft } from '@/features/ai/ai-resource-draft-codecs'
import type { AgentModelDTO } from '@/shared/api/contracts'

export function AgentForm({
  draft,
  models,
  onChange,
}: {
  draft: AgentDraft
  models: AgentModelDTO[]
  onChange: (draft: AgentDraft) => void
}) {
  const selectedModel = models.find((model) => model.providerName === draft.defaultProvider && model.name === draft.defaultModel)
  const variantOptions = variantOptionsFromModel(selectedModel)
  const selectedDefaultVariant = variantOptions.includes(draft.defaultVariant.trim()) ? draft.defaultVariant.trim() : variantOptions[0]

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
        <select value={`${draft.defaultProvider}/${draft.defaultModel}`} onChange={(event) => onChange(applyAgentModelSelection(draft, event.target.value, models))} required>
          {models.map((model) => (
            <option key={`${model.providerName}/${model.name}`} value={`${model.providerName}/${model.name}`}>
              {model.name} ({model.providerName})
            </option>
          ))}
        </select>
      </label>
      <label className="form-group">
        <span>Default Variant</span>
        <select value={selectedDefaultVariant} onChange={(event) => onChange({ ...draft, defaultVariant: event.target.value })} disabled={models.length === 0}>
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

      <StringListEditor label="Tools" items={draft.tools} onChange={(tools) => onChange({ ...draft, tools })} itemPlaceholder="tool name" />
      <StringListEditor label="Subagents" items={draft.subagents} onChange={(subagents) => onChange({ ...draft, subagents })} itemPlaceholder="subagent name" />
      <StringListEditor label="Skills" items={draft.skills} onChange={(skills) => onChange({ ...draft, skills })} itemPlaceholder="skill name" />

      {models.length === 0 && (
        <div className="inline-hint" role="status">
          需要先创建 Model 才能配置 Agent。
        </div>
      )}
      {models.length > 0 && !draft.defaultModel && (
        <button className="ghost-inline-btn" type="button" onClick={() => onChange(emptyAgentDraft(models[0]))}>
          使用第一个 Model 填充默认配置
        </button>
      )}
    </>
  )
}
