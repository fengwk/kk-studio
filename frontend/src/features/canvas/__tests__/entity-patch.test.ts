import { describe, expect, it } from 'vitest'
import { applyEntityPatch } from '@/features/canvas/entity-patch'
import type {
  CanvasPatchDTO,
  CanvasSnapshotDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f' as UUIDString
const NODE_A = 'aaaaaaaa-1111-4111-8111-111111111111' as UUIDString
const NODE_B = 'bbbbbbbb-2222-4222-8222-222222222222' as UUIDString
const GROUP_A = 'cccccccc-3333-4333-8333-333333333333' as UUIDString

const NODE_DTO = (id: UUIDString) => ({
  id,
  canvasId: CANVAS_ID,
  name: `node-${id.slice(0, 4)}`,
  transform: { x: 0, y: 0, width: 320, height: 260 },
  groupId: null,
  resources: [],
  function: null,
  run: null,
})

const GROUP_DTO = (id: UUIDString) => ({
  id,
  canvasId: CANVAS_ID,
  title: 'group',
  transform: { x: 0, y: 0, width: 400, height: 300 },
})

function snapshot(revision: number | string): CanvasSnapshotDTO {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      revision: String(revision),
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    nodes: [NODE_DTO(NODE_A)],
    groups: [GROUP_DTO(GROUP_A)],
    references: [],
  }
}

describe('Canvas entity patch reducer', () => {
  it('upserts and removes nodes and groups while advancing revision', () => {
    const current = snapshot(3)
    const patch: CanvasPatchDTO = {
      revision: '4',
      nodes: [
        { op: 'UPSERT', node: { ...NODE_DTO(NODE_A), name: 'renamed' } },
        { op: 'UPSERT', node: NODE_DTO(NODE_B) },
      ],
      groups: [{ op: 'REMOVE', groupId: GROUP_A }],
    }

    const next = applyEntityPatch(current, patch)

    expect(next).not.toBeNull()
    expect(next?.document.revision).toBe('4')
    expect(next?.nodes.map((node) => node.id).sort()).toEqual([NODE_A, NODE_B])
    expect(next?.nodes.find((node) => node.id === NODE_A)?.name).toBe('renamed')
    expect(next?.groups).toEqual([])
    expect(current.document.revision).toBe('3')
    expect(current.nodes.map((node) => node.id)).toEqual([NODE_A])
  })

  it('ignores duplicate and older patches (revision not advanced)', () => {
    const current = snapshot(4)
    expect(applyEntityPatch(current, {
      revision: '4',
      groups: [],
      nodes: [],
    })).toBeNull()
    expect(applyEntityPatch(current, {
      revision: '2',
      groups: [],
      nodes: [],
    })).toBeNull()
  })

  it('compares 9 vs 10 and revisions beyond Number.MAX_SAFE_INTEGER exactly', () => {
    expect(applyEntityPatch(snapshot('9'), {
      revision: '10',
      groups: [],
      nodes: [],
    })).not.toBeNull()

    const huge = '9007199254740992'
    const hugeNext = '9007199254740993'
    expect(applyEntityPatch(snapshot(huge), {
      revision: hugeNext,
      groups: [],
      nodes: [],
    })).not.toBeNull()

    expect(applyEntityPatch(snapshot(hugeNext), {
      revision: huge,
      groups: [],
      nodes: [],
    })).toBeNull()
  })
})
