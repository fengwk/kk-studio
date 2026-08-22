import { describe, expect, it } from 'vitest'
import {
  buildContextMenuTarget,
  contextMenuTargetKey,
  isValidResourceConnection,
  sameIdList,
} from '@/features/canvas/canvas-stage-model'
import type {
  CanvasSnapshot,
  Group,
  Resource,
  ResourceNode,
} from '@/features/canvas/domain'
import type { CanvasFunctionModelDTO } from '@/shared/api/contracts/studio'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const GROUP_ID = 'c4d5e6f7-8a9b-4c0d-8e1f-2a3b4c5d6e7f'
const RESOURCE_NODE_ID = '9f1e6d2a-3b4c-4d5e-8f6a-7b8c9d0e1f2a'
const FUNCTION_NODE_ID = 'a2b3c4d5-6e7f-4a8b-9c0d-1e2f3a4b5c6d'
const EMPTY_NODE_ID = 'b3c4d5e6-7f8a-4b9c-8d0e-1f2a3b4c5d6e'

const model: CanvasFunctionModelDTO = {
  key: 'fake-image',
  label: 'Fake Image',
  outputKind: 'IMAGE',
  referencePolicy: { allowedKinds: ['IMAGE'], maxReferences: 1, maxByKind: {} },
  parameters: [],
  available: true,
  unavailableReason: null,
}

const transform = { x: 0, y: 0, width: 320, height: 260 }

function resource(overrides: Partial<Resource> = {}): Resource {
  return {
    id: 'd5e6f7a8-9b0c-4d1e-8f2a-3b4c5d6e7f8a',
    canvasId: CANVAS_ID,
    ownerNodeId: RESOURCE_NODE_ID,
    resourceIndex: 0,
    blobId: 'blob-asset',
    name: 'image.png',
    textContent: null,
    kind: 'IMAGE',
    mediaType: 'image/png',
    sizeBytes: 3,
    width: null,
    height: null,
    durationMs: null,
    createdAt: '2026-08-10T00:00:00Z',
    ...overrides,
  }
}

function resourceNode(overrides: Partial<ResourceNode> = {}): ResourceNode {
  return {
    id: RESOURCE_NODE_ID,
    canvasId: CANVAS_ID,
    name: 'Image',
    transform,
    groupId: null,
    resources: [resource()],
    function: null,
    run: null,
    ...overrides,
  }
}

function group(overrides: Partial<Group> = {}): Group {
  return {
    id: GROUP_ID,
    canvasId: CANVAS_ID,
    title: 'Frame',
    transform,
    ...overrides,
  }
}

function snapshot(overrides: Partial<CanvasSnapshot> = {}): CanvasSnapshot {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      version: '0',
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    resourceNodes: [resourceNode()],
    groups: [group()],
    links: [],
    ...overrides,
  }
}

describe('buildContextMenuTarget', () => {
  it('builds a resource target with the matched function model', () => {
    const target = buildContextMenuTarget(snapshot({
      resourceNodes: [resourceNode({
        id: FUNCTION_NODE_ID,
        function: { modelKey: 'fake-image', configJson: '{}' },
      })],
    }), [FUNCTION_NODE_ID], [model])

    expect(target).toEqual({
      kind: 'resource',
      node: expect.objectContaining({ id: FUNCTION_NODE_ID }),
      model,
    })
  })

  it('keeps the model null when the model list has no match', () => {
    const target = buildContextMenuTarget(snapshot({
      resourceNodes: [resourceNode({
        id: FUNCTION_NODE_ID,
        function: { modelKey: 'missing-model', configJson: '{}' },
      })],
    }), [FUNCTION_NODE_ID], [])

    expect(target).toMatchObject({ kind: 'resource', model: null })
  })

  it('builds a group target from the group flow id', () => {
    const target = buildContextMenuTarget(snapshot(), [`group:${GROUP_ID}`], [])

    expect(target).toEqual({ kind: 'group', group: expect.objectContaining({ id: GROUP_ID }) })
  })

  it('returns null for an unresolvable group flow id, missing node, or missing snapshot entity', () => {
    expect(buildContextMenuTarget(snapshot(), ['group:00000000-0000-4000-8000-000000000000'], [])).toBeNull()
    expect(buildContextMenuTarget(snapshot(), ['missing-node'], [])).toBeNull()
    expect(buildContextMenuTarget(snapshot({ groups: [] }), [`group:${GROUP_ID}`], [])).toBeNull()
  })

  it('builds a multi target flagging all-ungrouped resources', () => {
    const target = buildContextMenuTarget(snapshot({
      resourceNodes: [
        resourceNode({ id: RESOURCE_NODE_ID }),
        resourceNode({ id: EMPTY_NODE_ID }),
      ],
    }), [RESOURCE_NODE_ID, EMPTY_NODE_ID], [])

    expect(target).toEqual({
      kind: 'multi',
      nodeIds: [RESOURCE_NODE_ID, EMPTY_NODE_ID],
      hasUngroupedResource: true,
    })
  })

  it('flags multi targets with any grouped member as not groupable', () => {
    const target = buildContextMenuTarget(snapshot({
      resourceNodes: [
        resourceNode({ id: RESOURCE_NODE_ID }),
        resourceNode({ id: EMPTY_NODE_ID, groupId: GROUP_ID }),
      ],
    }), [RESOURCE_NODE_ID, EMPTY_NODE_ID], [])

    expect(target).toMatchObject({ kind: 'multi', hasUngroupedResource: false })
  })

  it('builds multi targets even when some ids are missing from the snapshot', () => {
    const target = buildContextMenuTarget(snapshot(), [RESOURCE_NODE_ID, 'ghost-id'], [])

    expect(target).toEqual({
      kind: 'multi',
      nodeIds: [RESOURCE_NODE_ID, 'ghost-id'],
      hasUngroupedResource: false,
    })
  })
})

describe('isValidResourceConnection', () => {
  it('accepts a resource node with assets targeting a function node', () => {
    const current = snapshot({
      resourceNodes: [
        resourceNode(),
        resourceNode({
          id: FUNCTION_NODE_ID,
          resources: [],
          function: { modelKey: 'fake-image', configJson: '{}' },
        }),
      ],
    })

    expect(isValidResourceConnection(current, RESOURCE_NODE_ID, FUNCTION_NODE_ID)).toBe(true)
  })

  it('rejects reversed direction, missing endpoints, self-loops, and empty snapshots', () => {
    const current = snapshot({
      resourceNodes: [
        resourceNode(),
        resourceNode({
          id: FUNCTION_NODE_ID,
          resources: [],
          function: { modelKey: 'fake-image', configJson: '{}' },
        }),
      ],
    })

    expect(isValidResourceConnection(current, FUNCTION_NODE_ID, RESOURCE_NODE_ID)).toBe(false)
    expect(isValidResourceConnection(current, RESOURCE_NODE_ID, 'ghost-id')).toBe(false)
    expect(isValidResourceConnection(current, 'ghost-id', FUNCTION_NODE_ID)).toBe(false)
    expect(isValidResourceConnection(current, RESOURCE_NODE_ID, RESOURCE_NODE_ID)).toBe(false)
    expect(isValidResourceConnection(null, RESOURCE_NODE_ID, FUNCTION_NODE_ID)).toBe(false)
  })

  it('rejects a resource source without assets and a plain target', () => {
    const current = snapshot({
      resourceNodes: [
        resourceNode({ id: EMPTY_NODE_ID, resources: [] }),
        resourceNode({ id: RESOURCE_NODE_ID, resources: [] }),
      ],
    })

    expect(isValidResourceConnection(current, EMPTY_NODE_ID, RESOURCE_NODE_ID)).toBe(false)
  })
})

describe('contextMenuTargetKey', () => {
  it('encodes each target kind stably', () => {
    expect(contextMenuTargetKey({
      kind: 'resource',
      node: resourceNode(),
      model: null,
    })).toBe(`resource:${RESOURCE_NODE_ID}`)
    expect(contextMenuTargetKey({ kind: 'group', group: group() })).toBe(`group:${GROUP_ID}`)
    expect(contextMenuTargetKey({
      kind: 'multi',
      nodeIds: [RESOURCE_NODE_ID, FUNCTION_NODE_ID],
      hasUngroupedResource: true,
    })).toBe(`multi:${RESOURCE_NODE_ID},${FUNCTION_NODE_ID}`)
  })
})

describe('sameIdList', () => {
  it('compares lists as sets regardless of order', () => {
    expect(sameIdList(['a', 'b'], ['b', 'a'])).toBe(true)
    expect(sameIdList(['a', 'b'], ['a', 'b'])).toBe(true)
  })

  it('rejects different lengths, duplicates, and disjoint lists', () => {
    expect(sameIdList(['a'], ['a', 'b'])).toBe(false)
    expect(sameIdList(['a', 'a'], ['a'])).toBe(false)
    expect(sameIdList(['a', 'b'], ['a', 'c'])).toBe(false)
    expect(sameIdList([], ['a'])).toBe(false)
    expect(sameIdList(['a'], [])).toBe(false)
  })
})
