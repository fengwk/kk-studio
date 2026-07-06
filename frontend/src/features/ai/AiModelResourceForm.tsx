import { VariantListEditor } from '@/features/ai/AiResourceFieldEditors'
import { CapabilitiesSection, LimitsSection, PricingSection } from '@/features/ai/AiResourceMetadataSections'
import type { ModelDraft, VariantDraft } from '@/features/ai/ai-console-types'
import { variantOptionsFromDraft } from '@/features/ai/ai-draft-normalizers'
import { blankVariant } from '@/features/ai/ai-resource-form-drafts'
import type { AgentProviderDTO } from '@/shared/api/contracts'

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
