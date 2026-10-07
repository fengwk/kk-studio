import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'

describe('TextInput', () => {
  it('keeps native input semantics and reports invalid state to assistive tech', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<TextInput aria-label="项目名称" invalid onChange={onChange} />)

    const input = screen.getByLabelText('项目名称')
    expect(input.tagName).toBe('INPUT')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAttribute('data-invalid', 'true')

    await user.type(input, 'ab')
    expect(onChange).toHaveBeenCalled()
  })

  it('stays a valid, focusable control in the normal and disabled states', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const { rerender } = render(<TextInput aria-label="名称" onChange={onChange} />)

    const input = screen.getByLabelText('名称')
    expect(input).not.toHaveAttribute('aria-invalid')
    await user.click(input)
    expect(input).toHaveFocus()

    rerender(<TextInput aria-label="名称" disabled onChange={onChange} />)
    expect(screen.getByLabelText('名称')).toBeDisabled()
  })
})

describe('TextArea', () => {
  it('renders a textarea with the shared class, theme semantics and no invalid state by default', () => {
    render(<TextArea aria-label="系统提示词" defaultValue="prompt" />)

    const textarea = screen.getByLabelText('系统提示词')
    expect(textarea.tagName).toBe('TEXTAREA')
    expect(textarea).toHaveClass('ui-textarea')
    expect(textarea).not.toHaveAttribute('data-invalid')
    expect(textarea).toHaveValue('prompt')
  })
})
