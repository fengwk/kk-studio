import type {
  CanvasFunctionDefinitionDTO,
  CanvasResourceKind,
  UUIDString,
} from '@/shared/api/contracts/studio'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import type { CanvasFunctionConfig, CanvasResourceReference } from '@/features/canvas/types'
import type { CanvasSnapshot, Resource, ResourceNode } from '@/features/canvas/domain'

export interface ReferenceCandidate {
  nodeId: UUIDString
  index: number
  node: ResourceNode
  resource: Resource
  label: string
}

export interface CanvasFunctionParameterDefinition {
  key: string
  label: string
  type: 'ENUM' | 'NUMBER'
  required: boolean
  defaultValue: unknown
  options: unknown[]
  isInteger: boolean
  min: number | null
  max: number | null
}

export function functionSupportsReferences(model: CanvasFunctionDefinitionDTO | null | undefined): boolean {
  if (!model?.argsSchema || typeof model.argsSchema !== 'object') return false
  const props = (model.argsSchema as Record<string, unknown>).properties
  if (!props || typeof props !== 'object') return false
  const refsProp = (props as Record<string, unknown>).references
  if (!refsProp || typeof refsProp !== 'object') return false
  const r = refsProp as Record<string, unknown>
  if (r.type !== 'array') return false
  if (!r.items || typeof r.items !== 'object') {
    return false
  }
  const items = r.items as Record<string, unknown>
  return items.type === 'resourceReference'
}

export function isCanonicalResourceReference(item: unknown): item is CanvasResourceReference {
  if (!item || typeof item !== 'object' || Array.isArray(item)) return false
  const rec = item as Record<string, unknown>
  const keys = Object.keys(rec)
  if (keys.length !== 3) return false
  return (
    rec.type === 'resource'
    && typeof rec.nodeId === 'string'
    && isCanonicalUuid(rec.nodeId)
    && typeof rec.index === 'number'
    && Number.isInteger(rec.index)
    && rec.index >= 0
    && 'type' in rec
    && 'nodeId' in rec
    && 'index' in rec
  )
}

export function extractParametersFromDefinition(model: CanvasFunctionDefinitionDTO): CanvasFunctionParameterDefinition[] {
  const schema = model.argsSchema
  if (!schema || typeof schema !== 'object' || !schema.properties || typeof schema.properties !== 'object') {
    return []
  }
  const required = Array.isArray(schema.required) ? new Set(schema.required) : new Set<string>()
  const params: CanvasFunctionParameterDefinition[] = []
  for (const [key, prop] of Object.entries(schema.properties as Record<string, unknown>)) {
    if (key === 'prompt' || key === 'references') continue
    if (!prop || typeof prop !== 'object') continue
    const p = prop as Record<string, unknown>
    if (Array.isArray(p.enum)) {
      params.push({
        key,
        label: typeof p.title === 'string' ? p.title : key,
        type: 'ENUM',
        required: required.has(key),
        defaultValue: p.default !== undefined ? p.default : null,
        options: p.enum,
        isInteger: false,
        min: null,
        max: null,
      })
    } else if (p.type === 'integer' || p.type === 'number') {
      params.push({
        key,
        label: typeof p.title === 'string' ? p.title : key,
        type: 'NUMBER',
        required: required.has(key),
        defaultValue: typeof p.default === 'number' ? p.default : null,
        options: [],
        isInteger: p.type === 'integer',
        min: typeof p.minimum === 'number' ? p.minimum : null,
        max: typeof p.maximum === 'number' ? p.maximum : null,
      })
    }
  }
  return params
}

export function createDefaultFunctionConfig(model: CanvasFunctionDefinitionDTO): CanvasFunctionConfig {
  const parameters: Record<string, unknown> = {}
  const paramDefs = extractParametersFromDefinition(model)
  for (const parameter of paramDefs) {
    if (parameter.defaultValue !== null && parameter.defaultValue !== undefined) {
      parameters[parameter.key] = parameter.defaultValue
    } else if (parameter.type === 'ENUM' && parameter.options[0] !== undefined) {
      parameters[parameter.key] = parameter.options[0]
    } else if (parameter.type === 'NUMBER' && parameter.min !== null) {
      parameters[parameter.key] = parameter.min
    }
  }
  return {
    prompt: '',
    references: [],
    parameters,
  }
}

function hasUnsupportedPromptSchema(model?: CanvasFunctionDefinitionDTO | null): boolean {
  if (!model?.argsSchema || typeof model.argsSchema !== 'object') return false
  const props = (model.argsSchema as Record<string, unknown>).properties
  if (!props || typeof props !== 'object') return false
  const promptProp = (props as Record<string, unknown>).prompt
  if (!promptProp || typeof promptProp !== 'object') return false
  return (promptProp as Record<string, unknown>).type !== 'string'
}

export function parseFunctionConfig(
  args: Record<string, unknown> | null | undefined,
  model: CanvasFunctionDefinitionDTO,
): CanvasFunctionConfig {
  if (!args || typeof args !== 'object') {
    return createDefaultFunctionConfig(model)
  }

  // 1. 若 schema 声明的 prompt 并非字符串类型，不能默认为简单字符串表单，切换至完整 JSON 通道
  if (hasUnsupportedPromptSchema(model)) {
    return {
      references: [],
      parameters: {},
      rawArgs: args,
      rawError: '模型定义的提示词并非字符串类型，已进入完整 JSON 模式。',
    }
  }

  // 2. 若入参 prompt 包含非字符串结构（如对象、数组），绝不能静默覆盖为 ''，必须完整保留原始数据
  if ('prompt' in args && typeof args.prompt !== 'string') {
    return {
      references: [],
      parameters: {},
      rawArgs: args,
      rawError: '入参 prompt 包含非字符串结构，已进入完整 JSON 模式以防止数据丢失。',
    }
  }

  // 3. 若入参 references 并非数组或包含非规范引用项，绝不能静默过滤删除，必须完整保留原始数据
  if ('references' in args) {
    if (!Array.isArray(args.references)) {
      return {
        references: [],
        parameters: {},
        rawArgs: args,
        rawError: '入参 references 并非数组，已进入完整 JSON 模式以防止数据丢失。',
      }
    }
    for (const item of args.references) {
      if (!isCanonicalResourceReference(item)) {
        return {
          references: [],
          parameters: {},
          rawArgs: args,
          rawError: '入参 references 包含非规范项，已进入完整 JSON 模式以防止数据丢失。',
        }
      }
    }
    // 4. 若入参包含 references 但当前模型 schema 不支持 references，不能剥离进 references，必须 raw 保留以防保存时丢弃
    if (args.references.length > 0 && !functionSupportsReferences(model)) {
      return {
        references: [],
        parameters: {},
        rawArgs: args,
        rawError: '当前模型不支持参考资源，但入参包含引用数据，已切换至完整 JSON 模式以防止数据丢失。',
      }
    }
  }

  const prompt = typeof args.prompt === 'string' ? args.prompt : undefined

  const references: CanvasResourceReference[] = Array.isArray(args.references)
    ? args.references.map((item) => ({
        type: 'resource' as const,
        nodeId: (item as CanvasResourceReference).nodeId,
        index: (item as CanvasResourceReference).index,
      }))
    : []

  // 保留所有业务参数（包括未来嵌套对象、自定义字段等），绝不丢弃
  const parameters: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(args)) {
    if (key !== 'prompt' && key !== 'references') {
      parameters[key] = value
    }
  }

  // 仅对缺失的字段补齐显式 schema default；对已有值（无论是否匹配 enum 或范围）绝不擅自重置校正
  const paramDefs = extractParametersFromDefinition(model)
  for (const definition of paramDefs) {
    if (parameters[definition.key] === undefined && definition.defaultValue !== null && definition.defaultValue !== undefined) {
      parameters[definition.key] = definition.defaultValue
    }
  }

  return {
    prompt,
    references,
    parameters,
  }
}

export function configToFunctionArgs(
  config: CanvasFunctionConfig,
  model?: CanvasFunctionDefinitionDTO | null,
): Record<string, unknown> {
  // 完整 rawArgs 模式无损回传
  if (config.rawArgs) {
    return config.rawArgs
  }

  const args: Record<string, unknown> = {
    ...config.parameters,
  }
  if (config.prompt !== undefined) {
    args.prompt = config.prompt
  }
  // 仅当 model 显式声明 properties.references 为 resourceReference 数组时注入 references
  if (functionSupportsReferences(model)) {
    args.references = config.references.map((ref) => ({
      type: 'resource' as const,
      nodeId: ref.nodeId,
      index: ref.index,
    }))
  }
  return args
}

export function referenceCandidates(
  snapshot: CanvasSnapshot,
  targetNodeId: UUIDString,
  model: CanvasFunctionDefinitionDTO,
): ReferenceCandidate[] {
  if (!functionSupportsReferences(model)) {
    return []
  }
  const candidates: ReferenceCandidate[] = []
  for (const node of snapshot.resourceNodes) {
    if (node.id === targetNodeId) {
      continue
    }
    node.resources.forEach((resource, index) => {
      if (!model.referencePolicy || model.referencePolicy.allowedKinds.includes(resource.kind)) {
        candidates.push({
          nodeId: node.id,
          index,
          node,
          resource,
          label: referenceAlias(node, index),
        })
      }
    })
  }
  return candidates
}

export function referenceAlias(node: ResourceNode, index: number): string {
  return `@${node.name}_${index}`
}

export function referenceKey(nodeId: UUIDString, index: number): string {
  return `${nodeId}:${index}`
}

export function canAddReference(
  currentReferences: CanvasResourceReference[],
  candidate: ReferenceCandidate,
  candidates: ReferenceCandidate[],
  model: CanvasFunctionDefinitionDTO,
): boolean {
  if (!functionSupportsReferences(model)) {
    return false
  }
  const key = referenceKey(candidate.nodeId, candidate.index)
  if (currentReferences.some((ref) => referenceKey(ref.nodeId, ref.index) === key)) {
    return false
  }
  const maxRefs = model.referencePolicy?.maxReferences ?? Infinity
  if (currentReferences.length >= maxRefs) {
    return false
  }
  const kindLimit = model.referencePolicy?.maxByKind?.[candidate.resource.kind]
  if (kindLimit !== undefined) {
    const candidateByKey = new Map(candidates.map((item) => [
      referenceKey(item.nodeId, item.index),
      item,
    ]))
    let currentKindCount = 0
    for (const ref of currentReferences) {
      const existing = candidateByKey.get(referenceKey(ref.nodeId, ref.index))
      if (existing && existing.resource.kind === candidate.resource.kind) {
        currentKindCount += 1
      }
    }
    if (currentKindCount >= kindLimit) {
      return false
    }
  }
  return true
}

export function filterConfigReferences(
  config: CanvasFunctionConfig,
  candidates: ReferenceCandidate[],
  model: CanvasFunctionDefinitionDTO,
): CanvasFunctionConfig {
  if (!functionSupportsReferences(model)) {
    return {
      ...config,
      references: [],
    }
  }
  const byKey = new Map(candidates.map((candidate) => [
    referenceKey(candidate.nodeId, candidate.index),
    candidate,
  ]))
  const accepted: CanvasResourceReference[] = []
  const seenKeys = new Set<string>()
  const kindCounts = new Map<CanvasResourceKind, number>()
  const maxRefs = model.referencePolicy?.maxReferences ?? Infinity

  for (const ref of config.references) {
    const key = referenceKey(ref.nodeId, ref.index)
    if (seenKeys.has(key)) {
      continue
    }
    const candidate = byKey.get(key)
    if (!candidate) {
      continue
    }
    const currentKindCount = kindCounts.get(candidate.resource.kind) ?? 0
    const kindLimit = model.referencePolicy?.maxByKind?.[candidate.resource.kind]
    if (
      accepted.length >= maxRefs
      || (kindLimit !== undefined && currentKindCount >= kindLimit)
    ) {
      continue
    }
    accepted.push(ref)
    seenKeys.add(key)
    kindCounts.set(candidate.resource.kind, currentKindCount + 1)
  }

  return {
    ...config,
    references: accepted,
  }
}
