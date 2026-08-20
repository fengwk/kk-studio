import { useI18n } from '@/shared/i18n'
import {
  RestartNotice,
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
  draftLeafPaths,
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
  SystemSettingsSchemaFieldType,
} from '@/shared/api/contracts/system-settings'

const FIELD_TYPES = new Set<SystemSettingsSchemaFieldType>([
  'BOOLEAN',
  'INTEGER',
  'LONG',
  'TEXT',
  'ENUM',
  'PERMISSION',
  'MODEL_SELECTION',
])

/**
 * Fail-closed validation for the server schema. It checks actual paths and draft leaves rather
 * than keeping a parallel list of settings fields in the UI.
 */
export function validateSystemSettingsSchema(
  schema: SystemSettingsSchemaDTO,
  draft: SystemSettingsSectionsDraft,
): string | null {
  if (!Array.isArray(schema.sections) || schema.sections.length === 0) {
    return 'settings schema has no sections'
  }
  const sectionKeys = new Set<string>()
  const groupKeys = new Set<string>()
  const fieldPaths = new Set<string>()
  try {
    for (const section of schema.sections) {
      if (
        !nonBlank(section.key)
        || !nonBlank(section.labelKey)
        || !nonBlank(section.descriptionKey)
        || !Array.isArray(section.groups)
        || section.groups.length === 0
      ) {
        return `invalid settings schema section: ${section.key ?? '<missing>'}`
      }
      if (sectionKeys.has(section.key)) {
        return `duplicate settings schema section: ${section.key}`
      }
      sectionKeys.add(section.key)
      for (const group of section.groups) {
        if (
          !nonBlank(group.key)
          || !nonBlank(group.labelKey)
          || !nonBlank(group.descriptionKey)
          || !Array.isArray(group.fields)
          || group.fields.length === 0
        ) {
          return `invalid settings schema group: ${group.key ?? '<missing>'}`
        }
        if (groupKeys.has(group.key)) {
          return `duplicate settings schema group: ${group.key}`
        }
        groupKeys.add(group.key)
        for (const field of group.fields) {
          const fieldError = validateField(field, draft, fieldPaths)
          if (fieldError != null) {
            return fieldError
          }
        }
      }
    }
    const expectedPaths = new Set(draftLeafPaths(draft))
    if (!sameSet(expectedPaths, fieldPaths)) {
      return 'settings schema field paths do not match the editable draft'
    }
  } catch (error) {
    return error instanceof Error ? error.message : 'invalid settings schema'
  }
  return null
}

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
          {section.restartRequired ? <RestartNotice /> : null}
          <div className="settings-section-stack">
            {section.groups.map((group) => (
              <SettingsCard
                key={group.key}
                title={t(group.labelKey)}
                description={group.descriptionKey}
                timing={toApplyTiming(group.applyTiming)}
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
          label={label}
          hint={hint ?? null}
          onChange={update}
        />
      )
  }
}

function validateField(
  field: SystemSettingsSchemaField,
  draft: SystemSettingsSectionsDraft,
  paths: Set<string>,
): string | null {
  if (
    !nonBlank(field.path)
    || !nonBlank(field.labelKey)
    || !FIELD_TYPES.has(field.type)
    || typeof field.nullable !== 'boolean'
    || (field.hintKey != null && !nonBlank(field.hintKey))
  ) {
    return `invalid settings schema field: ${field.path ?? '<missing>'}`
  }
  if (paths.has(field.path)) {
    return `duplicate settings schema field: ${field.path}`
  }
  paths.add(field.path)
  const value = getDraftValue(draft, field.path)
  if (field.min != null && field.max != null && field.min > field.max) {
    return `invalid settings schema bounds: ${field.path}`
  }
  if (field.options != null) {
    if (!Array.isArray(field.options) || field.options.length === 0) {
      return `invalid settings schema options: ${field.path}`
    }
    const optionValues = new Set<string>()
    for (const option of field.options) {
      if (!nonBlank(option.value) || !nonBlank(option.labelKey) || !optionValues.add(option.value)) {
        return `invalid settings schema option: ${field.path}`
      }
    }
  }
  if (!matchesFieldType(field.type, value)) {
    return `settings schema type does not match draft: ${field.path}`
  }
  if (
    (field.type === 'ENUM' || field.type === 'PERMISSION')
    && (field.options == null || field.options.length === 0)
  ) {
    return `settings schema options are required: ${field.path}`
  }
  if (field.type === 'ENUM' && !(field.options ?? []).some((option) => option.value === value)) {
    return `settings schema enum value is not declared: ${field.path}`
  }
  return null
}

function matchesFieldType(type: SystemSettingsSchemaFieldType, value: unknown): boolean {
  switch (type) {
    case 'BOOLEAN':
      return typeof value === 'boolean'
    case 'INTEGER':
    case 'LONG':
      return typeof value === 'string'
    case 'TEXT':
      return typeof value === 'string'
    case 'ENUM':
      return typeof value === 'string'
    case 'PERMISSION':
      return Array.isArray(value)
    case 'MODEL_SELECTION':
      return value == null || isModelSelectionDraft(value)
  }
}

function isModelSelectionDraft(value: unknown): value is ModelSelectionDraft {
  return (
    value != null
    && typeof value === 'object'
    && !Array.isArray(value)
    && typeof (value as ModelSelectionDraft).providerName === 'string'
    && typeof (value as ModelSelectionDraft).modelName === 'string'
    && typeof (value as ModelSelectionDraft).variant === 'string'
  )
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

function sameSet(left: Set<string>, right: Set<string>): boolean {
  return left.size === right.size && [...left].every((value) => right.has(value))
}

function nonBlank(value: unknown): value is string {
  return typeof value === 'string' && value.trim() !== ''
}
