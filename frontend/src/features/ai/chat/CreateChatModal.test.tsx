import type { ComponentProps, ReactElement } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CreateChatModal } from '@/features/ai/chat/CreateChatModal'
import { chooseSelectOption } from '@/test-support/chooseSelectOption'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import { environmentService } from '@/shared/api/environment-service'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listEnvironments: vi.fn(),
  },
}))

const agentWithEnv: AgentDefinitionDTO = {
  name: 'assistant',
  model: {
    providerName: 'minimax',
    modelName: 'MiniMax',
    variant: 'default',
  },
  tools: [],
  skills: [],
  instruction: '',
  executionConfig: {
    environment: 'macos',
    inheritParentEnvironment: true,
    tools: [],
    skills: [],
    subagents: [],
  },
  version: '1',
  createTime: null,
  updateTime: null,
}

function createModalProps(
  overrides: Partial<ComponentProps<typeof CreateChatModal>> = {},
): ComponentProps<typeof CreateChatModal> {
  return {
    open: true,
    mode: 'create',
    agents: [agentWithEnv],
    selectedAgentName: '',
    title: '',
    yoloEnabled: false,
    selectedEnvironmentName: null,
    pending: false,
    formError: '',
    nameError: '',
    onClose: vi.fn(),
    onSelectAgent: vi.fn(),
    onTitleChange: vi.fn(),
    onYoloChange: vi.fn(),
    onSelectEnvironment: vi.fn(),
    onSubmit: vi.fn((event) => event.preventDefault()),
    ...overrides,
  }
}

function renderModal(ui: ReactElement, queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  return render(
    <QueryClientProvider client={queryClient}>
      {ui}
    </QueryClientProvider>,
  )
}

describe('CreateChatModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(environmentService.listEnvironments).mockResolvedValue([
      { name: 'macos' } as never,
      { name: 'ubuntu' } as never,
    ])
  })

  it('renders nothing when closed', () => {
    const { container } = renderModal(
      <CreateChatModal
        {...createModalProps({
          open: false,
          agents: [],
        })}
      />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('matches resource modal validation style with Name * and field errors', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn((event) => event.preventDefault())
    const onSelectAgent = vi.fn()
    const onTitleChange = vi.fn()
    renderModal(
      <CreateChatModal
        {...createModalProps({
          formError: '请填写 Chat 名称',
          nameError: '请填写名称',
          onSelectAgent,
          onTitleChange,
          onSubmit,
        })}
      />,
    )

    expect(screen.getByText('Name')).toBeInTheDocument()
    expect(screen.getAllByText('*')).toHaveLength(2)
    expect(screen.getByRole('alert')).toHaveTextContent('请填写 Chat 名称')
    expect(screen.getByText('请填写名称')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Chat 名称（可重名）').closest('label')).toHaveClass('is-error')

    await user.type(screen.getByPlaceholderText('Chat 名称（可重名）'), 'My Chat')
    expect(onTitleChange).toHaveBeenCalled()
    await chooseSelectOption(user, 'Agent', 'assistant')
    expect(onSelectAgent).toHaveBeenCalledWith('assistant')
    await user.click(screen.getByRole('button', { name: '确认创建' }))
    expect(onSubmit).toHaveBeenCalled()
  })

  // 验证编辑模式下共用表单布局与字段，展示编辑标题、保存按钮和取消动作，完整回显 YOLO 与环境
  it('renders edit mode with shared layout, edit title, save button, cancel action, and echoes values', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    const onSubmit = vi.fn((event) => event.preventDefault())
    const onTitleChange = vi.fn()
    const onYoloChange = vi.fn()
    const onSelectEnvironment = vi.fn()

    renderModal(
      <CreateChatModal
        {...createModalProps({
          mode: 'edit',
          selectedAgentName: 'assistant',
          title: 'Existing Chat',
          yoloEnabled: true,
          selectedEnvironmentName: 'ubuntu',
          onClose,
          onTitleChange,
          onYoloChange,
          onSelectEnvironment,
          onSubmit,
        })}
      />,
    )

    expect(screen.getByRole('form', { name: '编辑 Chat' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '编辑 Chat' })).toBeInTheDocument()
    expect(screen.getByDisplayValue('Existing Chat')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存修改' })).toBeInTheDocument()

    // 检查 YOLO switch 回显与切换
    const yoloSwitch = screen.getByRole('switch', { name: 'YOLO 模式' })
    expect(yoloSwitch).toHaveAttribute('aria-checked', 'true')
    await user.click(yoloSwitch)
    expect(onYoloChange).toHaveBeenCalledWith(false)

    // 检查环境选择回显及切换到“不选择环境”
    await chooseSelectOption(user, '默认环境', '不选择环境')
    expect(onSelectEnvironment).toHaveBeenCalledWith(null)

    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(onClose).toHaveBeenCalledOnce()

    await user.type(screen.getByDisplayValue('Existing Chat'), ' Updated')
    expect(onTitleChange).toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '保存修改' }))
    expect(onSubmit).toHaveBeenCalledOnce()
  })

  // 验证编辑已删除/不可用环境时保留其名字作为不可用选项，不静默替换或篡改
  it('preserves unavailable environment as an option in edit mode', async () => {
    renderModal(
      <CreateChatModal
        {...createModalProps({
          mode: 'edit',
          selectedAgentName: 'assistant',
          title: 'Existing Chat',
          selectedEnvironmentName: 'deleted-env',
        })}
      />,
    )

    await waitFor(() => {
      expect(screen.getByText('deleted-env （不可用）')).toBeInTheDocument()
    })
  })

  // 验证环境列表加载失败时显式提示错误，保留当前选中的环境，且不错误标记为不可用
  it('displays explicit error message when environment list fails to load and retains selection without claiming unavailable', async () => {
    vi.mocked(environmentService.listEnvironments).mockRejectedValueOnce(new Error('network error'))

    renderModal(
      <CreateChatModal
        {...createModalProps({
          selectedAgentName: 'assistant',
          title: 'Chat',
          selectedEnvironmentName: 'my-custom-env',
        })}
      />,
    )

    await waitFor(() => {
      expect(screen.getByText('环境列表加载失败，已保留当前选择')).toBeInTheDocument()
    })
    expect(screen.getByText('my-custom-env')).toBeInTheDocument()
    expect(screen.queryByText('my-custom-env （不可用）')).not.toBeInTheDocument()
  })

  // 验证环境列表初始加载中不提前宣称不可用，仅在确认成功且不含该项时才标记为不可用
  it('does not claim unavailable while environment list is initially loading and only labels unavailable after confirmed success', async () => {
    let resolvePromise: (envs: Array<{ name: string }>) => void
    const pendingPromise = new Promise<Array<{ name: string }>>((resolve) => {
      resolvePromise = resolve
    })
    vi.mocked(environmentService.listEnvironments).mockReturnValueOnce(pendingPromise as never)

    renderModal(
      <CreateChatModal
        {...createModalProps({
          selectedAgentName: 'assistant',
          title: 'Chat',
          selectedEnvironmentName: 'pending-env',
        })}
      />,
    )

    // 初始加载中：保留环境名称，不宣称不可用，展示加载指示
    expect(screen.getByText('pending-env')).toBeInTheDocument()
    expect(screen.queryByText('pending-env （不可用）')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('正在加载 Environment')

    // 列表成功返回且不含 pending-env：确认为不可用，方才标记 （不可用）
    await act(async () => {
      resolvePromise!([{ name: 'ubuntu' }])
    })

    await waitFor(() => {
      expect(screen.getByText('pending-env （不可用）')).toBeInTheDocument()
    })
  })
})
