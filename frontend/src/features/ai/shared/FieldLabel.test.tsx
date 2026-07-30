import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { FieldLabel } from '@/features/ai/shared/FieldLabel'

describe('FieldLabel', () => {
  it('only renders the required marker for required fields', () => {
    const { rerender } = render(<FieldLabel>普通字段</FieldLabel>)

    expect(screen.getByText('普通字段')).toBeInTheDocument()
    expect(screen.queryByText('*')).not.toBeInTheDocument()

    rerender(<FieldLabel required>必填字段</FieldLabel>)

    expect(screen.getByText('*')).toHaveAttribute('aria-hidden', 'true')
  })
})
