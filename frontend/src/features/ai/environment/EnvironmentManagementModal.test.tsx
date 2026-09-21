import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvironmentManagementModal } from '@/features/ai/environment/EnvironmentManagementModal'
import { environmentService } from '@/shared/api/environment-service'
import type { EnvironmentCardDTO, EnvironmentEventDTO } from '@/shared/api/contracts/ai-environment'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironmentEvents: vi.fn(),
  },
}))

function testEnvironment(overrides: Partial<EnvironmentCardDTO> = {}): EnvironmentCardDTO {
  return {
    id: 'env-test-1',
    name: 'test-environment',
    userName: 'dev-user',
    homeDirectory: '/home/dev',
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

function renderModal(
  environment: EnvironmentCardDTO = testEnvironment(),
  onClose = vi.fn(),
  queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  }),
) {
  const view = render(
    <QueryClientProvider client={queryClient}>
      <EnvironmentManagementModal environment={environment} onClose={onClose} />
    </QueryClientProvider>,
  )
  return { queryClient, view, onClose }
}

describe('EnvironmentManagementModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  /**
   * 测试意图：验证宿主信息卡片呈现包含 userName 与 homeDirectory 的宿主事实，不展示已废弃的 rootPath。
   */
  it('renders host information from environment card including userName and homeDirectory', async () => {
    vi.mocked(environmentService.listEnvironmentEvents).mockResolvedValue([])
    renderModal(testEnvironment())

    expect(screen.getByText('Linux 5.15.0')).toBeInTheDocument()
    expect(screen.getByText('Asia/Shanghai')).toBeInTheDocument()
    // 宿主信息展示最近一次 READY 的进程用户与 HOME，不再展示任何 Root 路径。
    expect(screen.getByText('dev-user')).toBeInTheDocument()
    expect(screen.getByText('/home/dev')).toBeInTheDocument()
    expect(screen.queryByText('根路径')).toBeNull()
    expect(screen.queryByText('/opt/studio/workspace')).toBeNull()
    expect(screen.getByText('Production host')).toBeInTheDocument()
    expect(screen.getByText('2026-07-20T02:00:00.000Z')).toBeInTheDocument()
    expect(screen.queryByText('/opt/studio/workspace')).not.toBeInTheDocument()

    // 绝不包含任何操作记录区域
    expect(screen.queryByText('异步操作记录')).not.toBeInTheDocument()
    expect(screen.queryByText('Operations')).not.toBeInTheDocument()
  })

  /**
   * 测试意图：验证点击关闭按钮触发 onClose 回调。
   */
  it('triggers onClose when close button is clicked', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    vi.mocked(environmentService.listEnvironmentEvents).mockResolvedValue([])
    renderModal(testEnvironment(), onClose)

    const closeBtn = screen.getByRole('button', { name: '关闭' })
    await user.click(closeBtn)
    expect(onClose).toHaveBeenCalled()
  })

  /**
   * 测试意图：验证事件列表在有记录时正确渲染时间、级别、类型和说明，且高亮 WARN/ERROR 级别。
   */
  it('renders events list with distinguishable levels and empty state when none', async () => {
    const mockEvents: EnvironmentEventDTO[] = [
      {
        time: '2026-07-20T01:00:00.000Z',
        level: 'INFO',
        type: 'READY',
        message: 'Host connected successfully',
      },
      {
        time: '2026-07-20T01:05:00.000Z',
        level: 'WARN',
        type: 'SKILL_SYNC_FAILED',
        message: 'Failed to synchronize skill package',
      },
    ]
    vi.mocked(environmentService.listEnvironmentEvents).mockResolvedValue(mockEvents)

    renderModal(testEnvironment())

    expect(await screen.findByText('Host connected successfully')).toBeInTheDocument()
    expect(screen.getByText('Failed to synchronize skill package')).toBeInTheDocument()
    expect(screen.getByText('READY')).toBeInTheDocument()
    expect(screen.getByText('SKILL_SYNC_FAILED')).toBeInTheDocument()

    // 检查 WARN 级别是否具有视觉区分样式类
    const warnLevel = screen.getByText('WARN')
    expect(warnLevel).toHaveClass('is-warn')
  })

  /**
   * 测试意图：验证没有事件时渲染空态文本。
   */
  it('renders empty state when there are no events', async () => {
    vi.mocked(environmentService.listEnvironmentEvents).mockResolvedValue([])
    renderModal(testEnvironment())

    expect(await screen.findByText('暂无事件记录')).toBeInTheDocument()
  })

  /**
   * 测试意图：验证管理弹窗在打开期间每 10 秒轮询一次事件端点，
   * 使用 fakeTimers 推进时间后能至少观察到 2 次 refetch（总计调用至少 3 次）。
   */
  it('polls environment events endpoint every 10 seconds while open', async () => {
    vi.useFakeTimers()
    try {
      const queryClient = new QueryClient({
        defaultOptions: {
          queries: {
            retry: false,
          },
        },
      })
      vi.mocked(environmentService.listEnvironmentEvents).mockResolvedValue([])

      renderModal(testEnvironment(), vi.fn(), queryClient)

      // 首次加载发起 1 次请求
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(environmentService.listEnvironmentEvents).toHaveBeenCalledTimes(1)

      // 前进 10 秒触发第 1 次 refetch
      await act(async () => {
        await vi.advanceTimersByTimeAsync(10_000)
      })
      expect(environmentService.listEnvironmentEvents).toHaveBeenCalledTimes(2)

      // 再次前进 10 秒触发第 2 次 refetch
      await act(async () => {
        await vi.advanceTimersByTimeAsync(10_000)
      })
      expect(environmentService.listEnvironmentEvents).toHaveBeenCalledTimes(3)
    } finally {
      vi.useRealTimers()
    }
  })
})
