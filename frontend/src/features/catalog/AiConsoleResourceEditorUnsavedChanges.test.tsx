import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ResourceEditorModal } from '@/features/catalog/AiConsoleResourceEditorModal'
import { useAiConsoleResourceController } from '@/features/catalog/useAiConsoleResourceController'
import { agentService } from '@/shared/api/agent-service'
import type { AgentDTO, ProviderDTO, SingleModelDTO } from '@/shared/api/generated/contracts'

vi.mock('@/features/environment/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(async () => []),
    getEnvironmentRuntime: vi.fn(),
    startEnvironmentUpdate: vi.fn(),
  },
}))

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listProviders: vi.fn(),
    listModels: vi.fn(),
    listAgents: vi.fn(),
    listTools: vi.fn(),
    listSkillPackages: vi.fn(),
    createProvider: vi.fn(),
    updateProvider: vi.fn(),
    deleteProvider: vi.fn(),
    createModel: vi.fn(),
    updateModel: vi.fn(),
    deleteModel: vi.fn(),
    createAgent: vi.fn(),
    updateAgent: vi.fn(),
    deleteAgent: vi.fn(),
  },
}))

type ClosePath = 'escape' | 'close-button' | 'footer-cancel' | 'backdrop'

/**
 * 资源编辑器的未保存确认：关闭入口全部在弹窗内部（Dialog 的 Escape/遮罩/关闭按钮与页脚取消）。
 * 宿主消费真实控制器，弹窗按生产接线只在有资源时挂载，因此草稿基线、保存成功关闭都走真实链路。
 */
function Harness() {
  const controller = useAiConsoleResourceController({
    providers: true,
    models: true,
    agents: true,
    tools: true,
    skills: true,
    environments: true,
  })
  const editor = controller.resourceEditorModal
  const ready =
    !controller.queries.providers.isLoading &&
    !controller.queries.models.isLoading &&
    !controller.queries.agents.isLoading

  return (
    <div>
      <div data-testid="ready">{ready ? 'ready' : 'loading'}</div>
      <div data-testid="editor-state">{editor.modal ? 'open' : 'closed'}</div>
      <button type="button" onClick={() => controller.editorActions.createProvider()}>
        create-provider
      </button>
      <button type="button" onClick={() => controller.editorActions.editProvider('stub')}>
        edit-provider
      </button>
      <button type="button" onClick={() => controller.editorActions.createModel()}>
        create-model
      </button>
      <button type="button" onClick={() => controller.editorActions.createAgent()}>
        create-agent
      </button>
      {editor.modal ? <ResourceEditorModal {...editor} /> : null}
    </div>
  )
}

/** 编辑器与确认弹窗是嵌套的两层：编辑器始终是 role=dialog，确认是 role=alertdialog。 */
function editorDialog(): HTMLElement {
  return screen.getByRole('dialog')
}

/** 第一个文本框在每种资源表单里都是名称字段，改它即可让草稿偏离打开时的基线。 */
function firstField(scope: HTMLElement): HTMLElement {
  return within(scope).getAllByRole('textbox')[0]
}

/** 遮罩是弹窗卡片的父节点；在遮罩自身按下鼠标才会关闭。 */
function backdropOf(dialog: HTMLElement): HTMLElement {
  const backdrop = dialog.parentElement
  if (!backdrop) {
    throw new Error('dialog is not portaled under a backdrop')
  }
  return backdrop
}

async function openEditor(
  user: ReturnType<typeof userEvent.setup>,
  action: 'create-provider' | 'edit-provider' | 'create-model' | 'create-agent',
): Promise<HTMLElement> {
  await waitFor(() => {
    expect(screen.getByTestId('ready')).toHaveTextContent('ready')
  })
  await user.click(screen.getByRole('button', { name: action }))
  await waitFor(() => {
    expect(screen.getByTestId('editor-state')).toHaveTextContent('open')
  })
  return editorDialog()
}

async function closeEditor(
  user: ReturnType<typeof userEvent.setup>,
  dialog: HTMLElement,
  path: ClosePath,
): Promise<void> {
  if (path === 'escape') {
    await user.keyboard('{Escape}')
    return
  }
  if (path === 'backdrop') {
    fireEvent.mouseDown(backdropOf(dialog))
    return
  }
  const label = path === 'close-button' ? '关闭' : '取消'
  await user.click(within(dialog).getByRole('button', { name: label }))
}

async function expectEditorClosed(): Promise<void> {
  await waitFor(() => {
    expect(screen.getByTestId('editor-state')).toHaveTextContent('closed')
  })
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
}

function keepEditing(): HTMLElement {
  return within(screen.getByRole('alertdialog')).getByRole('button', { name: '继续编辑' })
}

function discardChanges(): HTMLElement {
  return within(screen.getByRole('alertdialog')).getByRole('button', { name: '放弃修改' })
}

function renderHarness() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <Harness />
    </QueryClientProvider>,
  )
}

describe('catalog resource editor unsaved changes', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(agentService.listProviders).mockResolvedValue([provider()])
    vi.mocked(agentService.listModels).mockResolvedValue([model()])
    vi.mocked(agentService.listAgents).mockResolvedValue([agent()])
    vi.mocked(agentService.listTools).mockResolvedValue([])
    vi.mocked(agentService.listSkillPackages).mockResolvedValue([])
    vi.mocked(agentService.createProvider).mockResolvedValue(provider())
  })

  // 无修改时四条关闭入口都直接关闭，不出现确认弹窗。
  it('closes a pristine editor straight from every close path', async () => {
    const user = userEvent.setup()
    renderHarness()

    for (const path of ['escape', 'close-button', 'footer-cancel', 'backdrop'] as const) {
      const dialog = await openEditor(user, 'create-provider')
      await closeEditor(user, dialog, path)
      await expectEditorClosed()
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    }
  })

  // 继续编辑回到原弹窗：草稿与焦点都在编辑器内；再次关闭选择放弃才真正关闭且不提交。
  it('keeps an edited draft when the user continues editing and discards it on the second close', async () => {
    const user = userEvent.setup()
    renderHarness()
    const dialog = await openEditor(user, 'create-provider')
    await user.type(firstField(dialog), 'draft-provider')

    await user.keyboard('{Escape}')
    await screen.findByRole('alertdialog')
    expect(screen.getByTestId('editor-state')).toHaveTextContent('open')

    await user.click(keepEditing())
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    const resumed = editorDialog()
    expect(firstField(resumed)).toHaveValue('draft-provider')
    expect(resumed).toContainElement(document.activeElement as HTMLElement)

    await user.keyboard('{Escape}')
    await screen.findByRole('alertdialog')
    await user.click(discardChanges())
    await expectEditorClosed()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(agentService.createProvider).not.toHaveBeenCalled()
  })

  // 关闭按钮、遮罩与页脚取消同样先确认，选择放弃后关闭且不提交。
  it('asks before discarding from the close button, the backdrop and the footer cancel', async () => {
    const user = userEvent.setup()
    renderHarness()

    for (const path of ['close-button', 'backdrop', 'footer-cancel'] as const) {
      const dialog = await openEditor(user, 'create-provider')
      await user.type(firstField(dialog), 'draft-provider')
      await closeEditor(user, dialog, path)
      await screen.findByRole('alertdialog')
      await user.click(discardChanges())
      await expectEditorClosed()
    }
    expect(agentService.createProvider).not.toHaveBeenCalled()
  })

  // 基线是打开时的草稿：改回原值视为无修改，关闭不再询问。
  // 编辑态的 Provider 名称只读，因此用可编辑的 baseUrl 验证改回原值。
  it('closes without asking when an edited field is reverted to its opened value', async () => {
    const user = userEvent.setup()
    renderHarness()
    const dialog = await openEditor(user, 'edit-provider')
    const baseUrl = within(dialog).getByPlaceholderText('https://api.example.com/v1')
    expect(baseUrl).toHaveValue('http://stub.local/v1')

    await user.type(baseUrl, '-edited')
    expect(baseUrl).toHaveValue('http://stub.local/v1-edited')
    await user.clear(baseUrl)
    await user.type(baseUrl, 'http://stub.local/v1')

    await user.keyboard('{Escape}')
    await expectEditorClosed()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  // 保存成功由系统关闭弹窗：即便草稿有修改也不询问。
  it('closes after a successful save without asking', async () => {
    const user = userEvent.setup()
    renderHarness()
    const dialog = await openEditor(user, 'create-provider')
    await user.type(firstField(dialog), 'new-provider')

    await user.click(within(dialog).getByRole('button', { name: '创建' }))
    await waitFor(() => {
      expect(agentService.createProvider).toHaveBeenCalled()
    })
    await expectEditorClosed()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  // 确认弹窗是栈顶：Escape 只关确认弹窗，编辑器与草稿都保留。
  it('closes only the confirm dialog when Escape is pressed inside it', async () => {
    const user = userEvent.setup()
    renderHarness()
    const dialog = await openEditor(user, 'create-provider')
    await user.type(firstField(dialog), 'draft-provider')

    await user.keyboard('{Escape}')
    await screen.findByRole('alertdialog')
    await user.keyboard('{Escape}')

    await waitFor(() => {
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    })
    expect(screen.getByTestId('editor-state')).toHaveTextContent('open')
    expect(firstField(editorDialog())).toHaveValue('draft-provider')
  })

  // pending 的关闭门禁不变：保存进行中不关闭、也不弹确认。
  it('keeps the pending close gate without asking', async () => {
    const user = userEvent.setup()
    let resolveCreate: ((created: ProviderDTO) => void) | undefined
    vi.mocked(agentService.createProvider).mockImplementation(
      () =>
        new Promise<ProviderDTO>((resolve) => {
          resolveCreate = resolve
        }),
    )
    renderHarness()
    const dialog = await openEditor(user, 'create-provider')
    await user.type(firstField(dialog), 'new-provider')
    await user.click(within(dialog).getByRole('button', { name: '创建' }))
    await waitFor(() => {
      expect(within(editorDialog()).getByRole('button', { name: '创建' })).toBeDisabled()
    })

    await user.keyboard('{Escape}')
    await user.click(within(editorDialog()).getByRole('button', { name: '取消' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByTestId('editor-state')).toHaveTextContent('open')

    await act(async () => {
      resolveCreate?.(provider())
    })
    await expectEditorClosed()
  })

  // 分区表单同样按各自草稿确认关闭。
  it.each(['create-model', 'create-agent'] as const)(
    'asks before discarding a dirty %s editor',
    async (action) => {
      const user = userEvent.setup()
      renderHarness()
      const dialog = await openEditor(user, action)
      await user.type(firstField(dialog), 'x')

      await user.keyboard('{Escape}')
      await screen.findByRole('alertdialog')
      await user.click(discardChanges())
      await expectEditorClosed()
    },
  )

  // 切回模型页签也不能静默丢弃隐藏的模型组草稿。
  it('asks before discarding changes in the inactive model group tab', async () => {
    const user = userEvent.setup()
    renderHarness()
    const dialog = await openEditor(user, 'create-model')
    await user.click(within(dialog).getByRole('tab', { name: '模型组' }))
    await user.type(firstField(dialog), 'draft-group')
    await user.click(within(dialog).getByRole('tab', { name: '模型' }))
    await user.keyboard('{Escape}')
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
  })
})

function provider(): ProviderDTO {
  return {
    name: 'stub',
    description: 'Local deterministic provider',
    providerType: 'openai',
    promptCacheRetentions: ['NONE', 'SHORT', 'LONG'],
    baseUrl: 'http://stub.local/v1',
    credential: { configured: true },
    modelCallTimeoutMillis: '1800000',
    modelCallIdleTimeoutMillis: '120000',
    modelHttpRetryStatusCodes: null,
    version: '0',
    createdAt: 1781920800,
    updatedAt: 1781920800,
  }
}

function model(): SingleModelDTO {
  return {
    providerName: 'stub',
    type: 'MODEL',
    name: 'acceptance-stub',
    modelId: 'acceptance-wire-id',
    description: 'Acceptance model',
    config: {
      limit: { context: 128000, output: 8192 },
      abilities: {
        tools: true,
        reasoning: false,
        midConversationSystemMessages: false,
        messageLevelEffort: false,
        inputModalities: ['TEXT'],
      },
      pricing: {
        base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
        rules: [],
        changes: [],
      },
      defaultVariant: 'default',
      variants: [{ id: 'default', promptCacheRetention: 'NONE' }],
    },
    version: '0',
    createdAt: 1781920800,
    updatedAt: 1781920800,
  }
}

function agent(): AgentDTO {
  return {
    name: 'default-assistant',
    description: 'Cloud agent',
    systemPrompt: 'You are helpful',
    execution: { driver: 'builtin', settings: { modelName: 'acceptance-stub', variant: 'default' } },
    config: { tools: [], skills: [], subagents: [] },
    version: '0',
    createdAt: 1781920800,
    updatedAt: 1781920800,
  }
}
