import { useQuery } from '@tanstack/react-query'
import { useId } from 'react'
import { variantOptionsFromModel } from '@/features/catalog/ai-draft-variant-options'
import type { ModelSelectionDraft } from '@/features/settings/system-settings-draft'
import { agentService } from '@/shared/api/agent-service'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { Select } from '@/shared/ui/controls/Select'

/**
 * 可空的模型引用：选择模型或模型组，单模型可再选变体（模型组没有变体）。
 *
 * <p>空模型名表示未设置。当前值不在模型目录中时显示为不可用，仍可清空或改选。
 */
export function SettingsModelSelectionField({
  label,
  hint,
  unsetLabel,
  fieldPath,
  nullable,
  value,
  onChange,
}: {
  label: string
  hint?: string
  unsetLabel: string
  fieldPath: string
  nullable: boolean
  value: ModelSelectionDraft
  onChange: (next: ModelSelectionDraft) => void
}) {
  const { t } = useI18n()
  const modelFieldId = useId()
  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
  })
  const models = modelsQuery.data ?? []
  const selected = models.find((model) => model.name === value.modelName)
  const unavailableLabel = t('ai.catalog.form.unavailable')
  // 目录加载完成后仍找不到当前引用才算不可用；加载中只原样展示名字。
  const modelMissing = value.modelName !== '' && selected === undefined
  const modelUnavailable = modelMissing && modelsQuery.isSuccess
  const variants = variantOptionsFromModel(selected)
  const variantMissing = value.variant !== '' && !variants.includes(value.variant)
  const showVariant = variants.length > 0 || variantMissing

  const modelOptions = [
    { value: '', label: unsetLabel },
    ...(modelMissing
      ? [
          {
            value: value.modelName,
            label: modelUnavailable ? `${value.modelName} · ${unavailableLabel}` : value.modelName,
            disabled: modelUnavailable,
          },
        ]
      : []),
    ...models.map((model) => ({ value: model.name, label: model.name })),
  ]
  const variantOptions = [
    { value: '', label: t('ai.catalog.form.useModelDefault') },
    ...(variantMissing
      ? [{ value: value.variant, label: `${value.variant} · ${unavailableLabel}`, disabled: true }]
      : []),
    ...variants.map((variant) => ({ value: variant, label: variant })),
  ]

  return (
    <div
      className="settings-field"
      data-settings-field-path={fieldPath}
      data-settings-nullable={nullable}
    >
      <label className="settings-field-label" htmlFor={modelFieldId}>
        {label}
      </label>
      {hint ? <span className="settings-field-description">{hint}</span> : null}
      <Select
        id={modelFieldId}
        value={value.modelName}
        options={modelOptions}
        onChange={(modelName) => onChange({ modelName, variant: '' })}
      />
      {showVariant ? (
        <Select
          aria-label={t('settings.field.modelSelection.variant')}
          value={value.variant}
          options={variantOptions}
          onChange={(variant) => onChange({ ...value, variant })}
        />
      ) : null}
    </div>
  )
}
