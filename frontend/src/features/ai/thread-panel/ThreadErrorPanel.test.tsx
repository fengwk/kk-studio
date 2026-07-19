import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ThreadErrorPanel } from '@/features/ai/thread-panel/ThreadErrorPanel'

describe('ThreadErrorPanel', () => {
  it('hides blank errors and renders an optional dismiss action', async () => {
    const dismiss = vi.fn()
    const { rerender } = render(<ThreadErrorPanel message=" " />)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    rerender(<ThreadErrorPanel message="network failed" onDismiss={dismiss} />)
    await userEvent.setup().click(screen.getByRole('button', { name: '关闭错误' }))
    expect(dismiss).toHaveBeenCalledOnce()
    rerender(<ThreadErrorPanel message="network failed" />)
    expect(screen.queryByRole('button', { name: '关闭错误' })).not.toBeInTheDocument()
  })
})
