import { request } from '@/shared/api/client'
import type {
  CanvasCreateDTO,
  ChatSpaceCreateDTO,
  ChatSpacePatchDTO,
  FolderCreateDTO,
  ScheduleDraftDTO,
  SchedulePatchDTO,
  SchedulePreviewRequestDTO,
} from '@/shared/api/generated/contracts'
import type { TreeDrop, TreeRow } from './resource-tree'

export const listSpaces = (signal?: AbortSignal) => request('GET /api/chat-spaces', {}, { signal })
export const getSpaceTree = (spaceId: string, signal?: AbortSignal) =>
  request('GET /api/chat-spaces/{spaceId}/tree', { path: { spaceId } }, { signal })
export const getThreadPlacement = (spaceId: string, id: string, signal?: AbortSignal) =>
  request('GET /api/chat-spaces/{spaceId}/threads/{id}', { path: { spaceId, id } }, { signal })
export const createSpace = (body: ChatSpaceCreateDTO, idempotencyKey: string) =>
  request('POST /api/chat-spaces', { body }, { idempotencyKey })
export const patchSpace = (spaceId: string, body: ChatSpacePatchDTO) =>
  request('PATCH /api/chat-spaces/{spaceId}', { path: { spaceId }, body })
export const createFolder = (spaceId: string, body: FolderCreateDTO, idempotencyKey: string) =>
  request('POST /api/chat-spaces/{spaceId}/folders', { path: { spaceId }, body }, { idempotencyKey })
export const createCanvas = (spaceId: string, body: CanvasCreateDTO, idempotencyKey: string) =>
  request('POST /api/chat-spaces/{spaceId}/canvases', { path: { spaceId }, body }, { idempotencyKey })

export function renameTreeRow(spaceId: string, row: TreeRow, name: string) {
  const { id } = row
  switch (row.kind) {
    case 'folder': return request('PATCH /api/chat-spaces/{spaceId}/folders/{folderId}', {
      path: { spaceId, folderId: id }, body: { name },
    })
    case 'thread': return request('PATCH /api/chat-spaces/{spaceId}/threads/{id}', {
      path: { spaceId, id }, body: { title: name, expectedVersion: row.thread.version },
    })
    case 'canvas':
    case 'session': return request('PATCH /api/chat-spaces/{spaceId}/resources/{kind}/{id}', {
      path: { spaceId, kind: row.kind === 'session' ? 'SESSION' : 'CANVAS', id }, body: { title: name },
    })
  }
}

export function moveTreeRow(spaceId: string, row: TreeRow, drop: TreeDrop, expectedTreeVersion: string) {
  const { id } = row
  const { parentId, beforeId } = drop
  switch (row.kind) {
    case 'folder': return request('PATCH /api/chat-spaces/{spaceId}/folders/{folderId}', {
      path: { spaceId, folderId: id }, body: { parentId, beforeId, expectedTreeVersion },
    })
    case 'canvas':
    case 'session': return request('PATCH /api/chat-spaces/{spaceId}/resources/{kind}/{id}', {
      path: { spaceId, kind: row.kind === 'session' ? 'SESSION' : 'CANVAS', id },
      body: { folderId: parentId, beforeId, expectedTreeVersion },
    })
    case 'thread': throw new Error('Threads cannot be moved')
  }
}

/** Read before displaying the confirmation, so later Canvas edits cannot be deleted silently. */
export async function readCanvasRevision(canvasId: string, signal?: AbortSignal) {
  const snapshot = await request('GET /api/canvas/canvases/{canvasId}', { path: { canvasId } }, { signal })
  return snapshot.document.revision
}

export type TreeDeletion =
  | { kind: 'folder' | 'session'; id: string; expectedTreeVersion: string }
  | { kind: 'thread'; id: string; expectedTreeVersion: string; expectedVersion: string }
  | { kind: 'canvas'; id: string; expectedTreeVersion: string; expectedRevision: string }

export function deleteTreeRow(spaceId: string, deletion: TreeDeletion) {
  const { id, expectedTreeVersion } = deletion
  switch (deletion.kind) {
    case 'folder': return request('DELETE /api/chat-spaces/{spaceId}/folders/{folderId}', {
      path: { spaceId, folderId: id }, query: { expectedTreeVersion },
    })
    case 'session': return request('DELETE /api/chat-spaces/{spaceId}/sessions/{id}', {
      path: { spaceId, id }, query: { expectedTreeVersion },
    })
    case 'thread': return request('DELETE /api/chat-spaces/{spaceId}/threads/{id}', {
      path: { spaceId, id }, query: { expectedTreeVersion, expectedVersion: deletion.expectedVersion },
    })
    case 'canvas': return request('DELETE /api/chat-spaces/{spaceId}/canvases/{canvasId}', {
      path: { spaceId, canvasId: id }, query: { expectedTreeVersion, expectedRevision: deletion.expectedRevision },
    })
  }
}

export const listSchedules = (spaceId: string, signal?: AbortSignal) =>
  request('GET /api/chat-spaces/{spaceId}/schedules', { path: { spaceId } }, { signal })

export const previewSchedule = (spaceId: string, body: SchedulePreviewRequestDTO, signal?: AbortSignal) =>
  request('POST /api/chat-spaces/{spaceId}/schedules/preview', { path: { spaceId }, body }, { signal })

export const createSchedule = (spaceId: string, body: ScheduleDraftDTO, idempotencyKey: string) =>
  request('POST /api/chat-spaces/{spaceId}/schedules', { path: { spaceId }, body }, { idempotencyKey })

export const patchSchedule = (spaceId: string, id: string, body: SchedulePatchDTO) =>
  request('PATCH /api/chat-spaces/{spaceId}/schedules/{id}', { path: { spaceId, id }, body })

export const deleteSchedule = (spaceId: string, id: string, expectedVersion: string) =>
  request('DELETE /api/chat-spaces/{spaceId}/schedules/{id}', { path: { spaceId, id }, query: { expectedVersion } })

export const runScheduleNow = (spaceId: string, id: string, idempotencyKey: string) =>
  request('POST /api/chat-spaces/{spaceId}/schedules/{id}/runs', { path: { spaceId, id } }, { idempotencyKey })

export const listScheduleRuns = (spaceId: string, id: string, before: string | null, signal?: AbortSignal) =>
  request('GET /api/chat-spaces/{spaceId}/schedules/{id}/runs', {
    path: { spaceId, id },
    query: before ? { before } : undefined,
  }, { signal })

export const listAgentSummaries = (signal?: AbortSignal) =>
  request('GET /api/platform/agent-summaries', {}, { signal })

export const getThreadEntriesSnapshot = (threadId: string, signal?: AbortSignal) =>
  request('GET /api/harness/threads/{threadId}', { path: { threadId } }, { signal })
