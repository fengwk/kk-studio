import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { RetryPolicyPage } from '@/features/ai/RetryPolicyPage'
import { harnessService } from '@/shared/api/harness-service'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getRetryPolicy: vi.fn(),
    updateRetryPolicy: vi.fn(),
  },
}))

vi.mock('@/platform/workbench/WorkbenchSlots', () => ({
  NavigationSlot: () => null,
}))

const defaultPolicy = {
  maxRetries: 3,
  backoffStrategy: 'EXPONENTIAL' as const,
  baseDelayMillis: 2_000,
  maxDelayMillis: 60_000,
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <RetryPolicyPage />
    </QueryClientProvider>,
  )
}

describe('RetryPolicyPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(harnessService.getRetryPolicy).mockResolvedValue(defaultPolicy)
    vi.mocked(harnessService.updateRetryPolicy).mockResolvedValue(defaultPolicy)
  })

  it('loads and replaces the complete automatic retry policy', async () => {
    const user = userEvent.setup()
    renderPage()

    const maxRetries = await screen.findByLabelText('最大重试次数')
    expect(maxRetries).toBeRequired()
    expect(screen.getByLabelText('退避策略')).toBeRequired()
    expect(screen.getByLabelText('基础间隔（秒）')).toBeRequired()
    expect(screen.getByLabelText('最大间隔（秒）')).toBeRequired()
    expect(screen.getAllByText('*')).toHaveLength(4)
    await user.clear(maxRetries)
    await user.type(maxRetries, '2')
    await user.selectOptions(screen.getByLabelText('退避策略'), 'FIXED')
    const baseDelay = screen.getByLabelText('基础间隔（秒）')
    await user.clear(baseDelay)
    await user.type(baseDelay, '4')
    const maxDelay = screen.getByLabelText('最大间隔（秒）')
    await user.clear(maxDelay)
    await user.type(maxDelay, '8')
    await user.click(screen.getByRole('button', { name: '保存策略' }))

    await waitFor(() =>
      expect(harnessService.updateRetryPolicy).toHaveBeenCalledWith({
        maxRetries: 2,
        backoffStrategy: 'FIXED',
        baseDelayMillis: 4_000,
        maxDelayMillis: 8_000,
      }),
    )
  })

  it('keeps invalid settings in the form and does not call the API', async () => {
    const user = userEvent.setup()
    renderPage()

    const baseDelay = await screen.findByLabelText('基础间隔（秒）')
    await user.clear(baseDelay)
    await user.type(baseDelay, '8')
    const maxDelay = screen.getByLabelText('最大间隔（秒）')
    await user.clear(maxDelay)
    await user.type(maxDelay, '3')
    await user.click(screen.getByRole('button', { name: '保存策略' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('最大间隔不能小于基础间隔')
    expect(harnessService.updateRetryPolicy).not.toHaveBeenCalled()
  })

  it('round-trips millisecond API precision through the seconds-based form', async () => {
    vi.mocked(harnessService.getRetryPolicy).mockResolvedValue({
      ...defaultPolicy,
      baseDelayMillis: 1_500,
      maxDelayMillis: 2_500,
    })
    vi.mocked(harnessService.updateRetryPolicy).mockResolvedValue({
      ...defaultPolicy,
      baseDelayMillis: 1_500,
      maxDelayMillis: 2_500,
    })
    const user = userEvent.setup()
    renderPage()

    expect(await screen.findByLabelText('基础间隔（秒）')).toHaveValue(1.5)
    expect(screen.getByLabelText('最大间隔（秒）')).toHaveValue(2.5)

    await user.click(screen.getByRole('button', { name: '保存策略' }))

    await waitFor(() =>
      expect(harnessService.updateRetryPolicy).toHaveBeenCalledWith({
        maxRetries: 3,
        backoffStrategy: 'EXPONENTIAL',
        baseDelayMillis: 1_500,
        maxDelayMillis: 2_500,
      }),
    )
  })
})
