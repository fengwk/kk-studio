import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { Tabs, type TabItem } from '@/shared/ui/controls/Tabs'

const TABS: TabItem[] = [
  { id: 'basic', label: '基础信息' },
  { id: 'workflow', label: '工作流' },
  { id: 'archived', label: '已归档', disabled: true },
  { id: 'history', label: '历史' },
]

/** 受控 Tabs 的最小宿主：选中态由调用方持有。 */
function Harness({ onChange }: { onChange?: (id: string) => void } = {}) {
  const [activeId, setActiveId] = useState('basic')
  return (
    <Tabs
      tabs={TABS}
      activeId={activeId}
      ariaLabel="项目配置"
      onChange={(id) => {
        onChange?.(id)
        setActiveId(id)
      }}
    >
      <p>{`面板：${activeId}`}</p>
    </Tabs>
  )
}

describe('Tabs', () => {
  it('wires tablist/tab/tabpanel semantics and a single roving tabindex', () => {
    render(<Harness />)

    const tablist = screen.getByRole('tablist', { name: '项目配置' })
    const tabs = within(tablist).getAllByRole('tab')
    expect(tabs.map((tab) => tab.textContent)).toEqual(['基础信息', '工作流', '已归档', '历史'])

    const selected = screen.getByRole('tab', { name: '基础信息' })
    expect(selected).toHaveAttribute('aria-selected', 'true')
    expect(selected).toHaveAttribute('tabindex', '0')
    expect(screen.getByRole('tab', { name: '工作流' })).toHaveAttribute('tabindex', '-1')

    const panel = screen.getByRole('tabpanel')
    expect(panel).toHaveTextContent('面板：basic')
    // 面板必须由当前选中 tab 命名，且与 tab 的 aria-controls 对应。
    expect(panel).toHaveAttribute('aria-labelledby', selected.id)
    expect(selected).toHaveAttribute('aria-controls', panel.id)
  })

  it('selects a tab on click and swaps the visible panel', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)

    await user.click(screen.getByRole('tab', { name: '工作流' }))
    expect(onChange).toHaveBeenCalledWith('workflow')
    expect(screen.getByRole('tab', { name: '工作流' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tabpanel')).toHaveTextContent('面板：workflow')
  })

  it('navigates with Arrow/Home/End while skipping disabled tabs and moving focus', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    const basic = screen.getByRole('tab', { name: '基础信息' })
    const workflow = screen.getByRole('tab', { name: '工作流' })
    const history = screen.getByRole('tab', { name: '历史' })

    basic.focus()
    await user.keyboard('{ArrowRight}')
    expect(workflow).toHaveFocus()
    expect(workflow).toHaveAttribute('aria-selected', 'true')

    // 已归档被禁用：方向键必须跳过它，直接落到下一个可用 tab。
    await user.keyboard('{ArrowRight}')
    expect(history).toHaveFocus()
    expect(history).toHaveAttribute('aria-selected', 'true')

    await user.keyboard('{Home}')
    expect(basic).toHaveFocus()
    expect(basic).toHaveAttribute('aria-selected', 'true')

    await user.keyboard('{ArrowLeft}')
    expect(history).toHaveFocus()

    await user.keyboard('{End}')
    expect(history).toHaveFocus()

    expect(screen.getByRole('tab', { name: '已归档' })).toBeDisabled()
  })
})
