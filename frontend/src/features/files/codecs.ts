import { ApiError } from '@/shared/api/client'
import type {
  CloudBlobMetadataDTO,
  CloudFileSnapshotDTO,
  CloudNodeDTO,
  CloudNodeKind,
  CloudTextRevisionDTO,
  CloudTextRevisionLineDTO,
  CloudTextWindowDTO,
} from './types'

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const DECIMAL_LONG_REGEX = /^(0|[1-9][0-9]*)$/
const SHA256_REGEX = /^[0-9a-fA-F]{64}$/
const NODE_KINDS: readonly CloudNodeKind[] = ['DIRECTORY', 'TEXT', 'BLOB']

function invalidPayload(detail: string): ApiError {
  return new ApiError(`Cloud Files API returned an invalid payload: ${detail}`)
}

export function isCanonicalUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID_REGEX.test(value)
}

export function isDecimalLong(value: unknown): value is string {
  return typeof value === 'string' && DECIMAL_LONG_REGEX.test(value)
}

export function isCloudNodeKind(value: unknown): value is CloudNodeKind {
  return typeof value === 'string' && (NODE_KINDS as readonly string[]).includes(value)
}

function requireRecord(value: unknown, path: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw invalidPayload(`${path} must be an object`)
  }
  return value as Record<string, unknown>
}

function requireString(value: unknown, path: string): string {
  if (typeof value !== 'string') {
    throw invalidPayload(`${path} must be a string`)
  }
  return value
}

function requireNullableString(value: unknown, path: string): string | null {
  if (value === undefined) {
    throw invalidPayload(`${path} must not be undefined`)
  }
  if (value === null) {
    return null
  }
  if (typeof value !== 'string') {
    throw invalidPayload(`${path} must be a string or null`)
  }
  return value
}

function requireUuid(value: unknown, path: string): string {
  const str = requireString(value, path)
  if (!UUID_REGEX.test(str)) {
    throw invalidPayload(`${path} must be a canonical UUID string`)
  }
  return str
}

function requireNullableUuid(value: unknown, path: string): string | null {
  if (value === undefined) {
    throw invalidPayload(`${path} must not be undefined`)
  }
  if (value === null) {
    return null
  }
  return requireUuid(value, path)
}

function requireDecimalLong(value: unknown, path: string): string {
  const str = requireString(value, path)
  if (!DECIMAL_LONG_REGEX.test(str)) {
    throw invalidPayload(`${path} must be a canonical non-negative decimal string`)
  }
  return str
}

function requireNullableDecimalLong(value: unknown, path: string): string | null {
  if (value === undefined) {
    throw invalidPayload(`${path} must not be undefined`)
  }
  if (value === null) {
    return null
  }
  return requireDecimalLong(value, path)
}

function requireNullableSha256(value: unknown, path: string): string | null {
  if (value === undefined) {
    throw invalidPayload(`${path} must not be undefined`)
  }
  if (value === null) {
    return null
  }
  const str = requireString(value, path)
  if (!SHA256_REGEX.test(str)) {
    throw invalidPayload(`${path} must be a 64-character hex SHA-256 hash`)
  }
  return str.toLowerCase()
}

function requireBoolean(value: unknown, path: string): boolean {
  if (typeof value !== 'boolean') {
    throw invalidPayload(`${path} must be a boolean`)
  }
  return value
}

function requireSafeInteger(value: unknown, path: string, min = 0): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min) {
    throw invalidPayload(`${path} must be a safe integer >= ${min}`)
  }
  return value
}

function requireNullableSafeInteger(value: unknown, path: string, min = 0): number | null {
  if (value === undefined) {
    throw invalidPayload(`${path} must not be undefined`)
  }
  if (value === null) {
    return null
  }
  return requireSafeInteger(value, path, min)
}

function requirePath(value: unknown, path: string): string {
  const str = requireString(value, path)
  if (!str.startsWith('/')) {
    throw invalidPayload(`${path} must start with /`)
  }
  return str
}

export function decodeCloudNode(value: unknown, pathContext = 'node'): CloudNodeDTO {
  const obj = requireRecord(value, pathContext)
  const path = requirePath(obj.path, `${pathContext}.path`)
  const id = requireNullableUuid(obj.id, `${pathContext}.id`)
  const name = requireString(obj.name, `${pathContext}.name`)

  const rawKind = requireString(obj.kind, `${pathContext}.kind`)
  if (!isCloudNodeKind(rawKind)) {
    throw invalidPayload(`${pathContext}.kind must be DIRECTORY, TEXT, or BLOB`)
  }

  const version = requireDecimalLong(obj.version, `${pathContext}.version`)
  const blobId = requireNullableUuid(obj.blobId, `${pathContext}.blobId`)
  const mediaType = requireNullableString(obj.mediaType, `${pathContext}.mediaType`)
  const sizeBytes = requireNullableDecimalLong(obj.sizeBytes, `${pathContext}.sizeBytes`)
  const sha256 = requireNullableSha256(obj.sha256, `${pathContext}.sha256`)
  const createdAt = requireNullableString(obj.createdAt, `${pathContext}.createdAt`)
  const updatedAt = requireNullableString(obj.updatedAt, `${pathContext}.updatedAt`)

  return {
    id,
    path,
    name,
    kind: rawKind,
    version,
    blobId,
    mediaType,
    sizeBytes,
    sha256,
    createdAt,
    updatedAt,
  }
}

export function decodeCloudTextRevisionLine(
  value: unknown,
  pathContext = 'line',
): CloudTextRevisionLineDTO {
  const obj = requireRecord(value, pathContext)
  const lineNumber = requireSafeInteger(obj.lineNumber, `${pathContext}.lineNumber`, 1)
  const content = requireString(obj.content, `${pathContext}.content`)
  const truncated = requireBoolean(obj.truncated, `${pathContext}.truncated`)

  return {
    lineNumber,
    content,
    truncated,
  }
}

export function decodeCloudTextRevision(
  value: unknown,
  pathContext = 'text',
): CloudTextRevisionDTO {
  const obj = requireRecord(value, pathContext)
  const revision = requireDecimalLong(obj.revision, `${pathContext}.revision`)
  const endsWithNewline = requireBoolean(obj.endsWithNewline, `${pathContext}.endsWithNewline`)
  const offset = requireSafeInteger(obj.offset, `${pathContext}.offset`, 1)
  const totalLines = requireSafeInteger(obj.totalLines, `${pathContext}.totalLines`, 0)
  const nextOffset = requireNullableSafeInteger(obj.nextOffset, `${pathContext}.nextOffset`, 1)

  if (!Array.isArray(obj.lines)) {
    throw invalidPayload(`${pathContext}.lines must be an array`)
  }
  const lines = obj.lines.map((line, idx) =>
    decodeCloudTextRevisionLine(line, `${pathContext}.lines[${idx}]`),
  )

  return {
    revision,
    endsWithNewline,
    offset,
    totalLines,
    nextOffset,
    lines,
  }
}

export const decodeCloudTextWindow: (value: unknown, pathContext?: string) => CloudTextWindowDTO =
  decodeCloudTextRevision

export function decodeCloudBlobMetadata(
  value: unknown,
  pathContext = 'blob',
): CloudBlobMetadataDTO {
  const obj = requireRecord(value, pathContext)
  const blobId = requireUuid(obj.blobId, `${pathContext}.blobId`)
  const mediaType = requireNullableString(obj.mediaType, `${pathContext}.mediaType`)
  const sizeBytes = requireNullableDecimalLong(obj.sizeBytes, `${pathContext}.sizeBytes`)
  const sha256 = requireNullableSha256(obj.sha256, `${pathContext}.sha256`)

  return {
    blobId,
    mediaType,
    sizeBytes,
    sha256,
  }
}

export function decodeCloudFileSnapshot(value: unknown): CloudFileSnapshotDTO {
  const obj = requireRecord(value, 'snapshot')
  const node = decodeCloudNode(obj.node, 'snapshot.node')

  let children: CloudNodeDTO[] | null = null
  let text: CloudTextRevisionDTO | null = null
  let blob: CloudBlobMetadataDTO | null = null

  if (node.kind === 'DIRECTORY') {
    if (obj.children !== null && obj.children !== undefined) {
      if (!Array.isArray(obj.children)) {
        throw invalidPayload('snapshot.children must be an array or null')
      }
      children = obj.children.map((child, idx) =>
        decodeCloudNode(child, `snapshot.children[${idx}]`),
      )
    } else {
      children = []
    }
  } else if (node.kind === 'TEXT') {
    if (obj.text !== null && obj.text !== undefined) {
      text = decodeCloudTextRevision(obj.text, 'snapshot.text')
    }
  } else if (node.kind === 'BLOB') {
    if (obj.blob !== null && obj.blob !== undefined) {
      blob = decodeCloudBlobMetadata(obj.blob, 'snapshot.blob')
    } else if (node.blobId) {
      // Fallback from node properties if blob metadata is folded in node
      blob = {
        blobId: node.blobId,
        mediaType: node.mediaType,
        sizeBytes: node.sizeBytes,
        sha256: node.sha256,
      }
    }
  }

  return {
    node,
    children,
    text,
    blob,
  }
}
