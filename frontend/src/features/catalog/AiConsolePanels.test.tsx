import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { AgentsPanel, ModelsPanel, ProvidersPanel } from '@/features/catalog/AiConsolePanels'
import { ChatCardsPanel } from '@/features/chat/ChatCardsPanel'
import type { ModelConfigDTO, ChatDTO, ModelDTO } from '@/shared/api/generated/contracts'

const baseConfig: ModelConfigDTO = {
  limit: { context: 128000, output: 8192 },
  abilities: { tools: true, reasoning: false, midConversationSystemMessages: false, messageLevelEffort: false, inputModalities: ['TEXT'] },
  pricing: {
      base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
      overrides: [],
      multipliers: [],
    },
  defaultVariant: 'default',
  variants: [{ id: 'default', promptCacheRetention: 'NONE' }],
}

function modelWithProvider(): ModelDTO {
  return {
    providerName: 'minimax',
    type: 'MODEL',
    name: 'MiniMax',
    modelId: 'minimax',
    description: null,
    config: baseConfig,
    version: '1',
    createdAt: 0,
    updatedAt: 0,
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
              createdAt: 0,
              updatedAt: 0,
            } satisfies ChatDTO,
          ]}
          agents={[]}
          onCreate={onCreate}
        />
        <AgentsPanel
          agents={[
            {
              name: 'assistant',
              description: 'd',
              systemPrompt: null,
              type: 'USER',
              execution: { driver: 'builtin', settings: { modelName: 'm1', variant: 'default' } },
              config: {
                tools: ['bash'],
                skills: [],
                subagents: [],
              },
              version: '1',
              createdAt: 0,
              updatedAt: 0,
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
              name: 'minimax',
              description: null,
              providerType: 'openai',
              promptCacheRetentions: ['NONE', 'SHORT', 'LONG'],
              baseUrl: null,
              credential: { configured: true },
              modelCallTimeoutMillis: '1',
              modelCallIdleTimeoutMillis: '1',
              version: '1',
              createdAt: 0,
              updatedAt: 0,
              modelHttpRetryStatusCodes: null,
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
    // Model 卡片使用全局唯一逻辑名。
    expect(screen.getAllByText('MiniMax').length).toBeGreaterThan(0)
    expect(screen.getByText(/ctx 128000/)).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: '新建 Chat' })[0]!)
    expect(onCreate).toHaveBeenCalled()
  })
})
