import type { ComposerPart, ImageInputTier } from '@/features/thread/composer/composer-parts'
import type { BranchDraft } from '@/features/thread/thread-settings'
import type { HarnessCommandCreateDTO, HarnessThreadCommandBatchDTO } from '@/shared/api/contracts/ai-runtime'
import { hasExactKeys, isRecord, nonBlank } from '@/shared/lib/value'

export function isImageTier(value: unknown): value is ImageInputTier {
  return value === '720P' || value === '1080P' || value === 'ORIGINAL'
}

function isEnvironmentName(value: unknown): value is string | null {
  return value === null || (typeof value === 'string' && value.length > 0 && value.length <= 64
    && value === value.trim() && !value.includes('/'))
}

function isExecutionSelection(value: unknown) {
  return isRecord(value) && hasExactKeys(value, ['driver', 'settings'])
    && nonBlank(value.driver) && isRecord(value.settings)
}

function isCommandContent(value: unknown): boolean {
  if (!isRecord(value)) return false
  if (value.type === 'TEXT') return hasExactKeys(value, ['type', 'text']) && typeof value.text === 'string'
  if (value.type === 'ATTACHMENT') {
    return nonBlank(value.uploadId) && (hasExactKeys(value, ['type', 'uploadId'])
      || (hasExactKeys(value, ['type', 'uploadId', 'imageTier']) && isImageTier(value.imageTier)))
  }
  if (value.type !== 'RESOURCE') return false
  const keys = ['type', 'blobId', 'name']
  if (Object.hasOwn(value, 'preview')) keys.push('preview')
  if (Object.hasOwn(value, 'imageTier')) {
    if (!isImageTier(value.imageTier)) return false
    keys.push('imageTier')
  }
  return hasExactKeys(value, keys) && nonBlank(value.blobId) && nonBlank(value.name)
    && (value.preview == null || typeof value.preview === 'string')
}

function isCommand(value: unknown): value is HarnessCommandCreateDTO {
  if (!isRecord(value) || !nonBlank(value.idempotencyKey)) return false
  switch (value.type) {
    case 'USER_MESSAGE': return hasExactKeys(value, ['type', 'idempotencyKey', 'contents'])
      && Array.isArray(value.contents) && value.contents.length > 0 && value.contents.every(isCommandContent)
    case 'SET_EXECUTION': return hasExactKeys(value, ['type', 'idempotencyKey', 'execution']) && isExecutionSelection(value.execution)
    case 'SET_ENVIRONMENT': return hasExactKeys(value, ['type', 'idempotencyKey', 'environmentName']) && isEnvironmentName(value.environmentName)
    default: return false
  }
}

export function isThreadCommandBatchRequest(value: unknown): value is HarnessThreadCommandBatchDTO {
  if (!isRecord(value) || !Array.isArray(value.commands) || value.commands.length === 0 || !value.commands.every(isCommand)) return false
  if (value.commands.length === 1 && value.commands[0]?.type === 'USER_MESSAGE') return hasExactKeys(value, ['commands'])
  return hasExactKeys(value, ['expectedHeadEntryId', 'expectedNextCommandSequence', 'commands'])
    && nonBlank(value.expectedHeadEntryId) && nonBlank(value.expectedNextCommandSequence)
}

export function isBranchDraft(value: unknown): value is BranchDraft {
  return isRecord(value) && hasExactKeys(value, ['agentName', 'execution', 'environmentName', 'yoloEnabled'])
    && nonBlank(value.agentName) && isExecutionSelection(value.execution)
    && isEnvironmentName(value.environmentName) && typeof value.yoloEnabled === 'boolean'
}

export function isComposerPart(value: unknown): value is ComposerPart {
  if (!isRecord(value) || !nonBlank(value.partId)) return false
  if (value.type === 'text') return typeof value.text === 'string'
  if (value.imageTier !== undefined && !isImageTier(value.imageTier)) return false
  if (value.type === 'attachment') return nonBlank(value.uploadId) && typeof value.filename === 'string'
  return value.type === 'resource' && nonBlank(value.blobId) && nonBlank(value.name)
    && (value.preview === undefined || typeof value.preview === 'string')
}
