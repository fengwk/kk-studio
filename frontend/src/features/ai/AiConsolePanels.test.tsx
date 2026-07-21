import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AgentsPanel, ChatCardsPanel, ModelsPanel, ProvidersPanel } from '@/features/ai/AiConsolePanels'
import type { AgentModelView } from '@/features/ai/AgentModelView'

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
    id: 'm1',
    providerId: 'p1',
    providerName: 'minimax',
    name: 'MiniMax',
    description: null,
    config: baseConfig,
    version: 1,
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
              defaultAgentId: null,
              version: 1,
              createTime: null,
              updateTime: null,
            },
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
              modelId: 'm1',
              variant: 'default',
              config: {
                environmentName: 'local',
                tools: ['bash'],
                skills: [],
                allowedSubagents: [],
                executionPolicy: {},
              },
              version: 1,
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
              version: 1,
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
    expect(screen.getAllByText('MiniMax').length).toBeGreaterThan(0)
    expect(screen.getAllByText(/minimax/i).length).toBeGreaterThan(0)
    expect(screen.getByText(/ctx 128000/)).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: '新建 Chat' })[0]!)
    expect(onCreate).toHaveBeenCalled()
  })
})