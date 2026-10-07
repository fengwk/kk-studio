import { describe, expect, it, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BranchGoalPanel, type BranchGoalSetting } from '@/features/ai/runtime/thread-panel/BranchGoalPanel'
import type { BranchGoalProgressResult } from '@/features/ai/runtime/goal-progress'

const mockGoal: BranchGoalSetting = {
  id: 'goal-uuid-12345678',
  text: 'Implement user-controlled Branch Goal UI',
}

const emptyProgress: BranchGoalProgressResult = {
  active: null,
  stale: null,
}

describe('BranchGoalPanel', () => {
  it('renders empty goal state and allows setting a new goal', () => {
    const onSubmitGoal = vi.fn()
    const onClearGoal = vi.fn()
    const onClose = vi.fn()

    render(
      <BranchGoalPanel
        goal={null}
        progress={emptyProgress}
        onSubmitGoal={onSubmitGoal}
        onClearGoal={onClearGoal}
        onClose={onClose}
      />,
    )

    expect(screen.getByText(/暂无设定目标/)).toBeInTheDocument()
    const textarea = screen.getByRole('textbox')
    expect(textarea).toBeInTheDocument()

    // Type new goal text and submit
    fireEvent.change(textarea, { target: { value: '  Write documentation  ' } })
    const submitBtn = screen.getByRole('button', { name: /设置目标/ })
    expect(submitBtn).toBeEnabled()
    fireEvent.click(submitBtn)

    expect(onSubmitGoal).toHaveBeenCalledWith('Write documentation')
  })

  it('renders current goal text without exposing the raw goal id, and allows clearing', () => {
    const onSubmitGoal = vi.fn()
    const onClearGoal = vi.fn()
    const onClose = vi.fn()

    render(
      <BranchGoalPanel
        goal={mockGoal}
        progress={emptyProgress}
        onSubmitGoal={onSubmitGoal}
        onClearGoal={onClearGoal}
        onClose={onClose}
      />,
    )

    expect(screen.getByText(mockGoal.text)).toBeInTheDocument()
    // 轻量面板不再把原始 goal ID 当作主信息展示。
    expect(screen.queryByText(/ID:/)).not.toBeInTheDocument()
    expect(screen.queryByText(mockGoal.id)).not.toBeInTheDocument()

    const clearBtn = screen.getByRole('button', { name: /清除目标/ })
    expect(clearBtn).toBeInTheDocument()
    fireEvent.click(clearBtn)

    expect(onClearGoal).toHaveBeenCalledTimes(1)
  })

  it('renders active Agent report with disclaimer and status badge', () => {
    const activeProgress: BranchGoalProgressResult = {
      active: {
        entryId: 'entry-1',
        goalId: mockGoal.id,
        status: 'complete',
        reason: 'Successfully satisfied all acceptance criteria',
        reportedAt: '2026-09-24T12:00:00Z',
        createTime: '2026-09-24T12:00:00Z',
      },
      stale: null,
    }

    render(
      <BranchGoalPanel
        goal={mockGoal}
        progress={activeProgress}
        onSubmitGoal={vi.fn()}
        onClearGoal={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    expect(screen.getByText(/已完成（Agent 报告）/)).toBeInTheDocument()
    expect(screen.getByText(/由 Agent 报告，非系统验收/)).toBeInTheDocument()
    expect(screen.getByText('Successfully satisfied all acceptance criteria')).toBeInTheDocument()
  })

  it('renders stale report notice when report goalId does not match current goal', () => {
    const staleProgress: BranchGoalProgressResult = {
      active: null,
      stale: {
        entryId: 'entry-1',
        goalId: 'old-goal-id',
        status: 'complete',
        reason: 'Old report',
        reportedAt: '2026-09-24T10:00:00Z',
        createTime: '2026-09-24T10:00:00Z',
      },
    }

    render(
      <BranchGoalPanel
        goal={mockGoal}
        progress={staleProgress}
        onSubmitGoal={vi.fn()}
        onClearGoal={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    expect(screen.getByText(/历史目标的 Agent 报告已失效/)).toBeInTheDocument()
  })

  it('validates Unicode code points ≤ 2000 (supporting surrogate pairs/emojis)', () => {
    const onSubmitGoal = vi.fn()

    render(
      <BranchGoalPanel
        goal={null}
        progress={emptyProgress}
        onSubmitGoal={onSubmitGoal}
        onClearGoal={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    const textarea = screen.getByRole('textbox')
    const submitBtn = screen.getByRole('button', { name: /设置目标/ })

    // 2000 emojis (each is 2 UTF-16 code units = 4000 UTF-16 units, but exactly 2000 Unicode code points)
    const emoji2000 = '🎯'.repeat(2000)
    expect(emoji2000.length).toBe(4000)
    expect(Array.from(emoji2000).length).toBe(2000)

    fireEvent.change(textarea, { target: { value: emoji2000 } })
    expect(screen.getByText('2000 / 2000')).toBeInTheDocument()
    expect(submitBtn).toBeEnabled()

    // 2001 emojis => exceeds code point limit
    const emoji2001 = '🎯'.repeat(2001)
    fireEvent.change(textarea, { target: { value: emoji2001 } })
    expect(screen.getByText('2001 / 2000')).toBeInTheDocument()
    expect(submitBtn).toBeDisabled()
  })

  it('renders read-only notice and hides actions when readOnly is true', () => {
    render(
      <BranchGoalPanel
        goal={mockGoal}
        progress={emptyProgress}
        readOnly={true}
        onSubmitGoal={vi.fn()}
        onClearGoal={vi.fn()}
        onClose={vi.fn()}
      />,
    )

    expect(screen.getByText(/只读模式/)).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /清除目标/ })).not.toBeInTheDocument()
  })
})

describe('BranchGoalPanel Escape close policy (U3)', () => {
  it('closes from inside the goal textarea without submitting or clearing', async () => {
    const user = userEvent.setup()
    const onSubmitGoal = vi.fn()
    const onClearGoal = vi.fn()
    const onClose = vi.fn()

    render(
      <BranchGoalPanel
        goal={mockGoal}
        progress={emptyProgress}
        onSubmitGoal={onSubmitGoal}
        onClearGoal={onClearGoal}
        onClose={onClose}
      />,
    )

    const textarea = screen.getByRole('textbox')
    // 打开即聚焦输入框，使面板自身能捕获 Esc。
    expect(document.activeElement).toBe(textarea)
    await user.type(textarea, 'draft in progress')
    await user.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalledTimes(1)
    // Esc 不等于 Stop：不提交、不清除 Goal。
    expect(onSubmitGoal).not.toHaveBeenCalled()
    expect(onClearGoal).not.toHaveBeenCalled()
  })

  it('closes from a focused action button', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()

    render(
      <BranchGoalPanel
        goal={mockGoal}
        progress={emptyProgress}
        onSubmitGoal={vi.fn()}
        onClearGoal={vi.fn()}
        onClose={onClose}
      />,
    )

    const clearBtn = screen.getByRole('button', { name: /清除目标/ })
    clearBtn.focus()
    await user.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('closes from the read-only panel itself when there is no focusable input', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()

    render(
      <BranchGoalPanel
        goal={mockGoal}
        progress={emptyProgress}
        readOnly
        onSubmitGoal={vi.fn()}
        onClearGoal={vi.fn()}
        onClose={onClose}
      />,
    )

    const region = screen.getByRole('region', { name: 'thread 目标' })
    expect(document.activeElement).toBe(region)
    await user.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('ignores IME composition and already-consumed Escape, then closes on a plain Escape', () => {
    const onClose = vi.fn()

    render(
      <BranchGoalPanel
        goal={null}
        progress={emptyProgress}
        onSubmitGoal={vi.fn()}
        onClearGoal={vi.fn()}
        onClose={onClose}
      />,
    )

    const region = screen.getByRole('region', { name: 'thread 目标' })

    // IME 组合中（keyCode 229 / isComposing）不得关闭面板。
    fireEvent.keyDown(region, { key: 'Escape', keyCode: 229 })
    fireEvent.keyDown(region, { key: 'Escape', isComposing: true })
    expect(onClose).not.toHaveBeenCalled()

    // 已在更内层被消费（defaultPrevented）的 Escape 不得重复关闭父面板。
    const consumed = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    consumed.preventDefault()
    fireEvent(region, consumed)
    expect(onClose).not.toHaveBeenCalled()

    fireEvent.keyDown(region, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
