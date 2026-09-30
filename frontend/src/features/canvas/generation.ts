import type {
  CanvasFunctionDefinitionDTO,
  CanvasResourceKind,
  UUIDString,
} from '@/shared/api/contracts/studio'
import type { CanvasFunctionConfig, PromptSegment } from '@/features/canvas/types'
import type { CanvasSnapshot, Resource, ResourceNode } from '@/features/canvas/domain'

export interface ReferenceCandidate {
  nodeId: UUIDString
  index: number
  node: ResourceNode
  resource: Resource
  label: string
}

export interface PromptCursor {
  segmentIndex: number
  offset: number
}

export interface CanvasFunctionParameterDefinition {
  key: string
  label: string
  type: 'ENUM' | 'INTEGER'
  required: boolean
  defaultValue: string | number | null
  options: string[]
  min: number | null
  max: number | null
}

export function extractParametersFromDefinition(model: CanvasFunctionDefinitionDTO): CanvasFunctionParameterDefinition[] {
  const schema = model.argsSchema
  if (!schema || typeof schema !== 'object' || !schema.properties || typeof schema.properties !== 'object') {
    return []
  }
  const required = Array.isArray(schema.required) ? new Set(schema.required) : new Set<string>()
  const params: CanvasFunctionParameterDefinition[] = []
  for (const [key, prop] of Object.entries(schema.properties as Record<string, unknown>)) {
    if (!prop || typeof prop !== 'object') continue
    const p = prop as Record<string, unknown>
    if (Array.isArray(p.enum)) {
      params.push({
        key,
        label: typeof p.title === 'string' ? p.title : key,
        type: 'ENUM',
        required: required.has(key),
        defaultValue: typeof p.default === 'string' || typeof p.default === 'number' ? p.default : (p.enum[0] ?? null),
        options: p.enum.map(String),
        min: null,
        max: null,
      })
    } else if (p.type === 'integer' || p.type === 'number') {
      params.push({
        key,
        label: typeof p.title === 'string' ? p.title : key,
        type: 'INTEGER',
        required: required.has(key),
        defaultValue: typeof p.default === 'number' ? p.default : null,
        options: [],
        min: typeof p.minimum === 'number' ? p.minimum : null,
        max: typeof p.maximum === 'number' ? p.maximum : null,
      })
    }
  }
  return params
}

export function createDefaultFunctionConfig(model: CanvasFunctionDefinitionDTO): CanvasFunctionConfig {
  const parameters: Record<string, string | number> = {}
  const paramDefs = extractParametersFromDefinition(model)
  for (const parameter of paramDefs) {
    if (parameter.defaultValue !== null) {
      parameters[parameter.key] = parameter.defaultValue
    } else if (parameter.type === 'ENUM' && parameter.options[0] !== undefined) {
      parameters[parameter.key] = parameter.options[0]
    } else if (parameter.type === 'INTEGER' && parameter.min !== null) {
      parameters[parameter.key] = parameter.min
    }
  }
  return {
    prompt: { segments: [{ type: 'TEXT', text: '' }] },
    parameters,
  }
}

export function parseFunctionConfig(
  args: Record<string, unknown> | null | undefined,
  model: CanvasFunctionDefinitionDTO,
): CanvasFunctionConfig {
  if (!isConfig(args)) {
    return createDefaultFunctionConfig(model)
  }
  const defaults = createDefaultFunctionConfig(model)
  const parameters: Record<string, string | number> = { ...defaults.parameters }
  const paramDefs = extractParametersFromDefinition(model)
  for (const definition of paramDefs) {
    const value = args.parameters[definition.key]
    if (
      definition.type === 'ENUM'
      && typeof value === 'string'
      && definition.options.includes(value)
    ) {
      parameters[definition.key] = value
    } else if (
      definition.type === 'INTEGER'
      && typeof value === 'number'
      && Number.isInteger(value)
      && (definition.min === null || value >= definition.min)
      && (definition.max === null || value <= definition.max)
    ) {
      parameters[definition.key] = value
    }
  }
  return {
    prompt: {
      segments: args.prompt.segments.map((segment) => ({ ...segment })),
    },
    parameters,
  }
}

export function referenceCandidates(
  snapshot: CanvasSnapshot,
  targetNodeId: UUIDString,
  model: CanvasFunctionDefinitionDTO,
): ReferenceCandidate[] {
  const linkedIds = new Set(snapshot.references
    .filter((ref) => ref.targetNodeId === targetNodeId)
    .map((ref) => ref.sourceNodeId))
  const candidates: ReferenceCandidate[] = []
  for (const node of snapshot.resourceNodes) {
    if (!linkedIds.has(node.id)) {
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

export function insertReferenceAtCursor(
  segments: PromptSegment[],
  cursor: PromptCursor,
  candidate: Pick<ReferenceCandidate, 'nodeId' | 'index'>,
): PromptSegment[] {
  const reference: PromptSegment = {
    type: 'REFERENCE',
    nodeId: candidate.nodeId,
    index: candidate.index,
  }
  const target = segments[cursor.segmentIndex]
  if (target?.type !== 'TEXT') {
    return normalizePromptSegments([...segments, reference, { type: 'TEXT', text: '' }])
  }
  const offset = Math.max(0, Math.min(cursor.offset, target.text.length))
  return normalizePromptSegments([
    ...segments.slice(0, cursor.segmentIndex),
    { type: 'TEXT', text: target.text.slice(0, offset) },
    reference,
    { type: 'TEXT', text: target.text.slice(offset) },
    ...segments.slice(cursor.segmentIndex + 1),
  ])
}

export function updateTextSegment(
  segments: PromptSegment[],
  segmentIndex: number,
  text: string,
): PromptSegment[] {
  return segments.map((segment, index) => (
    index === segmentIndex && segment.type === 'TEXT'
      ? { type: 'TEXT', text }
      : segment
  ))
}

export function removePromptSegment(
  segments: PromptSegment[],
  segmentIndex: number,
): PromptSegment[] {
  return normalizePromptSegments(segments.filter((_segment, index) => index !== segmentIndex))
}

export function filterConfigReferences(
  config: CanvasFunctionConfig,
  candidates: ReferenceCandidate[],
  model: CanvasFunctionDefinitionDTO,
): CanvasFunctionConfig {
  const byKey = new Map(candidates.map((candidate) => [
    referenceKey(candidate.nodeId, candidate.index),
    candidate,
  ]))
  const accepted = new Set<string>()
  const rejected = new Set<string>()
  const kindCounts = new Map<CanvasResourceKind, number>()
  const segments = config.prompt.segments.filter((segment) => {
    if (segment.type === 'TEXT') {
      return true
    }
    const key = referenceKey(segment.nodeId, segment.index)
    if (accepted.has(key)) {
      return true
    }
    if (rejected.has(key)) {
      return false
    }
    const candidate = byKey.get(key)
    if (!candidate) {
      rejected.add(key)
      return false
    }
    const currentKindCount = kindCounts.get(candidate.resource.kind) ?? 0
    const kindLimit = model.referencePolicy?.maxByKind?.[candidate.resource.kind]
    const maxRefs = model.referencePolicy?.maxReferences ?? Infinity
    if (
      accepted.size >= maxRefs
      || (kindLimit !== undefined && currentKindCount >= kindLimit)
    ) {
      rejected.add(key)
      return false
    }
    accepted.add(key)
    kindCounts.set(candidate.resource.kind, currentKindCount + 1)
    return true
  })
  return {
    prompt: { segments: normalizePromptSegments(segments) },
    parameters: { ...config.parameters },
  }
}

export function canInsertReference(
  segments: PromptSegment[],
  candidate: ReferenceCandidate,
  candidates: ReferenceCandidate[],
  model: CanvasFunctionDefinitionDTO,
): boolean {
  const key = referenceKey(candidate.nodeId, candidate.index)
  const candidateByKey = new Map(candidates.map((item) => [
    referenceKey(item.nodeId, item.index),
    item,
  ]))
  const unique = new Map<string, CanvasResourceKind>()
  for (const segment of segments) {
    if (segment.type === 'REFERENCE') {
      const segmentKey = referenceKey(segment.nodeId, segment.index)
      if (segmentKey === key) {
        return true
      }
      const existing = candidateByKey.get(segmentKey)
      if (existing) {
        unique.set(segmentKey, existing.resource.kind)
      }
    }
  }
  if (model.referencePolicy?.maxReferences != null && unique.size >= model.referencePolicy.maxReferences) {
    return false
  }
  const kindLimit = model.referencePolicy?.maxByKind?.[candidate.resource.kind]
  if (kindLimit === undefined) {
    return true
  }
  let count = 0
  for (const kind of unique.values()) {
    if (kind === candidate.resource.kind) {
      count += 1
    }
  }
  return count < kindLimit
}

export function promptVisibleText(segments: PromptSegment[]): string {
  return segments
    .filter((segment): segment is Extract<PromptSegment, { type: 'TEXT' }> => segment.type === 'TEXT')
    .map((segment) => segment.text)
    .join('')
}

export function referenceKey(nodeId: UUIDString, index: number): string {
  return `${nodeId}:${index}`
}

function normalizePromptSegments(segments: PromptSegment[]): PromptSegment[] {
  const normalized: PromptSegment[] = []
  for (const segment of segments) {
    const previous = normalized.at(-1)
    if (segment.type === 'TEXT' && previous?.type === 'TEXT') {
      previous.text += segment.text
    } else {
      normalized.push({ ...segment })
    }
  }
  if (normalized.length === 0 || normalized[0]?.type !== 'TEXT') {
    normalized.unshift({ type: 'TEXT', text: '' })
  }
  if (normalized.at(-1)?.type !== 'TEXT') {
    normalized.push({ type: 'TEXT', text: '' })
  }
  return normalized
}

function isConfig(value: unknown): value is CanvasFunctionConfig {
  if (!value || typeof value !== 'object') {
    return false
  }
  const prompt = (value as { prompt?: unknown }).prompt
  const parameters = (value as { parameters?: unknown }).parameters
  if (!prompt || typeof prompt !== 'object' || !parameters || typeof parameters !== 'object' || Array.isArray(parameters)) {
    return false
  }
  const segments = (prompt as { segments?: unknown }).segments
  return Array.isArray(segments) && segments.every((segment) => {
    if (!segment || typeof segment !== 'object') {
      return false
    }
    const type = (segment as { type?: unknown }).type
    return type === 'TEXT'
      ? typeof (segment as { text?: unknown }).text === 'string'
      : type === 'REFERENCE'
        && /^[1-9][0-9]*$/.test(String((segment as { nodeId?: unknown }).nodeId))
        && Number.isInteger((segment as { index?: unknown }).index)
        && Number((segment as { index?: number }).index) >= 0
  })
}
