import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ResourceCardSkeleton } from '@/shared/ui/cards/ResourceCardSkeleton'

describe('ResourceCardSkeleton', () => {
  // 装饰卡不可聚焦，加载说明由一个状态区域承载。
  it('exposes only one loading status', () => {
    const { container } = render(<ResourceCardSkeleton label="加载资源中..." />)
    const status = screen.getByRole('status')
    expect(status).toHaveAttribute('aria-busy', 'true')
    expect(status).toHaveTextContent('加载资源中...')
    expect(container.querySelector('.resource-card-skeleton')).toHaveAttribute('aria-hidden', 'true')
    expect(container.querySelector('button, a, input, [tabindex]')).toBeNull()
  })

  // 页面自定义列宽时，骨架直接读取同一个栅格的实际列数。
  it('uses the resolved columns on the caller’s grid', () => {
    const styles = vi.spyOn(window, 'getComputedStyle').mockReturnValue({
      gridTemplateColumns: '280px 280px 280px 280px',
    } as CSSStyleDeclaration)
    try {
      const { container } = render(<ResourceCardSkeleton className="canvas-library-grid" />)
      expect(container.querySelectorAll('.resource-grid.canvas-library-grid .resource-card-skeleton')).toHaveLength(4)
    } finally {
      styles.mockRestore()
    }
  })
})
