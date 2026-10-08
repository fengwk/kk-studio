import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PluginConnectModal } from './PluginConnectModal'
import { pluginsService } from '@/shared/api/plugins-service'
import { setLocale } from '@/shared/i18n'
import type { PluginDTO } from '@/shared/api/contracts/ai-plugin'

vi.mock('@/shared/api/plugins-service', () => ({
  pluginsService: { prepareAuth: vi.fn(), completeAuth: vi.fn() },
}))

const plugin: PluginDTO = {
  pluginId: 'demo', name: 'Demo', version: '1', status: 'NOT_CONNECTED',
  authKind: { type: 'DEEP_LINK', regionCandidates: ['CN', 'EN'] },
  region: null, expiresAt: null, nextRefreshAt: null, lastRefreshedAt: null, lastRefreshError: null,
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

describe('PluginConnectModal lifecycle', () => {
  let client: QueryClient
  const onClose = vi.fn()
  const onSuccess = vi.fn()
  beforeEach(() => {
    vi.resetAllMocks()
    vi.stubGlobal('open', vi.fn())
    // 即使全局配置重试，凭据请求也必须明确不重试。
    client = new QueryClient({ defaultOptions: { mutations: { retry: 3, retryDelay: 0 } } })
    setLocale('zh-CN')
  })
  afterEach(() => { client.clear(); vi.unstubAllGlobals(); setLocale('zh-CN') })

  function view(target = plugin) {
    return <QueryClientProvider client={client}>
      <PluginConnectModal plugin={target} onClose={onClose} onSuccess={onSuccess} />
    </QueryClientProvider>
  }
  const input = () => screen.getByTestId('plugin-callback-input')
  const login = () => screen.getByRole('button', { name: '打开官方登录页面' })
  const complete = () => screen.getByRole('button', { name: '完成连接' })

  it.each(['prepare', 'complete'] as const)('blocks all close paths and cross-stage submissions while %s is pending', async (stage) => {
    const user = userEvent.setup()
    const prepare = deferred<{ loginUrl: string }>()
    const finish = deferred<void>()
    vi.mocked(pluginsService.prepareAuth).mockReturnValue(prepare.promise)
    vi.mocked(pluginsService.completeAuth).mockReturnValue(finish.promise)
    render(view())
    fireEvent.change(input(), { target: { value: 'callback' } })
    const form = input().closest('form')!
    const loginButton = login()
    if (stage === 'prepare') {
      // 同步重复事件验证 mutate 边界，不仅依赖下一次 render 的 disabled。
      fireEvent.click(loginButton)
      fireEvent.click(loginButton)
    } else {
      fireEvent.submit(form)
      fireEvent.submit(form)
      expect(input()).toHaveValue('')
    }
    await waitFor(() => expect(input()).toBeDisabled())
    expect(screen.getByRole('button', { name: '区域' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '取消' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '关闭' })).toBeDisabled()
    fireEvent.click(loginButton)
    fireEvent.submit(form)
    fireEvent.change(input(), { target: { value: 'ignored' } })
    await user.keyboard('{Escape}')
    fireEvent.mouseDown(screen.getByRole('presentation'))
    fireEvent.click(screen.getByRole('button', { name: '关闭' }))
    fireEvent.click(screen.getByRole('button', { name: '取消' }))
    expect(onClose).not.toHaveBeenCalled()
    await waitFor(() => expect(stage === 'prepare' ? pluginsService.prepareAuth : pluginsService.completeAuth).toHaveBeenCalledTimes(1))
    expect(stage === 'prepare' ? pluginsService.completeAuth : pluginsService.prepareAuth).not.toHaveBeenCalled()
    await act(async () => {
      if (stage === 'prepare') prepare.resolve({ loginUrl: '' })
      else finish.reject(new Error('Rejected'))
    })
    await waitFor(() => expect(input()).not.toBeDisabled())
    if (stage === 'complete') {
      expect(input()).toHaveValue('')
      expect(screen.getByRole('alert')).toHaveTextContent('Rejected')
    }
    await user.keyboard('{Escape}')
    fireEvent.mouseDown(screen.getByRole('presentation'))
    await user.click(screen.getByRole('button', { name: '关闭' }))
    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(onClose).toHaveBeenCalledTimes(4)
  })

  it.each(['prepare', 'complete'] as const)('never retries a failed %s request and permits an explicit retry', async (stage) => {
    vi.mocked(pluginsService.prepareAuth).mockRejectedValue('Prepare failed')
    vi.mocked(pluginsService.completeAuth).mockRejectedValue('Complete failed')
    render(view())
    if (stage === 'prepare') fireEvent.click(login())
    else {
      fireEvent.change(input(), { target: { value: 'callback' } })
      fireEvent.submit(input().closest('form')!)
    }
    expect(await screen.findByRole('alert')).toHaveTextContent(stage === 'prepare' ? 'Prepare failed' : 'Complete failed')
    const service = stage === 'prepare' ? pluginsService.prepareAuth : pluginsService.completeAuth
    expect(service).toHaveBeenCalledTimes(1)
    if (stage === 'prepare') fireEvent.click(login())
    else {
      expect(input()).toHaveValue('')
      fireEvent.change(input(), { target: { value: 'second' } })
      fireEvent.click(complete())
    }
    await waitFor(() => expect(service).toHaveBeenCalledTimes(2))
  })

  it.each(['prepare', 'complete'] as const)('ignores late %s success or error after unmount or plugin replacement', async (stage) => {
    // 每个实际 onSuccess/onError 都在卸载实例上被触发，不能打开网页或污染新实例。
    for (const replace of [false, true]) {
      for (const fail of [false, true]) {
        const prepare = deferred<{ loginUrl: string }>()
        const finish = deferred<void>()
        if (stage === 'prepare') vi.mocked(pluginsService.prepareAuth).mockReturnValue(prepare.promise)
        else vi.mocked(pluginsService.completeAuth).mockReturnValue(finish.promise)
        const mounted = render(view())
        if (stage === 'prepare') fireEvent.click(login())
        else {
          fireEvent.change(input(), { target: { value: 'callback' } })
          fireEvent.submit(input().closest('form')!)
        }
        await waitFor(() => expect(input()).toBeDisabled())
        if (replace) mounted.rerender(view({ ...plugin, pluginId: 'other', name: 'Other' }))
        else mounted.unmount()
        await act(async () => {
          if (fail) {
            if (stage === 'prepare') prepare.reject(new Error('Late failure'))
            else finish.reject(new Error('Late failure'))
          } else if (stage === 'prepare') prepare.resolve({ loginUrl: 'https://example.invalid' })
          else finish.resolve()
        })
        expect(window.open).not.toHaveBeenCalled()
        expect(onSuccess).not.toHaveBeenCalled()
        if (replace) {
          expect(screen.getByRole('dialog')).toHaveTextContent('Other')
          expect(input()).toHaveValue('')
          expect(input()).not.toBeDisabled()
          expect(screen.queryByRole('alert')).not.toBeInTheDocument()
          mounted.unmount()
        }
      }
    }
  })

  it('uses shared secure input, trims callback, clears immediately and reports current success', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.completeAuth).mockResolvedValue(undefined)
    vi.mocked(pluginsService.prepareAuth).mockResolvedValue({ loginUrl: 'https://example.invalid' })
    render(view())
    expect(input()).toHaveClass('ui-text-input')
    expect(input()).toHaveAttribute('type', 'password')
    expect(input()).toHaveAttribute('autocomplete', 'off')
    expect(input()).toHaveAccessibleName('2. 粘贴回调 Deep Link')
    fireEvent.submit(input().closest('form')!)
    expect(pluginsService.completeAuth).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '区域' }))
    await user.click(screen.getByRole('option', { name: 'EN' }))
    fireEvent.click(login())
    await waitFor(() => expect(window.open).toHaveBeenCalledWith('https://example.invalid', '_blank', 'noopener,noreferrer'))
    expect(pluginsService.prepareAuth).toHaveBeenCalledWith('demo', { region: 'EN' })
    expect(screen.getByText(/登录后，从浏览器/)).toBeInTheDocument()
    await waitFor(() => expect(input()).not.toBeDisabled())
    fireEvent.change(input(), { target: { value: '  callback  ' } })
    fireEvent.click(complete())
    expect(input()).toHaveValue('')
    await waitFor(() => expect(onSuccess).toHaveBeenCalledTimes(1))
    expect(pluginsService.completeAuth).toHaveBeenCalledWith('demo', { callbackUrl: 'callback' })
  })

  it('locks unavailable credentials even for programmatic submissions, with fallback region and English copy', () => {
    setLocale('en-US')
    render(view({ ...plugin, status: 'KEY_UNAVAILABLE', authKind: null }))
    expect(screen.getByRole('button', { name: 'Region' })).toHaveTextContent('CN')
    expect(screen.getByRole('alert')).toHaveTextContent('Credential operations are locked')
    expect(screen.getByText('Open the login page first, then paste the callback link here.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeEnabled()
    fireEvent.submit(input().closest('form')!)
    expect(pluginsService.completeAuth).not.toHaveBeenCalled()
  })
})
