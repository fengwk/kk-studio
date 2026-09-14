import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { EnvironmentSourcesSection } from './EnvironmentSourcesSection'

describe('EnvironmentSourcesSection', () => {
  // 测试意图：验证「添加来源」按钮具有明确的中等强调度样式类 (.btn-secondary.btn-sm) 且点击正确触发 onAdd
  it('renders add source button with btn-secondary btn-sm style and triggers onAdd callback', async () => {
    const user = userEvent.setup()
    const onAdd = vi.fn()

    render(
      <EnvironmentSourcesSection
        sources={[]}
        onAdd={onAdd}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onAction={vi.fn()}
      />,
    )

    const addBtn = screen.getByRole('button', { name: /添加来源/ })
    expect(addBtn).toBeInTheDocument()
    expect(addBtn).toHaveClass('btn-secondary')
    expect(addBtn).toHaveClass('btn-sm')

    await user.click(addBtn)
    expect(onAdd).toHaveBeenCalledTimes(1)
  })
})
