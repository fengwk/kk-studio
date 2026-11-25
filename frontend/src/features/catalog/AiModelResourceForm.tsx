import { useId, useLayoutEffect, useState } from 'react'
import type { ModelDraft } from '@/features/catalog/ai-console-types'
import { variantOptionsFromDraft } from '@/features/catalog/ai-draft-normalizers'
import type { ResourceFieldKey } from '@/features/catalog/ai-resource-form-validation'
import { VariantListEditor } from '@/features/catalog/AiVariantListEditor'
import { ModelPricingPlanEditor } from '@/features/catalog/ModelPricingPlanEditor'
import { CatalogEditorSection } from '@/features/catalog/CatalogEditorSection'
import { ModelCatalogPicker } from '@/features/catalog/AiModelCatalogPicker'
import { applyCatalogCandidate } from '@/features/catalog/ai-model-catalog-apply'
import { getModelCapabilities } from '@/features/catalog/ai-model-capabilities'
import { toUserFacingErrorMessage } from '@/shared/lib/user-facing-error'
import type { ModelCapabilitiesDTO, ProviderDTO } from '@/shared/api/generated/contracts'
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
import { FieldError } from '@/shared/ui/controls/FieldError'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Select } from '@/shared/ui/controls/Select'
import { joinDescribedBy, useFieldErrorId } from '@/shared/ui/controls/field-error'
import { useI18n } from '@/shared/i18n'
import { RemoteModelIdPicker } from '@/features/catalog/AiRemoteModelIdPicker'
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
  const providerName = draft.providerName.trim()
  const modelId = draft.modelId.trim()
  const activeProvider = providers.find((item) => item.name === providerName)
  const [attempt, setAttempt] = useState(0)
  const requestKey = activeProvider && modelId ? JSON.stringify([
    providerName, activeProvider.createdAt, activeProvider.version,
    activeProvider.providerType, activeProvider.baseUrl, modelId, attempt,
  ]) : null
  const [result, setResult] = useState<({ key: string } & (
    { capabilities: ModelCapabilitiesDTO; error?: never } | { capabilities?: never; error: string }
  )) | null>(null)
  const currentResult = result?.key === requestKey ? result : null
  const modelCapabilities = currentResult?.capabilities

  // Invalidate in the same commit as the identity change, including when the model ID is cleared.
  useLayoutEffect(() => {
    setResult(null)
    if (!requestKey) return
    const controller = new AbortController()
    void getModelCapabilities(providerName, modelId, controller.signal).then((capabilities) => {
      if (!controller.signal.aborted) setResult({ key: requestKey, capabilities })
    }).catch((error: unknown) => {
      if (!controller.signal.aborted) setResult({ key: requestKey, error: toUserFacingErrorMessage(error) })
    })
    return () => controller.abort()
  }, [requestKey, providerName, modelId])

  useLayoutEffect(() => {
    if (!modelCapabilities || modelCapabilities.defaultPromptCacheRetention == null) return
    const defaultRetention = modelCapabilities.defaultPromptCacheRetention
    const supported = new Set(modelCapabilities.promptCacheOptions.map((option) => option.retention))
    const variants = draft.variants.map((variant) => {
      const retention = variant.promptCacheRetention
      // Edit mode only initializes new, unset variants; stored selections need an explicit change.
      const needsDefault = retention === '' || (mode === 'create' && !supported.has(retention))
      return needsDefault && retention !== defaultRetention
        ? { ...variant, promptCacheRetention: defaultRetention }
        : variant
    })
    if (variants.some((variant, index) => variant !== draft.variants[index])) {
      onChange({ ...draft, variants })
    }
  }, [modelCapabilities, draft, mode, onChange])
  const defaultVariant = selectedDefaultVariant(draft)
  const nameId = useId()
  const modelIdFieldId = useId()
  const providerNameErrorId = useFieldErrorId(fieldErrors.providerName)
  const nameErrorId = useFieldErrorId(fieldErrors.name)
  const modelIdErrorId = useFieldErrorId(fieldErrors.modelId)
  const defaultVariantErrorId = useFieldErrorId(fieldErrors.defaultVariant)

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
      <CatalogEditorSection name="basics">
        <div className={`form-group${fieldErrors.providerName ? ' is-error' : ''}`}>
          <FieldLabel required>{t('ai.catalog.form.provider')}</FieldLabel>
          <Select
            aria-label={t('ai.catalog.form.provider')}
            aria-describedby={joinDescribedBy(
              provider.unavailable ? 'model-provider-identity-status' : undefined,
              providerNameErrorId,
            )}
            aria-invalid={Boolean(fieldErrors.providerName)}
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
          {fieldErrors.providerName ? (
            <FieldError id={providerNameErrorId}>{fieldErrors.providerName}</FieldError>
          ) : null}
        </div>

        <div className={`form-group${fieldErrors.name ? ' is-error' : ''}`}>
          <FieldLabel required htmlFor={nameId}>{t('ai.catalog.form.name')}</FieldLabel>
          <TextInput
            id={nameId}
            aria-label={t('ai.catalog.form.name')}
            invalid={Boolean(fieldErrors.name)}
            aria-describedby={nameErrorId}
            value={draft.name}
            onChange={(event) => onChange({ ...draft, name: event.target.value })}
            placeholder="MiniMax-M2.7"
            required
          />
          {fieldErrors.name ? <FieldError id={nameErrorId}>{fieldErrors.name}</FieldError> : null}
        </div>

        <div className={`form-group${fieldErrors.modelId ? ' is-error' : ''}`}>
          <FieldLabel required htmlFor={modelIdFieldId}>{t('ai.catalog.form.modelId')}</FieldLabel>
          <TextInput
            id={modelIdFieldId}
            aria-label={t('ai.catalog.form.modelId')}
            invalid={Boolean(fieldErrors.modelId)}
            aria-describedby={modelIdErrorId}
            value={draft.modelId}
            onChange={(event) => setModelId(event.target.value)}
            placeholder="minimax-m2.7"
            list={mode === 'create' ? 'model-remote-ids' : undefined}
            required
          />
          {mode === 'create' ? (
            <RemoteModelIdPicker
              listId="model-remote-ids"
              providerName={draft.providerName}
              load={loadRemoteModelIds}
            />
          ) : null}
          {fieldErrors.modelId ? (
            <FieldError id={modelIdErrorId}>{fieldErrors.modelId}</FieldError>
          ) : null}
        </div>

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
            variant="secondary"
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
      </CatalogEditorSection>

      <CatalogEditorSection name="limits">
        <ModelLimitFields draft={draft} fieldErrors={fieldErrors} onChange={onChange} />
        <ModelAbilityToggles draft={draft} onChange={onChange} />
        <ModelInputModalityField
          draft={draft}
          error={fieldErrors.inputModalities}
          onChange={onChange}
        />
      </CatalogEditorSection>

      <CatalogEditorSection name="presets">
        <div className={`form-group${fieldErrors.defaultVariant ? ' is-error' : ''}`}>
          <FieldLabel required>{t('ai.catalog.form.defaultVariant')}</FieldLabel>
          <Select
            aria-label={t('ai.catalog.form.defaultVariant')}
            aria-describedby={defaultVariantErrorId}
            aria-invalid={Boolean(fieldErrors.defaultVariant)}
            value={defaultVariant}
            options={variantOptions.map((name) => ({ value: name, label: name }))}
            required
            onChange={(variant) => onChange({ ...draft, defaultVariant: variant })}
          />
          {fieldErrors.defaultVariant ? (
            <FieldError id={defaultVariantErrorId}>{fieldErrors.defaultVariant}</FieldError>
          ) : null}
        </div>

        <div className={fieldErrors.variants ? 'is-error' : undefined}>
          <VariantListEditor
            label={t('ai.catalog.form.presetList')}
            variants={draft.variants}
            defaultVariant={defaultVariant}
            reasoning={draft.reasoning}
            capabilities={modelCapabilities}
            capabilitiesLoading={requestKey !== null && !currentResult}
            capabilitiesError={currentResult?.error}
            onRetryCapabilities={() => setAttempt((value) => value + 1)}
            onChange={(nextVariants, preferred) =>
              onChange({ ...draft, ...variantSelection(draft, nextVariants, preferred) })
            }
          />
          {/* 只保留一条汇总错误，避免顶部 banner + 字段旁重复堆叠 */}
          {fieldErrors.variants ? <span className="field-error">{fieldErrors.variants}</span> : null}
        </div>
      </CatalogEditorSection>

      <CatalogEditorSection name="pricing">
        {/* 价格错误放在分组顶部，供编辑器外壳导航定位 */}
        {fieldErrors.pricing ? <span className="field-error">{fieldErrors.pricing}</span> : null}
        <ModelPricingPlanEditor
          value={draft.pricing}
          pricingImportNotice={draft.pricingImportNotice}
          onChange={(pricing) => onChange({ ...draft, pricing })}
        />
      </CatalogEditorSection>
    </>
  )
}
