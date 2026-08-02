import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { AgentsPanel, ModelsPanel, ProvidersPanel } from '@/features/ai/catalog/AiConsolePanels'
import type { AgentModelView } from '@/features/ai/catalog/AgentModelView'
import { ChatCardsPanel } from '@/features/ai/chat/ChatCardsPanel'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'

const baseConfig = {
  limit: { context: 128000, output: 8192 },
  abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] as const },
  pricing: {
    currency: 'USD',
    pricingTier: 'default',
    serviceTier: 'default',
    serviceTierMultiplier: 1,
    version: 'v1',
    inputPerMillionTokens: 0,
    outputPerMillionTokens: 0,
    cacheReadPerMillionTokens: 0,
    cacheWritePerMillionTokens: 0,
    cacheWriteLongPerMillionTokens: 0,
    reasoningPerMillionTokens: 0,
  },
  defaultVariant: 'default',
  variants: [{ id: 'default' }],
}

function modelWithProvider(): AgentModelView {
  return {
    providerName: 'minimax',
    name: 'MiniMax',
    description: null,
    config: baseConfig,
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

describe('AiConsolePanels', () => {
  it('renders chat/agent/model/provider cards and action hooks', async () => {
    const user = userEvent.setup()
    const onCreate = vi.fn()
    const onEdit = vi.fn()
    const onDelete = vi.fn()
    const model = modelWithProvider()
    render(
      <MemoryRouter>
        <ChatCardsPanel
          chats={[
            {
              id: 'c1',
              title: 'One',
              agentName: 'missing',
              environmentName: null,
              yoloEnabled: false,
              version: '1',
              createTime: null,
              updateTime: null,
            } satisfies ChatDTO,
          ]}
          agents={[]}
          onCreate={onCreate}
        />
        <AgentsPanel
          agents={[
            {
              id: 'a1',
              name: 'assistant',
              description: 'd',
              systemPrompt: null,
              model: 'm1',
              variant: 'default',
              config: {
                tools: ['bash'],
                skills: []
              },
              version: '1',
              createTime: null,
              updateTime: null,
            },
          ]}
          models={[model]}
          deletePending={false}
          onCreate={onCreate}
          onEdit={onEdit}
          onDelete={onDelete}
        />
        <ModelsPanel
          models={[model]}
          deletePending={false}
          onCreate={onCreate}
          onEdit={onEdit}
          onDelete={onDelete}
        />
        <ProvidersPanel
          providers={[
            {
              id: 'p1',
              name: 'minimax',
              description: null,
              providerType: 'openai',
              baseUrl: null,
              configured: true,
              modelCallTimeoutMillis: 1,
              modelCallIdleTimeoutMillis: 1,
              version: '1',
              createTime: null,
              updateTime: null,
            },
          ]}
          deletePending={false}
          onCreate={onCreate}
          onEdit={onEdit}
          onDelete={onDelete}
        />
      </MemoryRouter>,
    )
    expect(screen.getByText('One')).toBeInTheDocument()
    expect(screen.getByText('assistant')).toBeInTheDocument()
    // Model cards use the canonical provider/model display ref.
    expect(screen.getAllByText(/minimax\/MiniMax/i).length).toBeGreaterThan(0)
    expect(screen.getByText(/ctx 128000/)).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: '新建 Chat' })[0]!)
    expect(onCreate).toHaveBeenCalled()
  })
})