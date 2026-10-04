import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { chooseSelectOption } from '@/test-support/chooseSelectOption'
import { Select } from '@/shared/ui/console/Select'

const OPTIONS = [
  { value: 'a', label: 'Alpha' },
  { value: 'b', label: 'Bravo' },
  { value: 'c', label: 'Charlie', disabled: true },
]

describe('Select', () => {
  afterEach(() => vi.restoreAllMocks())
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

  it('refocuses the trigger when Escape closes the listbox', async () => {
    const user = userEvent.setup()
    render(
      <Select aria-label="Choose" value="a" options={OPTIONS} onChange={() => undefined} />,
    )
    const trigger = screen.getByLabelText('Choose')
    await user.click(trigger)
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })

  it('closes on outside pointerdown without moving focus', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <div>
        <Select aria-label="Choose" value="a" options={OPTIONS} onChange={onChange} />
        <button type="button">Outside</button>
      </div>,
    )
    const trigger = screen.getByLabelText('Choose')
    await user.click(trigger)
    await user.click(screen.getByText('Outside'))
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()
  })

  it('closes when focus moves outside the control and the portal menu', async () => {
    const user = userEvent.setup()
    render(
      <div>
        <Select aria-label="Choose" value="a" options={OPTIONS} onChange={() => undefined} />
        <button type="button">Outside</button>
      </div>,
    )
    await user.click(screen.getByLabelText('Choose'))
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    // 菜单 portal 到 body 后，焦点移到触发按钮与菜单之外的元素仍应关闭。
    await user.tab()
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(screen.getByText('Outside')).toHaveFocus()
  })

  it('renders the listbox as a body-level portal outside the control root', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <Select aria-label="Choose" value="a" options={OPTIONS} onChange={() => undefined} />,
    )
    await user.click(screen.getByLabelText('Choose'))
    const listbox = screen.getByRole('listbox')
    expect(container.contains(listbox)).toBe(false)
    expect(document.body.contains(listbox)).toBe(true)
  })

  it('repositions the portal menu from the trigger rect when the viewport changes', async () => {
    const user = userEvent.setup()
    render(<Select aria-label="Choose" value="a" options={OPTIONS} onChange={() => undefined} />)
    const trigger = screen.getByLabelText('Choose')
    const rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(
      { top: 100, bottom: 136, left: 40, right: 240, width: 200, height: 36, x: 40, y: 100 } as DOMRect,
    )
    await user.click(trigger)
    const listbox = screen.getByRole('listbox')
    expect(listbox).toHaveStyle({ position: 'fixed', left: '40px', top: '142px', minWidth: '200px' })
    // 触发按钮上移后，菜单需随滚动事件重新贴合，而不是停留在旧坐标。
    rectSpy.mockReturnValue(
      { top: 20, bottom: 56, left: 40, right: 240, width: 200, height: 36, x: 40, y: 20 } as DOMRect,
    )
    window.dispatchEvent(new Event('scroll'))
    await waitFor(() => expect(listbox).toHaveStyle({ top: '62px' }))
  })

  it('flips the portal menu above the trigger when there is not enough room below', async () => {
    const user = userEvent.setup()
    render(<Select aria-label="Choose" value="a" options={OPTIONS} onChange={() => undefined} />)
    const trigger = screen.getByLabelText('Choose')
    // 视口高 768，触发按钮贴近底部：下方空间不足，菜单应改为贴上方定位。
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(
      { top: 700, bottom: 740, left: 40, right: 240, width: 200, height: 40, x: 40, y: 700 } as DOMRect,
    )
    await user.click(trigger)
    const listbox = screen.getByRole('listbox')
    expect(listbox).toHaveStyle({ position: 'fixed', bottom: '74px' })
  })

  it('opens with Space and confirms the active option with Space', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <Select aria-label="Choose" value="a" options={OPTIONS} onChange={onChange} />,
    )
    const trigger = screen.getByLabelText('Choose')
    trigger.focus()
    await user.keyboard(' ')
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    await user.keyboard(' ')
    expect(onChange).toHaveBeenCalledWith('a')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })

  it('wraps ArrowDown navigation past the end and skips disabled options', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <Select aria-label="Choose" value="a" options={OPTIONS} onChange={onChange} />,
    )
    await user.click(screen.getByLabelText('Choose'))
    const options = screen.getAllByRole('option')
    expect(options[0]).toHaveFocus()
    await user.keyboard('{ArrowDown}')
    await user.keyboard('{ArrowDown}')
    await user.keyboard('{ArrowDown}')
    expect(options[1]).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(onChange).toHaveBeenCalledWith('b')
  })

  it('wraps ArrowUp navigation past the start and skips disabled options', async () => {
    const user = userEvent.setup()
    render(
      <Select aria-label="Choose" value="b" options={OPTIONS} onChange={() => undefined} />,
    )
    await user.click(screen.getByLabelText('Choose'))
    const options = screen.getAllByRole('option')
    expect(options[1]).toHaveFocus()
    await user.keyboard('{ArrowUp}')
    expect(options[0]).toHaveFocus()
    await user.keyboard('{ArrowUp}')
    expect(options[1]).toHaveFocus()
    await user.keyboard('{ArrowUp}')
    expect(options[0]).toHaveFocus()
  })

  it('keeps the listbox open when a disabled option is clicked', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(
      <Select aria-label="Choose" value="a" options={OPTIONS} onChange={onChange} />,
    )
    await user.click(screen.getByLabelText('Choose'))
    const disabledOption = screen.getByRole('option', { name: 'Charlie' })
    await user.click(disabledOption)
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()
  })

  it('uses the default placeholder text when no placeholder is provided', () => {
    render(
      <Select aria-label="Choose" value="" options={OPTIONS} onChange={() => undefined} />,
    )
    expect(screen.getByLabelText('Choose')).toHaveTextContent('请选择')
  })

  it('applies the placeholder class when no option matches', () => {
    render(
      <Select
        aria-label="Choose"
        value=""
        options={OPTIONS}
        placeholder="请选择"
        onChange={() => undefined}
      />,
    )
    expect(document.querySelector('.ui-select')).toHaveClass('is-placeholder')
  })

  it('forwards className to the root element', () => {
    render(
      <Select
        aria-label="Choose"
        value="a"
        options={OPTIONS}
        className="my-select"
        onChange={() => undefined}
      />,
    )
    expect(document.querySelector('.ui-select')).toHaveClass('my-select')
  })
})
