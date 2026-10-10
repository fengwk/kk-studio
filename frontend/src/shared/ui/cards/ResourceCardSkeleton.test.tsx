import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ResourceCardSkeleton } from '@/shared/ui/cards/ResourceCardSkeleton'

describe('ResourceCardSkeleton', () => {
  it('renders fixed 6 placeholder cards with status semantics and stable skeleton layout', () => {
    const { container } = render(<ResourceCardSkeleton label="加载资源中..." />)

    const statusRegion = screen.getByRole('status', { name: '加载资源中...' })
    expect(statusRegion).toHaveAttribute('aria-busy', 'true')
    expect(statusRegion).toHaveClass('resource-skeleton-container')

    const skeletonCards = container.querySelectorAll('.resource-card-skeleton')
    expect(skeletonCards.length).toBe(6)

    // 检查每个卡片包含头、元信息和动作区骨架
    skeletonCards.forEach((card) => {
      expect(card.querySelector('.skeleton-icon')).toBeInTheDocument()
      expect(card.querySelector('.skeleton-title')).toBeInTheDocument()
      expect(card.querySelector('.skeleton-subtitle')).toBeInTheDocument()
      expect(card.querySelectorAll('.skeleton-meta-row').length).toBe(3)
      expect(card.querySelectorAll('.skeleton-action').length).toBe(2)
    })
  })

  it('supports custom accessible label with default 6 fixed cards', () => {
    const { container } = render(<ResourceCardSkeleton label="加载模型..." />)
    expect(screen.getByRole('status', { name: '加载模型...' })).toBeInTheDocument()
    expect(container.querySelectorAll('.resource-card-skeleton').length).toBe(6)
  })
})
