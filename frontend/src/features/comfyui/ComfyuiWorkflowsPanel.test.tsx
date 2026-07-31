import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ComfyuiWorkflowsPanel } from '@/features/comfyui/ComfyuiWorkflowsPanel'
import type { ComfyuiWorkflowApiDTO } from '@/shared/api/contracts/comfyui'

function makeWorkflow(id: string, name: string, apiName: string, enabled = true): ComfyuiWorkflowApiDTO {
  return {
    id,
    apiName,
    name,
    description: null,
    workflowJson: '{}',
    inputBindingsJson: '[]',
    defaultSelector: null,
    enabled,
    createTime: null,
    updateTime: null,
  }
}

describe('ComfyuiWorkflowsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders the create card alongside every workflow card with delete-pending disabled', () => {
    const onCreate = vi.fn()
    render(
      <ComfyuiWorkflowsPanel
        workflows={[makeWorkflow('1', 'Alpha', 'alpha'), makeWorkflow('2', 'Beta', 'beta', false)]}
        deletePending
        onCreate={onCreate}
        onRun={vi.fn()}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: '新建 ComfyUI Workflow' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '运行 Alpha' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '运行 Beta' })).toBeDisabled()
    const betaCard = screen.getByRole('button', { name: '运行 Beta' }).closest('article')!
    expect(within(betaCard).getByRole('button', { name: '删除 Beta' })).toBeDisabled()
    // deletePending applies to every card, even enabled ones.
    const alphaCard = screen.getByRole('button', { name: '运行 Alpha' }).closest('article')!
    expect(within(alphaCard).getByRole('button', { name: '删除 Alpha' })).toBeDisabled()
  })

  it('leaves delete enabled on every card when no delete is pending', () => {
    render(
      <ComfyuiWorkflowsPanel
        workflows={[makeWorkflow('1', 'Alpha', 'alpha'), makeWorkflow('2', 'Beta', 'beta', false)]}
        deletePending={false}
        onCreate={vi.fn()}
        onRun={vi.fn()}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
      />,
    )

    const alphaCard = screen.getByRole('button', { name: '运行 Alpha' }).closest('article')!
    expect(within(alphaCard).getByRole('button', { name: '删除 Alpha' })).not.toBeDisabled()
    const betaCard = screen.getByRole('button', { name: '运行 Beta' }).closest('article')!
    expect(within(betaCard).getByRole('button', { name: '删除 Beta' })).not.toBeDisabled()
  })

  it('routes create, run, edit and delete clicks to the matching workflow', async () => {
    const onCreate = vi.fn()
    const onRun = vi.fn()
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const alpha = makeWorkflow('1', 'Alpha', 'alpha')
    const beta = makeWorkflow('2', 'Beta', 'beta')
    const user = userEvent.setup()
    render(
      <ComfyuiWorkflowsPanel
        workflows={[alpha, beta]}
        deletePending={false}
        onCreate={onCreate}
        onRun={onRun}
        onEdit={onEdit}
        onDelete={onDelete}
      />,
    )

    await user.click(screen.getByRole('button', { name: '新建 ComfyUI Workflow' }))
    await user.click(screen.getByRole('button', { name: '运行 Alpha' }))
    await user.click(screen.getByRole('button', { name: '编辑 Beta' }))
    await user.click(screen.getByRole('button', { name: '删除 Alpha' }))

    expect(onCreate).toHaveBeenCalledTimes(1)
    expect(onRun).toHaveBeenCalledTimes(1)
    expect(onRun).toHaveBeenCalledWith(alpha)
    expect(onEdit).toHaveBeenCalledTimes(1)
    expect(onEdit).toHaveBeenCalledWith(beta)
    expect(onDelete).toHaveBeenCalledTimes(1)
    expect(onDelete).toHaveBeenCalledWith(alpha)
  })
})
