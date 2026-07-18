import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState, type ReactNode } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import {
  AgentResourceCard,
  CreateCard,
  ModelResourceCard,
  ProviderResourceCard,
  SearchField,
  ThreadCard,
  StateBlock,
} from '@/features/ai/AiConsoleCards'

describe('AiConsoleCards', () => {
  it('updates the search field', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<SearchFieldHarness onChange={onChange} />)

    await user.type(screen.getByPlaceholderText('搜索资源...'), 'mini')
    expect(onChange).toHaveBeenLastCalledWith('mini')
  })

  it('renders create card with aria label and click handler', async () => {
    const user = userEvent.setup()
    const onClick = vi.fn()
    render(<CreateCard title="新建 Provider" subtitle="配置连接地址、凭据与超时" onClick={onClick} />)

    await user.click(screen.getByRole('button', { name: '新建 Provider' }))
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('renders thread fallback fields and navigates to the thread route', async () => {
    const user = userEvent.setup()
    renderWithRouter(
      <ThreadCard
        thread={{
          threadId: 'thread-1',
          sessionId: 'session-1',
          sessionTitle: null,
          headEntryId: 'entry-1',
          agentDefinitionId: 'agent-1',
          runtimeConfigJson: null,
          yoloEnabled: false,
          inputSequence: 0,
          processing: false,
          createTime: '2026-06-20T02:00:00',
          updateTime: '2026-06-20T02:01:00',
        }}
      />,
    )

    expect(screen.getByText('Untitled Chat')).toBeInTheDocument()
    expect(screen.getAllByText('agent-1').length).toBeGreaterThan(0)

    await user.click(screen.getByRole('button', { name: '进入对话 thread-1' }))
    expect(screen.getByTestId('location')).toHaveTextContent('/threads/thread-1')
  })

  it('uses the resolved agent name for the thread card', () => {
    renderWithRouter(
      <ThreadCard
        thread={{
          threadId: 'thread-2',
          sessionId: 'session-2',
          sessionTitle: 'Named Session',
          headEntryId: 'entry-2',
          agentDefinitionId: 'agent-1',
          runtimeConfigJson: null,
          yoloEnabled: false,
          inputSequence: 0,
          processing: false,
          createTime: '2026-06-20T02:00:00',
          updateTime: '2026-06-20T02:01:00',
        }}
        agent={{
          id: 'agent-1',
          name: 'preferred-agent',
          description: 'Cloud agent',
          systemPrompt: 'You are helpful',
          defaultProviderId: 'provider-1',
          defaultProviderName: 'minimax',
          defaultModelId: 'model-1',
          defaultModelName: 'MiniMax-M2.7',
          defaultVariant: 'default',
          toolsJson: '[]',
          createTime: '2026-06-20T02:00:00',
          updateTime: '2026-06-20T02:00:00',
        }}
      />,
    )

    expect(screen.getAllByText('preferred-agent')).toHaveLength(2)
  })

  it('renders agent card fallbacks and start action', async () => {
    const user = userEvent.setup()
    const onStart = vi.fn()
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    render(
      <>
        <AgentResourceCard
          agent={{
            id: 'agent-1',
            name: 'agent-system-prompt',
            description: null,
            systemPrompt: 'Prompt fallback',
            defaultProviderId: 'provider-1',
            defaultProviderName: 'minimax',
            defaultModelId: 'model-1',
            defaultModelName: 'MiniMax-M2.7',
            defaultVariant: 'default',
            toolsJson: '[]',
            createTime: '2026-06-20T02:00:00',
            updateTime: '2026-06-20T02:00:00',
          }}
          onStart={onStart}
          onEdit={onEdit}
          onDelete={onDelete}
          deletePending={false}
        />
        <AgentResourceCard
          agent={{
            id: 'agent-2',
            name: 'agent-name-fallback',
            description: null,
            systemPrompt: null,
            defaultProviderId: 'provider-1',
            defaultProviderName: 'minimax',
            defaultModelId: 'model-1',
            defaultModelName: 'MiniMax-M2.7',
            defaultVariant: null,
            toolsJson: '[]',
            createTime: '2026-06-20T02:00:00',
            updateTime: '2026-06-20T02:00:00',
          }}
          onStart={() => undefined}
          onEdit={() => undefined}
          onDelete={() => undefined}
          deletePending={false}
        />
      </>,
    )

    expect(screen.getByText('Prompt fallback')).toBeInTheDocument()
    expect(screen.getAllByText('agent-name-fallback').length).toBeGreaterThan(0)

    await user.click(screen.getByRole('button', { name: '创建会话 agent-system-prompt' }))
    await user.click(screen.getByRole('button', { name: '编辑 agent-system-prompt' }))
    await user.click(screen.getByRole('button', { name: '删除 agent-system-prompt' }))
    expect(onStart).toHaveBeenCalledTimes(1)
    expect(onEdit).toHaveBeenCalledTimes(1)
    expect(onDelete).toHaveBeenCalledTimes(1)
  })

  it('renders model card description fallback and actions', async () => {
    const user = userEvent.setup()
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    render(
      <ModelResourceCard
        model={{
          id: 'model-1',
          providerId: 'provider-1',
          providerName: 'minimax',
          name: 'MiniMax-M2.7',
          description: null,
          defaultVariant: null,
          variantsJson: '[{"name":"default"}]',
          createTime: '2026-06-20T02:00:00',
          updateTime: '2026-06-20T02:00:00',
        }}
        onEdit={onEdit}
        onDelete={onDelete}
        deletePending={false}
      />,
    )

    expect(screen.getByText('minimax/MiniMax-M2.7')).toBeInTheDocument()
    expect(screen.getByText('1 item')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '编辑 MiniMax-M2.7' }))
    await user.click(screen.getByRole('button', { name: '删除 MiniMax-M2.7' }))
    expect(onEdit).toHaveBeenCalledTimes(1)
    expect(onDelete).toHaveBeenCalledTimes(1)
  })

  it('renders provider fallbacks and pending state', async () => {
    const user = userEvent.setup()
    const onEdit = vi.fn()
    render(
      <ProviderResourceCard
        provider={{
          id: 'provider-1',
          name: 'minimax',
          description: null,
          providerType: 'openai',
          baseUrl: null,
          apiKey: null,
          timeoutMillis: null,
          createTime: '2026-06-20T02:00:00',
          updateTime: '2026-06-20T02:00:00',
        }}
        onEdit={onEdit}
        onDelete={() => undefined}
        deletePending
      />,
    )

    expect(screen.getAllByText('openai').length).toBeGreaterThan(0)
    expect(screen.getAllByText('-').length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: '删除 minimax' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: '编辑 minimax' }))
    expect(onEdit).toHaveBeenCalledTimes(1)
  })

  it('renders state blocks in default and danger tone', () => {
    const { rerender } = render(<StateBlock title="正在加载资源" />)
    expect(screen.getByText('正在加载资源')).not.toHaveClass('danger')

    rerender(<StateBlock title="资源加载失败" tone="danger" />)
    expect(screen.getByText('资源加载失败')).toHaveClass('danger')
  })
})

function renderWithRouter(element: ReactNode) {
  return render(
    <MemoryRouter initialEntries={['/threads']}>
      <Routes>
        <Route path="*" element={<>{element}<LocationProbe /></>} />
      </Routes>
    </MemoryRouter>,
  )
}

function LocationProbe() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname}</div>
}

function SearchFieldHarness({ onChange }: { onChange: (value: string) => void }) {
  const [value, setValue] = useState('')

  return (
    <SearchField
      value={value}
      onChange={(nextValue) => {
        setValue(nextValue)
        onChange(nextValue)
      }}
    />
  )
}
