import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { CatalogSetupGuide } from '@/features/chat/CatalogSetupGuide'
import { isCatalogSetupComplete } from '@/features/chat/catalog-setup'
import { setLocale } from '@/shared/i18n'

function renderGuide(state: { hasProvider: boolean; hasModel: boolean; hasAgent: boolean }) {
  return render(
    <MemoryRouter>
      <CatalogSetupGuide state={state} />
    </MemoryRouter>,
  )
}

describe('CatalogSetupGuide', () => {
  beforeEach(() => setLocale('en-US'))

  it('starts a fresh catalog at the Provider step', () => {
    renderGuide({ hasProvider: false, hasModel: false, hasAgent: false })

    const steps = screen.getAllByRole('listitem')
    expect(steps.map((step) => step.dataset.state)).toEqual([
      'current',
      'pending',
      'pending',
      'pending',
    ])
    expect(within(steps[0]).getByRole('link', { name: 'Add Provider' })).toHaveAttribute(
      'href',
      '/providers',
    )
  })

  it('marks finished steps done and highlights the next one', () => {
    renderGuide({ hasProvider: true, hasModel: true, hasAgent: false })

    const steps = screen.getAllByRole('listitem')
    expect(within(steps[0]).getByText('Done')).toBeInTheDocument()
    expect(within(steps[1]).queryByRole('link')).not.toBeInTheDocument()
    expect(steps[2].dataset.state).toBe('current')
    expect(within(steps[2]).getByRole('link', { name: 'Create Agent' })).toHaveAttribute(
      'href',
      '/agents',
    )
  })

  it('is complete only when every catalog step is done', () => {
    expect(isCatalogSetupComplete({ hasProvider: true, hasModel: true, hasAgent: true })).toBe(true)
    expect(isCatalogSetupComplete({ hasProvider: true, hasModel: false, hasAgent: true })).toBe(
      false,
    )
  })
})
