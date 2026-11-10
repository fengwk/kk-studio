import { fromBinary, toBinary } from '@bufbuild/protobuf'

import {
  EnvelopeSchema,
  IdeAction,
  IdeOpenMode,
  IdeViewState,
  MessageType,
  TicketState,
  UpdatePhase,
  type CapabilityResult,
  type Content,
  type Envelope,
  type EnvironmentConfig,
  type Error as ProtocolErrorMessage,
  type ExpectedFile,
  type Heartbeat,
  type HostInfo,
  type IdeCommand,
  type IdeLease,
  type IdeResult,
  type IdeViewSummary,
  type Invoke,
  type PresignedGet,
  type PresignedPut,
  type Ready,
  type ResourceContent,
  type ResourceDownloadRequest,
  type ResourceDownloadTicket,
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
import { DAEMON_VERSION_PATTERN } from '../../release.js'
import { parseDaemonConfiguration } from '../../config.js'
import type { DaemonConfiguration } from '../../contracts.js'
import { LONG_MAX } from '../../validation-constants.js'
import { frame, requireMessageLength, unframe } from './framing.js'
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

export const PROTOCOL_VERSION = 4
const MAX_INVOCATION_ID_CHARS = 256
const MAX_CALL_ID_CHARS = 256
export const MAX_ARGUMENTS_JSON_UTF8_BYTES = MAX_MESSAGE_BYTES
export const MAX_DETAILS_JSON_UTF8_BYTES = 1024 * 1024
const MAX_JSON_CONTENT_UTF8_BYTES = 1024 * 1024
const MAX_CONTENT_ITEMS = 64
const MAX_MEDIA_TYPE_ASCII_BYTES = 255
const MAX_NAME_UTF8_BYTES = 512
const MAX_PREVIEW_UTF8_BYTES = 16 * 1024
const MAX_FAILURE_MESSAGE_CHARS = 512
const MAX_DISPLAY_NAME_CHARS = 256
const MAX_ERROR_MESSAGE_CHARS = 4096
const MAX_IDE_PATH_CHARS = 4096
const MAX_LISTED_IDE_VIEWS = 64
const MAX_IDE_LEASE_BINDINGS = 64
export const MAX_NOTE_CHARS = 512
const MAX_UPDATE_MESSAGE_CHARS = 500
const MAX_PRESIGNED_URL_CHARS = 4096
const MAX_PRESIGNED_HEADERS = 16
const MAX_PRESIGNED_HEADER_CHARS = 1024

const CAPABILITY_ID_PATTERN = /^[a-z0-9]+(?:[.-][a-z0-9]+)*$/
const IDE_CODE_PATTERN = /^[A-Z][A-Z0-9_]{0,63}$/
const CONTROL_CHARACTER_PATTERN = /[\u0000-\u001f\u007f-\u009f]/

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
  MessageType.RESOURCE_DOWNLOAD_REQUEST,
  MessageType.RESOURCE_DOWNLOAD_TICKET,
  MessageType.ENVIRONMENT_CONFIG,
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
  MessageType.RESOURCE_DOWNLOAD_REQUEST,
  MessageType.RESOURCE_DOWNLOAD_TICKET,
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
  MessageType.ENVIRONMENT_CONFIG,
])

interface UnknownCarrier {
  $unknown?: readonly unknown[]
}

/** Decodes one bare protobuf message and applies all application-level checks. */
export function decodeBinary(bytes: Uint8Array): Envelope {
  requireMessageLength(bytes.length)
  return validate(fromBinary(EnvelopeSchema, bytes, { readUnknownFields: true }))
}

/** Validates and encodes one bare protobuf message. */
export function encodeBinary(envelope: Envelope): Uint8Array {
  validate(envelope)
  const bytes = toBinary(EnvelopeSchema, envelope)
  requireMessageLength(bytes.length)
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
  requireText(upgrade.targetVersion, 'upgradeRequired.targetVersion', {
    nonBlank: true,
    pattern: DAEMON_VERSION_PATTERN,
  })
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
    case MessageType.RESOURCE_DOWNLOAD_REQUEST:
      requireCase(envelope, 'resourceDownloadRequest', 'RESOURCE_DOWNLOAD_REQUEST')
      validateDownloadRequest(envelope.payload.value as ResourceDownloadRequest)
      break
    case MessageType.RESOURCE_DOWNLOAD_TICKET:
      requireCase(envelope, 'resourceDownloadTicket', 'RESOURCE_DOWNLOAD_TICKET')
      validateDownloadTicket(envelope.payload.value as ResourceDownloadTicket)
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
    case MessageType.ENVIRONMENT_CONFIG:
      requireCase(envelope, 'environmentConfig', 'ENVIRONMENT_CONFIG')
      validateEnvironmentConfig(envelope.payload.value as EnvironmentConfig)
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
}

/** 校验并还原一条完整 ENVIRONMENT_CONFIG 快照。 */
export function parseEnvironmentConfig(config: EnvironmentConfig): {
  version: bigint
  configuration: DaemonConfiguration
} {
  if (config.version === undefined) {
    throw new Error('environmentConfig.version is required')
  }
  requireSafeLong(config.version, 'environmentConfig.version', 0n, LONG_MAX)
  if (config.config === undefined) {
    throw new Error('environmentConfig.config is required')
  }
  assertNoUnknown(config.config, 'environmentConfig.config')
  const servers: Record<string, unknown> = {}
  for (const server of config.config!.lsp?.servers ?? []) {
    if (servers[server.id] !== undefined) {
      throw new Error('environmentConfig.config.lsp.servers must not repeat id')
    }
    assertNoUnknown(server, 'environmentConfig.config.lsp.server')
    servers[server.id] = {
      command: [...server.command],
      ...(server.extensions.length === 0 ? {} : { extensions: [...server.extensions] }),
      ...(server.rootMarkers.length === 0 ? {} : { rootMarkers: [...server.rootMarkers] }),
      ...(server.firstMatchMarkers.length === 0
        ? {}
        : { firstMatchMarkers: [...server.firstMatchMarkers] }),
    }
  }
  const plain = {
    studioUrl: config.config!.studioUrl,
    bashExecutable: config.config!.bashExecutable ?? null,
    lsp: config.config!.lsp === undefined ? null : { servers },
    proxyUrl: config.config!.proxyUrl ?? null,
  }
  return {
    version: config.version!,
    configuration: parseDaemonConfiguration(plain, 'environmentConfig.config'),
  }
}

function validateEnvironmentConfig(config: EnvironmentConfig): void {
  assertNoUnknown(config, 'environmentConfig')
  if (config.version === undefined) {
    throw new Error('environmentConfig.version is required')
  }
  requireSafeLong(config.version, 'environmentConfig.version', 0n, LONG_MAX)
  if (config.config === undefined) {
    throw new Error('environmentConfig.config is required')
  }
  assertNoUnknown(config.config, 'environmentConfig.config')
  try {
    parseEnvironmentConfig(config)
  } catch (error) {
    if (error instanceof Error && error.message.startsWith('environmentConfig.config')) {
      throw error
    }
    throw new Error(`environmentConfig.config is invalid: ${error instanceof Error ? error.message : 'invalid value'}`)
  }
}

function validateReady(ready: Ready): void {
  assertNoUnknown(ready, 'ready')
  const daemonVersion = requireDaemonVersion(ready.daemonVersion, 'ready.daemonVersion')
  if (ready.hostInfo === undefined) {
    throw new Error('ready.hostInfo is required')
  }
  validateHostInfo(ready.hostInfo)
  if (ready.runtimeStatus === undefined) {
    throw new Error('ready.runtimeStatus is required')
  }
  const snapshot = validateSnapshot(ready.runtimeStatus, 'ready.runtimeStatus')
  if (snapshot.status?.daemonVersion !== daemonVersion) {
    throw new Error('ready.runtimeStatus.status.daemonVersion must match ready.daemonVersion')
  }
  if (snapshot.status.configuration?.appliedVersion === undefined) {
    throw new Error('ready.runtimeStatus.status.configuration.appliedVersion is required')
  }
}

function validateHostInfo(info: HostInfo): void {
  assertNoUnknown(info, 'ready.hostInfo')
  if (
    info.operatingSystem !== 1 &&
    info.operatingSystem !== 2 &&
    info.operatingSystem !== 3 &&
    info.operatingSystem !== 4
  ) {
    throw new Error('ready.hostInfo.operatingSystem is required')
  }
  if (info.architecture !== 1 && info.architecture !== 2) {
    throw new Error('ready.hostInfo.architecture is required')
  }
  const zone = requireText(info.timeZone, 'ready.hostInfo.timeZone', { nonBlank: true, maxChars: 128 })
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: zone })
  } catch {
    throw new Error('ready.hostInfo.timeZone must be a valid ZoneId')
  }
  requireSingleLine(info.userName, 'ready.hostInfo.userName')
  requireSingleLine(info.homeDirectory, 'ready.hostInfo.homeDirectory')
  requireSingleLine(info.tempDirectory, 'ready.hostInfo.tempDirectory')
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
  if (snapshot.status.configuration !== undefined) {
    validateConfigurationStatus(snapshot.status.configuration, `${path}.status.configuration`)
  }
  if (snapshot.status.update !== undefined) {
    validateUpdateProgress(snapshot.status.update, `${path}.status.update`)
  }
  return snapshot
}

function validateConfigurationStatus(
  configuration: { appliedVersion?: bigint; proxyUrl?: string; error?: { version?: bigint; code?: string; field?: string } },
  path: string,
): void {
  assertNoUnknown(configuration as UnknownCarrier, path)
  if (configuration.appliedVersion !== undefined) {
    requireSafeLong(configuration.appliedVersion, `${path}.appliedVersion`, 0n, LONG_MAX)
  }
  if (configuration.proxyUrl !== undefined) {
    if (configuration.appliedVersion === undefined) {
      throw new Error(`${path}.proxyUrl requires appliedVersion`)
    }
    requireText(configuration.proxyUrl, `${path}.proxyUrl`, {
      nonBlank: true,
      maxChars: 2048,
      pattern: /^http:\/\/(?:\[[^\]]+\]|[^/?#@[\]:]+):[0-9]{1,5}$/,
    })
  }
  if (configuration.error !== undefined) {
    const error = configuration.error
    assertNoUnknown(error as UnknownCarrier, `${path}.error`)
    if (error.version === undefined) {
      throw new Error(`${path}.error.version is required`)
    }
    requireSafeLong(error.version, `${path}.error.version`, 0n, LONG_MAX)
    requireText(error.code, `${path}.error.code`, {
      nonBlank: true,
      maxChars: 64,
      pattern: /^[A-Z][A-Z0-9_]{0,63}$/,
    })
    if (error.field !== undefined) {
      requireText(error.field, `${path}.error.field`, {
        nonBlank: true,
        maxChars: 256,
        pattern: /^[A-Za-z0-9_.\[\]-]+$/,
      })
    }
  }
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
  requireSafeLong(put.expiresAtEpochMs, 'presignedPut.expiresAtEpochMs', 1n, LONG_MAX)
}

function validateUploadCommit(commit: { transferId?: string; uploadId?: string }): void {
  assertNoUnknown(commit as UnknownCarrier, 'uploadCommit')
  requireUuid(commit.transferId, 'uploadCommit.transferId')
  requireUuid(commit.uploadId, 'uploadCommit.uploadId')
}

/** 下载申请：transferId 与全局 blob id 必填；业务票据签发由服务端 download 端口完成。 */
function validateDownloadRequest(request: ResourceDownloadRequest): void {
  assertNoUnknown(request, 'downloadRequest')
  requireUuid(request.transferId, 'downloadRequest.transferId')
  requireUuid(request.blobId, 'downloadRequest.blobId')
}

/** 下载票据：READY 携带预签名 GET、大小与摘要；FAILED 只携带去敏说明。 */
function validateDownloadTicket(ticket: ResourceDownloadTicket): void {
  assertNoUnknown(ticket, 'downloadTicket')
  requireUuid(ticket.transferId, 'downloadTicket.transferId')
  if (ticket.state === TicketState.TICKET_READY) {
    if (ticket.message !== undefined) {
      throw new Error('READY download ticket must not declare a message')
    }
    validatePresignedGet(ticket.presignedGet as PresignedGet)
    requireSafeLong(ticket.size, 'downloadTicket.size', 0n, MAX_SAFE_BIGINT)
    requireLowerSha256(ticket.sha256, 'downloadTicket.sha256')
    return
  }
  if (ticket.state === TicketState.TICKET_FAILED) {
    if (ticket.presignedGet !== undefined || ticket.size !== undefined || ticket.sha256 !== undefined) {
      throw new Error('FAILED download ticket requires a message only')
    }
    requireText(ticket.message, 'downloadTicket.message', { nonBlank: true, maxChars: MAX_FAILURE_MESSAGE_CHARS })
    return
  }
  throw new Error('downloadTicket.state is required')
}

function validatePresignedGet(get: PresignedGet): void {
  assertNoUnknown(get, 'presignedGet')
  if (requireText(get.method, 'presignedGet.method', { nonBlank: true }) !== 'GET') {
    throw new Error('presignedGet.method must be GET')
  }
  requireText(get.url, 'presignedGet.url', { nonBlank: true, maxChars: MAX_PRESIGNED_URL_CHARS })
  requireSafeLong(get.expiresAtEpochMs, 'presignedGet.expiresAtEpochMs', 1n, LONG_MAX)
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
      requireExpectedDaemonInstance(command)
      if (command.expectedBindingEpoch === undefined) {
        throw new Error('ideCommand.expectedBindingEpoch is required')
      }
      requireNonNegative(command.expectedBindingEpoch, 'ideCommand.expectedBindingEpoch')
      if (command.bindingEpoch !== undefined || command.discard !== undefined) {
        throw new Error('OPEN_VIEW must not declare bindingEpoch or discard')
      }
      if (command.mode !== IdeOpenMode.CREATE && command.mode !== IdeOpenMode.RESUME) {
        throw new Error('ideCommand.mode is required for OPEN_VIEW')
      }
      if (command.mode === IdeOpenMode.CREATE && command.expectedBindingEpoch !== 0n) {
        throw new Error('OPEN_VIEW create requires expectedBindingEpoch 0')
      }
      if (command.mode === IdeOpenMode.RESUME && command.expectedBindingEpoch <= 0n) {
        throw new Error('OPEN_VIEW resume requires a positive expectedBindingEpoch')
      }
      if (command.directory !== undefined) {
        requireIdePath(command.directory, 'ideCommand.directory')
      }
      return
    case IdeAction.CHANGE_DIRECTORY:
      requireBoundView(command, false)
      requireExpectedDaemonInstance(command)
      if (command.directory === undefined) {
        throw new Error('ideCommand.directory is required')
      }
      requireIdePath(command.directory, 'ideCommand.directory')
      rejectOpenFields(command)
      return
    case IdeAction.FOCUS_TERMINAL:
    case IdeAction.COLLAPSE_MOBILE:
      requireBoundView(command, false)
      requireExpectedDaemonInstance(command)
      if (command.directory !== undefined) {
        throw new Error('ideCommand.directory is not valid for this action')
      }
      rejectOpenFields(command)
      return
    case IdeAction.CLOSE_VIEW:
      requireBoundView(command, true)
      requireExpectedDaemonInstance(command)
      if (command.directory !== undefined) {
        throw new Error('CLOSE_VIEW must not declare directory')
      }
      rejectOpenFields(command)
      return
    case IdeAction.LIST_VIEWS:
    case IdeAction.LIST_DIRECTORIES:
      if (
        command.viewId !== undefined ||
        command.browserSessionId !== undefined ||
        command.eventNodeId !== undefined ||
        command.connectionId !== undefined ||
        command.claimId !== undefined ||
        command.bindingEpoch !== undefined ||
        command.expectedBindingEpoch !== undefined ||
        command.expectedDaemonInstanceId !== undefined ||
        command.mode !== IdeOpenMode.IDE_OPEN_MODE_UNSPECIFIED ||
        (command.action === IdeAction.LIST_VIEWS && command.directory !== undefined) ||
        command.discard !== undefined
      ) {
        throw new Error('IDE list command has invalid fields')
      }
      if (command.directory !== undefined) {
        requireIdePath(command.directory, 'ideCommand.directory')
      }
      return
    default:
      throw new Error('ideCommand.action is required')
  }
}

function requireOpenIdentity(command: IdeCommand): void {
  requireUuid(command.viewId, 'ideCommand.viewId')
  requireUuid(command.browserSessionId, 'ideCommand.browserSessionId')
  requireUuid(command.eventNodeId, 'ideCommand.eventNodeId')
  requireUuid(command.connectionId, 'ideCommand.connectionId')
  requireUuid(command.claimId, 'ideCommand.claimId')
}

function requireExpectedDaemonInstance(command: IdeCommand): void {
  requireUuid(command.expectedDaemonInstanceId, 'ideCommand.expectedDaemonInstanceId')
}

function requireBoundView(command: IdeCommand, discardRequired: boolean): void {
  requireUuid(command.viewId, 'ideCommand.viewId')
  requireUuid(command.browserSessionId, 'ideCommand.browserSessionId')
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
    command.expectedBindingEpoch !== undefined ||
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
    if (result.revision !== undefined) {
      if (
        result.viewId !== undefined ||
        result.browserSessionId !== undefined ||
        result.eventNodeId !== undefined ||
        result.connectionId !== undefined ||
        result.claimId !== undefined ||
        result.bindingEpoch !== undefined ||
        result.directory !== undefined ||
        result.workspacePath !== undefined ||
        result.directoryListing !== undefined
      ) {
        throw new Error(
          'failed IDE result with a snapshot must only declare code, message, revision and views',
        )
      }
      validateIdeSnapshot(result)
    } else if (result.views.length > 0) {
      throw new Error('ideResult.revision is required with views')
    } else if (
      result.viewId !== undefined ||
      result.browserSessionId !== undefined ||
      result.eventNodeId !== undefined ||
      result.connectionId !== undefined ||
      result.claimId !== undefined ||
      result.bindingEpoch !== undefined ||
      result.directory !== undefined ||
      result.workspacePath !== undefined ||
      result.directoryListing !== undefined
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
  if (result.directoryListing !== undefined) {
    if (
      result.revision !== undefined || result.views.length > 0 ||
      result.viewId !== undefined || result.browserSessionId !== undefined ||
      result.eventNodeId !== undefined || result.connectionId !== undefined ||
      result.claimId !== undefined || result.bindingEpoch !== undefined ||
      result.directory !== undefined || result.workspacePath !== undefined
    ) {
      throw new Error('directory listing result must not contain view or snapshot fields')
    }
    const listing = result.directoryListing
    assertNoUnknown(listing, 'directoryListing')
    requireIdePath(listing.path, 'directoryListing.path')
    if (listing.parentPath !== undefined) {
      requireIdePath(listing.parentPath, 'directoryListing.parentPath')
    }
    if (listing.truncated === undefined || listing.children.length > 1000) {
      throw new Error('directory listing requires truncated and at most 1000 children')
    }
    for (const child of listing.children) {
      assertNoUnknown(child, 'directoryEntry')
      requireIdePath(child.name, 'directoryEntry.name')
      requireIdePath(child.path, 'directoryEntry.path')
    }
    return
  }
  if (result.revision !== undefined) {
    validateIdeSnapshot(result)
  } else if (result.views.length > 0) {
    throw new Error('ideResult.revision is required with views')
  }
  const binding =
    result.browserSessionId !== undefined ||
    result.eventNodeId !== undefined ||
    result.connectionId !== undefined ||
    result.claimId !== undefined ||
    result.bindingEpoch !== undefined ||
    result.workspacePath !== undefined
  if (binding) {
    requireUuid(result.viewId, 'ideResult.viewId')
    requireUuid(result.browserSessionId, 'ideResult.browserSessionId')
    requireUuid(result.eventNodeId, 'ideResult.eventNodeId')
    requireUuid(result.connectionId, 'ideResult.connectionId')
    requireUuid(result.claimId, 'ideResult.claimId')
    requirePositive(result.bindingEpoch, 'ideResult.bindingEpoch')
    requireIdePath(result.directory, 'ideResult.directory')
    requireIdePath(result.workspacePath, 'ideResult.workspacePath')
    if (result.revision !== undefined) {
      validateSnapshotBinding(result)
    }
    return
  }
  if (result.directory !== undefined) {
    requireUuid(result.viewId, 'ideResult.viewId')
    requireIdePath(result.directory, 'ideResult.directory')
    return
  }
  if (result.viewId !== undefined) {
    requireUuid(result.viewId, 'ideResult.viewId')
    return
  }
  if (result.revision === undefined) {
    throw new Error('successful IDE result must declare an outcome')
  }
}

function validateIdeSnapshot(result: IdeResult): void {
  const revision = requireNonNegative(result.revision, 'ideResult.revision')
  if (result.views.length > MAX_LISTED_IDE_VIEWS) {
    throw new Error(`ideResult.views must not exceed ${MAX_LISTED_IDE_VIEWS} entries`)
  }
  let previousViewId: string | null = null
  result.views.forEach((view, index) => {
    validateView(view, `ideResult.views[${index}]`)
    if (view.bindingEpoch !== undefined && view.bindingEpoch > revision) {
      throw new Error(
        `ideResult.views[${index}].bindingEpoch must not exceed ideResult.revision`,
      )
    }
    if (previousViewId !== null && view.viewId !== undefined) {
      if (previousViewId === view.viewId) {
        throw new Error('ideResult.views must not repeat viewId')
      }
      if (previousViewId > view.viewId) {
        throw new Error('ideResult.views must be sorted by viewId')
      }
    }
    if (view.viewId !== undefined) {
      previousViewId = view.viewId
    }
  })
}

/**
 * top-level 完整绑定与同一快照中的 summary 必须一致，防止旧绑定套在新 revision 快照上。
 *
 * <p>没有快照的简单回执（例如 FOCUS_TERMINAL/COLLAPSE_MOBILE 只带 viewId）不经过本校验。
 */
function validateSnapshotBinding(result: IdeResult): void {
  const view = result.views.find((candidate) => candidate.viewId === result.viewId)
  if (view === undefined) {
    throw new Error('ideResult snapshot must contain the top-level binding view')
  }
  if (
    view.browserSessionId !== result.browserSessionId ||
    view.eventNodeId !== result.eventNodeId ||
    view.connectionId !== result.connectionId ||
    view.claimId !== result.claimId ||
    view.bindingEpoch !== result.bindingEpoch ||
    view.directory !== result.directory ||
    view.workspacePath !== result.workspacePath
  ) {
    throw new Error('ideResult snapshot view must match the top-level binding')
  }
}

function validateView(view: IdeViewSummary, path: string): void {
  assertNoUnknown(view, path)
  requireUuid(view.viewId, `${path}.viewId`)
  requireUuid(view.browserSessionId, `${path}.browserSessionId`)
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
    requireUuid(binding.browserSessionId, `ideLease.bindings[${index}].browserSessionId`)
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

function requireDaemonVersion(value: unknown, name: string): string {
  return requireText(value, name, { nonBlank: true, pattern: DAEMON_VERSION_PATTERN })
}

function requirePositive(value: unknown, name: string): bigint {
  return requireSafeLong(value, name, 1n, LONG_MAX)
}

function requireNonNegative(value: unknown, name: string): bigint {
  return requireSafeLong(value, name, 0n, LONG_MAX)
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
