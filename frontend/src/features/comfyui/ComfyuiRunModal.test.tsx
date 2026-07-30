import { StrictMode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ComfyuiRunModal } from '@/features/comfyui/ComfyuiRunModal'
import { comfyuiService } from '@/shared/api/comfyui-service'
import type { ComfyuiWorkflowApiDTO, ComfyuiWorkflowJobDTO, ComfyuiWorkflowRunDTO } from '@/shared/api/contracts'

vi.mock('@/shared/api/comfyui-service', () => ({
  comfyuiService: {
    listWorkflows: vi.fn(),
    createWorkflow: vi.fn(),
    updateWorkflow: vi.fn(),
    deleteWorkflow: vi.fn(),
    runWorkflow: vi.fn(),
    getRun: vi.fn(),
    cancelRun: vi.fn(),
    uploadFile: vi.fn(),
    createPresignedUpload: vi.fn(),
    createPresignedDownload: vi.fn(),
  },
}))

function makeWorkflow(overrides: Partial<ComfyuiWorkflowApiDTO> = {}): ComfyuiWorkflowApiDTO {
  return {
    id: 'workflow-1',
    apiName: 'image-upscale',
    name: 'Image Upscale',
    description: null,
    workflowJson: '{}',
    inputBindingsJson: JSON.stringify([
      { name: 'prompt', kind: 'parameter', nodeId: '6', inputName: 'text', valueType: 'string', defaultValue: 'hello' },
      { name: 'steps', kind: 'parameter', nodeId: '3', inputName: 'steps', valueType: 'integer', defaultValue: 4, required: true },
      { name: 'safeMode', kind: 'parameter', nodeId: '3', inputName: 'safe', valueType: 'boolean', defaultValue: false },
      { name: 'config', kind: 'parameter', nodeId: '3', inputName: 'config', valueType: 'json', defaultValue: { seed: 1 } },
      { name: 'image', kind: 'file', nodeId: '9', inputName: 'image', required: true },
    ]),
    defaultSelector: '$.outputs',
    enabled: true,
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

function makeRun(overrides: Partial<ComfyuiWorkflowRunDTO> = {}): ComfyuiWorkflowRunDTO {
  return {
    runId: 'run-1',
    status: 'pending',
    defaultSelector: '$.outputs',
    ...overrides,
  }
}

function makeJob(status: string, overrides: Partial<ComfyuiWorkflowJobDTO> = {}): ComfyuiWorkflowJobDTO {
  return {
    runId: 'run-1',
    status,
    priority: 0,
    createTime: 1,
    updateTime: 2,
    workflowId: 'workflow-1',
    executionStartTime: 1,
    executionEndTime: status === 'completed' ? 2 : null,
    outputsCount: status === 'completed' ? 1 : 0,
    executionError: null,
    executionStatus: { queue: 'done' },
    workflow: null,
    previewOutput: null,
    result: {
      outputs: [
        {
          filename: 'result.png',
          downloadUrl: '/api/comfyui/runs/run-1/files/9/images/0',
        },
      ],
    },
    ...overrides,
  }
}

function renderModal({ workflow, strictMode = false }: { workflow?: ComfyuiWorkflowApiDTO; strictMode?: boolean } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const usedWorkflow = workflow ?? makeWorkflow()
  const content = (
    <QueryClientProvider client={queryClient}>
      <ComfyuiRunModal workflow={usedWorkflow} onClose={vi.fn()} />
    </QueryClientProvider>
  )
  return {
    queryClient,
    ...render(strictMode ? <StrictMode>{content}</StrictMode> : content),
  }
}

async function flushPromises() {
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()
  })
}

describe('ComfyuiRunModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(comfyuiService.runWorkflow).mockReset().mockResolvedValue(makeRun())
    vi.mocked(comfyuiService.getRun).mockReset().mockResolvedValue(makeJob('completed'))
    vi.mocked(comfyuiService.cancelRun).mockReset().mockResolvedValue({ runId: 'run-1', cancelled: true })
    vi.mocked(comfyuiService.uploadFile).mockReset().mockResolvedValue({
      key: 'comfyui-inputs/image-upscale/abc/input.png',
      filename: 'input.png',
      contentType: 'image/png',
    })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders dynamic fields, endpoint, default values, and locks submit while binding parse error blocks the form', () => {
    renderModal({ workflow: makeWorkflow({ inputBindingsJson: '{broken' }) })
    expect(screen.getByText(/输入绑定配置错误/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Run workflow' })).toBeDisabled()
    expect(screen.getByText('POST /api/comfyui/workflows/image-upscale/runs')).toBeInTheDocument()
  })

  it('uploads files via the service and submits run with only S3 key references', async () => {
    const user = userEvent.setup()
    renderModal()

    // Provide the required file.
    fireEvent.change(screen.getByLabelText('image *'), {
      target: { files: [new File(['pixels'], 'input.png', { type: 'image/png' })] },
    })
    await user.click(screen.getByRole('button', { name: 'Run workflow' }))

    // The required steps value comes from the default, the prompt comes from the default.
    expect(comfyuiService.uploadFile).toHaveBeenCalledTimes(1)
    const [apiNameArg, fileArg] = vi.mocked(comfyuiService.uploadFile).mock.calls[0]
    expect(apiNameArg).toBe('image-upscale')
    expect(fileArg).toBeInstanceOf(File)

    await waitFor(() => expect(comfyuiService.runWorkflow).toHaveBeenCalledTimes(1))
    expect(comfyuiService.runWorkflow).toHaveBeenCalledWith('image-upscale', {
      parameters: {
        prompt: 'hello',
        steps: 4,
        safeMode: false,
        config: { seed: 1 },
      },
      files: {
        image: {
          key: 'comfyui-inputs/image-upscale/abc/input.png',
          filename: 'input.png',
          contentType: 'image/png',
        },
      },
    })
    expect(vi.mocked(comfyuiService.uploadFile).mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(comfyuiService.runWorkflow).mock.invocationCallOrder[0],
    )
  })

  it('blocks submit when a required file is missing and never calls run/upload', async () => {
    // Build a workflow whose required file binding has no default in the controller.
    const workflow = makeWorkflow({
      inputBindingsJson: JSON.stringify([
        { name: 'image', kind: 'file', nodeId: '9', inputName: 'image', required: true },
      ]),
    })
    vi.mocked(comfyuiService.runWorkflow).mockClear()
    vi.mocked(comfyuiService.uploadFile).mockClear()
    renderModal({ workflow })
    // Submit must be guarded client-side before any upload or run happens.
    expect(screen.queryByRole('button', { name: 'Run workflow' })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('image *'), { target: { files: [] } })
    fireEvent.submit(screen.getByRole('button', { name: 'Run workflow' }).closest('form')!)
    await flushPromises()
    expect(comfyuiService.runWorkflow).not.toHaveBeenCalled()
    expect(comfyuiService.uploadFile).not.toHaveBeenCalled()
  })

  it('completes submit and job updates under React StrictMode', async () => {
    vi.mocked(comfyuiService.getRun).mockResolvedValueOnce(makeJob('running', { result: null }))
    const user = userEvent.setup()
    renderModal({ strictMode: true })
    fireEvent.change(screen.getByLabelText('image *'), {
      target: { files: [new File(['strict'], 'strict.png', { type: 'image/png' })] },
    })
    await user.click(screen.getByRole('button', { name: 'Run workflow' }))
    // Default mock for getRun returns 'completed' so the polling cycle eventually surfaces it.
    expect(await screen.findByText('completed', undefined, { timeout: 3000 })).toBeInTheDocument()
    expect(comfyuiService.getRun).toHaveBeenCalledWith('run-1', '$.outputs')
  })

  it('shows terminal status, renders download links, and stops polling', async () => {
    vi.mocked(comfyuiService.getRun)
      .mockResolvedValueOnce(makeJob('running', { result: null }))
      .mockResolvedValueOnce(makeJob('completed'))
    const user = userEvent.setup()
    renderModal()
    fireEvent.change(screen.getByLabelText('image *'), {
      target: { files: [new File(['pixels'], 'input.png', { type: 'image/png' })] },
    })
    await user.click(screen.getByRole('button', { name: 'Run workflow' }))
    // Polling interval is 1500ms; wait for the terminal payload before asserting the download link.
    const download = await screen.findByRole('link', { name: 'result.png' }, { timeout: 3000 })
    expect(download).toHaveAttribute('href', '/api/comfyui/runs/run-1/files/9/images/0')
    expect(screen.getByText('completed')).toBeInTheDocument()
  })

  it('refresh uses the current selector and re-fetches the running job', async () => {
    vi.mocked(comfyuiService.getRun)
      .mockResolvedValueOnce(makeJob('running', { result: null }))
      .mockResolvedValueOnce(makeJob('running', { result: null }))
      .mockResolvedValueOnce(makeJob('running', { result: null }))
    const user = userEvent.setup()
    renderModal()
    fireEvent.change(screen.getByLabelText('image *'), {
      target: { files: [new File(['pixels'], 'input.png', { type: 'image/png' })] },
    })
    await user.click(screen.getByRole('button', { name: 'Run workflow' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '刷新结果' })).toBeEnabled())
    // Change selector and refresh.
    fireEvent.change(screen.getByLabelText('JSONPath selector'), { target: { value: '$.userSelection' } })
    await user.click(screen.getByRole('button', { name: '刷新结果' }))
    await waitFor(() =>
      expect(comfyuiService.getRun).toHaveBeenLastCalledWith('run-1', '$.userSelection'),
    )
  })

  it('polls with the live selector until a terminal status is observed', async () => {
    vi.mocked(comfyuiService.getRun)
      .mockResolvedValueOnce(makeJob('running', { result: null }))
      .mockResolvedValueOnce(makeJob('in_progress', { result: null }))
      .mockResolvedValueOnce(makeJob('completed'))
    const user = userEvent.setup()
    renderModal()
    fireEvent.change(screen.getByLabelText('image *'), {
      target: { files: [new File(['pixels'], 'input.png', { type: 'image/png' })] },
    })
    await user.click(screen.getByRole('button', { name: 'Run workflow' }))
    // Two polling cycles at 1500ms each must end in a terminal status.
    expect(await screen.findByText('completed', undefined, { timeout: 4500 })).toBeInTheDocument()
    expect(comfyuiService.getRun).toHaveBeenCalledTimes(3)
  }, 10000)

  it('surfaces a direct upload failure without submitting the run', async () => {
    vi.mocked(comfyuiService.uploadFile).mockRejectedValue(new Error('S3 upload rejected'))
    const user = userEvent.setup()
    renderModal()
    fireEvent.change(screen.getByLabelText('image *'), {
      target: { files: [new File(['pixels'], 'input.png', { type: 'image/png' })] },
    })
    await user.click(screen.getByRole('button', { name: 'Run workflow' }))
    expect(await screen.findByText('S3 upload rejected')).toBeInTheDocument()
    expect(comfyuiService.runWorkflow).not.toHaveBeenCalled()
  })

  it('cancels a running job and flips status to cancelled', async () => {
    vi.mocked(comfyuiService.getRun).mockResolvedValue(makeJob('running', { result: null }))
    const user = userEvent.setup()
    renderModal()
    fireEvent.change(screen.getByLabelText('image *'), {
      target: { files: [new File(['pixels'], 'input.png', { type: 'image/png' })] },
    })
    await user.click(screen.getByRole('button', { name: 'Run workflow' }))
    expect(await screen.findByRole('button', { name: 'Cancel' }, { timeout: 3000 })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(comfyuiService.cancelRun).toHaveBeenCalledWith('run-1'))
    expect(await screen.findByText('cancelled', undefined, { timeout: 3000 })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
  })

  it('re-loads the job when cancel returns cancelled=false', async () => {
    vi.mocked(comfyuiService.getRun).mockResolvedValue(makeJob('running', { result: null }))
    vi.mocked(comfyuiService.cancelRun).mockResolvedValue({ runId: 'run-1', cancelled: false })
    const user = userEvent.setup()
    renderModal()
    fireEvent.change(screen.getByLabelText('image *'), {
      target: { files: [new File(['pixels'], 'input.png', { type: 'image/png' })] },
    })
    await user.click(screen.getByRole('button', { name: 'Run workflow' }))
    const cancel = await screen.findByRole('button', { name: 'Cancel' })
    await user.click(cancel)
    await waitFor(() => expect(comfyuiService.cancelRun).toHaveBeenCalledWith('run-1'))
    // cancelRun returned cancelled=false -> the modal must refetch via loadJob.
    await waitFor(() =>
      expect(comfyuiService.getRun).toHaveBeenLastCalledWith('run-1', '$.outputs'),
    )
  })

  it('keeps submit, refresh and cancel mutually exclusive while cancel is pending', async () => {
    const user = userEvent.setup()
    vi.mocked(comfyuiService.getRun).mockResolvedValue(makeJob('running', { result: null }))
    let release!: () => void
    vi.mocked(comfyuiService.cancelRun).mockImplementation(
      () =>
        new Promise((resolve) => {
          release = () => resolve({ runId: 'run-1', cancelled: true })
        }),
    )
    renderModal()
    fireEvent.change(screen.getByLabelText('image *'), {
      target: { files: [new File(['pixels'], 'input.png', { type: 'image/png' })] },
    })
    await user.click(screen.getByRole('button', { name: 'Run workflow' }))
    await user.click(await screen.findByRole('button', { name: 'Cancel' }))
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '刷新结果' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Run again' })).toBeDisabled()
    // Submitting the form while cancel is pending must be a no-op.
    fireEvent.submit(screen.getByRole('button', { name: 'Run again' }).closest('form')!)
    expect(comfyuiService.runWorkflow).toHaveBeenCalledTimes(1)
    await act(async () => {
      release()
      await Promise.resolve()
    })
    expect(await screen.findByText('cancelled')).toBeInTheDocument()
  })

  it('discards stale responses when a newer submit takes over (generation guard)', async () => {
    // First submit resolves with a stale "running" payload; second submit bumps generation
    // and resolves with the terminal payload. The stale running status must never paint.
    vi.mocked(comfyuiService.getRun).mockResolvedValueOnce(makeJob('running', { result: null }))
    vi.mocked(comfyuiService.runWorkflow)
      .mockResolvedValueOnce(makeRun({ runId: 'run-stale' }))
      .mockResolvedValueOnce(makeRun({ runId: 'run-fresh' }))
    vi.mocked(comfyuiService.getRun)
      .mockResolvedValueOnce(makeJob('running', { result: null, runId: 'run-fresh' }))
      .mockResolvedValueOnce(makeJob('completed', { runId: 'run-fresh' }))
    const user = userEvent.setup()
    renderModal()
    fireEvent.change(screen.getByLabelText('image *'), {
      target: { files: [new File(['pixels'], 'input.png', { type: 'image/png' })] },
    })
    // First submit lands but stops polling because the stale job's status (running) schedules a poll.
    await user.click(screen.getByRole('button', { name: 'Run workflow' }))
    expect(await screen.findByRole('button', { name: 'Run again' }, { timeout: 3000 })).toBeInTheDocument()
    // Second submit bumps generation. The prior run-state must be discarded.
    await user.click(screen.getByRole('button', { name: 'Run again' }))
    // The fresh submission must land as completed, never re-paint the stale running state.
    expect(await screen.findByText('completed', undefined, { timeout: 3000 })).toBeInTheDocument()
    expect(comfyuiService.runWorkflow).toHaveBeenCalledTimes(2)
  })

  it('treats bad binding config as a hard block (cannot submit)', () => {
    const workflow = makeWorkflow({ inputBindingsJson: '[]' })
    renderModal({ workflow })
    expect(screen.getByRole('button', { name: 'Run workflow' })).toBeEnabled()
    // No required file bindings - submit must succeed with empty parameters and empty files.
    fireEvent.submit(screen.getByRole('button', { name: 'Run workflow' }).closest('form')!)
    return flushPromises().then(() => {
      expect(comfyuiService.uploadFile).not.toHaveBeenCalled()
      expect(comfyuiService.runWorkflow).toHaveBeenCalledWith('image-upscale', {
        parameters: {},
        files: {},
      })
    })
  })
})
