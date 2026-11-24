import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setLocale } from '@/shared/i18n'
import { NameDialog } from './NameDialog'

describe('NameDialog', () => {
  beforeEach(() => setLocale('en-US'))

  it('requires a name and submits the trimmed value', async () => {
    const user = userEvent.setup()
    const onConfirm = vi.fn()
    render(<NameDialog title="命名" onConfirm={onConfirm} onCancel={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a name.')
    await user.type(screen.getByRole('textbox', { name: 'Name' }), '  新空间  ')
    await user.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(onConfirm).toHaveBeenCalledWith('新空间')
  })

  it('shows custom validation errors and cancels', async () => {
    const user = userEvent.setup()
    const onCancel = vi.fn()
    render(<NameDialog title="命名" validate={(name) => name === 'bad' ? '名称不可用' : undefined} onConfirm={vi.fn()} onCancel={onCancel} />)
    await user.type(screen.getByRole('textbox', { name: 'Name' }), 'bad')
    await user.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(screen.getByRole('alert')).toHaveTextContent('名称不可用')
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(onCancel).toHaveBeenCalledOnce()
  })
})
