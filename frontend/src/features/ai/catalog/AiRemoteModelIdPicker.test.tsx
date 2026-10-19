import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { RemoteModelIdPicker } from '@/features/ai/catalog/AiRemoteModelIdPicker'
import { setLocale } from '@/shared/i18n'

describe('RemoteModelIdPicker', () => {
  beforeEach(() => setLocale('en-US'))

  it('fills the datalist with the provider model ids', async () => {
    const load = vi.fn().mockResolvedValue(['m-a', 'm-b'])
    const { container } = render(
      <RemoteModelIdPicker listId="ids" providerName="openai" load={load} />,
    )

    await userEvent.click(screen.getByRole('button', { name: 'Fetch models from Provider' }))

    expect(load).toHaveBeenCalledWith('openai')
    expect(await screen.findByRole('status')).toHaveTextContent('2 models found')
    const options = container.querySelectorAll('datalist#ids option')
    expect(Array.from(options).map((option) => option.getAttribute('value'))).toEqual(['m-a', 'm-b'])
  })

  it('cannot fetch before a provider is chosen', () => {
    render(<RemoteModelIdPicker listId="ids" providerName="" load={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Fetch models from Provider' })).toBeDisabled()
  })

  it('shows the upstream detail when the provider rejects the request', async () => {
    const load = vi
      .fn()
      .mockRejectedValue(
        new ApiError('The agent provider service request failed.', 502, 'upstream_failed', {
          detail: 'Provider model list returned HTTP 401',
        }),
      )
    render(<RemoteModelIdPicker listId="ids" providerName="openai" load={load} />)

    await userEvent.click(screen.getByRole('button', { name: 'Fetch models from Provider' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The agent provider service request failed. Provider model list returned HTTP 401',
    )
  })

  it('drops results that belong to a previously selected provider', async () => {
    const load = vi.fn().mockResolvedValue([])
    const { rerender } = render(<RemoteModelIdPicker listId="ids" providerName="a" load={load} />)
    await userEvent.click(screen.getByRole('button', { name: 'Fetch models from Provider' }))
    expect(await screen.findByRole('status')).toHaveTextContent('The Provider listed no models.')

    rerender(<RemoteModelIdPicker listId="ids" providerName="b" load={load} />)
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument())
  })
})
