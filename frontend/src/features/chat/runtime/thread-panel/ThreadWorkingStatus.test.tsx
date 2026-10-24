import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ThreadWorkingStatus } from '@/features/chat/runtime/thread-panel/ThreadWorkingStatus'

describe('ThreadWorkingStatus', () => {
  it('renders nothing when active is false', () => {
    const { container } = render(
      <ThreadWorkingStatus active={false} label="执行中" animated />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('renders active execution with animation enabled (STOPPED + still active)', () => {
    // 验证 STOPPED 独立控制下仍有 active 执行事实时，通过 animated=true 保留真实执行动画
    render(
      <ThreadWorkingStatus
        active
        label="执行中"
        animated
      />,
    )
    const working = screen.getByText('执行中').closest('.thread-working')
    expect(working).toHaveAttribute('data-animated', 'true')
    const dot = working?.querySelector('.thread-working-dot')
    expect(dot).toBeInTheDocument()
    expect(dot).not.toHaveClass('is-static')
    expect(dot?.getAttribute('style') ?? '').not.toContain('animation: none')
  })

  it('renders WAITING without generation animation', () => {
    // 验证 WAITING（等待异步结果）必须准确展示文字且默认/显式无动画
    render(
      <ThreadWorkingStatus
        active
        label="等待异步结果"
        animated={false}
      />,
    )
    const working = screen.getByText('等待异步结果').closest('.thread-working')
    expect(working).toHaveAttribute('data-animated', 'false')
    const dot = working?.querySelector('.thread-working-dot')
    expect(dot).toHaveClass('is-static')
    expect(dot?.getAttribute('style')).toContain('animation: none')
  })

  it('renders approval blockage without animation', () => {
    // 验证审批阻塞必须准确展示“等待审批”且无动画
    render(
      <ThreadWorkingStatus
        active
        label="等待审批"
        animated={false}
      />,
    )
    const working = screen.getByText('等待审批').closest('.thread-working')
    expect(working).toHaveAttribute('data-animated', 'false')
    const dot = working?.querySelector('.thread-working-dot')
    expect(dot).toHaveClass('is-static')
    expect(dot?.getAttribute('style')).toContain('animation: none')
  })

  it('renders environment blockage without animation', () => {
    // 验证环境阻塞必须准确展示“等待环境 archlinux 上线”且无动画
    render(
      <ThreadWorkingStatus
        active
        label="等待环境 archlinux 上线"
        animated={false}
      />,
    )
    const working = screen.getByText('等待环境 archlinux 上线').closest('.thread-working')
    expect(working).toHaveAttribute('data-animated', 'false')
    const dot = working?.querySelector('.thread-working-dot')
    expect(dot).toHaveClass('is-static')
    expect(dot?.getAttribute('style')).toContain('animation: none')
  })

  it('renders input blockage without animation', () => {
    // 验证问卷/输入阻塞准确展示“等待输入”且无动画
    render(
      <ThreadWorkingStatus
        active
        label="等待输入"
        animated={false}
      />,
    )
    const working = screen.getByText('等待输入').closest('.thread-working')
    expect(working).toHaveAttribute('data-animated', 'false')
    const dot = working?.querySelector('.thread-working-dot')
    expect(dot).toHaveClass('is-static')
    expect(dot?.getAttribute('style')).toContain('animation: none')
  })

  it('renders RUNNING without active model/tool as running and without animation', () => {
    // 验证无 model/tool 且 RUNNING 必须展示“运行中”，绝不能直接显示生成，且无动画
    render(
      <ThreadWorkingStatus
        active
        label="运行中"
        animated={false}
      />,
    )
    const working = screen.getByText('运行中').closest('.thread-working')
    expect(working).not.toHaveTextContent('生成')
    expect(working).toHaveAttribute('data-animated', 'false')
    const dot = working?.querySelector('.thread-working-dot')
    expect(dot).toHaveClass('is-static')
    expect(dot?.getAttribute('style')).toContain('animation: none')
  })

  it('defaults to animated=false when omitted', () => {
    // 验证默认非动画机制
    render(
      <ThreadWorkingStatus
        active
        label="等待审批"
      />,
    )
    const working = screen.getByText('等待审批').closest('.thread-working')
    expect(working).toHaveAttribute('data-animated', 'false')
    const dot = working?.querySelector('.thread-working-dot')
    expect(dot).toHaveClass('is-static')
  })
})
