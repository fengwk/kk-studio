import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { chooseSelectOption } from '@/test-support/chooseSelectOption'
import { Select } from '@/shared/ui/console/Select'

const OPTIONS = [
  { value: 'a', label: 'Alpha' },
  { value: 'b', label: 'Bravo' },
  { value: 'c', label: 'Charlie', disabled: true },
]

describe('Select', () => {
  it('renders a labelled listbox trigger rather than a native select', () => {
    render(
      <Select
        aria-label="Choose"
        value="a"
        options={OPTIONS}
        onChange={() => undefined}
      />,
    )
    const trigger = screen.getByLabelText('Choose')
    expect(trigger).toBeInstanceOf(HTMLButtonElement)
    expect(trigger).toHaveAttribute('data-value', 'a')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('opens options in document order and marks the selected value', async () => {
    const user = userEvent.setup()
    render(
      <Select aria-label="Choose" value="b" options={OPTIONS} onChange={() => undefined} />,
    )
    await user.click(screen.getByLabelText('Choose'))
    const options = screen.getAllByRole('option')
    expect(options.map((option) => option.textContent)).toEqual(['Alpha', 'Bravo', 'Charlie'])
    expect(options[1]).toHaveAttribute('aria-selected', 'true')
    expect(options[2]).toBeDisabled()
  })

  it('forwards onChange with the chosen value', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <Select aria-label="Choose" value="a" options={OPTIONS} onChange={onChange} />,
    )
    await chooseSelectOption(user, 'Choose', 'Bravo')
    expect(onChange).toHaveBeenCalledWith('b')
  })

  it('opens the listbox with ArrowDown and selects the next option with Enter', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <Select aria-label="Choose" value="a" options={OPTIONS} onChange={onChange} />,
    )
    const trigger = screen.getByLabelText('Choose')
    await user.click(trigger)
    await user.keyboard('{ArrowDown}')
    await user.keyboard('{Enter}')
    expect(onChange).toHaveBeenCalledWith('b')
  })

  it('renders a disabled control when disabled', () => {
    render(
      <Select
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
      <Select
        aria-label="Choose"
        value="a"
        options={OPTIONS}
        required
        onChange={() => undefined}
      />,
    )
    expect(screen.getByLabelText('Choose')).toHaveAttribute('aria-required', 'true')
  })

  it('shows the placeholder on the trigger when no option matches', () => {
    render(
      <Select
        aria-label="Choose"
        value=""
        options={OPTIONS}
        placeholder="请选择"
        onChange={() => undefined}
      />,
    )
    expect(screen.getByLabelText('Choose')).toHaveTextContent('请选择')
  })
})
