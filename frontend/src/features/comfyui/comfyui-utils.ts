import type { ComfyuiWorkflowDraft } from '@/features/comfyui/comfyui-types'
import type {
  ComfyuiBindingValueType,
  ComfyuiInputBinding,
  ComfyuiWorkflowApiDTO,
  ComfyuiWorkflowApiEditablePropertiesDTO,
} from '@/shared/api/contracts/comfyui'
import { translate } from '@/shared/i18n'

const apiNamePattern = /^[a-z][a-z0-9-]{0,63}$/
const valueTypes = new Set<ComfyuiBindingValueType>(['string', 'integer', 'number', 'boolean', 'json'])

export const emptyComfyuiWorkflowDraft: ComfyuiWorkflowDraft = {
  apiName: '',
  name: '',
  description: '',
  workflowJson: '{}',
  inputBindingsJson: '[]',
  defaultSelector: '',
  enabled: true,
}

export interface ComfyuiDownloadLink {
  downloadUrl: string
  filename: string
}

export function workflowToDraft(workflow: ComfyuiWorkflowApiDTO): ComfyuiWorkflowDraft {
  return {
    apiName: workflow.apiName,
    name: workflow.name,
    description: workflow.description ?? '',
    workflowJson: workflow.workflowJson,
    inputBindingsJson: workflow.inputBindingsJson || '[]',
    defaultSelector: workflow.defaultSelector ?? '',
    enabled: workflow.enabled,
  }
}

export function validateComfyuiWorkflowDraft(draft: ComfyuiWorkflowDraft): ComfyuiWorkflowApiEditablePropertiesDTO {
  const apiName = draft.apiName.trim()
  const name = draft.name.trim()
  const workflowJson = draft.workflowJson.trim()
  const inputBindingsJson = draft.inputBindingsJson.trim()
  if (!apiName) {
    throw new Error(translate('comfyui.validation.apiNameRequired'))
  }
  if (!apiNamePattern.test(apiName)) {
    throw new Error(translate('comfyui.validation.apiNameInvalid'))
  }
  if (!name) {
    throw new Error(translate('comfyui.validation.displayNameRequired'))
  }
  if (!workflowJson) {
    throw new Error(translate('comfyui.validation.workflowJsonRequired'))
  }
  const workflow = parseJson(workflowJson, translate('comfyui.validation.workflowJsonLabel'))
  if (!isRecord(workflow)) {
    throw new Error(translate('comfyui.validation.workflowJsonObject'))
  }
  if (!inputBindingsJson) {
    throw new Error(translate('comfyui.validation.inputBindingsJsonRequired'))
  }
  const bindings = parseJson(inputBindingsJson, translate('comfyui.validation.inputBindingsJsonLabel'))
  if (!Array.isArray(bindings)) {
    throw new Error(translate('comfyui.validation.inputBindingsJsonArray'))
  }
  return {
    apiName,
    name,
    description: draft.description.trim() || null,
    workflowJson,
    inputBindingsJson,
    defaultSelector: draft.defaultSelector.trim() || null,
    enabled: draft.enabled,
  }
}

export function parseComfyuiBindings(json: string): ComfyuiInputBinding[] {
  const parsed = parseJson(json || '[]', translate('comfyui.validation.inputBindingsJsonLabel'))
  if (!Array.isArray(parsed)) {
    throw new Error(translate('comfyui.validation.inputBindingsJsonArrayRepair'))
  }
  const seenNames = new Set<string>()
  return parsed.map((item, index) => parseBinding(item, index, seenNames))
}

export function getBindingSummary(json: string): { count: number; error: string | null } {
  try {
    return { count: parseComfyuiBindings(json).length, error: null }
  } catch (error) {
    return { count: 0, error: errorMessage(error) }
  }
}

export function filterComfyuiWorkflows(workflows: ComfyuiWorkflowApiDTO[], search: string): ComfyuiWorkflowApiDTO[] {
  if (!search) {
    return workflows
  }
  return workflows.filter((workflow) =>
    `${workflow.name} ${workflow.apiName} ${workflow.description ?? ''}`.toLowerCase().includes(search),
  )
}

export function initialBindingValues(bindings: ComfyuiInputBinding[]): Record<string, string> {
  return Object.fromEntries(
    bindings
      .filter((binding) => binding.kind === 'parameter')
      .map((binding) => [binding.name, formatDefaultValue(binding)]),
  )
}

export function buildComfyuiParameters(
  bindings: ComfyuiInputBinding[],
  values: Record<string, string>,
): Record<string, unknown> {
  const parameters: Record<string, unknown> = {}
  for (const binding of bindings) {
    if (binding.kind !== 'parameter') {
      continue
    }
    const valueType = binding.valueType ?? 'string'
    const raw = values[binding.name] ?? ''
    if (valueType === 'boolean') {
      if (!raw) {
        if (binding.required) {
          throw new Error(
            translate('comfyui.validation.parameterBooleanRequired', { name: binding.name }),
          )
        }
        continue
      }
      if (raw !== 'true' && raw !== 'false') {
        throw new Error(translate('comfyui.validation.parameterBoolean', { name: binding.name }))
      }
      parameters[binding.name] = raw === 'true'
      continue
    }
    const text = raw
    if (!text.trim()) {
      if (binding.required) {
        throw new Error(translate('comfyui.validation.parameterRequired', { name: binding.name }))
      }
      continue
    }
    if (valueType === 'integer') {
      if (!/^-?\d+$/.test(text.trim())) {
        throw new Error(translate('comfyui.validation.parameterInteger', { name: binding.name }))
      }
      parameters[binding.name] = Number(text)
      continue
    }
    if (valueType === 'number') {
      const numberValue = Number(text)
      if (!Number.isFinite(numberValue)) {
        throw new Error(translate('comfyui.validation.parameterNumber', { name: binding.name }))
      }
      parameters[binding.name] = numberValue
      continue
    }
    if (valueType === 'json') {
      parameters[binding.name] = parseJson(
        text,
        translate('comfyui.validation.parameterLabel', { name: binding.name }),
      )
      continue
    }
    parameters[binding.name] = text
  }
  return parameters
}

export function discoverComfyuiDownloads(value: unknown): ComfyuiDownloadLink[] {
  const links: ComfyuiDownloadLink[] = []
  const seen = new Set<string>()
  function visit(current: unknown) {
    if (Array.isArray(current)) {
      current.forEach(visit)
      return
    }
    if (!isRecord(current)) {
      return
    }
    if (typeof current.downloadUrl === 'string' && current.downloadUrl && !seen.has(current.downloadUrl)) {
      seen.add(current.downloadUrl)
      links.push({
        downloadUrl: current.downloadUrl,
        filename:
          (typeof current.filename === 'string' && current.filename) ||
          (typeof current.name === 'string' && current.name) ||
          current.downloadUrl.split('/').pop() ||
          translate('comfyui.run.downloadFallback'),
      })
    }
    Object.values(current).forEach(visit)
  }
  visit(value)
  return links
}

export function isComfyuiPollingStatus(status: string | null | undefined): boolean {
  return ['pending', 'in_progress', 'running'].includes(normalizeComfyuiStatus(status))
}

export function isComfyuiTerminalStatus(status: string | null | undefined): boolean {
  return ['succeeded', 'success', 'complete', 'completed', 'failed', 'error', 'cancelled', 'canceled', 'interrupted'].includes(
    normalizeComfyuiStatus(status),
  )
}

export function prettyJson(value: unknown): string {
  if (value === undefined) {
    return '-'
  }
  return JSON.stringify(value, null, 2) ?? String(value)
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : translate('comfyui.error.operationFailed')
}

function parseBinding(item: unknown, index: number, seenNames: Set<string>): ComfyuiInputBinding {
  const prefix = translate('comfyui.validation.bindingItem', { index: index + 1 })
  if (!isRecord(item)) {
    throw new Error(translate('comfyui.validation.bindingItemObject', { prefix }))
  }
  const name = requiredString(item, 'name', prefix)
  if (seenNames.has(name)) {
    throw new Error(translate('comfyui.validation.duplicateBindingName', { name }))
  }
  seenNames.add(name)
  const kind = requiredString(item, 'kind', prefix)
  if (kind !== 'parameter' && kind !== 'file') {
    throw new Error(translate('comfyui.validation.bindingKind', { prefix }))
  }
  const nodeId = requiredString(item, 'nodeId', prefix)
  const inputName = requiredString(item, 'inputName', prefix)
  if (item.required !== undefined && typeof item.required !== 'boolean') {
    throw new Error(translate('comfyui.validation.bindingRequiredBoolean', { prefix }))
  }
  if (item.description !== undefined && typeof item.description !== 'string') {
    throw new Error(translate('comfyui.validation.bindingDescriptionString', { prefix }))
  }
  if (kind === 'file') {
    if (item.valueType !== undefined || item.defaultValue !== undefined) {
      throw new Error(translate('comfyui.validation.fileBindingOptions', { prefix }))
    }
    return { name, kind, nodeId, inputName, required: item.required, description: item.description }
  }
  const valueType = item.valueType === undefined ? inferValueType(item.defaultValue) : item.valueType
  if (typeof valueType !== 'string' || !valueTypes.has(valueType as ComfyuiBindingValueType)) {
    throw new Error(translate('comfyui.validation.bindingValueType', { prefix }))
  }
  validateDefaultValue(prefix, valueType as ComfyuiBindingValueType, item.defaultValue)
  return {
    name,
    kind,
    nodeId,
    inputName,
    required: item.required,
    description: item.description,
    valueType: valueType as ComfyuiBindingValueType,
    defaultValue: item.defaultValue,
  }
}

function inferValueType(defaultValue: unknown): ComfyuiBindingValueType {
  if (defaultValue === undefined || defaultValue === null) {
    return 'string'
  }
  if (typeof defaultValue === 'boolean') {
    return 'boolean'
  }
  if (typeof defaultValue === 'number') {
    return Number.isInteger(defaultValue) ? 'integer' : 'number'
  }
  if (typeof defaultValue === 'string') {
    return 'string'
  }
  return 'json'
}

function validateDefaultValue(prefix: string, valueType: ComfyuiBindingValueType, value: unknown) {
  if (value === undefined || value === null || valueType === 'json') {
    return
  }
  if (valueType === 'string' && typeof value !== 'string') {
    throw new Error(translate('comfyui.validation.defaultString', { prefix }))
  }
  if (valueType === 'integer' && (!Number.isInteger(value) || typeof value !== 'number')) {
    throw new Error(translate('comfyui.validation.defaultInteger', { prefix }))
  }
  if (valueType === 'number' && (typeof value !== 'number' || !Number.isFinite(value))) {
    throw new Error(translate('comfyui.validation.defaultNumber', { prefix }))
  }
  if (valueType === 'boolean' && typeof value !== 'boolean') {
    throw new Error(translate('comfyui.validation.defaultBoolean', { prefix }))
  }
}

function formatDefaultValue(binding: ComfyuiInputBinding): string {
  const valueType = binding.valueType ?? 'string'
  if (binding.defaultValue === undefined || binding.defaultValue === null) {
    return ''
  }
  if (valueType === 'boolean') {
    return String(binding.defaultValue)
  }
  if (valueType === 'json') {
    return JSON.stringify(binding.defaultValue, null, 2)
  }
  return String(binding.defaultValue)
}

function requiredString(value: Record<string, unknown>, field: string, prefix: string): string {
  const fieldValue = value[field]
  if (typeof fieldValue !== 'string' || !fieldValue.trim()) {
    throw new Error(translate('comfyui.validation.fieldRequired', { prefix, field }))
  }
  return fieldValue.trim()
}

function normalizeComfyuiStatus(status: string | null | undefined): string {
  return status?.trim().toLowerCase() ?? ''
}

function parseJson(value: string, label: string): unknown {
  try {
    return JSON.parse(value)
  } catch {
    throw new Error(translate('comfyui.validation.invalidJson', { label }))
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}
