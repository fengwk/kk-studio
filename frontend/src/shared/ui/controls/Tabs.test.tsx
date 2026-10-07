import { fireEvent, render, screen, within } from '@testing-library/react'
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

  it('never pretends another pane when activeId is disabled or unknown', () => {
    render(
      <Tabs tabs={TABS} activeId="archived" ariaLabel="项目配置" onChange={() => undefined}>
        <p>真实内容</p>
      </Tabs>,
    )

    const tabs = within(screen.getByRole('tablist')).getAllByRole('tab')
    // 没有任何 tab 冒充选中。
    expect(tabs.every((tab) => tab.getAttribute('aria-selected') === 'false')).toBe(true)
    // 内容不伪装成某个 tab 的面板。
    expect(screen.queryByRole('tabpanel')).not.toBeInTheDocument()
    const region = screen.getByText('真实内容').parentElement as HTMLElement
    expect(region).toHaveAttribute('data-tab-state', 'no-active-tab')
    expect(region).not.toHaveAttribute('aria-labelledby')
    // roving tabindex 仍落在第一个可用 tab，键盘可达。
    expect(screen.getByRole('tab', { name: '基础信息' })).toHaveAttribute('tabindex', '0')
    expect(screen.getByRole('tab', { name: '已归档' })).toHaveAttribute('tabindex', '-1')
  })

  it('renders no tablist for empty tabs and keeps content visible without pane claims', () => {
    render(
      <Tabs tabs={[]} activeId="" ariaLabel="项目配置" onChange={() => undefined}>
        <p>仅内容</p>
      </Tabs>,
    )

    expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
    expect(screen.queryByRole('tabpanel')).not.toBeInTheDocument()
    expect(screen.getByText('仅内容')).toBeInTheDocument()
  })

  it('ignores navigation keys when prevented or composing, and never swallows unrelated keys', () => {
    const onChange = vi.fn()
    render(
      <div
        onKeyDownCapture={(event) => {
          if (event.key === 'ArrowLeft') {
            event.preventDefault()
          }
        }}
      >
        <Harness onChange={onChange} />
      </div>,
    )

    const basic = screen.getByRole('tab', { name: '基础信息' })
    basic.focus()

    // 无关按键既不触发选择也不被吞掉。
    expect(fireEvent.keyDown(basic, { key: 'a' })).toBe(true)
    expect(onChange).not.toHaveBeenCalled()

    // 上游已 preventDefault 的方向键不得改变选中。
    fireEvent.keyDown(basic, { key: 'ArrowLeft' })
    expect(onChange).not.toHaveBeenCalled()
    expect(basic).toHaveAttribute('aria-selected', 'true')

    // 输入法组合中的方向键同样不处理。
    fireEvent.keyDown(basic, { key: 'ArrowRight', isComposing: true })
    fireEvent.keyDown(basic, { key: 'ArrowRight', keyCode: 229 })
    expect(onChange).not.toHaveBeenCalled()

    // 正常方向键仍然生效。
    fireEvent.keyDown(basic, { key: 'ArrowRight' })
    expect(onChange).toHaveBeenCalledWith('workflow')
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
