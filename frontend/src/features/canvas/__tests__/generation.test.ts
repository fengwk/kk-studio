import { describe, expect, it } from 'vitest'
import type { CanvasSnapshot, ResourceNode } from '@/features/canvas/domain'
import {
  canInsertReference,
  createDefaultFunctionConfig,
  filterConfigReferences,
  insertReferenceAtCursor,
  parseFunctionConfig,
  promptVisibleText,
  referenceAlias,
  referenceCandidates,
  referenceKey,
  removePromptSegment,
  updateTextSegment,
} from '@/features/canvas/generation'
import type { CanvasFunctionModelDTO } from '@/shared/api/contracts/studio'

const imageModel: CanvasFunctionModelDTO = {
  key: 'fake-image',
  label: 'Fake Image',
  outputKind: 'IMAGE',
  referencePolicy: {
    allowedKinds: ['IMAGE'],
    maxReferences: 2,
    maxByKind: { IMAGE: 2 },
  },
  parameters: [{
    key: 'ratio',
    label: '比例',
    type: 'ENUM',
    required: false,
    defaultValue: 'AUTO',
    options: ['AUTO', '16:9'],
    min: null,
    max: null,
  }, {
    key: 'count',
    label: '数量',
    type: 'INTEGER',
    required: true,
    defaultValue: null,
    options: [],
    min: 1,
    max: 4,
  }],
  available: true,
  unavailableReason: null,
}

function node(
  id: `${bigint}`,
  name: string,
  kinds: Array<'IMAGE' | 'VIDEO'>,
  functionNode = false,
): ResourceNode {
  return {
    id,
    canvasId: '1',
    name,
    transform: { x: 0, y: 0, width: 320, height: 260 },
    groupId: null,
    resources: kinds.map((kind, index) => ({
      id: `${Number(id) * 10 + index}` as `${bigint}`,
      canvasId: '1',
      kind,
      mediaType: kind === 'IMAGE' ? 'image/png' : 'video/mp4',
      name: `${name}-${index}`,
      size: '3',
      text: null,
      metadata: {},
      createdAt: '2026-08-10T00:00:00Z',
    })),
    function: functionNode ? { modelKey: 'fake-image', configJson: '{}' } : null,
    run: null,
  }
}

function snapshot(): CanvasSnapshot {
  return {
    document: {
      id: '1',
      title: 'Board',
      graphRevision: '0',
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    resourceNodes: [
      node('2', 'single', ['IMAGE']),
      node('3', 'multi', ['IMAGE', 'IMAGE']),
      node('4', 'function-output', ['IMAGE'], true),
      node('5', 'video', ['VIDEO']),
      node('9', 'target', [], true),
    ],
    groups: [],
    links: [
      { canvasId: '1', sourceNodeId: '2', targetNodeId: '9' },
      { canvasId: '1', sourceNodeId: '3', targetNodeId: '9' },
      { canvasId: '1', sourceNodeId: '4', targetNodeId: '9' },
      { canvasId: '1', sourceNodeId: '5', targetNodeId: '9' },
    ],
  }
}

describe('Canvas structured generation config', () => {
  it('builds descriptor defaults and recovers malformed persisted config', () => {
    // Defaults prove ENUM and INTEGER descriptors drive UI config without model-specific branches.
    expect(createDefaultFunctionConfig(imageModel)).toEqual({
      prompt: { segments: [{ type: 'TEXT', text: '' }] },
      parameters: { ratio: 'AUTO', count: 1 },
    })
    expect(createDefaultFunctionConfig({
      ...imageModel,
      parameters: [{
        ...imageModel.parameters[0]!,
        defaultValue: null,
      }, {
        ...imageModel.parameters[1]!,
        key: 'optional',
        min: null,
      }],
    }).parameters).toEqual({ ratio: 'AUTO' })
    expect(parseFunctionConfig('{bad', imageModel).parameters).toEqual({
      ratio: 'AUTO',
      count: 1,
    })
    expect(parseFunctionConfig(JSON.stringify({
      prompt: { segments: [{ type: 'TEXT', text: 'valid' }] },
      parameters: { ratio: '16:9' },
    }), imageModel)).toEqual({
      prompt: { segments: [{ type: 'TEXT', text: 'valid' }] },
      parameters: { ratio: '16:9', count: 1 },
    })
    expect(parseFunctionConfig('null', imageModel)).toEqual(createDefaultFunctionConfig(imageModel))
    expect(parseFunctionConfig('{"prompt":[],"parameters":{}}', imageModel)).toEqual(
      createDefaultFunctionConfig(imageModel),
    )
    expect(parseFunctionConfig(JSON.stringify({
      prompt: { segments: [null] },
      parameters: {},
    }), imageModel)).toEqual(createDefaultFunctionConfig(imageModel))
  })

  it('filters incoming Link candidates by model kind and derives stable aliases', () => {
    // Only linked IMAGE resources are candidates; aliases remain derived display data.
    const candidates = referenceCandidates(snapshot(), '9', imageModel)
    expect(candidates.map((candidate) => candidate.label)).toEqual([
      '@single',
      '@multi[0]',
      '@multi[1]',
      '@function-output[0]',
    ])
    expect(referenceAlias(node('4', 'fn', ['IMAGE'], true), 0)).toBe('@fn[0]')
  })

  it('inserts at the caret, allows duplicate mentions, and merges text after chip deletion', () => {
    // Splitting the exact text segment proves persistence stores nodeId/index rather than alias text.
    const candidate = referenceCandidates(snapshot(), '9', imageModel)[0]
    expect(candidate).toBeDefined()
    const inserted = insertReferenceAtCursor(
      [{ type: 'TEXT', text: 'front back' }],
      { segmentIndex: 0, offset: 6 },
      candidate!,
    )
    expect(inserted).toEqual([
      { type: 'TEXT', text: 'front ' },
      { type: 'REFERENCE', nodeId: '2', index: 0 },
      { type: 'TEXT', text: 'back' },
    ])
    const duplicate = insertReferenceAtCursor(inserted, { segmentIndex: 2, offset: 4 }, candidate!)
    expect(duplicate.filter((segment) => segment.type === 'REFERENCE')).toHaveLength(2)
    expect(removePromptSegment(inserted, 1)).toEqual([{ type: 'TEXT', text: 'front back' }])
    expect(insertReferenceAtCursor(
      [{ type: 'REFERENCE', nodeId: '2', index: 0 }],
      { segmentIndex: 0, offset: 99 },
      candidate!,
    )).toEqual([
      { type: 'TEXT', text: '' },
      { type: 'REFERENCE', nodeId: '2', index: 0 },
      { type: 'REFERENCE', nodeId: '2', index: 0 },
      { type: 'TEXT', text: '' },
    ])
    expect(updateTextSegment(inserted, 0, 'changed')[0]).toEqual({
      type: 'TEXT',
      text: 'changed',
    })
    expect(updateTextSegment(inserted, 1, 'ignored')[1]).toEqual({
      type: 'REFERENCE',
      nodeId: '2',
      index: 0,
    })
    expect(promptVisibleText(inserted)).toBe('front back')
    expect(referenceKey('2', 0)).toBe('2:0')
  })

  it('removes unlinked or incompatible references while counting duplicate refs once', () => {
    // Model switching must retain compatible duplicate mentions but drop VIDEO and stale node refs.
    const candidates = referenceCandidates(snapshot(), '9', imageModel)
    const config = {
      prompt: {
        segments: [
          { type: 'TEXT' as const, text: 'a' },
          { type: 'REFERENCE' as const, nodeId: '2' as const, index: 0 },
          { type: 'REFERENCE' as const, nodeId: '2' as const, index: 0 },
          { type: 'REFERENCE' as const, nodeId: '5' as const, index: 0 },
          { type: 'REFERENCE' as const, nodeId: '88' as const, index: 0 },
        ],
      },
      parameters: { ratio: 'AUTO' },
    }
    const filtered = filterConfigReferences(config, candidates, imageModel)
    expect(filtered.prompt.segments.filter((segment) => segment.type === 'REFERENCE')).toEqual([
      { type: 'REFERENCE', nodeId: '2', index: 0 },
      { type: 'REFERENCE', nodeId: '2', index: 0 },
    ])
    expect(canInsertReference(filtered.prompt.segments, candidates[0]!, candidates, imageModel)).toBe(true)
  })

  it('enforces total and per-kind unique reference limits without blocking duplicate mentions', () => {
    // Limits apply to unique manifest entries; a repeated prompt mention remains legal.
    const candidates = referenceCandidates(snapshot(), '9', imageModel)
    const first = candidates[0]!
    const second = candidates[1]!
    const third = candidates[2]!
    const oneReference = [
      { type: 'TEXT' as const, text: 'x' },
      { type: 'REFERENCE' as const, nodeId: first.nodeId, index: first.index },
    ]
    const oneLimitModel = {
      ...imageModel,
      referencePolicy: {
        ...imageModel.referencePolicy,
        maxReferences: 1,
        maxByKind: {},
      },
    }
    expect(canInsertReference(oneReference, first, candidates, oneLimitModel)).toBe(true)
    expect(canInsertReference(oneReference, second, candidates, oneLimitModel)).toBe(false)

    const kindLimitModel = {
      ...imageModel,
      referencePolicy: {
        ...imageModel.referencePolicy,
        maxReferences: 3,
        maxByKind: { IMAGE: 1 },
      },
    }
    expect(canInsertReference(oneReference, second, candidates, kindLimitModel)).toBe(false)
    expect(canInsertReference([], third, candidates, {
      ...imageModel,
      referencePolicy: {
        ...imageModel.referencePolicy,
        maxByKind: {},
      },
    })).toBe(true)

    const filtered = filterConfigReferences({
      prompt: {
        segments: [
          ...oneReference,
          { type: 'REFERENCE', nodeId: second.nodeId, index: second.index },
          { type: 'REFERENCE', nodeId: second.nodeId, index: second.index },
        ],
      },
      parameters: {},
    }, candidates, oneLimitModel)
    expect(filtered.prompt.segments.filter((segment) => segment.type === 'REFERENCE')).toHaveLength(1)
  })
})
