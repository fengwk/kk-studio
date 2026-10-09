import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { AgentForm } from '@/features/ai/catalog/AiAgentResourceForm'
import { chooseSelectOption } from '@/test-support/chooseSelectOption'
import type { AgentDraft } from '@/features/ai/catalog/ai-console-types'
import {
  emptyAgentDraft,
  toEditableAgent,
  toEditableAgentUpdate,
} from '@/features/ai/catalog/ai-agent-draft-codec'
import type {
  AgentDefinitionDTO,
  AgentModelConfigDTO,
  AgentModelView,
  SkillPackageDTO,
} from '@/shared/api/contracts/ai-catalog'

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

function skillPackage(
  packageName: string,
  skills: { name: string; description: string }[],
): SkillPackageDTO {
  return {
    packageName,
    description: null,
    repositoryUrl: 'https://example.com/repo.git',
    branch: 'main',
    hasToken: false,
    currentCommit: '1111111111111111111111111111111111111111',
    observedHeadCommit: null,
    headCheckedAt: null,
    headCheckError: null,
    checkStatus: 'UP_TO_DATE',
    skills,
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T01:00:00.000Z',
  }
}

function agentDefinition(name: string, description: string | null): AgentDefinitionDTO {
  return {
    name,
    description,
    systemPrompt: null,
    type: 'USER',
    model: 'minimax/MiniMax',
    variant: null,
    config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

describe('AgentForm current contracts', () => {
  it('preserves multiline description and prompt through controlled edits and serialization', () => {
    let latest = { ...emptyAgentDraft(modelWithVariants()), name: 'assistant' }
    function Harness() {
      const [draft, setDraft] = useState(latest)
      return <AgentForm draft={draft} models={[modelWithVariants()]} onChange={(next) => {
        latest = next
        setDraft(next)
      }} />
    }
    render(<Harness />)
    fireEvent.change(screen.getByPlaceholderText('default-assistant'), { target: { value: 'edited-assistant' } })
    const description = screen.getByPlaceholderText('用途说明')
    const prompt = screen.getByPlaceholderText('系统提示词')
    fireEvent.change(description, { target: { value: '第一行\n第二行' } })
    fireEvent.change(prompt, { target: { value: '保留上下文\n  保留缩进' } })
    expect(description).toHaveValue('第一行\n第二行')
    expect(prompt).toHaveValue('保留上下文\n  保留缩进')
    expect(toEditableAgent(latest)).toMatchObject({
      name: 'edited-assistant',
      description: '第一行\n第二行',
      systemPrompt: '保留上下文\n  保留缩进',
    })
  })

  it('selects model/variant and selects skills as SkillRefDTO[]', async () => {
    const user = userEvent.setup()
    const longDescription =
      'Execute shell commands in the configured environment and return the captured output without losing long diagnostic context.'
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>({
        ...emptyAgentDraft(modelWithVariants()),
        tools: ['missing-tool'],
      })
      const skills = [skillPackage('core', [{ name: 'dev', description: 'dev skill' }])]
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          skills={skills}
          toolCatalog={[
            {
              name: 'bash',
              description: longDescription,
              environmentRequired: false,
              environmentId: null,
            },
            {
              name: 'lsp',
              description: 'lsp',
              environmentRequired: true,
              environmentId: null,
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
    expect(bashInput.closest('.capability-option')?.textContent).toContain('bash')
    await user.click(missingTool)
    expect(missingTool).not.toBeChecked()
    await user.click(bashInput)
    expect(bashInput).toBeChecked()

    // Skill candidate shows "core / dev"
    const devSkill = screen.getByLabelText(/core \/ dev/)
    expect(devSkill).not.toBeChecked()
    await user.click(devSkill)
    expect(devSkill).toBeChecked()
  })

  it('renders and toggles skills as exact {packageName, name} objects', async () => {
    const user = userEvent.setup()
    const drafts: AgentDraft[] = []
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>(emptyAgentDraft(modelWithVariants()))
      const skills = [skillPackage('core', [{ name: 'online-skill', description: 'global skill' }])]
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          skills={skills}
          onChange={(next) => {
            drafts.push(next)
            setDraft(next)
          }}
        />
      )
    }
    render(<Harness />)

    const skillCheckbox = screen.getByLabelText(/core \/ online-skill/)
    expect(skillCheckbox).not.toBeChecked()
    await user.click(skillCheckbox)
    expect(drafts.at(-1)?.skills).toEqual([{ packageName: 'core', name: 'online-skill' }])

    await user.click(skillCheckbox)
    expect(drafts.at(-1)?.skills).toEqual([])
  })

  it('preserves orphan skills as removable choices and serializes SkillRefDTO[]', async () => {
    const user = userEvent.setup()
    const drafts: AgentDraft[] = []
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>({
        ...emptyAgentDraft(modelWithVariants()),
        skills: [{ packageName: 'legacy', name: 'orphan-skill' }],
      })
      const skills = [skillPackage('core', [{ name: 'live-skill', description: 'live' }])]
      return (
        <AgentForm
          draft={draft}
          mode="edit"
          models={[modelWithVariants()]}
          skills={skills}
          onChange={(next) => {
            drafts.push(next)
            setDraft(next)
          }}
        />
      )
    }
    render(<Harness />)

    const orphanCheckbox = screen.getByLabelText(/legacy \/ orphan-skill/)
    expect(orphanCheckbox).toBeChecked()

    // Uncheck orphan skill
    await user.click(orphanCheckbox)
    expect(drafts.at(-1)?.skills).toEqual([])
  })

  it('allows selecting variant override or model default', async () => {
    const user = userEvent.setup()
    const drafts: AgentDraft[] = []
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>(emptyAgentDraft(modelWithVariants()))
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          onChange={(next) => {
            drafts.push(next)
            setDraft(next)
          }}
        />
      )
    }
    render(<Harness />)

    await chooseSelectOption(user, /Default Variant Override|模型变体覆盖/, 'fast')
    expect(drafts.at(-1)?.variant).toBe('fast')

    await chooseSelectOption(
      user,
      /Default Variant Override|模型变体覆盖/,
      /use model default|使用模型默认/i,
    )
    expect(drafts.at(-1)?.variant).toBe('')
  })

  it('selects and toggles subagents from available agent catalog', async () => {
    const user = userEvent.setup()
    const drafts: AgentDraft[] = []
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>(emptyAgentDraft(modelWithVariants()))
      const agents = [
        agentDefinition('helper', 'delegated helper'),
        agentDefinition('reviewer', 'code reviewer'),
      ]
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          agents={agents}
          onChange={(next) => {
            drafts.push(next)
            setDraft(next)
          }}
        />
      )
    }
    render(<Harness />)

    const helperCheckbox = screen.getByLabelText(/helper/)
    expect(helperCheckbox).not.toBeChecked()
    await user.click(helperCheckbox)
    expect(drafts.at(-1)?.subagents).toEqual(['helper'])

    const reviewerCheckbox = screen.getByLabelText(/reviewer/)
    await user.click(reviewerCheckbox)
    expect(drafts.at(-1)?.subagents).toEqual(['helper', 'reviewer'])
  })

  it('toggles inheritParentEnvironment cleanly', async () => {
    const user = userEvent.setup()
    const drafts: AgentDraft[] = []
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>(emptyAgentDraft(modelWithVariants()))
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          onChange={(next) => {
            drafts.push(next)
            setDraft(next)
          }}
        />
      )
    }
    render(<Harness />)

    const checkbox = screen.getByLabelText(/Inherit parent session|作为子 Agent 被委派时/)
    expect(checkbox).toBeChecked()
    await user.click(checkbox)
    expect(drafts.at(-1)?.inheritParentEnvironment).toBe(false)
  })

  it('submits clean payload with skills SkillRefDTO[] and no environmentId', () => {
    const draft: AgentDraft = {
      name: 'assistant',
      description: 'my description',
      systemPrompt: 'my prompt',
      model: 'minimax/MiniMax',
      variant: 'fast',
      inheritParentEnvironment: true,
      tools: ['bash'],
      skills: [
        { packageName: 'core', name: 'dev' },
        { packageName: 'core', name: 'search' },
      ],
      subagents: ['helper'],
    }

    const payload = toEditableAgent(draft)
    expect(payload).toEqual({
      name: 'assistant',
      description: 'my description',
      systemPrompt: 'my prompt',
      model: 'minimax/MiniMax',
      variant: 'fast',
      config: {
        inheritParentEnvironment: true,
        tools: ['bash'],
        skills: [
          { packageName: 'core', name: 'dev' },
          { packageName: 'core', name: 'search' },
        ],
        subagents: ['helper'],
      },
    })
    expect(payload).not.toHaveProperty('environmentId')
  })

  it('renders an explicit unconfigured model state and suppresses fillFirstModel for BUILTIN', async () => {
    const user = userEvent.setup()
    let currentDraft: AgentDraft = {
      ...emptyAgentDraft(),
      name: 'compaction',
      model: '',
    }
    function Harness() {
      const [draft, setDraft] = useState(currentDraft)
      return (
        <AgentForm
          mode="edit"
          agentType="BUILTIN"
          draft={draft}
          models={[modelWithVariants()]}
          onChange={(next) => {
            currentDraft = next
            setDraft(next)
          }}
        />
      )
    }
    render(<Harness />)

    // 1. 显式呈现未配置状态提示，aria-describedby 正确关联
    expect(
      screen.getByText('该内置 Agent 尚未配置 Model。选择 Model 后保存即可生效。'),
    ).toBeInTheDocument()
    const modelTrigger = screen.getByLabelText('Default Model')
    expect(modelTrigger).toHaveAttribute(
      'aria-describedby',
      'agent-model-unconfigured-status',
    )
    expect(modelTrigger).not.toHaveAttribute('aria-required')
    expect(modelTrigger).toHaveTextContent('未配置')

    // 2. BUILTIN 绝不呈现「使用第一个 Model 填充默认配置」快捷按钮（不自动用父/第一个 model）
    expect(
      screen.queryByRole('button', { name: '使用第一个 Model 填充默认配置' }),
    ).not.toBeInTheDocument()

    // 3. 用户可选定具体 Model 并更新草稿
    await chooseSelectOption(user, 'Default Model', 'minimax/MiniMax')
    expect(currentDraft.model).toBe('minimax/MiniMax')
    expect(modelTrigger).toHaveTextContent('minimax/MiniMax')

    // 4. 保存为 BUILTIN 更新 payload：model 包含所选值，且 payload 不含只读 type
    const payload = toEditableAgentUpdate(currentDraft, true)
    expect(payload.model).toBe('minimax/MiniMax')
    expect(payload).not.toHaveProperty('type')
  })

  it('shows fillFirstModel and marks model as required for USER agent drafts', async () => {
    const user = userEvent.setup()
    let currentDraft: AgentDraft = {
      ...emptyAgentDraft(),
      name: 'custom-assistant',
      model: '',
    }
    function Harness() {
      const [draft, setDraft] = useState(currentDraft)
      return (
        <AgentForm
          mode="create"
          agentType="USER"
          draft={draft}
          models={[modelWithVariants()]}
          onChange={(next) => {
            currentDraft = next
            setDraft(next)
          }}
        />
      )
    }
    render(<Harness />)

    // USER agent 未配置模型时标记为必填，并允许用户一键填充第一个 model
    const modelTrigger = screen.getByLabelText('Default Model')
    expect(modelTrigger).toHaveAttribute('aria-required', 'true')
    const fillButton = screen.getByRole('button', {
      name: '使用第一个 Model 填充默认配置',
    })
    expect(fillButton).toBeInTheDocument()

    await user.click(fillButton)
    expect(currentDraft.model).toBe('minimax/MiniMax')
  })
})
