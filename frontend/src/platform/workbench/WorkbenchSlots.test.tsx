import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'
import { ExtensionHostProvider } from '@/platform/extensions/ExtensionHostContext'
import { OverlayHost } from '@/platform/workbench/WorkbenchSlots'

describe('OverlayHost', () => {
  it('renders registered dialogs and overlays reactively', () => {
    const host = new ExtensionHost()
    host.register({
      id: 'test',
      dialogs: [{ id: 'test.dialog', component: () => <div data-testid="dialog">Dialog content</div> }],
      overlays: [{ id: 'test.overlay', component: () => <div data-testid="overlay">Overlay content</div> }],
    })

    render(
      <ExtensionHostProvider host={host}>
        <OverlayHost />
      </ExtensionHostProvider>,
    )

    expect(screen.getByTestId('dialog')).toHaveTextContent('Dialog content')
    expect(screen.getByTestId('overlay')).toHaveTextContent('Overlay content')
  })
})
