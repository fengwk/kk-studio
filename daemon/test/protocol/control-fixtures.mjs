import { create } from '@bufbuild/protobuf'

import {
  CapabilityResultSchema,
  CompletedSchema,
  ContentSchema,
  EnvelopeSchema,
  ExecutionExitedSchema,
  ExpectedFileSchema,
  InvokeSchema,
  JsonContentSchema,
  MessageType,
  ResourceContentSchema,
  TextContentSchema,
} from '../../dist/generated/control/v8/control_pb.js'

export const ENVIRONMENT_ID = '3f2a9c1e-7b4d-4e2a-9c6f-1a2b3c4d5e6f'
export const INVOCATION_ID = 'invoke-0001'
export const CAPABILITY_ID = 'fs.edit'
export const CAPABILITY_VERSION = '1.0.0'
export const ARGUMENTS_JSON =
  '{"path":"src/main/java/App.java","exponent":1e-7,"highPrecision":0.123456789012345678901234567890,"negativeZero":-0}'
export const TIMEOUT_MILLIS = 30000n
export const EXPECTED_PATH = 'src/main/java/App.java'
export const SHA_A = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
export const CALL_ID = 'call-0001'
export const DETAILS_JSON =
  '{"durationMillis":12,"ratio":1e-7,"precise":0.123456789012345678901234567890,"negativeZero":-0}'
export const TEXT_CONTENT = 'completed'
export const JSON_CONTENT =
  '{"answer":42,"exponent":1e-7,"highPrecision":0.123456789012345678901234567890,"negativeZero":-0}'

const ENVELOPE_BASE = {
  protocolVersion: 8,
  environmentId: ENVIRONMENT_ID,
  invocationId: INVOCATION_ID,
}

function envelope(messageType, payload) {
  return create(EnvelopeSchema, { ...ENVELOPE_BASE, messageType, payload })
}

export function invokeEnvelope() {
  return envelope(MessageType.INVOKE, {
    case: 'invoke',
    value: create(InvokeSchema, {
      capabilityId: CAPABILITY_ID,
      capabilityVersion: CAPABILITY_VERSION,
      argumentsJson: ARGUMENTS_JSON,
      timeoutMillis: TIMEOUT_MILLIS,
      expectedFile: create(ExpectedFileSchema, {
        path: EXPECTED_PATH,
        sha256: SHA_A,
      }),
    }),
  })
}

export function completedEnvelope() {
  return envelope(MessageType.COMPLETED, {
    case: 'completed',
    value: create(CompletedSchema, {
      result: create(CapabilityResultSchema, {
        callId: CALL_ID,
        error: false,
        detailsJson: DETAILS_JSON,
        contents: [
          create(ContentSchema, {
            body: {
              case: 'text',
              value: create(TextContentSchema, { text: TEXT_CONTENT }),
            },
          }),
          create(ContentSchema, {
            body: {
              case: 'json',
              value: create(JsonContentSchema, { json: JSON_CONTENT }),
            },
          }),
          create(ContentSchema, {
            body: {
              case: 'resource',
              value: create(ResourceContentSchema, {
                uploadId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
                mediaType: 'text/plain',
                name: 'notes.txt',
                size: 12n,
                sha256: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                preview: 'hello world!',
              }),
            },
          }),
          create(ContentSchema, {
            body: {
              case: 'resource',
              value: create(ResourceContentSchema, {
                uploadId: '9b2f4c6a-1d3e-4f5a-8b7c-2d1e0f3a4b5c',
                mediaType: 'application/octet-stream',
                size: 0n,
                sha256: '0000000000000000000000000000000000000000000000000000000000000000',
              }),
            },
          }),
        ],
      }),
    }),
  })
}

export function executionExitedEnvelope() {
  return envelope(MessageType.EXECUTION_EXITED, {
    case: 'executionExited',
    value: create(ExecutionExitedSchema, {}),
  })
}
