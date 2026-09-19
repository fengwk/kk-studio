import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { EnvironmentManagementModal } from '@/features/ai/environment/EnvironmentManagementModal'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'

function testEnvironment(overrides: Partial<EnvironmentCardDTO> = {}): EnvironmentCardDTO {
  return {
    id: 'env-test-1',
    name: 'test-environment',
    rootPath: '/opt/studio/workspace',
    operatingSystem: 'Linux 5.15.0',
    timeZone: 'Asia/Shanghai',
    note: 'Production host',
    status: 'READY',
    ready: true,
    lastSeen: '2026-07-20T02:00:00.000Z',
    capabilities: [],
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T00:00:00.000Z',
    ...overrides,
  }
}

describe('EnvironmentManagementModal', () => {
  it('renders host information from environment card', () => {
    const onClose = vi.fn()
    render(<EnvironmentManagementModal environment={testEnvironment()} onClose={onClose} />)

    expect(screen.getByText('Linux 5.15.0')).toBeInTheDocument()
    expect(screen.getByText('Asia/Shanghai')).toBeInTheDocument()
    expect(screen.getByText('/opt/studio/workspace')).toBeInTheDocument()
    expect(screen.getByText('Production host')).toBeInTheDocument()
    expect(screen.getByText('2026-07-20T02:00:00.000Z')).toBeInTheDocument()

    // 绝不包含任何操作记录区域
    expect(screen.queryByText('异步操作记录')).not.toBeInTheDocument()
    expect(screen.queryByText('Operations')).not.toBeInTheDocument()
  })

  it('triggers onClose when close button is clicked', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<EnvironmentManagementModal environment={testEnvironment()} onClose={onClose} />)

    const closeBtn = screen.getByRole('button', { name: '关闭' })
    await user.click(closeBtn)
    expect(onClose).toHaveBeenCalled()
  })
})
