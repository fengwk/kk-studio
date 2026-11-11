import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { SkillSelectionPanel, ToolSelectionPanel } from '@/features/catalog/CapabilitySelectionPanels'
import type { CapabilityOption } from '@/features/catalog/agent-capability-candidates'

describe('Agent capability selection panels', () => {
  // 搜索只缩小可见候选；provider 分组和选中值都保留在用户可观察状态中。
  it('filters tools by name or description without hiding group identity or changing selection', async () => {
    const user = userEvent.setup()
    const toggle = vi.fn()
    const options: CapabilityOption[] = [
      { value: 'read', name: 'read', description: 'Read local files', group: 'BUILTIN' },
      { value: 'mcp_search', name: 'mcp_search', description: 'Search the web', group: 'MCP' },
      { value: 'old_tool', name: 'old_tool', description: null, missing: true },
    ]
    render(<ToolSelectionPanel options={options} selected={['old_tool']} emptyText="No tools" onToggle={toggle} />)

    expect(screen.getByRole('heading', { name: 'BUILTIN' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'MCP' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /old_tool/ })).toBeChecked()
    await user.type(screen.getByRole('searchbox'), 'web')
    expect(screen.getByRole('checkbox', { name: /mcp_search/ })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: /read/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: /old_tool/ })).not.toBeInTheDocument()
    expect(toggle).not.toHaveBeenCalled()
  })

  // 包目录搜索不清理未匹配的已选项，恢复列表后仍可取消不可用项。
  it('filters skills within package groups and lets a selected unavailable skill be removed', async () => {
    const user = userEvent.setup()
    const toggle = vi.fn()
    const options: CapabilityOption[] = [
      { value: 'core:dev', name: 'core / dev', description: 'Development workflow', group: 'core' },
      { value: 'extras:research', name: 'extras / research', description: 'Find sources', group: 'extras' },
      { value: 'lost:old', name: 'lost / old', description: null, missing: true },
    ]
    render(<SkillSelectionPanel options={options} selected={['lost:old']} emptyText="No skills" onToggle={toggle} />)

    expect(screen.getByRole('heading', { name: 'core' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'extras' })).toBeInTheDocument()
    await user.type(screen.getByRole('searchbox'), 'Development')
    expect(screen.getByRole('checkbox', { name: /core \/ dev/ })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: /extras \/ research/ })).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '' } })
    await user.click(screen.getByRole('checkbox', { name: /lost \/ old/ }))
    expect(toggle).toHaveBeenCalledWith('lost:old')
  })
})
