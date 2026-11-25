import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { EnvironmentList } from './EnvironmentList'
import { useIdeWorkspace } from './ide-workspace-context'
import type { EnvironmentDTO } from '@/shared/api/generated/contracts'

vi.mock('./ide-workspace-context', () => ({ useIdeWorkspace: vi.fn() }))

const environment = (overrides: Partial<EnvironmentDTO> = {}): EnvironmentDTO => ({
  id: 'env-1', name: 'Dev', daemonConfig: {} as EnvironmentDTO['daemonConfig'], status: 'READY', ready: true,
  statusExpiresAt: Date.now() + 60_000, capabilities: [], version: '1', createdAt: 0, updatedAt: 0, ...overrides,
})

describe('EnvironmentList', () => {
  it('shows environments with status and opens available environment using resolved target', () => {
    const target = { kind: 'vscode', environmentId: 'env-1', viewId: 'view-1' } as const
    const resolveTarget = vi.fn(() => target)
    vi.mocked(useIdeWorkspace).mockReturnValue({ environments: [environment()], resolveTarget } as never)
    const open = vi.fn()
    render(<EnvironmentList open={open} findOpenView={() => 'view-1'} />)
    const button = screen.getByRole('button', { name: '在 Dev 打开 VS Code' })
    fireEvent.click(button)
    expect(resolveTarget).toHaveBeenCalledWith('env-1', 'view-1')
    expect(open).toHaveBeenCalledWith(target, button)
  })

  it('does not offer opening when environment is offline', () => {
    vi.mocked(useIdeWorkspace).mockReturnValue({ environments: [environment({ status: 'OFFLINE', ready: false })], resolveTarget: vi.fn() } as never)
    render(<EnvironmentList open={vi.fn()} />)
    expect(screen.queryByRole('button')).toBeNull()
  })
})
