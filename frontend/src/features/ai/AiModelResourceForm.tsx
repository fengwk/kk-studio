import { useMemo } from 'react'
import { FormSelect } from '@/features/ai/FormSelect'
import { VariantListEditor } from '@/features/ai/AiResourceFieldEditors'
import type { ModelDraft, VariantDraft } from '@/features/ai/ai-console-types'
import { applyKnownModelDefaults } from '@/features/ai/ai-model-draft-codec'
import { variantOptionsFromDraft } from '@/features/ai/ai-draft-normalizers'
import { findKnownModelDefault, knownModelNames } from '@/features/ai/known-model-catalog'
import type { AgentProviderDTO } from '@/shared/api/contracts'

/** Align with harness ModelInputModality + LangChain4j multimodal inputs. */
const MODALITIES = ['TEXT', 'IMAGE', 'AUDIO', 'VIDEO', 'DOCUMENT'] as const
/** Align with harness ModelCapability. */
const CAPABILITIES = ['TEXT', 'VISION', 'AUDIO', 'TOOLS', 'THINKING'] as const

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
  const selectedDefaultVariant = variantOptions.includes(draft.defaultVariant.trim())
    ? draft.defaultVariant.trim()
    : variantOptions[0]
  const known = useMemo(() => findKnownModelDefault(draft.name), [draft.name])

  function commitVariants(nextVariants: VariantDraft[], preferredDefaultVariant?: string) {
    const nextOptions = variantOptionsFromDraft(nextVariants, preferredDefaultVariant || draft.defaultVariant)
    const preferred = preferredDefaultVariant?.trim() || draft.defaultVariant.trim()
    onChange({
      ...draft,
      variants: nextVariants,
      defaultVariant: preferred && nextOptions.includes(preferred) ? preferred : nextOptions[0],
    })
  }

  function toggleList(list: string[], value: string): string[] {
    return list.includes(value) ? list.filter((item) => item !== value) : [...list, value]
  }

  return (
    <>
      <label className="form-group">
        <span>Provider</span>
        <FormSelect
          aria-label="Provider"
          value={draft.providerId}
          required
          disabled={mode === 'edit'}
          options={providers.map((provider) => ({ value: String(provider.id), label: provider.name }))}
          onChange={(providerId) => onChange({ ...draft, providerId })}
        />
      </label>

      <label className="form-group">
        <span>Name</span>
        <input
          list="known-model-names"
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          onBlur={() => {
            if (findKnownModelDefault(draft.name)) {
              onChange(applyKnownModelDefaults(draft))
            }
          }}
          placeholder="MiniMax-M2.7"
          required
        />
        <datalist id="known-model-names">
          {knownModelNames().map((name) => (
            <option key={name} value={name} />
          ))}
        </datalist>
      </label>

      {known ? (
        <div className="inline-hint" role="status">
          已匹配已知模型目录「{known.id}」。可点击下方「恢复目录默认」加载 context / thinking profiles。
        </div>
      ) : (
        <div className="inline-hint">未匹配已知模型时使用通用默认；可手动编辑全部字段。</div>
      )}

      <div className="structured-section-actions" style={{ marginBottom: 8 }}>
        <button
          className="ghost-inline-btn"
          type="button"
          onClick={() => onChange(applyKnownModelDefaults(draft))}
        >
          恢复目录默认
        </button>
      </div>

      <label className="form-group">
        <span>Description</span>
        <input
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          placeholder="模型说明"
        />
      </label>

      <div className="form-grid-2">
        <label className="form-group">
          <span>Context Window</span>
          <input
            value={draft.contextWindow}
            onChange={(event) => onChange({ ...draft, contextWindow: event.target.value })}
            placeholder="200000"
            inputMode="numeric"
            required
          />
        </label>
        <label className="form-group">
          <span>Max Output Tokens</span>
          <input
            value={draft.maxOutputTokens}
            onChange={(event) => onChange({ ...draft, maxOutputTokens: event.target.value })}
            placeholder="8192"
            inputMode="numeric"
            required
          />
        </label>
      </div>

      <label className="form-group checkbox-field">
        <input
          type="checkbox"
          checked={draft.reasoning}
          onChange={(event) => onChange({ ...draft, reasoning: event.target.checked })}
        />
        <span>支持 Reasoning / Thinking</span>
      </label>

      <fieldset className="form-group capability-picker">
        <legend>Input Modalities</legend>
        <p className="inline-hint">
          按模型实际输入能力勾选（参考 LangChain4j：text / image / audio / video / PDF·document），不限于 pi
          catalog。
        </p>
        <div className="capability-options">
          {MODALITIES.map((item) => {
            const checked = draft.inputModalities.includes(item)
            return (
              <label key={item} className={`capability-option${checked ? ' is-selected' : ''}`}>
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => onChange({ ...draft, inputModalities: toggleList(draft.inputModalities, item) })}
                />
                <span>{item}</span>
              </label>
            )
          })}
        </div>
      </fieldset>

      <fieldset className="form-group capability-picker">
        <legend>Capabilities</legend>
        <div className="capability-options">
          {CAPABILITIES.map((item) => {
            const checked = draft.capabilities.includes(item)
            return (
              <label key={item} className={`capability-option${checked ? ' is-selected' : ''}`}>
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => onChange({ ...draft, capabilities: toggleList(draft.capabilities, item) })}
                />
                <span>{item}</span>
              </label>
            )
          })}
        </div>
      </fieldset>

      <label className="form-group">
        <span>Default Profile</span>
        <FormSelect
          aria-label="Default Profile"
          value={selectedDefaultVariant}
          options={variantOptions.map((name) => ({ value: name, label: name }))}
          onChange={(defaultVariant) => onChange({ ...draft, defaultVariant })}
        />
      </label>

      <VariantListEditor
        label="Thinking Profiles"
        variants={draft.variants}
        defaultVariant={selectedDefaultVariant}
        onChange={commitVariants}
      />
    </>
  )
}
