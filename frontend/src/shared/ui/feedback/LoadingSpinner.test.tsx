import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { LoadingIndicator, LoadingSpinner } from '@/shared/ui/feedback/LoadingSpinner'

describe('LoadingSpinner', () => {
  it('renders with role="status" and aria-busy="true"', () => {
    render(<LoadingSpinner label="正在载入" />)
    const spinner = screen.getByRole('status')
    expect(spinner).toHaveAttribute('aria-busy', 'true')
    expect(spinner).toHaveClass('ui-loading-spinner')
    expect(spinner.querySelector('.ui-loading-spinner-icon')).toBeInTheDocument()
    expect(screen.getByText('正在载入')).toHaveClass('sr-only')
  })

  it('supports different sizes', () => {
    const { rerender } = render(<LoadingSpinner size="sm" />)
    expect(screen.getByRole('status').querySelector('svg')).toHaveAttribute('width', '14')

    rerender(<LoadingSpinner size="lg" />)
    expect(screen.getByRole('status').querySelector('svg')).toHaveAttribute('width', '24')

    rerender(<LoadingSpinner size={20} />)
    expect(screen.getByRole('status').querySelector('svg')).toHaveAttribute('width', '20')
  })
})

describe('LoadingIndicator', () => {
  it('renders inline or block indicator with visible label and status semantics', () => {
    render(<LoadingIndicator label="正在加载配置..." />)
    const indicator = screen.getByRole('status')
    expect(indicator).toHaveAttribute('aria-busy', 'true')
    expect(indicator).toHaveClass('ui-loading-indicator')
    expect(screen.getByText('正在加载配置...')).toHaveClass('ui-loading-indicator-label')
  })
})
