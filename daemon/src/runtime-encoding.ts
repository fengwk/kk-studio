import { create } from '@bufbuild/protobuf'

import type { CapabilityResult, ResultContent } from './contracts.js'
import {
  CapabilityResultSchema,
  CompletedSchema,
  ContentSchema,
  FailedSchema,
  JsonContentSchema,
  ProgressSchema,
  ResourceContentSchema,
  TextContentSchema,
  type Completed,
  type Content,
  type Envelope,
  type Failed,
  type Progress,
} from './generated/control_pb.js'
import type { UploadedResource } from './runtime-resources.js'
import { toolOutputExceededMessage } from './tool-output-limit.js'

const FALLBACK_FAILURE_MESSAGE = 'capability execution failed'

/** 安全失败文案：只透出非空 Error message，其它值使用固定兜底。 */
export function safeMessage(error: unknown): string {
  if (error instanceof Error) {
    const message = error.message
    if (typeof message === 'string' && message.trim() !== '') {
      return message
    }
  }
  return FALLBACK_FAILURE_MESSAGE
}

export function requireInvocationId(envelope: Envelope): string {
  const invocationId = envelope.invocationId
  if (invocationId === undefined || invocationId === null || invocationId.trim() === '') {
    throw new Error(`${envelope.messageType} requires non-null invocationId`)
  }
  return invocationId
}

export function failedPayload(message: string): { case: 'failed'; value: Failed } {
  return {
    case: 'failed',
    value: create(FailedSchema, { message }),
  }
}

export async function encodeCompletedProto(
  result: CapabilityResult,
  maximumResourceBytes: number,
  uploader: ((content: Extract<ResultContent, { type: 'binary' }>) => Promise<UploadedResource>) | null,
): Promise<Completed> {
  if (!Number.isSafeInteger(maximumResourceBytes) || maximumResourceBytes <= 0) {
    throw new Error('maximumResourceBytes must be positive')
  }
  let aggregate = 0
  for (const content of result.contents) {
    let size = 0
    if (content.type === 'resource') {
      size = content.size
    } else if (content.type === 'binary') {
      size = content.bytes.byteLength
    }
    if (size > maximumResourceBytes - aggregate) {
      throw new Error(toolOutputExceededMessage(aggregate + size, maximumResourceBytes))
    }
    aggregate += size
  }

  const protoContents: Content[] = []
  for (const content of result.contents) {
    if (content.type === 'text') {
      protoContents.push(create(ContentSchema, {
        body: {
          case: 'text',
          value: create(TextContentSchema, { text: content.text }),
        },
      }))
    } else if (content.type === 'json') {
      protoContents.push(create(ContentSchema, {
        body: {
          case: 'json',
          value: create(JsonContentSchema, { json: JSON.stringify(content.json) }),
        },
      }))
    } else if (content.type === 'resource') {
      protoContents.push(create(ContentSchema, {
        body: {
          case: 'resource',
          value: create(ResourceContentSchema, {
            uploadId: content.uploadId,
            mediaType: content.mediaType,
            name: content.name ?? undefined,
            size: BigInt(content.size),
            sha256: content.sha256,
          }),
        },
      }))
    } else if (content.type === 'binary') {
      if (uploader === null) {
        throw new Error('resource upload is not configured')
      }
      const uploaded = await uploader(content)
      protoContents.push(create(ContentSchema, {
        body: {
          case: 'resource',
          value: create(ResourceContentSchema, {
            uploadId: uploaded.uploadId,
            mediaType: uploaded.mediaType,
            name: uploaded.name ?? undefined,
            size: BigInt(uploaded.size),
            sha256: uploaded.sha256,
          }),
        },
      }))
    }
  }

  return create(CompletedSchema, {
    result: create(CapabilityResultSchema, {
      callId: result.callId,
      error: result.error,
      detailsJson: JSON.stringify(result.details ?? {}),
      contents: protoContents,
    }),
  })
}

export function encodeProgressProto(result: CapabilityResult): Progress {
  return create(ProgressSchema, {
    result: create(CapabilityResultSchema, {
      callId: result.callId,
      error: result.error,
      detailsJson: JSON.stringify(result.details ?? {}),
      contents: result.contents.map((content) => {
        if (content.type === 'text') {
          return create(ContentSchema, {
            body: { case: 'text', value: create(TextContentSchema, { text: content.text }) },
          })
        }
        if (content.type === 'json') {
          return create(ContentSchema, {
            body: { case: 'json', value: create(JsonContentSchema, { json: JSON.stringify(content.json) }) },
          })
        }
        throw new Error('progress does not allow binary content')
      }),
    }),
  })
}
