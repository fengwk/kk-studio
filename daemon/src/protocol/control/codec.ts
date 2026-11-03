import { fromBinary, toBinary } from '@bufbuild/protobuf'

import {
  Architecture,
  EnvelopeSchema,
  IdeAction,
  IdeOpenMode,
  IdeViewState,
  MessageType,
  OperatingSystem,
  TicketState,
  UpdatePhase,
  type CapabilityResult,
  type Content,
  type Envelope,
  type EnvironmentInfo,
  type Error as ProtocolErrorMessage,
  type ExpectedFile,
  type Heartbeat,
  type IdeCommand,
  type IdeLease,
  type IdeResult,
  type IdeViewSummary,
  type Invoke,
  type PresignedPut,
  type Ready,
  type ResourceContent,
  type ResourceUploadRequest,
  type ResourceUploadTicket,
  type RuntimeSnapshot,
  type Settings,
  type Started,
  type TemporaryResourcePolicy,
  type Update,
  type UpdateProgress,
  type UpdateResult,
  type UpgradeRequired,
} from '../../generated/control_pb.js'
import { frame, unframe } from './framing.js'
import {
  MAX_MESSAGE_BYTES,
  MAX_SAFE_BIGINT,
  parseJsonObject,
  parseJsonValue,
  requireLowerSha256,
  requireMediaType,
  requireSafeLong,
  requireText,
  requireUuid,
} from './runtime.js'

export const PROTOCOL_VERSION = 1
export const MAX_INVOCATION_ID_CHARS = 256
export const MAX_CALL_ID_CHARS = 256
export const MAX_ARGUMENTS_JSON_UTF8_BYTES = MAX_MESSAGE_BYTES
export const MAX_DETAILS_JSON_UTF8_BYTES = 1024 * 1024
export const MAX_JSON_CONTENT_UTF8_BYTES = 1024 * 1024
export const MAX_CONTENT_ITEMS = 64
export const MAX_MEDIA_TYPE_ASCII_BYTES = 255
export const MAX_NAME_UTF8_BYTES = 512
export const MAX_PREVIEW_UTF8_BYTES = 16 * 1024
export const MAX_FAILURE_MESSAGE_CHARS = 512
export const MAX_DISPLAY_NAME_CHARS = 256
export const MAX_ERROR_MESSAGE_CHARS = 4096
export const MAX_IDE_PATH_CHARS = 4096
export const MAX_LISTED_IDE_VIEWS = 64
export const MAX_IDE_LEASE_BINDINGS = 64
export const MAX_NOTE_CHARS = 512
export const MAX_DAEMON_VERSION_CHARS = 64
export const MAX_UPDATE_MESSAGE_CHARS = 500
export const MAX_PRESIGNED_URL_CHARS = 4096
export const MAX_PRESIGNED_HEADERS = 16
export const MAX_PRESIGNED_HEADER_CHARS = 1024
export const MAX_POSITIVE_BIGINT = 9223372036854775807n

const CAPABILITY_ID_PATTERN = /^[a-z0-9]+(?:[.-][a-z0-9]+)*$/
const DAEMON_VERSION_PATTERN = /^[0-9A-Za-z._+-]{1,64}$/
const SHA256_ANY_PATTERN = /^[0-9a-fA-F]{64}$/
const IDE_CODE_PATTERN = /^[A-Z][A-Z0-9_]{0,63}$/
const CONTROL_CHARACTER_PATTERN = /[\u0000-\u001f\u007f-\u009f]/
const ARTIFACT_URL_PATTERN =
  /^https:\/\/github\.com\/fengwk\/kk-studio\/releases\/download\/v([0-9A-Za-z._+-]{1,64})\/kk-studio-daemon-v\1-((linux|macos)-(amd64|arm64)|windows-amd64)\.tar\.gz$/

const BOUND = new Set<MessageType>([
  MessageType.SETTINGS,
  MessageType.READY,
  MessageType.HEARTBEAT,
  MessageType.RUNTIME_STATUS,
  MessageType.INVOKE,
  MessageType.STARTED,
  MessageType.PROGRESS,
  MessageType.COMPLETED,
  MessageType.FAILED,
  MessageType.CANCEL,
  MessageType.CANCELLED,
  MessageType.RECOVER,
  MessageType.RECOVER_NOT_FOUND,
  MessageType.EXECUTION_EXITED,
  MessageType.RESOURCE_UPLOAD_REQUEST,
  MessageType.RESOURCE_UPLOAD_TICKET,
  MessageType.RESOURCE_UPLOAD_COMMIT,
  MessageType.TEMPORARY_RESOURCE_POLICY,
  MessageType.UPDATE,
  MessageType.UPDATE_RESULT,
  MessageType.IDE_COMMAND,
  MessageType.IDE_RESULT,
  MessageType.IDE_LEASE,
  MessageType.ERROR,
])

const INVOCATION = new Set<MessageType>([
  MessageType.INVOKE,
  MessageType.STARTED,
  MessageType.PROGRESS,
  MessageType.COMPLETED,
  MessageType.FAILED,
  MessageType.CANCEL,
  MessageType.CANCELLED,
  MessageType.RECOVER,
  MessageType.RECOVER_NOT_FOUND,
  MessageType.EXECUTION_EXITED,
  MessageType.RESOURCE_UPLOAD_REQUEST,
  MessageType.RESOURCE_UPLOAD_TICKET,
  MessageType.RESOURCE_UPLOAD_COMMIT,
  MessageType.IDE_COMMAND,
  MessageType.IDE_RESULT,
])

const NO_INVOCATION = new Set<MessageType>([
  MessageType.SETTINGS,
  MessageType.READY,
  MessageType.HEARTBEAT,
  MessageType.RUNTIME_STATUS,
  MessageType.TEMPORARY_RESOURCE_POLICY,
  MessageType.UPDATE,
  MessageType.UPDATE_RESULT,
  MessageType.IDE_LEASE,
])

interface UnknownCarrier {
  $unknown?: readonly unknown[]
}

/** Decodes one bare protobuf message and applies all application-level checks. */
export function decodeBinary(bytes: Uint8Array): Envelope {
  requireMessageLength(bytes)
  return validate(fromBinary(EnvelopeSchema, bytes, { readUnknownFields: true }))
}

/** Validates and encodes one bare protobuf message. */
export function encodeBinary(envelope: Envelope): Uint8Array {
  validate(envelope)
  const bytes = toBinary(EnvelopeSchema, envelope)
  requireMessageLength(bytes)
  return bytes
}

/** Decodes one four-byte-length-prefixed frame. */
export function decodeFrame(framed: Uint8Array): Envelope {
  return decodeBinary(unframe(framed))
}

/** Validates and encodes one four-byte-length-prefixed frame. */
export function encodeFrame(envelope: Envelope): Uint8Array {
  return frame(encodeBinary(envelope))
}

/** Validates the HTTP 426 upgrade payload. It is not a control-stream envelope. */
export function validateUpgrade(upgrade: UpgradeRequired): UpgradeRequired {
  assertNoUnknown(upgrade, 'upgradeRequired')
  requirePositiveInt32(
    upgrade.targetProtocolVersion,
    'upgradeRequired.targetProtocolVersion',
  )
  const hasUrl = upgrade.artifactUrl !== undefined
  const hasSha = upgrade.artifactSha256 !== undefined
  if (hasUrl !== hasSha) {
    throw new Error('upgradeRequired artifactUrl and artifactSha256 must both be present or both absent')
  }
  if (hasUrl) {
    const url = requireText(upgrade.artifactUrl, 'upgradeRequired.artifactUrl', { nonBlank: true })
    if (!ARTIFACT_URL_PATTERN.test(url)) {
      throw new Error('upgradeRequired.artifactUrl must be the official platform release archive')
    }
    const sha = requireText(upgrade.artifactSha256, 'upgradeRequired.artifactSha256', { nonBlank: true })
    if (!SHA256_ANY_PATTERN.test(sha)) {
      throw new Error('upgradeRequired.artifactSha256 has invalid syntax')
    }
  }
  return upgrade
}

export function validate(envelope: Envelope): Envelope {
  assertNoUnknown(envelope, 'envelope')
  const messageType = envelope.messageType
  if (!messageType) {
    throw new Error('messageType must be a known non-unspecified value')
  }
  validateScope(envelope, messageType)
  switch (messageType) {
    case MessageType.SETTINGS:
      requireCase(envelope, 'settings', 'SETTINGS')
      validateSettings(envelope.payload.value as Settings)
      break
    case MessageType.READY:
      requireCase(envelope, 'ready', 'READY')
      validateReady(envelope.payload.value as Ready)
      break
    case MessageType.HEARTBEAT:
      requireCase(envelope, 'heartbeat', 'HEARTBEAT')
      validateHeartbeat(envelope.payload.value as Heartbeat)
      break
    case MessageType.RUNTIME_STATUS:
      requireCase(envelope, 'runtimeStatus', 'RUNTIME_STATUS')
      validateRuntimeMessage(envelope.payload.value as { runtimeStatus?: RuntimeSnapshot })
      break
    case MessageType.INVOKE:
      requireCase(envelope, 'invoke', 'INVOKE')
      validateInvoke(envelope.payload.value as Invoke)
      break
    case MessageType.STARTED:
      requireCase(envelope, 'started', 'STARTED')
      validateStarted(envelope.payload.value as Started)
      break
    case MessageType.PROGRESS:
      requireCase(envelope, 'progress', 'PROGRESS')
      validateResult((envelope.payload.value as { result?: CapabilityResult }).result, 'progress', false)
      break
    case MessageType.COMPLETED:
      requireCase(envelope, 'completed', 'COMPLETED')
      validateResult((envelope.payload.value as { result?: CapabilityResult }).result, 'completed', true)
      break
    case MessageType.FAILED:
      requireCase(envelope, 'failed', 'FAILED')
      requireMessage((envelope.payload.value as { message?: string }).message, 'failed.message')
      break
    case MessageType.CANCEL:
      requireCase(envelope, 'cancel', 'CANCEL')
      break
    case MessageType.CANCELLED:
      requireCase(envelope, 'cancelled', 'CANCELLED')
      requireMessage((envelope.payload.value as { reason?: string }).reason, 'cancelled.reason')
      break
    case MessageType.RECOVER:
      requireCase(envelope, 'recover', 'RECOVER')
      break
    case MessageType.RECOVER_NOT_FOUND:
      requireCase(envelope, 'recoverNotFound', 'RECOVER_NOT_FOUND')
      break
    case MessageType.EXECUTION_EXITED:
      requireCase(envelope, 'executionExited', 'EXECUTION_EXITED')
      break
    case MessageType.RESOURCE_UPLOAD_REQUEST:
      requireCase(envelope, 'resourceUploadRequest', 'RESOURCE_UPLOAD_REQUEST')
      validateUploadRequest(envelope.payload.value as ResourceUploadRequest)
      break
    case MessageType.RESOURCE_UPLOAD_TICKET:
      requireCase(envelope, 'resourceUploadTicket', 'RESOURCE_UPLOAD_TICKET')
      validateUploadTicket(envelope.payload.value as ResourceUploadTicket)
      break
    case MessageType.RESOURCE_UPLOAD_COMMIT:
      requireCase(envelope, 'resourceUploadCommit', 'RESOURCE_UPLOAD_COMMIT')
      validateUploadCommit(envelope.payload.value as { transferId?: string; uploadId?: string })
      break
    case MessageType.TEMPORARY_RESOURCE_POLICY:
      requireCase(envelope, 'temporaryResourcePolicy', 'TEMPORARY_RESOURCE_POLICY')
      validatePolicy(envelope.payload.value as TemporaryResourcePolicy)
      break
    case MessageType.UPDATE:
      requireCase(envelope, 'update', 'UPDATE')
      validateUpdate(envelope.payload.value as Update)
      break
    case MessageType.UPDATE_RESULT:
      requireCase(envelope, 'updateResult', 'UPDATE_RESULT')
      validateUpdateResult(envelope.payload.value as UpdateResult)
      break
    case MessageType.IDE_COMMAND:
      requireCase(envelope, 'ideCommand', 'IDE_COMMAND')
      validateIdeCommand(envelope.payload.value as IdeCommand)
      break
    case MessageType.IDE_RESULT:
      requireCase(envelope, 'ideResult', 'IDE_RESULT')
      validateIdeResult(envelope.payload.value as IdeResult)
      break
    case MessageType.IDE_LEASE:
      requireCase(envelope, 'ideLease', 'IDE_LEASE')
      validateIdeLease(envelope.payload.value as IdeLease)
      break
    case MessageType.ERROR:
      requireCase(envelope, 'error', 'ERROR')
      validateError(envelope.payload.value as ProtocolErrorMessage)
      break
    default:
      throw new Error('messageType must be a known non-unspecified value')
  }
  return envelope
}

function validateScope(envelope: Envelope, messageType: MessageType): void {
  if (BOUND.has(messageType)) {
    requireUuid(envelope.environmentId, 'environmentId')
  } else if (envelope.environmentId !== undefined) {
    requireUuid(envelope.environmentId, 'environmentId')
  }
  if (INVOCATION.has(messageType)) {
    requireText(envelope.invocationId, 'invocationId', { nonBlank: true, maxChars: MAX_INVOCATION_ID_CHARS })
  } else if (NO_INVOCATION.has(messageType) && envelope.invocationId !== undefined) {
    throw new Error(`${MessageType[messageType]} must not declare invocationId`)
  } else if (envelope.invocationId !== undefined) {
    requireText(envelope.invocationId, 'invocationId', { nonBlank: true, maxChars: MAX_INVOCATION_ID_CHARS })
  }
}

function validateSettings(settings: Settings): void {
  assertNoUnknown(settings, 'settings')
  if (settings.resourceBudgets === undefined) {
    throw new Error('settings.resourceBudgets is required')
  }
  assertNoUnknown(settings.resourceBudgets, 'settings.resourceBudgets')
  requireSafeLong(settings.resourceBudgets.maxResourceBytes, 'settings.resourceBudgets.maxResourceBytes', 1n, MAX_SAFE_BIGINT)
  requirePositive(settings.resourceBudgets.temporaryResourceTtlSeconds, 'settings.resourceBudgets.temporaryResourceTtlSeconds')
  requirePositive(
    settings.resourceBudgets.temporaryResourceCleanupIntervalSeconds,
    'settings.resourceBudgets.temporaryResourceCleanupIntervalSeconds',
  )
  if (settings.displayName !== undefined) {
    requireText(settings.displayName, 'settings.displayName', { nonBlank: true, maxChars: MAX_DISPLAY_NAME_CHARS })
  }
}

function validateReady(ready: Ready): void {
  assertNoUnknown(ready, 'ready')
  const daemonVersion = requireDaemonVersion(ready.daemonVersion, 'ready.daemonVersion')
  if (ready.environment === undefined) {
    throw new Error('ready.environment is required')
  }
  validateEnvironment(ready.environment)
  if (ready.runtimeStatus === undefined) {
    throw new Error('ready.runtimeStatus is required')
  }
  const snapshot = validateSnapshot(ready.runtimeStatus, 'ready.runtimeStatus')
  if (snapshot.status?.daemonVersion !== daemonVersion) {
    throw new Error('ready.runtimeStatus.status.daemonVersion must match ready.daemonVersion')
  }
}

function validateEnvironment(info: EnvironmentInfo): void {
  assertNoUnknown(info, 'ready.environment')
  if (!info.operatingSystem) {
    throw new Error('ready.environment.operatingSystem is required')
  }
  if (!info.architecture) {
    throw new Error('ready.environment.architecture is required')
  }
  const zone = requireText(info.timeZone, 'ready.environment.timeZone', { nonBlank: true, maxChars: 128 })
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: zone })
  } catch {
    throw new Error('ready.environment.timeZone must be a valid ZoneId')
  }
  requireSingleLine(info.userName, 'ready.environment.userName')
  requireSingleLine(info.homeDirectory, 'ready.environment.homeDirectory')
  requireSingleLine(info.note, 'ready.environment.note', MAX_NOTE_CHARS)
  requireSingleLine(info.tempDirectory, 'ready.environment.tempDirectory')
}

function validateHeartbeat(heartbeat: Heartbeat): void {
  assertNoUnknown(heartbeat, 'heartbeat')
  if (heartbeat.runtimeStatus === undefined) {
    throw new Error('heartbeat.runtimeStatus is required')
  }
  validateSnapshot(heartbeat.runtimeStatus, 'heartbeat.runtimeStatus')
  if (heartbeat.ideLeaseRound !== undefined) {
    requirePositive(heartbeat.ideLeaseRound, 'heartbeat.ideLeaseRound')
  }
}

function validateRuntimeMessage(message: { runtimeStatus?: RuntimeSnapshot }): void {
  assertNoUnknown(message as UnknownCarrier, 'runtimeStatus')
  if (message.runtimeStatus === undefined) {
    throw new Error('runtimeStatus.runtimeStatus is required')
  }
  validateSnapshot(message.runtimeStatus, 'runtimeStatus')
}

function validateSnapshot(snapshot: RuntimeSnapshot, path: string): RuntimeSnapshot {
  assertNoUnknown(snapshot, path)
  requirePositive(snapshot.revision, `${path}.revision`)
  if (snapshot.status === undefined) {
    throw new Error(`${path}.status is required`)
  }
  assertNoUnknown(snapshot.status, `${path}.status`)
  requireDaemonVersion(snapshot.status.daemonVersion, `${path}.status.daemonVersion`)
  if (snapshot.status.update !== undefined) {
    validateUpdateProgress(snapshot.status.update, `${path}.status.update`)
  }
  return snapshot
}

function validateUpdateProgress(update: UpdateProgress, path: string): void {
  assertNoUnknown(update, path)
  requireDaemonVersion(update.targetVersion, `${path}.targetVersion`)
  requireSafeLong(update.downloadedBytes, `${path}.downloadedBytes`, 0n, MAX_SAFE_BIGINT)
  if (update.totalBytes !== undefined) {
    requireSafeLong(update.totalBytes, `${path}.totalBytes`, update.downloadedBytes ?? 0n, MAX_SAFE_BIGINT)
  }
}

function validateInvoke(invoke: Invoke): void {
  assertNoUnknown(invoke, 'envelope.invoke')
  const capabilityId = requireText(invoke.capabilityId, 'invoke.capabilityId', {
    nonBlank: true,
    maxChars: 128,
    pattern: CAPABILITY_ID_PATTERN,
  })
  parseJsonObject(invoke.argumentsJson, 'invoke.argumentsJson', MAX_ARGUMENTS_JSON_UTF8_BYTES)
  requireSafeLong(invoke.timeoutMillis, 'invoke.timeoutMillis', 0n, MAX_SAFE_BIGINT)
  if (invoke.expectedFile !== undefined) {
    if (capabilityId !== 'fs.edit') {
      throw new Error('invoke.expectedFile is only valid for fs.edit')
    }
    const expected: ExpectedFile = invoke.expectedFile
    assertNoUnknown(expected, 'invoke.expectedFile')
    requireText(expected.path, 'invoke.expectedFile.path', { nonBlank: true })
    requireLowerSha256(expected.sha256, 'invoke.expectedFile.sha256')
  }
}

function validateStarted(started: Started): void {
  assertNoUnknown(started, 'started')
  if (started.replayed === false) {
    throw new Error('started.replayed must be true when present')
  }
}

function validateResult(result: CapabilityResult | undefined, path: string, allowResources: boolean): void {
  if (result === undefined) {
    throw new Error(`${path}.result is required`)
  }
  assertNoUnknown(result, `${path}.result`)
  requireText(result.callId, `${path}.result.callId`, { nonBlank: true, maxChars: MAX_CALL_ID_CHARS })
  if (result.error === undefined) {
    throw new Error(`${path}.result.error is required`)
  }
  parseJsonObject(result.detailsJson, `${path}.result.detailsJson`, MAX_DETAILS_JSON_UTF8_BYTES)
  if (result.contents.length > MAX_CONTENT_ITEMS) {
    throw new Error(`${path}.result.contents must not exceed ${MAX_CONTENT_ITEMS} items`)
  }
  result.contents.forEach((content, index) => validateContent(content, `${path}.result.contents[${index}]`, allowResources))
}

function validateContent(content: Content, path: string, allowResources: boolean): void {
  assertNoUnknown(content, path)
  switch (content.body.case) {
    case 'text':
      assertNoUnknown(content.body.value, `${path}.text`)
      requireText(content.body.value.text, `${path}.text`, {})
      break
    case 'json':
      assertNoUnknown(content.body.value, `${path}.json`)
      parseJsonValue(content.body.value.json, `${path}.json`, MAX_JSON_CONTENT_UTF8_BYTES)
      break
    case 'resource':
      if (!allowResources) {
        throw new Error(`${path} must not declare a resource`)
      }
      assertNoUnknown(content.body.value, `${path}.resource`)
      validateResource(content.body.value, `${path}.resource`)
      break
    default:
      throw new Error(`${path} must declare exactly one content body`)
  }
}

function validateResource(resource: ResourceContent, path: string): void {
  requireUuid(resource.uploadId, `${path}.uploadId`)
  requireMediaType(resource.mediaType, `${path}.mediaType`, MAX_MEDIA_TYPE_ASCII_BYTES)
  if (resource.name !== undefined) {
    const name = requireText(resource.name, `${path}.name`, { nonBlank: true, maxBytes: MAX_NAME_UTF8_BYTES })
    if (CONTROL_CHARACTER_PATTERN.test(name)) {
      throw new Error(`${path}.name must not contain control characters`)
    }
  }
  requireSafeLong(resource.size, `${path}.size`, 0n, MAX_SAFE_BIGINT)
  requireLowerSha256(resource.sha256, `${path}.sha256`)
  if (resource.preview !== undefined) {
    requireText(resource.preview, `${path}.preview`, { maxBytes: MAX_PREVIEW_UTF8_BYTES })
  }
}

function validateUploadRequest(request: ResourceUploadRequest): void {
  assertNoUnknown(request, 'uploadRequest')
  requireUuid(request.transferId, 'uploadRequest.transferId')
  requireMediaType(request.mediaType, 'uploadRequest.mediaType', MAX_MEDIA_TYPE_ASCII_BYTES)
  if (request.name !== undefined) {
    requireText(request.name, 'uploadRequest.name', { nonBlank: true, maxBytes: MAX_NAME_UTF8_BYTES })
  }
  requireSafeLong(request.size, 'uploadRequest.size', 0n, MAX_SAFE_BIGINT)
  requireLowerSha256(request.sha256, 'uploadRequest.sha256')
}

function validateUploadTicket(ticket: ResourceUploadTicket): void {
  assertNoUnknown(ticket, 'uploadTicket')
  requireUuid(ticket.transferId, 'uploadTicket.transferId')
  if (ticket.state === TicketState.TICKET_PENDING) {
    requireUuid(ticket.uploadId, 'uploadTicket.uploadId')
    if (ticket.presignedPut === undefined || ticket.message !== undefined) {
      throw new Error('PENDING ticket requires uploadId and presignedPut only')
    }
    validatePresigned(ticket.presignedPut)
    return
  }
  if (ticket.state === TicketState.TICKET_READY) {
    requireUuid(ticket.uploadId, 'uploadTicket.uploadId')
    if (ticket.presignedPut !== undefined || ticket.message !== undefined) {
      throw new Error('READY ticket requires uploadId only')
    }
    return
  }
  if (ticket.state === TicketState.TICKET_FAILED) {
    if (ticket.uploadId !== undefined || ticket.presignedPut !== undefined) {
      throw new Error('FAILED ticket requires a message only')
    }
    requireText(ticket.message, 'uploadTicket.message', { nonBlank: true, maxChars: MAX_FAILURE_MESSAGE_CHARS })
    return
  }
  throw new Error('uploadTicket.state is required')
}

function validatePresigned(put: PresignedPut): void {
  assertNoUnknown(put, 'presignedPut')
  if (requireText(put.method, 'presignedPut.method', { nonBlank: true }) !== 'PUT') {
    throw new Error('presignedPut.method must be PUT')
  }
  requireText(put.url, 'presignedPut.url', { nonBlank: true, maxChars: MAX_PRESIGNED_URL_CHARS })
  const headers = put.headers
  if (Object.keys(headers).length > MAX_PRESIGNED_HEADERS) {
    throw new Error(`presignedPut.headers must not exceed ${MAX_PRESIGNED_HEADERS} entries`)
  }
  for (const [name, value] of Object.entries(headers)) {
    requireText(name, 'presignedPut.header', { nonBlank: true, maxChars: MAX_PRESIGNED_HEADER_CHARS })
    requireText(value, 'presignedPut.header', { nonBlank: true, maxChars: MAX_PRESIGNED_HEADER_CHARS })
  }
  const expires = requireText(put.expiresAt, 'presignedPut.expiresAt', { nonBlank: true })
  const parsed = new Date(expires)
  if (Number.isNaN(parsed.getTime()) || parsed.toISOString().replace('.000Z', 'Z') !== expires) {
    throw new Error('presignedPut.expiresAt must be a canonical ISO-8601 instant')
  }
}

function validateUploadCommit(commit: { transferId?: string; uploadId?: string }): void {
  assertNoUnknown(commit as UnknownCarrier, 'uploadCommit')
  requireUuid(commit.transferId, 'uploadCommit.transferId')
  requireUuid(commit.uploadId, 'uploadCommit.uploadId')
}

function validatePolicy(policy: TemporaryResourcePolicy): void {
  assertNoUnknown(policy, 'temporaryResourcePolicy')
  requirePositive(policy.temporaryResourceTtlSeconds, 'temporaryResourcePolicy.temporaryResourceTtlSeconds')
  requirePositive(
    policy.temporaryResourceCleanupIntervalSeconds,
    'temporaryResourcePolicy.temporaryResourceCleanupIntervalSeconds',
  )
}

function validateUpdate(update: Update): void {
  assertNoUnknown(update, 'update')
  requireUuid(update.operationId, 'update.operationId')
  requireDaemonVersion(update.targetVersion, 'update.targetVersion')
}

function validateUpdateResult(result: UpdateResult): void {
  assertNoUnknown(result, 'updateResult')
  requireText(result.operationId, 'updateResult.operationId', { nonBlank: true, maxChars: 256 })
  if (
    result.phase !== UpdatePhase.UPDATE_ACCEPTED &&
    result.phase !== UpdatePhase.UPDATE_PREPARED &&
    result.phase !== UpdatePhase.UPDATE_FAILED
  ) {
    throw new Error('updateResult.phase is required')
  }
  if (result.message !== undefined) {
    const message = requireText(result.message, 'updateResult.message', {
      nonBlank: true,
      maxChars: MAX_UPDATE_MESSAGE_CHARS,
    })
    if (CONTROL_CHARACTER_PATTERN.test(message)) {
      throw new Error('updateResult.message must not contain control characters')
    }
  }
}

function validateIdeCommand(command: IdeCommand): void {
  assertNoUnknown(command, 'ideCommand')
  switch (command.action) {
    case IdeAction.OPEN_VIEW:
      requireOpenIdentity(command)
      if (command.bindingEpoch !== undefined || command.discard !== undefined) {
        throw new Error('OPEN_VIEW must not declare bindingEpoch or discard')
      }
      if (command.mode !== IdeOpenMode.CREATE && command.mode !== IdeOpenMode.RESUME) {
        throw new Error('ideCommand.mode is required for OPEN_VIEW')
      }
      if (command.directory !== undefined) {
        requireIdePath(command.directory, 'ideCommand.directory')
      }
      return
    case IdeAction.CHANGE_DIRECTORY:
      requireBoundView(command, false)
      if (command.directory === undefined) {
        throw new Error('ideCommand.directory is required')
      }
      requireIdePath(command.directory, 'ideCommand.directory')
      rejectOpenFields(command)
      return
    case IdeAction.FOCUS_TERMINAL:
    case IdeAction.DETACH_VIEW:
      requireBoundView(command, false)
      if (command.directory !== undefined) {
        throw new Error('ideCommand.directory is not valid for this action')
      }
      rejectOpenFields(command)
      return
    case IdeAction.CLOSE_VIEW:
      requireBoundView(command, true)
      if (command.directory !== undefined) {
        throw new Error('CLOSE_VIEW must not declare directory')
      }
      rejectOpenFields(command)
      return
    case IdeAction.LIST_VIEWS:
      if (
        command.viewId !== undefined ||
        command.documentId !== undefined ||
        command.eventNodeId !== undefined ||
        command.connectionId !== undefined ||
        command.claimId !== undefined ||
        command.bindingEpoch !== undefined ||
        command.mode !== IdeOpenMode.IDE_OPEN_MODE_UNSPECIFIED ||
        command.directory !== undefined ||
        command.discard !== undefined
      ) {
        throw new Error('LIST_VIEWS must only declare action')
      }
      return
    default:
      throw new Error('ideCommand.action is required')
  }
}

function requireOpenIdentity(command: IdeCommand): void {
  requireUuid(command.viewId, 'ideCommand.viewId')
  requireUuid(command.documentId, 'ideCommand.documentId')
  requireUuid(command.eventNodeId, 'ideCommand.eventNodeId')
  requireUuid(command.connectionId, 'ideCommand.connectionId')
  requireUuid(command.claimId, 'ideCommand.claimId')
}

function requireBoundView(command: IdeCommand, discardRequired: boolean): void {
  requireUuid(command.viewId, 'ideCommand.viewId')
  requireUuid(command.documentId, 'ideCommand.documentId')
  requirePositive(command.bindingEpoch, 'ideCommand.bindingEpoch')
  if (discardRequired && command.discard === undefined) {
    throw new Error('ideCommand.discard is required')
  }
  if (!discardRequired && command.discard !== undefined) {
    throw new Error('ideCommand.discard is not valid for this action')
  }
}

function rejectOpenFields(command: IdeCommand): void {
  if (
    command.eventNodeId !== undefined ||
    command.connectionId !== undefined ||
    command.claimId !== undefined ||
    command.mode !== IdeOpenMode.IDE_OPEN_MODE_UNSPECIFIED
  ) {
    throw new Error('ideCommand declares fields that do not belong to its action')
  }
}

function validateIdeResult(result: IdeResult): void {
  assertNoUnknown(result, 'ideResult')
  if (result.ok === undefined) {
    throw new Error('ideResult.ok is required')
  }
  if (!result.ok) {
    if (
      result.viewId !== undefined ||
      result.documentId !== undefined ||
      result.eventNodeId !== undefined ||
      result.connectionId !== undefined ||
      result.claimId !== undefined ||
      result.bindingEpoch !== undefined ||
      result.directory !== undefined ||
      result.workspacePath !== undefined ||
      result.views.length > 0
    ) {
      throw new Error('failed IDE result must only declare code and message')
    }
    requireText(result.code, 'ideResult.code', { nonBlank: true, pattern: IDE_CODE_PATTERN })
    requireText(result.message, 'ideResult.message', { nonBlank: true, maxChars: MAX_UPDATE_MESSAGE_CHARS })
    return
  }
  if (result.code !== undefined || result.message !== undefined) {
    throw new Error('successful IDE result must not declare code or message')
  }
  if (result.views.length > 0) {
    if (
      result.viewId !== undefined ||
      result.documentId !== undefined ||
      result.directory !== undefined ||
      result.workspacePath !== undefined ||
      result.eventNodeId !== undefined ||
      result.connectionId !== undefined ||
      result.claimId !== undefined ||
      result.bindingEpoch !== undefined
    ) {
      throw new Error('IDE list result must only declare views')
    }
    if (result.views.length > MAX_LISTED_IDE_VIEWS) {
      throw new Error(`ideResult.views must not exceed ${MAX_LISTED_IDE_VIEWS} entries`)
    }
    const viewIds = new Set<string>()
    result.views.forEach((view, index) => {
      validateView(view, `ideResult.views[${index}]`)
      if (viewIds.has(view.viewId ?? '')) {
        throw new Error('ideResult.views must not repeat viewId')
      }
      viewIds.add(view.viewId ?? '')
    })
    return
  }
  const binding =
    result.documentId !== undefined ||
    result.eventNodeId !== undefined ||
    result.connectionId !== undefined ||
    result.claimId !== undefined ||
    result.bindingEpoch !== undefined ||
    result.workspacePath !== undefined
  if (binding) {
    requireUuid(result.viewId, 'ideResult.viewId')
    requireUuid(result.documentId, 'ideResult.documentId')
    requireUuid(result.eventNodeId, 'ideResult.eventNodeId')
    requireUuid(result.connectionId, 'ideResult.connectionId')
    requireUuid(result.claimId, 'ideResult.claimId')
    requirePositive(result.bindingEpoch, 'ideResult.bindingEpoch')
    requireIdePath(result.directory, 'ideResult.directory')
    requireIdePath(result.workspacePath, 'ideResult.workspacePath')
    return
  }
  if (result.directory !== undefined) {
    requireUuid(result.viewId, 'ideResult.viewId')
    requireIdePath(result.directory, 'ideResult.directory')
    return
  }
  if (result.viewId !== undefined) {
    requireUuid(result.viewId, 'ideResult.viewId')
  }
}

function validateView(view: IdeViewSummary, path: string): void {
  assertNoUnknown(view, path)
  requireUuid(view.viewId, `${path}.viewId`)
  requireUuid(view.documentId, `${path}.documentId`)
  requireUuid(view.eventNodeId, `${path}.eventNodeId`)
  requireUuid(view.connectionId, `${path}.connectionId`)
  requireUuid(view.claimId, `${path}.claimId`)
  requirePositive(view.bindingEpoch, `${path}.bindingEpoch`)
  requireIdePath(view.directory, `${path}.directory`)
  requireIdePath(view.workspacePath, `${path}.workspacePath`)
  if (view.state !== IdeViewState.OPEN && view.state !== IdeViewState.CLOSING) {
    throw new Error(`${path}.state is required`)
  }
}

function validateIdeLease(lease: IdeLease): void {
  assertNoUnknown(lease, 'ideLease')
  requirePositive(lease.round, 'ideLease.round')
  if (lease.bindings.length > MAX_IDE_LEASE_BINDINGS) {
    throw new Error(`ideLease.bindings must not exceed ${MAX_IDE_LEASE_BINDINGS}`)
  }
  const viewIds = new Set<string>()
  lease.bindings.forEach((binding, index) => {
    assertNoUnknown(binding, `ideLease.bindings[${index}]`)
    const viewId = requireUuid(binding.viewId, `ideLease.bindings[${index}].viewId`)
    if (viewIds.has(viewId)) {
      throw new Error('ideLease.bindings must not repeat viewId')
    }
    viewIds.add(viewId)
    requireUuid(binding.documentId, `ideLease.bindings[${index}].documentId`)
    requirePositive(binding.bindingEpoch, `ideLease.bindings[${index}].bindingEpoch`)
  })
}

function validateError(error: ProtocolErrorMessage): void {
  assertNoUnknown(error, 'error')
  requireMessage(error.message, 'error.message')
}

function requireCase(envelope: Envelope, expected: string, messageType: string): void {
  assertNoUnknown(envelope.payload.value as UnknownCarrier, `envelope.${expected}`)
  if (envelope.payload.case !== expected) {
    throw new Error(`${messageType} envelope must carry the matching payload`)
  }
}

function requireMessageLength(bytes: Uint8Array): void {
  if (bytes.length === 0 || bytes.length > MAX_MESSAGE_BYTES) {
    throw new Error(`control message length must be in 1..${MAX_MESSAGE_BYTES} bytes`)
  }
}

function requireDaemonVersion(value: unknown, name: string): string {
  return requireText(value, name, { nonBlank: true, pattern: DAEMON_VERSION_PATTERN })
}

function requirePositive(value: unknown, name: string): bigint {
  return requireSafeLong(value, name, 1n, MAX_POSITIVE_BIGINT)
}

/** proto int32 is a 32-bit signed integer; values above 2^31-1 are not representable. */
function requirePositiveInt32(value: unknown, name: string): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 1 || value > 2147483647) {
    throw new Error(`${name} must be in 1..2147483647`)
  }
  return value
}

function requireSingleLine(value: unknown, name: string, maxChars = Number.MAX_SAFE_INTEGER): string {
  const text = requireText(value, name, { nonBlank: true, maxChars })
  if (text !== text.trim() || CONTROL_CHARACTER_PATTERN.test(text)) {
    throw new Error(`${name} must be a single line without surrounding whitespace`)
  }
  return text
}

function requireMessage(value: unknown, name: string): string {
  return requireText(value, name, { nonBlank: true, maxChars: MAX_ERROR_MESSAGE_CHARS })
}

function requireIdePath(value: unknown, name: string): string {
  const text = requireText(value, name, { nonBlank: true, maxChars: MAX_IDE_PATH_CHARS })
  if (/[\u0000-\u001f\u007f]/.test(text)) {
    throw new Error(`${name} must not contain control characters`)
  }
  return text
}

function assertNoUnknown(message: UnknownCarrier | undefined, path: string): void {
  if (message !== undefined && message.$unknown !== undefined && message.$unknown.length > 0) {
    throw new Error(`${path} contains unknown protobuf fields`)
  }
}
