import { describe, expect, it, vi } from 'vitest'
import { projectCanvasSnapshot } from '@/features/canvas/domain'
import {
  groupIdFromFlowId,
  projectEdges,
  projectNodes,
} from '@/features/canvas/projection'
import type { CanvasSnapshotDTO, UUIDString } from '@/shared/api/contracts/studio'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f' as UUIDString
const NODE_IMAGE = 'aaaaaaaa-1111-4111-8111-111111111111' as UUIDString
const NODE_NOTE = 'bbbbbbbb-2222-4222-8222-222222222222' as UUIDString
const GROUP_A = 'cccccccc-3333-4333-8333-333333333333' as UUIDString

const snapshotDTO: CanvasSnapshotDTO = {
  document: {
    id: CANVAS_ID,
    title: 'Board',
    revision: '3',
    createdAt: '2026-08-10T00:00:00Z',
    updatedAt: '2026-08-10T00:00:00Z',
  },
  nodes: [{
    id: NODE_IMAGE,
    canvasId: CANVAS_ID,
    name: 'image',
    transform: { x: 20, y: 30, width: 320, height: 260 },
    groupId: GROUP_A,
    resources: [{
      id: 'dddddddd-4444-4444-8444-444444444444' as UUIDString,
      canvasId: CANVAS_ID,
      ownerNodeId: NODE_IMAGE,
      resourceIndex: 0,
      blobId: 'blob-image' as UUIDString,
      name: 'image.png',
      textContent: null,
      kind: 'IMAGE',
      mediaType: 'image/png',
      sizeBytes: Number.MAX_SAFE_INTEGER,
      width: 1024,
      height: 768,
      durationMs: null,
      createdAt: '2026-08-10T00:00:00Z',
    }],
    function: {
      name: 'fake-image',
      args: {
        prompt: { segments: [{ type: 'TEXT', text: 'x' }] },
        parameters: {},
      },
    },
    run: {
      nodeId: NODE_IMAGE,
      requestId: 'c3c3c3c3-3333-4333-8333-333333333331' as UUIDString,
      status: 'RUNNING',
      stage: 'QUEUED',
      error: null,
      updatedAt: '2026-08-10T00:00:00Z',
    },
  }, {
    id: NODE_NOTE,
    canvasId: CANVAS_ID,
    name: 'note',
    transform: { x: 600, y: 30, width: 320, height: 260 },
    groupId: null,
    resources: [{
      id: 'eeeeeeee-5555-4555-8555-555555555555' as UUIDString,
      canvasId: CANVAS_ID,
      ownerNodeId: NODE_NOTE,
      resourceIndex: 0,
      blobId: null,
      name: 'note.md',
      textContent: 'note',
      kind: 'TEXT',
      mediaType: 'text/markdown',
      sizeBytes: 4,
      width: null,
      height: null,
      durationMs: null,
      createdAt: '2026-08-10T00:00:00Z',
    }, {
      id: 'ffffffff-6666-4666-8666-666666666666' as UUIDString,
      canvasId: CANVAS_ID,
      ownerNodeId: NODE_NOTE,
      resourceIndex: 1,
      blobId: null,
      name: 'broken.md',
      textContent: 'x',
      kind: 'TEXT',
      mediaType: 'text/markdown',
      sizeBytes: 1,
      width: null,
      height: null,
      durationMs: null,
      createdAt: '2026-08-10T00:00:00Z',
    }],
    function: null,
    run: null,
  }],
  groups: [{
    id: GROUP_A,
    canvasId: CANVAS_ID,
    title: 'group',
    transform: { x: 0, y: 0, width: 500, height: 400 },
  }],
  references: [{
    canvasId: CANVAS_ID,
    sourceNodeId: NODE_NOTE,
    targetNodeId: NODE_IMAGE,
    index: 0,
  }],
}

describe('Canvas snapshot projection', () => {
  it('projects DTOs into clean ResourceNode/Resource/Function/Run/Group/Link values', () => {
    const snapshot = projectCanvasSnapshot(snapshotDTO)

    expect(snapshot.document.revision).toBe('3')
    expect(snapshot.resourceNodes[0]).toMatchObject({
      id: NODE_IMAGE,
      function: { name: 'fake-image' },
      run: { requestId: 'c3c3c3c3-3333-4333-8333-333333333331', status: 'RUNNING' },
      resources: [{
        id: 'dddddddd-4444-4444-8444-444444444444',
        ownerNodeId: NODE_IMAGE,
        resourceIndex: 0,
        blobId: 'blob-image',
        textContent: null,
        sizeBytes: Number.MAX_SAFE_INTEGER,
        width: 1024,
        height: 768,
        durationMs: null,
      }],
    })
    expect(snapshot.resourceNodes[1]?.resources[0]?.width).toBeNull()
    expect(snapshot.resourceNodes[1]?.resources[1]?.height).toBeNull()
    expect(snapshot.groups[0]).toMatchObject({ id: GROUP_A, title: 'group' })
    expect(snapshot.references[0]).toEqual({
      canvasId: CANVAS_ID,
      sourceNodeId: NODE_NOTE,
      targetNodeId: NODE_IMAGE,
      index: 0,
    })
  })

  it('projects a single media renderer at its source-ratio size', () => {
    const snapshot = projectCanvasSnapshot({
      ...snapshotDTO,
      nodes: [{
        ...snapshotDTO.nodes[0],
        id: 'aaaaaaaa-7777-4777-8777-777777777777' as UUIDString,
        groupId: null,
        function: null,
        run: null,
        resources: [{
          ...snapshotDTO.nodes[0].resources[0],
          id: 'aaaaaaaa-8888-4888-8888-888888888888' as UUIDString,
          width: 1122,
          height: 1402,
        }],
      }],
      groups: [],
      references: [],
    })
    expect(snapshot.resourceNodes[0]?.transform).toEqual({
      x: 20,
      y: 30,
      width: 256,
      height: 346,
    })

    const nodes = projectNodes(snapshot, [], [], {
      renameNode: vi.fn(),
      editTextNode: vi.fn(),
      deleteNode: vi.fn(),
    })
    expect(nodes[0]).toMatchObject({
      style: { width: 256, height: 346 },
      measured: { width: 256, height: 346 },
    })
  })

  it('keeps groups in world coordinates and applies group drafts to members without parent transforms', () => {
    const snapshot = projectCanvasSnapshot(snapshotDTO)
    const callbacks = {
      renameNode: vi.fn(),
      editTextNode: vi.fn(),
      deleteNode: vi.fn(),
    }
    const nodes = projectNodes(snapshot, [NODE_IMAGE], [], callbacks, {
      [`group:${GROUP_A}`]: { x: 100, y: -20 },
    })
    const group = nodes.find((node) => node.id === `group:${GROUP_A}`)
    const member = nodes.find((node) => node.id === NODE_IMAGE)
    const outside = nodes.find((node) => node.id === NODE_NOTE)

    expect(group).toMatchObject({
      type: 'group',
      position: { x: 100, y: -20 },
      zIndex: -1,
    })
    expect(group).not.toHaveProperty('parentId')
    expect(member).toMatchObject({
      type: 'resource',
      selected: true,
      position: { x: 120, y: 10 },
      zIndex: 1,
    })
    expect(member).not.toHaveProperty('dragHandle')
    expect(member).not.toHaveProperty('parentId')
    expect(outside?.position).toEqual({ x: 600, y: 30 })
  })

  it('uses reference endpoints as edge identity and keeps edges operable', () => {
    const snapshot = projectCanvasSnapshot(snapshotDTO)
    expect(projectEdges(snapshot.references)[0]).toMatchObject({
      id: `${NODE_NOTE}->${NODE_IMAGE}`,
      source: NODE_NOTE,
      target: NODE_IMAGE,
      sourceHandle: 'out',
      targetHandle: 'in',
      selectable: true,
      focusable: true,
    })
    expect(projectEdges(snapshot.references, [{
      sourceNodeId: NODE_NOTE,
      targetNodeId: NODE_IMAGE,
    }])[0]?.selected).toBe(true)
    expect(groupIdFromFlowId(`group:${GROUP_A}`)).toBe(GROUP_A)
    expect(groupIdFromFlowId(`group:0${GROUP_A}`)).toBeNull()
    // 非 canonical UUID flow id 一律拒绝。
    expect(groupIdFromFlowId('group:invalid-group-id')).toBeNull()
    expect(groupIdFromFlowId('group:')).toBeNull()
    expect(groupIdFromFlowId(NODE_IMAGE)).toBeNull()
  })

  it('deduplicates edge display by endpoint pair while retaining multiple references with distinct port indices', () => {
    const multiRefSnapshot = projectCanvasSnapshot({
      ...snapshotDTO,
      references: [
        { canvasId: CANVAS_ID, sourceNodeId: NODE_NOTE, targetNodeId: NODE_IMAGE, index: 0 },
        { canvasId: CANVAS_ID, sourceNodeId: NODE_NOTE, targetNodeId: NODE_IMAGE, index: 1 },
      ],
    })
    // 1. 验证 snapshot.references 完整保留两个引用及其各自独立的 index（不丢端口 index）
    expect(multiRefSnapshot.references).toHaveLength(2)
    expect(multiRefSnapshot.references[0]?.index).toBe(0)
    expect(multiRefSnapshot.references[1]?.index).toBe(1)
    expect(multiRefSnapshot.references[0]?.sourceNodeId).toBe(NODE_NOTE)
    expect(multiRefSnapshot.references[1]?.sourceNodeId).toBe(NODE_NOTE)

    // 2. 验证 projectEdges 渲染时对同一对端点执行去重，仅生成一条 edge，避免 React Flow 渲染重复边或 ID 冲突
    const edges = projectEdges(multiRefSnapshot.references)
    expect(edges).toHaveLength(1)
    expect(edges[0]?.id).toBe(`${NODE_NOTE}->${NODE_IMAGE}`)
    expect(edges[0]?.source).toBe(NODE_NOTE)
    expect(edges[0]?.target).toBe(NODE_IMAGE)
  })
})
