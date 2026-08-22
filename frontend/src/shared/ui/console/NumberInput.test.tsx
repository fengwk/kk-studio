import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { NumberInput } from '@/shared/ui/console/NumberInput'

/**
 * 受控 Harness：保持与生产用法一致（父组件持有 value），
 * 使 nudge/键入后组件能拿到最新值继续计算。
 */
function ControlledNumberInput(props: {
  initial: string
  min?: number
  max?: number
  step?: number
  onValue?: (value: string) => void
}) {
  const [value, setValue] = useState(props.initial)
  const updateValue = (next: string) => {
    setValue(next)
    props.onValue?.(next)
  }
  return (
    <NumberInput
      value={value}
      min={props.min}
      max={props.max}
      step={props.step}
      onChange={updateValue}
    />
  )
}

describe('NumberInput', () => {
  it('sanitizes free-form typing to digits only', async () => {
    const user = userEvent.setup()
    render(<ControlledNumberInput initial="" />)

    const field = screen.getByRole('textbox')
    await user.type(field, '1a2-b.3')

    // 非数字字符被剥掉，只保留数字。
    expect(field).toHaveValue('123')
  })

  it('nudges with ArrowUp/ArrowDown using the step', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<ControlledNumberInput initial="5" step={2} onValue={onChange} />)

    const field = screen.getByRole('textbox')
    await user.click(field)
    await user.keyboard('{ArrowUp}{ArrowUp}{ArrowDown}')

    expect(onChange.mock.calls.map(([next]) => next)).toEqual(['7', '9', '7'])
  })

  it('nudges through both stepper buttons', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<ControlledNumberInput initial="5" onValue={onChange} />)

    await user.click(screen.getByRole('button', { name: '增加' }))
    expect(onChange).toHaveBeenLastCalledWith('6')
    await user.click(screen.getByRole('button', { name: '减少' }))
    expect(onChange).toHaveBeenLastCalledWith('5')
  })

  it('treats an empty value as the min boundary on increment and max on decrement', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<NumberInput value="  " min={10} max={20} onChange={onChange} />)

    const field = screen.getByRole('textbox')
    await user.click(field)
    await user.keyboard('{ArrowUp}')
    expect(onChange).toHaveBeenLastCalledWith('10')

    await user.keyboard('{ArrowDown}')
    expect(onChange).toHaveBeenLastCalledWith('20')
  })

  it('clamps nudges to min/max', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <ControlledNumberInput
        initial="8"
        min={0}
        max={10}
        onValue={onChange}
      />,
    )

    const field = screen.getByRole('textbox')
    await user.click(field)
    await user.keyboard('{ArrowUp}{ArrowUp}{ArrowUp}')
    expect(onChange.mock.calls.map(([next]) => next)).toEqual(['9', '10', '10'])

    await user.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}')
    expect(onChange.mock.calls.map(([next]) => next)).toEqual([
      '9', '10', '10', '9', '8', '7', '6', '5',
    ])
  })

  it('falls back to bounds when the typed value is not a number', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const { rerender } = render(
      <NumberInput value="abc" min={3} max={7} onChange={onChange} />,
    )

    const field = screen.getByRole('textbox')
    await user.click(field)
    await user.keyboard('{ArrowUp}')
    expect(onChange).toHaveBeenLastCalledWith('3')

    rerender(<NumberInput value="abc" min={3} max={7} onChange={onChange} />)
    await user.keyboard('{ArrowDown}')
    expect(onChange.mock.calls.map(([next]) => next)).toEqual(['3', '7'])
  })

  it('disables the field and both steppers when disabled', () => {
    render(<NumberInput value="5" disabled onChange={() => undefined} />)

    expect(screen.getByRole('textbox')).toBeDisabled()
    expect(screen.getByRole('button', { name: '增加' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '减少' })).toBeDisabled()
  })

  it('forwards id and ARIA attributes to the input', () => {
    render(
      <NumberInput
        id="volume"
        value="5"
        aria-label="Volume"
        aria-describedby="volume-hint"
        onChange={() => undefined}
      />,
    )

    const field = screen.getByLabelText('Volume')
    expect(field).toHaveAttribute('id', 'volume')
    expect(field).toHaveAttribute('aria-describedby', 'volume-hint')
  })

  it('does not intercept unrelated keys', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<NumberInput value="5" onChange={onChange} />)

    const field = screen.getByRole('textbox')
    await user.click(field)
    await user.keyboard('a')

    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenLastCalledWith('5')
  })
})
