import { fromBinary, toBinary } from '@bufbuild/protobuf'

import {
  EnvelopeSchema,
  MessageType,
  type Completed,
  type Content,
  type Envelope,
  type ExpectedFile,
  type Invoke,
  type ResourceContent,
} from '../../generated/control/v8/control_pb.js'
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

export const PROTOCOL_VERSION = 8
export const MAX_INVOCATION_ID_CHARS = 256
export const MAX_CAPABILITY_VERSION_CHARS = 128
export const MAX_CALL_ID_CHARS = 256
export const MAX_ARGUMENTS_JSON_UTF8_BYTES = MAX_MESSAGE_BYTES
export const MAX_DETAILS_JSON_UTF8_BYTES = 1024 * 1024
export const MAX_JSON_CONTENT_UTF8_BYTES = 1024 * 1024
export const MAX_CONTENT_ITEMS = 64
export const MAX_MEDIA_TYPE_ASCII_BYTES = 255
export const MAX_NAME_UTF8_BYTES = 512
export const MAX_PREVIEW_UTF8_BYTES = 16 * 1024

const CAPABILITY_ID_PATTERN = /^[a-z0-9]+(?:[.-][a-z0-9]+)*$/
const CONTROL_CHARACTER_PATTERN = /[\u0000-\u001f\u007f-\u009f]/

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

export function validate(envelope: Envelope): Envelope {
  assertNoUnknown(envelope, 'envelope')
  if (envelope.protocolVersion !== PROTOCOL_VERSION) {
    throw new Error(`protocolVersion must be ${PROTOCOL_VERSION}`)
  }
  requireUuid(envelope.environmentId, 'environmentId')
  requireText(envelope.invocationId, 'invocationId', {
    nonBlank: true,
    maxChars: MAX_INVOCATION_ID_CHARS,
  })

  const messageType = envelope.messageType
  if (
    messageType !== MessageType.INVOKE &&
    messageType !== MessageType.COMPLETED &&
    messageType !== MessageType.EXECUTION_EXITED
  ) {
    throw new Error('messageType must be a known non-unspecified value')
  }

  switch (messageType) {
    case MessageType.INVOKE:
      if (envelope.payload.case !== 'invoke') {
        throw new Error('INVOKE envelope must carry the matching payload')
      }
      assertNoUnknown(envelope.payload.value, 'envelope.invoke')
      validateInvoke(envelope.payload.value)
      break
    case MessageType.COMPLETED:
      if (envelope.payload.case !== 'completed') {
        throw new Error('COMPLETED envelope must carry the matching payload')
      }
      assertNoUnknown(envelope.payload.value, 'envelope.completed')
      validateCompleted(envelope.payload.value)
      break
    case MessageType.EXECUTION_EXITED:
      if (envelope.payload.case !== 'executionExited') {
        throw new Error('EXECUTION_EXITED envelope must carry the matching payload')
      }
      assertNoUnknown(envelope.payload.value, 'envelope.executionExited')
      break
  }
  return envelope
}

function validateInvoke(invoke: Invoke): void {
  const capabilityId = requireText(invoke.capabilityId, 'invoke.capabilityId', {
    nonBlank: true,
    maxChars: 128,
    pattern: CAPABILITY_ID_PATTERN,
  })
  requireText(invoke.capabilityVersion, 'invoke.capabilityVersion', {
    nonBlank: true,
    maxChars: MAX_CAPABILITY_VERSION_CHARS,
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

function validateCompleted(completed: Completed): void {
  if (completed.result === undefined) {
    throw new Error('completed.result is required')
  }
  const result = completed.result
  assertNoUnknown(result, 'completed.result')
  requireText(result.callId, 'completed.result.callId', {
    nonBlank: true,
    maxChars: MAX_CALL_ID_CHARS,
  })
  if (result.error === undefined) {
    throw new Error('completed.result.error is required')
  }
  parseJsonObject(result.detailsJson, 'completed.result.detailsJson', MAX_DETAILS_JSON_UTF8_BYTES)
  if (result.contents.length > MAX_CONTENT_ITEMS) {
    throw new Error(`completed.result.contents must not exceed ${MAX_CONTENT_ITEMS} items`)
  }
  result.contents.forEach((content, index) => {
    validateContent(content, `completed.result.contents[${index}]`)
  })
}

function validateContent(content: Content, path: string): void {
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
    const name = requireText(resource.name, `${path}.name`, {
      nonBlank: true,
      maxBytes: MAX_NAME_UTF8_BYTES,
    })
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

function assertNoUnknown(message: UnknownCarrier, path: string): void {
  if (message.$unknown !== undefined && message.$unknown.length > 0) {
    throw new Error(`${path} contains unknown protobuf fields`)
  }
}

function requireMessageLength(bytes: Uint8Array): void {
  if (bytes.length === 0 || bytes.length > MAX_MESSAGE_BYTES) {
    throw new Error(`control message length must be in 1..${MAX_MESSAGE_BYTES} bytes`)
  }
}
