import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { HelpHint } from './HelpHint'

describe('HelpHint', () => {
  // 测试意图：? 按钮必须可聚焦、可键盘展开，并通过 aria 关联说明文本。
  it('toggles the linked note by keyboard and exposes accessible state', async () => {
    const user = userEvent.setup()
    render(
      <HelpHint label="帮助：测试说明">
        这是一段帮助说明
      </HelpHint>,
    )

    const trigger = screen.getByRole('button', { name: '帮助：测试说明' })
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('note')).not.toBeInTheDocument()

    trigger.focus()
    await user.keyboard('{Enter}')
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    expect(trigger).toHaveAttribute('aria-describedby', screen.getByRole('note').id)
    expect(screen.getByRole('note')).toHaveTextContent('这是一段帮助说明')

    await user.keyboard(' ')
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
  })
})
