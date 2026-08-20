import {
  draftLeafPaths,
  getDraftValue,
  type ModelSelectionDraft,
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

const APPLY_TIMINGS = new Set<SystemSettingsSchemaApplyTiming>([
  'NEXT_INVOCATION',
  'NEXT_CHAT',
  'RESTART',
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
        || typeof section.restartRequired !== 'boolean'
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
          || typeof group.restartRequired !== 'boolean'
          || (group.applyTiming != null && !APPLY_TIMINGS.has(group.applyTiming))
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
  if (!isFiniteBound(field.min) || !isFiniteBound(field.max)) {
    return `invalid settings schema bounds: ${field.path}`
  }
  if (field.min != null && field.max != null && field.min > field.max) {
    return `invalid settings schema bounds: ${field.path}`
  }
  if (field.options != null) {
    if (!Array.isArray(field.options) || field.options.length === 0) {
      return `invalid settings schema options: ${field.path}`
    }
    const optionValues = new Set<string>()
    for (const option of field.options) {
      if (
        !nonBlank(option.value)
        || !nonBlank(option.labelKey)
        || optionValues.has(option.value)
      ) {
        return `invalid settings schema option: ${field.path}`
      }
      optionValues.add(option.value)
    }
  }
  if (!matchesFieldType(field.type, value, field.nullable)) {
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

function isFiniteBound(value: number | null): boolean {
  return value == null || (Number.isInteger(value) && Number.isFinite(value))
}

function matchesFieldType(
  type: SystemSettingsSchemaFieldType,
  value: unknown,
  nullable: boolean,
): boolean {
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
      return value == null ? nullable : isModelSelectionDraft(value)
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

function sameSet(left: Set<string>, right: Set<string>): boolean {
  return left.size === right.size && [...left].every((value) => right.has(value))
}

function nonBlank(value: unknown): value is string {
  return typeof value === 'string' && value.trim() !== ''
}
