import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { comfyuiService } from '@/shared/api/comfyui-service'
import { useComfyuiRunLifecycle } from '@/features/comfyui/useComfyuiRunLifecycle'
import type {
  ComfyuiWorkflowJobDTO,
  ComfyuiWorkflowRunDTO,
} from '@/shared/api/contracts/comfyui'

vi.mock('@/shared/api/comfyui-service', () => ({
  comfyuiService: {
    runWorkflow: vi.fn(),
    getRun: vi.fn(),
    cancelRun: vi.fn(),
    uploadFile: vi.fn(),
  },
}))

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
    workflowId: 'workflow-1',
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
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(comfyuiService.uploadFile).mockResolvedValue({
      key: 'comfyui-inputs/image-upscale/input.png',
      filename: 'input.png',
      contentType: 'image/png',
    })
    vi.mocked(comfyuiService.runWorkflow).mockResolvedValue(makeRun())
    vi.mocked(comfyuiService.getRun).mockResolvedValue(makeJob('completed'))
    vi.mocked(comfyuiService.cancelRun).mockResolvedValue({ runId: 'run-1', cancelled: true })
  })

  it('uploads files, submits the run, and loads its initial job with the resolved selector', async () => {
    const { result } = renderHook(() =>
      useComfyuiRunLifecycle({ apiName: 'image-upscale', defaultSelector: null }),
    )

    await act(async () => {
      await result.current.submit({
        parameters: { prompt: 'hello' },
        files: { image: new File(['pixels'], 'input.png', { type: 'image/png' }) },
      })
    })

    expect(comfyuiService.uploadFile).toHaveBeenCalledWith('image-upscale', expect.any(File))
    expect(comfyuiService.runWorkflow).toHaveBeenCalledWith('image-upscale', {
      parameters: { prompt: 'hello' },
      files: {
        image: {
          key: 'comfyui-inputs/image-upscale/input.png',
          filename: 'input.png',
          contentType: 'image/png',
        },
      },
    })
    expect(comfyuiService.getRun).toHaveBeenCalledWith('run-1', '$.outputs')
    expect(result.current).toMatchObject({
      selector: '$.outputs',
      run: { runId: 'run-1', status: 'completed' },
      job: { runId: 'run-1', status: 'completed' },
      operationPending: false,
    })
  })

  it('reloads the current job when cancellation is no longer accepted by the server', async () => {
    vi.mocked(comfyuiService.getRun).mockResolvedValue(makeJob('running'))
    vi.mocked(comfyuiService.cancelRun).mockResolvedValue({ runId: 'run-1', cancelled: false })
    const { result } = renderHook(() =>
      useComfyuiRunLifecycle({ apiName: 'image-upscale', defaultSelector: '$.outputs' }),
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
    vi.mocked(comfyuiService.uploadFile).mockRejectedValueOnce(new Error('upload rejected'))
    const { result } = renderHook(() =>
      useComfyuiRunLifecycle({ apiName: 'image-upscale', defaultSelector: '$.outputs' }),
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
      useComfyuiRunLifecycle({ apiName: 'image-upscale', defaultSelector: '$.outputs' }),
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
})
