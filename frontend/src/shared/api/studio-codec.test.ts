import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import {
  decodeCanvasChanges,
  decodeCanvasDocument,
  decodeCanvasDocumentList,
  decodeCanvasPatch,
  decodeCanvasSnapshot,
} from '@/shared/api/studio-codec'
import type { CanvasChangesDTO, CanvasSnapshotDTO } from '@/shared/api/contracts/studio'

const ID = '11111111-1111-4111-8111-111111111111'
const CANVAS_ID = '22222222-2222-4222-8222-222222222222'
const NODE_ID = '33333333-3333-4333-8333-333333333333'
const GROUP_ID = '44444444-4444-4444-8444-444444444444'
const REQUEST_ID = '55555555-5555-4555-8555-555555555555'

function documentPayload() {
  return {
    id: CANVAS_ID,
    title: 'Canvas',
    version: '3',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
  }
}

function resourcePayload() {
  return {
    id: ID,
    canvasId: CANVAS_ID,
    ownerNodeId: NODE_ID,
    resourceIndex: 0,
    blobId: null,
    name: 'note',
    textContent: 'hello',
    kind: 'TEXT',
    mediaType: null,
    sizeBytes: '12',
    width: null,
    height: null,
    durationMs: null,
    createdAt: '2026-01-01T00:00:00Z',
  }
}

function nodePayload() {
  return {
    id: NODE_ID,
    canvasId: CANVAS_ID,
    name: 'Node',
    transform: { x: 1, y: 2, width: 100, height: 80 },
    groupId: GROUP_ID,
    resources: [resourcePayload()],
    function: { modelKey: 'image', configJson: '{}' },
    run: {
      nodeId: NODE_ID,
      requestId: REQUEST_ID,
      status: 'SUCCEEDED',
      stage: 'done',
      error: null,
      updatedAt: '2026-01-02T00:00:00Z',
    },
  }
}

function groupPayload() {
  return {
    id: GROUP_ID,
    canvasId: CANVAS_ID,
    title: 'Group',
    transform: { x: 0, y: 0, width: 200, height: 160 },
  }
}

function linkPayload() {
  return {
    canvasId: CANVAS_ID,
    sourceNodeId: NODE_ID,
    targetNodeId: ID,
  }
}

function snapshotPayload(): CanvasSnapshotDTO {
  return {
    document: documentPayload(),
    nodes: [nodePayload()],
    groups: [groupPayload()],
    links: [linkPayload()],
  }
}

function patchPayload() {
  return {
    baseVersion: '3',
    version: '4',
    groups: [
      { op: 'UPSERT', group: groupPayload() },
      { op: 'REMOVE', groupId: GROUP_ID },
    ],
    nodes: [
      { op: 'UPSERT', node: nodePayload() },
      { op: 'REMOVE', nodeId: NODE_ID },
    ],
    links: [
      { op: 'UPSERT', link: linkPayload() },
      { op: 'REMOVE', sourceNodeId: NODE_ID, targetNodeId: ID },
    ],
  }
}

describe('studio codec', () => {
  it('decodes the full document/snapshot graph', () => {
    const document = decodeCanvasDocument(documentPayload())
    expect(document).toEqual(documentPayload())

    const snapshot = decodeCanvasSnapshot(snapshotPayload())
    expect(snapshot.nodes[0]?.resources[0]?.sizeBytes).toBe(12)
    expect(snapshot.nodes[0]?.transform).toEqual({ x: 1, y: 2, width: 100, height: 80 })
    expect(snapshot.groups[0]?.id).toBe(GROUP_ID)
    expect(snapshot.links[0]).toEqual(linkPayload())
    expect(snapshot.nodes[0]?.resources[0]?.kind).toBe('TEXT')
    expect(snapshot.nodes[0]?.run?.status).toBe('SUCCEEDED')
  })

  it('decodes a document list and a patch with both REMOVE and UPSERT forms', () => {
    expect(decodeCanvasDocumentList([documentPayload()])).toEqual([documentPayload()])

    const patch = decodeCanvasPatch(patchPayload())
    expect(patch.baseVersion).toBe('3')
    expect(patch.version).toBe('4')
    expect(patch.groups).toEqual([
      { op: 'UPSERT', group: groupPayload() },
      { op: 'REMOVE', groupId: GROUP_ID },
    ])
    expect(patch.nodes).toEqual([
      { op: 'UPSERT', node: decodeCanvasSnapshot(snapshotPayload()).nodes[0] },
      { op: 'REMOVE', nodeId: NODE_ID },
    ])
    expect(patch.links).toEqual([
      { op: 'UPSERT', link: linkPayload() },
      { op: 'REMOVE', sourceNodeId: NODE_ID, targetNodeId: ID },
    ])
  })

  it('decodes nullable fields and empty changes with a null snapshot', () => {
    const value = {
      document: documentPayload(),
      nodes: [{
        ...nodePayload(),
        groupId: null,
        resources: [{
          ...resourcePayload(),
          blobId: null,
          textContent: null,
          mediaType: null,
          sizeBytes: null,
          durationMs: null,
          width: 10,
          height: 20,
        }],
        function: null,
        run: null,
      }],
      groups: [],
      links: [],
    }
    const snapshot = decodeCanvasSnapshot(value)
    expect(snapshot.nodes[0]?.function).toBeNull()
    expect(snapshot.nodes[0]?.run).toBeNull()
    expect(snapshot.nodes[0]?.groupId).toBeNull()
    expect(snapshot.nodes[0]?.resources[0]?.blobId).toBeNull()
    expect(snapshot.nodes[0]?.resources[0]?.textContent).toBeNull()
    expect(snapshot.nodes[0]?.resources[0]?.mediaType).toBeNull()
    expect(snapshot.nodes[0]?.resources[0]?.width).toBe(10)
    expect(snapshot.nodes[0]?.resources[0]?.height).toBe(20)
    expect(snapshot.nodes[0]?.resources[0]?.sizeBytes).toBeNull()
    expect(snapshot.nodes[0]?.resources[0]?.durationMs).toBeNull()

    const changes: CanvasChangesDTO = decodeCanvasChanges({ patches: [], snapshot: null })
    expect(changes.snapshot).toBeNull()
    expect(changes.patches).toEqual([])
  })

  it('rejects undefined/missing nullable fields instead of treating them as null', () => {
    const missingResourceField = (field: string) => {
      const resource = { ...resourcePayload(), [field]: undefined }
      return {
        ...snapshotPayload(),
        nodes: [{ ...nodePayload(), resources: [resource] }],
      }
    }
    for (const field of ['blobId', 'textContent', 'mediaType', 'sizeBytes', 'width', 'height', 'durationMs']) {
      expect(() => decodeCanvasSnapshot(missingResourceField(field))).toThrow(
        `resource.${field} must be explicitly null`,
      )
    }

    const missingNodeField = (field: string) => {
      const node = { ...nodePayload(), [field]: undefined }
      return { ...snapshotPayload(), nodes: [node] }
    }
    for (const field of ['groupId', 'function', 'run']) {
      expect(() => decodeCanvasSnapshot(missingNodeField(field))).toThrow(
        `node.${field} must be explicitly null`,
      )
    }

    const missingRunError = {
      ...snapshotPayload(),
      nodes: [{ ...nodePayload(), run: { ...nodePayload().run, error: undefined } }],
    }
    expect(() => decodeCanvasSnapshot(missingRunError)).toThrow('node.run.error must be explicitly null')

    const missingChangesSnapshot = { patches: [], snapshot: undefined }
    expect(() => decodeCanvasChanges(missingChangesSnapshot)).toThrow('changes.snapshot must be explicitly null')
  })

  it('rejects invalid resource kind and function run status', () => {
    const invalidKind = {
      ...snapshotPayload(),
      nodes: [{
        ...nodePayload(),
        resources: [{ ...resourcePayload(), kind: 'SVG' }],
      }],
    }
    expect(() => decodeCanvasSnapshot(invalidKind)).toThrow('resource.kind must be one of')

    const invalidStatus = {
      ...snapshotPayload(),
      nodes: [{
        ...nodePayload(),
        run: { ...nodePayload().run, status: 'PENDING' },
      }],
    }
    expect(() => decodeCanvasSnapshot(invalidStatus)).toThrow('node.run.status must be one of')
  })

  it('rejects unknown patch operations instead of defaulting to UPSERT', () => {
    const invalidGroupOp = {
      ...patchPayload(),
      groups: [{ op: 'REPLACE', group: groupPayload() }],
    }
    expect(() => decodeCanvasPatch(invalidGroupOp)).toThrow('group patch.op must be one of')

    const invalidNodeOp = {
      ...patchPayload(),
      nodes: [{ op: 'REPLACE', node: nodePayload() }],
    }
    expect(() => decodeCanvasPatch(invalidNodeOp)).toThrow('node patch.op must be one of')

    const invalidLinkOp = {
      ...patchPayload(),
      links: [{ op: 'REPLACE', link: linkPayload() }],
    }
    expect(() => decodeCanvasPatch(invalidLinkOp)).toThrow('link patch.op must be one of')
  })

  it('decodes changes with patches and a snapshot', () => {
    const changes = decodeCanvasChanges({
      patches: [patchPayload()],
      snapshot: snapshotPayload(),
    })
    expect(changes.patches).toHaveLength(1)
    expect(changes.patches[0]?.version).toBe('4')
    expect(changes.snapshot?.document.version).toBe('3')
  })

  it('fails closed for a non-object document list', () => {
    let error: unknown
    try {
      decodeCanvasDocumentList(null)
    } catch (caught) {
      error = caught
    }
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).message).toContain('document list')
  })

  it('fails closed when top-level payloads are not objects', () => {
    expect(() => decodeCanvasDocument(null)).toThrow('document must be an object')
    expect(() => decodeCanvasSnapshot(null)).toThrow('snapshot must be an object')
    expect(() => decodeCanvasPatch(null)).toThrow('patch must be an object')
    expect(() => decodeCanvasChanges(null)).toThrow('changes must be an object')
  })

  it.each([
    ['document id not a UUID', { ...snapshotPayload(), document: { ...documentPayload(), id: 'bad' } }, 'document.id'],
    ['document version with leading zero', { ...snapshotPayload(), document: { ...documentPayload(), version: '01' } }, 'document.version'],
    ['document version numeric', { ...snapshotPayload(), document: { ...documentPayload(), version: 3 } }, 'document.version'],
    ['node not an object', { ...snapshotPayload(), nodes: [{}] }, 'node.id'],
    ['group not an object', { ...snapshotPayload(), groups: [{}] }, 'group.id'],
    ['link not an object', { ...snapshotPayload(), links: [{}] }, 'link.canvasId'],
    ['transform width zero', {
      ...snapshotPayload(),
      nodes: [{ ...nodePayload(), transform: { ...nodePayload().transform, width: 0 } }],
    }, 'node.transform.width'],
    ['resource index negative', {
      ...snapshotPayload(),
      nodes: [{ ...nodePayload(), resources: [{ ...resourcePayload(), resourceIndex: -1 }] }],
    }, 'resource.resourceIndex'],
    ['run status missing', {
      ...snapshotPayload(),
      nodes: [{ ...nodePayload(), run: { ...nodePayload().run, status: undefined } }],
    }, 'node.run.status'],
  ])('fails closed for malformed payload: %s', (_name, payload, path) => {
    let error: unknown
    try {
      decodeCanvasSnapshot(payload)
    } catch (caught) {
      error = caught
    }
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).message).toContain(path)
  })

  it('fails closed for unsafe long values', () => {
    const payload = {
      ...snapshotPayload(),
      nodes: [{
        ...nodePayload(),
        resources: [{ ...resourcePayload(), sizeBytes: '9007199254740992' }],
      }],
    }
    expect(() => decodeCanvasSnapshot(payload)).toThrow('exceeds Number.MAX_SAFE_INTEGER')
  })

  it('fails closed for non-canonical long strings in patches', () => {
    const payload = {
      ...patchPayload(),
      baseVersion: '01',
    }
    expect(() => decodeCanvasPatch(payload)).toThrow('canonical non-negative decimal string')
  })

  it('fails closed for non-finite transform numbers and non-string long values', () => {
    const nonFiniteTransform = {
      ...snapshotPayload(),
      nodes: [{ ...nodePayload(), transform: { ...nodePayload().transform, x: '1' } }],
    }
    expect(() => decodeCanvasSnapshot(nonFiniteTransform)).toThrow('node.transform.x must be a finite number')

    const numericLong = {
      ...snapshotPayload(),
      nodes: [{
        ...nodePayload(),
        resources: [{ ...resourcePayload(), sizeBytes: 12 }],
      }],
    }
    expect(() => decodeCanvasSnapshot(numericLong)).toThrow('resource.sizeBytes must be a canonical')
  })

  it('fails closed for patch op without required REMOVE fields', () => {
    const payload = {
      ...patchPayload(),
      nodes: [{ op: 'REMOVE' }],
    }
    expect(() => decodeCanvasPatch(payload)).toThrow('node patch.nodeId')
  })
})
