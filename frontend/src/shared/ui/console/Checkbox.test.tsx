import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Checkbox } from './Checkbox'

describe('Checkbox', () => {
  // 测试意图：验证 Checkbox 渲染原生 checkbox 角色，支持受控 checked 状态，并渲染指定标签文本
  it('renders native checkbox role with accessible label and reflects checked state', () => {
    const onChange = vi.fn()
    const { rerender } = render(
      <Checkbox checked={false} onChange={onChange} label="测试选项" />,
    )

    const checkbox = screen.getByRole('checkbox', { name: '测试选项' })
    expect(checkbox).toBeInTheDocument()
    expect(checkbox).not.toBeChecked()

    rerender(<Checkbox checked={true} onChange={onChange} label="测试选项" />)
    expect(checkbox).toBeChecked()
  })

  // 测试意图：验证用户通过鼠标点击或键盘空格键能够触发 onChange 回调并传递正确布尔值
  it('triggers onChange when clicked or toggled via keyboard space', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<Checkbox checked={false} onChange={onChange} label="可切换选项" />)

    const checkbox = screen.getByRole('checkbox', { name: '可切换选项' })
    await user.click(checkbox)
    expect(onChange).toHaveBeenCalledWith(true)

    onChange.mockClear()
    checkbox.focus()
    expect(checkbox).toHaveFocus()
    await user.keyboard(' ')
    expect(onChange).toHaveBeenCalledWith(true)
  })

  // 测试意图：验证 disabled 状态下不可点击、不触发 onChange，并且视觉具备禁用标记
  it('respects disabled state and prevents user interactions', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<Checkbox checked={false} onChange={onChange} disabled label="禁用选项" />)

    const checkbox = screen.getByRole('checkbox', { name: '禁用选项' })
    expect(checkbox).toBeDisabled()

    await user.click(checkbox)
    expect(onChange).not.toHaveBeenCalled()
  })

  // 测试意图：验证无文本 label 时可通过 aria-label 保证屏幕阅读器无障碍可访问
  it('supports aria-label when visual label text is omitted', () => {
    render(<Checkbox checked={true} onChange={vi.fn()} aria-label="无可见标签的选项" />)
    const checkbox = screen.getByRole('checkbox', { name: '无可见标签的选项' })
    expect(checkbox).toBeInTheDocument()
    expect(checkbox).toBeChecked()
  })
})
