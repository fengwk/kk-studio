import { useI18n } from '@/shared/i18n'
import { SettingsTextField } from '@/features/settings/settings-primitives'
import type { ModelSelectionDraft } from '@/features/settings/system-settings-draft'

const EMPTY_SELECTION: ModelSelectionDraft = {
  providerName: '',
  modelName: '',
  variant: '',
}

/**
 * 可空 model selection 的三个字段编辑器；null 始终表示三个输入都为空。
 *
 * 子字段标签由 schema 传入的 labelKey 派生（`<labelKey>.providerName` / `.modelName` / `.variant`），
 * 组件不硬编码任何具体设置路径；子字段 128 cap 与 ModelSelection 的 canonical 约束一致。
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
      data-settings-nullable={nullable}
    >
      <div className="settings-field-label">{t(labelKey)}</div>
      <div className="settings-model-selection-fields">
        <SettingsTextField
          label={t(`${labelKey}.providerName`)}
          value={selection.providerName}
          maxLength={128}
          onChange={(next) => update('providerName', next)}
        />
        <SettingsTextField
          label={t(`${labelKey}.modelName`)}
          value={selection.modelName}
          maxLength={128}
          onChange={(next) => update('modelName', next)}
        />
        <SettingsTextField
          label={t(`${labelKey}.variant`)}
          value={selection.variant}
          maxLength={128}
          onChange={(next) => update('variant', next)}
        />
      </div>
      {hint ? <span className="settings-field-hint">{hint}</span> : null}
    </div>
  )
}
