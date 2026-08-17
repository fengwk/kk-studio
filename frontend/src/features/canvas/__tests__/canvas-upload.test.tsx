import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  useCanvasUploadPipeline,
  type CanvasUploadPipelineOptions,
} from '@/features/canvas/canvas-upload'
import { uniqueNodeAlias } from '@/features/canvas/node-alias'
import type { CanvasCommandDTO } from '@/shared/api/contracts/studio'
import type { StorageUploadDTO } from '@/shared/api/contracts/storage'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const UPLOAD_ID = 'dddddddd-0000-4000-8000-000000000009'
const PENDING_PUT = {
  method: 'PUT' as const,
  url: 'https://s3.example/upload',
  headers: { 'If-None-Match': '*' },
}
const EXPIRES_AT = '2026-08-10T00:15:00Z'

function pendingReservation(id: string = UPLOAD_ID): StorageUploadDTO {
  return {
    id,
    state: 'PENDING',
    blobId: null,
    presignedPut: PENDING_PUT,
    expiresAt: EXPIRES_AT,
  }
}

function readyReservation(id: string = UPLOAD_ID): StorageUploadDTO {
  return {
    id,
    state: 'READY',
    blobId: 'blob-existing',
    presignedPut: null,
    expiresAt: EXPIRES_AT,
  }
}

function createHarness(options?: Partial<CanvasUploadPipelineOptions>) {
  const progressCalls: Array<[string, number | null]> = []
  const toasts: string[] = []
  // 模拟 controller 的 alias 池：已落库节点名（不释放）+ 进行中预留（完成后释放）。
  const snapshotNames = new Set<string>()
  const reservedNames = new Set<string>()
  const reserveNodeAlias = vi.fn((preferred: string) => {
    const alias = uniqueNodeAlias(preferred, [...snapshotNames, ...reservedNames])
    reservedNames.add(alias)
    return alias
  })
  const releaseNodeAlias = vi.fn((alias: string) => {
    reservedNames.delete(alias)
  })
  const executeCommands = vi.fn(async (commands: CanvasCommandDTO[]) => {
    for (const command of commands) {
      if (
        command.type === 'CREATE_RESOURCE_NODE'
        || command.type === 'CREATE_TEXT_NODE'
        || command.type === 'CREATE_FUNCTION_NODE'
      ) {
        snapshotNames.add(command.name)
      }
    }
  })
  const hashFile = vi.fn(async (file: File) => `${file.name}:sha256`)
  const storageService = {
    reserveUpload: vi.fn().mockResolvedValue(pendingReservation()),
    uploadFile: vi.fn().mockResolvedValue(undefined),
    completeUpload: vi.fn().mockResolvedValue(readyReservation()),
    deleteUpload: vi.fn(),
    getBlobOriginalUrl: vi.fn(),
    getBlobPreviewUrl: vi.fn(),
  }
  const nextTransform = vi.fn(() => ({ x: 100, y: 100, width: 320, height: 246 }))
  const view = renderHook(() => useCanvasUploadPipeline({
    canvasId: CANVAS_ID,
    storageService,
    hashFile,
    executeCommands,
    reserveNodeAlias,
    releaseNodeAlias,
    nextTransform,
    onUploadProgress: (localId, progress) => progressCalls.push([localId, progress]),
    setToast: (toast) => toasts.push(toast),
    ...options,
  }))
  return {
    result: view.result,
    progressCalls,
    toasts,
    executeCommands,
    reserveNodeAlias,
    releaseNodeAlias,
    hashFile,
    storageService,
  }
}

function localIdOf(file: File, sequence = 0): string {
  return `${file.name}:${file.lastModified}:${file.size}:${sequence}`
}

function resourceNodeCommands(executeCommands: ReturnType<typeof vi.fn>): CanvasCommandDTO[] {
  return executeCommands.mock.calls.map(([commands]) => commands[0] as CanvasCommandDTO)
}

describe('useCanvasUploadPipeline', () => {
  it('skips the direct PUT for READY reservations (sha256 dedup) and completes with a node command', async () => {
    const {
      result,
      storageService,
      executeCommands,
      progressCalls,
    } = createHarness()
    storageService.reserveUpload.mockResolvedValue(readyReservation(UPLOAD_ID))
    storageService.completeUpload.mockResolvedValue(readyReservation(UPLOAD_ID))
    const file = new File(['png'], 'upload.png')
    const localId = localIdOf(file)

    await act(async () => {
      await result.current.uploadFiles([file])
    })

    expect(storageService.reserveUpload).toHaveBeenCalledWith({
      filename: 'upload.png',
      mediaType: 'image/png',
      sizeBytes: 3,
      sha256: 'upload.png:sha256',
    })
    expect(storageService.uploadFile).not.toHaveBeenCalled()
    expect(storageService.completeUpload).toHaveBeenCalledWith(UPLOAD_ID)
    expect(resourceNodeCommands(executeCommands)).toEqual([{
      type: 'CREATE_RESOURCE_NODE',
      nodeId: expect.stringMatching(/^[0-9a-f-]{36}$/i),
      name: 'upload.png',
      uploadIds: [UPLOAD_ID],
      transform: { x: 100, y: 100, width: 320, height: 246 },
    }])
    expect(progressCalls).toEqual([
      [localId, 0.1],
      [localId, 0.3],
      [localId, 0.5],
      [localId, null],
    ])
  })

  it('directly PUTs PENDING reservations before completing', async () => {
    const {
      result,
      storageService,
      executeCommands,
      progressCalls,
    } = createHarness()
    const file = new File(['png'], 'pending.png')
    const localId = localIdOf(file)

    await act(async () => {
      await result.current.uploadFiles([file])
    })

    expect(storageService.reserveUpload).toHaveBeenCalledOnce()
    expect(storageService.uploadFile).toHaveBeenCalledWith(PENDING_PUT, file)
    expect(storageService.completeUpload).toHaveBeenCalledWith(UPLOAD_ID)
    expect(resourceNodeCommands(executeCommands)).toHaveLength(1)
    expect(progressCalls).toEqual([
      [localId, 0.1],
      [localId, 0.3],
      [localId, 0.5],
      [localId, 0.8],
      [localId, null],
    ])
  })

  it('allocates unique aliases for same-named files in one batch and releases them after each command', async () => {
    const {
      result,
      executeCommands,
      releaseNodeAlias,
      reserveNodeAlias,
      progressCalls,
    } = createHarness()

    await act(async () => {
      await result.current.uploadFiles([
        new File(['one'], 'same.heic'),
        new File(['two'], 'same.heic'),
      ])
    })

    expect(resourceNodeCommands(executeCommands).map((command) => command.name)).toEqual([
      'same.heic',
      'same.heic 2',
    ])
    expect(reserveNodeAlias).toHaveBeenCalledTimes(2)
    expect(releaseNodeAlias).toHaveBeenCalledWith('same.heic')
    expect(releaseNodeAlias).toHaveBeenCalledWith('same.heic 2')
    const progressIds = progressCalls
      .filter(([, progress]) => progress === 0.1)
      .map(([localId]) => localId)
    expect(progressIds).toHaveLength(2)
    expect(new Set(progressIds).size).toBe(2)
  })

  it('keeps uploading remaining files when one file fails mid-pipeline', async () => {
    const { result, storageService, executeCommands, toasts, progressCalls } = createHarness()
    storageService.uploadFile
      .mockRejectedValueOnce(new Error('direct upload failed'))
      .mockResolvedValue(undefined)
    const failed = new File(['bad'], 'fail.png')
    const ok = new File(['good'], 'ok.png')

    await act(async () => {
      await result.current.uploadFiles([failed, ok])
    })

    // 失败的单个文件不影响后续文件。
    expect(toasts).toEqual(['direct upload failed', '已上传 ok.png'])
    expect(resourceNodeCommands(executeCommands)).toHaveLength(1)
    expect(resourceNodeCommands(executeCommands)[0]?.name).toBe('ok.png')
    // 两个文件的 progress 都被清理。
    expect(progressCalls).toContainEqual([localIdOf(failed), null])
    expect(progressCalls).toContainEqual([localIdOf(ok, 1), null])
  })

  it('skips unsupported file types without touching the storage pipeline', async () => {
    const { result, storageService, executeCommands, toasts } = createHarness()

    await act(async () => {
      await result.current.uploadFiles([
        new File(['bad'], 'bad.bin', { type: 'application/octet-stream' }),
        new File(['png'], 'ok.png'),
      ])
    })

    expect(toasts[0]).toBe('不支持的文件类型：bad.bin')
    expect(storageService.reserveUpload).toHaveBeenCalledTimes(1)
    expect(resourceNodeCommands(executeCommands)).toHaveLength(1)
  })

  it('abandons in-flight uploads and clears progress when the canvas is switched', async () => {
    let resolveHash!: (value: string) => void
    const hashGate = new Promise<string>((resolve) => {
      resolveHash = resolve
    })
    const { result, storageService, executeCommands, reserveNodeAlias, progressCalls } = createHarness({
      hashFile: vi.fn(() => hashGate),
    })
    const file = new File(['slow'], 'slow.png')
    const localId = localIdOf(file)

    let uploadPromise!: Promise<void>
    act(() => {
      uploadPromise = result.current.uploadFiles([file])
    })
    // 画布切换：作废在途任务并清空 progress。
    act(() => {
      result.current.resetUploads()
    })
    await act(async () => {
      resolveHash('sha')
      await uploadPromise
    })

    expect(storageService.reserveUpload).not.toHaveBeenCalled()
    expect(executeCommands).not.toHaveBeenCalled()
    expect(reserveNodeAlias).not.toHaveBeenCalled()
    expect(progressCalls).toEqual([
      [localId, 0.1],
      [localId, null],
    ])
  })

  it('re-arms uploads after a reset so the next batch starts fresh', async () => {
    const { result, storageService, executeCommands } = createHarness()

    act(() => {
      result.current.resetUploads()
    })
    await act(async () => {
      await result.current.uploadFiles([new File(['png'], 'after.png')])
    })

    expect(storageService.reserveUpload).toHaveBeenCalledOnce()
    expect(storageService.uploadFile).toHaveBeenCalledOnce()
    expect(executeCommands).toHaveBeenCalledOnce()
  })

  it('does not start queued files of an abandoned batch after reset', async () => {
    let resolveFirst!: (value: string) => void
    const firstGate = new Promise<string>((resolve) => {
      resolveFirst = resolve
    })
    const { result, storageService, executeCommands, progressCalls, toasts } = createHarness({
      hashFile: vi.fn()
        .mockImplementationOnce(() => firstGate)
        .mockResolvedValue('second:sha'),
    })
    const first = new File(['a'], 'a.png')
    const second = new File(['b'], 'b.png')
    let uploadPromise!: Promise<void>
    act(() => {
      uploadPromise = result.current.uploadFiles([first, second])
    })
    act(() => {
      result.current.resetUploads()
    })
    await act(async () => {
      resolveFirst('first:sha')
      await uploadPromise
    })

    // 批次已作废：第二个（排队中的）文件不得再产生进度、预留、命令或 toast。
    expect(toasts).toEqual([])
    expect(storageService.reserveUpload).not.toHaveBeenCalled()
    expect(executeCommands).not.toHaveBeenCalled()
    expect(progressCalls).toEqual([
      [localIdOf(first), 0.1],
      [localIdOf(first), null],
    ])
  })

  it('releases the reserved alias and reports the error when the create command fails', async () => {
    const { result, executeCommands, releaseNodeAlias, toasts, progressCalls } = createHarness()
    executeCommands.mockRejectedValueOnce(new Error('命令冲突'))
    const file = new File(['png'], 'cmd.png')
    const localId = localIdOf(file)

    await act(async () => {
      await result.current.uploadFiles([file])
    })

    expect(releaseNodeAlias).toHaveBeenCalledWith('cmd.png')
    expect(toasts).toEqual(['命令冲突'])
    expect(progressCalls).toContainEqual([localId, null])
  })

  it('rejects invalid upload handles from the storage service', async () => {
    const { result, storageService, executeCommands, toasts } = createHarness()
    storageService.reserveUpload.mockResolvedValue(pendingReservation('not-a-uuid'))
    storageService.completeUpload.mockResolvedValue({ ...readyReservation(), id: 'not-a-uuid' })

    await act(async () => {
      await result.current.uploadFiles([new File(['x'], 'bad-handle.png')])
    })

    expect(executeCommands).not.toHaveBeenCalled()
    expect(toasts).toEqual(['存储服务返回了无效的上传句柄'])
  })

  it('ignores uploads without a canvas id', async () => {
    const { result, storageService } = createHarness({ canvasId: null })

    await act(async () => {
      await result.current.uploadFiles([new File(['x'], 'a.png')])
    })

    expect(storageService.reserveUpload).not.toHaveBeenCalled()
  })
})
