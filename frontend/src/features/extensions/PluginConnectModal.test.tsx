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
  pluginId: 'demo',
  name: 'Demo',
  version: '1',
  status: 'NOT_CONNECTED',
  authKind: { type: 'DEEP_LINK', regionCandidates: ['CN', 'EN'] },
  region: null,
  expiresAt: null,
  nextRefreshAt: null,
  lastRefreshedAt: null,
  lastRefreshError: null,
  configuration: {
    revision: '1',
    valuesJson: '{}',
    valuesSchema: '{"type":"object","properties":{}}',
    defaultsJson: '{}',
    secrets: {},
  },
  desiredRevision: '1',
  appliedRevision: '1',
  connectionCheckSupported: false,
  harnessToolNames: [],
  canvasFunctionNames: [],
  applicationError: null,
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

describe('PluginConnectModal lifecycle', () => {
  let client: QueryClient
  const onClose = vi.fn()
  const onSuccess = vi.fn()

  beforeEach(() => {
    vi.resetAllMocks()
    vi.stubGlobal('open', vi.fn())
    client = new QueryClient({
      defaultOptions: { mutations: { retry: false } },
    })
    setLocale('zh-CN')
  })

  afterEach(() => {
    client.clear()
    vi.unstubAllGlobals()
    setLocale('zh-CN')
  })

  function view(target = plugin) {
    return (
      <QueryClientProvider client={client}>
        <PluginConnectModal plugin={target} onClose={onClose} onSuccess={onSuccess} />
      </QueryClientProvider>
    )
  }

  const input = () => screen.getByTestId('plugin-callback-input')
  const login = () => screen.getByRole('button', { name: '打开官方登录页面' })
  const complete = () => screen.getByRole('button', { name: '完成连接' })

  it.each(['prepare', 'complete'] as const)(
    'blocks all close paths and cross-stage submissions while %s is pending',
    async (stage) => {
      const user = userEvent.setup()
      const prepare = deferred<{ authAttemptId: string; loginUrl: string }>()
      const finish = deferred<PluginDTO>()
      vi.mocked(pluginsService.prepareAuth).mockReturnValue(prepare.promise)
      vi.mocked(pluginsService.completeAuth).mockReturnValue(finish.promise)

      render(view())
      const form = input().closest('form')!
      const loginButton = login()

      if (stage === 'prepare') {
        fireEvent.change(input(), { target: { value: 'callback' } })
        fireEvent.click(loginButton)
        fireEvent.click(loginButton)
      } else {
        // Complete stage requires an attemptId obtained from prepare first
        vi.mocked(pluginsService.prepareAuth).mockResolvedValueOnce({
          authAttemptId: 'attempt-nonce-1',
          loginUrl: 'https://auth.example.com',
        })
        fireEvent.click(loginButton)
        await waitFor(() =>
          expect(
            screen.getByText('先打开登录页面，再将回调链接粘贴到这里。'),
          ).toBeInTheDocument(),
        )
        fireEvent.change(input(), { target: { value: 'callback' } })
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

      await waitFor(() =>
        expect(
          stage === 'prepare' ? pluginsService.prepareAuth : pluginsService.completeAuth,
        ).toHaveBeenCalledTimes(1),
      )

      await act(async () => {
        if (stage === 'prepare') {
          prepare.resolve({ authAttemptId: 'attempt-1', loginUrl: '' })
        } else {
          finish.reject(new Error('Rejected'))
        }
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

      // 断言全局 ReactQuery mutationCache 没有任何 secret / callback / nonce 数据
      expect(client.getMutationCache().getAll()).toHaveLength(0)
    },
  )

  it.each(['prepare', 'complete'] as const)(
    'never retries a failed %s request and permits an explicit retry',
    async (stage) => {
      vi.mocked(pluginsService.prepareAuth).mockRejectedValue(new Error('Prepare failed'))
      vi.mocked(pluginsService.completeAuth).mockRejectedValue(new Error('Complete failed'))
      render(view())

      if (stage === 'prepare') {
        fireEvent.click(login())
      } else {
        // Prepare must succeed before complete can be attempted
        vi.mocked(pluginsService.prepareAuth).mockResolvedValueOnce({
          authAttemptId: 'attempt-1',
          loginUrl: 'https://example.com',
        })
        fireEvent.click(login())
        await waitFor(() => expect(pluginsService.prepareAuth).toHaveBeenCalledTimes(1))
        fireEvent.change(input(), { target: { value: 'callback' } })
        fireEvent.submit(input().closest('form')!)
      }

      expect(await screen.findByRole('alert')).toHaveTextContent(
        stage === 'prepare' ? 'Prepare failed' : 'Complete failed',
      )
      const service =
        stage === 'prepare' ? pluginsService.prepareAuth : pluginsService.completeAuth
      expect(service).toHaveBeenCalledTimes(1)

      if (stage === 'prepare') {
        fireEvent.click(login())
      } else {
        expect(input()).toHaveValue('')
        // 因为 nonce 已消费，重新输入后 complete 按钮应为 disabled 直到重新 prepare
        expect(complete()).toBeDisabled()
      }

      // 断言全局 mutationCache 为空
      expect(client.getMutationCache().getAll()).toHaveLength(0)
    },
  )

  it('blocks complete submission without an active prepare attempt handle', async () => {
    render(view())
    fireEvent.change(input(), { target: { value: 'callback-url' } })
    expect(complete()).toBeDisabled()
    fireEvent.submit(input().closest('form')!)
    expect(pluginsService.completeAuth).not.toHaveBeenCalled()
  })

  it('uses shared secure input, trims callback, clears immediately and sends attempt handle with callback', async () => {
    const user = userEvent.setup()
    vi.mocked(pluginsService.completeAuth).mockResolvedValue(plugin)
    vi.mocked(pluginsService.prepareAuth).mockResolvedValue({
      authAttemptId: 'nonce-handle-xyz',
      loginUrl: 'https://example.invalid',
    })
    render(view())
    expect(input()).toHaveClass('ui-text-input')
    expect(input()).toHaveAttribute('type', 'password')
    expect(input()).toHaveAttribute('autocomplete', 'off')
    expect(input()).toHaveAccessibleName('2. 粘贴回调 Deep Link')

    // Step 1: select region EN and open login
    await user.click(screen.getByRole('button', { name: '区域' }))
    await user.click(screen.getByRole('option', { name: 'EN' }))
    fireEvent.click(login())

    await waitFor(() =>
      expect(window.open).toHaveBeenCalledWith(
        'https://example.invalid',
        '_blank',
        'noopener,noreferrer',
      ),
    )
    expect(pluginsService.prepareAuth).toHaveBeenCalledWith('demo', { region: 'EN' })

    // Step 2: complete with attemptId and callback
    await waitFor(() => expect(input()).not.toBeDisabled())
    fireEvent.change(input(), { target: { value: '  callback  ' } })
    expect(complete()).not.toBeDisabled()
    fireEvent.click(complete())

    expect(input()).toHaveValue('')
    await waitFor(() => expect(onSuccess).toHaveBeenCalledTimes(1))
    expect(pluginsService.completeAuth).toHaveBeenCalledWith('demo', {
      authAttemptId: 'nonce-handle-xyz',
      callbackUrl: 'callback',
    })

    // 断言全局 mutationCache 为空
    expect(client.getMutationCache().getAll()).toHaveLength(0)
  })

  it('warns when region candidates are empty', () => {
    setLocale('en-US')
    render(
      view({
        ...plugin,
        authKind: { type: 'DEEP_LINK', regionCandidates: [] },
      }),
    )
    expect(screen.getByText('No regions available for this plugin')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeEnabled()
    fireEvent.submit(input().closest('form')!)
    expect(pluginsService.completeAuth).not.toHaveBeenCalled()
  })
})
