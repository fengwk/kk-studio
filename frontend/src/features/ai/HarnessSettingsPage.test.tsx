import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HarnessSettingsPage } from '@/features/ai/HarnessSettingsPage'
import { harnessService } from '@/shared/api/harness-service'

vi.mock('@/shared/api/harness-service', () => ({
  harnessService: {
    getRetryPolicy: vi.fn(),
    updateRetryPolicy: vi.fn(),
    getRealtimeStreamPolicy: vi.fn(),
    updateRealtimeStreamPolicy: vi.fn(),
  },
}))

vi.mock('@/platform/workbench/WorkbenchSlots', () => ({
  NavigationSlot: () => null,
}))

const defaultRetryPolicy = {
  maxRetries: 3,
  backoffStrategy: 'EXPONENTIAL' as const,
  baseDelayMillis: 2_000,
  maxDelayMillis: 60_000,
}

const defaultRealtimeStreamPolicy = { maxLength: 5_000 }

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <HarnessSettingsPage />
    </QueryClientProvider>,
  )
}

describe('HarnessSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(harnessService.getRetryPolicy).mockResolvedValue(defaultRetryPolicy)
    vi.mocked(harnessService.updateRetryPolicy).mockResolvedValue(defaultRetryPolicy)
    vi.mocked(harnessService.getRealtimeStreamPolicy).mockResolvedValue(defaultRealtimeStreamPolicy)
    vi.mocked(harnessService.updateRealtimeStreamPolicy).mockResolvedValue(defaultRealtimeStreamPolicy)
  })

  it('loads and replaces the complete automatic retry policy independently', async () => {
    const user = userEvent.setup()
    renderPage()

    const maxRetries = await screen.findByLabelText('最大重试次数')
    expect(maxRetries).toBeRequired()
    expect(screen.getByLabelText('退避策略')).toBeRequired()
    expect(screen.getByLabelText('基础间隔（秒）')).toBeRequired()
    expect(screen.getByLabelText('最大间隔（秒）')).toBeRequired()
    await user.clear(maxRetries)
    await user.type(maxRetries, '2')
    await user.selectOptions(screen.getByLabelText('退避策略'), 'FIXED')
    const baseDelay = screen.getByLabelText('基础间隔（秒）')
    await user.clear(baseDelay)
    await user.type(baseDelay, '4')
    const maxDelay = screen.getByLabelText('最大间隔（秒）')
    await user.clear(maxDelay)
    await user.type(maxDelay, '8')
    await user.click(screen.getByRole('button', { name: '保存重试策略' }))

    await waitFor(() =>
      expect(harnessService.updateRetryPolicy).toHaveBeenCalledWith({
        maxRetries: 2,
        backoffStrategy: 'FIXED',
        baseDelayMillis: 4_000,
        maxDelayMillis: 8_000,
      }),
    )
    expect(harnessService.updateRealtimeStreamPolicy).not.toHaveBeenCalled()
  })

  it('loads and replaces the realtime Stream capacity independently', async () => {
    const user = userEvent.setup()
    renderPage()

    const maxLength = await screen.findByLabelText('最大保留事件数')
    expect(maxLength).toBeRequired()
    expect(maxLength).toHaveValue(5_000)
    expect(
      screen.getByText(/下一次写入生效；其他实例最多约一秒刷新/),
    ).toBeInTheDocument()
    await user.clear(maxLength)
    await user.type(maxLength, '100000')
    await user.click(screen.getByRole('button', { name: '保存实时流设置' }))

    await waitFor(() =>
      expect(harnessService.updateRealtimeStreamPolicy).toHaveBeenCalledWith({ maxLength: 100_000 }),
    )
    expect(harnessService.updateRetryPolicy).not.toHaveBeenCalled()
  })

  it('keeps invalid realtime Stream settings in the form and does not call the API', async () => {
    const user = userEvent.setup()
    renderPage()

    const maxLength = await screen.findByLabelText('最大保留事件数')
    await user.clear(maxLength)
    await user.type(maxLength, '0')
    await user.click(screen.getByRole('button', { name: '保存实时流设置' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('最大保留事件数必须在 1 到')
    expect(harnessService.updateRealtimeStreamPolicy).not.toHaveBeenCalled()
  })

  it('round-trips millisecond retry precision through the seconds-based form', async () => {
    vi.mocked(harnessService.getRetryPolicy).mockResolvedValue({
      ...defaultRetryPolicy,
      baseDelayMillis: 1_500,
      maxDelayMillis: 2_500,
    })
    vi.mocked(harnessService.updateRetryPolicy).mockResolvedValue({
      ...defaultRetryPolicy,
      baseDelayMillis: 1_500,
      maxDelayMillis: 2_500,
    })
    const user = userEvent.setup()
    renderPage()

    expect(await screen.findByLabelText('基础间隔（秒）')).toHaveValue(1.5)
    expect(screen.getByLabelText('最大间隔（秒）')).toHaveValue(2.5)
    await user.click(screen.getByRole('button', { name: '保存重试策略' }))

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
