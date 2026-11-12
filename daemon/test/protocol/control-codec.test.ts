import { create, toBinary } from '@bufbuild/protobuf'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'

import { describe, expect, it } from 'vitest'

import {
  Architecture,
  CancelSchema,
  CancelledSchema,
  CapabilityResultSchema,
  CompletedSchema,
  ContentSchema,
  EnvelopeSchema,
  ImageInputTier,
  EnvironmentConfigSchema,
  DaemonConfigurationSchema,
  LspConfigurationSchema,
  LspServerSchema,
  ConfigurationStatusSchema,
  ConfigurationErrorSchema,
  HostInfoSchema,
  ErrorSchema,
  ExecutionExitedSchema,
  ExpectedFileSchema,
  FailedSchema,
  HeartbeatSchema,
  IdeAction,
  IdeCommandSchema,
  IdeDirectoryListingSchema,
  IdeDirectoryEntrySchema,
  IdeLeaseBindingSchema,
  IdeLeaseSchema,
  IdeOpenMode,
  IdeResultSchema,
  IdeViewState,
  IdeViewSummarySchema,
  InvokeSchema,
  JsonContentSchema,
  MessageType,
  OperatingSystem,
  PresignedGetSchema,
  PresignedPutSchema,
  ProgressSchema,
  ReadySchema,
  RecoverNotFoundSchema,
  RecoverSchema,
  ResourceBudgetsSchema,
  ResourceContentSchema,
  ResourceDownloadRequestSchema,
  ResourceDownloadTicketSchema,
  ResourceUploadCommitSchema,
  ResourceUploadRequestSchema,
  ResourceUploadTicketSchema,
  RuntimeBodySchema,
  RuntimeSnapshotSchema,
  RuntimeStatusSchema,
  SettingsSchema,
  StartedSchema,
  TemporaryResourcePolicySchema,
  TextContentSchema,
  TicketState,
  UpdatePhase,
  UpdateProgressSchema,
  UpdateResultSchema,
  UpdateSchema,
  UpgradeRequiredSchema,
  type CapabilityResult,
  type Envelope,
  type IdeResult,
  type Invoke,
  type ResourceContent,
} from '../../src/generated/control_pb.js'
import {
  decodeFrame,
  encodeFrame,
  PROTOCOL_VERSION,
  validate,
  validateUpgrade,
} from '../../src/protocol/control/codec.js'
import { frame, unframe } from '../../src/protocol/control/framing.js'
import {
  MAX_MESSAGE_BYTES,
  MAX_SAFE_BIGINT,
  parseJsonObject,
  parseJsonValue,
  requireSafeLong,
} from '../../src/protocol/control/runtime.js'
import {
  ARGUMENTS_JSON,
  CALL_ID,
  CAPABILITY_ID,
  DETAILS_JSON,
  ENVIRONMENT_ID,
  INVOCATION_ID,
  JSON_CONTENT,
  TIMEOUT_MILLIS,
  completedEnvelope,
  executionExitedEnvelope,
  invokeEnvelope,
  upgradeRequired,
  GOLDEN_FIXTURES,
} from './control-fixtures.mjs'

const GOLDEN = fileURLToPath(
  new URL('../../../platform/daemon-server/src/main/protocol/golden/', import.meta.url),
)

// Golden fixtures contain the protobuf payload; production transport uses framed messages.
function encodeBinary(envelope: Envelope): Uint8Array {
  return unframe(encodeFrame(envelope))
}

function decodeBinary(bytes: Uint8Array): Envelope {
  return decodeFrame(frame(bytes))
}

const DAEMON_INSTANCE = 'a1a1a1a1-b2b2-4c3c-8d4d-e5e5e5e5e5e5'

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
    argumentsJson: ARGUMENTS_JSON,
    timeoutMillis: TIMEOUT_MILLIS,
    maxOutputBytes: 16n * 1024n * 1024n,
    imageInputTier: ImageInputTier.P720,
  })
}

describe('control binary codec', () => {
  it('pins the public control protocol version to v4', () => {
    expect(PROTOCOL_VERSION).toBe(4)
  })

  it('roundtrips directory listing and mobile actions with their payloads', () => {
    // Verify the action fields survive validation and binary serialization.
    for (const action of [IdeAction.LIST_DIRECTORIES, IdeAction.COLLAPSE_MOBILE]) {
      const command = create(EnvelopeSchema, {
        messageType: MessageType.IDE_COMMAND, environmentId: ENVIRONMENT_ID, invocationId: INVOCATION_ID,
        payload: { case: 'ideCommand', value: create(IdeCommandSchema, action === IdeAction.LIST_DIRECTORIES
          ? { action, directory: '/workspace' }
          : { action, viewId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
            browserSessionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7', bindingEpoch: 1n,
            expectedDaemonInstanceId: DAEMON_INSTANCE }) },
      })
      expect(decodeBinary(encodeBinary(command))).toEqual(command)
    }
    const result = create(EnvelopeSchema, {
      messageType: MessageType.IDE_RESULT, environmentId: ENVIRONMENT_ID, invocationId: INVOCATION_ID,
      payload: { case: 'ideResult', value: create(IdeResultSchema, {
        ok: true, directoryListing: create(IdeDirectoryListingSchema, {
          path: '/workspace', parentPath: '/', truncated: true,
          children: [create(IdeDirectoryEntrySchema, { name: 'src', path: '/workspace/src' })],
        }),
      }) },
    })
    expect(decodeBinary(encodeBinary(result))).toEqual(result)
  })

  it('encodes the shared fixtures to golden byte-for-byte', () => {
    for (const [name, factory] of GOLDEN_FIXTURES) {
      assertBytesEqual(encodeBinary(factory()), readGolden(name))
    }
  })

  it('roundtrips shared golden through bare and framed paths', () => {
    for (const [name] of GOLDEN_FIXTURES) {
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
      argumentsJson: '{"a":1,"a":2}',
      timeoutMillis: TIMEOUT_MILLIS,
    })
    expect(() => encodeBinary(envelope(MessageType.INVOKE, duplicate))).toThrow(/duplicate field/)

    const invalid = create(InvokeSchema, {
      capabilityId: CAPABILITY_ID,
      argumentsJson: '{"a":}',
      timeoutMillis: TIMEOUT_MILLIS,
    })
    expect(() => encodeBinary(envelope(MessageType.INVOKE, invalid))).toThrow(/valid JSON/)

    const missing = create(InvokeSchema, {
      argumentsJson: '{}',
      timeoutMillis: 0n,
    })
    expect(() => encodeBinary(envelope(MessageType.INVOKE, missing))).toThrow(/capabilityId/)
  })

  it('requires a supported image input tier on each invoke', () => {
    const invalidTiers = [
      undefined,
      ImageInputTier.IMAGE_INPUT_TIER_UNSPECIFIED,
      (ImageInputTier.ORIGINAL + 1) as ImageInputTier,
    ]
    for (const imageInputTier of invalidTiers) {
      const invoke = create(InvokeSchema, { ...validInvoke(), imageInputTier })
      expect(() => encodeBinary(envelope(MessageType.INVOKE, invoke))).toThrow(/imageInputTier/)
    }
  })

  it('rejects unsafe integers, UTF-8 overflow and invalid resource metadata', () => {
    expect(() =>
      encodeBinary(
        envelope(
          MessageType.INVOKE,
          create(InvokeSchema, {
            capabilityId: CAPABILITY_ID,
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

  it('validates SETTINGS bounds', () => {
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.SETTINGS,
          environmentId: ENVIRONMENT_ID,
          payload: { case: 'settings', value: create(SettingsSchema, {}) },
        }),
      ),
    ).toThrow(/resourceBudgets is required/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.INVOKE,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: { case: 'invoke', value: create(InvokeSchema, { ...validInvoke(), maxOutputBytes: 0n }) },
        }),
      ),
    ).toThrow(/maxOutputBytes/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.SETTINGS,
          environmentId: ENVIRONMENT_ID,
          invocationId: 'inv-bad',
          payload: {
            case: 'settings',
            value: create(SettingsSchema, {
              resourceBudgets: create(ResourceBudgetsSchema, {
                temporaryResourceTtlSeconds: 60n,
                temporaryResourceCleanupIntervalSeconds: 30n,
              }),
            }),
          },
        }),
      ),
    ).toThrow(/must not declare invocationId/)
  })

  it('validates ENVIRONMENT_CONFIG as a complete snapshot', () => {
    const config = create(DaemonConfigurationSchema, {
      studioUrl: 'https://studio.example.com',
      bashExecutable: '/usr/bin/bash',
      proxyUrl: 'http://127.0.0.1:7890',
      lsp: create(LspConfigurationSchema, {
        servers: [
          create(LspServerSchema, {
            id: 'java',
            command: ['java'],
            extensions: ['.java'],
            rootMarkers: ['pom.xml'],
            firstMatchMarkers: ['src/main/java'],
          }),
        ],
      }),
    })
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.ENVIRONMENT_CONFIG,
          environmentId: ENVIRONMENT_ID,
          payload: { case: 'environmentConfig', value: create(EnvironmentConfigSchema, { config }) },
        }),
      ),
    ).toThrow(/version is required/)
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.ENVIRONMENT_CONFIG,
          environmentId: ENVIRONMENT_ID,
          payload: { case: 'environmentConfig', value: create(EnvironmentConfigSchema, { version: 4n }) },
        }),
      ),
    ).toThrow(/config is required/)
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.ENVIRONMENT_CONFIG,
          environmentId: ENVIRONMENT_ID,
          invocationId: 'env-config',
          payload: {
            case: 'environmentConfig',
            value: create(EnvironmentConfigSchema, { version: 4n, config }),
          },
        }),
      ),
    ).toThrow(/must not declare invocationId/)
    const invalidCases: Array<[string, any, RegExp]> = [
      ['studio', create(DaemonConfigurationSchema, { studioUrl: 'ftp://studio.example.com' }), /studioUrl/],
      [
        'proxy',
        create(DaemonConfigurationSchema, { studioUrl: 'https://a.example.com', proxyUrl: 'https://a:1' }),
        /proxyUrl/,
      ],
      [
        'lsp',
        create(DaemonConfigurationSchema, {
          studioUrl: 'https://a.example.com',
          lsp: create(LspConfigurationSchema, {
            servers: [create(LspServerSchema, { id: 'java', command: ['java'], extensions: ['java'] })],
          }),
        }),
        /leading dot/,
      ],
    ]
    for (const [name, value, expected] of invalidCases) {
      expect(() =>
        encodeBinary(
          create(EnvelopeSchema, {
            messageType: MessageType.ENVIRONMENT_CONFIG,
            environmentId: ENVIRONMENT_ID,
            payload: { case: 'environmentConfig', value: create(EnvironmentConfigSchema, { version: 4n, config: value }) },
          }),
        ),
        name,
      ).toThrow(expected)
    }
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.ENVIRONMENT_CONFIG,
          environmentId: ENVIRONMENT_ID,
          payload: {
            case: 'environmentConfig',
            value: create(EnvironmentConfigSchema, {
              version: 4n,
              config: create(DaemonConfigurationSchema, {
                studioUrl: 'https://a.example.com',
                lsp: create(LspConfigurationSchema, {
                  servers: [
                    create(LspServerSchema, { id: 'java', command: ['java'], extensions: ['.java'] }),
                    create(LspServerSchema, { id: 'java', command: ['java'], extensions: ['.java'] }),
                  ],
                }),
              }),
            }),
          },
        }),
      ),
    ).toThrow(/repeat id/)
  })

  it('validates runtime configuration status bounds', () => {
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.RUNTIME_STATUS,
          environmentId: ENVIRONMENT_ID,
          payload: {
            case: 'runtimeStatus',
            value: create(RuntimeStatusSchema, {
              runtimeStatus: create(RuntimeSnapshotSchema, {
                revision: 1n,
                status: create(RuntimeBodySchema, {
                  daemonVersion: '1.0.0',
                  configuration: create(ConfigurationStatusSchema, {
                    appliedVersion: 3n,
                    proxyUrl: 'ftp://invalid',
                  }),
                }),
              }),
            }),
          },
        }),
      ),
    ).toThrow(/configuration\.proxyUrl/)
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.RUNTIME_STATUS,
          environmentId: ENVIRONMENT_ID,
          payload: {
            case: 'runtimeStatus',
            value: create(RuntimeStatusSchema, {
              runtimeStatus: create(RuntimeSnapshotSchema, {
                revision: 1n,
                status: create(RuntimeBodySchema, {
                  daemonVersion: '1.0.0',
                  configuration: create(ConfigurationStatusSchema, {
                    error: create(ConfigurationErrorSchema, { code: 'VALIDATION_FAILED' }),
                  }),
                }),
              }),
            }),
          },
        }),
      ),
    ).toThrow(/error\.version/)
  })

  it('validates READY bounds', () => {
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.READY,
          environmentId: ENVIRONMENT_ID,
          payload: {
            case: 'ready',
            value: create(ReadySchema, { daemonVersion: '1.0.0' }),
          },
        }),
      ),
    ).toThrow(/ready\.hostInfo is required/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.READY,
          environmentId: ENVIRONMENT_ID,
          payload: {
            case: 'ready',
            value: create(ReadySchema, {
              daemonVersion: '1.0.0',
              hostInfo: create(HostInfoSchema, {
                operatingSystem: OperatingSystem.LINUX,
                architecture: Architecture.AMD64,
                timeZone: 'Invalid/Zone',
                userName: 'test',
                homeDirectory: '/home/test',
                tempDirectory: '/tmp',
              }),
              runtimeStatus: create(RuntimeSnapshotSchema, {
                revision: 1n,
                status: create(RuntimeBodySchema, { daemonVersion: '1.0.0' }),
              }),
            }),
          },
        }),
      ),
    ).toThrow(/valid ZoneId/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.READY,
          environmentId: ENVIRONMENT_ID,
          payload: {
            case: 'ready',
            value: create(ReadySchema, {
              daemonVersion: '1.0.0',
              hostInfo: create(HostInfoSchema, {
                operatingSystem: OperatingSystem.LINUX,
                architecture: Architecture.AMD64,
                timeZone: 'UTC',
                userName: 'test',
                homeDirectory: '/home/test',
                tempDirectory: '/tmp',
              }),
              runtimeStatus: create(RuntimeSnapshotSchema, {
                revision: 1n,
                status: create(RuntimeBodySchema, { daemonVersion: '5.0.0' }),
              }),
            }),
          },
        }),
      ),
    ).toThrow(/must match ready\.daemonVersion/)
  })

  it('validates HEARTBEAT and RUNTIME_STATUS bounds', () => {
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.HEARTBEAT,
          environmentId: ENVIRONMENT_ID,
          payload: { case: 'heartbeat', value: create(HeartbeatSchema, {}) },
        }),
      ),
    ).toThrow(/runtimeStatus is required/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.HEARTBEAT,
          environmentId: ENVIRONMENT_ID,
          payload: {
            case: 'heartbeat',
            value: create(HeartbeatSchema, {
              runtimeStatus: create(RuntimeSnapshotSchema, {
                revision: 0n,
                status: create(RuntimeBodySchema, { daemonVersion: '1.0.0' }),
              }),
            }),
          },
        }),
      ),
    ).toThrow(/revision/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.RUNTIME_STATUS,
          environmentId: ENVIRONMENT_ID,
          payload: {
            case: 'runtimeStatus',
            value: create(RuntimeStatusSchema, {
              runtimeStatus: create(RuntimeSnapshotSchema, {
                revision: 1n,
                status: create(RuntimeBodySchema, {
                  daemonVersion: '1.0.0',
                  update: create(UpdateProgressSchema, {
                    targetVersion: '1.1.0',
                    downloadedBytes: 100n,
                    totalBytes: 50n,
                  }),
                }),
              }),
            }),
          },
        }),
      ),
    ).toThrow(/totalBytes/)
  })

  it('validates INVOKE and STARTED bounds', () => {
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.INVOKE,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'invoke',
            value: create(InvokeSchema, {
              capabilityId: 'Bad_Capability!',
              argumentsJson: '{}',
              timeoutMillis: 0n,
            }),
          },
        }),
      ),
    ).toThrow(/invalid syntax/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.INVOKE,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'invoke',
            value: create(InvokeSchema, {
              capabilityId: 'fs.read',
              argumentsJson: '{}',
              timeoutMillis: 0n,
              maxOutputBytes: 16n * 1024n * 1024n,
              imageInputTier: ImageInputTier.P720,
              expectedFile: create(ExpectedFileSchema, {
                path: 'test.txt',
                sha256: 'a'.repeat(64),
              }),
            }),
          },
        }),
      ),
    ).toThrow(/only valid for fs\.edit/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.STARTED,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'started',
            value: create(StartedSchema, { replayed: false }),
          },
        }),
      ),
    ).toThrow(/started\.replayed must be true/)
  })

  it('validates PROGRESS, FAILED, and CANCELLED bounds', () => {
    const progressWithResource = create(EnvelopeSchema, {
      messageType: MessageType.PROGRESS,
      environmentId: ENVIRONMENT_ID,
      invocationId: INVOCATION_ID,
      payload: {
        case: 'progress',
        value: create(ProgressSchema, {
          result: create(CapabilityResultSchema, {
            callId: CALL_ID,
            error: false,
            detailsJson: '{}',
            contents: [
              create(ContentSchema, {
                body: {
                  case: 'resource',
                  value: create(ResourceContentSchema, {
                    uploadId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
                    mediaType: 'text/plain',
                    size: 10n,
                    sha256: 'b'.repeat(64),
                  }),
                },
              }),
            ],
          }),
        }),
      },
    })
    expect(() => encodeBinary(progressWithResource)).toThrow(/must not declare a resource/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.FAILED,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: { case: 'failed', value: create(FailedSchema, { message: '' }) },
        }),
      ),
    ).toThrow(/failed\.message/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.CANCELLED,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: { case: 'cancelled', value: create(CancelledSchema, { reason: '   ' }) },
        }),
      ),
    ).toThrow(/cancelled\.reason/)
  })

  it('validates UPLOAD tickets and commits', () => {
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.RESOURCE_UPLOAD_TICKET,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'resourceUploadTicket',
            value: create(ResourceUploadTicketSchema, {
              transferId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              state: TicketState.TICKET_PENDING,
              uploadId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              message: 'should not be here',
              presignedPut: create(PresignedPutSchema, {
                method: 'PUT',
                url: 'https://example.com/upload',
                expiresAtEpochMs: 1795411200000n,
              }),
            }),
          },
        }),
      ),
    ).toThrow(/PENDING ticket requires uploadId and presignedPut only/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.RESOURCE_UPLOAD_TICKET,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'resourceUploadTicket',
            value: create(ResourceUploadTicketSchema, {
              transferId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              state: TicketState.TICKET_PENDING,
              uploadId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              presignedPut: create(PresignedPutSchema, {
                method: 'POST',
                url: 'https://example.com/upload',
                expiresAtEpochMs: 1795411200000n,
              }),
            }),
          },
        }),
      ),
    ).toThrow(/presignedPut\.method must be PUT/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.RESOURCE_UPLOAD_TICKET,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'resourceUploadTicket',
            value: create(ResourceUploadTicketSchema, {
              transferId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              state: TicketState.TICKET_PENDING,
              uploadId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              presignedPut: create(PresignedPutSchema, {
                method: 'PUT',
                url: 'https://example.com/upload',
                expiresAtEpochMs: 0n,
              }),
            }),
          },
        }),
      ),
    ).toThrow(/presignedPut\.expiresAtEpochMs/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.RESOURCE_UPLOAD_COMMIT,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'resourceUploadCommit',
            value: create(ResourceUploadCommitSchema, {
              transferId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
            }),
          },
        }),
      ),
    ).toThrow(/uploadCommit\.uploadId/)
  })

  it('validates DOWNLOAD requests and tickets', () => {
    // 请求必须携带 transferId，但不能自行选择归档。
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.RESOURCE_DOWNLOAD_REQUEST,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'resourceDownloadRequest',
            value: create(ResourceDownloadRequestSchema, {}),
          },
        }),
      ),
    ).toThrow(/downloadRequest\.transferId/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.RESOURCE_DOWNLOAD_REQUEST,
          environmentId: ENVIRONMENT_ID,
          payload: {
            case: 'resourceDownloadRequest',
            value: create(ResourceDownloadRequestSchema, {
              transferId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
            }),
          },
        }),
      ),
    ).toThrow(/invocationId/)

    // READY 票据不允许携带 message。
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.RESOURCE_DOWNLOAD_TICKET,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'resourceDownloadTicket',
            value: create(ResourceDownloadTicketSchema, {
              transferId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              state: TicketState.TICKET_READY,
              message: 'should not be here',
              size: 4n,
              sha256: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
              presignedGet: create(PresignedGetSchema, {
                method: 'GET',
                url: 'https://example.com/blob',
                expiresAtEpochMs: 1795411200000n,
              }),
            }),
          },
        }),
      ),
    ).toThrow(/READY download ticket must not declare a message/)

    // READY 票据的 presignedGet.method 必须是 GET。
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.RESOURCE_DOWNLOAD_TICKET,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'resourceDownloadTicket',
            value: create(ResourceDownloadTicketSchema, {
              transferId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              state: TicketState.TICKET_READY,
              size: 4n,
              sha256: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
              presignedGet: create(PresignedGetSchema, {
                method: 'POST',
                url: 'https://example.com/blob',
                expiresAtEpochMs: 1795411200000n,
              }),
            }),
          },
        }),
      ),
    ).toThrow(/presignedGet\.method must be GET/)

    // FAILED 票据只允许 message。
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.RESOURCE_DOWNLOAD_TICKET,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'resourceDownloadTicket',
            value: create(ResourceDownloadTicketSchema, {
              transferId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              state: TicketState.TICKET_FAILED,
              message: 'blob expired',
              size: 4n,
            }),
          },
        }),
      ),
    ).toThrow(/FAILED download ticket requires a message only/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.RESOURCE_DOWNLOAD_TICKET,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'resourceDownloadTicket',
            value: create(ResourceDownloadTicketSchema, {
              transferId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              state: TicketState.TICKET_FAILED,
            }),
          },
        }),
      ),
    ).toThrow(/downloadTicket\.message/)

    // 缺少 state 直接拒绝。
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.RESOURCE_DOWNLOAD_TICKET,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'resourceDownloadTicket',
            value: create(ResourceDownloadTicketSchema, {
              transferId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
            }),
          },
        }),
      ),
    ).toThrow(/downloadTicket\.state is required/)
  })

  it('validates UPDATE and POLICY bounds', () => {
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.TEMPORARY_RESOURCE_POLICY,
          environmentId: ENVIRONMENT_ID,
          payload: {
            case: 'temporaryResourcePolicy',
            value: create(TemporaryResourcePolicySchema, {
              temporaryResourceTtlSeconds: 0n,
              temporaryResourceCleanupIntervalSeconds: 30n,
            }),
          },
        }),
      ),
    ).toThrow(/temporaryResourceTtlSeconds/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.UPDATE,
          environmentId: ENVIRONMENT_ID,
          payload: {
            case: 'update',
            value: create(UpdateSchema, {
              operationId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              targetVersion: '../invalid',
            }),
          },
        }),
      ),
    ).toThrow(/targetVersion/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.UPDATE_RESULT,
          environmentId: ENVIRONMENT_ID,
          payload: {
            case: 'updateResult',
            value: create(UpdateResultSchema, {
              operationId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              phase: UpdatePhase.UPDATE_PHASE_UNSPECIFIED,
            }),
          },
        }),
      ),
    ).toThrow(/updateResult\.phase is required/)
  })

  it('validates IDE_COMMAND, IDE_RESULT, IDE_LEASE, and ERROR bounds', () => {
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.IDE_COMMAND,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'ideCommand',
            value: create(IdeCommandSchema, { action: IdeAction.IDE_ACTION_UNSPECIFIED }),
          },
        }),
      ),
    ).toThrow(/ideCommand\.action is required/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.IDE_COMMAND,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'ideCommand',
            value: create(IdeCommandSchema, {
              action: IdeAction.OPEN_VIEW,
              viewId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              browserSessionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              eventNodeId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              connectionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              claimId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              mode: IdeOpenMode.CREATE,
              bindingEpoch: 1n,
              expectedBindingEpoch: 0n,
              expectedDaemonInstanceId: DAEMON_INSTANCE,
            }),
          },
        }),
      ),
    ).toThrow(/OPEN_VIEW must not declare bindingEpoch/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.IDE_COMMAND,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'ideCommand',
            value: create(IdeCommandSchema, {
              action: IdeAction.CHANGE_DIRECTORY,
              viewId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              browserSessionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              bindingEpoch: 1n,
              expectedDaemonInstanceId: DAEMON_INSTANCE,
            }),
          },
        }),
      ),
    ).toThrow(/ideCommand\.directory is required/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.IDE_COMMAND,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'ideCommand',
            value: create(IdeCommandSchema, {
              action: IdeAction.CLOSE_VIEW,
              viewId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              browserSessionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              bindingEpoch: 1n,
              expectedDaemonInstanceId: DAEMON_INSTANCE,
            }),
          },
        }),
      ),
    ).toThrow(/ideCommand\.discard is required/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.IDE_RESULT,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'ideResult',
            value: create(IdeResultSchema, {
              ok: false,
              code: 'VIEW_GONE',
              message: 'gone',
              viewId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
            }),
          },
        }),
      ),
    ).toThrow(/failed IDE result must only declare code and message/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.IDE_RESULT,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'ideResult',
            value: create(IdeResultSchema, {
              ok: true,
              code: 'VIEW_GONE',
            }),
          },
        }),
      ),
    ).toThrow(/successful IDE result must not declare code or message/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.IDE_LEASE,
          environmentId: ENVIRONMENT_ID,
          payload: {
            case: 'ideLease',
            value: create(IdeLeaseSchema, { round: 0n }),
          },
        }),
      ),
    ).toThrow(/ideLease\.round/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.IDE_LEASE,
          environmentId: ENVIRONMENT_ID,
          payload: {
            case: 'ideLease',
            value: create(IdeLeaseSchema, {
              round: 1n,
              bindings: [
                create(IdeLeaseBindingSchema, {
                  viewId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
                  browserSessionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
                  bindingEpoch: 1n,
                }),
                create(IdeLeaseBindingSchema, {
                  viewId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
                  browserSessionId: '7c9e6679-7425-40de-944b-e07fc1f90ae8',
                  bindingEpoch: 1n,
                }),
              ],
            }),
          },
        }),
      ),
    ).toThrow(/must not repeat viewId/)

    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.ERROR,
          environmentId: ENVIRONMENT_ID,
          payload: { case: 'error', value: create(ErrorSchema, { message: '' }) },
        }),
      ),
    ).toThrow(/error\.message/)
  })

  it('validates Unicode and JSON text parsing rules', () => {
    // 孤立高位代理
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.INVOKE,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'invoke',
            value: create(InvokeSchema, {
              capabilityId: 'fs.read',
              argumentsJson: '{"file": "\ud800"}',
              timeoutMillis: 0n,
            }),
          },
        }),
      ),
    ).toThrow(/must be valid Unicode/)

    // 孤立低位代理
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.INVOKE,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'invoke',
            value: create(InvokeSchema, {
              capabilityId: 'fs.read',
              argumentsJson: '{"file": "\udc00"}',
              timeoutMillis: 0n,
            }),
          },
        }),
      ),
    ).toThrow(/must be valid Unicode/)

    // JSON 包含重复属性
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.INVOKE,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'invoke',
            value: create(InvokeSchema, {
              capabilityId: 'fs.read',
              argumentsJson: '{"a": 1, "a": 2}',
              timeoutMillis: 0n,
            }),
          },
        }),
      ),
    ).toThrow(/contains duplicate field/)

    // JSON 语法错误
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.INVOKE,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'invoke',
            value: create(InvokeSchema, {
              capabilityId: 'fs.read',
              argumentsJson: '{not_json}',
              timeoutMillis: 0n,
            }),
          },
        }),
      ),
    ).toThrow(/must be valid JSON/)

    // JSON 非对象（数字/数组）
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.INVOKE,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'invoke',
            value: create(InvokeSchema, {
              capabilityId: 'fs.read',
              argumentsJson: '[1, 2, 3]',
              timeoutMillis: 0n,
            }),
          },
        }),
      ),
    ).toThrow(/must be a JSON object/)

    // JSON 为空
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.INVOKE,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'invoke',
            value: create(InvokeSchema, {
              capabilityId: 'fs.read',
              argumentsJson: '   ',
              timeoutMillis: 0n,
            }),
          },
        }),
      ),
    ).toThrow(/is required/)

    // ContentSchema JSON 非法
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.COMPLETED,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'completed',
            value: create(CompletedSchema, {
              result: create(CapabilityResultSchema, {
                callId: CALL_ID,
                error: false,
                detailsJson: '{}',
                contents: [
                  create(ContentSchema, {
                    body: {
                      case: 'json',
                      value: create(JsonContentSchema, { json: 'not-json' }),
                    },
                  }),
                ],
              }),
            }),
          },
        }),
      ),
    ).toThrow(/must be valid JSON/)

    // requireSingleLine 包含换行或首尾空格
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.READY,
          environmentId: ENVIRONMENT_ID,
          payload: {
            case: 'ready',
            value: create(ReadySchema, {
              daemonVersion: '1.0.0',
              hostInfo: create(HostInfoSchema, {
                operatingSystem: OperatingSystem.LINUX,
                architecture: Architecture.AMD64,
                timeZone: 'UTC',
                userName: ' user ',
                homeDirectory: '/home/test',
                tempDirectory: '/tmp',
              }),
              runtimeStatus: create(RuntimeSnapshotSchema, {
                revision: 1n,
                status: create(RuntimeBodySchema, { daemonVersion: '1.0.0' }),
              }),
            }),
          },
        }),
      ),
    ).toThrow(/single line without surrounding whitespace/)

    // requireIdePath 包含控制字符
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.IDE_COMMAND,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'ideCommand',
            value: create(IdeCommandSchema, {
              action: IdeAction.OPEN_VIEW,
              viewId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              browserSessionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              eventNodeId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              connectionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              claimId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              mode: IdeOpenMode.CREATE,
              expectedBindingEpoch: 0n,
              expectedDaemonInstanceId: DAEMON_INSTANCE,
              directory: '/bad\x00path',
            }),
          },
        }),
      ),
    ).toThrow(/must not contain control characters/)
  })

  it('validates and roundtrips IDE commands and results across all actions', () => {
    // FOCUS_TERMINAL
    const focusCmd = create(EnvelopeSchema, {
      messageType: MessageType.IDE_COMMAND,
      environmentId: ENVIRONMENT_ID,
      invocationId: INVOCATION_ID,
      payload: {
        case: 'ideCommand',
        value: create(IdeCommandSchema, {
          action: IdeAction.FOCUS_TERMINAL,
          viewId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
          browserSessionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
          bindingEpoch: 1n,
          expectedDaemonInstanceId: DAEMON_INSTANCE,
        }),
      },
    })
    const focusDecoded = decodeBinary(encodeBinary(focusCmd))
    expect(focusDecoded.payload.case).toBe('ideCommand')

    // DETACH_VIEW 已退役：raw enum value 5 不能再通过出站校验
    const detachCmd = create(EnvelopeSchema, {
      messageType: MessageType.IDE_COMMAND,
      environmentId: ENVIRONMENT_ID,
      invocationId: INVOCATION_ID,
      payload: {
        case: 'ideCommand',
        value: create(IdeCommandSchema, {
          action: 5 as IdeAction,
          viewId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
          browserSessionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
          bindingEpoch: 1n,
        }),
      },
    })
    expect(() => encodeBinary(detachCmd)).toThrow(/ideCommand.action is required/)

    // LIST_VIEWS
    const listCmd = create(EnvelopeSchema, {
      messageType: MessageType.IDE_COMMAND,
      environmentId: ENVIRONMENT_ID,
      invocationId: INVOCATION_ID,
      payload: {
        case: 'ideCommand',
        value: create(IdeCommandSchema, {
          action: IdeAction.LIST_VIEWS,
        }),
      },
    })
    expect(decodeBinary(encodeBinary(listCmd)).payload.case).toBe('ideCommand')

    // CLOSE_VIEW with discard: true
    const closeCmd = create(EnvelopeSchema, {
      messageType: MessageType.IDE_COMMAND,
      environmentId: ENVIRONMENT_ID,
      invocationId: INVOCATION_ID,
      payload: {
        case: 'ideCommand',
        value: create(IdeCommandSchema, {
          action: IdeAction.CLOSE_VIEW,
          viewId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
          browserSessionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
          bindingEpoch: 1n,
          expectedDaemonInstanceId: DAEMON_INSTANCE,
          discard: true,
        }),
      },
    })
    expect(decodeBinary(encodeBinary(closeCmd)).payload.case).toBe('ideCommand')

    // IDE_RESULT with views list
    const listResult = create(EnvelopeSchema, {
      messageType: MessageType.IDE_RESULT,
      environmentId: ENVIRONMENT_ID,
      invocationId: INVOCATION_ID,
      payload: {
        case: 'ideResult',
        value: create(IdeResultSchema, {
          ok: true,
          revision: 1n,
          views: [
            create(IdeViewSummarySchema, {
              viewId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              browserSessionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              eventNodeId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              connectionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              claimId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
              bindingEpoch: 1n,
              directory: '/workspace/dir',
              workspacePath: '/workspace/dir/project.code-workspace',
              state: IdeViewState.OPEN,
            }),
          ],
        }),
      },
    })
    expect(decodeBinary(encodeBinary(listResult)).payload.case).toBe('ideResult')

    // IDE_RESULT with change directory
    const dirResult = create(EnvelopeSchema, {
      messageType: MessageType.IDE_RESULT,
      environmentId: ENVIRONMENT_ID,
      invocationId: INVOCATION_ID,
      payload: {
        case: 'ideResult',
        value: create(IdeResultSchema, {
          ok: true,
          viewId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
          directory: '/workspace/new-dir',
        }),
      },
    })
    expect(decodeBinary(encodeBinary(dirResult)).payload.case).toBe('ideResult')

    // IDE_RESULT with single viewId
    const viewResult = create(EnvelopeSchema, {
      messageType: MessageType.IDE_RESULT,
      environmentId: ENVIRONMENT_ID,
      invocationId: INVOCATION_ID,
      payload: {
        case: 'ideResult',
        value: create(IdeResultSchema, {
          ok: true,
          viewId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
        }),
      },
    })
    expect(decodeBinary(encodeBinary(viewResult)).payload.case).toBe('ideResult')

    // IDE_RESULT with full binding
    const boundResult = create(EnvelopeSchema, {
      messageType: MessageType.IDE_RESULT,
      environmentId: ENVIRONMENT_ID,
      invocationId: INVOCATION_ID,
      payload: {
        case: 'ideResult',
        value: create(IdeResultSchema, {
          ok: true,
          viewId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
          browserSessionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
          eventNodeId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
          connectionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
          claimId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
          bindingEpoch: 1n,
          directory: '/workspace/dir',
          workspacePath: '/workspace/dir/project.code-workspace',
        }),
      },
    })
    expect(decodeBinary(encodeBinary(boundResult)).payload.case).toBe('ideResult')

    // IDE_RESULT views list with invalid view.state
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.IDE_RESULT,
          environmentId: ENVIRONMENT_ID,
          invocationId: INVOCATION_ID,
          payload: {
            case: 'ideResult',
            value: create(IdeResultSchema, {
              ok: true,
              revision: 1n,
              views: [
                create(IdeViewSummarySchema, {
                  viewId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
                  browserSessionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
                  eventNodeId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
                  connectionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
                  claimId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
                  bindingEpoch: 1n,
                  directory: '/workspace/dir',
                  workspacePath: '/workspace/dir/project.code-workspace',
                  state: IdeViewState.IDE_VIEW_STATE_UNSPECIFIED,
                }),
              ],
            }),
          },
        }),
      ),
    ).toThrow(/state is required/)

    // IDE_LEASE bindings limit > 64
    const tooManyBindings = Array.from({ length: 65 }, (_, i) =>
      create(IdeLeaseBindingSchema, {
        viewId: `7c9e6679-7425-40de-944b-${String(i).padStart(12, '0')}`,
        browserSessionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
        bindingEpoch: 1n,
      }),
    )
    expect(() =>
      encodeBinary(
        create(EnvelopeSchema, {
          messageType: MessageType.IDE_LEASE,
          environmentId: ENVIRONMENT_ID,
          payload: {
            case: 'ideLease',
            value: create(IdeLeaseSchema, { round: 1n, bindings: tooManyBindings }),
          },
        }),
      ),
    ).toThrow(/must not exceed 64/)
  })

  it('validates upload tickets in READY and FAILED states', () => {
    // READY ticket
    const readyTicket = create(EnvelopeSchema, {
      messageType: MessageType.RESOURCE_UPLOAD_TICKET,
      environmentId: ENVIRONMENT_ID,
      invocationId: INVOCATION_ID,
      payload: {
        case: 'resourceUploadTicket',
        value: create(ResourceUploadTicketSchema, {
          transferId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
          state: TicketState.TICKET_READY,
          uploadId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
        }),
      },
    })
    expect(decodeBinary(encodeBinary(readyTicket)).payload.case).toBe('resourceUploadTicket')

    // FAILED ticket
    const failedTicket = create(EnvelopeSchema, {
      messageType: MessageType.RESOURCE_UPLOAD_TICKET,
      environmentId: ENVIRONMENT_ID,
      invocationId: INVOCATION_ID,
      payload: {
        case: 'resourceUploadTicket',
        value: create(ResourceUploadTicketSchema, {
          transferId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
          state: TicketState.TICKET_FAILED,
          message: 'upload failed due to quota',
        }),
      },
    })
    expect(decodeBinary(encodeBinary(failedTicket)).payload.case).toBe('resourceUploadTicket')
  })

  it('rejects unknown protobuf fields via assertNoUnknown', () => {
    const raw = create(EnvelopeSchema, {
      messageType: MessageType.ERROR,
      environmentId: ENVIRONMENT_ID,
      payload: {
        case: 'error',
        value: create(ErrorSchema, { message: 'boom' }),
      },
    })
    // 注入未知字段
    ;(raw as unknown as { $unknown: unknown[] }).$unknown = [
      { no: 999, wireType: 0, data: new Uint8Array([1]) },
    ]
    expect(() => encodeBinary(raw)).toThrow(/contains unknown protobuf fields/)
  })

  it('directly validates runtime helper error paths', () => {
    expect(() => requireSafeLong(123 as unknown as bigint, 'val', 0n, 100n)).toThrow(/must be an int64/)
    expect(() => parseJsonObject('not json', 'jsonObj', 1024)).toThrow(/must be valid JSON/)
    expect(() => parseJsonValue('not json', 'jsonVal', 1024)).toThrow(/must be valid JSON/)
    expect(() => parseJsonObject('{"a": 1, "a": 2, "b": 3}', 'dupObj', 1024)).toThrow(/contains duplicate field/)
  })
})

describe('upgrade payload', () => {
  it('accepts only the target daemon version', () => {
    const upgrade = upgradeRequired()
    expect(validateUpgrade(upgrade).targetVersion).toBe('1.2.3')

    for (const targetVersion of [undefined, '', ' 1.2.3 ', '1.2.3 bad', 'v'.repeat(65)]) {
      expect(() => validateUpgrade({ ...upgrade, targetVersion })).toThrow(
        /upgradeRequired\.targetVersion/,
      )
    }
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

  it('validates IDE snapshot constraints (revision, view limit, duplicate and order)', () => {
    const makeValidView = (viewId = '7c9e6679-7425-40de-944b-e07fc1f90ae7') =>
      create(IdeViewSummarySchema, {
        viewId,
        browserSessionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
        eventNodeId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
        connectionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
        claimId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
        bindingEpoch: 1n,
        directory: '/workspace/dir',
        workspacePath: '/workspace/dir/project.code-workspace',
        state: IdeViewState.OPEN,
      })
    const resultEnvelope = (result: IdeResult) =>
      create(EnvelopeSchema, {
        messageType: MessageType.IDE_RESULT,
        environmentId: ENVIRONMENT_ID,
        invocationId: INVOCATION_ID,
        payload: { case: 'ideResult', value: result },
      })

    // 1. 快照声明了不完整的绑定字段
    expect(() =>
      validate(resultEnvelope(create(IdeResultSchema, {
        ok: true,
        revision: 1n,
        browserSessionId: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
        views: [makeValidView()],
      }))),
    ).toThrow(/ideResult\.viewId/)

    // 2. views 超过上限 64
    const tooManyViews = Array.from({ length: 65 }, (_, index) =>
      makeValidView(`7c9e6679-7425-40de-944b-${String(index).padStart(12, '0')}`))
    expect(() =>
      validate(resultEnvelope(create(IdeResultSchema, {
        ok: true,
        revision: 1n,
        views: tooManyViews,
      }))),
    ).toThrow(/must not exceed 64/)

    // 3. 重复 viewId
    expect(() =>
      validate(resultEnvelope(create(IdeResultSchema, {
        ok: true,
        revision: 1n,
        views: [
          makeValidView('7c9e6679-7425-40de-944b-e07fc1f90ae7'),
          makeValidView('7c9e6679-7425-40de-944b-e07fc1f90ae7'),
        ],
      }))),
    ).toThrow(/must not repeat viewId/)

    // 4. viewId 必须按快照规范升序
    expect(() =>
      validate(resultEnvelope(create(IdeResultSchema, {
        ok: true,
        revision: 1n,
        views: [
          makeValidView('8c9e6679-7425-40de-944b-e07fc1f90ae7'),
          makeValidView('7c9e6679-7425-40de-944b-e07fc1f90ae7'),
        ],
      }))),
    ).toThrow(/must be sorted by viewId/)

    // 5. views 必须与 revision 同一快照提交
    expect(() =>
      validate(resultEnvelope(create(IdeResultSchema, {
        ok: true,
        views: [makeValidView()],
      }))),
    ).toThrow(/revision is required with views/)
  })

  it('validates IDE snapshot revision and top-level binding invariants', () => {
    const golden = decodeBinary(readGolden('ide-result.bin'))
    if (golden.payload.case !== 'ideResult') throw new Error('fixture must contain an IDE result')
    const bound = golden.payload.value
    const view = bound.views[0]
    if (view === undefined || bound.revision === undefined || bound.bindingEpoch === undefined) {
      throw new Error('fixture must contain a bound snapshot view')
    }
    const resultEnvelope = (value: IdeResult) =>
      create(EnvelopeSchema, {
        messageType: MessageType.IDE_RESULT,
        environmentId: ENVIRONMENT_ID,
        invocationId: INVOCATION_ID,
        payload: { case: 'ideResult', value },
      })
    const resultWith = (changes: Partial<IdeResult>): IdeResult =>
      create(IdeResultSchema, { ...bound, ...changes })

    // revision 可以大于已提交 bindingEpoch；summary epoch 不得超前 revision。
    validate(resultEnvelope(resultWith({ revision: bound.revision + 1n })))
    expect(() => validate(resultEnvelope(resultWith({ revision: bound.bindingEpoch! - 1n }))))
      .toThrow(/bindingEpoch must not exceed ideResult\.revision/)
    expect(() =>
      validate(resultEnvelope(create(IdeResultSchema, {
        ok: false,
        code: 'VIEW_GONE',
        message: 'gone',
        revision: bound.bindingEpoch! - 1n,
        views: [view],
      }))),
    ).toThrow(/bindingEpoch must not exceed ideResult\.revision/)

    // 有 top 完整绑定的成功回执只要携带快照，就必须包含同源 summary。
    expect(() => validate(resultEnvelope(resultWith({ views: [] }))))
      .toThrow(/snapshot must contain the top-level binding view/)
    expect(() =>
      validate(resultEnvelope(resultWith({
        viewId: '99999999-1111-4111-8111-111111111111',
      }))),
    ).toThrow(/snapshot must contain the top-level binding view/)
    expect(() =>
      validate(resultEnvelope(resultWith({
        browserSessionId: '99999999-1111-4111-8111-111111111111',
      }))),
    ).toThrow(/snapshot view must match the top-level binding/)
    expect(() =>
      validate(resultEnvelope(resultWith({
        eventNodeId: '99999999-1111-4111-8111-111111111111',
      }))),
    ).toThrow(/snapshot view must match the top-level binding/)
    expect(() =>
      validate(resultEnvelope(resultWith({
        connectionId: '99999999-1111-4111-8111-111111111111',
      }))),
    ).toThrow(/snapshot view must match the top-level binding/)
    expect(() =>
      validate(resultEnvelope(resultWith({
        claimId: '99999999-1111-4111-8111-111111111111',
      }))),
    ).toThrow(/snapshot view must match the top-level binding/)
    expect(() => validate(resultEnvelope(resultWith({ directory: '/other' }))))
      .toThrow(/snapshot view must match the top-level binding/)
    expect(() => validate(resultEnvelope(resultWith({ workspacePath: '/other' }))))
      .toThrow(/snapshot view must match the top-level binding/)
    expect(() => validate(resultEnvelope(resultWith({ bindingEpoch: bound.bindingEpoch! - 1n }))))
      .toThrow(/snapshot view must match the top-level binding/)

    // FOCUS_TERMINAL/COLLAPSE_MOBILE 等既有简单回执不携带快照，不能被新不变量误拒绝。
    validate(resultEnvelope(create(IdeResultSchema, { ok: true, viewId: view.viewId })))
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
