import { useQuery } from '@tanstack/react-query'
import type { ModelSelectionDraft } from '@/features/settings/system-settings-draft'
import { agentService } from '@/shared/api/agent-service'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { ModelSelectionPicker } from '@/shared/ui/controls/ModelSelectionPicker'

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
  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
  })
  const models = modelsQuery.data ?? []
  const selected = models.some((model) => model.name === value.modelName)
  const modelUnavailable = Boolean(value.modelName) && !selected && modelsQuery.isSuccess

  return (
    <div
      className="settings-field"
      data-settings-field-path={fieldPath}
      data-settings-nullable={nullable}
    >
      <span className="settings-field-label">{label}</span>
      {hint ? <span className="settings-field-description">{hint}</span> : null}
      <ModelSelectionPicker
        models={models}
        modelName={value.modelName}
        variant={value.variant}
        modelLabel={label}
        variantLabel={t('settings.field.modelSelection.variant')}
        allowModelGroup
        showVariant
        allowUnsetModel
        unsetModelLabel={unsetLabel}
        variantUnsetLabel={t('ai.catalog.form.useModelDefault')}
        modelUnavailable={modelUnavailable}
        variantUnavailable={modelUnavailable}
        onModelChange={(modelName) => onChange({ modelName, variant: '' })}
        onVariantChange={(variant) => onChange({ ...value, variant })}
      />
    </div>
  )
}
