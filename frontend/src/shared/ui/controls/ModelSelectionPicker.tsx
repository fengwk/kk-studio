import { useId } from 'react'
import type { ModelDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { Select, type SelectOption } from '@/shared/ui/controls/Select'
import { variantOptionsFromModel } from '@/shared/lib/model-variant-options'

export function ModelSelectionPicker({
  models,
  modelName,
  variant,
  onModelChange,
  onVariantChange,
  allowModelGroup,
  showVariant,
  allowUnsetModel = false,
  unsetModelLabel,
  variantUnsetLabel,
  modelLabel,
  variantLabel,
  variantFieldLabel,
  modelPlaceholder,
  variantPlaceholder,
  modelDisabled = false,
  variantDisabled = false,
  modelUnavailable = false,
  variantUnavailable = false,
  modelRequired = false,
  modelDescribedBy,
  variantDescribedBy,
  modelId,
}: {
  models: ModelDTO[]
  modelName: string
  variant: string
  onModelChange: (modelName: string) => void
  onVariantChange: (variant: string) => void
  allowModelGroup: boolean
  showVariant: boolean
  allowUnsetModel?: boolean
  unsetModelLabel?: string
  variantUnsetLabel?: string
  modelLabel: string
  variantLabel: string
  variantFieldLabel?: string
  modelPlaceholder?: string
  variantPlaceholder?: string
  modelDisabled?: boolean
  variantDisabled?: boolean
  modelUnavailable?: boolean
  variantUnavailable?: boolean
  modelRequired?: boolean
  modelDescribedBy?: string
  variantDescribedBy?: string
  modelId?: string
}) {
  const { t } = useI18n()
  const generatedId = useId()
  const modelFieldId = modelId ?? generatedId
  const selectedModel = models.find((model) => model.name === modelName)
  const modelMissing = Boolean(modelName) && selectedModel === undefined
  const variants = variantOptionsFromModel(selectedModel)
  const variantMissing = Boolean(variant) && !variants.includes(variant)
  const modelOptions: SelectOption[] = [
    ...(allowUnsetModel ? [{ value: '', label: unsetModelLabel ?? t('settings.field.modelSelection.unset') }] : []),
    ...(modelMissing
      ? [{
          value: modelName,
          label: `${modelName} · ${t('ai.catalog.form.unavailable')}`,
          disabled: modelUnavailable,
        }]
      : []),
    ...models
      .filter((model) => allowModelGroup || model.type === 'MODEL')
      .map((model) => ({ value: model.name, label: model.name })),
  ]
  const variantOptions: SelectOption[] = [
    ...(variantUnsetLabel ? [{ value: '', label: variantUnsetLabel }] : []),
    ...(variantMissing
      ? [{
          value: variant,
          label: `${variant} · ${t('ai.catalog.form.unavailable')}`,
          disabled: variantUnavailable,
        }]
      : []),
    ...variants.map((item) => ({ value: item, label: item })),
  ]

  return (
    <>
      <Select
        id={modelFieldId}
        aria-label={modelLabel}
        aria-describedby={modelDescribedBy}
        value={modelName}
        placeholder={modelPlaceholder}
        options={modelOptions}
        disabled={modelDisabled}
        required={modelRequired}
        onChange={onModelChange}
      />
      {/* 只有已选的具体模型才有变体；未选模型或选中模型组时不显示。 */}
      {showVariant && modelName && selectedModel?.type !== 'GROUP' ? (
        <>
          {variantFieldLabel ? (
            <span className="model-selection-variant-label">{variantFieldLabel}</span>
          ) : null}
          <Select
            aria-label={variantLabel}
            aria-describedby={variantDescribedBy}
            value={variant}
            placeholder={variantPlaceholder}
            options={variantOptions}
            disabled={variantDisabled}
            onChange={onVariantChange}
          />
        </>
      ) : null}
    </>
  )
}
