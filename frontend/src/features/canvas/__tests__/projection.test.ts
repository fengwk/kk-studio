import { describe, expect, it, vi } from 'vitest'
import { projectCanvasSnapshot } from '@/features/canvas/domain'
import {
  groupIdFromFlowId,
  projectEdges,
  projectNodes,
} from '@/features/canvas/projection'
import type { CanvasSnapshotDTO } from '@/shared/api/contracts/studio'

const snapshotDTO: CanvasSnapshotDTO = {
  document: {
    id: '1',
    title: 'Board',
    graphRevision: '3',
    createdAt: '2026-08-10T00:00:00Z',
    updatedAt: '2026-08-10T00:00:00Z',
  },
  nodes: [{
    id: '2',
    canvasId: '1',
    name: 'image',
    transform: { x: 20, y: 30, width: 320, height: 260 },
    groupId: '4',
    resources: [{
      id: '20',
      canvasId: '1',
      kind: 'IMAGE',
      mediaType: 'image/png',
      name: 'image.png',
      size: '9223372036854775807',
      textContent: null,
      metadataJson: '{"width":1024,"height":768}',
      createdAt: '2026-08-10T00:00:00Z',
    }],
    function: {
      modelKey: 'fake-image',
      configJson: '{"prompt":{"segments":[{"type":"TEXT","text":"x"}]},"parameters":{}}',
    },
    run: {
      nodeId: '2',
      requestId: 'request-1',
      status: 'RUNNING',
      stage: 'QUEUED',
      error: null,
      updatedAt: '2026-08-10T00:00:00Z',
    },
  }, {
    id: '3',
    canvasId: '1',
    name: 'broken metadata',
    transform: { x: 600, y: 30, width: 320, height: 260 },
    groupId: null,
    resources: [{
      id: '30',
      canvasId: '1',
      kind: 'TEXT',
      mediaType: 'text/markdown',
      name: 'note.md',
      size: '4',
      textContent: 'note',
      metadataJson: '[]',
      createdAt: '2026-08-10T00:00:00Z',
    }, {
      id: '31',
      canvasId: '1',
      kind: 'TEXT',
      mediaType: 'text/markdown',
      name: 'broken.md',
      size: '1',
      textContent: 'x',
      metadataJson: '{',
      createdAt: '2026-08-10T00:00:00Z',
    }],
    function: null,
    run: null,
  }],
  groups: [{
    id: '4',
    canvasId: '1',
    title: 'group',
    transform: { x: 0, y: 0, width: 500, height: 400 },
  }],
  links: [{
    canvasId: '1',
    sourceNodeId: '3',
    targetNodeId: '2',
  }],
}

describe('Canvas snapshot projection', () => {
  it('projects DTOs into clean ResourceNode/Resource/Function/Run/Group/Link values', () => {
    const snapshot = projectCanvasSnapshot(snapshotDTO)

    expect(snapshot.document.graphRevision).toBe('3')
    expect(snapshot.resourceNodes[0]).toMatchObject({
      id: '2',
      function: { modelKey: 'fake-image' },
      run: { requestId: 'request-1', status: 'RUNNING' },
      resources: [{
        id: '20',
        size: '9223372036854775807',
        text: null,
        metadata: { width: 1024, height: 768 },
      }],
    })
    expect(snapshot.resourceNodes[1]?.resources[0]?.metadata).toEqual({})
    expect(snapshot.resourceNodes[1]?.resources[1]?.metadata).toEqual({})
    expect(snapshot.groups[0]).toMatchObject({ id: '4', title: 'group' })
    expect(snapshot.links[0]).toEqual({
      canvasId: '1',
      sourceNodeId: '3',
      targetNodeId: '2',
    })
  })

  it('keeps groups in world coordinates and applies group drafts to members without parent transforms', () => {
    const snapshot = projectCanvasSnapshot(snapshotDTO)
    const callbacks = {
      renameNode: vi.fn(),
      editTextNode: vi.fn(),
    }
    const nodes = projectNodes(snapshot, ['2'], [], callbacks, {
      'group:4': { x: 100, y: -20 },
    })
    const group = nodes.find((node) => node.id === 'group:4')
    const member = nodes.find((node) => node.id === '2')
    const outside = nodes.find((node) => node.id === '3')

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
    expect(member).not.toHaveProperty('parentId')
    expect(outside?.position).toEqual({ x: 600, y: 30 })
  })

  it('uses link endpoints as edge identity and keeps edges operable', () => {
    const snapshot = projectCanvasSnapshot(snapshotDTO)
    expect(projectEdges(snapshot.links)[0]).toMatchObject({
      id: '3->2',
      source: '3',
      target: '2',
      sourceHandle: 'out',
      targetHandle: 'in',
      selectable: true,
      focusable: true,
    })
    expect(projectEdges(snapshot.links, [{
      sourceNodeId: '3',
      targetNodeId: '2',
    }])[0]?.selected).toBe(true)
    expect(groupIdFromFlowId('group:4')).toBe('4')
    expect(groupIdFromFlowId('group:04')).toBeNull()
    expect(groupIdFromFlowId('4')).toBeNull()
  })
})
