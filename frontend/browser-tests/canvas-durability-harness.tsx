import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { CanvasCommandQueue } from '@/features/canvas/command-queue'
import {
  createCanvasOperationStore,
  resetCanvasOperationStorage,
} from '@/features/canvas/canvas-operation-storage'
import {
  getEditingSessionId,
  resetEditingSessionForTests,
} from '@/features/canvas/canvas-editing-session'
import { onCanvasStorageError } from '@/features/canvas/canvas-local-store'
import type {
  CanvasCommandDTO,
  CanvasSnapshotDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'

const SESSION_STORAGE_KEY = 'kkstudio.canvas.editingSessionId'
const DEFAULT_CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f' as UUIDString

export interface CanvasDurabilityRecordedCall {
  idempotencyKey: UUIDString
  commands: CanvasCommandDTO[]
}

export interface CanvasDurabilityBuildOptions {
  canvasId?: UUIDString
  initialRevision?: string
}

export interface CanvasDurabilityHarnessApi {
  mode: 'ok' | 'network-error'
  setMode: (mode: 'ok' | 'network-error') => void
  resetData: () => Promise<void>
  sessionId: () => string
  readSessionStorageId: () => string | null
  replaceSessionStorageId: (id: string) => void
  buildQueue: (options?: CanvasDurabilityBuildOptions) => CanvasCommandQueue
  enqueue: (
    commands: CanvasCommandDTO[],
  ) => Promise<{ ok: true; revision: string } | { ok: false; errorName: string; errorMessage: string }>
  recover: () => Promise<{
    replayed: number
    conflicted: number
    failed: number
    applyCalls: CanvasDurabilityRecordedCall[]
  }>
  calls: () => CanvasDurabilityRecordedCall[]
  clearCalls: () => void
  listPersistedOperations: (
    canvasId?: UUIDString,
  ) => Promise<Array<{ id: string; idempotencyKey: UUIDString; commands: CanvasCommandDTO[]; sequence: number }>>
  getStorageErrors: () => string[]
  hasStorageError: () => boolean
  clearStorageErrors: () => void
}

declare global {
  interface Window {
    __canvasDurability: CanvasDurabilityHarnessApi
  }
}

let queue: CanvasCommandQueue | null = null
let currentCanvasId: UUIDString = DEFAULT_CANVAS_ID
let currentRevision = '1'
let currentMode: 'ok' | 'network-error' = 'ok'
let recordedCalls: CanvasDurabilityRecordedCall[] = []
let storageErrors: string[] = []

onCanvasStorageError((err) => {
  storageErrors.push(err.name || err.message)
})

function buildAuthoritativeSnapshot(canvasId: UUIDString, revision: string): CanvasSnapshotDTO {
  return {
    document: {
      id: canvasId,
      title: 'Canvas Durability Document',
      revision,
      createdAt: '2026-09-27T00:00:00Z',
      updatedAt: '2026-09-27T00:00:00Z',
    },
    nodes: [],
    groups: [],
    references: [],
  }
}

function deleteIdbDatabase(name: string): Promise<void> {
  return new Promise((resolve) => {
    if (typeof window === 'undefined' || !window.indexedDB) {
      resolve()
      return
    }
    try {
      const req = window.indexedDB.deleteDatabase(name)
      req.onsuccess = () => resolve()
      req.onerror = () => resolve()
      req.onblocked = () => resolve()
    } catch {
      resolve()
    }
  })
}

async function resetData(): Promise<void> {
  recordedCalls = []
  storageErrors = []
  currentRevision = '1'
  currentMode = 'ok'
  queue = null
  resetEditingSessionForTests()
  resetCanvasOperationStorage()
  try {
    window.localStorage.clear()
  } catch {
    // ignore when storage is restricted
  }
  try {
    window.sessionStorage.clear()
  } catch {
    // ignore when storage is restricted
  }
  await Promise.all([
    deleteIdbDatabase('kkstudio.canvas.operations'),
    deleteIdbDatabase('kkstudio.canvas.drafts'),
  ])
}

function readSessionStorageId(): string | null {
  try {
    return window.sessionStorage.getItem(SESSION_STORAGE_KEY)
  } catch {
    return null
  }
}

function replaceSessionStorageId(id: string): void {
  try {
    window.sessionStorage.setItem(SESSION_STORAGE_KEY, id)
  } catch {
    // ignore when storage is restricted
  }
}

function buildQueue(options?: CanvasDurabilityBuildOptions): CanvasCommandQueue {
  if (options?.canvasId) {
    currentCanvasId = options.canvasId
  }
  if (options?.initialRevision) {
    currentRevision = options.initialRevision
  }

  const initialSnapshot = buildAuthoritativeSnapshot(currentCanvasId, currentRevision)

  queue = new CanvasCommandQueue(currentCanvasId, {
    initialSnapshot,
    apply: async (_canvasId, body) => {
      recordedCalls.push({
        idempotencyKey: body.idempotencyKey,
        commands: body.commands,
      })
      if (currentMode === 'network-error') {
        throw new Error('network down')
      }
      currentRevision = String(BigInt(currentRevision) + 1n)
      return {
        revision: currentRevision,
        nodes: [],
        groups: [],
      }
    },
    refetch: async (cid) => {
      return buildAuthoritativeSnapshot(cid, currentRevision)
    },
  })

  return queue
}

async function enqueue(
  commands: CanvasCommandDTO[],
): Promise<{ ok: true; revision: string } | { ok: false; errorName: string; errorMessage: string }> {
  if (!queue) {
    buildQueue()
  }
  try {
    const snapshot = await queue!.enqueue(commands)
    return { ok: true, revision: snapshot.document.revision }
  } catch (error: unknown) {
    const err = error as Error | undefined
    return {
      ok: false,
      errorName: err?.name || 'Error',
      errorMessage: err?.message || String(error),
    }
  }
}

async function recover(): Promise<{
  replayed: number
  conflicted: number
  failed: number
  applyCalls: CanvasDurabilityRecordedCall[]
}> {
  if (!queue) {
    buildQueue()
  }
  const result = await queue!.recover()
  return {
    replayed: result.replayed,
    conflicted: result.conflicted,
    failed: result.failed,
    applyCalls: [...recordedCalls],
  }
}

async function listPersistedOperations(
  canvasId?: UUIDString,
): Promise<Array<{ id: string; idempotencyKey: UUIDString; commands: CanvasCommandDTO[]; sequence: number }>> {
  const cid = canvasId ?? currentCanvasId
  const store = createCanvasOperationStore(cid)
  const list = await store.list()
  return list.map((op) => ({
    id: op.id,
    idempotencyKey: op.idempotencyKey,
    commands: op.commands,
    sequence: op.sequence,
  }))
}

window.__canvasDurability = {
  get mode() {
    return currentMode
  },
  setMode(mode: 'ok' | 'network-error') {
    currentMode = mode
  },
  resetData,
  sessionId: () => getEditingSessionId(),
  readSessionStorageId,
  replaceSessionStorageId,
  buildQueue,
  enqueue,
  recover,
  calls: () => [...recordedCalls],
  clearCalls: () => {
    recordedCalls = []
  },
  listPersistedOperations,
  getStorageErrors: () => [...storageErrors],
  hasStorageError: () => storageErrors.length > 0,
  clearStorageErrors: () => {
    storageErrors = []
  },
}

export function CanvasDurabilityHarness() {
  return (
    <div className="harness-box">
      <h1>Canvas Durability Test Harness</h1>
      <p id="harness-ready">Harness ready for Playwright</p>
    </div>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<CanvasDurabilityHarness />)
}
