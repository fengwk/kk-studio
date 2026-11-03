import { create } from '@bufbuild/protobuf'

import {
  Architecture,
  CancelSchema,
  CapabilityResultSchema,
  CompletedSchema,
  ContentSchema,
  EnvelopeSchema,
  ErrorSchema,
  ExecutionExitedSchema,
  ExpectedFileSchema,
  HeartbeatSchema,
  IdeAction,
  IdeCommandSchema,
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
  PresignedPutSchema,
  ProgressSchema,
  ReadySchema,
  RecoverNotFoundSchema,
  RecoverSchema,
  ResourceBudgetsSchema,
  ResourceContentSchema,
  ResourceUploadCommitSchema,
  ResourceUploadRequestSchema,
  ResourceUploadTicketSchema,
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
  FailedSchema,
  CancelledSchema,
  EnvironmentInfoSchema,
  RuntimeBodySchema,
} from '../../dist/generated/control_pb.js'

export const ENVIRONMENT_ID = '3f2a9c1e-7b4d-4e2a-9c6f-1a2b3c4d5e6f'
export const INVOCATION_ID = 'invoke-0001'
export const CAPABILITY_ID = 'fs.edit'
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

const VIEW = '11111111-1111-4111-8111-111111111111'
const DOCUMENT = '22222222-2222-4222-8222-222222222222'
const NODE = '33333333-3333-4333-8333-333333333333'
const CONNECTION = '44444444-4444-4444-8444-444444444444'
const CLAIM = '55555555-5555-4555-8555-555555555555'
const TRANSFER = '66666666-6666-4666-8666-666666666666'
const UPLOAD = '77777777-7777-4777-8777-777777777777'
const OPERATION = '88888888-8888-4888-8888-888888888888'
const ARTIFACT =
  'https://github.com/fengwk/kk-studio/releases/download/v1.2.3/kk-studio-daemon-v1.2.3-linux-amd64.tar.gz'

function runtimeSnapshot(withUpdate) {
  return create(RuntimeSnapshotSchema, {
    revision: 7n,
    status: create(RuntimeBodySchema, {
      daemonVersion: '5.0.1',
      ...(withUpdate
        ? {
            update: create(UpdateProgressSchema, {
              targetVersion: '5.0.2',
              downloadedBytes: 10n,
              totalBytes: 20n,
            }),
          }
        : {}),
    }),
  })
}

export function settingsEnvelope() {
  return create(EnvelopeSchema, {
    messageType: MessageType.SETTINGS,
    environmentId: ENVIRONMENT_ID,
    payload: {
      case: 'settings',
      value: create(SettingsSchema, {
        resourceBudgets: create(ResourceBudgetsSchema, {
          maxResourceBytes: 1048576n,
          temporaryResourceTtlSeconds: 3600n,
          temporaryResourceCleanupIntervalSeconds: 60n,
        }),
        displayName: 'dev-box',
      }),
    },
  })
}

export function readyEnvelope() {
  return create(EnvelopeSchema, {
    messageType: MessageType.READY,
    environmentId: ENVIRONMENT_ID,
    payload: {
      case: 'ready',
      value: create(ReadySchema, {
        daemonVersion: '5.0.1',
        environment: create(EnvironmentInfoSchema, {
          operatingSystem: OperatingSystem.LINUX,
          architecture: Architecture.AMD64,
          timeZone: 'Asia/Shanghai',
          userName: 'dev',
          homeDirectory: '/srv/work',
          note: 'local workstation',
          tempDirectory: '/tmp',
        }),
        runtimeStatus: runtimeSnapshot(false),
      }),
    },
  })
}

export function heartbeatEnvelope() {
  return create(EnvelopeSchema, {
    messageType: MessageType.HEARTBEAT,
    environmentId: ENVIRONMENT_ID,
    payload: {
      case: 'heartbeat',
      value: create(HeartbeatSchema, { runtimeStatus: runtimeSnapshot(true) }),
    },
  })
}

export function runtimeStatusEnvelope() {
  return create(EnvelopeSchema, {
    messageType: MessageType.RUNTIME_STATUS,
    environmentId: ENVIRONMENT_ID,
    payload: {
      case: 'runtimeStatus',
      value: create(RuntimeStatusSchema, { runtimeStatus: runtimeSnapshot(false) }),
    },
  })
}

export function startedEnvelope() {
  return envelope(MessageType.STARTED, { case: 'started', value: create(StartedSchema, { replayed: true }) })
}

export function progressEnvelope() {
  return envelope(MessageType.PROGRESS, {
    case: 'progress',
    value: create(ProgressSchema, {
      result: create(CapabilityResultSchema, {
        callId: CALL_ID,
        error: false,
        detailsJson: '{}',
        contents: [create(ContentSchema, { body: { case: 'text', value: create(TextContentSchema, { text: 'partial' }) } })],
      }),
    }),
  })
}

export function failedEnvelope() {
  return envelope(MessageType.FAILED, { case: 'failed', value: create(FailedSchema, { message: 'boom' }) })
}

export function cancelEnvelope() {
  return envelope(MessageType.CANCEL, { case: 'cancel', value: create(CancelSchema, {}) })
}

export function cancelledEnvelope() {
  return envelope(MessageType.CANCELLED, { case: 'cancelled', value: create(CancelledSchema, { reason: 'cancelled' }) })
}

export function recoverEnvelope() {
  return envelope(MessageType.RECOVER, { case: 'recover', value: create(RecoverSchema, {}) })
}

export function recoverNotFoundEnvelope() {
  return envelope(MessageType.RECOVER_NOT_FOUND, {
    case: 'recoverNotFound',
    value: create(RecoverNotFoundSchema, {}),
  })
}

export function uploadRequestEnvelope() {
  return envelope(MessageType.RESOURCE_UPLOAD_REQUEST, {
    case: 'resourceUploadRequest',
    value: create(ResourceUploadRequestSchema, {
      transferId: TRANSFER,
      mediaType: 'text/plain',
      name: 'notes.txt',
      size: 4n,
      sha256: SHA_A,
    }),
  })
}

export function uploadTicketEnvelope() {
  return envelope(MessageType.RESOURCE_UPLOAD_TICKET, {
    case: 'resourceUploadTicket',
    value: create(ResourceUploadTicketSchema, {
      transferId: TRANSFER,
      state: TicketState.TICKET_PENDING,
      uploadId: UPLOAD,
      presignedPut: create(PresignedPutSchema, {
        method: 'PUT',
        url: 'https://uploads.example/object',
        headers: { 'content-type': 'text/plain' },
        expiresAt: '2026-10-31T00:00:00Z',
      }),
    }),
  })
}

export function uploadCommitEnvelope() {
  return envelope(MessageType.RESOURCE_UPLOAD_COMMIT, {
    case: 'resourceUploadCommit',
    value: create(ResourceUploadCommitSchema, { transferId: TRANSFER, uploadId: UPLOAD }),
  })
}

export function policyEnvelope() {
  return create(EnvelopeSchema, {
    messageType: MessageType.TEMPORARY_RESOURCE_POLICY,
    environmentId: ENVIRONMENT_ID,
    payload: {
      case: 'temporaryResourcePolicy',
      value: create(TemporaryResourcePolicySchema, {
        temporaryResourceTtlSeconds: 3600n,
        temporaryResourceCleanupIntervalSeconds: 60n,
      }),
    },
  })
}

export function updateEnvelope() {
  return create(EnvelopeSchema, {
    messageType: MessageType.UPDATE,
    environmentId: ENVIRONMENT_ID,
    payload: {
      case: 'update',
      value: create(UpdateSchema, {
        operationId: OPERATION,
        targetVersion: '1.2.3',
      }),
    },
  })
}

export function updateResultEnvelope() {
  return create(EnvelopeSchema, {
    messageType: MessageType.UPDATE_RESULT,
    environmentId: ENVIRONMENT_ID,
    payload: {
      case: 'updateResult',
      value: create(UpdateResultSchema, { operationId: OPERATION, phase: UpdatePhase.UPDATE_PREPARED }),
    },
  })
}

export function ideCommandEnvelope() {
  return envelope(MessageType.IDE_COMMAND, {
    case: 'ideCommand',
    value: create(IdeCommandSchema, {
      action: IdeAction.OPEN_VIEW,
      viewId: VIEW,
      documentId: DOCUMENT,
      eventNodeId: NODE,
      connectionId: CONNECTION,
      claimId: CLAIM,
      mode: IdeOpenMode.CREATE,
      directory: '/work',
    }),
  })
}

export function ideResultEnvelope() {
  return envelope(MessageType.IDE_RESULT, {
    case: 'ideResult',
    value: create(IdeResultSchema, {
      ok: true,
      views: [
        create(IdeViewSummarySchema, {
          viewId: VIEW,
          documentId: DOCUMENT,
          eventNodeId: NODE,
          connectionId: CONNECTION,
          claimId: CLAIM,
          bindingEpoch: 3n,
          directory: '/work',
          workspacePath: '/work',
          state: IdeViewState.OPEN,
        }),
      ],
    }),
  })
}

export function ideLeaseEnvelope() {
  return create(EnvelopeSchema, {
    messageType: MessageType.IDE_LEASE,
    environmentId: ENVIRONMENT_ID,
    payload: {
      case: 'ideLease',
      value: create(IdeLeaseSchema, {
        round: 2n,
        bindings: [create(IdeLeaseBindingSchema, { viewId: VIEW, documentId: DOCUMENT, bindingEpoch: 4n })],
      }),
    },
  })
}

export function errorEnvelope() {
  return create(EnvelopeSchema, {
    messageType: MessageType.ERROR,
    environmentId: ENVIRONMENT_ID,
    payload: {
      case: 'error',
      value: create(ErrorSchema, { message: 'retry' }),
    },
  })
}

export function upgradeRequired() {
  return create(UpgradeRequiredSchema, {
    targetProtocolVersion: 1,
    artifactUrl: ARTIFACT,
    artifactSha256: 'b'.repeat(64),
  })
}

export const GOLDEN_FIXTURES = [
  ['invoke.bin', invokeEnvelope],
  ['completed.bin', completedEnvelope],
  ['execution-exited.bin', executionExitedEnvelope],
  ['settings.bin', settingsEnvelope],
  ['ready.bin', readyEnvelope],
  ['heartbeat.bin', heartbeatEnvelope],
  ['runtime-status.bin', runtimeStatusEnvelope],
  ['started.bin', startedEnvelope],
  ['progress.bin', progressEnvelope],
  ['failed.bin', failedEnvelope],
  ['cancel.bin', cancelEnvelope],
  ['cancelled.bin', cancelledEnvelope],
  ['recover.bin', recoverEnvelope],
  ['recover-not-found.bin', recoverNotFoundEnvelope],
  ['upload-request.bin', uploadRequestEnvelope],
  ['upload-ticket.bin', uploadTicketEnvelope],
  ['upload-commit.bin', uploadCommitEnvelope],
  ['policy.bin', policyEnvelope],
  ['update.bin', updateEnvelope],
  ['update-result.bin', updateResultEnvelope],
  ['ide-command.bin', ideCommandEnvelope],
  ['ide-result.bin', ideResultEnvelope],
  ['ide-lease.bin', ideLeaseEnvelope],
  ['error.bin', errorEnvelope],
]
