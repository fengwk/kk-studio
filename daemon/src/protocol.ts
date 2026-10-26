import { visit as jsoncVisit, type JSONPath } from 'jsonc-parser'

import type { CapabilityResult, ExpectedFile, IdeCommand, IdeReply, ResultContent } from './contracts.js'
import { parseReleaseArtifactUrl } from './release.js'
import {
  ARCHITECTURES,
  MAX_CAPABILITY_ID_CHARS,
  MAX_CONTENT_ITEMS,
  MAX_CONTROL_PAYLOAD_CHARS,
  MAX_DAEMON_VERSION_CHARS,
  MAX_FAILURE_MESSAGE_CHARS,
  MAX_MEDIA_TYPE_ASCII_BYTES,
  MAX_NAME_UTF8_BYTES,
  MAX_PAYLOAD_UTF8_BYTES,
  MAX_PRESIGNED_HEADERS,
  MAX_PRESIGNED_HEADER_CHARS,
  MAX_PRESIGNED_URL_CHARS,
  MAX_PREVIEW_UTF8_BYTES,
  MAX_UPDATE_MESSAGE_CHARS,
  MESSAGE_TYPES,
  OPERATING_SYSTEMS,
  PROTOCOL_VERSION,
  READY_CAPABILITIES_VERSION,
  TICKET_STATES,
  UPDATE_PHASES,
  type Architecture,
  type MessageType,
  type OperatingSystem,
  type TicketState,
  type UpdatePhase,
} from './generated/protocol-contract.js'

/**
 * Daemon wire 协议的严格 codec（envelope v5 / READY payload v5 / capability catalog v3）。
 *
 * <p>这是 Java Environment Daemon codec 的 Node 等价实现，wire 形状逐字段对齐 harness/environment 的
 * DaemonEnvelopeCodec / DaemonCapabilitiesCodec / DaemonCapabilityInvokeCodec /
 * DaemonCapabilityResultCodec / DaemonResourceTransferCodec / DaemonUpdateCommandCodec /
 * DaemonUpdateResultCodec。所有入站文本都经过严格解析：拒绝未知字段、重复键、尾随内容、非法 UTF-8 代理项、
 * 超限嵌套/数字/字符串，避免把不完整消息交给运行时。
 */

export * from './generated/protocol-contract.js'

/** 不符合 Daemon wire 协议的消息。 */
export class ProtocolError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ProtocolError'
  }
}

export interface Envelope {
  protocolVersion: number
  messageType: MessageType
  environmentId: string | null
  invocationId: string | null
  payload: Record<string, unknown>
}

/** envelope 必须携带非空 Environment scope 的消息类型。 */
const BOUND_MESSAGES: ReadonlySet<MessageType> = new Set([
  'WELCOME',
  'READY',
  'HEARTBEAT',
  'INVOKE',
  'CANCEL',
  'RECOVER',
  'STARTED',
  'PROGRESS',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
  'EXECUTION_EXITED',
  'RESOURCE_UPLOAD_REQUEST',
  'RESOURCE_UPLOAD_TICKET',
  'RESOURCE_UPLOAD_COMMIT',
  'UPDATE',
  'UPDATE_RESULT',
  'IDE_COMMAND',
  'IDE_RESULT',
])

/** envelope 必须携带非空 invocationId 的消息类型。IDE 消息携带 IDE 请求 UUID，但不进入 Agent journal。 */
const INVOCATION_MESSAGES: ReadonlySet<MessageType> = new Set([
  'INVOKE',
  'CANCEL',
  'RECOVER',
  'STARTED',
  'PROGRESS',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
  'EXECUTION_EXITED',
  'RESOURCE_UPLOAD_REQUEST',
  'RESOURCE_UPLOAD_TICKET',
  'RESOURCE_UPLOAD_COMMIT',
  'IDE_COMMAND',
  'IDE_RESULT',
])

const ENVELOPE_FIELDS = new Set(['protocolVersion', 'messageType', 'environmentId', 'invocationId', 'payload'])

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const SHA256_PATTERN = /^[0-9a-f]{64}$/
const MEDIA_TYPE_PATTERN = /^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/

// ---------------------------------------------------------------------------
// 严格 JSON 解析：JSON.parse 负责语法/尾随，jsonc-parser visit 负责重复键与放大防护。
// ---------------------------------------------------------------------------

interface StrictLimits {
  maxBytes: number
  maxNestingDepth: number
  maxNumberLength: number
  maxStringBytes: number
}

const ENVELOPE_LIMITS: StrictLimits = {
  maxBytes: MAX_PAYLOAD_UTF8_BYTES,
  maxNestingDepth: 100,
  maxNumberLength: 1000,
  maxStringBytes: MAX_PAYLOAD_UTF8_BYTES,
}

const CONTROL_LIMITS: StrictLimits = {
  maxBytes: MAX_CONTROL_PAYLOAD_CHARS,
  maxNestingDepth: 20,
  maxNumberLength: 64,
  maxStringBytes: MAX_CONTROL_PAYLOAD_CHARS,
}

const UPDATE_LIMITS: StrictLimits = {
  maxBytes: MAX_CONTROL_PAYLOAD_CHARS,
  maxNestingDepth: 20,
  maxNumberLength: 64,
  maxStringBytes: MAX_CONTROL_PAYLOAD_CHARS,
}

/** 精确计算 UTF-8 字节数；拒绝未配对代理项，避免静默替换后突破协议边界。 */
export function utf8ByteLength(value: string, name: string): number {
  let bytes = 0
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index)
    if (code < 0x80) {
      bytes += 1
    } else if (code < 0x800) {
      bytes += 2
    } else if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(index + 1)
      if (!(next >= 0xdc00 && next <= 0xdfff)) {
        throw new ProtocolError(`${name} must be valid Unicode`)
      }
      bytes += 4
      index++
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      throw new ProtocolError(`${name} must be valid Unicode`)
    } else {
      bytes += 3
    }
  }
  return bytes
}

/**
 * 严格解析一段 JSON 文本为值。
 *
 * <p>JSON.parse 保证语法正确且无尾随内容；jsonc-parser visit 额外检测重复对象键并施加嵌套深度、数字长度、
 * 字符串字节与整体字节上限，弥补 JSON.parse 默认吞重复键的缺口。
 */
export function parseStrictJson(text: unknown, name: string, limits: StrictLimits): unknown {
  if (typeof text !== 'string') {
    throw new ProtocolError(`${name} must be a string`)
  }
  if (utf8ByteLength(text, name) > limits.maxBytes) {
    throw new ProtocolError(`${name} exceeds ${limits.maxBytes} UTF-8 bytes`)
  }
  const duplicate = detectViolations(text, limits)
  if (duplicate !== null) {
    throw new ProtocolError(`${name} ${duplicate}`)
  }
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    throw new ProtocolError(`${name} must be valid JSON`)
  }
  return value
}

function detectViolations(text: string, limits: StrictLimits): string | null {
  const seen = new Map<string, Set<string>>()
  let violation: string | null = null
  const pathKey = (path: JSONPath): string => JSON.stringify(path)
  jsoncVisit(
    text,
    {
      onObjectBegin: (_offset, _length, _line, _character, pathSupplier) => {
        if (violation === null && pathSupplier().length + 1 > limits.maxNestingDepth) {
          violation = `exceeds maximum nesting depth ${limits.maxNestingDepth}`
        }
      },
      onArrayBegin: (_offset, _length, _line, _character, pathSupplier) => {
        if (violation === null && pathSupplier().length + 1 > limits.maxNestingDepth) {
          violation = `exceeds maximum nesting depth ${limits.maxNestingDepth}`
        }
      },
      onObjectProperty: (property, _offset, _length, _line, _character, pathSupplier) => {
        if (violation !== null) {
          return
        }
        const key = pathKey(pathSupplier())
        let properties = seen.get(key)
        if (properties === undefined) {
          properties = new Set()
          seen.set(key, properties)
        }
        if (properties.has(property)) {
          violation = `contains duplicate field: ${property}`
          return
        }
        properties.add(property)
      },
      onLiteralValue: (value, _offset, length) => {
        if (violation !== null) {
          return
        }
        if (typeof value === 'number') {
          if (length > limits.maxNumberLength) {
            violation = `number token exceeds ${limits.maxNumberLength} characters`
          } else if (!Number.isFinite(value)) {
            violation = 'must not contain non-finite numbers'
          }
        } else if (typeof value === 'string') {
          try {
            if (utf8ByteLength(value, 'string') > limits.maxStringBytes) {
              violation = `string exceeds ${limits.maxStringBytes} UTF-8 bytes`
            }
          } catch {
            violation = 'must be valid Unicode'
          }
        }
      },
    },
    { disallowComments: true, allowTrailingComma: false, allowEmptyContent: false },
  )
  return violation
}

export function parseStrictObject(text: unknown, name: string, limits: StrictLimits): Record<string, unknown> {
  const value = parseStrictJson(text, name, limits)
  if (!isPlainObject(value)) {
    throw new ProtocolError(`${name} must be a JSON object`)
  }
  return value
}

export function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function isCanonicalUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID_PATTERN.test(value)
}

function rejectUnknownFields(value: Record<string, unknown>, allowed: ReadonlySet<string>, context: string): void {
  for (const field of Object.keys(value)) {
    if (!allowed.has(field)) {
      throw new ProtocolError(`${context} has unknown field: ${field}`)
    }
  }
}

function requiredText(value: Record<string, unknown>, field: string, context: string): string {
  const raw = value[field]
  if (typeof raw !== 'string' || raw.trim() === '') {
    throw new ProtocolError(`${context}.${field} must be non-blank text`)
  }
  return raw
}

function optionalText(value: Record<string, unknown>, field: string, context: string): string | null {
  const raw = value[field]
  if (raw === undefined || raw === null) {
    return null
  }
  if (typeof raw !== 'string') {
    throw new ProtocolError(`${context}.${field} must be a string`)
  }
  return raw
}

function requiredObject(value: Record<string, unknown>, field: string, context: string): Record<string, unknown> {
  const raw = value[field]
  if (!isPlainObject(raw)) {
    throw new ProtocolError(`${context}.${field} must be a JSON object`)
  }
  return raw
}

function requiredBoolean(value: Record<string, unknown>, field: string, context: string): boolean {
  const raw = value[field]
  if (typeof raw !== 'boolean') {
    throw new ProtocolError(`${context}.${field} must be a boolean`)
  }
  return raw
}

function requiredNonNegativeSafeInteger(value: Record<string, unknown>, field: string, context: string): number {
  const raw = value[field]
  if (typeof raw !== 'number' || !Number.isSafeInteger(raw) || raw < 0) {
    throw new ProtocolError(`${context}.${field} must be a non-negative integer`)
  }
  return raw
}

function requiredCanonicalUuid(value: Record<string, unknown>, field: string, context: string): string {
  const raw = value[field]
  if (!isCanonicalUuid(raw)) {
    throw new ProtocolError(`${context}.${field} must be a canonical UUID string`)
  }
  return raw
}

// ---------------------------------------------------------------------------
// Envelope
// ---------------------------------------------------------------------------

export function encodeEnvelope(envelope: Envelope): string {
  if (envelope.messageType === 'EXECUTION_EXITED' && Object.keys(envelope.payload).length !== 0) {
    throw new ProtocolError('EXECUTION_EXITED payload must be an empty object')
  }
  const root: Record<string, unknown> = {
    protocolVersion: envelope.protocolVersion,
    messageType: envelope.messageType,
  }
  if (envelope.environmentId !== null) {
    root.environmentId = envelope.environmentId
  }
  if (envelope.invocationId !== null) {
    root.invocationId = envelope.invocationId
  }
  root.payload = envelope.payload
  return JSON.stringify(root)
}

export function decodeEnvelope(text: string): Envelope {
  const root = parseStrictObject(text, 'envelope', ENVELOPE_LIMITS)
  rejectUnknownFields(root, ENVELOPE_FIELDS, 'envelope')
  const protocolVersion = root.protocolVersion
  if (typeof protocolVersion !== 'number' || !Number.isSafeInteger(protocolVersion)) {
    throw new ProtocolError('envelope.protocolVersion must be an integer')
  }
  if (protocolVersion !== PROTOCOL_VERSION) {
    throw new ProtocolError(`unsupported protocolVersion: ${protocolVersion}`)
  }
  const messageTypeValue = root.messageType
  if (typeof messageTypeValue !== 'string' || !(MESSAGE_TYPES as readonly string[]).includes(messageTypeValue)) {
    throw new ProtocolError(`unknown daemon messageType: ${String(messageTypeValue)}`)
  }
  const messageType = messageTypeValue as MessageType
  const payload = root.payload
  if (!isPlainObject(payload)) {
    throw new ProtocolError('envelope.payload must be a JSON object')
  }
  if (messageType === 'EXECUTION_EXITED' && Object.keys(payload).length !== 0) {
    throw new ProtocolError('EXECUTION_EXITED payload must be an empty object')
  }
  let invocationId = optionalText(root, 'invocationId', 'envelope')
  if (invocationId !== null && invocationId.trim() === '') {
    throw new ProtocolError('envelope.invocationId must not be blank when present')
  }
  if (INVOCATION_MESSAGES.has(messageType)) {
    if (invocationId === null || invocationId.trim() === '') {
      throw new ProtocolError(`envelope.${messageType} requires non-blank invocationId`)
    }
  }
  let environmentId: string | null = null
  if (messageType === 'HELLO') {
    const raw = root.environmentId
    if (raw !== undefined && raw !== null) {
      throw new ProtocolError('HELLO envelope must not declare environmentId')
    }
  } else {
    const raw = root.environmentId
    if (raw !== undefined && raw !== null) {
      if (!isCanonicalUuid(raw)) {
        throw new ProtocolError('envelope.environmentId must be a canonical UUID string')
      }
      environmentId = raw
    }
    if (BOUND_MESSAGES.has(messageType) && environmentId === null) {
      throw new ProtocolError(`envelope.${messageType} requires non-null environmentId`)
    }
  }
  return { protocolVersion, messageType, environmentId, invocationId, payload }
}

// ---------------------------------------------------------------------------
// READY capabilities (payload version 5；与 envelope protocolVersion 不是同一字段)
// ---------------------------------------------------------------------------

export interface EnvironmentInfo {
  operatingSystem: OperatingSystem
  /** 宿主 CPU 架构；用于新版归档选择 amd64/arm64 制品，不支持时构建期即失败关闭。 */
  architecture: Architecture
  timeZone: string
  userName: string
  homeDirectory: string
  note: string
  tempDirectory: string
}

export interface Capabilities {
  version: number
  daemonVersion: string
  environment: EnvironmentInfo
}

const READY_ROOT_FIELDS = new Set(['version', 'daemonVersion', 'environment'])
const READY_ENVIRONMENT_FIELDS = new Set([
  'operatingSystem',
  'architecture',
  'timeZone',
  'userName',
  'homeDirectory',
  'note',
  'tempDirectory',
])
const DAEMON_VERSION_PATTERN = new RegExp(`^[0-9A-Za-z._+-]{1,${MAX_DAEMON_VERSION_CHARS}}$`)

export function validateDaemonVersion(value: string): string {
  if (value.trim() === '' || !DAEMON_VERSION_PATTERN.test(value)) {
    throw new ProtocolError(`daemonVersion must match [0-9A-Za-z._+-]{1,${MAX_DAEMON_VERSION_CHARS}}`)
  }
  return value
}

export function encodeCapabilities(capabilities: Capabilities): string {
  return JSON.stringify({
    version: capabilities.version,
    daemonVersion: capabilities.daemonVersion,
    environment: {
      operatingSystem: capabilities.environment.operatingSystem,
      architecture: capabilities.environment.architecture,
      timeZone: capabilities.environment.timeZone,
      userName: capabilities.environment.userName,
      homeDirectory: capabilities.environment.homeDirectory,
      note: capabilities.environment.note,
      tempDirectory: capabilities.environment.tempDirectory,
    },
  })
}

export function decodeCapabilities(text: string): Capabilities {
  const root = parseStrictObject(text, 'READY payload', ENVELOPE_LIMITS)
  rejectUnknownFields(root, READY_ROOT_FIELDS, 'READY payload')
  const version = root.version
  if (typeof version !== 'number' || !Number.isSafeInteger(version)) {
    throw new ProtocolError('READY payload.version must be an integer')
  }
  if (version !== READY_CAPABILITIES_VERSION) {
    throw new ProtocolError(`unsupported READY capabilities version: ${version}`)
  }
  const daemonVersion = requiredText(root, 'daemonVersion', 'READY payload')
  try {
    validateDaemonVersion(daemonVersion)
  } catch (error) {
    throw new ProtocolError(`READY capabilities validation failed: ${(error as Error).message}`)
  }
  const environment = requiredObject(root, 'environment', 'READY payload')
  rejectUnknownFields(environment, READY_ENVIRONMENT_FIELDS, 'READY environment')
  const operatingSystem = requiredText(environment, 'operatingSystem', 'READY environment')
  if (!(OPERATING_SYSTEMS as readonly string[]).includes(operatingSystem)) {
    throw new ProtocolError(`unsupported daemon operating system: ${operatingSystem}`)
  }
  const architecture = requiredText(environment, 'architecture', 'READY environment')
  if (!(ARCHITECTURES as readonly string[]).includes(architecture)) {
    throw new ProtocolError(`unsupported daemon architecture: ${architecture}`)
  }
  return {
    version,
    daemonVersion,
    environment: {
      operatingSystem: operatingSystem as EnvironmentInfo['operatingSystem'],
      architecture: architecture as EnvironmentInfo['architecture'],
      timeZone: requiredText(environment, 'timeZone', 'READY environment'),
      userName: requiredText(environment, 'userName', 'READY environment'),
      homeDirectory: requiredText(environment, 'homeDirectory', 'READY environment'),
      note: requiredText(environment, 'note', 'READY environment'),
      tempDirectory: requiredText(environment, 'tempDirectory', 'READY environment'),
    },
  }
}

// ---------------------------------------------------------------------------
// Capability INVOKE
// ---------------------------------------------------------------------------

export interface InvokeRequest {
  capabilityId: string
  capabilityVersion: string
  arguments: Record<string, unknown>
  timeoutMillis: number
  expectedFile?: ExpectedFile
}

const INVOKE_FIELDS = new Set(['capabilityId', 'capabilityVersion', 'arguments', 'timeoutMillis', 'expectedFile'])
const CAPABILITY_ID_PATTERN = /^[a-z0-9]+(?:[.-][a-z0-9]+)*$/

export function decodeInvoke(text: string): InvokeRequest {
  const root = parseStrictObject(text, 'capability INVOKE payload', ENVELOPE_LIMITS)
  rejectUnknownFields(root, INVOKE_FIELDS, 'capability INVOKE payload')
  const capabilityId = requiredText(root, 'capabilityId', 'capability INVOKE payload')
  if (capabilityId.length > MAX_CAPABILITY_ID_CHARS || !CAPABILITY_ID_PATTERN.test(capabilityId)) {
    throw new ProtocolError('capability INVOKE payload.capabilityId must use canonical syntax')
  }
  const capabilityVersion = requiredText(root, 'capabilityVersion', 'capability INVOKE payload')
  const args = requiredObject(root, 'arguments', 'capability INVOKE payload')
  const timeoutMillis = requiredNonNegativeSafeInteger(root, 'timeoutMillis', 'capability INVOKE payload')
  if (root.expectedFile === undefined) {
    return { capabilityId, capabilityVersion, arguments: args, timeoutMillis }
  }
  if (capabilityId !== 'fs.edit') {
    throw new ProtocolError('capability INVOKE payload.expectedFile is only valid for fs.edit')
  }
  const expected = requiredObject(root, 'expectedFile', 'capability INVOKE payload')
  rejectUnknownFields(expected, new Set(['path', 'sha256']), 'capability INVOKE payload.expectedFile')
  const path = requiredText(expected, 'path', 'capability INVOKE payload.expectedFile')
  const sha256 = requiredText(expected, 'sha256', 'capability INVOKE payload.expectedFile')
  if (!SHA256_PATTERN.test(sha256)) {
    throw new ProtocolError('capability INVOKE payload.expectedFile.sha256 must be lowercase SHA-256')
  }
  return { capabilityId, capabilityVersion, arguments: args, timeoutMillis, expectedFile: { path, sha256 } }
}

export function encodeInvoke(request: InvokeRequest): string {
  return JSON.stringify({
    capabilityId: request.capabilityId,
    capabilityVersion: request.capabilityVersion,
    arguments: request.arguments,
    timeoutMillis: request.timeoutMillis,
    ...(request.expectedFile === undefined ? {} : { expectedFile: request.expectedFile }),
  })
}

// ---------------------------------------------------------------------------
// Capability result (PROGRESS / COMPLETED)
// ---------------------------------------------------------------------------

export interface WireResourceContent {
  type: 'resource'
  uploadId: string
  mediaType: string
  name: string | null
  size: number
  sha256: string
  preview?: string
}

interface WireResult {
  result: {
    callId: string
    error: boolean
    details: Record<string, unknown>
    contents: unknown[]
  }
}

function validateMediaType(mediaType: string, context: string): string {
  if (!MEDIA_TYPE_PATTERN.test(mediaType)) {
    throw new ProtocolError(`${context} mediaType must be canonical lowercase type/subtype`)
  }
  if (Buffer.byteLength(mediaType, 'ascii') > MAX_MEDIA_TYPE_ASCII_BYTES) {
    throw new ProtocolError(`${context} mediaType must not exceed ${MAX_MEDIA_TYPE_ASCII_BYTES} bytes`)
  }
  return mediaType
}

function validateName(name: string | null | undefined, context: string): string | null {
  if (name === undefined || name === null) {
    return null
  }
  if (name.trim() === '') {
    throw new ProtocolError(`${context} name must not be blank`)
  }
  if (utf8ByteLength(name, `${context} name`) > MAX_NAME_UTF8_BYTES) {
    throw new ProtocolError(`${context} name must not exceed ${MAX_NAME_UTF8_BYTES} UTF-8 bytes`)
  }
  return name
}

function validatePreview(preview: string | undefined, context: string): string | undefined {
  if (preview === undefined) {
    return undefined
  }
  if (utf8ByteLength(preview, `${context} preview`) > MAX_PREVIEW_UTF8_BYTES) {
    throw new ProtocolError(`${context} preview must not exceed ${MAX_PREVIEW_UTF8_BYTES} UTF-8 bytes`)
  }
  return preview
}

function wireContentFor(content: ResultContent, uploaded: (content: Extract<ResultContent, { type: 'binary' }>) => WireResourceContent, context: string): unknown {
  switch (content.type) {
    case 'text':
      return { type: 'text', text: content.text }
    case 'json':
      return { type: 'json', json: content.json }
    case 'binary':
      return uploaded(content)
    case 'resource':
      return {
        type: 'resource',
        uploadId: content.uploadId,
        mediaType: validateMediaType(content.mediaType, context),
        name: validateName(content.name, context),
        size: content.size,
        sha256: content.sha256,
        ...(content.preview === undefined ? {} : { preview: validatePreview(content.preview, context) }),
      }
    default:
      throw new ProtocolError(`${context} has unsupported result content`)
  }
}

function buildResultTree(
  result: CapabilityResult,
  uploadBinary: (content: Extract<ResultContent, { type: 'binary' }>) => WireResourceContent,
): WireResult {
  const contents: unknown[] = []
  for (const content of result.contents) {
    contents.push(wireContentFor(content, uploadBinary, 'result.contents'))
  }
  return {
    result: {
      callId: result.callId,
      error: result.error,
      details: result.details,
      contents,
    },
  }
}

function serializeBounded(tree: unknown): string {
  const payload = JSON.stringify(tree)
  if (utf8ByteLength(payload, 'daemon payload') > MAX_PAYLOAD_UTF8_BYTES) {
    throw new ProtocolError(`daemon payload exceeds ${MAX_PAYLOAD_UTF8_BYTES} UTF-8 bytes`)
  }
  return payload
}

/** 编码 PROGRESS 结果：只允许 text/json，任何 resource/binary 在任何上传之前被拒绝。 */
export function encodeProgress(result: CapabilityResult): string {
  for (const content of result.contents) {
    if (content.type !== 'text' && content.type !== 'json') {
      throw new ProtocolError(`PROGRESS result must not contain ${content.type} content`)
    }
  }
  return serializeBounded(buildResultTree(result, () => {
    throw new ProtocolError('PROGRESS result must not contain binary content')
  }))
}

export interface BinaryUploader {
  (content: Extract<ResultContent, { type: 'binary' }>): Promise<WireResourceContent>
}

/**
 * 编码 COMPLETED 结果。
 *
 * <p>在第一个上传之前完成全部预检：resource 必须携带非空 size/sha、binary 大小取自字节、单条与聚合资源字节都必须
 * ≤ maximumResourceBytes。任何后置条目超限都不会产生任何上传副作用。预检通过后才真实上传二进制字节。
 */
export async function encodeCompleted(
  result: CapabilityResult,
  maximumResourceBytes: number,
  uploader: BinaryUploader | null,
): Promise<string> {
  if (!Number.isSafeInteger(maximumResourceBytes) || maximumResourceBytes <= 0) {
    throw new ProtocolError('maximumResourceBytes must be positive')
  }
  let aggregate = 0
  for (const content of result.contents) {
    let size: number
    if (content.type === 'resource') {
      if (!isCanonicalUuid(content.uploadId)) {
        throw new ProtocolError('resource must be uploaded to global storage before encoding')
      }
      if (!SHA256_PATTERN.test(content.sha256)) {
        throw new ProtocolError('resource ref must declare size and sha256 for the daemon wire')
      }
      size = content.size
    } else if (content.type === 'binary') {
      size = content.bytes.byteLength
    } else {
      size = 0
    }
    if (size > maximumResourceBytes - aggregate) {
      throw new ProtocolError(
        `aggregate resource bytes exceed maximumResourceBytes: maximum=${maximumResourceBytes}`,
      )
    }
    aggregate += size
  }
  // 用固定长度占位元数据构建等尺寸 wire 树，只计数不保留输出；完整 payload 通过上限后才允许真实上传。
  const placeholder = (content: Extract<ResultContent, { type: 'binary' }>): WireResourceContent => ({
    type: 'resource',
    uploadId: '00000000-0000-0000-0000-000000000000',
    mediaType: validateMediaType(content.mediaType, 'result.contents'),
    name: validateName(content.name ?? null, 'result.contents'),
    size: content.bytes.byteLength,
    sha256: '0'.repeat(64),
  })
  serializeBounded(buildResultTree(result, placeholder))
  const finalTree = buildResultTree(result, placeholder)
  // 依次真实上传二进制内容，替换为返回的纯元数据引用。
  let index = 0
  for (const content of result.contents) {
    if (content.type === 'binary') {
      if (uploader === null) {
        throw new ProtocolError('resource upload is not configured')
      }
      const uploaded = await uploader(content)
      finalTree.result.contents[index] = {
        type: 'resource',
        uploadId: uploaded.uploadId,
        mediaType: uploaded.mediaType,
        name: uploaded.name,
        size: uploaded.size,
        sha256: uploaded.sha256,
      }
    }
    index++
  }
  return serializeBounded(finalTree)
}

function decodeContent(value: unknown, context: string, remainingResourceBytes: number, allowResources: boolean): { content: ResultContent; resourceBytes: number } {
  if (!isPlainObject(value)) {
    throw new ProtocolError(`${context} must be a JSON object`)
  }
  const type = requiredText(value, 'type', context)
  switch (type) {
    case 'text': {
      rejectUnknownFields(value, new Set(['type', 'text']), context)
      const text = value.text
      if (typeof text !== 'string') {
        throw new ProtocolError(`${context}.text must be a string`)
      }
      return { content: { type: 'text', text }, resourceBytes: 0 }
    }
    case 'json': {
      rejectUnknownFields(value, new Set(['type', 'json']), context)
      if (!('json' in value)) {
        throw new ProtocolError(`${context} must declare json`)
      }
      return { content: { type: 'json', json: value.json }, resourceBytes: 0 }
    }
    case 'resource': {
      if (!allowResources) {
        throw new ProtocolError('PROGRESS result must not contain resource content')
      }
      rejectUnknownFields(
        value,
        new Set(['type', 'uploadId', 'mediaType', 'name', 'size', 'sha256', 'preview']),
        context,
      )
      const uploadId = requiredCanonicalUuid(value, 'uploadId', context)
      const mediaType = validateMediaType(requiredText(value, 'mediaType', context), context)
      const name = validateName(optionalText(value, 'name', context), context)
      const size = requiredNonNegativeSafeInteger(value, 'size', context)
      const sha256 = requiredText(value, 'sha256', context)
      if (!SHA256_PATTERN.test(sha256)) {
        throw new ProtocolError(`${context}.sha256 must be 64 lowercase hex digits`)
      }
      const preview = validatePreview(optionalText(value, 'preview', context) ?? undefined, context)
      if (size > remainingResourceBytes) {
        throw new ProtocolError(
          `aggregate declared resource bytes exceed maximumResourceBytes: declared=${size} remaining=${remainingResourceBytes}`,
        )
      }
      return {
        content: { type: 'resource', uploadId, mediaType, name, size, sha256, ...(preview === undefined ? {} : { preview }) },
        resourceBytes: size,
      }
    }
    default:
      throw new ProtocolError(`${context} unknown content type: ${type}`)
  }
}

function decodeResult(text: string, expectedCallId: string | null, maximumResourceBytes: number, allowResources: boolean): CapabilityResult {
  const root = parseStrictObject(text, 'result payload', ENVELOPE_LIMITS)
  rejectUnknownFields(root, new Set(['result']), 'result payload')
  const resultObject = requiredObject(root, 'result', 'result payload')
  rejectUnknownFields(resultObject, new Set(['callId', 'error', 'details', 'contents']), 'result')
  const callId = requiredText(resultObject, 'callId', 'result')
  if (expectedCallId !== null && expectedCallId !== callId) {
    throw new ProtocolError('result callId does not match expected invocationId')
  }
  const error = requiredBoolean(resultObject, 'error', 'result')
  const details = requiredObject(resultObject, 'details', 'result')
  const contentsNode = resultObject.contents
  if (!Array.isArray(contentsNode)) {
    throw new ProtocolError("result 'contents' must be an array")
  }
  if (contentsNode.length > MAX_CONTENT_ITEMS) {
    throw new ProtocolError(`result 'contents' must not exceed ${MAX_CONTENT_ITEMS} items`)
  }
  const contents: ResultContent[] = []
  let declaredResourceBytes = 0
  for (let index = 0; index < contentsNode.length; index++) {
    const remaining = maximumResourceBytes - declaredResourceBytes
    const decoded = decodeContent(contentsNode[index], `result.contents[${index}]`, remaining, allowResources)
    declaredResourceBytes += decoded.resourceBytes
    contents.push(decoded.content)
  }
  return { callId, error, details, contents }
}

export function decodeProgress(text: string, expectedCallId: string, maximumResourceBytes: number): CapabilityResult {
  return decodeResult(text, expectedCallId, maximumResourceBytes, false)
}

export function decodeCompleted(text: string, expectedCallId: string, maximumResourceBytes: number): CapabilityResult {
  return decodeResult(text, expectedCallId, maximumResourceBytes, true)
}

// ---------------------------------------------------------------------------
// Resource transfer control
// ---------------------------------------------------------------------------

export interface UploadRequest {
  transferId: string
  mediaType: string
  name: string | null
  size: number
  sha256: string
}

export interface UploadCommit {
  transferId: string
  uploadId: string
}

export interface PresignedPut {
  method: string
  url: string
  headers: Record<string, string>
  expiresAt: string
}

export interface UploadTicket {
  transferId: string
  state: TicketState
  uploadId: string | null
  presignedPut: PresignedPut | null
  message: string | null
}

export const TRANSFER_ID_FIELD = 'transferId'

export function encodeUploadRequest(request: UploadRequest): string {
  return JSON.stringify({
    transferId: request.transferId,
    mediaType: request.mediaType,
    name: request.name,
    size: request.size,
    sha256: request.sha256,
  })
}

export function decodeUploadRequest(text: string): UploadRequest {
  const root = parseStrictObject(text, 'upload request', CONTROL_LIMITS)
  rejectUnknownFields(root, new Set([TRANSFER_ID_FIELD, 'mediaType', 'name', 'size', 'sha256']), 'upload request')
  const transferId = requiredCanonicalUuid(root, TRANSFER_ID_FIELD, 'upload request')
  const mediaType = validateMediaType(requiredText(root, 'mediaType', 'upload request'), 'upload request')
  const name = validateName(optionalText(root, 'name', 'upload request'), 'upload request')
  const size = requiredNonNegativeSafeInteger(root, 'size', 'upload request')
  const sha256 = requiredText(root, 'sha256', 'upload request')
  if (!SHA256_PATTERN.test(sha256)) {
    throw new ProtocolError('upload request.sha256 must be 64 lowercase hex digits')
  }
  return { transferId, mediaType, name, size, sha256 }
}

export function encodeUploadCommit(commit: UploadCommit): string {
  return JSON.stringify({ transferId: commit.transferId, uploadId: commit.uploadId })
}

export function decodeUploadCommit(text: string): UploadCommit {
  const root = parseStrictObject(text, 'upload commit', CONTROL_LIMITS)
  rejectUnknownFields(root, new Set([TRANSFER_ID_FIELD, 'uploadId']), 'upload commit')
  return {
    transferId: requiredCanonicalUuid(root, TRANSFER_ID_FIELD, 'upload commit'),
    uploadId: requiredCanonicalUuid(root, 'uploadId', 'upload commit'),
  }
}

export function encodeUploadTicket(ticket: UploadTicket): string {
  const root: Record<string, unknown> = { transferId: ticket.transferId, state: ticket.state }
  switch (ticket.state) {
    case 'PENDING':
      root.uploadId = ticket.uploadId
      root.presignedPut = ticket.presignedPut
      break
    case 'READY':
      root.uploadId = ticket.uploadId
      break
    case 'FAILED':
      root.message = ticket.message
      break
  }
  return JSON.stringify(root)
}

export function decodeUploadTicket(text: string): UploadTicket {
  const root = parseStrictObject(text, 'upload ticket', CONTROL_LIMITS)
  const transferId = requiredCanonicalUuid(root, TRANSFER_ID_FIELD, 'upload ticket')
  const stateText = requiredText(root, 'state', 'upload ticket')
  if (!(TICKET_STATES as readonly string[]).includes(stateText)) {
    throw new ProtocolError(`upload ticket state is unknown: ${stateText}`)
  }
  if (stateText === 'PENDING') {
    rejectUnknownFields(root, new Set([TRANSFER_ID_FIELD, 'state', 'uploadId', 'presignedPut']), 'upload ticket')
    return {
      transferId,
      state: 'PENDING',
      uploadId: requiredCanonicalUuid(root, 'uploadId', 'upload ticket'),
      presignedPut: decodePresignedPut(root.presignedPut),
      message: null,
    }
  }
  if (stateText === 'READY') {
    rejectUnknownFields(root, new Set([TRANSFER_ID_FIELD, 'state', 'uploadId']), 'upload ticket')
    return {
      transferId,
      state: 'READY',
      uploadId: requiredCanonicalUuid(root, 'uploadId', 'upload ticket'),
      presignedPut: null,
      message: null,
    }
  }
  rejectUnknownFields(root, new Set([TRANSFER_ID_FIELD, 'state', 'message']), 'upload ticket')
  const message = requiredText(root, 'message', 'upload ticket')
  if (message.length > MAX_FAILURE_MESSAGE_CHARS) {
    throw new ProtocolError(`upload ticket.message must not exceed ${MAX_FAILURE_MESSAGE_CHARS} characters`)
  }
  return { transferId, state: 'FAILED', uploadId: null, presignedPut: null, message }
}

function decodePresignedPut(value: unknown): PresignedPut {
  if (!isPlainObject(value)) {
    throw new ProtocolError('upload ticket presignedPut must be an object')
  }
  rejectUnknownFields(value, new Set(['method', 'url', 'headers', 'expiresAt']), 'upload ticket presignedPut')
  const method = requiredText(value, 'method', 'upload ticket presignedPut')
  if (method !== 'PUT') {
    throw new ProtocolError('upload ticket presignedPut.method must be PUT')
  }
  const url = requiredText(value, 'url', 'upload ticket presignedPut')
  if (url.length > MAX_PRESIGNED_URL_CHARS) {
    throw new ProtocolError(`upload ticket presignedPut.url must not exceed ${MAX_PRESIGNED_URL_CHARS} characters`)
  }
  const headersNode = value.headers
  if (!isPlainObject(headersNode)) {
    throw new ProtocolError('upload ticket presignedPut.headers must be an object')
  }
  const headers: Record<string, string> = {}
  for (const [name, headerValue] of Object.entries(headersNode)) {
    if (typeof headerValue !== 'string' || headerValue.trim() === '' || name.trim() === '') {
      throw new ProtocolError('upload ticket presignedPut header names and values must be non-blank strings')
    }
    if (name.length > MAX_PRESIGNED_HEADER_CHARS || headerValue.length > MAX_PRESIGNED_HEADER_CHARS) {
      throw new ProtocolError(
        `upload ticket presignedPut header name and value must not exceed ${MAX_PRESIGNED_HEADER_CHARS} characters`,
      )
    }
    headers[name] = headerValue
  }
  if (Object.keys(headers).length > MAX_PRESIGNED_HEADERS) {
    throw new ProtocolError(`upload ticket presignedPut.headers must not exceed ${MAX_PRESIGNED_HEADERS} entries`)
  }
  const expiresAt = requiredText(value, 'expiresAt', 'upload ticket presignedPut')
  const parsed = new Date(expiresAt)
  if (Number.isNaN(parsed.getTime()) || parsed.toISOString() !== expiresAt) {
    throw new ProtocolError('upload ticket presignedPut.expiresAt must be a canonical ISO-8601 instant')
  }
  return { method, url, headers, expiresAt }
}

// ---------------------------------------------------------------------------
// Managed update
// ---------------------------------------------------------------------------

export interface UpdateCommand {
  operationId: string
  targetVersion: string
  artifactUrl: string
  artifactSha256: string
}

export interface UpdateResult {
  operationId: string
  phase: UpdatePhase
  message: string | null
}

export function decodeUpdateCommand(text: string): UpdateCommand {
  const root = parseStrictObject(text, 'update command payload', UPDATE_LIMITS)
  rejectUnknownFields(root, new Set(['operationId', 'targetVersion', 'artifactUrl', 'artifactSha256']), 'update command')
  const operationId = requiredCanonicalUuid(root, 'operationId', 'update command')
  const targetVersion = requiredText(root, 'targetVersion', 'update command')
  validateDaemonVersion(targetVersion)
  const artifactUrl = requiredText(root, 'artifactUrl', 'update command')
  // 只接受官方五平台 tar.gz 制品：tag、targetVersion 与制品版本必须是同一个事实，平台必须是已发布组合。
  const artifact = parseReleaseArtifactUrl(artifactUrl)
  if (artifact === null) {
    throw new ProtocolError('update command.artifactUrl must be the official platform release archive')
  }
  if (artifact.targetVersion !== targetVersion) {
    throw new ProtocolError('update command.artifactUrl must match targetVersion')
  }
  const artifactSha256 = requiredText(root, 'artifactSha256', 'update command')
  if (!/^[0-9a-fA-F]{64}$/.test(artifactSha256)) {
    throw new ProtocolError('update command.artifactSha256 must be 64 hex characters')
  }
  return { operationId, targetVersion, artifactUrl, artifactSha256 }
}

export function encodeUpdateCommand(command: UpdateCommand): string {
  return JSON.stringify({
    operationId: command.operationId,
    targetVersion: command.targetVersion,
    artifactUrl: command.artifactUrl,
    artifactSha256: command.artifactSha256,
  })
}

export function encodeUpdateResult(result: UpdateResult): string {
  const root: Record<string, unknown> = { operationId: result.operationId, phase: result.phase }
  if (result.message !== null) {
    root.message = result.message
  }
  return JSON.stringify(root)
}

export function decodeUpdateResult(text: string): UpdateResult {
  const root = parseStrictObject(text, 'update result payload', UPDATE_LIMITS)
  rejectUnknownFields(root, new Set(['operationId', 'phase', 'message']), 'update result')
  const operationId = requiredText(root, 'operationId', 'update result')
  const phaseText = requiredText(root, 'phase', 'update result')
  const phase = UPDATE_PHASES.find((candidate) => candidate === phaseText)
  if (phase === undefined) {
    throw new ProtocolError(`unknown update phase: ${phaseText}`)
  }
  const message = optionalText(root, 'message', 'update result')
  if (message !== null) {
    if (message.trim() === '' || message.length > MAX_UPDATE_MESSAGE_CHARS) {
      throw new ProtocolError('update result.message must be a bounded non-blank string')
    }
  }
  return { operationId, phase, message }
}

// ---------------------------------------------------------------------------
// IDE metadata (IDE_COMMAND / IDE_RESULT)
// ---------------------------------------------------------------------------

const IDE_ACTIONS = new Set(['OPEN_VIEW', 'CHANGE_DIRECTORY', 'FOCUS_TERMINAL', 'CLOSE_VIEW', 'OPEN_STREAM'])
const IDE_COMMAND_FIELDS = new Set([
  'action',
  'viewId',
  'directory',
  'ticket',
  'streamUrl',
  'mode',
  'method',
  'path',
  'headers',
])
const IDE_REPLY_FIELDS = new Set(['ok', 'viewId', 'directory', 'workspacePath', 'message'])

export function decodeIdeCommand(text: string): IdeCommand {
  const root = parseStrictObject(text, 'IDE command payload', ENVELOPE_LIMITS)
  rejectUnknownFields(root, IDE_COMMAND_FIELDS, 'IDE command')
  const action = requiredText(root, 'action', 'IDE command')
  if (!IDE_ACTIONS.has(action)) {
    throw new ProtocolError(`IDE command.action is unknown: ${action}`)
  }
  const command: IdeCommand = { action: action as IdeCommand['action'] }
  for (const field of ['viewId', 'directory', 'ticket', 'streamUrl', 'method', 'path'] as const) {
    const value = optionalText(root, field, 'IDE command')
    if (value !== null) {
      if (value.trim() === '') {
        throw new ProtocolError(`IDE command.${field} must not be blank`)
      }
      command[field] = value
    }
  }
  const mode = optionalText(root, 'mode', 'IDE command')
  if (mode !== null) {
    if (mode !== 'HTTP' && mode !== 'WS') {
      throw new ProtocolError(`IDE command.mode is unknown: ${mode}`)
    }
    command.mode = mode
  }
  if ('headers' in root && root.headers !== null) {
    const headers = root.headers
    if (!Array.isArray(headers)) {
      throw new ProtocolError('IDE command.headers must be an array of [name, value] pairs')
    }
    const pairs: [string, string][] = []
    for (const entry of headers) {
      if (!Array.isArray(entry) || entry.length !== 2 || typeof entry[0] !== 'string' || typeof entry[1] !== 'string') {
        throw new ProtocolError('IDE command.headers must be an array of [name, value] pairs')
      }
      pairs.push([entry[0], entry[1]])
    }
    command.headers = pairs
  }
  return command
}

export function encodeIdeCommand(command: IdeCommand): string {
  const root: Record<string, unknown> = { action: command.action }
  for (const field of ['viewId', 'directory', 'ticket', 'streamUrl', 'mode', 'method', 'path'] as const) {
    if (command[field] !== undefined) {
      root[field] = command[field]
    }
  }
  if (command.headers !== undefined) {
    root.headers = command.headers
  }
  return JSON.stringify(root)
}

export function encodeIdeReply(reply: IdeReply): string {
  const root: Record<string, unknown> = { ok: reply.ok }
  for (const field of ['viewId', 'directory', 'workspacePath', 'message'] as const) {
    if (reply[field] !== undefined) {
      root[field] = reply[field]
    }
  }
  return JSON.stringify(root)
}

export function decodeIdeReply(text: string): IdeReply {
  const root = parseStrictObject(text, 'IDE result payload', ENVELOPE_LIMITS)
  rejectUnknownFields(root, IDE_REPLY_FIELDS, 'IDE result')
  const ok = requiredBoolean(root, 'ok', 'IDE result')
  const reply: IdeReply = { ok }
  for (const field of ['viewId', 'directory', 'workspacePath', 'message'] as const) {
    const value = optionalText(root, field, 'IDE result')
    if (value !== null) {
      reply[field] = value
    }
  }
  return reply
}

// ---------------------------------------------------------------------------
// Shared payload helpers
// ---------------------------------------------------------------------------

/** 构造一个只带 message 的 ERROR/FAILED payload。 */
export function errorPayload(message: string): Record<string, unknown> {
  return { message }
}
