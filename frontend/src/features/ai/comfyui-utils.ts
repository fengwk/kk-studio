import type { ComfyuiWorkflowDraft } from '@/features/ai/ai-console-types'
import type {
  ComfyuiBindingValueType,
  ComfyuiInputBinding,
  ComfyuiWorkflowApiDTO,
  ComfyuiWorkflowApiEditablePropertiesDTO,
} from '@/shared/api/contracts'

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
    throw new Error('API name 不能为空')
  }
  if (!apiNamePattern.test(apiName)) {
    throw new Error('API name 必须以小写字母开头，且只能包含小写字母、数字和连字符（最多 64 个字符）')
  }
  if (!name) {
    throw new Error('Display name 不能为空')
  }
  if (!workflowJson) {
    throw new Error('Workflow JSON 不能为空')
  }
  const workflow = parseJson(workflowJson, 'Workflow JSON')
  if (!isRecord(workflow)) {
    throw new Error('Workflow JSON 必须是 JSON 对象')
  }
  if (!inputBindingsJson) {
    throw new Error('Input bindings JSON 不能为空')
  }
  const bindings = parseJson(inputBindingsJson, 'Input bindings JSON')
  if (!Array.isArray(bindings)) {
    throw new Error('Input bindings JSON 必须是数组')
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
  const parsed = parseJson(json || '[]', 'Input bindings JSON')
  if (!Array.isArray(parsed)) {
    throw new Error('Input bindings JSON 必须是数组，请在编辑工作流时修正')
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
          throw new Error(`参数 ${binding.name} 必须明确选择 true 或 false`)
        }
        continue
      }
      if (raw !== 'true' && raw !== 'false') {
        throw new Error(`参数 ${binding.name} 必须是 true 或 false`)
      }
      parameters[binding.name] = raw === 'true'
      continue
    }
    const text = raw
    if (!text.trim()) {
      if (binding.required) {
        throw new Error(`参数 ${binding.name} 为必填项`)
      }
      continue
    }
    if (valueType === 'integer') {
      if (!/^-?\d+$/.test(text.trim())) {
        throw new Error(`参数 ${binding.name} 必须是整数`)
      }
      parameters[binding.name] = Number(text)
      continue
    }
    if (valueType === 'number') {
      const numberValue = Number(text)
      if (!Number.isFinite(numberValue)) {
        throw new Error(`参数 ${binding.name} 必须是数字`)
      }
      parameters[binding.name] = numberValue
      continue
    }
    if (valueType === 'json') {
      parameters[binding.name] = parseJson(text, `参数 ${binding.name}`)
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
          'download',
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
  return error instanceof Error ? error.message : '操作失败'
}

function parseBinding(item: unknown, index: number, seenNames: Set<string>): ComfyuiInputBinding {
  const prefix = `绑定项 #${index + 1}`
  if (!isRecord(item)) {
    throw new Error(`${prefix} 必须是 JSON 对象`)
  }
  const name = requiredString(item, 'name', prefix)
  if (seenNames.has(name)) {
    throw new Error(`绑定名称 ${name} 重复，请保持唯一`)
  }
  seenNames.add(name)
  const kind = requiredString(item, 'kind', prefix)
  if (kind !== 'parameter' && kind !== 'file') {
    throw new Error(`${prefix}.kind 必须是 parameter 或 file`)
  }
  const nodeId = requiredString(item, 'nodeId', prefix)
  const inputName = requiredString(item, 'inputName', prefix)
  if (item.required !== undefined && typeof item.required !== 'boolean') {
    throw new Error(`${prefix}.required 必须是布尔值`)
  }
  if (item.description !== undefined && typeof item.description !== 'string') {
    throw new Error(`${prefix}.description 必须是字符串`)
  }
  if (kind === 'file') {
    if (item.valueType !== undefined || item.defaultValue !== undefined) {
      throw new Error(`${prefix} 是文件绑定，不能设置 valueType 或 defaultValue`)
    }
    return { name, kind, nodeId, inputName, required: item.required, description: item.description }
  }
  const valueType = item.valueType === undefined ? inferValueType(item.defaultValue) : item.valueType
  if (typeof valueType !== 'string' || !valueTypes.has(valueType as ComfyuiBindingValueType)) {
    throw new Error(`${prefix}.valueType 必须是 string、integer、number、boolean 或 json`)
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
    throw new Error(`${prefix}.defaultValue 必须是字符串`)
  }
  if (valueType === 'integer' && (!Number.isInteger(value) || typeof value !== 'number')) {
    throw new Error(`${prefix}.defaultValue 必须是整数`)
  }
  if (valueType === 'number' && (typeof value !== 'number' || !Number.isFinite(value))) {
    throw new Error(`${prefix}.defaultValue 必须是数字`)
  }
  if (valueType === 'boolean' && typeof value !== 'boolean') {
    throw new Error(`${prefix}.defaultValue 必须是布尔值`)
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
    throw new Error(`${prefix}.${field} 不能为空`)
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
    throw new Error(`${label} 不是合法 JSON`)
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}
