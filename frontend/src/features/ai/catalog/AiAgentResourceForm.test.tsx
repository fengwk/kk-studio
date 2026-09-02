import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { AgentForm } from '@/features/ai/catalog/AiAgentResourceForm'
import { chooseSelectOption } from '@/test-support/chooseSelectOption'
import type { AgentDraft } from '@/features/ai/catalog/ai-console-types'
import { emptyAgentDraft } from '@/features/ai/catalog/ai-agent-draft-codec'
import type {
  AgentDefinitionDTO,
  AgentModelConfigDTO,
  AgentModelView,
} from '@/shared/api/contracts/ai-catalog'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'

const baseConfig: AgentModelConfigDTO = {
  limit: { context: 128000, output: 8192 },
  abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
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
  variants: [{ id: 'default' }, { id: 'fast' }],
}

function modelWithVariants(): AgentModelView {
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

function environmentCard(
  id: string,
  name: string,
  skills: { name: string; description: string | null }[],
  extra?: Partial<EnvironmentCardDTO>,
): EnvironmentCardDTO {
  return {
    id,
    name,
    rootPath: null,
    status: 'READY',
    ready: true,
    lastSeen: null,
    capabilities: [],
    skills,
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T00:00:00.000Z',
    ...extra,
  }
}

function agentDefinition(name: string, description: string | null): AgentDefinitionDTO {
  return {
    name,
    description,
    systemPrompt: null,
    model: 'minimax/MiniMax',
    variant: null,
    environmentId: null,
    config: { toolIds: [], skills: [], subagents: [] },
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

const ENV_LABEL = '绑定环境'

describe('AgentForm current contracts', () => {
  it('selects model/variant and environment UUID, and skills come from selected environment card', async () => {
    const user = userEvent.setup()
    const longDescription =
      'Execute shell commands in the configured environment and return the captured output without losing long diagnostic context.'
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>({
        ...emptyAgentDraft(modelWithVariants()),
        toolIds: ['missing-tool'],
      })
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          environments={[
            environmentCard('env-1', 'local', [{ name: 'dev', description: 'dev' }]),
          ]}
          toolCatalog={[
            {
              id: 'base.bash',
              name: 'bash',
              version: '1',
              description: longDescription,
            },
            {
              id: 'environment.lsp',
              name: 'lsp',
              version: '1',
              description: 'lsp',
            },
          ]}
          onChange={setDraft}
        />
      )
    }
    render(<Harness />)
    const missingTool = screen.getByLabelText(/missing-tool/)
    expect(missingTool).toBeChecked()
    const bashInput = screen.getByLabelText(/bash/)
    expect(bashInput).toHaveAttribute('value', 'base.bash')
    await user.click(missingTool)
    expect(missingTool).not.toBeChecked()
    await user.click(bashInput)
    expect(bashInput).toBeChecked()

    // 未绑定环境时没有任何 live skill 候选
    expect(screen.queryByLabelText(/dev/)).not.toBeInTheDocument()
    expect(screen.getByText('暂无候选 Skills')).toBeInTheDocument()

    // 选择绑定环境后，技能候选来自该卡片
    await chooseSelectOption(user, ENV_LABEL, 'local')
    expect(screen.getByLabelText(/dev/)).not.toBeChecked()
    await user.click(screen.getByLabelText(/dev/))
    expect(screen.getByLabelText(/dev/)).toBeChecked()
  })

  it('renders field-level errors for tools and skills', () => {
    render(
      <AgentForm
        draft={emptyAgentDraft(modelWithVariants())}
        models={[modelWithVariants()]}
        fieldErrors={{
          variant: '请选择 Variant',
          toolIds: 'Tools 冲突',
          skills: 'Skills 冲突',
        }}
        onChange={() => undefined}
      />,
    )

    expect(screen.getByText('请选择 Variant')).toBeInTheDocument()
    expect(screen.getByText('Tools 冲突')).toBeInTheDocument()
    expect(screen.getByText('Skills 冲突')).toBeInTheDocument()
    expect(screen.getByLabelText(ENV_LABEL)).toBeInTheDocument()
  })

  it('keeps selected skills as removable orphans when no environment is selected or skill is missing', async () => {
    const user = userEvent.setup()
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>({
        ...emptyAgentDraft(modelWithVariants()),
        skills: ['ghost-skill'],
      })
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          environments={[environmentCard('env-1', 'local', [{ name: 'dev', description: 'dev' }])]}
          onChange={setDraft}
        />
      )
    }
    render(<Harness />)

    expect(screen.getByLabelText(ENV_LABEL)).toHaveAttribute('data-value', '')
    expect(screen.queryByLabelText(/dev/)).not.toBeInTheDocument()
    const ghost = screen.getByLabelText(/ghost-skill/)
    expect(ghost).toBeChecked()
    expect(screen.getByText('不可用')).toBeInTheDocument()
    await user.click(ghost)
    expect(ghost).not.toBeChecked()
  })

  it('switches environment selection and updates skill candidates without losing selected names', async () => {
    const user = userEvent.setup()
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>(emptyAgentDraft(modelWithVariants()))
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          environments={[
            environmentCard('env-1', 'local', [
              { name: 'dev', description: 'dev skill' },
              { name: 'ops', description: 'ops skill' },
            ]),
            environmentCard('env-2', 'remote', [
              { name: 'dev', description: 'duplicate' },
              { name: 'docs', description: null },
            ]),
          ]}
          onChange={setDraft}
        />
      )
    }
    render(<Harness />)
    await chooseSelectOption(user, ENV_LABEL, 'local')
    await user.click(screen.getByLabelText(/dev/))
    await user.click(screen.getByLabelText(/ops/))

    // 切换到 remote 环境
    await chooseSelectOption(user, ENV_LABEL, 'remote')
    expect(screen.getByLabelText(/dev/)).toBeChecked()
    expect(screen.getByLabelText(/ops/)).toBeChecked()
    expect(screen.getByLabelText(/docs/)).not.toBeChecked()
  })

  it('handles subagents candidates and excludes self in create mode', () => {
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>({
        ...emptyAgentDraft(modelWithVariants()),
        name: 'my-agent',
        subagents: ['orphan-agent'],
      })
      return (
        <AgentForm
          draft={draft}
          mode="create"
          models={[modelWithVariants()]}
          agents={[
            agentDefinition('my-agent', 'Self'),
            agentDefinition('helper-agent', 'Helper'),
          ]}
          onChange={setDraft}
        />
      )
    }
    render(<Harness />)
    expect(screen.queryByLabelText(/^my-agent/)).not.toBeInTheDocument()
    expect(screen.getByLabelText(/helper-agent/)).toBeInTheDocument()
    expect(screen.getByLabelText(/orphan-agent/)).toBeChecked()
  })
})
