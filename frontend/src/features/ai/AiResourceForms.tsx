import type { AgentModelDTO, AgentProviderDTO } from '@/shared/api/contracts'
import { StringListEditor, VariantListEditor } from '@/features/ai/AiResourceFieldEditors'
import { CapabilitiesSection, LimitsSection, PricingSection } from '@/features/ai/AiResourceMetadataSections'
import type { AgentDraft, ModelDraft, ProviderDraft, VariantDraft } from '@/features/ai/ai-console-types'
import { applyAgentModelSelection, variantOptionsFromDraft, variantOptionsFromModel } from '@/features/ai/ai-draft-normalizers'
import { emptyAgentDraft } from '@/features/ai/ai-console-utils'
import { blankVariant } from '@/features/ai/ai-resource-form-drafts'
import { providerTypes } from '@/features/ai/ai-console-types'

export function ProviderForm({
  draft,
  onChange,
}: {
  draft: ProviderDraft
  onChange: (draft: ProviderDraft) => void
}) {
  return (
    <>
      <label className="form-group">
        <span>Name</span>
        <input value={draft.name} onChange={(event) => onChange({ ...draft, name: event.target.value })} placeholder="minimax" required />
      </label>
      <label className="form-group">
        <span>Description</span>
        <input value={draft.description} onChange={(event) => onChange({ ...draft, description: event.target.value })} placeholder="用途说明" />
      </label>
      <label className="form-group">
        <span>Provider Type</span>
        <select value={draft.providerType} onChange={(event) => onChange({ ...draft, providerType: event.target.value })} required>
          {providerTypes.map((providerType) => (
            <option key={providerType} value={providerType}>
              {providerType}
            </option>
          ))}
        </select>
      </label>
      <label className="form-group">
        <span>Base URL</span>
        <input value={draft.baseUrl} onChange={(event) => onChange({ ...draft, baseUrl: event.target.value })} placeholder="https://api.example.com/v1" />
      </label>
      <label className="form-group">
        <span>API Key</span>
        <input
          type="password"
          autoComplete="off"
          value={draft.apiKey}
          onChange={(event) => onChange({ ...draft, apiKey: event.target.value })}
          placeholder="sk-..."
        />
      </label>
      <label className="form-group">
        <span>Timeout Millis</span>
        <input value={draft.timeoutMillis} onChange={(event) => onChange({ ...draft, timeoutMillis: event.target.value })} placeholder="60000" inputMode="numeric" />
      </label>
      <label className="form-group">
        <span>Stream Idle Timeout Millis</span>
        <input
          value={draft.streamIdleTimeoutMillis}
          onChange={(event) => onChange({ ...draft, streamIdleTimeoutMillis: event.target.value })}
          placeholder="60000"
          inputMode="numeric"
        />
      </label>
    </>
  )
}

export function ModelForm({
  draft,
  mode,
  providers,
  onChange,
}: {
  draft: ModelDraft
  mode: 'create' | 'edit'
  providers: AgentProviderDTO[]
  onChange: (draft: ModelDraft) => void
}) {
  const variantOptions = variantOptionsFromDraft(draft.variants, draft.defaultVariant)
  const selectedDefaultVariant = variantOptions.includes(draft.defaultVariant.trim()) ? draft.defaultVariant.trim() : variantOptions[0]

  function commitVariants(nextVariants: VariantDraft[], preferredDefaultVariant?: string) {
    const nextOptions = variantOptionsFromDraft(nextVariants, preferredDefaultVariant || draft.defaultVariant)
    const preferred = preferredDefaultVariant?.trim() || draft.defaultVariant.trim()
    onChange({
      ...draft,
      variants: nextVariants,
      defaultVariant: preferred && nextOptions.includes(preferred) ? preferred : nextOptions[0],
    })
  }

  return (
    <>
      <label className="form-group">
        <span>Provider</span>
        <select value={draft.provider} onChange={(event) => onChange({ ...draft, provider: event.target.value })} required disabled={mode === 'edit'}>
          {providers.map((provider) => (
            <option key={provider.id} value={provider.name}>
              {provider.name}
            </option>
          ))}
        </select>
      </label>
      <label className="form-group">
        <span>Name</span>
        <input value={draft.name} onChange={(event) => onChange({ ...draft, name: event.target.value })} placeholder="MiniMax-M2.7" required />
      </label>
      <label className="form-group">
        <span>Description</span>
        <input value={draft.description} onChange={(event) => onChange({ ...draft, description: event.target.value })} placeholder="模型说明" />
      </label>
      <label className="form-group">
        <span>Default Variant</span>
        <select value={selectedDefaultVariant} onChange={(event) => onChange({ ...draft, defaultVariant: event.target.value })}>
          {variantOptions.map((variantName) => (
            <option key={variantName} value={variantName}>
              {variantName}
            </option>
          ))}
        </select>
      </label>

      <VariantListEditor
        label="Variants"
        variants={draft.variants}
        defaultVariant={selectedDefaultVariant}
        onChange={commitVariants}
        onResetDefault={() => commitVariants([blankVariant(selectedDefaultVariant || 'default')], selectedDefaultVariant)}
      />
      <CapabilitiesSection entries={draft.capabilities} onChange={(capabilities) => onChange({ ...draft, capabilities })} />
      <LimitsSection entries={draft.limits} onChange={(limits) => onChange({ ...draft, limits })} />
      <PricingSection entries={draft.pricing} onChange={(pricing) => onChange({ ...draft, pricing })} />
    </>
  )
}

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
