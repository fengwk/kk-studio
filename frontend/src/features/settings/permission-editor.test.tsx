import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PermissionEditor } from '@/features/settings/permission/PermissionEditor'
import type { PermissionGroupDraft } from '@/features/settings/system-settings-draft'
import { agentService } from '@/shared/api/agent-service'
import type { ToolCatalogEntryDTO } from '@/shared/api/contracts/ai-catalog'
import type { SystemSettingsSchemaOption } from '@/shared/api/contracts/system-settings'
import { chooseSelectOption } from '@/test-support/chooseSelectOption'

vi.mock('@/shared/api/agent-service', () => ({
  agentService: {
    listTools: vi.fn(),
    listModels: vi.fn(),
  },
}))

const PERMISSION_OPTIONS: SystemSettingsSchemaOption[] = [
  { value: 'allow', labelKey: 'settings.permission.action.allow' },
  { value: 'ask', labelKey: 'settings.permission.action.ask' },
  { value: 'deny', labelKey: 'settings.permission.action.deny' },
]

function tool(name: string): ToolCatalogEntryDTO {
  return {
    id: `base.${name}`,
    name,
    version: '1',
    description: null,
    backend: 'HOST',
  }
}

function Harness({ initial }: { initial: PermissionGroupDraft[] }) {
  const [groups, setGroups] = useState(initial)
  return <PermissionEditor groups={groups} onChange={setGroups} options={PERMISSION_OPTIONS} />
}

function renderEditor(initial: PermissionGroupDraft[]) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <Harness initial={initial} />
    </QueryClientProvider>,
  )
}

function patternInputs(scope: HTMLElement = document.body) {
  return within(scope).getAllByLabelText(/第 \d+ 条规则的模式/)
}

describe('PermissionEditor UI', () => {
  beforeEach(() => {
    vi.mocked(agentService.listTools).mockResolvedValue([
      tool('bash'),
      tool('write'),
      tool('edit'),
    ])
  })

  it('renders tool groups with their ordered rules', async () => {
    const user = userEvent.setup()
    renderEditor([
      { tool: 'base.bash', rules: [{ pattern: '*', action: 'ask' }] },
      { tool: 'base.write', rules: [{ pattern: 'node_modules/**', action: 'deny' }] },
    ])
    const bashGroup = screen.getByLabelText('权限分组 base.bash')
    const writeGroup = screen.getByLabelText('权限分组 base.write')
    await waitFor(() => {
      expect(within(bashGroup).getByLabelText('工具')).toHaveAttribute('data-value', 'base.bash')
    })
    expect(within(bashGroup).getByDisplayValue('*')).toBeInTheDocument()
    expect(within(writeGroup).getByDisplayValue('node_modules/**')).toBeInTheDocument()
    await user.click(within(bashGroup).getByLabelText('工具'))
    expect(screen.getByRole('option', { name: 'base.bash' })).toBeInTheDocument()
  })

  it('retains a persisted tool ID missing from the catalog as an unavailable option', async () => {
    const user = userEvent.setup()
    renderEditor([{ tool: 'legacy.read', rules: [{ pattern: '*', action: 'ask' }] }])

    const group = screen.getByLabelText('权限分组 legacy.read')
    const select = within(group).getByLabelText('工具')
    expect(select).toHaveAttribute('data-value', 'legacy.read')
    await user.click(select)
    expect(screen.getByRole('option', { name: 'legacy.read (不可用)' })).toBeInTheDocument()
  })

  it('edits the action of a rule via the select', async () => {
    const user = userEvent.setup()
    renderEditor([{ tool: 'base.bash', rules: [{ pattern: '*', action: 'ask' }] }])
    const select = screen.getByLabelText('第 1 条规则的动作')
    expect(select).toHaveAttribute('data-value', 'ask')
    await chooseSelectOption(user, '第 1 条规则的动作', '拒绝')
    expect(screen.getByLabelText('第 1 条规则的动作')).toHaveAttribute('data-value', 'deny')
  })

  it('adds and removes rules for a tool', async () => {
    const user = userEvent.setup()
    renderEditor([{ tool: 'base.bash', rules: [{ pattern: '*', action: 'ask' }] }])
    const group = screen.getByLabelText('权限分组 base.bash')
    expect(patternInputs(group)).toHaveLength(1)

    await user.click(within(group).getByRole('button', { name: '添加规则' }))
    expect(patternInputs(group)).toHaveLength(2)

    const removeButtons = within(group).getAllByRole('button', { name: '移除第 2 条规则' })
    expect(removeButtons.length).toBe(1)
    await user.click(removeButtons[0]!)
    expect(patternInputs(group)).toHaveLength(1)
  })

  it('moves a rule up/down within the same tool preserving order', async () => {
    const user = userEvent.setup()
    renderEditor([
      {
        tool: 'base.bash',
        rules: [
          { pattern: 'first', action: 'ask' },
          { pattern: 'second', action: 'allow' },
        ],
      },
    ])
    const group = screen.getByLabelText('权限分组 base.bash')
    const inputs = patternInputs(group)
    expect(inputs[0]).toHaveValue('first')

    const downButtons = within(group).getAllByRole('button', { name: '下移' })
    const enabledDown = downButtons.find((button) => !(button instanceof HTMLButtonElement && button.disabled))
    await user.click(enabledDown!)

    const afterDown = patternInputs(group)
    expect(afterDown[0]).toHaveValue('second')
    expect(afterDown[1]).toHaveValue('first')

    const upButtons = within(group).getAllByRole('button', { name: '上移' })
    await user.click(upButtons[1]!)
    const afterUp = patternInputs(group)
    expect(afterUp[0]).toHaveValue('first')
    expect(afterUp[1]).toHaveValue('second')
  })

  it('merges rules deterministically when renaming a tool onto an existing tool', async () => {
    const user = userEvent.setup()
    renderEditor([
      { tool: 'base.bash', rules: [{ pattern: 'scripts/*', action: 'deny' }] },
      { tool: 'base.write', rules: [{ pattern: '*', action: 'ask' }] },
    ])
    const bashGroup = await screen.findByLabelText('权限分组 base.bash')
    await waitFor(() => {
      expect(within(bashGroup).getByLabelText('工具')).toHaveAttribute('data-value', 'base.bash')
    })
    await chooseSelectOption(user, '工具', 'base.write', within(bashGroup))

    expect(screen.queryByLabelText('权限分组 base.bash')).not.toBeInTheDocument()
    const writeGroup = screen.getByLabelText('权限分组 base.write')
    const patterns = patternInputs(writeGroup).map((input) => input.getAttribute('value'))
    expect(patterns).toEqual(['*', 'scripts/*'])
  })

  it('exposes validation-friendly empty states for blank tool names and patterns', () => {
    renderEditor([{ tool: '', rules: [{ pattern: '', action: 'ask' }] }])
    expect(screen.getByText('工具名不能为空。')).toBeInTheDocument()
    expect(screen.getByText('规则模式不能为空。')).toBeInTheDocument()
  })

  it('adds a new empty tool group', async () => {
    const user = userEvent.setup()
    renderEditor([{ tool: 'base.bash', rules: [] }])
    expect(screen.getAllByLabelText(/^权限分组/)).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: '添加工具' }))
    const groups = screen.getAllByLabelText(/^权限分组/)
    expect(groups).toHaveLength(2)
    expect(within(groups[1]!).getByText('该工具暂无规则。')).toBeInTheDocument()
  })

  it('removes an entire tool group', async () => {
    const user = userEvent.setup()
    renderEditor([{ tool: 'base.bash', rules: [{ pattern: '*', action: 'ask' }] }])
    await user.click(screen.getByRole('button', { name: '移除工具 base.bash' }))
    expect(screen.queryByLabelText('权限分组 base.bash')).not.toBeInTheDocument()
    expect(screen.getByText('尚未配置权限规则。')).toBeInTheDocument()
  })
})
