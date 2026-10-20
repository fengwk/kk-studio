import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ApplicationContributionsProvider } from '@/platform/extensions/ApplicationContributionsContext'
import { OverlayHost } from '@/platform/workbench/WorkbenchSlots'
import { makeApplicationContributions } from '@/test-support/application-contributions'

describe('OverlayHost', () => {
  it('renders dialogs and overlays from the static contribution table', () => {
    const contributions = makeApplicationContributions({
      dialogs: [{ id: 'test.dialog', component: () => <div data-testid="dialog">Dialog content</div> }],
      overlays: [{ id: 'test.overlay', component: () => <div data-testid="overlay">Overlay content</div> }],
    })

    render(
      <ApplicationContributionsProvider contributions={contributions}>
        <OverlayHost />
      </ApplicationContributionsProvider>,
    )

    expect(screen.getByTestId('dialog')).toHaveTextContent('Dialog content')
    expect(screen.getByTestId('overlay')).toHaveTextContent('Overlay content')
  })
})
