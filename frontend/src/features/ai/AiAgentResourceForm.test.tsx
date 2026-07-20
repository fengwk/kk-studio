import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { AgentForm } from '@/features/ai/AiAgentResourceForm'
import type { AgentDraft } from '@/features/ai/ai-console-types'
import { emptyAgentDraft } from '@/features/ai/ai-agent-draft-codec'

describe('AgentForm current contracts', () => {
  it('selects model/variant/environment and marks invalid tools', async () => {
    const user = userEvent.setup()
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>({
        ...emptyAgentDraft({
          id: 'm1',
          providerId: 'p1',
          providerName: 'minimax',
          name: 'MiniMax',
          description: null,
          defaultVariant: 'default',
          variantsJson: '[{"name":"default"},{"name":"fast"}]',
          createTime: null,
          updateTime: null,
        }),
        tools: ['missing-tool'],
        environmentName: 'gone',
      })
      return (
        <AgentForm
          draft={draft}
          models={[
            {
              id: 'm1',
              providerId: 'p1',
              providerName: 'minimax',
              name: 'MiniMax',
              description: null,
              defaultVariant: 'default',
              variantsJson: '[{"name":"default"},{"name":"fast"}]',
              createTime: null,
              updateTime: null,
            },
          ]}
          agents={[{
            id: 'a2',
            name: 'researcher',
            description: null,
            systemPrompt: null,
            modelId: 'm1',
            variant: 'default',
            config: null,
            createTime: null,
            updateTime: null,
          }]}
          environments={[
            {
              name: 'platform',
              status: 'READY',
              lastSeen: null,
              tools: [{ name: 'bash', version: '1', description: 'shell' }],
              skills: [{ name: 'dev', description: 'dev' }],
            },
            {
              name: 'local',
              status: 'READY',
              lastSeen: null,
              tools: [{ name: 'lsp', version: '1', description: 'lsp' }],
              skills: [],
            },
          ]}
          onChange={setDraft}
        />
      )
    }
    render(<Harness />)
    expect(screen.getByText(/不在 live registry/)).toBeInTheDocument()
    expect(screen.getByText(/无效\/离线 Tools：missing-tool/)).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Environment'), 'local')
    await user.click(screen.getByLabelText(/bash/))
    await user.click(screen.getByLabelText(/researcher/))
    expect(screen.getByLabelText(/bash/)).toBeChecked()
    expect(screen.getByLabelText(/researcher/)).toBeChecked()
  })
})
