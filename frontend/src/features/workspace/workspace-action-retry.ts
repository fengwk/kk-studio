import { ApiError } from '@/shared/api/client'
import { createUuid } from '@/shared/lib/uuid'

export function isTreeVersionConflict(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409 && error.code === 'version_conflict'
}

function isWorkspaceTargetMissing(error: ApiError): boolean {
  return error.status === 404 && (error.reason === 'WORKSPACE_NOT_FOUND' || error.reason === 'RESOURCE_NOT_FOUND')
}

// Only application responses known to reject these operations release their original request.
export function isCreateRejected(failure: unknown): boolean {
  const error = failure instanceof Error ? failure.cause : undefined
  return error instanceof ApiError && (isTreeVersionConflict(error) || isWorkspaceTargetMissing(error)
    || (error.status === 400 && error.code === 'validation' && Boolean(error.reason || error.detail))
    || (error.status === 409 && (error.reason === 'FOLDER_NAME_EXISTS' || error.reason === 'IDEMPOTENCY_KEY_REUSED')))
}

export function isManualRunRejected(error: unknown): boolean {
  return error instanceof ApiError && (isWorkspaceTargetMissing(error)
    || (error.status === 400 && error.reason === 'INVALID_ARGUMENT'))
}

export function freezeTreeCreation<Folder extends string | undefined>(name: string, folderId: Folder, expectedTreeVersion: string) {
  return { name, folderId, expectedTreeVersion, key: createUuid() } as const
}

export type TreeCreation<Folder extends string | undefined> = ReturnType<typeof freezeTreeCreation<Folder>>
