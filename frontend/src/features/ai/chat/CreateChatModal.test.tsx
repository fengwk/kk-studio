import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CreateChatModal } from '@/features/ai/chat/CreateChatModal'
import { environmentService } from '@/shared/api/environment-service'
import { chooseSelectOption } from '@/test-support/chooseSelectOption'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listDirectories: vi.fn(),
  },
}))

const agentWithEnv: AgentDefinitionDTO = {
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  environmentId: 'env-dev-1',
  config: {
    toolIds: [],
    skills: [],
    subagents: [],
  },
  version: '1',
  createTime: null,
  updateTime: null,
}

const agentWithoutEnv: AgentDefinitionDTO = {
  ...agentWithEnv,
  name: 'no-env-assistant',
  environmentId: null,
}

const environments: EnvironmentCardDTO[] = [
  {
    id: 'env-dev-1',
    name: 'dev',
    rootPath: null,
    status: 'READY',
    ready: true,
    lastSeen: null,
    capabilities: [],
    skills: [],
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T00:00:00.000Z',
  },
]

describe('CreateChatModal', () => {
  it('renders nothing when closed', () => {
    const { container } = render(
      <CreateChatModal
        open={false}
        agents={[]}
        selectedAgentName=""
        title=""
        pending={false}
        onClose={() => undefined}
        onSelectAgent={() => undefined}
        onTitleChange={() => undefined}
        onSubmit={() => undefined}
      />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('matches resource modal validation style with Name * and field errors', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn((event) => event.preventDefault())
    const onSelectAgent = vi.fn()
    const onTitleChange = vi.fn()
    render(
      <CreateChatModal
        open
        agents={[agentWithEnv]}
        selectedAgentName=""
        title=""
        pending={false}
        formError="请填写 Chat 名称"
        nameError="请填写名称"
        onClose={() => undefined}
        onSelectAgent={onSelectAgent}
        onTitleChange={onTitleChange}
        onSubmit={onSubmit}
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

  it('selects workspacePath for agent with bound environment', async () => {
    const user = userEvent.setup()
    const onSelectWorkspacePath = vi.fn()
    vi.mocked(environmentService.listDirectories).mockResolvedValue({
      path: '.',
      displayPath: '.',
      parentPath: '.',
      truncated: false,
      gitBranch: 'main',
      entries: [{ name: 'src', path: 'src' }],
    })
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <QueryClientProvider client={queryClient}>
        <CreateChatModal
          open
          agents={[agentWithEnv]}
          environments={environments}
          selectedAgentName="assistant"
          title="Chat"
          pending={false}
          onClose={() => undefined}
          onSelectAgent={() => undefined}
          onSelectWorkspacePath={onSelectWorkspacePath}
          onTitleChange={() => undefined}
          onSubmit={(event) => event.preventDefault()}
        />
      </QueryClientProvider>,
    )

    await user.click(screen.getByRole('button', { name: '工作区路径' }))
    const directory = await screen.findByRole('region', { name: 'dev 目录' })
    await user.click(within(directory).getByRole('button', { name: /^使用当前/ }))

    expect(onSelectWorkspacePath).toHaveBeenCalledWith('.')
  })

  it('disables directory picker when agent has no environmentId', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <QueryClientProvider client={queryClient}>
        <CreateChatModal
          open
          agents={[agentWithoutEnv]}
          environments={environments}
          selectedAgentName="no-env-assistant"
          title="Chat"
          pending={false}
          onClose={() => undefined}
          onSelectAgent={() => undefined}
          onTitleChange={() => undefined}
          onSubmit={(event) => event.preventDefault()}
        />
      </QueryClientProvider>,
    )

    const envButton = screen.getByRole('button', { name: '工作区路径' })
    expect(envButton).toBeDisabled()
    expect(screen.getByText('当前 Agent 未绑定环境，无法选择工作区路径。')).toBeInTheDocument()
  })

  // 验证编辑模式下共用表单布局与字段，展示编辑标题、保存按钮和取消动作
  it('renders edit mode with shared layout, edit title, save button and cancel action', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    const onSubmit = vi.fn((event) => event.preventDefault())
    const onTitleChange = vi.fn()

    render(
      <CreateChatModal
        open
        mode="edit"
        agents={[agentWithEnv]}
        selectedAgentName="assistant"
        title="Existing Chat"
        pending={false}
        onClose={onClose}
        onSelectAgent={() => undefined}
        onTitleChange={onTitleChange}
        onSubmit={onSubmit}
      />,
    )

    expect(screen.getByRole('form', { name: '编辑 Chat' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '编辑 Chat' })).toBeInTheDocument()
    expect(screen.getByDisplayValue('Existing Chat')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存修改' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(onClose).toHaveBeenCalledOnce()

    await user.type(screen.getByDisplayValue('Existing Chat'), ' Updated')
    expect(onTitleChange).toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '保存修改' }))
    expect(onSubmit).toHaveBeenCalledOnce()
  })
})
