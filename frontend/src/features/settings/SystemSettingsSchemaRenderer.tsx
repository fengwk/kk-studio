import { requireTranslationKey, useI18n } from '@/shared/i18n'
import {
  SettingsCard,
  SettingsIntegerListField,
  SettingsNumberField,
  SettingsSelectField,
  SettingsSwitchRow,
  SettingsTextField,
} from '@/features/settings/settings-primitives'
import { PermissionEditor } from '@/features/settings/permission/PermissionEditor'
import { SettingsModelSelectionField } from '@/features/settings/SettingsModelSelectionField'
import {
  getDraftValue,
  setDraftValue,
  type ModelSelectionDraft,
  type PermissionGroupDraft,
  type SystemSettingsSectionsDraft,
} from '@/features/settings/system-settings-draft'
import type {
  SystemSettingsSchemaDTO,
  SystemSettingsSchemaFieldDTO,
} from '@/shared/api/generated/contracts'

export function SystemSettingsSchemaRenderer({
  schema,
  draft,
  fieldErrors = {},
  onChange,
}: {
  schema: SystemSettingsSchemaDTO
  draft: SystemSettingsSectionsDraft
  fieldErrors?: Record<string, string>
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
                title={t(requireTranslationKey(group.labelKey))}
              >
                {group.fields.map((field) => (
                  <SchemaField
                    key={field.path}
                    field={field}
                    draft={draft}
                    error={fieldErrors[field.path]}
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

function SchemaField({
  field,
  draft,
  error,
  onChange,
}: {
  field: SystemSettingsSchemaFieldDTO
  draft: SystemSettingsSectionsDraft
  error?: string
  onChange: (next: SystemSettingsSectionsDraft) => void
}) {
  const { t } = useI18n()
  const value = getDraftValue(draft, field.path)
  const label = t(requireTranslationKey(field.labelKey))
  const hint = field.hintKey == null ? undefined : t(requireTranslationKey(field.hintKey))
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
          error={error}
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
            label: t(requireTranslationKey(option.labelKey)),
          }))}
          hint={hint}
          onChange={update}
        />
      )
    case 'MODEL_SELECTION':
      return (
        <SettingsModelSelectionField
          fieldPath={field.path}
          nullable={field.nullable}
          label={label}
          hint={hint}
          unsetLabel={t('settings.field.modelSelection.unset')}
          value={value as ModelSelectionDraft}
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
    case 'INTEGER_LIST':
      return (
        <SettingsIntegerListField
          fieldPath={field.path}
          nullable={field.nullable}
          label={label}
          value={value as (number | string)[]}
          min={field.min ?? undefined}
          max={field.max ?? undefined}
          hint={hint}
          onChange={update}
        />
      )
  }
}
