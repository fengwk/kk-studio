import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { TagInput } from './TagInput'

describe('TagInput', () => {
  it('renders committed chips and allows removing a chip while preserving pending text', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <TagInput
        value={[408, 429, '500']}
        onChange={onChange}
        min={400}
        max={599}
        ariaLabel="HTTP status codes"
      />,
    )

    expect(screen.getByText('408')).toBeInTheDocument()
    expect(screen.getByText('429')).toBeInTheDocument()
    // The input displays the pending text
    expect(screen.getByRole('textbox')).toHaveValue('500')

    const removeBtn = screen.getByLabelText('移除 429')
    await user.click(removeBtn)

    // Removing 429 preserves pending text '500'
    expect(onChange).toHaveBeenCalledWith([408, '500'])
  })

  it('immediately propagates staged input on every keystroke without Enter', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <TagInput
        value={[408]}
        onChange={onChange}
        min={400}
        max={599}
        ariaLabel="HTTP status codes"
      />,
    )

    const input = screen.getByRole('textbox')
    await user.type(input, '2')
    expect(onChange).toHaveBeenCalledWith([408, '2'])
  })

  it('types invalid then corrects and presses enter to commit', async () => {
    const user = userEvent.setup()

    function ControlledTagInput() {
      const [value, setValue] = useState<(number | string)[]>([408])
      return (
        <TagInput
          value={value}
          onChange={setValue}
          min={400}
          max={599}
          ariaLabel="HTTP status codes"
        />
      )
    }

    render(<ControlledTagInput />)
    const input = screen.getByRole('textbox')

    // Type invalid status code 200 and press Enter
    await user.type(input, '200{enter}')
    expect(screen.getByRole('alert')).toHaveTextContent('200')
    // Chips still only 408; 200 stays in the input
    expect(screen.getByText('408')).toBeInTheDocument()
    expect(input).toHaveValue('200')

    // Correct to 500 and press Enter
    await user.clear(input)
    await user.type(input, '500{enter}')
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByText('500')).toBeInTheDocument()
    expect(input).toHaveValue('')
  })

  it('detects duplicate on commit and keeps pending raw text with error', async () => {
    const user = userEvent.setup()

    function ControlledTagInput() {
      const [value, setValue] = useState<(number | string)[]>([429])
      return (
        <TagInput
          value={value}
          onChange={setValue}
          min={400}
          max={599}
          ariaLabel="HTTP status codes"
        />
      )
    }

    render(<ControlledTagInput />)
    const input = screen.getByRole('textbox')

    // Type duplicate 429 and press Enter
    await user.type(input, '429{enter}')
    expect(screen.getByRole('alert')).toHaveTextContent('429')
    expect(input).toHaveValue('429')
  })

  it('commits single valid input on blur and handles paste batch all-or-nothing', async () => {
    const user = userEvent.setup()

    function ControlledTagInput() {
      const [value, setValue] = useState<(number | string)[]>([408])
      return (
        <TagInput
          value={value}
          onChange={setValue}
          min={400}
          max={599}
          ariaLabel="HTTP status codes"
        />
      )
    }

    render(<ControlledTagInput />)
    const input = screen.getByRole('textbox')

    // Type 429 and blur
    await user.type(input, '429')
    fireEvent.blur(input)
    expect(screen.getByText('429')).toBeInTheDocument()
    expect(input).toHaveValue('')

    // Paste invalid batch containing 600 (out of range) -> all-or-nothing: none committed
    fireEvent.paste(input, {
      clipboardData: {
        getData: () => '500, 600',
      },
    })
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.queryByText('500')).toBeNull()
    expect(input).toHaveValue('500, 600')

    // Paste valid batch -> all committed
    await user.clear(input)
    fireEvent.paste(input, {
      clipboardData: {
        getData: () => '500, 502, 503',
      },
    })
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByText('500')).toBeInTheDocument()
    expect(screen.getByText('502')).toBeInTheDocument()
    expect(screen.getByText('503')).toBeInTheDocument()
    expect(input).toHaveValue('')
  })

  it('resets input value and clears error upon external value reset', async () => {
    const user = userEvent.setup()

    function ResettableHarness() {
      const [value, setValue] = useState<(number | string)[]>([408])
      return (
        <>
          <button type="button" onClick={() => setValue([408, 429])}>
            Reset
          </button>
          <TagInput
            value={value}
            onChange={setValue}
            min={400}
            max={599}
            ariaLabel="HTTP status codes"
          />
        </>
      )
    }

    render(<ResettableHarness />)
    const input = screen.getByRole('textbox')

    // Trigger an error
    await user.type(input, '200{enter}')
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(input).toHaveValue('200')

    // Click external reset
    await user.click(screen.getByText('Reset'))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(input).toHaveValue('')
    expect(screen.getByText('408')).toBeInTheDocument()
    expect(screen.getByText('429')).toBeInTheDocument()
  })

  it('clears pending text when input is cleared or whitespace is committed', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <TagInput
        value={[408, '500']}
        onChange={onChange}
        min={400}
        max={599}
        ariaLabel="HTTP status codes"
      />,
    )

    const input = screen.getByRole('textbox')
    // Clear the input
    await user.clear(input)
    expect(onChange).toHaveBeenCalledWith([408])

    // Commit empty spaces
    onChange.mockClear()
    render(
      <TagInput
        value={[408, '   ']}
        onChange={onChange}
        min={400}
        max={599}
        ariaLabel="HTTP status codes"
      />,
    )
    const inputs = screen.getAllByRole('textbox')
    const secondInput = inputs[1]!
    await user.type(secondInput, '{enter}')
    expect(onChange).toHaveBeenCalledWith([408])
  })

  it('removes the last committed chip on backspace when input is empty', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <TagInput
        value={[408, 429]}
        onChange={onChange}
        min={400}
        max={599}
        ariaLabel="HTTP status codes"
      />,
    )

    const input = screen.getByRole('textbox')
    expect(input).toHaveValue('')
    await user.type(input, '{backspace}')
    expect(onChange).toHaveBeenCalledWith([408])
  })

  it('uses class name ui-tag-input-field for the inner input element', () => {
    render(
      <TagInput
        value={[408]}
        onChange={() => undefined}
        min={400}
        max={599}
        ariaLabel="HTTP status codes"
      />,
    )
    const input = screen.getByRole('textbox')
    expect(input).toHaveClass('ui-tag-input-field')
    expect(input).not.toHaveClass('ui-tag-inline-input')
  })

  it('ignores Enter key during IME composition and commits only after composition completes', async () => {
    const user = userEvent.setup()
    function ControlledTagInput() {
      const [value, setValue] = useState<(number | string)[]>([408])
      return (
        <TagInput
          value={value}
          onChange={setValue}
          min={400}
          max={599}
          ariaLabel="HTTP status codes"
        />
      )
    }

    render(<ControlledTagInput />)
    const input = screen.getByRole('textbox')

    // Simulate typing 502
    await user.type(input, '502')

    // Fire Enter with isComposing = true (e.g. confirming IME candidate)
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
    // Chip 502 should NOT be committed yet
    expect(screen.queryByText('502')).toBeNull()
    expect(input).toHaveValue('502')

    // Fire Enter with Process key (some IMEs)
    fireEvent.keyDown(input, { key: 'Process', isComposing: true })
    expect(screen.queryByText('502')).toBeNull()

    // Now press normal Enter (isComposing = false)
    fireEvent.keyDown(input, { key: 'Enter', isComposing: false })
    expect(screen.getByText('502')).toBeInTheDocument()
    expect(input).toHaveValue('')
  })

  it('prevents accidental submission of outer form when pressing Enter', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn((e) => e.preventDefault())

    function FormWithTagInput() {
      const [value, setValue] = useState<(number | string)[]>([408])
      return (
        <form onSubmit={onSubmit}>
          <TagInput
            value={value}
            onChange={setValue}
            min={400}
            max={599}
            ariaLabel="HTTP status codes"
          />
          <button type="submit">Submit</button>
        </form>
      )
    }

    render(<FormWithTagInput />)
    const input = screen.getByRole('textbox')

    // Press Enter with a code
    await user.type(input, '503{enter}')
    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByText('503')).toBeInTheDocument()

    // Press Enter on empty input
    await user.type(input, '{enter}')
    expect(onSubmit).not.toHaveBeenCalled()
  })
})
