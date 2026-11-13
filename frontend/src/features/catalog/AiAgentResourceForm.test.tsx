import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { AgentForm } from '@/features/catalog/AiAgentResourceForm'
import { chooseSelectOption } from '@/test-support/chooseSelectOption'
import type { AgentDraft } from '@/features/catalog/ai-console-types'
import {
  emptyAgentDraft,
  toEditableAgent,
  toEditableAgentUpdate,
} from '@/features/catalog/ai-agent-draft-codec'
import type {
  AgentDTO,
  ModelConfigDTO,
  ModelDTO,
  SkillPackageSummaryDTO,
} from '@/shared/api/generated/contracts'

const baseConfig: ModelConfigDTO = {
  limit: { context: 128000, output: 8192 },
  abilities: { tools: true, reasoning: false, midConversationSystemMessages: false, messageLevelEffort: false, inputModalities: ['TEXT'] },
  pricing: {
      base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
      overrides: [],
      multipliers: [],
    },
  defaultVariant: 'default',
  variants: [
    { id: 'default', promptCacheRetention: 'NONE' },
    { id: 'fast', promptCacheRetention: 'NONE' },
  ],
}

function modelWithVariants(): ModelDTO {
  return {
    providerName: 'minimax',
    type: 'MODEL',
    name: 'MiniMax',
    modelId: 'minimax-variants',
    description: null,
    config: baseConfig,
    version: '1',
    createdAt: 0,
    updatedAt: 0,
  }
}

function skillPackage(
  packageName: string,
  skills: { name: string; description: string }[],
): SkillPackageSummaryDTO {
  return {
    packageName,
    description: null,
    packageVersion: '1111111111111111111111111111111111111111',
    publishedAt: 1784505600000,
    skills,
    source: 'GIT',
    updateAvailable: false,
    version: '1',
    createdAt: 1784505600000,
    updatedAt: 1784509200000,
  }
}

function agent(name: string, description: string | null): AgentDTO {
  return {
    name,
    description,
    systemPrompt: null,
    execution: { driver: 'builtin', settings: { modelName: 'MiniMax', variant: null } },
    config: { tools: [], skills: [], subagents: [] },
    version: '1',
    createdAt: 0,
    updatedAt: 0,
  }
}

describe('AgentForm current contracts', () => {
  it('shows an unknown driver without model prompts and preserves its settings through edits', async () => {
    const user = userEvent.setup()
    const execution = { driver: 'future', settings: { nested: { token: 'opaque' } } }
    let latest: AgentDraft = { ...emptyAgentDraft(), name: 'assistant', execution }
    function Harness() {
      const [draft, setDraft] = useState(latest)
      return <AgentForm mode="edit" draft={draft} models={[modelWithVariants()]} onChange={(next) => {
        latest = next
        setDraft(next)
      }} />
    }
    render(<Harness />)
    // 非内置执行配置不应暴露模型校验或重置入口；普通编辑不改变不透明配置。
    expect(screen.getByLabelText('执行驱动')).toHaveTextContent('future')
    expect(screen.queryByLabelText('Default Model')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '使用第一个 Model 填充默认配置' })).not.toBeInTheDocument()
    await chooseSelectOption(user, '执行驱动', 'future (不可用)')
    fireEvent.change(screen.getByPlaceholderText('用途说明'), { target: { value: 'edited' } })
    expect(toEditableAgentUpdate(latest).execution).toEqual(execution)
    await chooseSelectOption(user, '执行驱动', 'builtin')
    expect(screen.getByLabelText('Default Model')).toBeInTheDocument()
    expect(latest.execution).toBeNull()
  })

  it('does not ask for a model when an unknown driver is configured and the catalog is empty', () => {
    // 独立于 catalog 是否为空，不支持的 driver 都不应显示 builtin 的配置引导。
    const draft = { ...emptyAgentDraft(), name: 'assistant', execution: { driver: 'future', settings: {} } }
    render(<AgentForm mode="edit" draft={draft} models={[]} onChange={() => undefined} />)
    expect(screen.queryByLabelText('Default Model')).not.toBeInTheDocument()
    expect(screen.queryByText('需要先创建 Model 才能配置 Agent。')).not.toBeInTheDocument()
  })

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
              provider: { kind: 'BUILTIN', id: 'builtin', name: 'Built-in' },
            },
            {
              name: 'lsp',
              description: 'lsp',
              environmentSupport: 'REQUIRED',
              provider: { kind: 'BUILTIN', id: 'builtin', name: 'Built-in' },
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
        skills: [{ packageName: 'orphan-pkg', name: 'orphan-skill' }],
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

    const orphanCheckbox = screen.getByLabelText(/orphan-pkg \/ orphan-skill/)
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

    await chooseSelectOption(user, 'Default Variant Override', 'fast')
    expect(drafts.at(-1)?.variant).toBe('fast')

    await chooseSelectOption(
      user,
      'Default Variant Override',
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
        agent('helper', 'delegated helper'),
        agent('reviewer', 'code reviewer'),
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

  it('submits clean payload with skills SkillRefDTO[] and no environmentId', () => {
    const draft: AgentDraft = {
      name: 'assistant',
      description: 'my description',
      systemPrompt: 'my prompt',
      model: 'MiniMax',
      variant: 'fast',
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
      execution: { driver: 'builtin', settings: { modelName: 'MiniMax', variant: 'fast' } },
      config: {
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

  it('marks the model as required and offers fillFirstModel', async () => {
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

    // 未配置模型时标记为必填，并允许用户一键填充第一个 model
    const modelTrigger = screen.getByLabelText('Default Model')
    expect(modelTrigger).toHaveAttribute('aria-required', 'true')
    const fillButton = screen.getByRole('button', {
      name: '使用第一个 Model 填充默认配置',
    })
    expect(fillButton).toBeInTheDocument()

    await user.click(fillButton)
    expect(currentDraft.model).toBe('MiniMax')
  })
})
