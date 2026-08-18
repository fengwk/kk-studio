import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  BrowserPreferencesProvider,
} from '@/features/settings/browser-preferences'
import { makeSettingsDto } from '@/features/settings/settings-test-fixtures'
import { setLocale } from '@/shared/i18n'
import { SettingsPage } from '@/features/settings/SettingsPage'

const mocks = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))

vi.mock('@/shared/api/system-settings-service', () => ({
  systemSettingsService: { get: mocks.get, update: mocks.update },
  createSystemSettingsService: () => ({ get: vi.fn(), update: vi.fn() }),
}))

const STORAGE_KEY = 'kkstudio.browser-preferences.v1'

interface NotificationState {
  permission: NotificationPermission
  requestPermission: ReturnType<typeof vi.fn>
}

let notificationState: NotificationState

beforeEach(() => {
  localStorage.clear()
  // 清空存储会连带清掉测试基座写入的 locale；恢复默认测试语言。
  setLocale('zh-CN')
  mocks.get.mockResolvedValue(makeSettingsDto())
  notificationState = {
    permission: 'default',
    requestPermission: vi.fn(async () => {
      notificationState.permission = 'granted'
      return 'granted' as const
    }),
  }
  Object.defineProperty(window, 'Notification', {
    configurable: true,
    value: {
      get permission() {
        return notificationState.permission
      },
      // 委托到当前 mock：测试可在 beforeEach 之后替换请求行为。
      requestPermission: () => notificationState.requestPermission(),
    },
  })
})

function renderSettings() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <BrowserPreferencesProvider>
        <SettingsPage />
      </BrowserPreferencesProvider>
    </QueryClientProvider>,
  )
}

function switchControl() {
  return screen.getByRole('switch', { name: '浏览器通知' })
}

function tabByName(name: string) {
  return screen.getByRole('tab', { name })
}

describe('SettingsPage general tab + notifications', () => {
  it('renders every top-level settings tab', () => {
    renderSettings()
    for (const name of [
      '常规',
      'AI 运行时',
      '工具与权限',
      '环境',
      '集成',
      '存储与媒体',
      '高级',
    ]) {
      expect(tabByName(name)).toBeInTheDocument()
    }
  })

  it('distinguishes an off setting from browser permission that is already granted', () => {
    notificationState.permission = 'granted'
    renderSettings()

    expect(switchControl()).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText('已关闭')).toHaveAttribute('data-enabled', 'false')
    expect(screen.getByText('已允许')).toHaveAttribute('data-permission', 'granted')
  })

  it('requests permission when enabling from default and only writes enabled after granted', async () => {
    const user = userEvent.setup()
    renderSettings()
    expect(screen.getByText('未决定')).toBeInTheDocument()
    expect(screen.getByText('开启时会向浏览器申请通知权限。')).toBeInTheDocument()
    expect(switchControl()).toBeEnabled()
    expect(switchControl()).toHaveAttribute('aria-checked', 'false')

    await user.click(switchControl())

    expect(notificationState.requestPermission).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem(STORAGE_KEY)).toBe('{"notificationsEnabled":true}')
    expect(switchControl()).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByText('已开启')).toHaveAttribute('data-enabled', 'true')
    // permission 状态在点击后更新。
    expect(screen.getByText('已允许')).toHaveAttribute('data-permission', 'granted')
  })

  it('enables immediately when permission is already granted without requesting again', async () => {
    notificationState.permission = 'granted'
    const user = userEvent.setup()
    renderSettings()
    expect(switchControl()).toBeEnabled()

    await user.click(switchControl())

    expect(notificationState.requestPermission).not.toHaveBeenCalled()
    expect(localStorage.getItem(STORAGE_KEY)).toBe('{"notificationsEnabled":true}')
    expect(switchControl()).toHaveAttribute('aria-checked', 'true')
  })

  it('does not fake-enable when the permission request is denied', async () => {
    notificationState.requestPermission = vi.fn(async () => {
      notificationState.permission = 'denied'
      return 'denied' as const
    })
    const user = userEvent.setup()
    renderSettings()

    await user.click(switchControl())

    expect(notificationState.requestPermission).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
    expect(switchControl()).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText('已拒绝')).toBeInTheDocument()
    expect(screen.getByText(/通知已被浏览器阻止/)).toBeInTheDocument()
    // 拒绝后开关进入只读状态，不能再次伪装启用。
    expect(switchControl()).toBeDisabled()
  })

  it('keeps the switch read-only with explicit guidance when permission is denied', async () => {
    notificationState.permission = 'denied'
    const user = userEvent.setup()
    renderSettings()

    expect(switchControl()).toBeDisabled()
    expect(switchControl()).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText('已拒绝')).toBeInTheDocument()
    expect(screen.getByText(/通知已被浏览器阻止/)).toBeInTheDocument()

    await user.click(switchControl())
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
  })

  it('keeps the switch read-only with explicit guidance when the browser is unsupported', async () => {
    delete (window as unknown as { Notification?: unknown }).Notification
    const user = userEvent.setup()
    renderSettings()

    expect(switchControl()).toBeDisabled()
    expect(switchControl()).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText('不支持')).toBeInTheDocument()
    expect(screen.getByText(/当前浏览器不支持通知/)).toBeInTheDocument()

    await user.click(switchControl())
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
  })

  it('turns the switch off by writing false directly', async () => {
    localStorage.setItem(STORAGE_KEY, '{"notificationsEnabled":true}')
    notificationState.permission = 'granted'
    const user = userEvent.setup()
    renderSettings()
    expect(switchControl()).toHaveAttribute('aria-checked', 'true')

    await user.click(switchControl())

    expect(notificationState.requestPermission).not.toHaveBeenCalled()
    expect(localStorage.getItem(STORAGE_KEY)).toBe('{"notificationsEnabled":false}')
    expect(switchControl()).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText('已关闭')).toHaveAttribute('data-enabled', 'false')
    expect(screen.getByText('已允许')).toHaveAttribute('data-permission', 'granted')
  })

  it('does not fake-enable on a stale stored flag while permission is still default; clicking requests permission instead of turning the setting off', async () => {
    // 存储残留 enabled=true，但浏览器 permission 仍为 default（例如用户之前在
    // 别的浏览器/时刻授权过，或请求被跳过）。UI 必须按 permission 事实显示未启用。
    localStorage.setItem(STORAGE_KEY, '{"notificationsEnabled":true}')
    notificationState.permission = 'default'
    const user = userEvent.setup()
    renderSettings()
    expect(switchControl()).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText('未决定')).toBeInTheDocument()

    await user.click(switchControl())

    // 点击走「请求权限」路径，而不是把 setting 关掉。
    expect(notificationState.requestPermission).toHaveBeenCalledTimes(1)
    expect(notificationState.permission).toBe('granted')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('{"notificationsEnabled":true}')
    expect(switchControl()).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByText('已允许')).toBeInTheDocument()
  })

  it('renders the read-only keyboard shortcut catalog section', () => {
    renderSettings()
    expect(screen.getByRole('heading', { name: '键盘快捷键' })).toBeInTheDocument()
    expect(screen.getByText('本版本支持的键盘快捷键只读目录。')).toBeInTheDocument()
    // scope 分组与条目来自唯一事实源。
    for (const title of ['应用', '对话', '事件', '画布']) {
      expect(screen.getByText(title)).toBeInTheDocument()
    }
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
  })
})

describe('settings tabs roving tabindex + keyboard navigation', () => {
  function selectedTab() {
    return screen.getByRole('tab', { selected: true })
  }

  it('keeps only the selected tab in the sequential tab order (tabIndex 0) and the rest at -1', () => {
    renderSettings()
    expect(tabByName('常规')).toHaveAttribute('tabindex', '0')
    for (const name of ['AI 运行时', '工具与权限', '环境', '集成', '存储与媒体', '高级']) {
      expect(tabByName(name)).toHaveAttribute('tabindex', '-1')
    }
  })

  it('moves selection and focus with Left/Right/Home/End, wrapping on arrows', async () => {
    const user = userEvent.setup()
    renderSettings()

    tabByName('常规').focus()
    await user.keyboard('{ArrowRight}')
    expect(selectedTab()).toBe(tabByName('AI 运行时'))
    expect(document.activeElement?.id).toBe('settings-tab-ai-runtime')

    await user.keyboard('{End}')
    expect(selectedTab()).toBe(tabByName('高级'))
    expect(document.activeElement?.id).toBe('settings-tab-advanced')

    await user.keyboard('{Home}')
    expect(selectedTab()).toBe(tabByName('常规'))
    expect(document.activeElement?.id).toBe('settings-tab-general')

    // Arrow 环绕：从首项向左回到末项。
    await user.keyboard('{ArrowLeft}')
    expect(selectedTab()).toBe(tabByName('高级'))
    expect(document.activeElement?.id).toBe('settings-tab-advanced')
  })

  it('keeps click selection working with focus on the clicked tab', async () => {
    const user = userEvent.setup()
    renderSettings()
    await user.click(tabByName('高级'))
    expect(selectedTab()).toBe(tabByName('高级'))
    expect(document.activeElement?.id).toBe('settings-tab-advanced')
  })
})
