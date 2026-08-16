import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CreateChatModal } from '@/features/ai/chat/CreateChatModal'
import { environmentService } from '@/shared/api/environment-service'

vi.mock('@/shared/api/environment-service', () => ({
  environmentService: {
    listDirectories: vi.fn(),
  },
}))

const agent = {
  name: 'assistant',
  description: null,
  systemPrompt: null,
  model: 'minimax/MiniMax',
  variant: 'default',
  config: {
    tools: [],
    skills: [],
  },
  version: '1',
  createTime: null,
  updateTime: null,
}

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
        agents={[agent]}
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
    await user.selectOptions(screen.getByLabelText('Agent'), 'assistant')
    expect(onSelectAgent).toHaveBeenCalledWith('assistant')
    // 可选的默认 Environment（可空）由完整 Environment/Workspace picker 选择。
    expect(screen.getByRole('button', { name: 'Environment' })).toHaveTextContent('（无）')
    await user.click(screen.getByRole('button', { name: '确认创建' }))
    expect(onSubmit).toHaveBeenCalled()
  })

  it('selects a complete Environment binding instead of silently forcing root', async () => {
    const user = userEvent.setup()
    const onSelectEnvironment = vi.fn()
    vi.mocked(environmentService.listDirectories).mockResolvedValue({
      path: '.',
      displayPath: '.',
      parentPath: '.',
      truncated: false,
      gitBranch: 'main',
      entries: [],
    })
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    render(
      <QueryClientProvider client={queryClient}>
        <CreateChatModal
          open
          agents={[agent]}
          environments={[
            {
              name: 'dev',
              status: 'READY',
              ready: true,
              lastSeen: null,
              tools: [],
              skills: [],
              mcpServers: [],
            },
          ]}
          selectedAgentName="assistant"
          title="Chat"
          pending={false}
          onClose={() => undefined}
          onSelectAgent={() => undefined}
          onSelectEnvironment={onSelectEnvironment}
          onTitleChange={() => undefined}
          onSubmit={(event) => event.preventDefault()}
        />
      </QueryClientProvider>,
    )

    await user.click(screen.getByRole('button', { name: 'Environment' }))
    const environments = await screen.findByRole('region', { name: '选择 Environment' })
    await user.click(within(environments).getByRole('option', { name: 'dev' }))
    const directory = await screen.findByRole('region', { name: 'dev 目录' })
    await user.click(within(directory).getByRole('button', { name: /^使用当前 Workspace/ }))

    expect(onSelectEnvironment).toHaveBeenCalledWith({ name: 'dev', workspacePath: '.' })
  })
})
