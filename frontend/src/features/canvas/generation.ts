import type {
  CanvasFunctionConfigDTO,
  CanvasFunctionModelDTO,
  CanvasResourceKind,
  DecimalString,
  PromptSegmentDTO,
} from '@/shared/api/contracts/studio'
import type { CanvasSnapshot, Resource, ResourceNode } from '@/features/canvas/domain'

export interface ReferenceCandidate {
  nodeId: DecimalString
  index: number
  node: ResourceNode
  resource: Resource
  label: string
}

export interface PromptCursor {
  segmentIndex: number
  offset: number
}

export function createDefaultFunctionConfig(model: CanvasFunctionModelDTO): CanvasFunctionConfigDTO {
  const parameters: Record<string, string | number> = {}
  for (const parameter of model.parameters) {
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
  configJson: string,
  model: CanvasFunctionModelDTO,
): CanvasFunctionConfigDTO {
  try {
    const parsed: unknown = JSON.parse(configJson)
    if (!isConfig(parsed)) {
      return createDefaultFunctionConfig(model)
    }
    const defaults = createDefaultFunctionConfig(model)
    const parameters: Record<string, string | number> = { ...defaults.parameters }
    for (const definition of model.parameters) {
      const value = parsed.parameters[definition.key]
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
        segments: parsed.prompt.segments.map((segment) => ({ ...segment })),
      },
      parameters,
    }
  } catch {
    return createDefaultFunctionConfig(model)
  }
}

export function referenceCandidates(
  snapshot: CanvasSnapshot,
  targetNodeId: DecimalString,
  model: CanvasFunctionModelDTO,
): ReferenceCandidate[] {
  const linkedIds = new Set(snapshot.links
    .filter((link) => link.targetNodeId === targetNodeId)
    .map((link) => link.sourceNodeId))
  const candidates: ReferenceCandidate[] = []
  for (const node of snapshot.resourceNodes) {
    if (!linkedIds.has(node.id)) {
      continue
    }
    node.resources.forEach((resource, index) => {
      if (model.referencePolicy.allowedKinds.includes(resource.kind)) {
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
  return node.function || node.resources.length > 1
    ? `@${node.name}[${index}]`
    : `@${node.name}`
}

export function insertReferenceAtCursor(
  segments: PromptSegmentDTO[],
  cursor: PromptCursor,
  candidate: Pick<ReferenceCandidate, 'nodeId' | 'index'>,
): PromptSegmentDTO[] {
  const reference: PromptSegmentDTO = {
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
  segments: PromptSegmentDTO[],
  segmentIndex: number,
  text: string,
): PromptSegmentDTO[] {
  return segments.map((segment, index) => (
    index === segmentIndex && segment.type === 'TEXT'
      ? { type: 'TEXT', text }
      : segment
  ))
}

export function removePromptSegment(
  segments: PromptSegmentDTO[],
  segmentIndex: number,
): PromptSegmentDTO[] {
  return normalizePromptSegments(segments.filter((_segment, index) => index !== segmentIndex))
}

export function filterConfigReferences(
  config: CanvasFunctionConfigDTO,
  candidates: ReferenceCandidate[],
  model: CanvasFunctionModelDTO,
): CanvasFunctionConfigDTO {
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
    const kindLimit = model.referencePolicy.maxByKind[candidate.resource.kind]
    if (
      accepted.size >= model.referencePolicy.maxReferences
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
  segments: PromptSegmentDTO[],
  candidate: ReferenceCandidate,
  candidates: ReferenceCandidate[],
  model: CanvasFunctionModelDTO,
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
  if (unique.size >= model.referencePolicy.maxReferences) {
    return false
  }
  const kindLimit = model.referencePolicy.maxByKind[candidate.resource.kind]
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

export function promptVisibleText(segments: PromptSegmentDTO[]): string {
  return segments
    .filter((segment): segment is Extract<PromptSegmentDTO, { type: 'TEXT' }> => segment.type === 'TEXT')
    .map((segment) => segment.text)
    .join('')
}

export function referenceKey(nodeId: DecimalString, index: number): string {
  return `${nodeId}:${index}`
}

function normalizePromptSegments(segments: PromptSegmentDTO[]): PromptSegmentDTO[] {
  const normalized: PromptSegmentDTO[] = []
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

function isConfig(value: unknown): value is CanvasFunctionConfigDTO {
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
