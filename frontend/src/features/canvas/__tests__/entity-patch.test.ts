import { describe, expect, it } from 'vitest'
import {
  applyCanvasChanges,
  applyEntityPatch,
} from '@/features/canvas/entity-patch'
import type {
  CanvasChangesDTO,
  CanvasPatchDTO,
  CanvasSnapshotDTO,
} from '@/shared/api/contracts/studio'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const NODE_A = 'aaaaaaaa-1111-4111-8111-111111111111'
const NODE_B = 'bbbbbbbb-2222-4222-8222-222222222222'
const GROUP_A = 'cccccccc-3333-4333-8333-333333333333'
const NODE_DTO = (id: string) => ({
  id,
  canvasId: CANVAS_ID,
  name: `node-${id.slice(0, 4)}`,
  transform: { x: 0, y: 0, width: 320, height: 260 },
  groupId: null,
  resources: [],
  function: null,
  run: null,
})
const GROUP_DTO = (id: string) => ({
  id,
  canvasId: CANVAS_ID,
  title: 'group',
  transform: { x: 0, y: 0, width: 400, height: 300 },
})
const LINK_DTO = (sourceNodeId: string, targetNodeId: string) => ({
  canvasId: CANVAS_ID,
  sourceNodeId,
  targetNodeId,
})

function snapshot(version: number): CanvasSnapshotDTO {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      version,
      threadId: null,
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    nodes: [NODE_DTO(NODE_A)],
    groups: [GROUP_DTO(GROUP_A)],
    links: [LINK_DTO(NODE_A, NODE_B)],
  }
}

describe('Canvas entity patch reducer', () => {
  it('upserts and removes nodes/groups/links while advancing version', () => {
    const current = snapshot(3)
    const patch: CanvasPatchDTO = {
      baseVersion: 3,
      version: 4,
      nodes: [
        { op: 'UPSERT', node: { ...NODE_DTO(NODE_A), name: 'renamed' } },
        { op: 'UPSERT', node: NODE_DTO(NODE_B) },
      ],
      groups: [{ op: 'REMOVE', groupId: GROUP_A }],
      links: [{ op: 'REMOVE', sourceNodeId: NODE_A, targetNodeId: NODE_B }],
    }

    const next = applyEntityPatch(current, patch)

    expect(next).not.toBeNull()
    expect(next?.document.version).toBe(4)
    expect(next?.document.threadId).toBeNull()
    expect(next?.nodes.map((node) => node.id).sort()).toEqual([NODE_A, NODE_B])
    expect(next?.nodes.find((node) => node.id === NODE_A)?.name).toBe('renamed')
    expect(next?.groups).toEqual([])
    expect(next?.links).toEqual([])
    // 输入快照保持不变（不可变 reducer）。
    expect(current.document.version).toBe(3)
    expect(current.nodes.map((node) => node.id)).toEqual([NODE_A])
  })

  it('ignores duplicate and older patches (version not advanced)', () => {
    const current = snapshot(4)
    expect(applyEntityPatch(current, {
      baseVersion: 3,
      version: 4,
      groups: [],
      nodes: [],
      links: [],
    })).toBeNull()
    expect(applyEntityPatch(current, {
      baseVersion: 2,
      version: 2,
      groups: [],
      nodes: [],
      links: [],
    })).toBeNull()
  })

  it('returns null on a base gap even when the target version is newer', () => {
    const current = snapshot(2)
    expect(applyEntityPatch(current, {
      baseVersion: 3,
      version: 5,
      groups: [],
      nodes: [],
      links: [],
    })).toBeNull()
    // 重叠区间（base 落后但 version 超前）同样视为 gap。
    expect(applyEntityPatch(current, {
      baseVersion: 1,
      version: 4,
      groups: [],
      nodes: [],
      links: [],
    })).toBeNull()
  })

  it('folds continuous patches in order and rejects a broken chain', () => {
    const current = snapshot(1)
    const changes: CanvasChangesDTO = {
      patches: [
        { baseVersion: 1, version: 2, nodes: [{ op: 'UPSERT', node: NODE_DTO(NODE_B) }], groups: [], links: [] },
        { baseVersion: 2, version: 3, nodes: [{ op: 'REMOVE', nodeId: NODE_A }], groups: [], links: [] },
      ],
      snapshot: null,
    }

    const folded = applyCanvasChanges(current, changes)

    expect(folded?.document.version).toBe(3)
    expect(folded?.nodes.map((node) => node.id)).toEqual([NODE_B])

    const broken = applyCanvasChanges(current, {
      patches: [
        { baseVersion: 1, version: 2, nodes: [], groups: [], links: [] },
        { baseVersion: 3, version: 4, nodes: [], groups: [], links: [] },
      ],
      snapshot: null,
    })
    expect(broken).toBeNull()
  })

  it('prefers the returned snapshot and rejects an older one', () => {
    const current = snapshot(5)
    const fresh = { ...snapshot(7), document: { ...snapshot(7).document, title: 'fresh' } }
    expect(applyCanvasChanges(current, {
      patches: [],
      snapshot: fresh,
    })?.document.title).toBe('fresh')

    expect(applyCanvasChanges(current, {
      patches: [],
      snapshot: snapshot(4),
    })).toBeNull()
  })

  it('keeps the current state when changes report no progress', () => {
    const current = snapshot(5)
    const unchanged = applyCanvasChanges(current, { patches: [], snapshot: null })
    expect(unchanged).toBe(current)
  })
})
