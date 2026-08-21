import { useI18n } from '@/shared/i18n'
import {
  SettingsCard,
  SettingsNumberField,
  SettingsSelectField,
  SettingsSwitchRow,
  SettingsTextField,
  type ApplyTiming,
} from '@/features/settings/settings-primitives'
import { PermissionEditor } from '@/features/settings/permission/PermissionEditor'
import { ModelSelectionEditor } from '@/features/settings/model-selection/ModelSelectionEditor'
import {
  getDraftValue,
  setDraftValue,
  type ModelSelectionDraft,
  type PermissionGroupDraft,
  type SystemSettingsSectionsDraft,
} from '@/features/settings/system-settings-draft'
import type {
  SystemSettingsSchemaApplyTiming,
  SystemSettingsSchemaDTO,
  SystemSettingsSchemaField,
} from '@/shared/api/contracts/system-settings'

export function SystemSettingsSchemaRenderer({
  schema,
  draft,
  onChange,
}: {
  schema: SystemSettingsSchemaDTO
  draft: SystemSettingsSectionsDraft
  onChange: (next: SystemSettingsSectionsDraft) => void
}) {
  const { t } = useI18n()
  return (
    <div className="settings-section-stack" data-settings-schema-renderer>
      {schema.sections.map((section) => (
        <section key={section.key} data-settings-section={section.key}>
          <div className="settings-section-stack">
            {section.groups.map((group) => (
              <SettingsCard
                key={group.key}
                title={t(group.labelKey)}
                timing={groupTiming(group)}
              >
                {group.fields.map((field) => (
                  <SchemaField
                    key={field.path}
                    field={field}
                    draft={draft}
                    onChange={onChange}
                  />
                ))}
              </SettingsCard>
            ))}
          </div>
        </section>
      ))}
    </div>
  )
}

/**
 * 生效时机徽标只出现在卡片上：显式 applyTiming 优先；未声明且 group.restartRequired 时回退为重启。
 */
function groupTiming(
  group: SystemSettingsSchemaDTO['sections'][number]['groups'][number],
): ApplyTiming | undefined {
  const explicit = toApplyTiming(group.applyTiming)
  if (explicit != null) {
    return explicit
  }
  if (group.restartRequired) {
    return 'restart'
  }
  return undefined
}

function SchemaField({
  field,
  draft,
  onChange,
}: {
  field: SystemSettingsSchemaField
  draft: SystemSettingsSectionsDraft
  onChange: (next: SystemSettingsSectionsDraft) => void
}) {
  const { t } = useI18n()
  const value = getDraftValue(draft, field.path)
  const label = t(field.labelKey)
  const hint = field.hintKey == null ? undefined : t(field.hintKey)
  const update = (next: unknown) => onChange(setDraftValue(draft, field.path, next))

  switch (field.type) {
    case 'BOOLEAN':
      return (
        <SettingsSwitchRow
          fieldPath={field.path}
          nullable={field.nullable}
          label={label}
          description={hint}
          checked={value as boolean}
          onChange={update}
        />
      )
    case 'INTEGER':
    case 'LONG':
      return (
        <SettingsNumberField
          fieldPath={field.path}
          nullable={field.nullable}
          label={label}
          value={value as string}
          min={field.min ?? undefined}
          max={field.max ?? undefined}
          hint={hint}
          onChange={update}
        />
      )
    case 'TEXT':
      return (
        <SettingsTextField
          fieldPath={field.path}
          nullable={field.nullable}
          label={label}
          value={value as string}
          maxLength={field.max ?? undefined}
          hint={hint}
          onChange={update}
        />
      )
    case 'ENUM':
      return (
        <SettingsSelectField
          fieldPath={field.path}
          nullable={field.nullable}
          label={label}
          value={value as string}
          options={(field.options ?? []).map((option) => ({
            value: option.value,
            label: t(option.labelKey),
          }))}
          hint={hint}
          onChange={update}
        />
      )
    case 'PERMISSION':
      return (
        <div
          className="settings-field"
          data-settings-field-path={field.path}
          data-settings-nullable={field.nullable}
        >
          {hint ? <span className="settings-field-description">{hint}</span> : null}
          <PermissionEditor
            groups={value as PermissionGroupDraft[]}
            options={field.options ?? []}
            onChange={update}
          />
        </div>
      )
    case 'MODEL_SELECTION':
      return (
        <ModelSelectionEditor
          path={field.path}
          value={value as ModelSelectionDraft | null}
          labelKey={field.labelKey}
          hint={hint ?? null}
          nullable={field.nullable}
          onChange={update}
        />
      )
  }
}

function toApplyTiming(value: SystemSettingsSchemaApplyTiming | null): ApplyTiming | undefined {
  switch (value) {
    case 'NEXT_INVOCATION':
      return 'nextInvocation'
    case 'NEXT_CHAT':
      return 'nextChat'
    case 'RESTART':
      return 'restart'
    default:
      return undefined
  }
}
