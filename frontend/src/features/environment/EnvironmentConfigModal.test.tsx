import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { EnvironmentConfigModal } from './EnvironmentConfigModal'
import { environmentService } from '@/features/environment/environment-service'
import { ApiError } from '@/shared/api/client'
import {
  testDaemonConfig,
  testEnvironment,
} from '@/test-support/environment-test-fixtures'

vi.mock('@/features/environment/environment-service', () => ({
  environmentService: {
    getEnvironment: vi.fn(),
    createEnvironment: vi.fn(),
    updateEnvironment: vi.fn(),
  },
}))

const card = testEnvironment({
  id: 'env-1',
  name: 'host-one',
  note: 'saved note',
  version: '7',
  daemonConfig: testDaemonConfig({
    studioUrl: 'https://saved.example.com',
    bashExecutable: '/bin/bash',
    proxyUrl: 'http://127.0.0.1:7890',
    lsp: {
      servers: {
        ts: { command: ['ts-server'], extensions: ['.ts'], rootMarkers: ['pom.xml'] },
      },
    },
  }),
})

function open(environment?: typeof card) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const close = vi.fn()
  const created = vi.fn()
  const view = render(
    <QueryClientProvider client={queryClient}>
      <EnvironmentConfigModal environment={environment} onClose={close} onCreated={created} />
    </QueryClientProvider>,
  )
  return { ...view, queryClient, close, created }
}

const saveButton = () => screen.getByRole('button', { name: '保存配置' })
const createButton = () => screen.getByRole('button', { name: '创建环境' })

describe('EnvironmentConfigModal', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(environmentService.getEnvironment).mockResolvedValue(card)
    vi.mocked(environmentService.createEnvironment).mockImplementation(async (data) => ({
      ...card,
      ...data,
      daemonConfig: data.daemonConfig,
    }))
    vi.mocked(environmentService.updateEnvironment).mockImplementation(async (id, data) => ({
      ...card,
      id,
      note: data.note,
      daemonConfig: data.daemonConfig,
      version: String(Number(data.expectedVersion) + 1),
    }))
  })

  it('creates a card with name, note, full daemon config and null direct proxy', async () => {
    const user = userEvent.setup()
    const { created } = open()
    expect(screen.getByRole('dialog', { name: '创建环境' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Studio 地址' })).toHaveValue(window.location.origin)
    await user.type(screen.getByRole('textbox', { name: '环境名称' }), ' new-host ')
    await user.type(screen.getByRole('textbox', { name: '备注' }), '  my note  ')
    const studio = screen.getByRole('textbox', { name: 'Studio 地址' })
    await user.clear(studio)
    await user.type(studio, 'https://studio.example.com/')
    await user.click(createButton())
    await waitFor(() =>
      expect(environmentService.createEnvironment).toHaveBeenCalledWith({
        name: 'new-host',
        note: 'my note',
        daemonConfig: {
          studioUrl: 'https://studio.example.com',
          bashExecutable: null,
          lsp: null,
          proxyUrl: null,
        },
      }),
    )
    await waitFor(() => expect(created).toHaveBeenCalledTimes(1))
    expect(created.mock.calls[0]![0]).toMatchObject({ name: 'new-host' })
  })

  it('edits every configurable property while keeping name immutable', async () => {
    const user = userEvent.setup()
    const { close } = open(card)
    expect(screen.getByRole('dialog', { name: '编辑环境：host-one' })).toBeInTheDocument()
    const name = screen.getByRole('textbox', { name: '环境名称' })
    expect(name).toBeDisabled()
    expect(name).toHaveValue('host-one')
    expect(screen.getByRole('textbox', { name: '备注' })).toHaveValue('saved note')
    expect(screen.getByRole('textbox', { name: 'Studio 地址' })).toHaveValue('https://saved.example.com')
    expect(screen.getByRole('textbox', { name: 'Bash 可执行文件' })).toHaveValue('/bin/bash')
    expect(screen.getByRole('textbox', { name: 'Daemon 代理' })).toHaveValue('http://127.0.0.1:7890')
    expect(screen.getByRole('checkbox')).toBeChecked()
    expect(screen.getByRole('textbox', { name: 'LSP servers' })).toHaveValue(
      JSON.stringify(card.daemonConfig.lsp!.servers, null, 2),
    )

    await user.clear(screen.getByRole('textbox', { name: '备注' }))
    await user.type(screen.getByRole('textbox', { name: '备注' }), 'updated')
    await user.clear(screen.getByRole('textbox', { name: 'Daemon 代理' }))
    await user.type(screen.getByRole('textbox', { name: 'Daemon 代理' }), ' http://proxy.example.com:8080 ')
    await user.click(saveButton())
    await waitFor(() =>
      expect(environmentService.updateEnvironment).toHaveBeenCalledWith('env-1', {
        expectedVersion: '7',
        note: 'updated',
        daemonConfig: {
          studioUrl: 'https://saved.example.com',
          bashExecutable: '/bin/bash',
          lsp: {
            servers: {
              ts: {
                command: ['ts-server'],
                extensions: ['.ts'],
                rootMarkers: ['pom.xml'],
                firstMatchMarkers: [],
              },
            },
          },
          proxyUrl: 'http://proxy.example.com:8080',
        },
      }),
    )
    await waitFor(() => expect(close).toHaveBeenCalled())
  })

  it('preserves the draft across a CAS conflict and saves again with the refreshed version', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.updateEnvironment).mockRejectedValueOnce(
      new ApiError('conflict', 409, 'version_conflict', undefined, undefined, 'card changed'),
    )
    open(card)
    const studio = screen.getByRole('textbox', { name: 'Studio 地址' })
    await user.clear(studio)
    await user.type(studio, 'https://draft.example.com')
    await user.click(saveButton())
    const conflict = await screen.findByRole('alertdialog', { name: '数据已发生变化' })
    expect(environmentService.getEnvironment).not.toHaveBeenCalled()
    vi.mocked(environmentService.getEnvironment).mockResolvedValue({ ...card, version: '12' })
    await user.click(within(conflict).getByRole('button', { name: '刷新' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(screen.getByRole('textbox', { name: 'Studio 地址' })).toHaveValue('https://draft.example.com')
    await user.click(saveButton())
    await waitFor(() =>
      expect(vi.mocked(environmentService.updateEnvironment).mock.calls[1]![1].expectedVersion).toBe('12'),
    )
    expect(vi.mocked(environmentService.updateEnvironment).mock.calls[1]![1].daemonConfig.studioUrl)
      .toBe('https://draft.example.com')
  })

  it('validates studio address, proxy, LSP JSON and note before calling the API', async () => {
    const user = userEvent.setup()
    open()
    const studio = screen.getByRole('textbox', { name: 'Studio 地址' })
    await user.clear(studio)
    await user.type(studio, 'https://host/path')
    await user.click(createButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('Studio 地址无效')
    expect(environmentService.createEnvironment).not.toHaveBeenCalled()

    await user.clear(studio)
    await user.type(studio, 'https://valid.example.com')
    const proxy = screen.getByRole('textbox', { name: 'Daemon 代理' })
    await user.type(proxy, 'https://host:8080')
    await user.click(createButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('Daemon 代理无效')
    expect(environmentService.createEnvironment).not.toHaveBeenCalled()

    await user.clear(proxy)
    await user.type(proxy, 'http://host:8080')
    await user.click(screen.getByRole('checkbox'))
    await user.type(screen.getByRole('textbox', { name: 'LSP servers' }), 'not JSON')
    await user.click(createButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('LSP servers 配置无效')
    expect(environmentService.createEnvironment).not.toHaveBeenCalled()

    await user.click(screen.getByRole('checkbox'))
    await user.type(screen.getByRole('textbox', { name: '环境名称' }), 'valid-name')
    fireEvent.change(screen.getByRole('textbox', { name: '备注' }), { target: { value: 'x\u2028y' } })
    await user.click(createButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('备注必须为单行')
    expect(environmentService.createEnvironment).not.toHaveBeenCalled()
  })

  it('shows direct proxy and hot update help hints', async () => {
    const user = userEvent.setup()
    open()
    expect(screen.getAllByText(/留空表示直连/).length).toBeGreaterThan(0)
    const applyHelp = screen.getByRole('button', { name: /帮助：保存后的配置无需重装 Daemon 即可生效/ })
    await user.click(applyHelp)
    expect(applyHelp).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText(/运行中的 Daemon 会立即收到完整配置/)).toBeVisible()
    const proxyHelp = screen.getByRole('button', { name: /帮助：Daemon 代理/ })
    await user.click(proxyHelp)
    expect(screen.getAllByText(/仅支持无认证的 http:\/\/host:port/).length).toBeGreaterThan(0)
  })

  it('shows a duplicate-name server failure inline instead of dropping the draft', async () => {
    const user = userEvent.setup()
    vi.mocked(environmentService.createEnvironment).mockRejectedValue(
      new ApiError('name exists', 409, 'duplicate', undefined, 'ENVIRONMENT_NAME_TAKEN', 'name exists'),
    )
    open()
    await user.type(screen.getByRole('textbox', { name: '环境名称' }), 'taken')
    await user.click(createButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('name exists')
    expect(screen.getByRole('textbox', { name: '环境名称' })).toHaveValue('taken')
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })
})
