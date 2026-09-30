import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import {
  decodeCanvasConflict,
  decodeCanvasDocument,
  decodeCanvasDocumentList,
  decodeCanvasFunctionDefinition,
  decodeCanvasPatch,
  decodeCanvasSnapshot,
} from '@/shared/api/studio-codec'
import type { CanvasSnapshotDTO } from '@/shared/api/contracts/studio'

const ID = '11111111-1111-4111-8111-111111111111'
const CANVAS_ID = '22222222-2222-4222-8222-222222222222'
const NODE_ID = '33333333-3333-4333-8333-333333333333'
const GROUP_ID = '44444444-4444-4444-8444-444444444444'
const REQUEST_ID = '55555555-5555-4555-8555-555555555555'

function documentPayload() {
  return {
    id: CANVAS_ID,
    title: 'Canvas',
    revision: '3',
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
    function: { name: 'image', args: { prompt: 'hello' } },
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

function referencePayload() {
  return {
    canvasId: CANVAS_ID,
    sourceNodeId: NODE_ID,
    targetNodeId: ID,
    index: 0,
  }
}

function snapshotPayload(): CanvasSnapshotDTO {
  return {
    document: documentPayload(),
    nodes: [nodePayload()],
    groups: [groupPayload()],
    references: [referencePayload()],
  }
}

function patchPayload() {
  return {
    revision: '4',
    groups: [
      { op: 'UPSERT', group: groupPayload() },
      { op: 'REMOVE', groupId: GROUP_ID },
    ],
    nodes: [
      { op: 'UPSERT', node: nodePayload() },
      { op: 'REMOVE', nodeId: NODE_ID },
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
    expect(snapshot.references[0]).toEqual(referencePayload())
    expect(snapshot.nodes[0]?.resources[0]?.kind).toBe('TEXT')
    expect(snapshot.nodes[0]?.run?.status).toBe('SUCCEEDED')
    expect(snapshot.nodes[0]?.function).toEqual({ name: 'image', args: { prompt: 'hello' } })
  })

  it('decodes a document list and a patch with both REMOVE and UPSERT forms', () => {
    expect(decodeCanvasDocumentList([documentPayload()])).toEqual([documentPayload()])

    const patch = decodeCanvasPatch(patchPayload())
    expect(patch.revision).toBe('4')
    expect(patch.groups).toEqual([
      { op: 'UPSERT', group: groupPayload() },
      { op: 'REMOVE', groupId: GROUP_ID },
    ])
    expect(patch.nodes).toEqual([
      { op: 'UPSERT', node: decodeCanvasSnapshot(snapshotPayload()).nodes[0] },
      { op: 'REMOVE', nodeId: NODE_ID },
    ])
  })

  it('decodes nullable snapshot fields', () => {
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
    expect(() => decodeCanvasSnapshot(invalidStatus))
      .toThrow('node.run.status must be one of READY, RUNNING, SUCCEEDED, FAILED, CANCELLED')
  })

  it('accepts READY as the durable pre-claim function run status', () => {
    const ready = {
      ...snapshotPayload(),
      nodes: [{
        ...nodePayload(),
        run: { ...nodePayload().run, status: 'READY' },
      }],
    }
    expect(decodeCanvasSnapshot(ready).nodes[0]?.run?.status).toBe('READY')
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
  })

  it.each([
    ['document id not a UUID', { ...snapshotPayload(), document: { ...documentPayload(), id: 'bad' } }, 'document.id'],
    ['document version with leading zero', { ...snapshotPayload(), document: { ...documentPayload(), revision: '01' } }, 'document.revision'],
    ['document version numeric', { ...snapshotPayload(), document: { ...documentPayload(), revision: 3 as never } }, 'document.revision'],
    ['node not an object', { ...snapshotPayload(), nodes: [{}] }, 'node.id'],
    ['group not an object', { ...snapshotPayload(), groups: [{}] }, 'group.id'],
    ['reference not an object', { ...snapshotPayload(), references: [{}] }, 'reference.canvasId'],
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
      revision: '01',
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

  describe('canonical acceptance and strict rejection of legacy payloads', () => {
    it('accepts canonical function definition with full fields', () => {
      const canonical = {
        name: 'test-fn',
        description: 'Test Function',
        argsSchema: { type: 'object', properties: { count: { type: 'number' } } },
        outputs: [
          { kind: 'IMAGE', name: 'main' },
          { kind: 'VIDEO', name: null },
        ],
        referencePolicy: {
          allowedKinds: ['IMAGE', 'VIDEO'],
          maxReferences: 2,
          maxByKind: { IMAGE: 1, VIDEO: 1 },
        },
        available: true,
        unavailableReason: null,
      }
      const decoded = decodeCanvasFunctionDefinition(canonical)
      expect(decoded.name).toBe('test-fn')
      expect(decoded.description).toBe('Test Function')
      expect(decoded.argsSchema).toEqual(canonical.argsSchema)
      expect(decoded.outputs).toEqual([
        { kind: 'IMAGE', name: 'main' },
        { kind: 'VIDEO', name: null },
      ])
      expect(decoded.referencePolicy).toEqual({
        allowedKinds: ['IMAGE', 'VIDEO'],
        maxReferences: 2,
        maxByKind: { IMAGE: 1, VIDEO: 1 },
      })
      expect(decoded.available).toBe(true)
      expect(decoded.unavailableReason).toBeNull()
    })

    it('accepts real NON_NULL wire that omits nullable function fields entirely', () => {
      // 测试意图：全局 NON_NULL 会省略 nullable 键（未命名输出槽位的 name、null 的
      // description/maxReferences/referencePolicy），只有 @JsonInclude(ALWAYS) 的
      // unavailableReason 显式输出 null；误按 required-nullable 解析会让整份目录解码失败。
      const canonical = {
        name: 'fake-image',
        argsSchema: { type: 'object', properties: {} },
        outputs: [{ kind: 'IMAGE' }],
        referencePolicy: { allowedKinds: ['IMAGE'], maxByKind: {} },
        available: true,
        unavailableReason: null,
      }
      const decoded = decodeCanvasFunctionDefinition(canonical)
      expect(decoded.description).toBeNull()
      expect(decoded.outputs).toEqual([{ kind: 'IMAGE', name: null }])
      expect(decoded.referencePolicy).toEqual({
        allowedKinds: ['IMAGE'],
        maxReferences: null,
        maxByKind: {},
      })
      expect(decoded.available).toBe(true)
      expect(decoded.unavailableReason).toBeNull()

      const withoutPolicy = {
        name: 'image.crop',
        argsSchema: {},
        outputs: [{ kind: 'IMAGE', name: 'crop.png' }],
        available: false,
        unavailableReason: 'disabled',
      }
      const decodedWithoutPolicy = decodeCanvasFunctionDefinition(withoutPolicy)
      expect(decodedWithoutPolicy.referencePolicy).toBeNull()
      expect(decodedWithoutPolicy.outputs).toEqual([{ kind: 'IMAGE', name: 'crop.png' }])
      expect(decodedWithoutPolicy.available).toBe(false)
    })

    it('fails closed when canonical non-nullable function fields are missing', () => {
      const base = { name: 'minimal-fn', argsSchema: {}, outputs: [], available: true, unavailableReason: null }
      expect(() => decodeCanvasFunctionDefinition({ ...base, argsSchema: undefined }))
        .toThrow('function.argsSchema must be an object')
      expect(() => decodeCanvasFunctionDefinition({ ...base, available: undefined }))
        .toThrow('function.available must be a boolean')
      expect(() => decodeCanvasFunctionDefinition({ ...base, unavailableReason: undefined }))
        .toThrow('function.unavailableReason must be explicitly null')
      expect(() => decodeCanvasFunctionDefinition({ ...base, outputs: [{ kind: 'IMAGE' }], referencePolicy: { allowedKinds: [] } }))
        .toThrow('referencePolicy.maxByKind must be an object')
    })

    it('strictly rejects legacy document payload without revision (only version)', () => {
      const legacyDocument = {
        id: CANVAS_ID,
        title: 'Legacy',
        version: '3',
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-02T00:00:00Z',
      }
      expect(() => decodeCanvasDocument(legacyDocument)).toThrow('document.revision must be a canonical')
    })

    it('strictly rejects legacy patch payload without revision (only version)', () => {
      const legacyPatch = {
        version: '4',
        groups: [],
        nodes: [],
      }
      expect(() => decodeCanvasPatch(legacyPatch)).toThrow('patch.revision must be a canonical')
    })

    it('strictly rejects legacy function definition using outputKind instead of outputs', () => {
      const legacyFn = {
        name: 'legacy-fn',
        description: null,
        argsSchema: {},
        outputKind: 'IMAGE',
        available: true,
        unavailableReason: null,
      }
      expect(() => decodeCanvasFunctionDefinition(legacyFn)).toThrow('function.outputs must be an array')
    })

    it('strictly rejects legacy node function payload with modelKey/configJson', () => {
      const legacyNodeFunction = {
        ...snapshotPayload(),
        nodes: [{
          ...nodePayload(),
          function: { modelKey: 'image', configJson: '{}' },
        }],
      }
      expect(() => decodeCanvasSnapshot(legacyNodeFunction)).toThrow('node.function.name must be a string')
    })

    it('strictly rejects node function missing args object', () => {
      const missingArgs = {
        ...snapshotPayload(),
        nodes: [{
          ...nodePayload(),
          function: { name: 'image' },
        }],
      }
      expect(() => decodeCanvasSnapshot(missingArgs)).toThrow('node.function.args must be an object')
    })
  })

  describe('canvas conflict decoding', () => {
    it('decodes TARGET_MISSING and TARGET_PRESENT conflicts', () => {
      const missing = decodeCanvasConflict({
        kind: 'TARGET_MISSING',
        targetId: NODE_ID,
        target: 'NODE',
      })
      expect(missing).toEqual({ kind: 'TARGET_MISSING', targetId: NODE_ID, target: 'NODE' })

      const present = decodeCanvasConflict({
        kind: 'TARGET_PRESENT',
        targetId: GROUP_ID,
        target: 'GROUP',
      })
      expect(present).toEqual({ kind: 'TARGET_PRESENT', targetId: GROUP_ID, target: 'GROUP' })
    })

    it('decodes STALE_NODE and STALE_GROUP conflicts with current state', () => {
      const staleNode = decodeCanvasConflict({
        kind: 'STALE_NODE',
        nodeId: NODE_ID,
        group: GROUP_ID,
        current: nodePayload(),
      })
      expect(staleNode.kind).toBe('STALE_NODE')
      if (staleNode.kind === 'STALE_NODE') {
        expect(staleNode.nodeId).toBe(NODE_ID)
        expect(staleNode.current.name).toBe('Node')
      }

      const staleGroup = decodeCanvasConflict({
        kind: 'STALE_GROUP',
        groupId: GROUP_ID,
        current: groupPayload(),
      })
      expect(staleGroup.kind).toBe('STALE_GROUP')
      if (staleGroup.kind === 'STALE_GROUP') {
        expect(staleGroup.groupId).toBe(GROUP_ID)
        expect(staleGroup.current.title).toBe('Group')
      }
    })

    it('decodes NODE_RUNNING and NODE_REFERENCED conflicts', () => {
      const running = decodeCanvasConflict({
        kind: 'NODE_RUNNING',
        nodeId: NODE_ID,
        run: nodePayload().run,
      })
      expect(running.kind).toBe('NODE_RUNNING')
      if (running.kind === 'NODE_RUNNING') {
        expect(running.run.status).toBe('SUCCEEDED')
      }

      const referenced = decodeCanvasConflict({
        kind: 'NODE_REFERENCED',
        nodeId: NODE_ID,
        referencingNodeIds: [ID],
      })
      expect(referenced).toEqual({
        kind: 'NODE_REFERENCED',
        nodeId: NODE_ID,
        referencingNodeIds: [ID],
      })
    })

    it('fails closed for unknown conflict kind', () => {
      expect(() => decodeCanvasConflict({ kind: 'NON_EXISTENT' })).toThrow('unknown conflict kind: NON_EXISTENT')
    })
  })
})
