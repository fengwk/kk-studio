import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvironmentInstallModal } from './EnvironmentInstallModal'
import { environmentService } from '@/shared/api/environment-service'
import { copyTextToClipboard } from './clipboard'
import { ApiError } from '@/shared/api/client'
import { chooseSelectOption } from '@/test-support/chooseSelectOption'
import type { EnvironmentCardDTO, EnvironmentInstallConfigDTO } from '@/shared/api/contracts/ai-environment'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: { getEnvironment: vi.fn(), saveInstallConfig: vi.fn(), getRegistrationToken: vi.fn() },
}))
vi.mock('./clipboard', () => ({ copyTextToClipboard: vi.fn() }))

const saved: EnvironmentInstallConfigDTO = {
  operatingSystem: 'windows', javaHome: 'C:\\Java\\21',
  daemon: { studioUrl: 'https://saved.example.com', bashExecutable: 'bash.exe', note: 'saved note',
    lsp: { servers: { ts: { command: ['ts-server'], extensions: ['.ts'], rootMarkers: ['pom.xml'] } } } },
}
// Shared codec normalization adds the omitted optional marker array.
const savedNormalized: EnvironmentInstallConfigDTO = {
  operatingSystem: 'windows', javaHome: 'C:\\Java\\21',
  daemon: { studioUrl: 'https://saved.example.com', bashExecutable: 'bash.exe', note: 'saved note',
    lsp: { servers: { ts: { command: ['ts-server'], extensions: ['.ts'], rootMarkers: ['pom.xml'], firstMatchMarkers: [] } } } },
}
const card: EnvironmentCardDTO = {
  id: 'env-1', name: 'box', version: '7', status: 'OFFLINE', ready: false,
  lastSeen: null, capabilities: [], createTime: '', updateTime: '', installConfig: saved,
}
const copyButton = () => screen.getByRole('button', { name: '保存并复制安装命令' })
const origin = () => screen.getByRole('textbox', { name: 'Studio 地址' })
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (err: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function open(options: { uninstall?: boolean; environment?: EnvironmentCardDTO } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const close = vi.fn()
  const environment = options.environment ?? { ...card, version: '1', installConfig: null }
  const view = render(
    <QueryClientProvider client={queryClient}>
      <EnvironmentInstallModal environment={environment} uninstall={options.uninstall} onClose={close} />
    </QueryClientProvider>,
  )
  return { ...view, queryClient, close }
}

describe('EnvironmentInstallModal', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(environmentService.getEnvironment).mockResolvedValue(card)
    vi.mocked(environmentService.saveInstallConfig).mockResolvedValue({ ...card, version: '8' })
    vi.mocked(environmentService.getRegistrationToken).mockResolvedValue({ id: card.id, version: '8', registrationToken: "private-'$`汉字" })
    vi.mocked(copyTextToClipboard).mockResolvedValue(true)
  })

  it('loads saved defaults from latest metadata, saves then explicitly reads token and copies returned settings', async () => {
    const user = userEvent.setup()
    const { queryClient } = open()
    await waitFor(() => expect(origin()).toHaveValue(saved.daemon.studioUrl))
    expect(screen.getByLabelText('操作系统')).toHaveAttribute('data-value', 'windows')
    expect(environmentService.getRegistrationToken).not.toHaveBeenCalled()
    await user.click(screen.getByText(/可选：Java/))
    expect(screen.getByRole('textbox', { name: 'Java home (JDK 21)' })).toHaveValue('C:\\Java\\21')
    expect(screen.getByRole('textbox', { name: '备注' })).toHaveValue('saved note')
    expect(screen.getByRole('checkbox')).toBeChecked()
    expect(screen.getByRole('textbox', { name: 'LSP servers (JSON)' }))
      .toHaveValue(JSON.stringify(savedNormalized.daemon.lsp!.servers, null, 2))
    // Returned canonical config, not unsaved browser form, drives generation.
    vi.mocked(environmentService.saveInstallConfig).mockResolvedValue({ ...card, version: '8',
      installConfig: { operatingSystem: 'linux', daemon: { studioUrl: 'https://canonical.example.com' } } })
    await user.click(copyButton())
    expect(await screen.findByText(/配置已保存，命令已复制/)).toBeInTheDocument()
    expect(screen.getByRole('status').closest('[role="dialog"]')).toBeNull()
    expect(within(screen.getByRole('dialog')).queryByRole('status')).toBeNull()
    expect(environmentService.saveInstallConfig).toHaveBeenCalledWith('env-1', '7', savedNormalized)
    expect(environmentService.getRegistrationToken).toHaveBeenCalledWith('env-1')
    expect(vi.mocked(environmentService.saveInstallConfig).mock.invocationCallOrder[0])
      .toBeLessThan(vi.mocked(environmentService.getRegistrationToken).mock.invocationCallOrder[0]!)
    expect(vi.mocked(copyTextToClipboard).mock.calls[0]![0]).toContain('https://canonical.example.com')
    expect(document.body.textContent).not.toContain("private-'$`汉字")
    expect(JSON.stringify([queryClient.getQueryCache().getAll(), localStorage])).not.toContain('private')
    await user.click(copyButton())
    expect(vi.mocked(environmentService.saveInstallConfig).mock.calls[1]![1]).toBe('8')
  })

  it('uses origin and OS defaults only when configuration is unset', async () => {
    vi.mocked(environmentService.getEnvironment).mockResolvedValue({ ...card, installConfig: null })
    open()
    await waitFor(() => expect(copyButton()).toBeEnabled())
    expect(origin()).toHaveValue(window.location.origin)
    expect(screen.getByLabelText('操作系统')).toHaveAttribute('data-value', 'linux')
  })

  it('preserves draft after save failure with no credential request or copy', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.saveInstallConfig).mockRejectedValue(new Error('save failed'))
    open()
    await waitFor(() => expect(copyButton()).toBeEnabled())
    await user.clear(origin()); await user.type(origin(), 'https://draft.example.com')
    await user.click(copyButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('save failed')
    expect(origin()).toHaveValue('https://draft.example.com')
    expect(environmentService.getRegistrationToken).not.toHaveBeenCalled()
    expect(copyTextToClipboard).not.toHaveBeenCalled()
  })

  it('presents CAS conflict, preserves draft and refreshes version without replacing edited fields', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.saveInstallConfig).mockRejectedValueOnce(
      new ApiError('conflict', 409, 'CONFLICT', { reason: 'stale_version', detail: 'Install settings changed' }))
    open()
    await waitFor(() => expect(copyButton()).toBeEnabled())
    await user.clear(origin()); await user.type(origin(), 'https://draft.example.com')
    await user.click(copyButton())
    const conflict = await screen.findByRole('alertdialog', { name: '数据已发生变化' })
    expect(environmentService.getRegistrationToken).not.toHaveBeenCalled()
    vi.mocked(environmentService.getEnvironment).mockResolvedValue({ ...card, version: '12' })
    await user.click(within(conflict).getByRole('button', { name: '刷新' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(origin()).toHaveValue('https://draft.example.com')
    await user.click(copyButton())
    expect(vi.mocked(environmentService.saveInstallConfig).mock.calls[1]![1]).toBe('12')
  })

  it('never generates a command when the token version moved past the saved config', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.getEnvironment)
      .mockResolvedValueOnce(card)
      // Rebase after the mismatch reads a fresh version without touching the draft.
      .mockResolvedValue({ ...card, version: '12' })
    vi.mocked(environmentService.getRegistrationToken).mockResolvedValueOnce({
      id: card.id, version: '9', registrationToken: 'private',
    })
    open()
    await waitFor(() => expect(copyButton()).toBeEnabled())
    await user.click(copyButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('配置已保存，但环境在读取凭据时已发生变化')
    expect(copyTextToClipboard).not.toHaveBeenCalled()
    expect(screen.queryByText(/配置已保存，命令已复制/)).toBeNull()
    // An explicit retry uses the refreshed version instead of the stale one.
    await user.click(copyButton())
    await waitFor(() => expect(vi.mocked(copyTextToClipboard)).toHaveBeenCalled())
    expect(vi.mocked(environmentService.saveInstallConfig).mock.calls[1]![1]).toBe('12')
  })

  it.each(['token', 'clipboard', 'missing-config'] as const)('explains settings saved but not copied on %s failure', async failure => {
    const user = userEvent.setup()
    if (failure === 'token') vi.mocked(environmentService.getRegistrationToken).mockRejectedValue(new Error('token unavailable'))
    if (failure === 'clipboard') vi.mocked(copyTextToClipboard).mockResolvedValue(false)
    if (failure === 'missing-config') vi.mocked(environmentService.saveInstallConfig).mockResolvedValue({ ...card, installConfig: null, version: '8' })
    open()
    await waitFor(() => expect(copyButton()).toBeEnabled())
    await user.click(copyButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('配置已保存，但命令生成或复制失败')
    expect(screen.queryByText(/配置已保存，命令已复制/)).toBeNull()
  })

  it('reopens with current remote defaults rather than stale list card metadata', async () => {
    const first = open()
    await waitFor(() => expect(origin()).toHaveValue(saved.daemon.studioUrl))
    first.unmount()
    vi.mocked(environmentService.getEnvironment).mockResolvedValue({ ...card, version: '12',
      installConfig: { operatingSystem: 'macos', daemon: { studioUrl: 'https://new.example.com' } } })
    open()
    await waitFor(() => expect(origin()).toHaveValue('https://new.example.com'))
    expect(screen.getByLabelText('操作系统')).toHaveAttribute('data-value', 'macos')
  })

  it.each(['metadata', 'save', 'token', 'clipboard'] as const)('ignores stale %s completion after unmount', async phase => {
    const user = userEvent.setup()
    const metadata = deferred<EnvironmentCardDTO>()
    const save = deferred<EnvironmentCardDTO>()
    const token = deferred<{ id: string; version: string; registrationToken: string }>()
    const clipboard = deferred<boolean>()
    if (phase === 'metadata') vi.mocked(environmentService.getEnvironment).mockReturnValue(metadata.promise)
    if (phase === 'save') vi.mocked(environmentService.saveInstallConfig).mockReturnValue(save.promise)
    if (phase === 'token') vi.mocked(environmentService.getRegistrationToken).mockReturnValue(token.promise)
    if (phase === 'clipboard') vi.mocked(copyTextToClipboard).mockReturnValue(clipboard.promise)
    const view = open()
    if (phase !== 'metadata') {
      await waitFor(() => expect(copyButton()).toBeEnabled())
      await user.click(copyButton())
      if (phase === 'token') await waitFor(() => expect(environmentService.getRegistrationToken).toHaveBeenCalled())
      if (phase === 'clipboard') {
        await waitFor(() => expect(copyTextToClipboard).toHaveBeenCalled())
        // 剪贴板尚未确认时，不能抢先宣布复制成功。
        expect(screen.queryByText(/配置已保存，命令已复制/)).toBeNull()
      }
    }
    view.unmount()
    await act(async () => {
      metadata.resolve(card); save.resolve(card)
      token.resolve({ id: card.id, version: '8', registrationToken: 'private' }); clipboard.resolve(true)
    })
    if (phase === 'save' || phase === 'metadata') expect(environmentService.getRegistrationToken).not.toHaveBeenCalled()
    if (phase === 'token') expect(copyTextToClipboard).not.toHaveBeenCalled()
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('offers reload to repair an invalid persisted configuration without masking it as defaults', async () => {
    vi.mocked(environmentService.getEnvironment).mockResolvedValue({ ...card,
      installConfig: { operatingSystem: 'linux', daemon: { studioUrl: 'https://host/invalid' } } })
    open()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('已保存的安装设置无效')
    expect(alert).toHaveTextContent('Studio 地址无效')
    expect(within(alert).getByRole('button', { name: '重新加载' })).toBeInTheDocument()
    expect(within(alert).getByRole('button', { name: '使用默认设置' })).toBeInTheDocument()
    expect(copyButton()).toBeDisabled()
  })

  it('retries metadata transport failure and keeps a usable form', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.getEnvironment)
      .mockRejectedValueOnce(new Error('metadata unavailable'))
      .mockResolvedValue(card)
    open()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('读取环境信息失败')
    expect(alert).toHaveTextContent('metadata unavailable')
    expect(within(alert).queryByRole('button', { name: '使用默认设置' })).toBeNull()
    await user.click(within(alert).getByRole('button', { name: '重新加载' }))
    await waitFor(() => expect(origin()).toHaveValue(saved.daemon.studioUrl))
    expect(environmentService.getEnvironment).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('lets the user replace an invalid saved configuration with defaults', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.getEnvironment).mockResolvedValue({ ...card,
      installConfig: { operatingSystem: 'linux', daemon: { studioUrl: 'not-an-origin' } } })
    open()
    const alert = await screen.findByRole('alert')
    await user.click(within(alert).getByRole('button', { name: '使用默认设置' }))
    expect(origin()).toHaveValue(window.location.origin)
    await waitFor(() => expect(copyButton()).toBeEnabled())
    await user.click(copyButton())
    await waitFor(() => expect(environmentService.saveInstallConfig).toHaveBeenCalledWith(
      'env-1', '7',
      { operatingSystem: 'linux', javaHome: null,
        daemon: { studioUrl: window.location.origin, note: null, bashExecutable: null, lsp: null } },
    ))
  })

  it('shows metadata transport failure and supports close', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.getEnvironment).mockRejectedValue(new Error('metadata unavailable'))
    const view = open()
    expect(await screen.findByRole('alert')).toHaveTextContent('metadata unavailable')
    await user.click(screen.getAllByRole('button', { name: '关闭' })[0]!)
    expect(view.close).toHaveBeenCalled()
  })

  it('validates JSON, LSP shape and origin before save with field-focused copy', async () => {
    const user = userEvent.setup()
    open()
    await waitFor(() => expect(copyButton()).toBeEnabled())
    await user.click(screen.getByText(/可选：Java/))
    const editor = screen.getByRole('textbox', { name: 'LSP servers (JSON)' })
    fireEvent.change(editor, { target: { value: 'not JSON' } })
    await user.click(copyButton())
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/LSP servers 配置无效/))
    expect(environmentService.saveInstallConfig).not.toHaveBeenCalled()
    // A dot-only extension is rejected by the shared codec, so the form must reject it too.
    fireEvent.change(editor, { target: { value: '{"ts":{"command":["ts"],"extensions":["."]}}' } })
    await user.click(copyButton())
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/LSP servers 配置无效/))
    expect(environmentService.saveInstallConfig).not.toHaveBeenCalled()
    // Missing extensions is likewise invalid rather than silently optional.
    fireEvent.change(editor, { target: { value: '{"ts":{"command":["ts"]}}' } })
    await user.click(copyButton())
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/LSP servers 配置无效/))
    expect(environmentService.saveInstallConfig).not.toHaveBeenCalled()
    await user.click(screen.getByRole('checkbox'))
    await user.clear(origin()); await user.type(origin(), 'https://host/path')
    await user.click(copyButton())
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/Studio 地址无效/))
    await user.clear(origin()); await user.type(origin(), 'https://valid.example.com/')
    await chooseSelectOption(user, '操作系统', 'Linux')
    const java = screen.getByRole('textbox', { name: 'Java home (JDK 21)' })
    fireEvent.change(java, { target: { value: '/opt/${JAVA_HOME}' } })
    await user.click(copyButton())
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/Java home 无效/))
    fireEvent.change(java, { target: { value: '' } })
    const bash = screen.getByRole('textbox', { name: 'Bash 可执行文件' })
    await user.clear(bash); await user.type(bash, ' bash ')
    const note = screen.getByRole('textbox', { name: '备注' })
    await user.clear(note); await user.type(note, '  note  ')
    await user.click(copyButton())
    await waitFor(() => expect(environmentService.saveInstallConfig).toHaveBeenCalledWith('env-1', '7', {
      operatingSystem: 'linux', javaHome: null,
      daemon: { studioUrl: 'https://valid.example.com', bashExecutable: ' bash ', note: 'note', lsp: null },
    }))
  })

  it('shows the LSP and Studio address help while editing', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.getEnvironment).mockResolvedValue({ ...card,
      installConfig: { operatingSystem: 'linux', daemon: { studioUrl: 'https://saved.example.com' } } })
    open()
    await waitFor(() => expect(copyButton()).toBeEnabled())
    expect(screen.getByText(/HTTP\(S\) base 主机/)).toBeInTheDocument()
    await user.click(screen.getByText(/可选：Java/))
    await user.click(screen.getByRole('checkbox'))
    expect(screen.getByText(/语言服务器需自行安装/)).toBeInTheDocument()
  })

  it('states the overwrite warning about the single daemon and its bound environment', async () => {
    open()
    const dialog = await screen.findByRole('dialog')
    // 覆盖语义是重启当前系统用户唯一的 Daemon 并重绑到所选环境，不能声称影响所有环境。
    expect(dialog).toHaveTextContent('覆盖会重启当前系统用户唯一的 Daemon')
    expect(dialog).toHaveTextContent('中断其当前绑定环境的工具调用')
    expect(dialog).toHaveTextContent('连接到所选环境')
    expect(dialog).toHaveTextContent('保留运行数据')
    expect(dialog).toHaveTextContent('保存不代表已应用')
    expect(dialog).not.toHaveTextContent('影响所有环境')
  })

  it('defaults uninstall to the saved operating system without metadata, token or save', async () => {
    const user = userEvent.setup()
    open({ uninstall: true, environment: { ...card, version: '1',
      installConfig: { operatingSystem: 'macos', daemon: { studioUrl: 'https://saved.example.com' } } } })
    expect(screen.getByLabelText('操作系统')).toHaveAttribute('data-value', 'macos')
    // 卸载作用域是当前 OS 用户唯一 Daemon，不一定是本卡片环境，且只移除服务与程序。
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveTextContent('当前系统用户 HOME/.kk-studio 下唯一 Daemon')
    expect(dialog).toHaveTextContent('不一定是此卡片对应的环境')
    expect(dialog).toHaveTextContent('保留本地配置、Token、数据和日志')
    expect(dialog).toHaveTextContent('不删除任何 Studio 环境记录')
    await chooseSelectOption(user, '操作系统', 'Windows')
    await user.click(screen.getByRole('button', { name: '复制卸载命令' }))
    expect(await screen.findByText(/卸载命令已复制/)).toBeInTheDocument()
    expect(vi.mocked(copyTextToClipboard).mock.calls[0]![0]).toContain('-File $installer uninstall')
    expect(environmentService.getEnvironment).not.toHaveBeenCalled()
    expect(environmentService.saveInstallConfig).not.toHaveBeenCalled()
    expect(environmentService.getRegistrationToken).not.toHaveBeenCalled()
    vi.mocked(copyTextToClipboard).mockResolvedValue(false)
    await user.click(screen.getByRole('button', { name: '复制卸载命令' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('复制失败')
  })
})
