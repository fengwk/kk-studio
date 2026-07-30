import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ComfyuiWorkflowEditorModal } from '@/features/comfyui/ComfyuiWorkflowEditorModal'
import type { ComfyuiWorkflowApiDTO } from '@/shared/api/contracts'

const baseDraft = {
  apiName: 'image-api',
  name: 'Image API',
  description: '',
  workflowJson: '{}',
  inputBindingsJson: '[]',
  defaultSelector: '',
  enabled: true,
}

const editing: ComfyuiWorkflowApiDTO = {
  id: 'workflow-1',
  apiName: 'image-api',
  name: 'Image API',
  description: null,
  workflowJson: '{}',
  inputBindingsJson: '[]',
  defaultSelector: null,
  enabled: false,
  createTime: null,
  updateTime: null,
}

describe('ComfyuiWorkflowEditorModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders nothing when modal is null', () => {
    const { container } = render(
      <ComfyuiWorkflowEditorModal
        modal={null}
        draft={baseDraft}
        pending={false}
        error={null}
        onClose={vi.fn()}
        onDraftChange={vi.fn()}
        onSubmit={vi.fn()}
      />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('renders create mode and propagates every draft field change', async () => {
    const onDraftChange = vi.fn()
    const onSubmit = vi.fn((event: React.FormEvent<HTMLFormElement>) => event.preventDefault())
    const user = userEvent.setup()
    render(
      <ComfyuiWorkflowEditorModal
        modal={{ mode: 'create', workflow: null }}
        draft={baseDraft}
        pending={false}
        error={null}
        onClose={vi.fn()}
        onDraftChange={onDraftChange}
        onSubmit={onSubmit}
      />,
    )

    expect(screen.getByRole('heading', { name: '新建 ComfyUI Workflow' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '确认创建' })).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('API name'), { target: { value: 'foo' } })
    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Foo' } })
    fireEvent.change(screen.getByLabelText('Description'), { target: { value: 'short desc' } })
    fireEvent.change(screen.getByLabelText('API-format workflow JSON'), { target: { value: '{"1":{}}' } })
    fireEvent.change(screen.getByLabelText('Input bindings JSON'), {
      target: { value: '[{"name":"x","kind":"file","nodeId":"1","inputName":"x"}]' },
    })
    fireEvent.change(screen.getByPlaceholderText('$.outputs'), { target: { value: '$.out' } })
    fireEvent.click(screen.getByRole('checkbox'))

    expect(onDraftChange).toHaveBeenCalled()
    expect(onDraftChange.mock.calls.some(([next]) => next.apiName === 'foo')).toBe(true)
    expect(onDraftChange.mock.calls.some(([next]) => next.name === 'Foo')).toBe(true)
    expect(onDraftChange.mock.calls.some(([next]) => next.description === 'short desc')).toBe(true)
    expect(onDraftChange.mock.calls.some(([next]) => next.workflowJson === '{"1":{}}')).toBe(true)
    expect(
      onDraftChange.mock.calls.some(([next]) => next.inputBindingsJson.includes('"name":"x"')),
    ).toBe(true)
    expect(onDraftChange.mock.calls.some(([next]) => next.defaultSelector === '$.out')).toBe(true)
    expect(onDraftChange.mock.calls.some(([next]) => next.enabled === false)).toBe(true)

    // Submit fires onSubmit through the form.
    fireEvent.submit(screen.getByRole('button', { name: '确认创建' }).closest('form')!)
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    // Reference userEvent to keep the variable used when the import is inspected.
    void user
  })

  it('renders edit mode with the save button label and edits API name separately from display name', () => {
    const onDraftChange = vi.fn()
    render(
      <ComfyuiWorkflowEditorModal
        modal={{ mode: 'edit', workflow: editing }}
        draft={{ ...baseDraft, apiName: editing.apiName, name: editing.name, enabled: editing.enabled }}
        pending={false}
        error={null}
        onClose={vi.fn()}
        onDraftChange={onDraftChange}
        onSubmit={vi.fn()}
      />,
    )

    expect(screen.getByRole('heading', { name: '编辑 ComfyUI Workflow' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存修改' })).toBeInTheDocument()
    expect(screen.getByLabelText('API name')).toHaveValue('image-api')
    expect(screen.getByLabelText('Display name')).toHaveValue('Image API')
    expect(screen.getByRole('checkbox')).not.toBeChecked()
  })

  it('surfaces error state via StateBlock when error is provided', () => {
    render(
      <ComfyuiWorkflowEditorModal
        modal={{ mode: 'create', workflow: null }}
        draft={baseDraft}
        pending={false}
        error="API name 必须以小写字母开头"
        onClose={vi.fn()}
        onDraftChange={vi.fn()}
        onSubmit={vi.fn()}
      />,
    )

    expect(screen.getByText('API name 必须以小写字母开头')).toBeInTheDocument()
  })

  it('blocks submit and backdrop close while pending', async () => {
    const onSubmit = vi.fn((event: React.FormEvent<HTMLFormElement>) => event.preventDefault())
    const onClose = vi.fn()
    const user = userEvent.setup()
    render(
      <ComfyuiWorkflowEditorModal
        modal={{ mode: 'create', workflow: null }}
        draft={baseDraft}
        pending
        error={null}
        onClose={onClose}
        onDraftChange={vi.fn()}
        onSubmit={onSubmit}
      />,
    )

    expect(screen.getByRole('button', { name: '确认创建' })).toBeDisabled()
    // Click the disabled backdrop - no closure call should happen.
    await user.click(screen.getByText('关闭'))
    expect(onClose).not.toHaveBeenCalled()
  })
})
