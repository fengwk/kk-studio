import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { EnvironmentList } from './EnvironmentList'
import { useIdeWorkspace } from './ide-workspace-context'
import type { EnvironmentSummaryDTO } from '@/shared/api/generated/contracts'

vi.mock('./ide-workspace-context', () => ({ useIdeWorkspace: vi.fn() }))

const environment = (overrides: Partial<EnvironmentSummaryDTO> = {}): EnvironmentSummaryDTO => ({
  id: 'env-1', name: 'Dev', status: 'READY', ready: true, capabilities: [], ...overrides,
})

describe('EnvironmentList', () => {
  it('opens the resolved target when the server says ready even after the client deadline', () => {
    const target = { kind: 'vscode', environmentId: 'env-1', viewId: 'view-1' } as const
    const resolveTarget = vi.fn(() => target)
    vi.mocked(useIdeWorkspace).mockReturnValue({ environments: [environment({ statusExpiresAt: 1 })], resolveTarget } as never)
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
