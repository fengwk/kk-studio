import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { comfyuiService } from '@/shared/api/comfyui-service'
import { storageService } from '@/shared/api/storage-service'
import { useComfyuiRunLifecycle } from '@/features/comfyui/useComfyuiRunLifecycle'
import type {
  ComfyuiWorkflowJobDTO,
  ComfyuiWorkflowRunDTO,
} from '@/shared/api/contracts/comfyui'
import type { StorageUploadDTO } from '@/shared/api/contracts/storage'

vi.mock('@/shared/api/comfyui-service', () => ({
  comfyuiService: {
    runWorkflow: vi.fn(),
    getRun: vi.fn(),
    cancelRun: vi.fn(),
  },
}))

vi.mock('@/shared/api/storage-service', () => ({
  storageService: {
    reserveUpload: vi.fn(),
    uploadFile: vi.fn(),
    completeUpload: vi.fn(),
    deleteUpload: vi.fn(),
  },
}))

const testWorkflowId = '37d4fa8b-00cf-4dbd-a44e-53934d6a7567'

function makeRun(overrides: Partial<ComfyuiWorkflowRunDTO> = {}): ComfyuiWorkflowRunDTO {
  return {
    runId: 'run-1',
    status: 'pending',
    defaultSelector: '$.outputs',
    ...overrides,
  }
}

function makeJob(status: string): ComfyuiWorkflowJobDTO {
  return {
    runId: 'run-1',
    status,
    priority: 0,
    createTime: 1,
    updateTime: 2,
    workflowId: testWorkflowId,
    executionStartTime: 1,
    executionEndTime: status === 'completed' ? 2 : null,
    outputsCount: 0,
    executionError: null,
    executionStatus: null,
    workflow: null,
    previewOutput: null,
    result: null,
  }
}

describe('useComfyuiRunLifecycle', () => {
  const fakeHash = vi.fn(async () => 'mock-sha256-hex')

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(storageService.reserveUpload).mockResolvedValue({
      id: 'up-1',
      state: 'PENDING',
      blobId: null,
      presignedPut: { method: 'PUT', url: 'https://s3.test/up-1', headers: {} },
      expiresAt: '2026-08-12T00:00:00Z',
    } satisfies StorageUploadDTO)
    vi.mocked(storageService.uploadFile).mockResolvedValue()
    vi.mocked(storageService.completeUpload).mockResolvedValue({
      id: 'up-1',
      state: 'READY',
      blobId: 'blob-1',
      presignedPut: null,
      expiresAt: '2026-08-12T00:00:00Z',
    } satisfies StorageUploadDTO)
    vi.mocked(storageService.deleteUpload).mockResolvedValue()

    vi.mocked(comfyuiService.runWorkflow).mockResolvedValue(makeRun())
    vi.mocked(comfyuiService.getRun).mockResolvedValue(makeJob('completed'))
    vi.mocked(comfyuiService.cancelRun).mockResolvedValue({ runId: 'run-1', cancelled: true })
  })

  it('uploads files via Storage, submits run with canonical UUID and { blobId, filename }, and releases handles in finally', async () => {
    const { result } = renderHook(() =>
      useComfyuiRunLifecycle({
        workflowId: testWorkflowId,
        defaultSelector: null,
        hashFile: fakeHash,
      }),
    )

    await act(async () => {
      await result.current.submit({
        parameters: { prompt: 'hello' },
        files: { image: new File(['pixels'], 'input.png', { type: 'image/png' }) },
      })
    })

    expect(storageService.reserveUpload).toHaveBeenCalledWith({
      filename: 'input.png',
      mediaType: 'image/png',
      sizeBytes: 6,
      sha256: 'mock-sha256-hex',
    })
    expect(storageService.uploadFile).toHaveBeenCalledTimes(1)
    expect(storageService.completeUpload).toHaveBeenCalledWith('up-1')
    expect(comfyuiService.runWorkflow).toHaveBeenCalledWith(testWorkflowId, {
      parameters: { prompt: 'hello' },
      files: {
        image: {
          blobId: 'blob-1',
          filename: 'input.png',
        },
      },
    })
    // 始终在 finally 释放 upload handle
    expect(storageService.deleteUpload).toHaveBeenCalledWith('up-1')
    expect(comfyuiService.getRun).toHaveBeenCalledWith('run-1', '$.outputs')
    expect(result.current).toMatchObject({
      selector: '$.outputs',
      run: { runId: 'run-1', status: 'completed' },
      job: { runId: 'run-1', status: 'completed' },
      operationPending: false,
    })
  })

  it('always releases upload handles in finally even when runWorkflow throws', async () => {
    vi.mocked(comfyuiService.runWorkflow).mockRejectedValueOnce(new Error('workflow rejected'))
    const { result } = renderHook(() =>
      useComfyuiRunLifecycle({
        workflowId: testWorkflowId,
        defaultSelector: '$.outputs',
        hashFile: fakeHash,
      }),
    )

    await act(async () => {
      await result.current.submit({
        parameters: {},
        files: { image: new File(['pixels'], 'input.png', { type: 'image/png' }) },
      })
    })

    expect(storageService.reserveUpload).toHaveBeenCalledTimes(1)
    expect(storageService.deleteUpload).toHaveBeenCalledWith('up-1')
    expect(result.current).toMatchObject({ error: 'workflow rejected', operationPending: false, run: null })
  })

  it('reloads the current job when cancellation is no longer accepted by the server', async () => {
    vi.mocked(comfyuiService.getRun).mockResolvedValue(makeJob('running'))
    vi.mocked(comfyuiService.cancelRun).mockResolvedValue({ runId: 'run-1', cancelled: false })
    const { result } = renderHook(() =>
      useComfyuiRunLifecycle({ workflowId: testWorkflowId, defaultSelector: '$.outputs', hashFile: fakeHash }),
    )

    await act(async () => {
      await result.current.submit({ parameters: {}, files: {} })
    })
    await waitFor(() => expect(result.current.run?.status).toBe('running'))

    await act(async () => {
      await result.current.cancel()
    })

    expect(comfyuiService.cancelRun).toHaveBeenCalledWith('run-1')
    expect(comfyuiService.getRun).toHaveBeenCalledTimes(2)
    expect(result.current.operationPending).toBe(false)
  })

  it('exposes upload and refresh failures without leaving an operation pending', async () => {
    vi.mocked(storageService.reserveUpload).mockRejectedValueOnce(new Error('upload rejected'))
    const { result } = renderHook(() =>
      useComfyuiRunLifecycle({ workflowId: testWorkflowId, defaultSelector: '$.outputs', hashFile: fakeHash }),
    )

    await act(async () => {
      await result.current.submit({
        parameters: {},
        files: { image: new File(['pixels'], 'input.png', { type: 'image/png' }) },
      })
    })

    expect(result.current).toMatchObject({ error: 'upload rejected', operationPending: false, run: null })

    await act(async () => {
      await result.current.submit({ parameters: {}, files: {} })
    })
    vi.mocked(comfyuiService.getRun).mockRejectedValueOnce(new Error('refresh rejected'))
    await act(async () => {
      await result.current.refresh()
    })

    expect(result.current).toMatchObject({ error: 'refresh rejected', operationPending: false })
  })

  it('keeps the current run and reports a rejected cancellation', async () => {
    vi.mocked(comfyuiService.getRun).mockResolvedValue(makeJob('running'))
    vi.mocked(comfyuiService.cancelRun).mockRejectedValue(new Error('cancel rejected'))
    const { result } = renderHook(() =>
      useComfyuiRunLifecycle({ workflowId: testWorkflowId, defaultSelector: '$.outputs', hashFile: fakeHash }),
    )

    await act(async () => {
      await result.current.submit({ parameters: {}, files: {} })
    })
    await act(async () => {
      await result.current.cancel()
    })

    expect(result.current).toMatchObject({
      error: 'cancel rejected',
      operationPending: false,
      run: { runId: 'run-1', status: 'running' },
    })
  })

  it('ignores refresh and cancel without an active run', async () => {
    const { result } = renderHook(() =>
      useComfyuiRunLifecycle({ workflowId: testWorkflowId, defaultSelector: '$.outputs', hashFile: fakeHash }),
    )

    // 无 run 时 refresh/cancel 都是 no-op，不触碰服务也不进入 pending 状态。
    await act(async () => {
      await result.current.refresh()
      await result.current.cancel()
    })

    expect(comfyuiService.getRun).not.toHaveBeenCalled()
    expect(comfyuiService.cancelRun).not.toHaveBeenCalled()
    expect(result.current.operationPending).toBe(false)
  })

  it('keeps submit mutually exclusive with an in-flight cancel', async () => {
    vi.mocked(comfyuiService.getRun).mockResolvedValue(makeJob('completed'))
    let resolveCancel!: (value: { runId: string; cancelled: boolean }) => void
    vi.mocked(comfyuiService.cancelRun).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveCancel = resolve
        }),
    )
    const { result } = renderHook(() =>
      useComfyuiRunLifecycle({ workflowId: testWorkflowId, defaultSelector: '$.outputs', hashFile: fakeHash }),
    )

    await act(async () => {
      await result.current.submit({ parameters: {}, files: {} })
    })
    await act(async () => {
      void result.current.cancel()
      // cancel 进行中 submit 是 no-op（互斥），cancel 的结果仍然生效。
      await result.current.submit({ parameters: {}, files: {} })
      resolveCancel({ runId: 'run-1', cancelled: true })
      await Promise.resolve()
    })

    expect(comfyuiService.cancelRun).toHaveBeenCalledTimes(1)
    expect(comfyuiService.runWorkflow).toHaveBeenCalledTimes(1)
    expect(result.current.run).toMatchObject({ runId: 'run-1', status: 'cancelled' })
    expect(result.current.operationPending).toBe(false)
  })

  it('sequentially uploads files and ensures all reserved handles are deleted without submitting run on partial failure', async () => {
    vi.mocked(storageService.reserveUpload)
      .mockResolvedValueOnce({
        id: 'up-first',
        state: 'READY',
        blobId: 'blob-first',
        presignedPut: null,
        expiresAt: '2026-08-12T00:00:00Z',
      } satisfies StorageUploadDTO)
      .mockRejectedValueOnce(new Error('second file reserve failed'))

    const { result } = renderHook(() =>
      useComfyuiRunLifecycle({
        workflowId: testWorkflowId,
        defaultSelector: '$.outputs',
        hashFile: fakeHash,
      }),
    )

    await act(async () => {
      await result.current.submit({
        parameters: { prompt: 'hello' },
        files: {
          first: new File(['first'], 'first.png', { type: 'image/png' }),
          second: new File(['second'], 'second.png', { type: 'image/png' }),
        },
      })
    })

    expect(storageService.reserveUpload).toHaveBeenCalledTimes(2)
    expect(storageService.deleteUpload).toHaveBeenCalledWith('up-first')
    expect(comfyuiService.runWorkflow).not.toHaveBeenCalled()
    expect(result.current).toMatchObject({
      error: 'second file reserve failed',
      operationPending: false,
      run: null,
    })
  })
})
