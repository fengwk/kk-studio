import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'

describe('StateBlock', () => {
  it('renders the title as static text for neutral states', () => {
    render(<StateBlock title="正在加载资源" />)

    const block = screen.getByText('正在加载资源')
    expect(block).toHaveClass('state-block')
    expect(block).not.toHaveClass('danger')
    // 中性状态不主动播报，保持原有的非 live region 行为。
    expect(block).not.toHaveAttribute('role')
  })

  // 失败是必须主动播报的状态：读屏用户不能在无声的列表失败里继续操作。
  it('announces failures with the alert role', () => {
    render(<StateBlock title="资源加载失败" tone="danger" />)

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('资源加载失败')
    expect(alert).toHaveClass('state-block', 'danger')
  })

  it('renders the optional action next to the title', () => {
    render(
      <StateBlock
        title="资源加载失败"
        tone="danger"
        action={<button type="button">重试</button>}
      />,
    )

    expect(screen.getByRole('alert')).toContainElement(
      screen.getByRole('button', { name: '重试' }),
    )
  })
})
