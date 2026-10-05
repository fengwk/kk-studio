import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setLocale } from '@/shared/i18n'
import { EnvironmentInstallModal } from './EnvironmentInstallModal'
import { environmentService } from '@/shared/api/environment-service'
import { copyTextToClipboard } from './clipboard'
import { ApiError } from '@/shared/api/client'
import { chooseSelectOption } from '@/test-support/chooseSelectOption'
import type { EnvironmentCardDTO, EnvironmentInstallConfigDTO } from '@/shared/api/contracts/ai-environment'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: { getEnvironment: vi.fn(), saveInstallConfig: vi.fn() },
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
    vi.mocked(copyTextToClipboard).mockResolvedValue(true)
  })

  it('loads saved defaults, saves, then copies the stable environment script URL', async () => {
    const user = userEvent.setup()
    const { queryClient } = open()
    await waitFor(() => expect(origin()).toHaveValue(saved.daemon.studioUrl))
    expect(screen.getByLabelText('操作系统')).toHaveAttribute('data-value', 'windows')
    expect(environmentService.getEnvironment).toHaveBeenCalled()
    await user.click(screen.getByText(/可选：Java/))
    expect(screen.getByRole('textbox', { name: 'Java home (JDK 21)' })).toHaveValue('C:\\Java\\21')
    expect(screen.getByRole('textbox', { name: '备注' })).toHaveValue('saved note')
    expect(screen.getByRole('checkbox')).toBeChecked()
    expect(screen.getByRole('textbox', { name: 'LSP servers (JSON)' }))
      .toHaveValue(JSON.stringify(savedNormalized.daemon.lsp!.servers, null, 2))
    // Returned canonical OS, not the unsaved browser form, selects the script URL.
    vi.mocked(environmentService.saveInstallConfig).mockResolvedValue({ ...card, version: '8',
      installConfig: { operatingSystem: 'linux', daemon: { studioUrl: 'https://canonical.example.com' } } })
    await user.click(copyButton())
    expect(await screen.findByText('安装命令已复制。')).toBeInTheDocument()
    expect(screen.getByRole('status').closest('[role="dialog"]')).toBeNull()
    expect(within(screen.getByRole('dialog')).queryByRole('status')).toBeNull()
    expect(environmentService.saveInstallConfig).toHaveBeenCalledWith('env-1', '7', savedNormalized)
    const copied = vi.mocked(copyTextToClipboard).mock.calls[0]![0]
    expect(copied).toBe(
      `(set -o pipefail; curl -fsSL $'${window.location.origin}/api/harness/environments/env-1/install' | bash)`,
    )
    expect(copied).not.toContain('canonical.example.com')
    expect(copied).not.toContain('registrationToken')
    expect(document.body.textContent).not.toContain('registrationToken')
    expect(JSON.stringify([queryClient.getQueryCache().getAll(), localStorage])).not.toContain('registrationToken')
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
    expect(environmentService.getEnvironment).toHaveBeenCalled()
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
    expect(environmentService.getEnvironment).toHaveBeenCalled()
    vi.mocked(environmentService.getEnvironment).mockResolvedValue({ ...card, version: '12' })
    await user.click(within(conflict).getByRole('button', { name: '刷新' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(origin()).toHaveValue('https://draft.example.com')
    await user.click(copyButton())
    expect(vi.mocked(environmentService.saveInstallConfig).mock.calls[1]![1]).toBe('12')
  })

  it('keeps the same install command for one environment id after the token rotates', async () => {
    const user = userEvent.setup()
    open()
    await waitFor(() => expect(copyButton()).toBeEnabled())
    await user.click(copyButton())
    await waitFor(() => expect(copyTextToClipboard).toHaveBeenCalledTimes(1))
    const first = vi.mocked(copyTextToClipboard).mock.calls[0]![0]
    vi.mocked(environmentService.saveInstallConfig).mockResolvedValue({
      ...card, version: '9', registrationToken: 'rotated-token',
    })
    await user.click(copyButton())
    await waitFor(() => expect(copyTextToClipboard).toHaveBeenCalledTimes(2))
    expect(vi.mocked(copyTextToClipboard).mock.calls[1]![0]).toBe(first)
    expect(first).toContain('/api/harness/environments/env-1/install')
    expect(first).not.toContain('rotated-token')
  })

  it.each(['clipboard', 'missing-config'] as const)('explains settings saved but not copied on %s failure', async failure => {
    const user = userEvent.setup()
    if (failure === 'clipboard') vi.mocked(copyTextToClipboard).mockResolvedValue(false)
    if (failure === 'missing-config') vi.mocked(environmentService.saveInstallConfig).mockResolvedValue({ ...card, installConfig: null, version: '8' })
    open()
    await waitFor(() => expect(copyButton()).toBeEnabled())
    await user.click(copyButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('配置已保存，但命令生成或复制失败')
    expect(screen.queryByText('安装命令已复制。')).toBeNull()
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

  it.each(['metadata', 'save', 'clipboard'] as const)('ignores stale %s completion after unmount', async phase => {
    const user = userEvent.setup()
    const metadata = deferred<EnvironmentCardDTO>()
    const save = deferred<EnvironmentCardDTO>()
    const clipboard = deferred<boolean>()
    if (phase === 'metadata') vi.mocked(environmentService.getEnvironment).mockReturnValue(metadata.promise)
    if (phase === 'save') vi.mocked(environmentService.saveInstallConfig).mockReturnValue(save.promise)
    if (phase === 'clipboard') vi.mocked(copyTextToClipboard).mockReturnValue(clipboard.promise)
    const view = open()
    if (phase !== 'metadata') {
      await waitFor(() => expect(copyButton()).toBeEnabled())
      await user.click(copyButton())
      if (phase === 'clipboard') {
        await waitFor(() => expect(copyTextToClipboard).toHaveBeenCalled())
        // 剪贴板尚未确认时，不能抢先宣布复制成功。
        expect(screen.queryByText('安装命令已复制。')).toBeNull()
      }
    }
    view.unmount()
    await act(async () => {
      metadata.resolve(card); save.resolve(card); clipboard.resolve(true)
    })
    if (phase === 'save' || phase === 'metadata') expect(copyTextToClipboard).not.toHaveBeenCalled()
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
    expect(screen.getByText('不含路径或查询参数。')).toBeInTheDocument()
    expect(origin()).toHaveAttribute('placeholder', 'https://studio.example.com')
    await user.click(screen.getByText(/可选：Java/))
    await user.click(screen.getByRole('checkbox'))
    expect(screen.getByText('请先在目标主机安装对应的语言服务器。')).toBeInTheDocument()
    expect(screen.queryByText(/rootMarkers/)).toBeNull()
  })

  it('states overwrite effects without implementation notes', async () => {
    open()
    const dialog = await screen.findByRole('dialog', { name: '安装/覆盖环境' })
    expect(dialog).toHaveAccessibleName('安装/覆盖环境')
    expect(within(dialog).getByRole('heading', { name: '安装/覆盖环境' })).toBeInTheDocument()
    expect(dialog).not.toHaveTextContent(card.name)
    expect(dialog).toHaveTextContent('复制命令后，在目标主机的终端执行。')
    expect(dialog).toHaveTextContent('需要 JDK 21 和 Bash。')
    expect(dialog).toHaveTextContent('覆盖安装会重启服务并连接到此环境')
    expect(dialog).toHaveTextContent('中断当前工具调用；已有数据保留')
    expect(dialog).toHaveTextContent('命令含凭据，请勿分享。')
    expect(dialog).not.toHaveTextContent('HOME/.kk-studio')
    expect(dialog).not.toHaveTextContent('Daemon')
    expect(dialog).not.toHaveTextContent('保存不代表已应用')
  })

  it('keeps a fresh empty LSP editor as a placeholder and rejects enabling it without JSON', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.getEnvironment).mockResolvedValue({ ...card, installConfig: null })
    open()
    await waitFor(() => expect(copyButton()).toBeEnabled())
    await user.click(screen.getByText(/可选：Java/))
    await user.click(screen.getByRole('checkbox'))
    const editor = screen.getByRole('textbox', { name: 'LSP servers (JSON)' })
    const example = {
      jdtls: {
        command: ['~/.local/share/nvim/mason/bin/jdtls'],
        extensions: ['.java'],
        rootMarkers: ['pom.xml'],
      },
    }
    expect(editor).toHaveValue('')
    expect(JSON.parse(editor.getAttribute('placeholder')!)).toEqual(example)
    await user.click(copyButton())
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/LSP servers 配置无效/))
    expect(environmentService.saveInstallConfig).not.toHaveBeenCalled()
    expect(environmentService.getEnvironment).toHaveBeenCalled()
    expect(copyTextToClipboard).not.toHaveBeenCalled()
    const real = JSON.stringify({
      jdtls: { command: ['/usr/bin/jdtls'], extensions: ['.java'] },
    }, null, 2)
    fireEvent.change(editor, { target: { value: real } })
    expect(editor).toHaveValue(real)
    await user.click(copyButton())
    await waitFor(() => expect(environmentService.saveInstallConfig).toHaveBeenCalledWith('env-1', '7', {
      operatingSystem: 'linux', javaHome: null,
      daemon: {
        studioUrl: window.location.origin, note: null, bashExecutable: null,
        lsp: { servers: { jdtls: { command: ['/usr/bin/jdtls'], extensions: ['.java'], rootMarkers: [], firstMatchMarkers: [] } } },
      },
    }))
  })

  it('rejects a typed empty object instead of substituting the example', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.getEnvironment).mockResolvedValue({ ...card, installConfig: null })
    open()
    await waitFor(() => expect(copyButton()).toBeEnabled())
    await user.click(screen.getByText(/可选：Java/))
    await user.click(screen.getByRole('checkbox'))
    const editor = screen.getByRole('textbox', { name: 'LSP servers (JSON)' })
    fireEvent.change(editor, { target: { value: '{}' } })
    expect(editor).toHaveValue('{}')
    expect(editor).toHaveAttribute('placeholder', expect.stringContaining('jdtls'))
    await user.click(copyButton())
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/LSP servers 配置无效/))
    expect(editor).toHaveValue('{}')
    expect(environmentService.saveInstallConfig).not.toHaveBeenCalled()
    expect(environmentService.getEnvironment).toHaveBeenCalled()
    expect(copyTextToClipboard).not.toHaveBeenCalled()
  })

  it('shows saved LSP settings without substituting the example', async () => {
    const servers = { jdtls: { command: ['/opt/jdtls'], extensions: ['.java'], rootMarkers: ['pom.xml'], firstMatchMarkers: ['build.gradle'] } }
    vi.mocked(environmentService.getEnvironment).mockResolvedValue({ ...card,
      installConfig: { operatingSystem: 'linux', daemon: { studioUrl: 'https://saved.example.com', lsp: { servers } } } })
    const user = userEvent.setup()
    open()
    await waitFor(() => expect(copyButton()).toBeEnabled())
    await user.click(screen.getByText(/可选：Java/))
    const editor = screen.getByRole('textbox', { name: 'LSP servers (JSON)' })
    expect(editor).toHaveValue(JSON.stringify(servers, null, 2))
    expect(editor).not.toHaveValue(/mason/)
  })

  it('uses the same English title and copy placeholders', () => {
    setLocale('en-US')
    try {
      const install = open()
      const dialog = screen.getByRole('dialog', { name: 'Install/overwrite environment' })
      expect(within(dialog).getByRole('heading', { name: 'Install/overwrite environment' })).toBeInTheDocument()
      expect(dialog).toHaveTextContent('Copy the command and run it in a terminal on the target host.')
      expect(dialog).toHaveTextContent('The command contains credentials. Do not share it.')
      install.unmount()
      open({ uninstall: true })
      const uninstall = screen.getByRole('dialog', { name: 'Uninstall environment' })
      expect(within(uninstall).getByRole('heading', { name: 'Uninstall environment' })).toBeInTheDocument()
      expect(uninstall).toHaveTextContent('The environment record in Studio is not deleted.')
    } finally {
      setLocale('zh-CN')
    }
  })

  it('defaults uninstall to the saved operating system without metadata, token or save', async () => {
    const user = userEvent.setup()
    open({ uninstall: true, environment: { ...card, version: '1',
      installConfig: { operatingSystem: 'macos', daemon: { studioUrl: 'https://saved.example.com' } } } })
    expect(screen.getByLabelText('操作系统')).toHaveAttribute('data-value', 'macos')
    // 卸载作用域是当前 OS 用户唯一 Daemon，不一定是本卡片环境，且只移除服务与程序。
    const dialog = screen.getByRole('dialog', { name: '卸载环境' })
    expect(within(dialog).getByRole('heading', { name: '卸载环境' })).toBeInTheDocument()
    expect(dialog).not.toHaveTextContent(card.name)
    expect(dialog).toHaveTextContent('在需要卸载的主机上执行命令。')
    expect(dialog).toHaveTextContent('环境服务会被移除，本地配置和数据保留')
    expect(dialog).toHaveTextContent('Studio 中的环境记录不会删除')
    expect(dialog).not.toHaveTextContent('HOME/.kk-studio')
    expect(dialog).not.toHaveTextContent('Token')
    await chooseSelectOption(user, '操作系统', 'Windows')
    await user.click(screen.getByRole('button', { name: '复制卸载命令' }))
    expect(await screen.findByText('卸载命令已复制。')).toBeInTheDocument()
    expect(vi.mocked(copyTextToClipboard).mock.calls[0]![0]).toContain(
      `${window.location.origin}/api/harness/environments/uninstall/windows`,
    )
    expect(environmentService.getEnvironment).not.toHaveBeenCalled()
    expect(environmentService.saveInstallConfig).not.toHaveBeenCalled()
    vi.mocked(copyTextToClipboard).mockResolvedValue(false)
    await user.click(screen.getByRole('button', { name: '复制卸载命令' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('复制失败')
  })
})
