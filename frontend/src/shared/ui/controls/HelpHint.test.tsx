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

  // 测试意图：带交互内容必须使用 dialog/popover 语义，而不是 tooltip。
  it('uses popover semantics for interactive content', async () => {
    render(
      <HelpHint label="帮助：交互说明" interactive>
        <a href="#details">查看详情</a>
      </HelpHint>,
    )
    const user = userEvent.setup()
    const trigger = screen.getByRole('button', { name: '帮助：交互说明' })
    await user.tab()

    expect(screen.getByRole('dialog', { name: '帮助：交互说明' })).toContainElement(
      screen.getByRole('link', { name: '查看详情' }),
    )
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
    expect(trigger).toHaveAttribute('aria-haspopup', 'dialog')

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })
})
