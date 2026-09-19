import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { AgentForm } from '@/features/ai/catalog/AiAgentResourceForm'
import { chooseSelectOption } from '@/test-support/chooseSelectOption'
import type { AgentDraft } from '@/features/ai/catalog/ai-console-types'
import { emptyAgentDraft, toEditableAgent } from '@/features/ai/catalog/ai-agent-draft-codec'
import type {
  AgentDefinitionDTO,
  AgentModelConfigDTO,
  AgentModelView,
} from '@/shared/api/contracts/ai-catalog'
import type { EnvironmentCardDTO, EnvironmentSkillDTO } from '@/shared/api/contracts/ai-environment'

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
  extra?: Partial<EnvironmentCardDTO>,
): EnvironmentCardDTO {
  const intermediate = {
    id,
    name,
    rootPath: null,
    status: 'READY',
    ready: true,
    lastSeen: null,
    capabilities: [],
    skills: [],
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T00:00:00.000Z',
    ...extra,
  }
  return intermediate as EnvironmentCardDTO
}

function inventorySkill(
  sourceId: string,
  name: string,
  description: string | null = null,
): EnvironmentSkillDTO {
  return {
    sourceId,
    name,
    description: description ?? '',
    sourceVersion: '1',
    baseDirectory: '/tmp',
    contentRevision: 'sha256-abc',
    discoveredAt: '2026-07-20T00:00:00.000Z',
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
    config: { tools: [], skills: [], subagents: [] },
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

const ENV_LABEL = '绑定环境'

describe('AgentForm current contracts', () => {
  it('selects model/variant and environment UUID, and skills come from durable usable inventory', async () => {
    const user = userEvent.setup()
    const longDescription =
      'Execute shell commands in the configured environment and return the captured output without losing long diagnostic context.'
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>({
        ...emptyAgentDraft(modelWithVariants()),
        tools: ['missing-tool'],
      })
      const inventory = [
        inventorySkill('src-1', 'dev', 'dev skill'),
      ]
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          environments={[
            environmentCard('env-1', 'local'),
          ]}
          inventorySkills={draft.environmentId ? inventory : []}
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
    expect(bashInput).toHaveAttribute('value', 'bash')
    await user.click(missingTool)
    expect(missingTool).not.toBeChecked()
    await user.click(bashInput)
    expect(bashInput).toBeChecked()

    // 未绑定环境时没有任何 durable inventory skill 候选
    expect(screen.queryByLabelText(/dev/)).not.toBeInTheDocument()
    expect(screen.getByText('暂无候选 Skills')).toBeInTheDocument()

    // 选择绑定环境后，技能候选来自 durable inventory
    await chooseSelectOption(user, ENV_LABEL, 'local')
    expect(screen.getByLabelText(/dev/)).not.toBeChecked()
    await user.click(screen.getByLabelText(/dev/))
    expect(screen.getByLabelText(/dev/)).toBeChecked()
  })

  it('allows selecting bound offline environment and its persisted usable skills', async () => {
    const user = userEvent.setup()
    const drafts: AgentDraft[] = []
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>(emptyAgentDraft(modelWithVariants()))
      const offlineEnv = environmentCard('env-offline', 'offline-box', {
        status: 'OFFLINE',
        ready: false,
      })
      const inventory = [
        inventorySkill('src-off', 'offline-skill', 'persisted offline skill'),
      ]
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          environments={[offlineEnv]}
          inventorySkills={draft.environmentId === 'env-offline' ? inventory : []}
          onChange={(next) => {
            drafts.push(next)
            setDraft(next)
          }}
        />
      )
    }
    render(<Harness />)
    // Offline environment option is marked offline in the label and can be selected
    await chooseSelectOption(user, ENV_LABEL, 'offline-box (offline)')
    expect(drafts.at(-1)?.environmentId).toBe('env-offline')

    // Persisted inventory skill is selectable while environment is offline
    const skillCheckbox = screen.getByLabelText(/offline-skill/)
    expect(skillCheckbox).not.toBeChecked()
    await user.click(skillCheckbox)
    expect(drafts.at(-1)?.skills).toEqual([{ sourceId: 'src-off', name: 'offline-skill' }])
  })

  it('does not collapse same-name skills from different sourceIds', async () => {
    const user = userEvent.setup()
    const drafts: AgentDraft[] = []
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>({
        ...emptyAgentDraft(modelWithVariants()),
        environmentId: 'env-1',
      })
      const inventory = [
        inventorySkill('src-1-uuid-1234', 'search', 'first search skill'),
        inventorySkill('src-2-uuid-5678', 'search', 'second search skill'),
      ]
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          environments={[environmentCard('env-1', 'local')]}
          inventorySkills={inventory}
          onChange={(next) => {
            drafts.push(next)
            setDraft(next)
          }}
        />
      )
    }
    render(<Harness />)
    const checkboxes = screen.getAllByRole('checkbox', { name: /search/ })
    expect(checkboxes).toHaveLength(2)

    // Check both skills with the same name
    await user.click(checkboxes[0])
    await user.click(checkboxes[1])

    expect(drafts.at(-1)?.skills).toEqual([
      { sourceId: 'src-1-uuid-1234', name: 'search' },
      { sourceId: 'src-2-uuid-5678', name: 'search' },
    ])
  })

  it('renders field-level errors for tools and skills', () => {
    render(
      <AgentForm
        draft={emptyAgentDraft(modelWithVariants())}
        models={[modelWithVariants()]}
        fieldErrors={{
          variant: '请选择 Variant',
          tools: 'Tools 冲突',
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
        skills: [{ sourceId: 'src-ghost', name: 'ghost-skill' }],
      })
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          environments={[environmentCard('env-1', 'local')]}
          inventorySkills={[]}
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

  it('clears skills on environment change or unbind, but preserves refs on same ID rerender', async () => {
    const user = userEvent.setup()
    const drafts: AgentDraft[] = []
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>(emptyAgentDraft(modelWithVariants()))
      const inventoryA = [inventorySkill('src-1', 'dev', 'dev skill')]
      const inventoryB = [inventorySkill('src-2', 'ops', 'ops skill')]
      const currentInventory =
        draft.environmentId === 'env-1'
          ? inventoryA
          : draft.environmentId === 'env-2'
            ? inventoryB
            : []
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          environments={[
            environmentCard('env-1', 'local'),
            environmentCard('env-2', 'remote'),
          ]}
          inventorySkills={currentInventory}
          onChange={(next) => {
            drafts.push(next)
            setDraft(next)
          }}
        />
      )
    }
    render(<Harness />)

    // Select env-1
    await chooseSelectOption(user, ENV_LABEL, 'local')
    expect(drafts.at(-1)?.environmentId).toBe('env-1')

    // Check dev
    await user.click(screen.getByLabelText(/dev/))
    expect(drafts.at(-1)?.skills).toEqual([{ sourceId: 'src-1', name: 'dev' }])

    // Switch to env-2 -> skills cleared explicitly
    await chooseSelectOption(user, ENV_LABEL, 'remote')
    expect(drafts.at(-1)?.environmentId).toBe('env-2')
    expect(drafts.at(-1)?.skills).toEqual([])

    // Select ops on env-2
    await user.click(screen.getByLabelText(/ops/))
    expect(drafts.at(-1)?.skills).toEqual([{ sourceId: 'src-2', name: 'ops' }])

    // Unbind environment -> skills cleared explicitly
    await chooseSelectOption(user, ENV_LABEL, '（无）')
    expect(drafts.at(-1)?.environmentId).toBe('')
    expect(drafts.at(-1)?.skills).toEqual([])
  })

  it('renders loading and error states while keeping saved selected refs visible as removable orphans', async () => {
    const savedSkill = { sourceId: 'src-saved', name: 'saved-skill' }

    // Loading state with saved refs
    const { rerender } = render(
      <AgentForm
        draft={{
          ...emptyAgentDraft(modelWithVariants()),
          environmentId: 'env-1',
          skills: [savedSkill],
        }}
        models={[modelWithVariants()]}
        environments={[environmentCard('env-1', 'local')]}
        inventorySkillsLoading={true}
        onChange={() => undefined}
      />,
    )
    expect(screen.getByText('正在加载资源')).toBeInTheDocument()
    const savedCheckbox = screen.getByLabelText(/saved-skill/)
    expect(savedCheckbox).toBeChecked()
    expect(screen.getByText('不可用')).toBeInTheDocument()

    // Loading state with NO saved refs
    rerender(
      <AgentForm
        draft={{
          ...emptyAgentDraft(modelWithVariants()),
          environmentId: 'env-1',
          skills: [],
        }}
        models={[modelWithVariants()]}
        environments={[environmentCard('env-1', 'local')]}
        inventorySkillsLoading={true}
        onChange={() => undefined}
      />,
    )
    expect(screen.getByText('正在加载资源')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: /saved-skill/ })).not.toBeInTheDocument()

    // Error state with saved refs
    rerender(
      <AgentForm
        draft={{
          ...emptyAgentDraft(modelWithVariants()),
          environmentId: 'env-1',
          skills: [savedSkill],
        }}
        models={[modelWithVariants()]}
        environments={[environmentCard('env-1', 'local')]}
        inventorySkillsError={new Error('Failed to load')}
        onChange={() => undefined}
      />,
    )
    expect(screen.getByText('加载失败')).toBeInTheDocument()
    expect(screen.getByLabelText(/saved-skill/)).toBeChecked()

    // Error state with NO saved refs
    rerender(
      <AgentForm
        draft={{
          ...emptyAgentDraft(modelWithVariants()),
          environmentId: 'env-1',
          skills: [],
        }}
        models={[modelWithVariants()]}
        environments={[environmentCard('env-1', 'local')]}
        inventorySkillsError={new Error('Failed to load')}
        onChange={() => undefined}
      />,
    )
    expect(screen.getByText('加载失败')).toBeInTheDocument()
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

  it('keeps embedded newlines from the description textarea in the draft', async () => {
    const user = userEvent.setup()
    const drafts: AgentDraft[] = []
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>({
        ...emptyAgentDraft(modelWithVariants()),
        name: 'multiline-agent',
      })
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

    const description = screen.getByLabelText('Description')
    expect(description.tagName).toBe('TEXTAREA')

    await user.type(description, '执行环境内的 shell 命令{enter}并返回捕获的输出')

    // 多行描述是合法内容：draft 与提交 payload 都不得折叠内部换行。
    expect(description).toHaveValue('执行环境内的 shell 命令\n并返回捕获的输出')
    expect(drafts.at(-1)?.description).toBe('执行环境内的 shell 命令\n并返回捕获的输出')
    expect(toEditableAgent(drafts.at(-1)!).description).toBe(
      '执行环境内的 shell 命令\n并返回捕获的输出',
    )
  })

  /**
   * 测试意图：验证 toolCatalog 中所有工具全量展示并可选（无视环境绑定状态），
   * 且切换或解绑环境时绝对不清空 draft.tools（而 skills 仍按旧规则清空）。
   */
  it('displays full tool catalog regardless of environment and preserves draft tools on environment change or unbind', async () => {
    const user = userEvent.setup()
    const drafts: AgentDraft[] = []

    const tools: ToolCatalogEntryDTO[] = [
      {
        name: 'bash',
        description: 'host shell tool',
        environmentRequired: false,
        environmentId: null,
      },
      {
        name: 'generic-tool',
        description: 'generic environment tool',
        environmentRequired: true,
        environmentId: null,
      },
      {
        name: 'exact-a-tool',
        description: 'exact environment A tool',
        environmentRequired: true,
        environmentId: 'env-a',
      },
      {
        name: 'exact-b-tool',
        description: 'exact environment B tool',
        environmentRequired: true,
        environmentId: 'env-b',
      },
    ]

    function Harness({ initialDraft }: { initialDraft: AgentDraft }) {
      const [draft, setDraft] = useState<AgentDraft>(initialDraft)
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          environments={[
            environmentCard('env-a', 'Environment A'),
            environmentCard('env-b', 'Environment B'),
          ]}
          toolCatalog={tools}
          onChange={(next) => {
            drafts.push(next)
            setDraft(next)
          }}
        />
      )
    }

    // 阶段 1：未绑定环境（No environment）也完整展示全部候选工具与未知 orphan 工具
    render(
      <Harness
        initialDraft={{
          ...emptyAgentDraft(modelWithVariants()),
          environmentId: '',
          tools: ['bash', 'unknown.orphan'],
        }}
      />,
    )

    expect(screen.getByLabelText(/bash/)).toBeInTheDocument()
    expect(screen.getByLabelText(/generic-tool/)).toBeInTheDocument()
    expect(screen.getByLabelText(/exact-a-tool/)).toBeInTheDocument()
    expect(screen.getByLabelText(/exact-b-tool/)).toBeInTheDocument()
    expect(screen.getByLabelText(/unknown\.orphan/)).toBeInTheDocument()

    // 阶段 2：选择 Environment A，已选 tools 完整保留
    await chooseSelectOption(user, ENV_LABEL, 'Environment A')
    expect(drafts.at(-1)?.environmentId).toBe('env-a')
    expect(drafts.at(-1)?.tools).toEqual(['bash', 'unknown.orphan'])

    // 勾选 generic-tool 和 exact-a-tool
    await user.click(screen.getByLabelText(/generic-tool/))
    await user.click(screen.getByLabelText(/exact-a-tool/))
    expect(drafts.at(-1)?.tools).toEqual([
      'bash',
      'unknown.orphan',
      'generic-tool',
      'exact-a-tool',
    ])

    // 阶段 3：切换 Environment A -> Environment B，tools 绝对不被清除
    await chooseSelectOption(user, ENV_LABEL, 'Environment B')
    expect(drafts.at(-1)?.environmentId).toBe('env-b')
    expect(drafts.at(-1)?.tools).toEqual([
      'bash',
      'unknown.orphan',
      'generic-tool',
      'exact-a-tool',
    ])

    // 阶段 4：解绑环境（切换为「（无）」），tools 仍完整保留
    await chooseSelectOption(user, ENV_LABEL, '（无）')
    expect(drafts.at(-1)?.environmentId).toBe('')
    expect(drafts.at(-1)?.tools).toEqual([
      'bash',
      'unknown.orphan',
      'generic-tool',
      'exact-a-tool',
    ])
  })

  /**
   * 测试意图：验证不在 toolCatalog 中的已选工具作为 missing orphan 正常展示且可取消勾选，
   * 勾选新工具时与已有全部工具正常合并。
   */
  it('preserves missing orphan tools and allows toggling them alongside catalog tools', async () => {
    const user = userEvent.setup()
    const drafts: AgentDraft[] = []

    const tools: ToolCatalogEntryDTO[] = [
      {
        name: 'bash',
        description: 'host tool',
        environmentRequired: false,
        environmentId: null,
      },
      {
        name: 'generic-tool',
        description: 'generic env tool',
        environmentRequired: true,
        environmentId: null,
      },
      {
        name: 'exact-b-tool',
        description: 'exact B tool',
        environmentRequired: true,
        environmentId: 'env-b',
      },
    ]

    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>({
        ...emptyAgentDraft(modelWithVariants()),
        environmentId: 'env-a',
        tools: ['bash', 'legacy-tool'],
      })
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          environments={[
            environmentCard('env-a', 'Environment A'),
            environmentCard('env-b', 'Environment B'),
          ]}
          toolCatalog={tools}
          onChange={(next) => {
            drafts.push(next)
            setDraft(next)
          }}
        />
      )
    }

    render(<Harness />)

    // legacy-tool 作为 missing orphan 展示并保持勾选
    expect(screen.getByLabelText(/legacy-tool/)).toBeInTheDocument()
    expect(screen.getByLabelText(/legacy-tool/)).toBeChecked()

    // 勾选 generic-tool
    await user.click(screen.getByLabelText(/generic-tool/))

    // 产出的 tools 包含原有 bash、legacy-tool 以及新勾选的 generic-tool
    expect(drafts.at(-1)?.tools).toEqual(['bash', 'legacy-tool', 'generic-tool'])
  })
})
