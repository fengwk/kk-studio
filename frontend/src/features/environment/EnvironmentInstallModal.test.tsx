import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setLocale } from '@/shared/i18n'
import { EnvironmentInstallModal } from './EnvironmentInstallModal'
import { environmentService } from '@/features/environment/environment-service'
import { copyTextToClipboard } from '@/shared/lib/clipboard'
import { ApiError, url } from '@/shared/api/client'
import { chooseSelectOption } from '@/test-support/chooseSelectOption'
import { testEnvironment } from '@/test-support/environment-test-fixtures'

vi.mock('@/features/environment/environment-service', () => ({
  environmentService: {
    getEnvironment: vi.fn(),
    createInstallCode: vi.fn(),
  },
}))
vi.mock('@/shared/lib/clipboard', () => ({ copyTextToClipboard: vi.fn() }))

const card = testEnvironment({ id: 'env-1', name: 'box', version: '7', status: 'OFFLINE', ready: false })
const copyInstall = () => screen.getByRole('button', { name: '复制安装命令' })
const copyUninstall = () => screen.getByRole('button', { name: '复制卸载命令' })

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (err: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

function open(options: { uninstall?: boolean; environment?: typeof card } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const close = vi.fn()
  const view = render(
    <QueryClientProvider client={queryClient}>
      <EnvironmentInstallModal
        environment={options.environment ?? card}
        uninstall={options.uninstall}
        onClose={close}
      />
    </QueryClientProvider>,
  )
  return { ...view, queryClient, close }
}

function installCommandUrl(os: 'linux' | 'macos' | 'windows', code: string) {
  return `${window.location.origin}${url(
    'GET /api/platform/environments/{environmentId}/install-script',
    { path: { environmentId: 'env-1' }, query: { code, operatingSystem: os } },
  )}`
}

function uninstallCommandUrl(os: 'linux' | 'macos' | 'windows') {
  return `${window.location.origin}${url(
    'GET /api/platform/environments/{environmentId}/uninstall-script',
    { path: { environmentId: 'env-1' }, query: { operatingSystem: os } },
  )}`
}

describe('EnvironmentInstallModal', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(environmentService.createInstallCode).mockResolvedValue({
      code: "short-code'汉字",
      expiresAt: 1_791_191_100_000,
    })
    vi.mocked(environmentService.getEnvironment).mockResolvedValue(card)
    vi.mocked(copyTextToClipboard).mockResolvedValue(true)
  })

  it('issues a one-time code and copies the install command for the chosen OS', async () => {
    const user = userEvent.setup()
    open()
    await chooseSelectOption(user, '操作系统', 'macOS')
    await user.click(copyInstall())
    expect(environmentService.createInstallCode).toHaveBeenCalledWith('env-1', '7')
    expect(await screen.findByText('安装命令已复制，5 分钟内有效。')).toBeInTheDocument()
    expect(screen.getByRole('status').closest('[role="dialog"]')).toBeNull()
    expect(within(screen.getByRole('dialog')).queryByRole('status')).toBeNull()
    const copied = vi.mocked(copyTextToClipboard).mock.calls[0]![0]
    expect(copied).toBe(
      `(set -o pipefail; curl -fsSL $'${installCommandUrl('macos', "short-code'汉字").replaceAll("'", "\\'")}' | bash)`,
    )
    expect(copied).toContain('operatingSystem=macos')
    expect(copied).not.toContain('registrationToken')
    expect(copied).not.toContain(card.daemonConfig.studioUrl)
    expect(document.body.textContent).not.toContain('registrationToken')
  })

  it('changes the OS without calling the API until copy and keeps the form usable', async () => {
    const user = userEvent.setup()
    open()
    expect(screen.getByLabelText('操作系统')).toHaveAttribute('data-value', 'linux')
    expect(environmentService.createInstallCode).not.toHaveBeenCalled()
    await chooseSelectOption(user, '操作系统', 'Windows')
    expect(environmentService.createInstallCode).not.toHaveBeenCalled()
    await user.click(copyInstall())
    await waitFor(() => expect(copyTextToClipboard).toHaveBeenCalledTimes(1))
    expect(vi.mocked(copyTextToClipboard).mock.calls[0]![0]).toContain(
      installCommandUrl('windows', "short-code'汉字").replaceAll("'", "''"),
    )
  })

  it('presents a CAS conflict, keeps the selected OS and rebases the expected version', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.createInstallCode).mockRejectedValueOnce(
      new ApiError('conflict', 409, 'version_conflict', undefined, undefined, 'environment changed'),
    )
    open()
    await chooseSelectOption(user, '操作系统', 'macOS')
    await user.click(copyInstall())
    const conflict = await screen.findByRole('alertdialog', { name: '数据已发生变化' })
    expect(environmentService.createInstallCode).toHaveBeenCalledWith('env-1', '7')
    vi.mocked(environmentService.getEnvironment).mockResolvedValue({ ...card, version: '12' })
    await user.click(within(conflict).getByRole('button', { name: '刷新' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(screen.getByLabelText('操作系统')).toHaveAttribute('data-value', 'macos')
    await user.click(copyInstall())
    await waitFor(() => expect(copyTextToClipboard).toHaveBeenCalledTimes(1))
    expect(environmentService.createInstallCode).toHaveBeenLastCalledWith('env-1', '12')
  })

  it('shows a CAS refresh failure and closes the stale conflict', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.createInstallCode).mockRejectedValueOnce(
      new ApiError('conflict', 409, 'version_conflict', undefined, undefined, 'environment changed'),
    )
    vi.mocked(environmentService.getEnvironment).mockRejectedValueOnce(new Error('refresh failed'))
    open()
    await user.click(copyInstall())
    const conflict = await screen.findByRole('alertdialog', { name: '数据已发生变化' })
    await user.click(within(conflict).getByRole('button', { name: '刷新' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('refresh failed')
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('shows the install code failure instead of pretending a command was copied', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.createInstallCode).mockRejectedValue(new Error('code unavailable'))
    open()
    await user.click(copyInstall())
    expect(await screen.findByRole('alert')).toHaveTextContent('code unavailable')
    expect(copyTextToClipboard).not.toHaveBeenCalled()
  })

  it('shows a non-Error install code failure as text', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.createInstallCode).mockRejectedValue('offline')
    open()
    await user.click(copyInstall())
    expect(await screen.findByRole('alert')).toHaveTextContent('offline')
    expect(copyTextToClipboard).not.toHaveBeenCalled()
  })

  it('ignores a duplicate submit while the install code request is in flight', async () => {
    const code = deferred<{ code: string; expiresAt: number }>()
    vi.mocked(environmentService.createInstallCode).mockReturnValue(code.promise)
    open()
    const form = screen.getByRole('dialog').querySelector('form')!
    await act(async () => {
      fireEvent.submit(form)
      fireEvent.submit(form)
    })
    expect(environmentService.createInstallCode).toHaveBeenCalledTimes(1)
    await act(async () => {
      code.resolve({ code: 'short-code', expiresAt: 1_791_191_100_000 })
    })
    expect(copyTextToClipboard).toHaveBeenCalledTimes(1)
  })

  it('dismisses copied feedback and closes a conflict through its callbacks', async () => {
    const user = userEvent.setup()
    open()
    await user.click(copyInstall())
    await screen.findByRole('status')
    await user.click(screen.getByRole('button', { name: '关闭通知' }))
    expect(screen.queryByRole('status')).toBeNull()

    vi.mocked(environmentService.createInstallCode).mockRejectedValueOnce(
      new ApiError('conflict', 409, 'version_conflict', undefined, undefined, 'environment changed'),
    )
    await user.click(copyInstall())
    const conflict = await screen.findByRole('alertdialog', { name: '数据已发生变化' })
    await user.click(within(conflict).getByRole('button', { name: '取消' }))
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('copies a card-scoped uninstall command without calling the server', async () => {
    const user = userEvent.setup()
    open({ uninstall: true })
    const dialog = screen.getByRole('dialog', { name: '卸载环境' })
    expect(dialog).toHaveTextContent('该环境的服务与程序会被移除')
    expect(dialog).toHaveTextContent('Studio 中的环境卡片不会删除')
    expect(dialog).not.toHaveTextContent('安装设置')
    await chooseSelectOption(user, '操作系统', 'Windows')
    await user.click(copyUninstall())
    expect(await screen.findByText('卸载命令已复制。')).toBeInTheDocument()
    expect(vi.mocked(copyTextToClipboard).mock.calls[0]![0]).toBe(
      `& { Set-PSDebug -Off; $ErrorActionPreference='Stop'; $script = (Invoke-WebRequest -UseBasicParsing -ErrorAction Stop -Uri '${uninstallCommandUrl('windows').replaceAll("'", "''")}').Content; if ([string]::IsNullOrEmpty($script)) { throw 'Empty installer' }; & ([scriptblock]::Create($script)) }`,
    )
    expect(environmentService.createInstallCode).not.toHaveBeenCalled()
    expect(environmentService.getEnvironment).not.toHaveBeenCalled()
    expect(vi.mocked(copyTextToClipboard).mock.calls[0]![0]).not.toContain('code=')
  })

  it('reports clipboard rejection and never claims a command was copied', async () => {
    const user = userEvent.setup()
    vi.mocked(copyTextToClipboard).mockResolvedValue(false)
    open({ uninstall: true })
    await user.click(copyUninstall())
    expect(await screen.findByRole('alert')).toHaveTextContent('复制失败')
    expect(screen.queryByRole('status')).toBeNull()
  })

  it.each(['code', 'clipboard'] as const)('ignores stale %s completion after unmount', async (phase) => {
    const user = userEvent.setup()
    const code = deferred<{ code: string; expiresAt: number }>()
    const clipboard = deferred<boolean>()
    if (phase === 'code') vi.mocked(environmentService.createInstallCode).mockReturnValue(code.promise)
    if (phase === 'clipboard') vi.mocked(copyTextToClipboard).mockReturnValue(clipboard.promise)
    const view = open()
    await user.click(copyInstall())
    if (phase === 'code') await waitFor(() => expect(environmentService.createInstallCode).toHaveBeenCalled())
    if (phase === 'clipboard') await waitFor(() => expect(copyTextToClipboard).toHaveBeenCalled())
    view.unmount()
    await act(async () => {
      code.resolve({ code: 'short-code', expiresAt: 1_791_191_100_000 })
      clipboard.resolve(true)
    })
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('states the current overwrite effects without install settings or backup promises', async () => {
    open()
    const dialog = await screen.findByRole('dialog', { name: '安装/覆盖环境' })
    expect(within(dialog).getByRole('heading', { name: '安装/覆盖环境' })).toBeInTheDocument()
    expect(dialog).toHaveTextContent('选择目标操作系统，复制命令后，在目标主机的终端执行。')
    expect(dialog).toHaveTextContent('无需预装 JRE 或 Node；请确保 Bash 可用。')
    expect(dialog).toHaveTextContent('覆盖安装会中断当前工具调用；本地配置和数据保留。')
    expect(dialog).toHaveTextContent('命令含一次性凭据，请勿分享。')
    expect(dialog).not.toHaveTextContent('保存安装设置')
    expect(dialog).not.toHaveTextContent('长期备份')
    expect(dialog).not.toHaveTextContent('Studio 地址')
    expect(dialog).not.toHaveTextContent('Bash 可执行文件')
  })

  it('uses the same English title and copy notices', () => {
    setLocale('en-US')
    try {
      const install = open()
      const dialog = screen.getByRole('dialog', { name: 'Install/overwrite environment' })
      expect(dialog).toHaveTextContent(
        'Choose the target operating system, copy the command and run it in a terminal on the target host.',
      )
      expect(dialog).toHaveTextContent('The command contains a one-time credential. Do not share it.')
      expect(within(dialog).getByRole('button', { name: 'Copy installation command' })).toBeInTheDocument()
      install.unmount()
      open({ uninstall: true })
      const uninstall = screen.getByRole('dialog', { name: 'Uninstall environment' })
      expect(uninstall).toHaveTextContent('The environment card in Studio is not deleted.')
      expect(within(uninstall).getByRole('button', { name: 'Copy uninstall command' })).toBeInTheDocument()
    } finally {
      setLocale('zh-CN')
    }
  })
})
