import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { FormSelect } from '@/shared/ui/console/FormSelect'

const OPTIONS = [
  { value: 'a', label: 'Alpha' },
  { value: 'b', label: 'Bravo' },
  { value: 'c', label: 'Charlie', disabled: true },
]

describe('FormSelect', () => {
  it('renders a single labelable control with the matching label', () => {
    render(
      <label>
        <span>Choose</span>
        <FormSelect
          aria-label="Choose"
          value="a"
          options={OPTIONS}
          onChange={() => undefined}
        />
      </label>,
    )
    // 通过 aria-label 找到唯一的 select；外部 <label> 不会再额外绑出第二个控件。
    expect(screen.getByLabelText('Choose')).toBeInstanceOf(HTMLSelectElement)
    expect(screen.queryByRole('button', { name: 'Choose' })).not.toBeInTheDocument()
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('exposes option labels in document order', () => {
    render(
      <FormSelect aria-label="Choose" value="a" options={OPTIONS} onChange={() => undefined} />,
    )
    const select = screen.getByLabelText('Choose')
    expect(Array.from(select.querySelectorAll('option')).map((option) => option.textContent)).toEqual([
      'Alpha',
      'Bravo',
      'Charlie',
    ])
  })

  it('marks the currently selected option as selected and disables the disabled option', () => {
    render(
      <FormSelect aria-label="Choose" value="b" options={OPTIONS} onChange={() => undefined} />,
    )
    const select = screen.getByLabelText('Choose') as HTMLSelectElement
    expect(select.value).toBe('b')
    const options = Array.from(select.querySelectorAll('option'))
    expect(options[1]).toBe(select.selectedOptions[0] as HTMLOptionElement)
    expect(options[2]).toBeDisabled()
  })

  it('forwards onChange with the chosen value', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <FormSelect aria-label="Choose" value="a" options={OPTIONS} onChange={onChange} />,
    )
    await user.selectOptions(screen.getByLabelText('Choose'), 'b')
    expect(onChange).toHaveBeenCalledWith('b')
  })

  it('renders a disabled control when disabled', () => {
    render(
      <FormSelect
        aria-label="Choose"
        value="a"
        options={OPTIONS}
        disabled
        onChange={() => undefined}
      />,
    )
    expect(screen.getByLabelText('Choose')).toBeDisabled()
  })

  it('forwards required to the underlying control', () => {
    render(
      <FormSelect
        aria-label="Choose"
        value="a"
        options={OPTIONS}
        required
        onChange={() => undefined}
      />,
    )
    expect(screen.getByLabelText('Choose')).toBeRequired()
  })

  it('renders the placeholder only when no option matches', () => {
    const { rerender } = render(
      <FormSelect
        aria-label="Choose"
        value=""
        options={OPTIONS}
        placeholder="请选择"
        onChange={() => undefined}
      />,
    )
    const select = screen.getByLabelText('Choose') as HTMLSelectElement
    const placeholderOption = select.querySelector('option[value=""]')
    expect(placeholderOption).not.toBeNull()
    expect(placeholderOption?.textContent).toBe('请选择')
    expect(placeholderOption).toBeDisabled()
    expect(placeholderOption).toHaveAttribute('hidden')

    rerender(
      <FormSelect
        aria-label="Choose"
        value="a"
        options={OPTIONS}
        placeholder="请选择"
        onChange={() => undefined}
      />,
    )
    const rerenderedSelect = screen.getByLabelText('Choose') as HTMLSelectElement
    expect(rerenderedSelect.querySelector('option[value=""]')).toBeNull()
  })
})