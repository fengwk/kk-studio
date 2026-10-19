/** Current Canvas DTO shapes shared by the offline smoke contract tests. */

import { createHash } from 'node:crypto'

export const CHAT_ID = '00000000-0000-0000-0000-000000000001'
export const SESSION_ID = '00000000-0000-0000-0000-000000000002'
export const THREAD_ID = '00000000-0000-0000-0000-000000000003'
export const COMMAND_ID = '00000000-0000-0000-0000-000000000004'
export const MARKER = 'smoke-marker-123'
export const BLOB_ID = '11111111-1111-4111-8111-111111111111'
export const CANVAS_ID = '22222222-2222-4222-8222-222222222222'
export const UPDATED_AT = '2026-01-01T00:00:00Z'
export const REQUIRED_FUNCTIONS = [
  'fake-image',
  'gpt-image-2',
  'seedance2.0',
  'seedance2.0fast',
  'seedance2.0_vip',
  'seedance2.0fast_vip',
]

/** CanvasDocumentDTO：五字段快照，revision 是规范十进制字符串。 */
export function documentDto(revision) {
  return {
    id: CANVAS_ID,
    title: 'container-resource-smoke',
    revision: String(revision),
    createdAt: UPDATED_AT,
    updatedAt: UPDATED_AT,
  }
}

/** CanvasPatchDTO：revision + groups/nodes 变化集。 */
export function patchDto(revision, nodes = []) {
  return { revision, groups: [], nodes: [...nodes] }
}

/** CanvasFunctionRunDTO：无错误时 error 必须显式为 null。 */
export function runDto(nodeId, requestId, { status = 'SUCCEEDED', error = null, updatedAt = UPDATED_AT } = {}) {
  return {
    nodeId,
    requestId,
    status,
    stage: status === 'READY' ? 'QUEUED' : status,
    error,
    updatedAt,
  }
}

/** 目录项版本身份：canonical 非负十进制 epoch + 该函数定义的 lowercase SHA-256。 */
export function functionVersion(name) {
  return { catalogEpoch: '7', definitionFingerprint: createHash('sha256').update(name).digest('hex') }
}

/** CanvasFunctionDefinitionDTO 列表：name + 严格 object schema + 目录版本。 */
export function functionDefinitions(names = REQUIRED_FUNCTIONS) {
  return names.map((name) => ({
    name,
    description: `offline ${name}`,
    argsSchema: {
      type: 'object',
      properties: { prompt: { type: 'string' } },
      required: ['prompt'],
      additionalProperties: false,
    },
    outputs: [{ kind: name.startsWith('seedance') ? 'VIDEO' : 'IMAGE', name: null }],
    referencePolicy: { allowedKinds: ['IMAGE'], maxReferences: 12, maxByKind: {} },
    available: true,
    unavailableReason: null,
    version: functionVersion(name),
  }))
}
