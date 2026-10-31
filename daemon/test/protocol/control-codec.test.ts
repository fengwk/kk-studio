import { create, toBinary } from '@bufbuild/protobuf'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'

import { describe, expect, it } from 'vitest'

import {
  CapabilityResultSchema,
  CompletedSchema,
  ContentSchema,
  EnvelopeSchema,
  ExecutionExitedSchema,
  InvokeSchema,
  MessageType,
  ResourceContentSchema,
  TextContentSchema,
  type CapabilityResult,
  type Invoke,
  type ResourceContent,
} from '../../src/generated/control_pb.js'
import {
  decodeBinary,
  decodeFrame,
  encodeBinary,
  encodeFrame,
  validateUpgrade,
} from '../../src/protocol/control/codec.js'
import { frame, unframe } from '../../src/protocol/control/framing.js'
import { MAX_MESSAGE_BYTES, MAX_SAFE_BIGINT } from '../../src/protocol/control/runtime.js'
import {
  ARGUMENTS_JSON,
  CALL_ID,
  CAPABILITY_ID,
  CAPABILITY_VERSION,
  DETAILS_JSON,
  ENVIRONMENT_ID,
  INVOCATION_ID,
  JSON_CONTENT,
  TIMEOUT_MILLIS,
  completedEnvelope,
  executionExitedEnvelope,
  invokeEnvelope,
  upgradeRequired,
} from './control-fixtures.mjs'

const GOLDEN = fileURLToPath(
  new URL('../../../harness/environment/src/main/protocol/golden/', import.meta.url),
)

function readGolden(name: string): Uint8Array {
  return new Uint8Array(readFileSync(join(GOLDEN, name)))
}

function assertBytesEqual(left: Uint8Array, right: Uint8Array): void {
  expect(Buffer.from(left).equals(Buffer.from(right))).toBe(true)
}

function varint(value: number): number[] {
  const result: number[] = []
  let remaining = value
  while (remaining >= 0x80) {
    result.push((remaining & 0x7f) | 0x80)
    remaining >>>= 7
  }
  result.push(remaining)
  return result
}

function lengthDelimited(field: number, bytes: Uint8Array): number[] {
  return [...varint((field << 3) | 2), ...varint(bytes.length), ...bytes]
}

function baseEnvelope(messageType: MessageType, payload: number[]): Uint8Array {
  return new Uint8Array([
    ...varint(1 << 3),
    ...varint(messageType),
    ...lengthDelimited(2, new TextEncoder().encode(ENVIRONMENT_ID)),
    ...lengthDelimited(3, new TextEncoder().encode(INVOCATION_ID)),
    ...payload,
  ])
}

function validInvoke() {
  return create(InvokeSchema, {
    capabilityId: CAPABILITY_ID,
    capabilityVersion: CAPABILITY_VERSION,
    argumentsJson: ARGUMENTS_JSON,
    timeoutMillis: TIMEOUT_MILLIS,
  })
}

describe('control binary codec', () => {
  it('encodes the shared fixtures to golden byte-for-byte', () => {
    assertBytesEqual(encodeBinary(invokeEnvelope()), readGolden('invoke.bin'))
    assertBytesEqual(encodeBinary(completedEnvelope()), readGolden('completed.bin'))
    assertBytesEqual(encodeBinary(executionExitedEnvelope()), readGolden('execution-exited.bin'))
  })

  it('roundtrips shared golden through bare and framed paths', () => {
    for (const name of ['invoke.bin', 'completed.bin', 'execution-exited.bin']) {
      const golden = readGolden(name)
      assertBytesEqual(encodeBinary(decodeBinary(golden)), golden)
      assertBytesEqual(encodeBinary(decodeFrame(encodeFrame(decodeBinary(golden)))), golden)
    }
  })

  it('preserves opaque JSON text without reserializing', () => {
    const invoke = decodeBinary(readGolden('invoke.bin'))
    expect(invoke.payload.case).toBe('invoke')
    if (invoke.payload.case === 'invoke') {
      expect(invoke.payload.value.argumentsJson).toBe(ARGUMENTS_JSON)
    }

    const completed = decodeBinary(readGolden('completed.bin'))
    expect(completed.payload.case).toBe('completed')
    if (completed.payload.case === 'completed') {
      expect(completed.payload.value.result?.detailsJson).toBe(DETAILS_JSON)
      expect(completed.payload.value.result?.contents[1]?.body.case).toBe('json')
      const json = completed.payload.value.result?.contents[1]?.body
      if (json?.case === 'json') {
        expect(json.value.json).toBe(JSON_CONTENT)
      }
    }
  })

  it('allows empty text content', () => {
    const completed = completedEnvelope()
    const result = completed.payload.case === 'completed' ? completed.payload.value.result : undefined
    if (result === undefined) {
      throw new Error('fixture must contain a completed result')
    }
    const emptyText = create(ContentSchema, {
      body: { case: 'text', value: create(TextContentSchema, { text: '' }) },
    })
    const envelope = create(EnvelopeSchema, {
      messageType: MessageType.COMPLETED,
      environmentId: ENVIRONMENT_ID,
      invocationId: INVOCATION_ID,
      payload: {
        case: 'completed',
        value: create(CompletedSchema, {
          result: create(CapabilityResultSchema, {
            callId: result.callId,
            error: result.error,
            detailsJson: result.detailsJson,
            contents: [emptyText],
          }),
        }),
      },
    })
    expect(() => encodeBinary(envelope)).not.toThrow()
  })

  it('rejects unknown envelope, invoke and content fields recursively', () => {
    const unknown = new Uint8Array([0xa0, 0x06, 0x01])
    const envelopeUnknown = new Uint8Array([...readGolden('execution-exited.bin'), ...unknown])
    expect(() => decodeBinary(envelopeUnknown)).toThrow(/unknown protobuf fields/)

    const invokeBytes = new Uint8Array([...toBinary(InvokeSchema, validInvoke()), ...unknown])
    const invokeUnknown = baseEnvelope(MessageType.INVOKE, lengthDelimited(8, invokeBytes))
    expect(() => decodeBinary(invokeUnknown)).toThrow(/unknown protobuf fields/)

    const content = create(ContentSchema, {
      body: { case: 'text', value: create(TextContentSchema, { text: 'x' }) },
    })
    const contentBytes = new Uint8Array([...toBinary(ContentSchema, content), ...unknown])
    const completedPayload = new Uint8Array([
      ...lengthDelimited(
        1,
        new Uint8Array([
          ...lengthDelimited(1, new TextEncoder().encode(CALL_ID)),
          ...varint(2 << 3),
          ...varint(0),
          ...lengthDelimited(3, new TextEncoder().encode('{}')),
          ...lengthDelimited(4, contentBytes),
        ]),
      ),
    ])
    const completedUnknown = baseEnvelope(MessageType.COMPLETED, lengthDelimited(11, completedPayload))
    expect(() => decodeBinary(completedUnknown)).toThrow(/unknown protobuf fields/)
  })

  it('rejects duplicate JSON keys, invalid JSON and missing required fields', () => {
    const duplicate = create(InvokeSchema, {
      capabilityId: CAPABILITY_ID,
      capabilityVersion: CAPABILITY_VERSION,
      argumentsJson: '{"a":1,"a":2}',
      timeoutMillis: TIMEOUT_MILLIS,
    })
    expect(() => encodeBinary(envelope(MessageType.INVOKE, duplicate))).toThrow(/duplicate field/)

    const invalid = create(InvokeSchema, {
      capabilityId: CAPABILITY_ID,
      capabilityVersion: CAPABILITY_VERSION,
      argumentsJson: '{"a":}',
      timeoutMillis: TIMEOUT_MILLIS,
    })
    expect(() => encodeBinary(envelope(MessageType.INVOKE, invalid))).toThrow(/valid JSON/)

    const missing = create(InvokeSchema, {
      capabilityVersion: CAPABILITY_VERSION,
      argumentsJson: '{}',
      timeoutMillis: 0n,
    })
    expect(() => encodeBinary(envelope(MessageType.INVOKE, missing))).toThrow(/capabilityId/)
  })

  it('rejects unsafe integers, UTF-8 overflow and invalid resource metadata', () => {
    expect(() =>
      encodeBinary(
        envelope(
          MessageType.INVOKE,
          create(InvokeSchema, {
            capabilityId: CAPABILITY_ID,
            capabilityVersion: CAPABILITY_VERSION,
            argumentsJson: '{}',
            timeoutMillis: MAX_SAFE_BIGINT + 1n,
          }),
        ),
      ),
    ).toThrow(/timeoutMillis/)

    const longName = create(ResourceContentSchema, {
      uploadId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
      mediaType: 'text/plain',
      name: 'x'.repeat(513),
      size: 0n,
      sha256: 'b'.repeat(64),
    })
    expect(() => encodeBinary(completedWithResource(longName))).toThrow(/512 UTF-8 bytes/)

    const badResource = create(ResourceContentSchema, {
      uploadId: 'not-a-uuid',
      mediaType: 'TEXT/PLAIN',
      size: 0n,
      sha256: 'not-sha',
    })
    expect(() => encodeBinary(completedWithResource(badResource))).toThrow(/canonical lowercase UUID/)
  })

  it('rejects protocol, payload and scope mismatches', () => {
    const invoke = invokeEnvelope()
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.COMPLETED,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: invoke.payload,
        }),
      ),
    ).toThrow(/COMPLETED envelope must carry/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.EXECUTION_EXITED,
          environmentId: 'not-a-uuid',
          invocationId: INVOCATION_ID,
          payload: { case: 'executionExited', value: create(ExecutionExitedSchema, {}) },
        }),
      ),
    ).toThrow(/environmentId/)
  })

  it('rejects malformed wire, oversized content lists and invalid UTF-8', () => {
    expect(() => decodeBinary(new Uint8Array([0x1a, 0x01, 0xff]))).toThrow()
    expect(() => decodeBinary(new Uint8Array())).toThrow(/1\.\./)

    const contents = Array.from({ length: 65 }, (_, index) =>
      create(ContentSchema, {
        body: { case: 'text', value: create(TextContentSchema, { text: String(index) }) },
      }),
    )
    const result = create(CapabilityResultSchema, {
      callId: CALL_ID,
      error: false,
      detailsJson: '{}',
      contents,
    })
    expect(() => encodeBinary(completedWithResult(result))).toThrow(/64 items/)
  })
})

describe('upgrade payload', () => {
  it('accepts only a positive int32 target protocol version and enforces url/sha co-presence', () => {
    const upgrade = upgradeRequired()
    expect(validateUpgrade({ ...upgrade, targetProtocolVersion: 2147483647 }).targetProtocolVersion).toBe(
      2147483647,
    )

    for (const targetProtocolVersion of [undefined, 0, -1, 1.5, 2147483648]) {
      expect(() => validateUpgrade({ ...upgrade, targetProtocolVersion })).toThrow(
        /targetProtocolVersion must be in 1\.\.2147483647/,
      )
    }

    expect(validateUpgrade({ targetProtocolVersion: 1 })).toBeDefined()
    expect(() => validateUpgrade({ targetProtocolVersion: 1, artifactUrl: upgrade.artifactUrl })).toThrow(
      /must both be present or both absent/,
    )
    expect(() => validateUpgrade({ targetProtocolVersion: 1, artifactSha256: upgrade.artifactSha256 })).toThrow(
      /must both be present or both absent/,
    )
  })
})

describe('control framing', () => {
  it('accepts exactly 16 MiB and rejects the adjacent boundaries', () => {
    const message = new Uint8Array(MAX_MESSAGE_BYTES)
    message[0] = 1
    message[message.length - 1] = 2
    const framed = frame(message)
    expect(framed.length).toBe(MAX_MESSAGE_BYTES + 4)
    expect(framed.slice(0, 4)).toEqual(new Uint8Array([1, 0, 0, 0]))
    assertBytesEqual(unframe(framed), message)

    expect(() => frame(new Uint8Array())).toThrow(/1\.\./)
    expect(() => frame(new Uint8Array(MAX_MESSAGE_BYTES + 1))).toThrow(/1\.\./)
    expect(() => unframe(new Uint8Array([0, 0, 0, 0]))).toThrow(/1\.\./)
    expect(() => unframe(new Uint8Array([0, 0, 0, 4, 1, 2, 3]))).toThrow(/does not match/)
    expect(() => unframe(new Uint8Array([0, 0, 0, 3, 1, 2, 3, 4]))).toThrow(/does not match/)
    expect(() => unframe(new Uint8Array([1, 0, 0, 1]))).toThrow(/1\.\./)
  })

  it('uses a big-endian prefix for small messages', () => {
    expect(frame(new Uint8Array([1, 2, 3]))).toEqual(new Uint8Array([0, 0, 0, 3, 1, 2, 3]))
  })
})

function envelope(messageType: MessageType, invoke: Invoke) {
  return create(EnvelopeSchema, {
    messageType,
    environmentId: ENVIRONMENT_ID,
    invocationId: INVOCATION_ID,
    payload: { case: 'invoke', value: invoke },
  })
}

function completedWithResource(resource: ResourceContent) {
  return completedWithResult(
    create(CapabilityResultSchema, {
      callId: CALL_ID,
      error: false,
      detailsJson: '{}',
      contents: [
        create(ContentSchema, {
          body: { case: 'resource', value: resource },
        }),
      ],
    }),
  )
}

function completedWithResult(result: CapabilityResult) {
  return create(EnvelopeSchema, {
    messageType: MessageType.COMPLETED,
    environmentId: ENVIRONMENT_ID,
    invocationId: INVOCATION_ID,
    payload: {
      case: 'completed',
      value: create(CompletedSchema, { result }),
    },
  })
}
