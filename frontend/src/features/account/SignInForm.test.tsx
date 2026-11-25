import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { SignInForm } from './SignInForm'

describe('SignInForm failure messages', () => {
  // Only the credential rejection is intentionally shown without the original message.
  it.each([
    { error: new ApiError('Unauthorized', 401, undefined, undefined, 'INVALID_CREDENTIALS'), message: '用户名或密码错误。' },
    { error: new ApiError('Session expired', 401, undefined, undefined, 'NOT_AUTHENTICATED'), message: 'Session expired' },
    { error: new ApiError('Origin is not allowed', 403, undefined, undefined, 'INVALID_ORIGIN'), message: 'Origin is not allowed' },
    { error: new ApiError('Service unavailable', 503), message: 'Service unavailable' },
    { error: new TypeError('Failed to fetch'), message: 'Failed to fetch' },
  ])('shows $message for the corresponding failure', async ({ error, message }) => {
    const user = userEvent.setup()
    const signIn = vi.fn().mockRejectedValue(error)
    render(<SignInForm signIn={signIn} />)
    await user.type(screen.getByLabelText('用户名'), 'reader')
    await user.type(screen.getByLabelText('密码'), 'example-password')
    await user.click(screen.getByRole('button', { name: '登录' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(message)
  })
})
