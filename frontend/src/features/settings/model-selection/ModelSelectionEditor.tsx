import { useI18n } from '@/shared/i18n'
import { SettingsTextField } from '@/features/settings/settings-primitives'
import type { ModelSelectionDraft } from '@/features/settings/system-settings-draft'

const EMPTY_SELECTION: ModelSelectionDraft = {
  providerName: '',
  modelName: '',
  variant: '',
}

/** 可空 fallback model 的三个字段编辑器；null 始终表示三个输入都为空。 */
export function ModelSelectionEditor({
  value,
  path,
  label,
  hint,
  onChange,
}: {
  value: ModelSelectionDraft | null
  path: string
  label: string
  hint: string | null
  onChange: (next: ModelSelectionDraft | null) => void
}) {
  const { t } = useI18n()
  const selection = value ?? EMPTY_SELECTION

  const update = (key: keyof ModelSelectionDraft, nextValue: string) => {
    const next = { ...selection, [key]: nextValue }
    onChange(
      next.providerName === '' && next.modelName === '' && next.variant === '' ? null : next,
    )
  }

  return (
    <div
      className="settings-field settings-model-selection"
      data-settings-field-path={path}
      data-settings-nullable="true"
    >
      <div className="settings-field-label">{label}</div>
      <div className="settings-model-selection-fields">
        <SettingsTextField
          label={t('settings.field.aiRuntime.compactionFallbackModel.providerName')}
          value={selection.providerName}
          maxLength={128}
          onChange={(next) => update('providerName', next)}
        />
        <SettingsTextField
          label={t('settings.field.aiRuntime.compactionFallbackModel.modelName')}
          value={selection.modelName}
          maxLength={128}
          onChange={(next) => update('modelName', next)}
        />
        <SettingsTextField
          label={t('settings.field.aiRuntime.compactionFallbackModel.variant')}
          value={selection.variant}
          maxLength={128}
          onChange={(next) => update('variant', next)}
        />
      </div>
      {hint ? <span className="settings-field-hint">{hint}</span> : null}
    </div>
  )
}
