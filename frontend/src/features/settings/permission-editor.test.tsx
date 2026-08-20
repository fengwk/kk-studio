import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { beforeEach, describe, expect, it } from 'vitest'
import { PermissionEditor } from '@/features/settings/permission/PermissionEditor'
import type { PermissionGroupDraft } from '@/features/settings/system-settings-draft'
import type { SystemSettingsSchemaOption } from '@/shared/api/contracts/system-settings'

const PERMISSION_OPTIONS: SystemSettingsSchemaOption[] = [
  { value: 'allow', labelKey: 'settings.permission.action.allow' },
  { value: 'ask', labelKey: 'settings.permission.action.ask' },
  { value: 'deny', labelKey: 'settings.permission.action.deny' },
]

function Harness({ initial }: { initial: PermissionGroupDraft[] }) {
  const [groups, setGroups] = useState(initial)
  return <PermissionEditor groups={groups} onChange={setGroups} options={PERMISSION_OPTIONS} />
}

function patternInputs(scope: HTMLElement = document.body) {
  return within(scope).getAllByLabelText(/第 \d+ 条规则的模式/)
}

describe('PermissionEditor UI', () => {
  beforeEach(() => {
    // test-setup 已把语言切到 zh-CN；这里只做显式断言前的环境快照。
  })

  it('renders tool groups with their ordered rules', () => {
    render(
      <Harness
        initial={[
          { tool: 'bash', rules: [{ pattern: '*', action: 'ask' }] },
          { tool: 'write', rules: [{ pattern: 'node_modules/**', action: 'deny' }] },
        ]}
      />,
    )
    const bashGroup = screen.getByLabelText('权限分组 bash')
    const writeGroup = screen.getByLabelText('权限分组 write')
    expect(within(bashGroup).getByDisplayValue('bash')).toBeInTheDocument()
    expect(within(bashGroup).getByDisplayValue('*')).toBeInTheDocument()
    expect(within(writeGroup).getByDisplayValue('node_modules/**')).toBeInTheDocument()
  })

  it('edits the action of a rule via the select', async () => {
    const user = userEvent.setup()
    render(<Harness initial={[{ tool: 'bash', rules: [{ pattern: '*', action: 'ask' }] }]} />)
    const select = screen.getByLabelText('第 1 条规则的动作')
    expect(select).toHaveValue('ask')
    await user.selectOptions(select, 'deny')
    expect(select).toHaveValue('deny')
  })

  it('adds and removes rules for a tool', async () => {
    const user = userEvent.setup()
    render(<Harness initial={[{ tool: 'bash', rules: [{ pattern: '*', action: 'ask' }] }]} />)
    const group = screen.getByLabelText('权限分组 bash')
    expect(patternInputs(group)).toHaveLength(1)

    await user.click(within(group).getByRole('button', { name: '添加规则' }))
    expect(patternInputs(group)).toHaveLength(2)

    // 移除第 2 条规则。
    const removeButtons = within(group).getAllByRole('button', { name: '移除第 2 条规则' })
    expect(removeButtons.length).toBe(1)
    await user.click(removeButtons[0]!)
    expect(patternInputs(group)).toHaveLength(1)
  })

  it('moves a rule up/down within the same tool preserving order', async () => {
    const user = userEvent.setup()
    render(
      <Harness
        initial={[
          {
            tool: 'bash',
            rules: [
              { pattern: 'first', action: 'ask' },
              { pattern: 'second', action: 'allow' },
            ],
          },
        ]}
      />,
    )
    const group = screen.getByLabelText('权限分组 bash')
    const inputs = patternInputs(group)
    expect(inputs[0]).toHaveValue('first')

    // 「下移」只对第一条生效；禁用按钮也带同名 aria-label，取可点击的那个。
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
    render(
      <Harness
        initial={[
          { tool: 'bash', rules: [{ pattern: 'scripts/*', action: 'deny' }] },
          { tool: 'write', rules: [{ pattern: '*', action: 'ask' }] },
        ]}
      />,
    )
    const bashGroup = screen.getByLabelText('权限分组 bash')
    const nameInput = within(bashGroup).getByLabelText('工具')
    await user.clear(nameInput)
    await user.type(nameInput, 'write')

    // bash 分组被合并进 write：现在只有一个 write 分组且规则追加其后，无规则丢失。
    expect(screen.queryByLabelText('权限分组 bash')).not.toBeInTheDocument()
    const writeGroup = screen.getByLabelText('权限分组 write')
    const patterns = patternInputs(writeGroup).map((input) => input.getAttribute('value'))
    expect(patterns).toEqual(['*', 'scripts/*'])
  })

  it('exposes validation-friendly empty states for blank tool names and patterns', () => {
    render(<Harness initial={[{ tool: '', rules: [{ pattern: '', action: 'ask' }] }]} />)
    expect(screen.getByText('工具名不能为空。')).toBeInTheDocument()
    expect(screen.getByText('规则模式不能为空。')).toBeInTheDocument()
  })

  it('adds a new empty tool group', async () => {
    const user = userEvent.setup()
    render(<Harness initial={[{ tool: 'bash', rules: [] }]} />)
    expect(screen.getAllByLabelText(/^权限分组/)).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: '添加工具' }))
    const groups = screen.getAllByLabelText(/^权限分组/)
    expect(groups).toHaveLength(2)
    // 新增分组为空：展示「暂无规则」提示。
    expect(within(groups[1]!).getByText('该工具暂无规则。')).toBeInTheDocument()
  })

  it('removes an entire tool group', async () => {
    const user = userEvent.setup()
    render(<Harness initial={[{ tool: 'bash', rules: [{ pattern: '*', action: 'ask' }] }]} />)
    await user.click(screen.getByRole('button', { name: '移除工具 bash' }))
    expect(screen.queryByLabelText('权限分组 bash')).not.toBeInTheDocument()
    expect(screen.getByText('尚未配置权限规则。')).toBeInTheDocument()
  })
})
