import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { ModelDraft } from '@/features/catalog/ai-console-types'
import { variantOptionsFromDraft } from '@/features/catalog/ai-draft-normalizers'
import type { ResourceFieldKey } from '@/features/catalog/ai-resource-form-validation'
import { VariantListEditor } from '@/features/catalog/AiVariantListEditor'
import { ModelPricingPlanEditor } from '@/features/catalog/ModelPricingPlanEditor'
import { ModelCatalogPicker } from '@/features/catalog/AiModelCatalogPicker'
import { applyCatalogCandidate } from '@/features/catalog/ai-model-catalog-apply'
import { getModelCapabilities } from '@/features/catalog/ai-model-capabilities'
import { toUserFacingErrorMessage } from '@/shared/lib/user-facing-error'
import type { ModelCapabilitiesDTO } from '@/shared/api/generated/contracts'
import {
  ModelAbilityToggles,
  ModelInputModalityField,
  ModelLimitFields,
} from '@/features/catalog/ai-model-form-sections'
import {
  providerOptionsFor,
  selectedDefaultVariant,
  variantSelection,
} from '@/features/catalog/ai-model-form-rules'
import { Button } from '@/shared/ui/controls/Button'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Select } from '@/shared/ui/controls/Select'
import { useI18n } from '@/shared/i18n'
import { RemoteModelIdPicker } from '@/features/catalog/AiRemoteModelIdPicker'
import type { ProviderDTO } from '@/shared/api/generated/contracts'
import './capability-picker.css'
import './catalog-form-layout.css'
import './ai-model-resource-form.css'

export function ModelForm({
  draft,
  mode,
  providers,
  fieldErrors = {},
  onChange,
  loadRemoteModelIds,
}: {
  draft: ModelDraft
  mode: 'create' | 'edit'
  providers: ProviderDTO[]
  fieldErrors?: Partial<Record<ResourceFieldKey, string>>
  onChange: (draft: ModelDraft) => void
  loadRemoteModelIds?: (providerName: string) => Promise<string[]>
}) {
  const { t } = useI18n()
  const [catalogOpen, setCatalogOpen] = useState(false)
  const variantOptions = variantOptionsFromDraft(draft.variants)
  const provider = providerOptionsFor(providers, draft, mode, t('ai.catalog.form.unavailable'))
  const [modelCapabilities, setModelCapabilities] = useState<ModelCapabilitiesDTO | null>(null)
  const [capabilitiesLoading, setCapabilitiesLoading] = useState(false)
  const [capabilitiesError, setCapabilitiesError] = useState<string | undefined>()
  const draftRef = useRef(draft)
  const onChangeRef = useRef(onChange)
  useLayoutEffect(() => {
    draftRef.current = draft
    onChangeRef.current = onChange
  }, [draft, onChange])

  useEffect(() => {
    const { providerName, modelId } = draftRef.current
    if (!providerName.trim() || !modelId.trim()) {
      setModelCapabilities(null)
      setCapabilitiesLoading(false)
      setCapabilitiesError(undefined)
      return
    }
    const controller = new AbortController()
    setModelCapabilities(null)
    setCapabilitiesLoading(true)
    setCapabilitiesError(undefined)
    void getModelCapabilities(providerName, modelId, controller.signal).then((capabilities) => {
      if (controller.signal.aborted) return
      setModelCapabilities(capabilities)
      if (mode === 'create') {
        const current = draftRef.current
        const supported = new Set(capabilities.promptCacheOptions.map((option) => option.retention))
        const defaultRetention = capabilities.defaultPromptCacheRetention ?? 'NONE'
        const variants = current.variants.map((variant) =>
          variant.promptCacheRetention === '' || !supported.has(variant.promptCacheRetention)
            ? { ...variant, promptCacheRetention: defaultRetention }
            : variant,
        )
        if (variants.some((variant, index) => variant !== current.variants[index])) {
          onChangeRef.current({ ...current, variants })
        }
      }
    }).catch((error: unknown) => {
      if (!controller.signal.aborted) setCapabilitiesError(toUserFacingErrorMessage(error))
    }).finally(() => {
      if (!controller.signal.aborted) setCapabilitiesLoading(false)
    })
    return () => controller.abort()
  }, [draft.providerName, draft.modelId, mode])
  const defaultVariant = selectedDefaultVariant(draft)

  /** 新建时 Name 跟随 Model ID，直到用户单独编辑 Name。 */
  function setModelId(modelId: string) {
    const nameFollows = mode === 'create' && (!draft.name.trim() || draft.name === draft.modelId)
    onChange({
      ...draft,
      modelId,
      name: nameFollows ? modelId : draft.name,
    })
  }

  return (
    <>
      <label className={`form-group${fieldErrors.providerName ? ' is-error' : ''}`}>
        <FieldLabel required>{t('ai.catalog.form.provider')}</FieldLabel>
        <Select
          aria-label={t('ai.catalog.form.provider')}
          aria-describedby={provider.unavailable ? 'model-provider-identity-status' : undefined}
          value={draft.providerName}
          required
          disabled={mode === 'edit'}
          options={provider.options}
          onChange={(providerName) =>
            onChange({
              ...draft,
              providerName,
            })
          }
        />
        {provider.unavailable ? (
          <span id="model-provider-identity-status" className="inline-hint" role="status">
            {t('ai.catalog.form.unavailableIdentityHint')}
          </span>
        ) : null}
        {fieldErrors.providerName ? <span className="field-error">{fieldErrors.providerName}</span> : null}
      </label>

      <label className={`form-group${fieldErrors.name ? ' is-error' : ''}`}>
        <FieldLabel required>{t('ai.catalog.form.name')}</FieldLabel>
        <TextInput
          invalid={Boolean(fieldErrors.name)}
          aria-label={t('ai.catalog.form.name')}
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          placeholder="MiniMax-M2.7"
          required
        />
        <span className="inline-hint">{t('ai.catalog.form.nameHint')}</span>
        {fieldErrors.name ? <span className="field-error">{fieldErrors.name}</span> : null}
      </label>

      <label className={`form-group${fieldErrors.modelId ? ' is-error' : ''}`}>
        <FieldLabel required>{t('ai.catalog.form.modelId')}</FieldLabel>
        <TextInput
          invalid={Boolean(fieldErrors.modelId)}
          aria-label={t('ai.catalog.form.modelId')}
          value={draft.modelId}
          onChange={(event) => setModelId(event.target.value)}
          placeholder="minimax-m2.7"
          list={mode === 'create' ? 'model-remote-ids' : undefined}
          required
        />
        <span className="inline-hint">{t('ai.catalog.form.modelIdHint')}</span>
        {mode === 'create' ? (
          <RemoteModelIdPicker
            listId="model-remote-ids"
            providerName={draft.providerName}
            load={loadRemoteModelIds}
          />
        ) : null}
        {fieldErrors.modelId ? <span className="field-error">{fieldErrors.modelId}</span> : null}
      </label>

      <label className="form-group">
        <FieldLabel>{t('ai.catalog.form.description')}</FieldLabel>
        <TextInput
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          placeholder={t('ai.catalog.form.modelDescriptionPlaceholder')}
        />
      </label>

      <div className="catalog-picker-launcher">
        <Button
          variant="inline"
          aria-expanded={catalogOpen}
          onClick={() => setCatalogOpen((current) => !current)}
        >
          {catalogOpen ? t('ai.catalog.catalog.hide') : t('ai.catalog.catalog.open')}
        </Button>
      </div>
      {catalogOpen ? (
        <ModelCatalogPicker
          onApply={(candidate) => {
            onChange(applyCatalogCandidate(draft, candidate))
            setCatalogOpen(false)
          }}
        />
      ) : null}

      <ModelLimitFields draft={draft} fieldErrors={fieldErrors} onChange={onChange} />
      <ModelAbilityToggles draft={draft} onChange={onChange} />
      <ModelInputModalityField
        draft={draft}
        error={fieldErrors.inputModalities}
        onChange={onChange}
      />

      <ModelPricingPlanEditor
        value={draft.pricing}
        pricingImportNotice={draft.pricingImportNotice}
        onChange={(pricing) => onChange({ ...draft, pricing })}
      />
      {fieldErrors.pricing ? <span className="field-error">{fieldErrors.pricing}</span> : null}

      <label className={`form-group${fieldErrors.defaultVariant ? ' is-error' : ''}`}>
        <FieldLabel required>{t('ai.catalog.form.defaultVariant')}</FieldLabel>
        <Select
          aria-label={t('ai.catalog.form.defaultVariant')}
          value={defaultVariant}
          options={variantOptions.map((name) => ({ value: name, label: name }))}
          onChange={(variant) => onChange({ ...draft, defaultVariant: variant })}
        />
        {fieldErrors.defaultVariant ? (
          <span className="field-error">{fieldErrors.defaultVariant}</span>
        ) : null}
      </label>

      <div className={fieldErrors.variants ? 'is-error' : undefined}>
        <VariantListEditor
          label={t('ai.catalog.form.variants')}
          variants={draft.variants}
          defaultVariant={defaultVariant}
          reasoning={draft.reasoning}
          capabilities={modelCapabilities}
          capabilitiesLoading={capabilitiesLoading}
          capabilitiesError={capabilitiesError}
          onChange={(nextVariants, preferred) =>
            onChange({ ...draft, ...variantSelection(draft, nextVariants, preferred) })
          }
        />
        {/* 只保留一条汇总错误，避免顶部 banner + 字段旁重复堆叠 */}
        {fieldErrors.variants ? <span className="field-error">{fieldErrors.variants}</span> : null}
      </div>
    </>
  )
}
