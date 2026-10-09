import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { TagInput } from './TagInput'

describe('TagInput', () => {
  it('renders existing tags and allows removing a tag', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<TagInput value={[408, 429, 500]} onChange={onChange} ariaLabel="HTTP status codes" />)

    expect(screen.getByText('408')).toBeInTheDocument()
    expect(screen.getByText('429')).toBeInTheDocument()
    expect(screen.getByText('500')).toBeInTheDocument()

    const removeBtn = screen.getByLabelText('Remove 429')
    await user.click(removeBtn)

    expect(onChange).toHaveBeenCalledWith([408, 500])
  })

  it('adds tags via Enter and comma', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<TagInput value={[429]} onChange={onChange} ariaLabel="HTTP status codes" />)

    const input = screen.getByRole('textbox')
    await user.type(input, '500{enter}')
    expect(onChange).toHaveBeenCalledWith([429, 500])

    onChange.mockClear()
    await user.type(input, '502,')
    expect(onChange).toHaveBeenCalledWith([429, 502])
  })

  it('parses pasted tokens with mixed commas and whitespace', async () => {
    const onChange = vi.fn()
    render(<TagInput value={[429]} onChange={onChange} ariaLabel="HTTP status codes" />)

    const input = screen.getByRole('textbox')
    fireEvent.paste(input, {
      clipboardData: {
        getData: () => '500, 502   503,504',
      },
    })

    expect(onChange).toHaveBeenCalledWith([429, 500, 502, 503, 504])
  })

  it('rejects duplicate status codes and flags invalid tag', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<TagInput value={[429]} onChange={onChange} ariaLabel="HTTP status codes" />)

    const input = screen.getByRole('textbox')
    await user.type(input, '429{enter}')
    expect(onChange).toHaveBeenCalledWith([429, '429'])
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })

  it('rejects out-of-range status codes and flags invalid tag', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<TagInput value={[]} onChange={onChange} min={400} max={599} ariaLabel="HTTP status codes" />)

    const input = screen.getByRole('textbox')
    await user.type(input, '200{enter}')
    expect(onChange).toHaveBeenCalledWith(['200'])
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })

  it('removes the last tag on backspace when input is empty', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<TagInput value={[429, 500]} onChange={onChange} ariaLabel="HTTP status codes" />)

    const input = screen.getByRole('textbox')
    await user.type(input, '{backspace}')
    expect(onChange).toHaveBeenCalledWith([429])
  })
})
