import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
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

/** 能力区默认收起：先展开对应面板再操作候选。 */
async function openCapabilityPanel(user: ReturnType<typeof userEvent.setup>, summary: string) {
  await user.click(screen.getByText(summary))
}

/** 以 legend 前缀定位单个能力面板，避免三个面板的提示互相串味。 */
function capabilityPanel(label: string): HTMLElement {
  return screen.getByRole('group', { name: new RegExp(`^${label}`) })
}

const baseConfig: ModelConfigDTO = {
  limit: { context: 128000, output: 8192 },
  abilities: { tools: true, reasoning: false, midConversationSystemMessages: false, messageLevelEffort: false, inputModalities: ['TEXT'] },
  pricing: {
      base: { input: '0', output: '0', cacheRead: '0', cacheWrite: '0', cacheWriteByTtl: {}, reasoning: null },
      rules: [],
      changes: []
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
    expect(screen.queryByLabelText('默认模型')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '使用第一个模型' })).not.toBeInTheDocument()
    await chooseSelectOption(user, '执行驱动', 'future (不可用)')
    fireEvent.change(screen.getByPlaceholderText('用途说明'), { target: { value: 'edited' } })
    expect(toEditableAgentUpdate(latest).execution).toEqual(execution)
    await chooseSelectOption(user, '执行驱动', 'builtin')
    expect(screen.getByLabelText('默认模型')).toBeInTheDocument()
    expect(latest.execution).toBeNull()
  })

  it('does not ask for a model when an unknown driver is configured and the catalog is empty', () => {
    // 独立于 catalog 是否为空，不支持的 driver 都不应显示 builtin 的配置引导。
    const draft = { ...emptyAgentDraft(), name: 'assistant', execution: { driver: 'future', settings: {} } }
    render(<AgentForm mode="edit" draft={draft} models={[]} onChange={() => undefined} />)
    expect(screen.queryByLabelText('默认模型')).not.toBeInTheDocument()
    expect(screen.queryByText('需要先创建模型才能配置 Agent。')).not.toBeInTheDocument()
  })

  // 测试意图：系统提示词只由区块标题表达一次，控件靠 aria-label 保留可访问名称，不重复可见标签。
  it('names the prompt field through the section heading without a duplicate label', () => {
    const draft = { ...emptyAgentDraft(modelWithVariants()), name: 'assistant' }
    render(<AgentForm draft={draft} models={[modelWithVariants()]} onChange={() => undefined} />)

    expect(screen.getAllByText('系统提示词')).toHaveLength(1)
    expect(screen.getByRole('textbox', { name: '系统提示词' })).toHaveValue('')
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
    await openCapabilityPanel(user, '管理技能')
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

    await openCapabilityPanel(user, '管理技能')
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

    await openCapabilityPanel(user, '管理技能')
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

    await chooseSelectOption(user, '模型预设覆盖', 'fast')
    expect(drafts.at(-1)?.variant).toBe('fast')

    await chooseSelectOption(user, '模型预设覆盖', '使用模型默认预设')
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

    await openCapabilityPanel(user, '管理子 Agent')
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
    const modelTrigger = screen.getByLabelText('默认模型')
    expect(modelTrigger).toHaveAttribute('aria-required', 'true')
    expect(modelTrigger.closest('.form-group')?.querySelector('.field-required')).toHaveAttribute(
      'aria-hidden',
      'true',
    )
    const fillButton = screen.getByRole('button', {
      name: '使用第一个模型',
    })
    expect(fillButton).toBeInTheDocument()

    await user.click(fillButton)
    expect(currentDraft.model).toBe('MiniMax')
  })

  // 三个能力目录各自独立：失败可重试，未读到时不能把已配置值当成不可用。
  it('reflects each capability directory status and forwards its retry', async () => {
    const user = userEvent.setup()
    const retryTools = vi.fn()
    const retrySkills = vi.fn()
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>({
        ...emptyAgentDraft(modelWithVariants()),
        tools: ['removed-tool'],
        skills: [{ packageName: 'gone', name: 'old-skill' }],
      })
      return (
        <AgentForm
          draft={draft}
          mode="edit"
          models={[modelWithVariants()]}
          capabilityDirectories={{
            tools: { status: 'error', retry: retryTools },
            skills: { status: 'loading', retry: retrySkills },
            subagents: { status: 'ready', retry: () => undefined },
          }}
          onChange={setDraft}
        />
      )
    }
    render(<Harness />)

    await openCapabilityPanel(user, '管理工具')
    const toolsPanel = capabilityPanel('工具')
    expect(within(toolsPanel).getByRole('alert')).toHaveTextContent('资源加载失败')
    expect(within(toolsPanel).getByRole('checkbox', { name: /removed-tool/ })).toBeChecked()
    expect(within(toolsPanel).queryByText('不可用')).not.toBeInTheDocument()
    await user.click(within(toolsPanel).getByRole('button', { name: '重试' }))
    expect(retryTools).toHaveBeenCalledTimes(1)

    await openCapabilityPanel(user, '管理技能')
    const skillsPanel = capabilityPanel('技能')
    expect(within(skillsPanel).getByRole('status')).toHaveTextContent('正在加载资源')
    expect(within(skillsPanel).getByRole('checkbox', { name: /gone \/ old-skill/ })).toBeChecked()
    expect(within(skillsPanel).queryByText('不可用')).not.toBeInTheDocument()
    expect(retrySkills).not.toHaveBeenCalled()

    // 只有读成功的空目录才给出「还没有候选」的结论。
    expect(within(capabilityPanel('子 Agent')).getByText('目录还没有子 Agent')).toBeInTheDocument()
  })
})
