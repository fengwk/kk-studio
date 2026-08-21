import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { AgentForm } from '@/features/ai/catalog/AiAgentResourceForm'
import { chooseSelectOption } from '@/shared/ui/console/chooseSelectOption'
import type { AgentDraft } from '@/features/ai/catalog/ai-console-types'
import { emptyAgentDraft } from '@/features/ai/catalog/ai-agent-draft-codec'
import type {
  AgentDefinitionDTO,
  AgentModelConfigDTO,
  AgentModelView,
} from '@/shared/api/contracts/ai-catalog'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'

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

function liveEnvironment(
  name: string,
  skills: { name: string; description: string | null }[],
  extra?: Partial<LiveEnvironmentDTO>,
): LiveEnvironmentDTO {
  return {
    name,
    status: 'READY',
    ready: true,
    lastSeen: null,
    tools: [],
    skills,
    mcpServers: [],
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
    config: { tools: [], skills: [], subagents: [] },
    version: '1',
    createTime: null,
    updateTime: null,
  }
}

const SOURCE_LABEL = 'Skill 目录 Environment'

describe('AgentForm current contracts', () => {
  it('selects model/variant and toggles unified tools and explicitly browsed live skills', async () => {
    const user = userEvent.setup()
    const longDescription =
      'Execute shell commands in the configured environment and return the captured output without losing long diagnostic context.'
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>({
        ...emptyAgentDraft(modelWithVariants()),
        tools: ['missing-tool'],
      })
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          environments={[
            liveEnvironment('local', [{ name: 'dev', description: 'dev' }]),
          ]}
          toolCatalog={[
            { name: 'bash', version: '1', description: longDescription, type: 'PLATFORM' },
            { name: 'lsp', version: '1', description: 'lsp', type: 'ENVIRONMENT' },
          ]}
          onChange={setDraft}
        />
      )
    }
    render(<Harness />)
    // 已配置但不在候选中的 tool 仍展示且可取消勾选，不会被自动清掉；无红色阻断提示。
    expect(screen.queryByText(/不在 live registry/)).not.toBeInTheDocument()
    expect(screen.queryByText(/无效\/离线 Tools/)).not.toBeInTheDocument()
    const missingTool = screen.getByLabelText(/missing-tool/)
    expect(missingTool).toBeChecked()
    const bashInput = screen.getByLabelText(/bash/)
    const bashOption = bashInput.closest('.capability-option')
    expect(bashOption).toHaveClass('capability-option-detailed')
    expect(bashOption?.querySelector('.capability-option-body')).toBeInTheDocument()
    expect(bashOption?.querySelector('.capability-option-heading .capability-name')).toHaveTextContent('bash')
    expect(bashOption?.querySelector('.capability-option-meta')).toHaveTextContent('1')
    expect(bashOption?.querySelector('.capability-option-description')).toHaveTextContent(longDescription)
    expect(bashOption?.querySelector('.capability-option-description')).toHaveAttribute('title', longDescription)
    await user.click(missingTool)
    expect(missingTool).not.toBeChecked()
    await user.click(bashInput)
    expect(bashInput).toBeChecked()

    // 未显式选择 Skill 目录来源时没有任何 live skill 候选。
    expect(screen.queryByLabelText(/dev/)).not.toBeInTheDocument()
    expect(screen.getByText('暂无候选 Skills')).toBeInTheDocument()
    await chooseSelectOption(user, SOURCE_LABEL, 'local')
    expect(screen.getByLabelText(/dev/)).not.toBeChecked()
    await user.click(screen.getByLabelText(/dev/))
    expect(screen.getByLabelText(/dev/)).toBeChecked()
  })

  it('renders field-level errors for tools and skills without a persisted Agent Environment field', () => {
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
    // 不存在持久化的 Agent Environment 字段；只有瞬态浏览选择器及其说明。
    expect(screen.queryByLabelText('Environment')).not.toBeInTheDocument()
    expect(screen.getByLabelText(SOURCE_LABEL)).toBeInTheDocument()
    expect(
      screen.getByText(/仅用于浏览所选 Environment 的当前技能名称以便编辑；选择是临时的，不会保存或绑定到 Agent/),
    ).toBeInTheDocument()
  })

  it('shows no live skills without a chosen source and keeps selected names as removable orphans', async () => {
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
          environments={[liveEnvironment('local', [{ name: 'dev', description: 'dev' }])]}
          onChange={setDraft}
        />
      )
    }
    render(<Harness />)

    expect(screen.getByLabelText(SOURCE_LABEL)).toHaveAttribute('data-value', '')
    expect(screen.queryByLabelText(/dev/)).not.toBeInTheDocument()
    const ghost = screen.getByLabelText(/ghost-skill/)
    expect(ghost).toBeChecked()
    expect(screen.getByText('不可用')).toBeInTheDocument()
    await user.click(ghost)
    expect(ghost).not.toBeChecked()
  })

  it('shows only the selected source skills and switches sources without losing selected names', async () => {
    const user = userEvent.setup()
    function Harness() {
      const [draft, setDraft] = useState<AgentDraft>(emptyAgentDraft(modelWithVariants()))
      return (
        <AgentForm
          draft={draft}
          models={[modelWithVariants()]}
          environments={[
            liveEnvironment('local', [
              { name: 'dev', description: 'dev skill' },
              { name: 'ops', description: 'ops skill' },
            ]),
            liveEnvironment('remote', [
              { name: 'dev', description: 'duplicate' },
              { name: 'docs', description: null },
            ]),
          ]}
          onChange={setDraft}
        />
      )
    }
    render(<Harness />)
    await chooseSelectOption(user, SOURCE_LABEL, 'local')
    expect(screen.getByLabelText(/dev/)).toBeInTheDocument()
    expect(screen.getByLabelText(/ops/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/docs/)).not.toBeInTheDocument()
    await user.click(screen.getByLabelText(/dev/))
    await user.click(screen.getByLabelText(/ops/))

    // 切换来源：只显示 remote 的 skills；同名 short name 仍是同一持久化名称，local 独有的 ops 成为可移除 orphan。
    await chooseSelectOption(user, SOURCE_LABEL, 'remote')
    expect(screen.getByLabelText(/dev/)).toBeChecked()
    expect(screen.getByLabelText(/docs/)).toBeInTheDocument()
    const opsOrphan = screen.getByLabelText(/ops/)
    expect(opsOrphan).toBeChecked()
    await user.click(opsOrphan)
    expect(opsOrphan).not.toBeChecked()
  })

  it('never mutates the AgentDraft when the browsing source changes', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const draft: AgentDraft = {
      ...emptyAgentDraft(modelWithVariants()),
      tools: ['bash'],
      skills: ['dev'],
    }
    render(
      <AgentForm
        draft={draft}
        models={[modelWithVariants()]}
        environments={[liveEnvironment('local', [{ name: 'dev', description: 'dev' }])]}
        onChange={onChange}
      />,
    )

    await chooseSelectOption(user, SOURCE_LABEL, 'local')
    expect(onChange).not.toHaveBeenCalled()
    // 回到无来源同样只动组件本地浏览状态，绝不触碰 AgentDraft。
    await chooseSelectOption(user, SOURCE_LABEL, '（无）')
    expect(onChange).not.toHaveBeenCalled()

    await user.click(screen.getByLabelText(/dev/))
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith({ ...draft, skills: [] })
  })

  it('clearing the chosen source returns to no live candidates and keeps selected names as orphans', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const draft: AgentDraft = {
      ...emptyAgentDraft(modelWithVariants()),
      skills: ['dev', 'ops'],
    }
    render(
      <AgentForm
        draft={draft}
        models={[modelWithVariants()]}
        environments={[
          liveEnvironment('local', [{ name: 'dev', description: 'dev' }]),
          liveEnvironment('remote', [{ name: 'docs', description: null }]),
        ]}
        onChange={onChange}
      />,
    )

    const sourceSelect = screen.getByLabelText(SOURCE_LABEL)
    await user.click(sourceSelect)
    expect(screen.getByRole('option', { name: '（无）' })).not.toBeDisabled()
    await user.click(screen.getByRole('option', { name: 'local' }))
    expect(screen.getByLabelText(/dev/)).toBeChecked()
    expect(screen.queryByLabelText(/docs/)).not.toBeInTheDocument()

    await chooseSelectOption(user, SOURCE_LABEL, '（无）')
    expect(sourceSelect).toHaveAttribute('data-value', '')
    // 回到无来源：没有 live 候选，已选名称保留为可移除 orphan。
    expect(screen.queryByLabelText(/docs/)).not.toBeInTheDocument()
    const devOrphan = screen.getByLabelText(/dev/)
    expect(devOrphan).toBeChecked()
    expect(devOrphan.closest('.capability-option')).toHaveClass('is-missing')
    expect(screen.getByLabelText(/ops/)).toBeChecked()
    // 清空只动组件本地浏览状态，绝不触碰 AgentDraft。
    expect(onChange).not.toHaveBeenCalled()

    await user.click(devOrphan)
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith({ ...draft, skills: ['ops'] })
  })

  it('excludes stale READY status environments and freezes an unavailable selected source as disabled', async () => {
    const user = userEvent.setup()
    const stale = liveEnvironment('stale', [{ name: 'stale-skill', description: null }], {
      status: 'READY',
      ready: false,
    })
    const initial = [liveEnvironment('local', [{ name: 'dev', description: 'dev' }]), stale]
    const draft: AgentDraft = {
      ...emptyAgentDraft(modelWithVariants()),
      skills: ['dev'],
    }
    const { rerender } = render(
      <AgentForm
        draft={draft}
        models={[modelWithVariants()]}
        environments={initial}
        onChange={() => undefined}
      />,
    )

    // 选项只列出 ready===true 的 Environment；状态文本 READY 但 ready=false 的 stale 被排除。
    const sourceSelect = screen.getByLabelText(SOURCE_LABEL)
    await user.click(sourceSelect)
    expect(screen.queryByRole('option', { name: 'stale' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: 'local' }))

    // 刷新后 local 失效（仍 READY 但 ready=false）：保留为禁用不可用选项、无 live 候选、已选名称保留为 orphan。
    rerender(
      <AgentForm
        draft={draft}
        models={[modelWithVariants()]}
        environments={[{ ...initial[0], ready: false }, stale]}
        onChange={() => undefined}
      />,
    )
    expect(screen.getByLabelText(SOURCE_LABEL)).toHaveAttribute('data-value', 'local')
    expect(screen.getByLabelText(SOURCE_LABEL)).toHaveTextContent('local (不可用)')
    await user.click(screen.getByLabelText(SOURCE_LABEL))
    expect(screen.getByRole('option', { name: 'local (不可用)' })).toBeDisabled()
    // 没有 live 候选：已选 dev 只作为可移除 orphan 保留（带不可用标记），不是 live 候选。
    const devOption = screen.getByLabelText(/dev/).closest('.capability-option')
    expect(devOption).toHaveClass('is-offline')
    expect(devOption).toHaveClass('is-missing')
    expect(screen.getByLabelText(/dev/)).toBeChecked()
  })

  it('keeps tools available even when zero live Environments exist', () => {
    render(
      <AgentForm
        draft={emptyAgentDraft(modelWithVariants())}
        models={[modelWithVariants()]}
        environments={[]}
        toolCatalog={[
          { name: 'bash', version: '1', description: 'shell', type: 'PLATFORM' },
          { name: 'read', version: null, description: 'files', type: 'ENVIRONMENT' },
        ]}
        onChange={() => undefined}
      />,
    )

    expect(screen.getByLabelText(/bash/)).toBeInTheDocument()
    expect(screen.getByLabelText(/read/)).toBeInTheDocument()
    expect(screen.getByLabelText(SOURCE_LABEL)).toHaveAttribute('data-value', '')
    expect(screen.getByText('暂无候选 Skills')).toBeInTheDocument()
  })

  it('keeps fixed tool candidates independent of Environment tools and MCP summaries', () => {
    render(
      <AgentForm
        draft={emptyAgentDraft(modelWithVariants())}
        models={[modelWithVariants()]}
        environments={[
          {
            ...liveEnvironment('local', []),
            tools: [{ name: 'env-only-tool', version: null, description: null }],
            mcpServers: [
              {
                name: 'demo-server',
                status: 'READY',
                error: null,
                tools: [{ name: 'mcp_only_tool', description: null }],
              },
            ],
          },
        ]}
        toolCatalog={[
          { name: 'bash', version: '1', description: 'shell', type: 'PLATFORM' },
          { name: 'read', version: null, description: 'files', type: 'ENVIRONMENT' },
        ]}
        onChange={() => undefined}
      />,
    )

    expect(screen.getByLabelText(/bash/)).toBeInTheDocument()
    expect(screen.getByLabelText(/read/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/env-only-tool/)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/mcp_only_tool/)).not.toBeInTheDocument()
  })

  it('resets the browsing source when a different Agent editor opens', async () => {
    const user = userEvent.setup()
    const draftA: AgentDraft = {
      ...emptyAgentDraft(),
      name: 'agent-a',
      model: 'minimax/MiniMax',
    }
    const draftB: AgentDraft = {
      ...draftA,
      name: 'agent-b',
    }
    const { rerender } = render(
      <AgentForm
        draft={draftA}
        mode="edit"
        models={[modelWithVariants()]}
        environments={[
          liveEnvironment('local', [{ name: 'dev', description: 'dev' }]),
          liveEnvironment('remote', [{ name: 'docs', description: null }]),
        ]}
        onChange={() => undefined}
      />,
    )

    await chooseSelectOption(user, SOURCE_LABEL, 'local')
    expect(screen.getByLabelText(SOURCE_LABEL)).toHaveAttribute('data-value', 'local')

    // 打开另一个 Agent 的编辑器：浏览选择重置为未选择。
    rerender(
      <AgentForm
        draft={draftB}
        mode="edit"
        models={[modelWithVariants()]}
        environments={[
          liveEnvironment('local', [{ name: 'dev', description: 'dev' }]),
          liveEnvironment('remote', [{ name: 'docs', description: null }]),
        ]}
        onChange={() => undefined}
      />,
    )
    expect(screen.getByLabelText(SOURCE_LABEL)).toHaveAttribute('data-value', '')
    expect(screen.queryByLabelText(/dev/)).not.toBeInTheDocument()
  })

  it.each(['deleted/original-model', 'off-page/original-model'])(
    'shows a missing edit Model %s as an unavailable orphan without unrelated variants',
    (modelName) => {
      const draft: AgentDraft = {
        ...emptyAgentDraft(),
        name: 'assistant',
        model: modelName,
        variant: 'persisted-override',
      }
      const unrelatedModel = {
        ...modelWithVariants(),
        providerName: 'loaded',
        name: 'unrelated-model',
      }

      render(
        <AgentForm
          draft={draft}
          mode="edit"
          models={[unrelatedModel]}
          onChange={() => undefined}
        />,
      )

      const modelSelect = screen.getByLabelText('Default Model')
      expect(modelSelect).toHaveAttribute('data-value', modelName)
      expect(modelSelect).toBeDisabled()
      expect(modelSelect).toHaveAttribute('aria-describedby', 'agent-model-identity-status')
      expect(modelSelect).toHaveTextContent(`${modelName} (不可用)`)

      const variantSelect = screen.getByLabelText('Default Variant Override')
      expect(variantSelect).toBeDisabled()
      expect(variantSelect).toHaveAttribute('aria-describedby', 'agent-model-variant-status')
      expect(variantSelect).toHaveTextContent('persisted-override (不可用)')
      expect(screen.getByText('不可用；保存其他字段时仍保留原始身份。')).toBeInTheDocument()
      expect(
        screen.getByText('引用的 Model 未加载，Variant 选项不可用；已保存的覆盖值会保留。'),
      ).toBeInTheDocument()
      expect(screen.getByPlaceholderText('用途说明')).not.toBeDisabled()
    },
  )

  it('lists global Agent catalog candidates and excludes the same-name row in create mode', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const draft: AgentDraft = {
      ...emptyAgentDraft(modelWithVariants()),
      name: 'assistant',
    }
    render(
      <AgentForm
        draft={draft}
        mode="create"
        models={[modelWithVariants()]}
        agents={[
          agentDefinition('helper', 'runs isolated tasks'),
          agentDefinition('writer', null),
          agentDefinition('assistant', 'self'),
        ]}
        onChange={onChange}
      />,
    )

    // 候选展示全局 Agent 的名称与描述；create 模式下与 draft.name 相同的行尚不存在，不展示。
    expect(screen.getByLabelText(/helper/)).toBeInTheDocument()
    expect(screen.getByText('runs isolated tasks')).toBeInTheDocument()
    expect(screen.getByLabelText(/writer/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/^assistant/)).not.toBeInTheDocument()

    await user.click(screen.getByLabelText(/helper/))
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith({ ...draft, subagents: ['helper'] })
  })

  it('keeps selected subagents missing from the catalog as removable orphans', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const draft: AgentDraft = {
      ...emptyAgentDraft(modelWithVariants()),
      name: 'assistant',
      subagents: ['ghost-agent'],
    }
    render(
      <AgentForm
        draft={draft}
        mode="create"
        models={[modelWithVariants()]}
        agents={[agentDefinition('helper', 'runs isolated tasks')]}
        onChange={onChange}
      />,
    )

    // catalog 缺失的已选名称不静默丢弃：保留为可移除 orphan（不可用标记）。
    const ghost = screen.getByLabelText(/ghost-agent/)
    expect(ghost).toBeChecked()
    expect(ghost.closest('.capability-option')).toHaveClass('is-offline')
    expect(ghost.closest('.capability-option')).toHaveClass('is-missing')
    expect(screen.getByText('不可用')).toBeInTheDocument()

    await user.click(ghost)
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith({ ...draft, subagents: [] })
  })

  it('shows the current agent as a subagent candidate in edit mode', () => {
    const draft: AgentDraft = {
      ...emptyAgentDraft(modelWithVariants()),
      name: 'assistant',
      subagents: ['writer'],
    }
    render(
      <AgentForm
        draft={draft}
        mode="edit"
        models={[modelWithVariants()]}
        agents={[agentDefinition('assistant', 'self'), agentDefinition('writer', null)]}
        onChange={() => undefined}
      />,
    )

    // edit 模式下当前 agent 已存在，可正常显示；已选的 writer 保持勾选。
    expect(screen.getByLabelText(/assistant/)).toBeInTheDocument()
    expect(screen.getByLabelText(/writer/)).toBeChecked()
  })

  it('renders field-level errors for the subagents checklist', () => {
    render(
      <AgentForm
        draft={emptyAgentDraft(modelWithVariants())}
        models={[modelWithVariants()]}
        fieldErrors={{ subagents: 'Subagents 名称冲突' }}
        onChange={() => undefined}
      />,
    )

    expect(screen.getByText('Subagents 名称冲突')).toBeInTheDocument()
    expect(screen.getByText('暂无候选 Subagents')).toBeInTheDocument()
  })
})
