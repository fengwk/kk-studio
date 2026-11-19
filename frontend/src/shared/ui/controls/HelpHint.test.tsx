import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { HelpHint } from './HelpHint'

describe('HelpHint', () => {
  // 测试意图：说明文本可由键盘焦点唤出，并可通过 Escape 与失焦关闭。
  it('shows a linked tooltip on focus and closes it with Escape or blur', async () => {
    const user = userEvent.setup()
    render(<HelpHint label="帮助：测试说明">这是一段帮助说明</HelpHint>)

    const trigger = screen.getByRole('button', { name: '帮助：测试说明' })
    await user.tab()
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    const tooltip = screen.getByRole('tooltip')
    expect(trigger).toHaveAttribute('aria-describedby', tooltip.id)
    expect(tooltip).toHaveTextContent('这是一段帮助说明')

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
    expect(trigger).toHaveAttribute('aria-expanded', 'false')

    trigger.blur()
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  // 测试意图：触屏式点击能开关提示，且外部点击关闭而不会改变提示内容。
  it('toggles on touch and dismisses when clicking outside', () => {
    render(
      <>
        <HelpHint label="帮助：触屏提示">触屏说明</HelpHint>
        <button type="button">外部操作</button>
      </>,
    )
    const trigger = screen.getByRole('button', { name: '帮助：触屏提示' })

    fireEvent.pointerDown(trigger, { pointerType: 'touch' })
    fireEvent.click(trigger, { detail: 1 })
    expect(screen.getByRole('tooltip')).toHaveTextContent('触屏说明')

    fireEvent.pointerDown(screen.getByRole('button', { name: '外部操作' }), { pointerType: 'touch' })
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  // 测试意图：取消的触屏按压不应让下一次键盘焦点被误判为触屏焦点。
  it('opens on keyboard focus after a canceled touch pointer', async () => {
    const user = userEvent.setup()
    render(<HelpHint label="帮助：取消触屏">键盘说明</HelpHint>)
    const trigger = screen.getByRole('button', { name: '帮助：取消触屏' })

    fireEvent.pointerDown(trigger, { pointerType: 'touch' })
    trigger.dispatchEvent(new Event('pointercancel', { bubbles: true }))
    await user.tab()

    expect(trigger).toHaveFocus()
    expect(screen.getByRole('tooltip')).toHaveTextContent('键盘说明')
  })

  // 测试意图：关闭已打开的提示时不把焦点从当前操作控件抢回触发器。
  it('does not move focus to the trigger when Escape closes an open tooltip', async () => {
    const user = userEvent.setup()
    render(
      <>
        <HelpHint label="帮助：提示">提示内容</HelpHint>
        <button type="button">当前操作</button>
      </>,
    )
    const trigger = screen.getByRole('button', { name: '帮助：提示' })
    const currentAction = screen.getByRole('button', { name: '当前操作' })

    fireEvent.pointerDown(trigger, { pointerType: 'mouse' })
    fireEvent.click(trigger)
    currentAction.focus()
    await user.keyboard('{Escape}')

    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
    expect(currentAction).toHaveFocus()
  })
})
