import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ComfyuiWorkflowCard } from '@/features/comfyui/ComfyuiWorkflowCard'
import type { ComfyuiWorkflowApiDTO } from '@/shared/api/contracts/comfyui'

function makeWorkflow(overrides: Partial<ComfyuiWorkflowApiDTO> = {}): ComfyuiWorkflowApiDTO {
  return {
    id: 'workflow-1',
    apiName: 'image-upscale',
    name: 'Image Upscale',
    description: 'Upscale reference image',
    workflowJson: '{"9":{"inputs":{"image":"input.png"},"class_type":"LoadImage"}}',
    inputBindingsJson: JSON.stringify([
      { name: 'prompt', kind: 'parameter', nodeId: '1', inputName: 'text', valueType: 'string' },
      { name: 'image', kind: 'file', nodeId: '9', inputName: 'image', required: true },
    ]),
    defaultSelector: '$.outputs',
    enabled: true,
    createTime: null,
    updateTime: null,
    ...overrides,
  }
}

describe('ComfyuiWorkflowCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders enabled card with name, description, API endpoint, and binding count', () => {
    const onRun = vi.fn()
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    render(
      <ComfyuiWorkflowCard
        workflow={makeWorkflow()}
        deletePending={false}
        onRun={onRun}
        onEdit={onEdit}
        onDelete={onDelete}
      />,
    )

    expect(screen.getByRole('heading', { name: 'Image Upscale' })).toBeInTheDocument()
    expect(screen.getByText('Upscale reference image')).toBeInTheDocument()
    expect(screen.getByText('POST /api/comfyui/workflows/image-upscale/runs')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(screen.getByText('Enabled')).toHaveClass('enabled')
    expect(screen.getByText('$.outputs')).toBeInTheDocument()
    expect(screen.queryByText(/绑定配置无法解析/)).not.toBeInTheDocument()
  })

  it('falls back to apiName when description is null', () => {
    render(
      <ComfyuiWorkflowCard
        workflow={makeWorkflow({ description: null })}
        deletePending={false}
        onRun={vi.fn()}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
      />,
    )

    expect(screen.getByText('image-upscale')).toBeInTheDocument()
  })

  it('renders disabled card with disabled badge, disabled run button, and the whole-result fallback for selector', () => {
    render(
      <ComfyuiWorkflowCard
        workflow={makeWorkflow({ enabled: false, defaultSelector: null })}
        deletePending={false}
        onRun={vi.fn()}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
      />,
    )

    expect(screen.getByText('Disabled')).toHaveClass('disabled')
    expect(screen.getByRole('button', { name: '运行 Image Upscale' })).toBeDisabled()
    expect(screen.getByText('whole result')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '编辑 Image Upscale' })).not.toBeDisabled()
  })

  it('renders an inline error banner when bindings JSON cannot be parsed', () => {
    render(
      <ComfyuiWorkflowCard
        workflow={makeWorkflow({ inputBindingsJson: '{broken' })}
        deletePending={false}
        onRun={vi.fn()}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
      />,
    )

    const banner = screen.getByText(/绑定配置无法解析/)
    expect(banner).toBeInTheDocument()
    expect(banner).toHaveTextContent('绑定配置无法解析：')
    expect(screen.getByText('配置错误')).toBeInTheDocument()
  })

  it('disables the delete button while deletePending is true', () => {
    render(
      <ComfyuiWorkflowCard
        workflow={makeWorkflow()}
        deletePending
        onRun={vi.fn()}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: '删除 Image Upscale' })).toBeDisabled()
  })

  it('wires run, edit and delete callbacks with aria labels', async () => {
    const onRun = vi.fn()
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const user = userEvent.setup()
    render(
      <ComfyuiWorkflowCard
        workflow={makeWorkflow()}
        deletePending={false}
        onRun={onRun}
        onEdit={onEdit}
        onDelete={onDelete}
      />,
    )

    await user.click(screen.getByRole('button', { name: '运行 Image Upscale' }))
    await user.click(screen.getByRole('button', { name: '编辑 Image Upscale' }))
    await user.click(screen.getByRole('button', { name: '删除 Image Upscale' }))

    expect(onRun).toHaveBeenCalledTimes(1)
    expect(onEdit).toHaveBeenCalledTimes(1)
    expect(onDelete).toHaveBeenCalledTimes(1)
  })
})
