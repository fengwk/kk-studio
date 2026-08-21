import { useQuery } from '@tanstack/react-query'
import { formatModelRef, modelRef, toAgentModelViews } from '@/features/ai/catalog/AgentModelView'
import { variantOptionsFromModel } from '@/features/ai/catalog/ai-draft-variant-options'
import { extractDefaultVariantFromModel } from '@/features/ai/catalog/ai-model-draft-codec'
import { SettingsSelectField } from '@/features/settings/settings-primitives'
import type { ModelSelectionDraft } from '@/features/settings/system-settings-draft'
import { agentService } from '@/shared/api/agent-service'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'

const EMPTY_SELECTION: ModelSelectionDraft = {
  providerName: '',
  modelName: '',
  variant: '',
}

/**
 * 可空 model selection：与 Agent 表单相同，从 catalog 选择 model + variant。
 * 空选择始终写成 null；选中 model 时写入其 defaultVariant，避免部分填写。
 */
export function ModelSelectionEditor({
  value,
  path,
  labelKey,
  hint,
  nullable,
  onChange,
}: {
  value: ModelSelectionDraft | null
  path: string
  labelKey: string
  hint: string | null
  nullable: boolean
  onChange: (next: ModelSelectionDraft | null) => void
}) {
  const { t } = useI18n()
  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
  })
  const models = toAgentModelViews(modelsQuery.data?.results ?? [])
  const selection = value ?? EMPTY_SELECTION
  const currentRef =
    selection.providerName.trim() && selection.modelName.trim()
      ? formatModelRef(selection.providerName, selection.modelName)
      : ''
  const selectedModel = models.find((model) => modelRef(model) === currentRef)
  const modelUnavailable = currentRef !== '' && selectedModel === undefined
  const variantIds = variantOptionsFromModel(selectedModel)
  const variantUnavailable =
    selection.variant.trim() !== '' && !variantIds.includes(selection.variant.trim())

  const modelOptions = [
    { value: '', label: t('settings.modelSelection.none') },
    ...(modelUnavailable
      ? [
          {
            value: currentRef,
            label: `${currentRef} (${t('ai.catalog.form.unavailable')})`,
          },
        ]
      : []),
    ...models.map((model) => ({ value: modelRef(model), label: modelRef(model) })),
  ]
  const variantOptions = [
    ...(variantUnavailable
      ? [
          {
            value: selection.variant,
            label: `${selection.variant} (${t('ai.catalog.form.unavailable')})`,
          },
        ]
      : []),
    ...variantIds.map((variantName) => ({ value: variantName, label: variantName })),
  ]

  const selectModel = (nextRef: string) => {
    if (nextRef === '') {
      onChange(null)
      return
    }
    const model = models.find((item) => modelRef(item) === nextRef)
    if (model == null) {
      return
    }
    const variants = variantOptionsFromModel(model)
    onChange({
      providerName: model.providerName,
      modelName: model.name,
      variant: extractDefaultVariantFromModel(model) || variants[0] || '',
    })
  }

  return (
    <div
      className="settings-field settings-model-selection"
      data-settings-field-path={path}
      data-settings-nullable={nullable}
    >
      <div className="settings-field-label">{t(labelKey)}</div>
      {hint ? <span className="settings-field-description">{hint}</span> : null}
      <div className="settings-model-selection-fields">
        <SettingsSelectField
          label={t(`${labelKey}.modelName`)}
          value={currentRef}
          options={modelOptions}
          onChange={selectModel}
        />
        <SettingsSelectField
          label={t(`${labelKey}.variant`)}
          value={selection.variant}
          options={variantOptions}
          disabled={currentRef === '' || (selectedModel === undefined && !variantUnavailable)}
          placeholder={t('shared.selectPlaceholder')}
          onChange={(variant) => onChange({ ...selection, variant })}
        />
      </div>
    </div>
  )
}
