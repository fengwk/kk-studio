/**
 * 终端浏览器控制命令编码器与事件解码器（Terminal Control Codec）。
 *
 * 与 Java TerminalControlCodec / TerminalCommand / TerminalEvent 规范严格一致的纯协议编解码层：
 * - encodeTerminalCommand: 将命令对象严格校验后按权威字段顺序编码为 canonical JSON wire 文本
 * - decodeTerminalEvent: 将已由上层解析好的 JSON 对象严格校验为不可变且 owned frozen 的事件模型
 * - validateTerminalCommand: 严格验证命令对象并返回防御性深只读结构
 *
 * 边界与约束：
 * - 纯协议层，不持有 WebSocket/网络连接、不引入外部依赖、不暴露 route wrapper facade
 * - 错误信息完全统一为固定文案，严禁回显 token、bytes、executable 或 screen 内容
 * - VIEW_UPDATE 委托既有 decodeTerminalViewUpdate 处理，并校验 identity terminalId 一致性
 */

import {
  decodeTerminalViewUpdate,
  MAX_COLUMNS,
  MAX_ROWS,
  MAX_SAFE_INTEGER,
  MIN_COLUMNS,
  MIN_ROWS,
  TerminalViewError,
  type TerminalViewUpdate,
} from './terminal-view-codec'

export const TERMINAL_CONTROL_VERSION = 1

export const MIN_INPUT_BYTES = 1
export const MAX_INPUT_BYTES = 4096
const MIN_INPUT_BASE64_LENGTH = 4
const MAX_INPUT_BASE64_LENGTH = Math.floor((MAX_INPUT_BYTES + 2) / 3) * 4 // 5464

export const MAX_EXECUTABLE_UTF8_BYTES = 4096
export const MAX_CONTROL_MESSAGE_BYTES = 8 * 1024 * 1024

export const TERMINAL_CONTROL_INVALID_MESSAGE =
  'terminal control message is invalid'

export class TerminalControlError extends Error {
  constructor() {
    super(TERMINAL_CONTROL_INVALID_MESSAGE)
    this.name = 'TerminalControlError'
  }
}

export type TerminalCommandType =
  | 'OPEN'
  | 'ATTACH'
  | 'DETACH'
  | 'CLAIM'
  | 'TAKEOVER'
  | 'RELEASE'
  | 'INPUT'
  | 'RESIZE'
  | 'VIEW_APPLIED'
  | 'KEEPALIVE'
  | 'CLOSE'

export type TerminalEventType =
  | 'ATTACHED'
  | 'WRITER_CHANGED'
  | 'OP_ACK'
  | 'VIEW_UPDATE'
  | 'EXITED'
  | 'ERROR'

export type TerminalStatus = 'RUNNING' | 'EXITED' | 'FAILED'

export type OperationOutcome = 'WRITTEN' | 'NOT_WRITTEN' | 'OUTCOME_UNKNOWN'

export type ControlResultStatus =
  | 'GRANTED'
  | 'RENEWED'
  | 'RELEASED'
  | 'BUSY'
  | 'REJECTED'

export type ControlRejectReason =
  | 'NOT_OWNER'
  | 'LEASE_EXPIRED'
  | 'FROZEN'
  | 'CAS_FAILED'
  | 'INVALID'
  | 'RECOVERY_MISMATCH'
  | 'RECOVERY_UNVERIFIABLE'
  | 'REQUEST_CONFLICT'

export type AdmissionKind = 'PENDING' | 'CONFIRMED' | 'REJECTED'

export type AdmissionRejectReason =
  | 'NOT_OWNER'
  | 'LEASE_EXPIRED'
  | 'FROZEN'
  | 'INVALID'
  | 'SEQ_CONFLICT'
  | 'SEQ_GAP'
  | 'UNVERIFIABLE'

export type ErrorCode =
  | 'INVALID_REQUEST'
  | 'TERMINAL_NOT_FOUND'
  | 'DAEMON_MISMATCH'
  | 'REQUEST_CONFLICT'
  | 'STREAM_NOT_FOUND'
  | 'VIEW_NOT_APPLIED'
  | 'STALE_MODE'
  | 'BUSY'
  | 'ROUTE_UNAVAILABLE'
  | 'BACKPRESSURE'
  | 'RUNTIME_FAILED'
  | 'OUTCOME_UNKNOWN'

export type ErrorDisposition = 'NOT_EXECUTED' | 'OUTCOME_UNKNOWN'

export interface TerminalIdentity {
  readonly daemonInstanceId: string
  readonly terminalId: string
}

export interface WriterGrant {
  readonly epoch: string
  readonly token: string
}

export interface Recovery {
  readonly previous: WriterGrant
  readonly seq: number
  readonly digest: string | null
}

export interface WriterState {
  readonly writerEpoch: string | null
  readonly lastWrittenSeq: number
  readonly lastWrittenDigest: string | null
  readonly lastResolvedSeq: number
  readonly lastResolvedDigest: string | null
  readonly lastResolvedOutcome: OperationOutcome | null
  readonly pendingSeq: number
  readonly pendingDigest: string | null
  readonly frozen: boolean
}

export interface ControlResult {
  readonly status: ControlResultStatus
  readonly grant: WriterGrant | null
  readonly recovered: OperationOutcome | null
  readonly reason: ControlRejectReason | null
}

export interface AdmissionResult {
  readonly kind: AdmissionKind
  readonly seq: number
  readonly digest: string | null
  readonly outcome: OperationOutcome | null
  readonly reason: AdmissionRejectReason | null
}

export interface TerminalOpenPayload {
  readonly expectedExited: TerminalIdentity | null
}

export interface TerminalAttachPayload {
  readonly identity: TerminalIdentity
}

export interface TerminalDetachPayload {
  readonly identity: TerminalIdentity
  readonly streamId: string
}

export interface TerminalClaimPayload {
  readonly identity: TerminalIdentity
  readonly streamId: string
  readonly recovery: Recovery | null
}

export interface TerminalTakeoverPayload {
  readonly identity: TerminalIdentity
  readonly streamId: string
  readonly expectedWriterEpoch: string | null
}

export interface TerminalReleasePayload {
  readonly identity: TerminalIdentity
  readonly streamId: string
  readonly grant: WriterGrant
}

export interface TerminalInputPayload {
  readonly identity: TerminalIdentity
  readonly streamId: string
  readonly grant: WriterGrant
  readonly seq: number
  readonly inputModeRevision: number
  readonly bytes: Uint8Array
}

export interface TerminalResizePayload {
  readonly identity: TerminalIdentity
  readonly streamId: string
  readonly grant: WriterGrant
  readonly seq: number
  readonly cols: number
  readonly rows: number
}

export interface TerminalViewAppliedPayload {
  readonly identity: TerminalIdentity
  readonly streamId: string
  readonly version: number
}

export interface TerminalKeepalivePayload {
  readonly identity: TerminalIdentity
  readonly streamId: string
  readonly grant: WriterGrant | null
}

export interface TerminalClosePayload {
  readonly identity: TerminalIdentity
  readonly expectedWriterEpoch: string | null
}

export interface TerminalCommandPayloadMap {
  OPEN: TerminalOpenPayload
  ATTACH: TerminalAttachPayload
  DETACH: TerminalDetachPayload
  CLAIM: TerminalClaimPayload
  TAKEOVER: TerminalTakeoverPayload
  RELEASE: TerminalReleasePayload
  INPUT: TerminalInputPayload
  RESIZE: TerminalResizePayload
  VIEW_APPLIED: TerminalViewAppliedPayload
  KEEPALIVE: TerminalKeepalivePayload
  CLOSE: TerminalClosePayload
}

export interface TerminalCommandBase<
  T extends TerminalCommandType,
  P extends TerminalCommandPayloadMap[T],
> {
  readonly version: 1
  readonly requestId: string
  readonly environmentId: string
  readonly viewerId: string
  readonly type: T
  readonly payload: P
}

export type TerminalCommandOf<T extends TerminalCommandType> =
  TerminalCommandBase<T, TerminalCommandPayloadMap[T]>

export type TerminalOpenCommand = TerminalCommandOf<'OPEN'>
export type TerminalAttachCommand = TerminalCommandOf<'ATTACH'>
export type TerminalDetachCommand = TerminalCommandOf<'DETACH'>
export type TerminalClaimCommand = TerminalCommandOf<'CLAIM'>
export type TerminalTakeoverCommand = TerminalCommandOf<'TAKEOVER'>
export type TerminalReleaseCommand = TerminalCommandOf<'RELEASE'>
export type TerminalInputCommand = TerminalCommandOf<'INPUT'>
export type TerminalResizeCommand = TerminalCommandOf<'RESIZE'>
export type TerminalViewAppliedCommand = TerminalCommandOf<'VIEW_APPLIED'>
export type TerminalKeepaliveCommand = TerminalCommandOf<'KEEPALIVE'>
export type TerminalCloseCommand = TerminalCommandOf<'CLOSE'>

export type TerminalCommandPayload =
  TerminalCommandPayloadMap[TerminalCommandType]

export type TerminalCommand = {
  [K in TerminalCommandType]: TerminalCommandOf<K>
}[TerminalCommandType]

export interface TerminalAttachedPayload {
  readonly streamId: string
  readonly executable: string
  readonly status: TerminalStatus
  readonly exitCode: number | null
  readonly inputModeRevision: number
  readonly writer: WriterState
}

export interface TerminalWriterChangedPayload {
  readonly writer: WriterState
  readonly result: ControlResult | null
}

export interface TerminalOpAckPayload {
  readonly writerEpoch: string
  readonly result: AdmissionResult
  readonly code: ErrorCode | null
}

export interface TerminalViewUpdatePayload {
  readonly update: TerminalViewUpdate
}

export interface TerminalExitedPayload {
  readonly status: TerminalStatus
  readonly exitCode: number | null
}

export interface TerminalErrorPayload {
  readonly code: ErrorCode
  readonly disposition: ErrorDisposition
}

export interface TerminalEventPayloadMap {
  ATTACHED: TerminalAttachedPayload
  WRITER_CHANGED: TerminalWriterChangedPayload
  OP_ACK: TerminalOpAckPayload
  VIEW_UPDATE: TerminalViewUpdatePayload
  EXITED: TerminalExitedPayload
  ERROR: TerminalErrorPayload
}

export interface TerminalEventBase<
  T extends TerminalEventType,
  P extends TerminalEventPayloadMap[T],
  I extends TerminalIdentity | null = TerminalIdentity,
> {
  readonly version: 1
  readonly requestId: string | null
  readonly environmentId: string
  readonly viewerId: string
  readonly identity: I
  readonly type: T
  readonly payload: P
}

export type TerminalAttachedEvent = TerminalEventBase<
  'ATTACHED',
  TerminalAttachedPayload,
  TerminalIdentity
>
export type TerminalWriterChangedEvent = TerminalEventBase<
  'WRITER_CHANGED',
  TerminalWriterChangedPayload,
  TerminalIdentity
>
export type TerminalOpAckEvent = TerminalEventBase<
  'OP_ACK',
  TerminalOpAckPayload,
  TerminalIdentity
>
export type TerminalViewUpdateEvent = TerminalEventBase<
  'VIEW_UPDATE',
  TerminalViewUpdatePayload,
  TerminalIdentity
>
export type TerminalExitedEvent = TerminalEventBase<
  'EXITED',
  TerminalExitedPayload,
  TerminalIdentity
>
export type TerminalErrorEvent = TerminalEventBase<
  'ERROR',
  TerminalErrorPayload,
  TerminalIdentity | null
>

export type TerminalEventPayload =
  TerminalEventPayloadMap[TerminalEventType]

export type TerminalEvent =
  | TerminalAttachedEvent
  | TerminalWriterChangedEvent
  | TerminalOpAckEvent
  | TerminalViewUpdateEvent
  | TerminalExitedEvent
  | TerminalErrorEvent

const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const DIGEST_REGEX = /^[0-9a-f]{64}$/
const BASE64_PATTERN = /^[A-Za-z0-9+/]+={0,2}$/
const BASE64_CHARS =
  'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

const COMMAND_ROOT_FIELDS = new Set([
  'version',
  'requestId',
  'environmentId',
  'viewerId',
  'type',
  'payload',
])

const EVENT_ROOT_FIELDS = new Set([
  'version',
  'requestId',
  'environmentId',
  'viewerId',
  'identity',
  'type',
  'payload',
])

const IDENTITY_FIELDS = new Set(['daemonInstanceId', 'terminalId'])
const GRANT_FIELDS = new Set(['epoch', 'token'])
const RECOVERY_FIELDS = new Set(['previous', 'seq', 'digest'])
const WRITER_STATE_FIELDS = new Set([
  'writerEpoch',
  'lastWrittenSeq',
  'lastWrittenDigest',
  'lastResolvedSeq',
  'lastResolvedDigest',
  'lastResolvedOutcome',
  'pendingSeq',
  'pendingDigest',
  'frozen',
])
const CONTROL_RESULT_FIELDS = new Set([
  'status',
  'grant',
  'recovered',
  'reason',
])
const ADMISSION_RESULT_FIELDS = new Set([
  'kind',
  'seq',
  'digest',
  'outcome',
  'reason',
])

const OPEN_PAYLOAD_FIELDS = new Set(['expectedExited'])
const ATTACH_PAYLOAD_FIELDS = new Set(['identity'])
const DETACH_PAYLOAD_FIELDS = new Set(['identity', 'streamId'])
const CLAIM_PAYLOAD_FIELDS = new Set(['identity', 'streamId', 'recovery'])
const TAKEOVER_PAYLOAD_FIELDS = new Set([
  'identity',
  'streamId',
  'expectedWriterEpoch',
])
const RELEASE_PAYLOAD_FIELDS = new Set(['identity', 'streamId', 'grant'])
const INPUT_PAYLOAD_FIELDS = new Set([
  'identity',
  'streamId',
  'grant',
  'seq',
  'inputModeRevision',
  'bytes',
])
const RESIZE_PAYLOAD_FIELDS = new Set([
  'identity',
  'streamId',
  'grant',
  'seq',
  'cols',
  'rows',
])
const VIEW_APPLIED_PAYLOAD_FIELDS = new Set(['identity', 'streamId', 'version'])
const KEEPALIVE_PAYLOAD_FIELDS = new Set(['identity', 'streamId', 'grant'])
const CLOSE_PAYLOAD_FIELDS = new Set(['identity', 'expectedWriterEpoch'])

const ATTACHED_PAYLOAD_FIELDS = new Set([
  'streamId',
  'executable',
  'status',
  'exitCode',
  'inputModeRevision',
  'writer',
])
const WRITER_CHANGED_PAYLOAD_FIELDS = new Set(['writer', 'result'])
const OP_ACK_PAYLOAD_FIELDS = new Set(['writerEpoch', 'result', 'code'])
const VIEW_UPDATE_PAYLOAD_FIELDS = new Set(['update'])
const EXITED_PAYLOAD_FIELDS = new Set(['status', 'exitCode'])
const ERROR_PAYLOAD_FIELDS = new Set(['code', 'disposition'])

const COMMAND_TYPES = new Set<TerminalCommandType>([
  'OPEN',
  'ATTACH',
  'DETACH',
  'CLAIM',
  'TAKEOVER',
  'RELEASE',
  'INPUT',
  'RESIZE',
  'VIEW_APPLIED',
  'KEEPALIVE',
  'CLOSE',
])

const EVENT_TYPES = new Set<TerminalEventType>([
  'ATTACHED',
  'WRITER_CHANGED',
  'OP_ACK',
  'VIEW_UPDATE',
  'EXITED',
  'ERROR',
])

const TERMINAL_STATUSES = new Set<TerminalStatus>([
  'RUNNING',
  'EXITED',
  'FAILED',
])

const OPERATION_OUTCOMES = new Set<OperationOutcome>([
  'WRITTEN',
  'NOT_WRITTEN',
  'OUTCOME_UNKNOWN',
])

const CONTROL_STATUSES = new Set<ControlResultStatus>([
  'GRANTED',
  'RENEWED',
  'RELEASED',
  'BUSY',
  'REJECTED',
])

const CONTROL_REJECT_REASONS = new Set<ControlRejectReason>([
  'NOT_OWNER',
  'LEASE_EXPIRED',
  'FROZEN',
  'CAS_FAILED',
  'INVALID',
  'RECOVERY_MISMATCH',
  'RECOVERY_UNVERIFIABLE',
  'REQUEST_CONFLICT',
])

const ADMISSION_KINDS = new Set<AdmissionKind>([
  'PENDING',
  'CONFIRMED',
  'REJECTED',
])

const ADMISSION_REJECT_REASONS = new Set<AdmissionRejectReason>([
  'NOT_OWNER',
  'LEASE_EXPIRED',
  'FROZEN',
  'INVALID',
  'SEQ_CONFLICT',
  'SEQ_GAP',
  'UNVERIFIABLE',
])

const ERROR_CODES = new Set<ErrorCode>([
  'INVALID_REQUEST',
  'TERMINAL_NOT_FOUND',
  'DAEMON_MISMATCH',
  'REQUEST_CONFLICT',
  'STREAM_NOT_FOUND',
  'VIEW_NOT_APPLIED',
  'STALE_MODE',
  'BUSY',
  'ROUTE_UNAVAILABLE',
  'BACKPRESSURE',
  'RUNTIME_FAILED',
  'OUTCOME_UNKNOWN',
])

const ERROR_DISPOSITIONS = new Set<ErrorDisposition>([
  'NOT_EXECUTED',
  'OUTCOME_UNKNOWN',
])

function rejectUnknownAndRequireFields(
  obj: Record<string, unknown>,
  allowed: Set<string>,
): void {
  const keys = Object.keys(obj)
  if (keys.length !== allowed.size) {
    throw new TerminalControlError()
  }
  for (const key of keys) {
    if (!allowed.has(key) || obj[key] === undefined) {
      throw new TerminalControlError()
    }
  }
}

function requireObject(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new TerminalControlError()
  }
  return value as Record<string, unknown>
}

function requireCanonicalUuid(value: unknown): string {
  if (typeof value !== 'string' || !UUID_REGEX.test(value)) {
    throw new TerminalControlError()
  }
  return value
}

function optionalCanonicalUuid(value: unknown): string | null {
  if (value === null) {
    return null
  }
  return requireCanonicalUuid(value)
}

function requireDigest(value: unknown): string {
  if (typeof value !== 'string' || !DIGEST_REGEX.test(value)) {
    throw new TerminalControlError()
  }
  return value
}

function optionalDigest(value: unknown): string | null {
  if (value === null) {
    return null
  }
  return requireDigest(value)
}

function requireSafeInteger(val: unknown, min: number, max: number): number {
  if (
    typeof val !== 'number' ||
    !Number.isSafeInteger(val) ||
    val < min ||
    val > max ||
    Object.is(val, -0)
  ) {
    throw new TerminalControlError()
  }
  return val
}

function requirePositiveSafeInteger(val: unknown): number {
  return requireSafeInteger(val, 1, MAX_SAFE_INTEGER)
}

function requireNonNegativeSafeInteger(val: unknown): number {
  return requireSafeInteger(val, 0, MAX_SAFE_INTEGER)
}

function requireInt32(val: unknown): number {
  return requireSafeInteger(val, -2147483648, 2147483647)
}

function requireBoolean(val: unknown): boolean {
  if (typeof val !== 'boolean') {
    throw new TerminalControlError()
  }
  return val
}

function measureUtf8BytesAndValidate(str: string): number {
  let bytes = 0
  const len = str.length
  for (let i = 0; i < len; i++) {
    const code = str.charCodeAt(i)
    if (code <= 0x7f) {
      bytes += 1
    } else if (code <= 0x7ff) {
      bytes += 2
    } else if (code >= 0xd800 && code <= 0xdbff) {
      if (i + 1 < len) {
        const next = str.charCodeAt(i + 1)
        if (next >= 0xdc00 && next <= 0xdfff) {
          bytes += 4
          i++
          continue
        }
      }
      throw new TerminalControlError()
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      throw new TerminalControlError()
    } else {
      bytes += 3
    }
  }
  return bytes
}

function uint8ArrayToBase64(bytes: Uint8Array): string {
  let result = ''
  const len = bytes.length
  let i = 0
  for (; i + 2 < len; i += 3) {
    const b0 = bytes[i]
    const b1 = bytes[i + 1]
    const b2 = bytes[i + 2]
    result += BASE64_CHARS[b0 >> 2]
    result += BASE64_CHARS[((b0 & 3) << 4) | (b1 >> 4)]
    result += BASE64_CHARS[((b1 & 15) << 2) | (b2 >> 6)]
    result += BASE64_CHARS[b2 & 63]
  }
  if (i < len) {
    const b0 = bytes[i]
    result += BASE64_CHARS[b0 >> 2]
    if (i + 1 < len) {
      const b1 = bytes[i + 1]
      result += BASE64_CHARS[((b0 & 3) << 4) | (b1 >> 4)]
      result += BASE64_CHARS[(b1 & 15) << 2]
      result += '='
    } else {
      result += BASE64_CHARS[(b0 & 3) << 4]
      result += '=='
    }
  }
  return result
}

function base64ToUint8Array(text: string): Uint8Array {
  if (
    typeof text !== 'string' ||
    text.length < MIN_INPUT_BASE64_LENGTH ||
    text.length > MAX_INPUT_BASE64_LENGTH ||
    text.length % 4 !== 0 ||
    !BASE64_PATTERN.test(text)
  ) {
    throw new TerminalControlError()
  }
  let binary: string
  try {
    binary = atob(text)
  } catch {
    throw new TerminalControlError()
  }
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  if (
    bytes.length < MIN_INPUT_BYTES ||
    bytes.length > MAX_INPUT_BYTES ||
    uint8ArrayToBase64(bytes) !== text
  ) {
    throw new TerminalControlError()
  }
  return bytes
}

function parseIdentity(value: unknown): TerminalIdentity {
  const obj = requireObject(value)
  rejectUnknownAndRequireFields(obj, IDENTITY_FIELDS)
  const daemonInstanceId = requireCanonicalUuid(obj.daemonInstanceId)
  const terminalId = requireCanonicalUuid(obj.terminalId)
  return Object.freeze({ daemonInstanceId, terminalId })
}

function optionalIdentity(value: unknown): TerminalIdentity | null {
  if (value === null) {
    return null
  }
  return parseIdentity(value)
}

function parseGrant(value: unknown): WriterGrant {
  const obj = requireObject(value)
  rejectUnknownAndRequireFields(obj, GRANT_FIELDS)
  const epoch = requireCanonicalUuid(obj.epoch)
  const token = requireCanonicalUuid(obj.token)
  return Object.freeze({ epoch, token })
}

function optionalGrant(value: unknown): WriterGrant | null {
  if (value === null) {
    return null
  }
  return parseGrant(value)
}

function parseRecovery(value: unknown): Recovery {
  const obj = requireObject(value)
  rejectUnknownAndRequireFields(obj, RECOVERY_FIELDS)
  const previous = parseGrant(obj.previous)
  const seq = requireNonNegativeSafeInteger(obj.seq)
  const digest = optionalDigest(obj.digest)
  if ((seq === 0) !== (digest === null)) {
    throw new TerminalControlError()
  }
  return Object.freeze({ previous, seq, digest })
}

function optionalRecovery(value: unknown): Recovery | null {
  if (value === null) {
    return null
  }
  return parseRecovery(value)
}

function parseWriterState(value: unknown): WriterState {
  const obj = requireObject(value)
  rejectUnknownAndRequireFields(obj, WRITER_STATE_FIELDS)
  const frozen = requireBoolean(obj.frozen)
  const writerEpoch = optionalCanonicalUuid(obj.writerEpoch)
  if (frozen && writerEpoch !== null) {
    throw new TerminalControlError()
  }
  const lastWrittenSeq = requireNonNegativeSafeInteger(obj.lastWrittenSeq)
  const lastWrittenDigest = optionalDigest(obj.lastWrittenDigest)
  if ((lastWrittenSeq === 0) !== (lastWrittenDigest === null)) {
    throw new TerminalControlError()
  }

  const lastResolvedSeq = requireNonNegativeSafeInteger(obj.lastResolvedSeq)
  const lastResolvedDigest = optionalDigest(obj.lastResolvedDigest)
  if ((lastResolvedSeq === 0) !== (lastResolvedDigest === null)) {
    throw new TerminalControlError()
  }

  let lastResolvedOutcome: OperationOutcome | null = null
  if (obj.lastResolvedOutcome !== null) {
    if (
      typeof obj.lastResolvedOutcome !== 'string' ||
      !OPERATION_OUTCOMES.has(obj.lastResolvedOutcome as OperationOutcome)
    ) {
      throw new TerminalControlError()
    }
    lastResolvedOutcome = obj.lastResolvedOutcome as OperationOutcome
  }
  if ((lastResolvedSeq === 0) !== (lastResolvedOutcome === null)) {
    throw new TerminalControlError()
  }

  if (lastWrittenSeq > lastResolvedSeq) {
    throw new TerminalControlError()
  }

  const pendingSeq = requireNonNegativeSafeInteger(obj.pendingSeq)
  const pendingDigest = optionalDigest(obj.pendingDigest)
  if ((pendingSeq === 0) !== (pendingDigest === null)) {
    throw new TerminalControlError()
  }
  if (pendingSeq > 0 && pendingSeq !== lastResolvedSeq + 1) {
    throw new TerminalControlError()
  }

  return Object.freeze({
    writerEpoch,
    lastWrittenSeq,
    lastWrittenDigest,
    lastResolvedSeq,
    lastResolvedDigest,
    lastResolvedOutcome,
    pendingSeq,
    pendingDigest,
    frozen,
  })
}

function parseControlResult(value: unknown): ControlResult {
  const obj = requireObject(value)
  rejectUnknownAndRequireFields(obj, CONTROL_RESULT_FIELDS)
  if (
    typeof obj.status !== 'string' ||
    !CONTROL_STATUSES.has(obj.status as ControlResultStatus)
  ) {
    throw new TerminalControlError()
  }
  const status = obj.status as ControlResultStatus
  const grant = optionalGrant(obj.grant)
  if ((status === 'GRANTED') !== (grant !== null)) {
    throw new TerminalControlError()
  }

  let reason: ControlRejectReason | null = null
  if (obj.reason !== null) {
    if (
      typeof obj.reason !== 'string' ||
      !CONTROL_REJECT_REASONS.has(obj.reason as ControlRejectReason)
    ) {
      throw new TerminalControlError()
    }
    reason = obj.reason as ControlRejectReason
  }
  if ((status === 'REJECTED') !== (reason !== null)) {
    throw new TerminalControlError()
  }

  let recovered: OperationOutcome | null = null
  if (obj.recovered !== null) {
    if (
      typeof obj.recovered !== 'string' ||
      !OPERATION_OUTCOMES.has(obj.recovered as OperationOutcome)
    ) {
      throw new TerminalControlError()
    }
    recovered = obj.recovered as OperationOutcome
  }
  if (recovered !== null && status !== 'GRANTED') {
    throw new TerminalControlError()
  }

  return Object.freeze({ status, grant, recovered, reason })
}

function optionalControlResult(value: unknown): ControlResult | null {
  if (value === null) {
    return null
  }
  return parseControlResult(value)
}

function parseAdmissionResult(value: unknown): AdmissionResult {
  const obj = requireObject(value)
  rejectUnknownAndRequireFields(obj, ADMISSION_RESULT_FIELDS)
  if (
    typeof obj.kind !== 'string' ||
    !ADMISSION_KINDS.has(obj.kind as AdmissionKind)
  ) {
    throw new TerminalControlError()
  }
  const kind = obj.kind as AdmissionKind
  const seq = requirePositiveSafeInteger(obj.seq)

  let outcome: OperationOutcome | null = null
  if (obj.outcome !== null) {
    if (
      typeof obj.outcome !== 'string' ||
      !OPERATION_OUTCOMES.has(obj.outcome as OperationOutcome)
    ) {
      throw new TerminalControlError()
    }
    outcome = obj.outcome as OperationOutcome
  }
  if ((kind === 'CONFIRMED') !== (outcome !== null)) {
    throw new TerminalControlError()
  }

  let reason: AdmissionRejectReason | null = null
  if (obj.reason !== null) {
    if (
      typeof obj.reason !== 'string' ||
      !ADMISSION_REJECT_REASONS.has(obj.reason as AdmissionRejectReason)
    ) {
      throw new TerminalControlError()
    }
    reason = obj.reason as AdmissionRejectReason
  }
  if ((kind === 'REJECTED') !== (reason !== null)) {
    throw new TerminalControlError()
  }

  const digest = optionalDigest(obj.digest)
  const isInvalidReject = kind === 'REJECTED' && reason === 'INVALID'
  if (!isInvalidReject && digest === null) {
    throw new TerminalControlError()
  }

  return Object.freeze({ kind, seq, digest, outcome, reason })
}

function parseCommandBytes(value: unknown): Uint8Array {
  if (value instanceof Uint8Array) {
    if (value.length < MIN_INPUT_BYTES || value.length > MAX_INPUT_BYTES) {
      throw new TerminalControlError()
    }
    return new Uint8Array(value)
  }
  if (typeof value === 'string') {
    return base64ToUint8Array(value)
  }
  throw new TerminalControlError()
}

/**
 * 校验并返回结构化、只读且防御性深拷贝的 TerminalCommand 模型。
 */
export function validateTerminalCommand(value: unknown): TerminalCommand {
  const root = requireObject(value)
  rejectUnknownAndRequireFields(root, COMMAND_ROOT_FIELDS)

  if (root.version !== TERMINAL_CONTROL_VERSION) {
    throw new TerminalControlError()
  }
  const requestId = requireCanonicalUuid(root.requestId)
  const environmentId = requireCanonicalUuid(root.environmentId)
  const viewerId = requireCanonicalUuid(root.viewerId)

  if (
    typeof root.type !== 'string' ||
    !COMMAND_TYPES.has(root.type as TerminalCommandType)
  ) {
    throw new TerminalControlError()
  }
  const type = root.type as TerminalCommandType
  const payloadObj = requireObject(root.payload)

  let payload: TerminalCommandPayload
  switch (type) {
    case 'OPEN': {
      rejectUnknownAndRequireFields(payloadObj, OPEN_PAYLOAD_FIELDS)
      const expectedExited = optionalIdentity(payloadObj.expectedExited)
      payload = Object.freeze({ expectedExited })
      break
    }
    case 'ATTACH': {
      rejectUnknownAndRequireFields(payloadObj, ATTACH_PAYLOAD_FIELDS)
      const identity = parseIdentity(payloadObj.identity)
      payload = Object.freeze({ identity })
      break
    }
    case 'DETACH': {
      rejectUnknownAndRequireFields(payloadObj, DETACH_PAYLOAD_FIELDS)
      const identity = parseIdentity(payloadObj.identity)
      const streamId = requireCanonicalUuid(payloadObj.streamId)
      payload = Object.freeze({ identity, streamId })
      break
    }
    case 'CLAIM': {
      rejectUnknownAndRequireFields(payloadObj, CLAIM_PAYLOAD_FIELDS)
      const identity = parseIdentity(payloadObj.identity)
      const streamId = requireCanonicalUuid(payloadObj.streamId)
      const recovery = optionalRecovery(payloadObj.recovery)
      payload = Object.freeze({ identity, streamId, recovery })
      break
    }
    case 'TAKEOVER': {
      rejectUnknownAndRequireFields(payloadObj, TAKEOVER_PAYLOAD_FIELDS)
      const identity = parseIdentity(payloadObj.identity)
      const streamId = requireCanonicalUuid(payloadObj.streamId)
      const expectedWriterEpoch = optionalCanonicalUuid(
        payloadObj.expectedWriterEpoch,
      )
      payload = Object.freeze({ identity, streamId, expectedWriterEpoch })
      break
    }
    case 'RELEASE': {
      rejectUnknownAndRequireFields(payloadObj, RELEASE_PAYLOAD_FIELDS)
      const identity = parseIdentity(payloadObj.identity)
      const streamId = requireCanonicalUuid(payloadObj.streamId)
      const grant = parseGrant(payloadObj.grant)
      payload = Object.freeze({ identity, streamId, grant })
      break
    }
    case 'INPUT': {
      rejectUnknownAndRequireFields(payloadObj, INPUT_PAYLOAD_FIELDS)
      const identity = parseIdentity(payloadObj.identity)
      const streamId = requireCanonicalUuid(payloadObj.streamId)
      const grant = parseGrant(payloadObj.grant)
      const seq = requirePositiveSafeInteger(payloadObj.seq)
      const inputModeRevision = requirePositiveSafeInteger(
        payloadObj.inputModeRevision,
      )
      const bytes = parseCommandBytes(payloadObj.bytes)
      payload = Object.freeze({
        identity,
        streamId,
        grant,
        seq,
        inputModeRevision,
        bytes,
      })
      break
    }
    case 'RESIZE': {
      rejectUnknownAndRequireFields(payloadObj, RESIZE_PAYLOAD_FIELDS)
      const identity = parseIdentity(payloadObj.identity)
      const streamId = requireCanonicalUuid(payloadObj.streamId)
      const grant = parseGrant(payloadObj.grant)
      const seq = requirePositiveSafeInteger(payloadObj.seq)
      const cols = requireSafeInteger(payloadObj.cols, MIN_COLUMNS, MAX_COLUMNS)
      const rows = requireSafeInteger(payloadObj.rows, MIN_ROWS, MAX_ROWS)
      payload = Object.freeze({ identity, streamId, grant, seq, cols, rows })
      break
    }
    case 'VIEW_APPLIED': {
      rejectUnknownAndRequireFields(payloadObj, VIEW_APPLIED_PAYLOAD_FIELDS)
      const identity = parseIdentity(payloadObj.identity)
      const streamId = requireCanonicalUuid(payloadObj.streamId)
      const version = requirePositiveSafeInteger(payloadObj.version)
      payload = Object.freeze({ identity, streamId, version })
      break
    }
    case 'KEEPALIVE': {
      rejectUnknownAndRequireFields(payloadObj, KEEPALIVE_PAYLOAD_FIELDS)
      const identity = parseIdentity(payloadObj.identity)
      const streamId = requireCanonicalUuid(payloadObj.streamId)
      const grant = optionalGrant(payloadObj.grant)
      payload = Object.freeze({ identity, streamId, grant })
      break
    }
    case 'CLOSE': {
      rejectUnknownAndRequireFields(payloadObj, CLOSE_PAYLOAD_FIELDS)
      const identity = parseIdentity(payloadObj.identity)
      const expectedWriterEpoch = optionalCanonicalUuid(
        payloadObj.expectedWriterEpoch,
      )
      payload = Object.freeze({ identity, expectedWriterEpoch })
      break
    }
  }

  return Object.freeze({
    version: 1,
    requestId,
    environmentId,
    viewerId,
    type,
    payload,
  }) as TerminalCommand
}

/**
 * 校验并编码命令为 canonical JSON 文本。
 *
 * 字段顺序与 Java TerminalControlCodec 严格一致，bytes 转换为 canonical padded Base64。
 * 不改变调用方原 bytes 结构，严格施加 MAX_CONTROL_MESSAGE_BYTES 长度预算。
 */
export function encodeTerminalCommand(command: unknown): string {
  const validated = validateTerminalCommand(command)

  let payloadNode: Record<string, unknown>
  if (validated.type === 'INPUT') {
    payloadNode = {
      identity: validated.payload.identity,
      streamId: validated.payload.streamId,
      grant: validated.payload.grant,
      seq: validated.payload.seq,
      inputModeRevision: validated.payload.inputModeRevision,
      bytes: uint8ArrayToBase64(validated.payload.bytes),
    }
  } else {
    payloadNode = validated.payload as unknown as Record<string, unknown>
  }

  const root = {
    version: 1,
    requestId: validated.requestId,
    environmentId: validated.environmentId,
    viewerId: validated.viewerId,
    type: validated.type,
    payload: payloadNode,
  }

  const json = JSON.stringify(root)
  const utf8Len = measureUtf8BytesAndValidate(json)
  if (utf8Len > MAX_CONTROL_MESSAGE_BYTES) {
    throw new TerminalControlError()
  }
  return json
}

/**
 * 解码由上层 app-events 解析完毕的 object 为不可变且 owned frozen 的 TerminalEvent 模型。
 */
export function decodeTerminalEvent(value: unknown): TerminalEvent {
  const root = requireObject(value)
  rejectUnknownAndRequireFields(root, EVENT_ROOT_FIELDS)

  if (root.version !== TERMINAL_CONTROL_VERSION) {
    throw new TerminalControlError()
  }

  const requestId = optionalCanonicalUuid(root.requestId)
  const environmentId = requireCanonicalUuid(root.environmentId)
  const viewerId = requireCanonicalUuid(root.viewerId)

  if (
    typeof root.type !== 'string' ||
    !EVENT_TYPES.has(root.type as TerminalEventType)
  ) {
    throw new TerminalControlError()
  }
  const type = root.type as TerminalEventType
  const identity = optionalIdentity(root.identity)
  if (type !== 'ERROR' && identity === null) {
    throw new TerminalControlError()
  }

  const payloadObj = requireObject(root.payload)
  let payload: TerminalEventPayload

  switch (type) {
    case 'ATTACHED': {
      rejectUnknownAndRequireFields(payloadObj, ATTACHED_PAYLOAD_FIELDS)
      const streamId = requireCanonicalUuid(payloadObj.streamId)
      if (typeof payloadObj.executable !== 'string') {
        throw new TerminalControlError()
      }
      const executable = payloadObj.executable
      if (
        executable.length === 0 ||
        executable.length > MAX_EXECUTABLE_UTF8_BYTES ||
        measureUtf8BytesAndValidate(executable) > MAX_EXECUTABLE_UTF8_BYTES
      ) {
        throw new TerminalControlError()
      }

      if (
        typeof payloadObj.status !== 'string' ||
        !TERMINAL_STATUSES.has(payloadObj.status as TerminalStatus)
      ) {
        throw new TerminalControlError()
      }
      const status = payloadObj.status as TerminalStatus

      let exitCode: number | null = null
      if (status === 'RUNNING') {
        if (payloadObj.exitCode !== null) {
          throw new TerminalControlError()
        }
      } else if (status === 'EXITED') {
        if (payloadObj.exitCode === null) {
          throw new TerminalControlError()
        }
        exitCode = requireInt32(payloadObj.exitCode)
      } else {
        // FAILED: exitCode nullable
        if (payloadObj.exitCode !== null) {
          exitCode = requireInt32(payloadObj.exitCode)
        }
      }

      const inputModeRevision = requirePositiveSafeInteger(
        payloadObj.inputModeRevision,
      )
      const writer = parseWriterState(payloadObj.writer)
      payload = Object.freeze({
        streamId,
        executable,
        status,
        exitCode,
        inputModeRevision,
        writer,
      })
      break
    }
    case 'WRITER_CHANGED': {
      rejectUnknownAndRequireFields(payloadObj, WRITER_CHANGED_PAYLOAD_FIELDS)
      const writer = parseWriterState(payloadObj.writer)
      const result = optionalControlResult(payloadObj.result)
      payload = Object.freeze({ writer, result })
      break
    }
    case 'OP_ACK': {
      rejectUnknownAndRequireFields(payloadObj, OP_ACK_PAYLOAD_FIELDS)
      const writerEpoch = requireCanonicalUuid(payloadObj.writerEpoch)
      const result = parseAdmissionResult(payloadObj.result)
      let code: ErrorCode | null = null
      if (payloadObj.code !== null) {
        if (
          typeof payloadObj.code !== 'string' ||
          !ERROR_CODES.has(payloadObj.code as ErrorCode)
        ) {
          throw new TerminalControlError()
        }
        code = payloadObj.code as ErrorCode
      }
      payload = Object.freeze({ writerEpoch, result, code })
      break
    }
    case 'VIEW_UPDATE': {
      rejectUnknownAndRequireFields(payloadObj, VIEW_UPDATE_PAYLOAD_FIELDS)
      let update: TerminalViewUpdate
      try {
        update = decodeTerminalViewUpdate(payloadObj.update)
      } catch (err) {
        if (err instanceof TerminalViewError) {
          throw new TerminalControlError()
        }
        throw err
      }
      if (identity === null || update.terminalId !== identity.terminalId) {
        throw new TerminalControlError()
      }
      payload = Object.freeze({ update })
      break
    }
    case 'EXITED': {
      rejectUnknownAndRequireFields(payloadObj, EXITED_PAYLOAD_FIELDS)
      if (
        typeof payloadObj.status !== 'string' ||
        !TERMINAL_STATUSES.has(payloadObj.status as TerminalStatus)
      ) {
        throw new TerminalControlError()
      }
      const status = payloadObj.status as TerminalStatus
      if (status === 'RUNNING') {
        throw new TerminalControlError()
      }
      let exitCode: number | null = null
      if (status === 'EXITED') {
        if (payloadObj.exitCode === null) {
          throw new TerminalControlError()
        }
        exitCode = requireInt32(payloadObj.exitCode)
      } else {
        if (payloadObj.exitCode !== null) {
          exitCode = requireInt32(payloadObj.exitCode)
        }
      }
      payload = Object.freeze({ status, exitCode })
      break
    }
    case 'ERROR': {
      rejectUnknownAndRequireFields(payloadObj, ERROR_PAYLOAD_FIELDS)
      if (
        typeof payloadObj.code !== 'string' ||
        !ERROR_CODES.has(payloadObj.code as ErrorCode)
      ) {
        throw new TerminalControlError()
      }
      const code = payloadObj.code as ErrorCode
      if (
        typeof payloadObj.disposition !== 'string' ||
        !ERROR_DISPOSITIONS.has(payloadObj.disposition as ErrorDisposition)
      ) {
        throw new TerminalControlError()
      }
      const disposition = payloadObj.disposition as ErrorDisposition
      payload = Object.freeze({ code, disposition })
      break
    }
  }

  return Object.freeze({
    version: 1,
    requestId,
    environmentId,
    viewerId,
    identity,
    type,
    payload,
  }) as TerminalEvent
}
