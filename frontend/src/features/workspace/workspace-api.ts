import { request } from '@/shared/api/client'
import type { ChatSpaceCreateDTO, ChatSpacePatchDTO, FolderCreateDTO, CanvasCreateDTO } from '@/shared/api/generated/contracts'
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
