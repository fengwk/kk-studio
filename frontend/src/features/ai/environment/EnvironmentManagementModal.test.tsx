import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvironmentManagementModal } from '@/features/ai/environment/EnvironmentManagementModal'
import { environmentService, DEFAULT_OPERATION_LIMIT } from '@/shared/api/environment-service'
import type {
  EnvironmentCardDTO,
  EnvironmentOperationDTO,
} from '@/shared/api/contracts/ai-environment'

vi.mock('@/shared/api/environment-service', () => ({
  DEFAULT_OPERATION_LIMIT: 50,
  environmentService: {
    listOperations: vi.fn(),
    cancelOperation: vi.fn(),
    listEnvironments: vi.fn(),
  },
}))

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

function testOperation(overrides: Partial<EnvironmentOperationDTO> = {}): EnvironmentOperationDTO {
  return {
    id: 'op-1',
    environmentId: 'env-test-1',
    type: 'REFRESH',
    targetResource: 'source:src-1',
    resourceVersion: '1',
    status: 'ACTIVE',
    errorMessage: null,
    startedAt: '2026-07-20T01:00:00.000Z',
    finishedAt: null,
    timeoutMillis: 60000,
    parameterSummary: null,
    resultSummary: null,
    createTime: '2026-07-20T01:00:00.000Z',
    updateTime: '2026-07-20T01:00:00.000Z',
    ...overrides,
  }
}

describe('EnvironmentManagementModal', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    vi.clearAllMocks()
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, gcTime: 0 },
        mutations: { retry: false },
      },
    })

    vi.mocked(environmentService.listOperations).mockResolvedValue([])
    vi.mocked(environmentService.cancelOperation).mockResolvedValue(
      testOperation({ id: 'op-1', status: 'CANCELLED' }),
    )
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([])
  })

  afterEach(() => {
    queryClient.clear()
  })

  function renderModal(props: { onClose?: () => void; environment?: EnvironmentCardDTO } = {}) {
    const env = props.environment ?? testEnvironment()
    const onClose = props.onClose ?? vi.fn()
    return {
      ...render(
        <QueryClientProvider client={queryClient}>
          <EnvironmentManagementModal environment={env} onClose={onClose} />
        </QueryClientProvider>,
      ),
      onClose,
    }
  }

  it('renders host information from environment card and operations section', async () => {
    renderModal()

    await waitFor(() => {
      expect(environmentService.listOperations).toHaveBeenCalledWith('env-test-1', DEFAULT_OPERATION_LIMIT)
      expect(screen.getByText('Linux 5.15.0')).toBeInTheDocument()
    })

    expect(screen.getByText('Asia/Shanghai')).toBeInTheDocument()
    expect(screen.getByText('/opt/studio/workspace')).toBeInTheDocument()
    expect(screen.getByText('Production host')).toBeInTheDocument()
    expect(screen.getByText('2026-07-20T02:00:00.000Z')).toBeInTheDocument()
  })

  it('handles cancelling an active operation', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.listOperations).mockResolvedValue([
      testOperation({ id: 'op-1', status: 'PENDING' }),
    ])

    renderModal()

    await waitFor(() => {
      expect(screen.getByText('op-1')).toBeInTheDocument()
    })

    // Find and click the cancel operation button
    const cancelBtn = screen.getByRole('button', { name: /op-1/ })
    await user.click(cancelBtn)

    // Confirm cancel in submodal
    const alertModal = await screen.findByRole('alertdialog')
    const confirmBtn = within(alertModal).getByRole('button', { name: /取消操作|确认|confirm/i })
    await user.click(confirmBtn)

    await waitFor(() => {
      expect(environmentService.cancelOperation).toHaveBeenCalledWith('env-test-1', 'op-1')
    })
  })

  it('calls onClose when close button is clicked', async () => {
    const user = userEvent.setup()
    const { onClose } = renderModal()

    const closeBtn = screen.getByRole('button', { name: /关闭|close/i })
    await user.click(closeBtn)

    expect(onClose).toHaveBeenCalledOnce()
  })
})
